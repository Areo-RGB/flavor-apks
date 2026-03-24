package com.paul.sprintsync.sensor_native

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

class SensorNativeController(
    private val activity: FlutterActivity,
) : EventChannel.StreamHandler {
    companion object {
        private const val TAG = "SensorNativeController"
        private const val PREVIEW_REBIND_RETRY_DELAY_MS = 200L
        private const val PREVIEW_REBIND_MAX_ATTEMPTS = 3
        private const val HS_FALLBACK_TRIGGER_FPS = 90.0
        const val METHOD_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_methods"
        const val EVENT_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_events"
        const val PREVIEW_VIEW_TYPE = "com.paul.sprintsync/sensor_native_preview"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameDiffer = RoiFrameDiffer()
    private val offsetSmoother = SensorOffsetSmoother()
    private val fpsMonitor = SensorNativeFpsMonitor(lowFpsThreshold = HS_FALLBACK_TRIGGER_FPS)

    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    @Volatile
    private var monitoring = false

    @Volatile
    private var config: NativeMonitoringConfig = NativeMonitoringConfig.defaults()

    @Volatile
    private var streamFrameCount = 0L

    @Volatile
    private var processedFrameCount = 0L

    @Volatile
    private var hostSensorMinusElapsedNanos: Long? = null

    @Volatile
    private var gpsUtcOffsetNanos: Long? = null

    @Volatile
    private var gpsFixElapsedRealtimeNanos: Long? = null

    @Volatile
    private var requestedCameraFpsMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL

    @Volatile
    private var activeCameraFpsMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL

    @Volatile
    private var targetFpsUpper = 0

    @Volatile
    private var observedFps: Double? = null

    @Volatile
    private var hsDowngradeTriggered = false

    private var locationManager: LocationManager? = null
    private var previewView: TextureView? = null
    private var pendingPreviewRebindRunnable: Runnable? = null
    private var previewRebindAttemptCount = 0

    private val detectionMath = NativeDetectionMath(config)

    private val cameraSession: SensorNativeCameraSession by lazy {
        SensorNativeCameraSession(
            activity = activity,
            mainHandler = mainHandler,
            emitError = ::emitError,
            emitDiagnostic = ::emitDiagnostic,
            onImageAvailable = ::onImageAvailable,
        )
    }

    private val gpsLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val utcNanos = location.time * 1_000_000L
            val elapsedNanos = location.elapsedRealtimeNanos
            gpsUtcOffsetNanos = utcNanos - elapsedNanos
            gpsFixElapsedRealtimeNanos = elapsedNanos
            emitState(if (monitoring) "monitoring" else "idle")
        }

        override fun onProviderDisabled(provider: String) {
            gpsUtcOffsetNanos = null
            gpsFixElapsedRealtimeNanos = null
            emitState(if (monitoring) "monitoring" else "idle")
        }

        override fun onProviderEnabled(provider: String) {
            // no-op
        }

        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            // no-op
        }
    }

    fun configure(binaryMessenger: BinaryMessenger) {
        MethodChannel(binaryMessenger, METHOD_CHANNEL_NAME).setMethodCallHandler(::onMethodCall)
        EventChannel(binaryMessenger, EVENT_CHANNEL_NAME).setStreamHandler(this)
    }

    fun onHostPaused() {
        if (monitoring) {
            stopNativeMonitoringInternal()
        }
    }

    fun dispose() {
        cancelPreviewRebindRetries()
        stopGpsUpdates()
        stopNativeMonitoringInternal()
    }

    fun attachPreviewSurface(targetPreviewView: TextureView) {
        mainHandler.post {
            previewView = targetPreviewView
            if (!monitoring) {
                return@post
            }
            if (targetPreviewView.isAvailable) {
                rebindCameraUseCasesIfMonitoring()
            } else {
                schedulePreviewRebindRetriesIfMonitoring()
            }
        }
    }

    fun detachPreviewSurface(targetPreviewView: TextureView) {
        mainHandler.post {
            if (previewView !== targetPreviewView) {
                return@post
            }
            previewView = null
            cancelPreviewRebindRetries()
            rebindCameraUseCasesIfMonitoring()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    private fun onImageAvailable(image: Image) {
        try {
            if (!monitoring) {
                return
            }
            streamFrameCount += 1
            val frameSensorNanos = image.timestamp
            val offsetSample = frameSensorNanos - SystemClock.elapsedRealtimeNanos()
            val smoothedOffset = offsetSmoother.update(offsetSample)
            val fpsObservation = fpsMonitor.update(
                frameSensorNanos = frameSensorNanos,
                mode = activeCameraFpsMode,
            )
            observedFps = fpsObservation.observedFps
            if (fpsObservation.shouldDowngradeToNormal) {
                requestHsFallbackToNormal()
            }
            hostSensorMinusElapsedNanos = smoothedOffset

            val activeConfig = config
            if ((streamFrameCount % activeConfig.processEveryNFrames.toLong()) != 0L) {
                return
            }

            val lumaPlane = image.planes.firstOrNull() ?: return
            val rawScore = frameDiffer.scoreLumaPlane(
                lumaBuffer = lumaPlane.buffer,
                rowStride = lumaPlane.rowStride,
                pixelStride = lumaPlane.pixelStride,
                width = image.width,
                height = image.height,
                roiCenterX = activeConfig.roiCenterX,
                roiWidth = activeConfig.roiWidth,
            )
            processedFrameCount += 1
            val stats = detectionMath.process(
                rawScore = rawScore,
                frameSensorNanos = frameSensorNanos,
            )
            emitFrameStats(stats, smoothedOffset)
            stats.triggerEvent?.let { emitTrigger(it) }
        } catch (error: Exception) {
            emitError("Native frame analysis failed: ${error.localizedMessage ?: "unknown"}")
        } finally {
            image.close()
        }
    }

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startNativeMonitoring" -> startNativeMonitoring(call, result)
            "warmupGpsSync" -> {
                startGpsUpdatesIfAvailable()
                emitState(if (monitoring) "monitoring" else "idle")
                result.success(null)
            }

            "stopNativeMonitoring" -> {
                stopNativeMonitoringInternal()
                result.success(null)
            }

            "updateNativeConfig" -> {
                updateNativeConfig(call)
                result.success(null)
            }

            "resetNativeRun" -> {
                resetNativeRun()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun startNativeMonitoring(call: MethodCall, result: MethodChannel.Result) {
        val permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            val message = "Camera permission is required before starting native monitoring."
            emitError(message)
            result.error("camera_permission_denied", message, null)
            return
        }

        config = NativeMonitoringConfig.fromMap(call.argument<Any>("config"))
        detectionMath.updateConfig(config)
        requestedCameraFpsMode = requestedFpsModeForConfig(config)

        if (monitoring) {
            emitState("monitoring")
            result.success(null)
            return
        }

        streamFrameCount = 0L
        processedFrameCount = 0L
        hostSensorMinusElapsedNanos = null
        gpsUtcOffsetNanos = null
        gpsFixElapsedRealtimeNanos = null
        requestedCameraFpsMode = requestedFpsModeForConfig(config)
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
        targetFpsUpper = 0
        observedFps = null
        hsDowngradeTriggered = false
        offsetSmoother.reset()
        fpsMonitor.reset()
        detectionMath.resetRun()
        frameDiffer.reset()
        startGpsUpdatesIfAvailable()

        val resultHandled = AtomicBoolean(false)
        monitoring = true
        schedulePreviewRebindRetriesIfMonitoring()
        try {
            cameraSession.bindAndConfigure(
                previewView = previewView,
                preferredFacing = config.cameraFacing,
                preferredFpsMode = requestedCameraFpsMode,
                onConfigured = {
                    if (!monitoring) {
                        return@bindAndConfigure
                    }
                    syncCameraFpsStateFromSession()
                    emitState("monitoring")
                    if (resultHandled.compareAndSet(false, true)) {
                        mainHandler.post { result.success(null) }
                    }
                },
                onError = { message ->
                    val fullMessage =
                        "Failed to initialize native monitoring: ${message.ifBlank { "unknown" }}"
                    emitError(fullMessage)
                    monitoring = false
                    stopGpsUpdates()
                    cameraSession.stop()
                    emitState("idle")
                    if (resultHandled.compareAndSet(false, true)) {
                        mainHandler.post {
                            result.error("native_monitor_start_failed", fullMessage, null)
                        }
                    }
                },
            )
        } catch (error: Exception) {
            monitoring = false
            stopGpsUpdates()
            cameraSession.stop()
            val message =
                "Failed to initialize native monitoring: ${error.localizedMessage ?: "unknown"}"
            emitError(message)
            emitState("idle")
            if (resultHandled.compareAndSet(false, true)) {
                result.error("native_monitor_start_failed", message, null)
            }
        }
    }

    private fun stopNativeMonitoringInternal() {
        cancelPreviewRebindRetries()
        monitoring = false
        stopGpsUpdates()
        cameraSession.stop()
        streamFrameCount = 0L
        processedFrameCount = 0L
        hostSensorMinusElapsedNanos = null
        requestedCameraFpsMode = NativeCameraFpsMode.NORMAL
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
        targetFpsUpper = 0
        observedFps = null
        hsDowngradeTriggered = false
        frameDiffer.reset()
        offsetSmoother.reset()
        fpsMonitor.reset()
        detectionMath.resetRun()
        emitState("idle")
    }

    private fun updateNativeConfig(call: MethodCall) {
        val previousFacing = config.cameraFacing
        val previousHighSpeedEnabled = config.highSpeedEnabled
        config = NativeMonitoringConfig.fromMap(call.argument<Any>("config"))
        detectionMath.updateConfig(config)
        requestedCameraFpsMode = requestedFpsModeForConfig(config)
        if (requestedCameraFpsMode == NativeCameraFpsMode.HS120) {
            hsDowngradeTriggered = false
        }
        if (monitoring &&
            (config.cameraFacing != previousFacing ||
                config.highSpeedEnabled != previousHighSpeedEnabled)
        ) {
            rebindCameraUseCasesIfMonitoring()
        }
        emitState(if (monitoring) "monitoring" else "idle")
    }

    private fun resetNativeRun() {
        streamFrameCount = 0L
        processedFrameCount = 0L
        detectionMath.resetRun()
        frameDiffer.reset()
        emitState(if (monitoring) "monitoring" else "idle")
    }

    private fun rebindCameraUseCasesIfMonitoring() {
        if (!monitoring) {
            return
        }
        if (!attemptPreviewRebind()) {
            schedulePreviewRebindRetriesIfMonitoring()
        }
    }

    private fun attemptPreviewRebind(): Boolean {
        if (!monitoring) {
            return false
        }
        return try {
            cameraSession.bindAndConfigure(
                previewView = previewView,
                preferredFacing = config.cameraFacing,
                preferredFpsMode = requestedCameraFpsMode,
                onConfigured = {
                    if (!monitoring) {
                        return@bindAndConfigure
                    }
                    syncCameraFpsStateFromSession()
                    emitState("monitoring")
                },
                onError = { message ->
                    emitError("Failed to bind preview surface: ${message.ifBlank { "unknown" }}")
                },
            )
            true
        } catch (error: Exception) {
            emitError("Failed to bind preview surface: ${error.localizedMessage ?: "unknown"}")
            false
        }
    }

    private fun syncCameraFpsStateFromSession() {
        activeCameraFpsMode = cameraSession.currentCameraFpsMode()
        targetFpsUpper = cameraSession.currentTargetFpsUpper()
        if (activeCameraFpsMode == NativeCameraFpsMode.NORMAL) {
            requestedCameraFpsMode = NativeCameraFpsMode.NORMAL
        }
        fpsMonitor.reset()
        observedFps = null
    }

    private fun requestHsFallbackToNormal() {
        if (hsDowngradeTriggered) {
            return
        }
        if (requestedCameraFpsMode != NativeCameraFpsMode.HS120 ||
            activeCameraFpsMode != NativeCameraFpsMode.HS120
        ) {
            return
        }
        hsDowngradeTriggered = true
        requestedCameraFpsMode = NativeCameraFpsMode.NORMAL
        emitDiagnostic(
            "Observed FPS stayed below ${HS_FALLBACK_TRIGGER_FPS.toInt()} in HS mode; downgrading to normal.",
        )
        mainHandler.post {
            if (!monitoring) {
                return@post
            }
            if (!attemptPreviewRebind()) {
                schedulePreviewRebindRetriesIfMonitoring()
            }
        }
    }

    private fun requestedFpsModeForConfig(
        activeConfig: NativeMonitoringConfig,
    ): NativeCameraFpsMode {
        return if (activeConfig.highSpeedEnabled) {
            NativeCameraFpsMode.HS120
        } else {
            NativeCameraFpsMode.NORMAL
        }
    }

    private fun schedulePreviewRebindRetriesIfMonitoring() {
        if (!monitoring || previewView == null) {
            return
        }
        val view = previewView ?: return
        if (view.isAvailable) {
            return
        }
        cancelPreviewRebindRetries()
        previewRebindAttemptCount = 0
        val runnable = object : Runnable {
            override fun run() {
                val currentView = previewView
                if (!monitoring || currentView == null) {
                    cancelPreviewRebindRetries()
                    return
                }
                if (currentView.isAvailable) {
                    val success = attemptPreviewRebind()
                    if (!success) {
                        Log.w(TAG, "Preview rebind after surface availability failed.")
                    }
                    cancelPreviewRebindRetries()
                    return
                }

                previewRebindAttemptCount += 1
                if (previewRebindAttemptCount >= PREVIEW_REBIND_MAX_ATTEMPTS) {
                    cancelPreviewRebindRetries()
                    return
                }
                mainHandler.postDelayed(this, PREVIEW_REBIND_RETRY_DELAY_MS)
            }
        }
        pendingPreviewRebindRunnable = runnable
        mainHandler.postDelayed(runnable, PREVIEW_REBIND_RETRY_DELAY_MS)
    }

    private fun cancelPreviewRebindRetries() {
        pendingPreviewRebindRunnable?.let(mainHandler::removeCallbacks)
        pendingPreviewRebindRunnable = null
        previewRebindAttemptCount = 0
    }

    private fun startGpsUpdatesIfAvailable() {
        val locMgr = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        locationManager = locMgr
        if (locMgr == null) {
            return
        }
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) {
            return
        }
        try {
            locMgr.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                gpsLocationListener,
                Looper.getMainLooper(),
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "GPS updates unavailable: missing runtime permission.", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "GPS provider unavailable for location updates.", error)
        }
    }

    private fun stopGpsUpdates() {
        try {
            locationManager?.removeUpdates(gpsLocationListener)
        } catch (_: SecurityException) {
            // ignore cleanup failures
        }
        locationManager = null
        gpsUtcOffsetNanos = null
        gpsFixElapsedRealtimeNanos = null
    }

    private fun emitFrameStats(stats: NativeFrameStats, sensorMinusElapsedNanos: Long?) {
        emitEvent(
            mapOf(
                "type" to "native_frame_stats",
                "rawScore" to stats.rawScore,
                "baseline" to stats.baseline,
                "effectiveScore" to stats.effectiveScore,
                "frameSensorNanos" to stats.frameSensorNanos,
                "streamFrameCount" to streamFrameCount,
                "processedFrameCount" to processedFrameCount,
                "hostSensorMinusElapsedNanos" to sensorMinusElapsedNanos,
                "gpsUtcOffsetNanos" to gpsUtcOffsetNanos,
                "gpsFixElapsedRealtimeNanos" to gpsFixElapsedRealtimeNanos,
                "observedFps" to observedFps,
                "cameraFpsMode" to activeCameraFpsMode.wireName,
                "targetFpsUpper" to targetFpsUpper,
            ),
        )
    }

    private fun emitTrigger(trigger: NativeTriggerEvent) {
        emitEvent(
            mapOf(
                "type" to "native_trigger",
                "triggerSensorNanos" to trigger.triggerSensorNanos,
                "score" to trigger.score,
                "triggerType" to trigger.triggerType,
                "splitIndex" to trigger.splitIndex,
            ),
        )
    }

    private fun emitState(state: String) {
        emitEvent(
            mapOf(
                "type" to "native_state",
                "state" to state,
                "monitoring" to monitoring,
                "hostSensorMinusElapsedNanos" to hostSensorMinusElapsedNanos,
                "gpsUtcOffsetNanos" to gpsUtcOffsetNanos,
                "gpsFixElapsedRealtimeNanos" to gpsFixElapsedRealtimeNanos,
            ),
        )
    }

    private fun emitError(message: String) {
        emitEvent(
            mapOf(
                "type" to "native_error",
                "message" to message,
            ),
        )
    }

    private fun emitDiagnostic(message: String) {
        emitEvent(
            mapOf(
                "type" to "native_diagnostic",
                "message" to message,
            ),
        )
    }

    private fun emitEvent(event: Map<String, Any?>) {
        val sink = eventSink ?: return
        mainHandler.post { sink.success(event) }
    }
}
