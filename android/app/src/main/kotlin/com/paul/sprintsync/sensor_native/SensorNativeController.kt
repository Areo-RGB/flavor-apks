package com.paul.sprintsync.sensor_native

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class SensorNativeController(
    private val activity: FlutterActivity,
) : EventChannel.StreamHandler, ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "SensorNativeController"
        private const val PREVIEW_REBIND_RETRY_DELAY_MS = 200L
        private const val PREVIEW_REBIND_MAX_ATTEMPTS = 3
        private const val HS_FALLBACK_TRIGGER_FPS = 80.0
        const val METHOD_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_methods"
        const val EVENT_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_events"
        const val PREVIEW_VIEW_TYPE = "com.paul.sprintsync/sensor_native_preview"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameDiffer = RoiFrameDiffer()
    private val offsetSmoother = SensorOffsetSmoother()
    private val fpsMonitor = SensorNativeFpsMonitor(lowFpsThreshold = HS_FALLBACK_TRIGGER_FPS)
    private val analysisInFlight = AtomicBoolean(false)

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
    private var observedFps: Double? = null

    @Volatile
    private var activeCameraFpsMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL

    @Volatile
    private var targetFpsUpper: Int? = null

    private var wasMonitoringBeforePause = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var locationManager: LocationManager? = null
    private var previewView: PreviewView? = null
    private var pendingPreviewRebindRunnable: Runnable? = null
    private var previewRebindAttemptCount = 0
    private val detectionMath = NativeDetectionMath(config)

    private val cameraSession: SensorNativeCameraSession by lazy {
        SensorNativeCameraSession(
            activity = activity,
            mainHandler = mainHandler,
            analyzerExecutor = analyzerExecutor,
            analyzer = this,
            emitError = ::emitError,
        )
    }

    private val hsSession: Camera2HsSessionManager by lazy {
        Camera2HsSessionManager(
            activity = activity,
            mainHandler = mainHandler,
            emitError = ::emitError,
            emitDiagnostic = ::emitDiagnostic,
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
        wasMonitoringBeforePause = monitoring
        if (monitoring) {
            stopNativeMonitoringInternal()
        }
    }

    fun onHostResumed() {
        if (!wasMonitoringBeforePause || monitoring) {
            return
        }
        wasMonitoringBeforePause = false
        startGpsUpdatesIfAvailable()
        startMonitoringBackend(
            onStarted = {
                monitoring = true
                emitState("monitoring")
            },
            onError = { error ->
                emitError("Failed to resume monitoring: $error")
                stopNativeMonitoringInternal()
            },
        )
    }

    fun dispose() {
        cancelPreviewRebindRetries()
        stopGpsUpdates()
        stopNativeMonitoringInternal()
        analyzerExecutor.shutdown()
    }

    fun attachPreviewSurface(targetPreviewView: PreviewView) {
        mainHandler.post {
            previewView = targetPreviewView
            if (!config.highSpeedEnabled) {
                rebindCameraUseCasesIfMonitoring()
                schedulePreviewRebindRetriesIfMonitoring()
            }
        }
    }

    fun detachPreviewSurface(targetPreviewView: PreviewView) {
        mainHandler.post {
            if (previewView !== targetPreviewView) {
                return@post
            }
            previewView = null
            cancelPreviewRebindRetries()
            if (!config.highSpeedEnabled) {
                rebindCameraUseCasesIfMonitoring()
            }
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!monitoring || config.highSpeedEnabled) {
                return
            }
            val frameSensorNanos = image.imageInfo.timestamp
            val smoothedOffset = updateStreamTelemetry(frameSensorNanos)
            val activeConfig = config
            if ((streamFrameCount % activeConfig.processEveryNFrames.toLong()) != 0L) {
                return
            }
            if (!analysisInFlight.compareAndSet(false, true)) {
                return
            }

            val lumaPlane = image.planes[0]
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
            analysisInFlight.set(false)
            image.close()
        }
    }

    private fun onHsFrameConsumed(timestampNanos: Long) {
        if (!monitoring || !config.highSpeedEnabled) {
            return
        }
        val smoothedOffset = updateStreamTelemetry(timestampNanos)
        val activeConfig = config
        if ((streamFrameCount % activeConfig.processEveryNFrames.toLong()) != 0L) {
            return
        }
        if (!analysisInFlight.compareAndSet(false, true)) {
            return
        }
        hsSession.requestReadback(activeConfig.roiCenterX, activeConfig.roiWidth) { result ->
            if (result == null) {
                analysisInFlight.set(false)
                return@requestReadback
            }
            try {
                analyzerExecutor.execute {
                    processHsReadback(result, smoothedOffset)
                }
            } catch (_: RejectedExecutionException) {
                analysisInFlight.set(false)
            }
        }
    }

    private fun processHsReadback(
        result: GlLumaExtractor.LumaReadbackResult,
        smoothedOffset: Long,
    ) {
        try {
            if (!monitoring) {
                return
            }
            val rawScore = frameDiffer.scorePrecroppedLuma(
                luma = result.luma,
                sampleCount = result.sampleCount,
            )
            processedFrameCount += 1
            val stats = detectionMath.process(
                rawScore = rawScore,
                frameSensorNanos = result.timestampNanos,
            )
            emitFrameStats(stats, hostSensorMinusElapsedNanos ?: smoothedOffset)
            stats.triggerEvent?.let { emitTrigger(it) }
        } catch (error: Exception) {
            emitError("Native HS frame analysis failed: ${error.localizedMessage ?: "unknown"}")
        } finally {
            analysisInFlight.set(false)
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
        if (monitoring) {
            emitState("monitoring")
            result.success(null)
            return
        }
        resetStreamState()
        startGpsUpdatesIfAvailable()
        startMonitoringBackend(
            onStarted = {
                monitoring = true
                emitState("monitoring")
                result.success(null)
            },
            onError = { error ->
                stopNativeMonitoringInternal()
                emitError("Failed to initialize native monitoring: $error")
                result.error("native_monitor_start_failed", error, null)
            },
        )
    }

    private fun startMonitoringBackend(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (config.highSpeedEnabled) {
            startHsBackend(onStarted = onStarted, onError = onError)
        } else {
            startNormalBackend(onStarted = onStarted, onError = onError)
        }
    }

    private fun startNormalBackend(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
        hsSession.stop()
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    cameraSession.bindAndConfigure(
                        provider = provider,
                        previewView = previewView,
                        includePreview = true,
                        preferredFacing = config.cameraFacing,
                    )
                    targetFpsUpper = cameraSession.currentTargetFpsUpper()
                    schedulePreviewRebindRetriesIfMonitoring()
                    onStarted()
                } catch (error: Exception) {
                    onError(error.localizedMessage ?: "unknown")
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    private fun startHsBackend(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        cancelPreviewRebindRetries()
        val resultHandled = AtomicBoolean(false)
        hsSession.start(
            preferredFacing = config.cameraFacing,
            onFrameConsumed = ::onHsFrameConsumed,
            onStarted = { fpsUpper ->
                mainHandler.post {
                    if (!resultHandled.compareAndSet(false, true)) {
                        return@post
                    }
                    activeCameraFpsMode = NativeCameraFpsMode.HS120
                    targetFpsUpper = fpsUpper
                    onStarted()
                }
            },
            onStartError = { message ->
                mainHandler.post {
                    if (!resultHandled.compareAndSet(false, true)) {
                        return@post
                    }
                    onError(message)
                }
            },
        )
    }

    private fun stopNativeMonitoringInternal() {
        cancelPreviewRebindRetries()
        monitoring = false
        stopGpsUpdates()
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        hsSession.stop()
        resetStreamState()
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
        targetFpsUpper = null
        emitState("idle")
    }

    private fun updateNativeConfig(call: MethodCall) {
        val previousFacing = config.cameraFacing
        val previousHighSpeedEnabled = config.highSpeedEnabled
        config = NativeMonitoringConfig.fromMap(call.argument<Any>("config"))
        detectionMath.updateConfig(config)

        if (monitoring) {
            if (config.highSpeedEnabled != previousHighSpeedEnabled) {
                restartMonitoringBackend()
            } else if (config.highSpeedEnabled && config.cameraFacing != previousFacing) {
                restartMonitoringBackend()
            } else if (!config.highSpeedEnabled && config.cameraFacing != previousFacing) {
                rebindCameraUseCasesIfMonitoring()
            }
        }
        emitState(if (monitoring) "monitoring" else "idle")
    }

    private fun restartMonitoringBackend() {
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        hsSession.stop()
        analysisInFlight.set(false)
        frameDiffer.reset()
        fpsMonitor.reset()
        observedFps = null
        targetFpsUpper = null

        startMonitoringBackend(
            onStarted = {
                monitoring = true
                emitState("monitoring")
            },
            onError = { error ->
                emitError("Failed to reconfigure camera backend: $error")
                stopNativeMonitoringInternal()
            },
        )
    }

    private fun resetNativeRun() {
        streamFrameCount = 0L
        processedFrameCount = 0L
        detectionMath.resetRun()
        frameDiffer.reset()
        emitState(if (monitoring) "monitoring" else "idle")
    }

    private fun resetStreamState() {
        streamFrameCount = 0L
        processedFrameCount = 0L
        hostSensorMinusElapsedNanos = null
        gpsUtcOffsetNanos = null
        gpsFixElapsedRealtimeNanos = null
        observedFps = null
        analysisInFlight.set(false)
        offsetSmoother.reset()
        fpsMonitor.reset()
        detectionMath.resetRun()
        frameDiffer.reset()
    }

    private fun rebindCameraUseCasesIfMonitoring() {
        if (!monitoring || config.highSpeedEnabled) {
            return
        }
        if (!attemptPreviewRebind()) {
            schedulePreviewRebindRetriesIfMonitoring()
        }
    }

    private fun attemptPreviewRebind(): Boolean {
        val provider = cameraProvider ?: return false
        return try {
            cameraSession.bindAndConfigure(
                provider = provider,
                previewView = previewView,
                includePreview = true,
                preferredFacing = config.cameraFacing,
            )
            targetFpsUpper = cameraSession.currentTargetFpsUpper()
            true
        } catch (error: Exception) {
            emitError("Failed to bind preview surface: ${error.localizedMessage ?: "unknown"}")
            false
        }
    }

    private fun schedulePreviewRebindRetriesIfMonitoring() {
        if (!monitoring || config.highSpeedEnabled || previewView == null || cameraProvider == null) {
            return
        }
        cancelPreviewRebindRetries()
        previewRebindAttemptCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (!monitoring || config.highSpeedEnabled || previewView == null || cameraProvider == null) {
                    cancelPreviewRebindRetries()
                    return
                }
                previewRebindAttemptCount += 1
                val success = attemptPreviewRebind()
                if (!success) {
                    Log.w(TAG, "Preview rebind attempt $previewRebindAttemptCount failed.")
                }
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

    private fun updateStreamTelemetry(frameSensorNanos: Long): Long {
        streamFrameCount += 1
        val offsetSample = frameSensorNanos - SystemClock.elapsedRealtimeNanos()
        val smoothedOffset = offsetSmoother.update(offsetSample)
        val fpsObservation = fpsMonitor.update(
            frameSensorNanos = frameSensorNanos,
            mode = activeCameraFpsMode,
        )
        observedFps = fpsObservation.observedFps
        if (fpsObservation.shouldDowngradeToNormal && config.highSpeedEnabled) {
            emitDiagnostic(
                "hs_low_fps_observed: observed=${fpsObservation.observedFps?.toString() ?: "n/a"} threshold=$HS_FALLBACK_TRIGGER_FPS",
            )
        }
        hostSensorMinusElapsedNanos = smoothedOffset
        return smoothedOffset
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
                "observedFps" to observedFps,
                "cameraFpsMode" to activeCameraFpsMode.wireName,
                "targetFpsUpper" to targetFpsUpper,
                "hostSensorMinusElapsedNanos" to sensorMinusElapsedNanos,
                "gpsUtcOffsetNanos" to gpsUtcOffsetNanos,
                "gpsFixElapsedRealtimeNanos" to gpsFixElapsedRealtimeNanos,
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

    private fun emitDiagnostic(message: String) {
        emitEvent(
            mapOf(
                "type" to "native_diagnostic",
                "message" to message,
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

    private fun emitEvent(event: Map<String, Any?>) {
        val sink = eventSink ?: return
        mainHandler.post { sink.success(event) }
    }
}
