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
class SensorNativeController(
    private val activity: FlutterActivity,
) : EventChannel.StreamHandler, ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "SensorNativeController"
        private const val PREVIEW_REBIND_RETRY_DELAY_MS = 200L
        private const val PREVIEW_REBIND_MAX_ATTEMPTS = 3
        const val METHOD_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_methods"
        const val EVENT_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_events"
        const val PREVIEW_VIEW_TYPE = "com.paul.sprintsync/sensor_native_preview"
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameDiffer = RoiFrameDiffer()
    private val offsetSmoother = SensorOffsetSmoother()
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
        analyzerExecutor.shutdown()
    }
    fun attachPreviewSurface(targetPreviewView: PreviewView) {
        mainHandler.post {
            previewView = targetPreviewView
            rebindCameraUseCasesIfMonitoring()
            schedulePreviewRebindRetriesIfMonitoring()
        }
    }
    fun detachPreviewSurface(targetPreviewView: PreviewView) {
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
    override fun analyze(image: ImageProxy) {
        try {
            if (!monitoring) {
                return
            }
            streamFrameCount += 1
            val frameSensorNanos = image.imageInfo.timestamp
            val offsetSample = frameSensorNanos - SystemClock.elapsedRealtimeNanos()
            val smoothedOffset = offsetSmoother.update(offsetSample)
            hostSensorMinusElapsedNanos = smoothedOffset
            val activeConfig = config
            if ((streamFrameCount % activeConfig.processEveryNFrames.toLong()) != 0L) {
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
        offsetSmoother.reset()
        detectionMath.resetRun()
        frameDiffer.reset()
        startGpsUpdatesIfAvailable()
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
                    monitoring = true
                    schedulePreviewRebindRetriesIfMonitoring()
                    emitState("monitoring")
                    result.success(null)
                } catch (error: Exception) {
                    val message = "Failed to initialize native monitoring: ${error.localizedMessage ?: "unknown"}"
                    emitError(message)
                    result.error("native_monitor_start_failed", message, null)
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }
    private fun stopNativeMonitoringInternal() {
        cancelPreviewRebindRetries()
        monitoring = false
        stopGpsUpdates()
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        streamFrameCount = 0L
        processedFrameCount = 0L
        hostSensorMinusElapsedNanos = null
        frameDiffer.reset()
        offsetSmoother.reset()
        detectionMath.resetRun()
        emitState("idle")
    }
    private fun updateNativeConfig(call: MethodCall) {
        val previousFacing = config.cameraFacing
        config = NativeMonitoringConfig.fromMap(call.argument<Any>("config"))
        detectionMath.updateConfig(config)
        if (monitoring && config.cameraFacing != previousFacing) {
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
        val provider = cameraProvider ?: return false
        return try {
            cameraSession.bindAndConfigure(
                provider = provider,
                previewView = previewView,
                includePreview = true,
                preferredFacing = config.cameraFacing,
            )
            true
        } catch (error: Exception) {
            emitError("Failed to bind preview surface: ${error.localizedMessage ?: "unknown"}")
            false
        }
    }

    private fun schedulePreviewRebindRetriesIfMonitoring() {
        if (!monitoring || previewView == null || cameraProvider == null) {
            return
        }
        cancelPreviewRebindRetries()
        previewRebindAttemptCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (!monitoring || previewView == null || cameraProvider == null) {
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
    private fun emitEvent(event: Map<String, Any?>) {
        val sink = eventSink ?: return
        mainHandler.post { sink.success(event) }
    }
}
