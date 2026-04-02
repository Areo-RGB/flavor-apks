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
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SensorNativeController(
    private val activity: ComponentActivity,
) : ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "SensorNativeController"
        private const val PREVIEW_REBIND_RETRY_DELAY_MS = 200L
        private const val PREVIEW_REBIND_MAX_ATTEMPTS = 3
        private const val ANALYZER_INACTIVITY_CHECK_MS = 1_500L
        private const val ANALYZER_INACTIVITY_WARN_MS = 3_500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameDiffer = RoiFrameDiffer()
    private val offsetSmoother = SensorOffsetSmoother()
    private val fpsMonitor = SensorNativeFpsMonitor()
    private val analysisInFlight = AtomicBoolean(false)

    @Volatile
    private var eventListener: ((SensorNativeEvent) -> Unit)? = null

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
    private var lastSensorElapsedSampleNanos: Long? = null

    @Volatile
    private var lastSensorElapsedSampleCapturedAtNanos: Long? = null

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

    @Volatile
    private var gpsUpdatesStarted = false

    private var wasMonitoringBeforePause = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var locationManager: LocationManager? = null
    private var previewView: PreviewView? = null
    private var pendingPreviewRebindRunnable: Runnable? = null
    private var analyzerInactivityWatchdogRunnable: Runnable? = null
    private var previewRebindAttemptCount = 0
    private var analyzerInactivityLastProcessedCount = 0L
    private var analyzerInactivityLastProgressElapsedMs = 0L
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

    fun setEventListener(listener: ((SensorNativeEvent) -> Unit)?) {
        eventListener = listener
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
            logRuntimeDiagnostic(
                "preview attached: monitoring=$monitoring hasProvider=${cameraProvider != null}",
            )
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
            logRuntimeDiagnostic(
                "preview detached: monitoring=$monitoring hasProvider=${cameraProvider != null}",
            )
            cancelPreviewRebindRetries()
            rebindCameraUseCasesIfMonitoring()
        }
    }

    fun currentClockSyncElapsedNanos(maxSensorSampleAgeNanos: Long, requireSensorDomain: Boolean): Long? {
        val nowElapsedNanos = SystemClock.elapsedRealtimeNanos()
        val sampledElapsedNanos = lastSensorElapsedSampleNanos
        val sampledCapturedAtNanos = lastSensorElapsedSampleCapturedAtNanos
        if (sampledElapsedNanos != null && sampledCapturedAtNanos != null) {
            val sampleAgeNanos = nowElapsedNanos - sampledCapturedAtNanos
            if (sampleAgeNanos >= 0 && sampleAgeNanos <= maxSensorSampleAgeNanos) {
                return sampledElapsedNanos + sampleAgeNanos
            }
        }
        if (requireSensorDomain) {
            return null
        }
        return nowElapsedNanos
    }

    fun startNativeMonitoring(monitoringConfig: NativeMonitoringConfig, onComplete: (Result<Unit>) -> Unit) {
        val permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            val message = "Camera permission is required before starting native monitoring."
            emitError(message)
            onComplete(Result.failure(IllegalStateException(message)))
            return
        }
        config = monitoringConfig
        detectionMath.updateConfig(config)
        if (monitoring) {
            emitState("monitoring")
            onComplete(Result.success(Unit))
            return
        }
        resetStreamState()
        startGpsUpdatesIfAvailable()
        startMonitoringBackend(
            onStarted = {
                monitoring = true
                scheduleAnalyzerInactivityWatchdog()
                emitState("monitoring")
                onComplete(Result.success(Unit))
            },
            onError = { error ->
                stopNativeMonitoringInternal()
                emitError("Failed to initialize native monitoring: $error")
                onComplete(Result.failure(IllegalStateException(error)))
            },
        )
    }

    fun warmupGpsSync() {
        startGpsUpdatesIfAvailable()
    }

    fun stopNativeMonitoring() {
        stopNativeMonitoringInternal()
    }

    fun updateNativeConfig(monitoringConfig: NativeMonitoringConfig) {
        val previousFacing = config.cameraFacing
        config = monitoringConfig
        detectionMath.updateConfig(config)

        if (monitoring && config.cameraFacing != previousFacing) {
            rebindCameraUseCasesIfMonitoring()
        }
        emitState(if (monitoring) "monitoring" else "idle")
    }

    fun resetNativeRun() {
        resetNativeRunInternal()
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!monitoring) {
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
            emitFrameStats(
                stats = stats,
                sensorMinusElapsedNanos = smoothedOffset,
                analysisWidth = image.width,
                analysisHeight = image.height,
            )
            stats.triggerEvent?.let { emitTrigger(it) }
        } catch (error: Exception) {
            emitError("Native frame analysis failed: ${error.localizedMessage ?: "unknown"}")
        } finally {
            analysisInFlight.set(false)
            image.close()
        }
    }

    private fun startMonitoringBackend(onStarted: () -> Unit, onError: (String) -> Unit) {
        startNormalBackend(onStarted = onStarted, onError = onError)
    }

    private fun startNormalBackend(onStarted: () -> Unit, onError: (String) -> Unit) {
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
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
                    logRuntimeDiagnostic(
                        "normal backend ready: hasPreview=${previewView != null} monitoringBeforeStart=$monitoring",
                    )
                    onStarted()
                    rebindCameraUseCasesIfMonitoring()
                    schedulePreviewRebindRetriesIfMonitoring()
                } catch (error: Exception) {
                    onError(error.localizedMessage ?: "unknown")
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    private fun stopNativeMonitoringInternal() {
        cancelAnalyzerInactivityWatchdog()
        cancelPreviewRebindRetries()
        monitoring = false
        stopGpsUpdates()
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        resetStreamState()
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
        targetFpsUpper = null
        emitState("idle")
    }

    private fun restartMonitoringBackend() {
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        analysisInFlight.set(false)
        frameDiffer.reset()
        fpsMonitor.reset()
        observedFps = null
        targetFpsUpper = null

        startMonitoringBackend(
            onStarted = {
                monitoring = true
                scheduleAnalyzerInactivityWatchdog()
                emitState("monitoring")
            },
            onError = { error ->
                emitError("Failed to reconfigure camera backend: $error")
                stopNativeMonitoringInternal()
            },
        )
    }

    private fun resetNativeRunInternal() {
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
        lastSensorElapsedSampleNanos = null
        lastSensorElapsedSampleCapturedAtNanos = null
        gpsUtcOffsetNanos = null
        gpsFixElapsedRealtimeNanos = null
        observedFps = null
        analysisInFlight.set(false)
        offsetSmoother.reset()
        fpsMonitor.reset()
        detectionMath.resetRun()
        frameDiffer.reset()
        analyzerInactivityLastProcessedCount = 0L
        analyzerInactivityLastProgressElapsedMs = 0L
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
            targetFpsUpper = cameraSession.currentTargetFpsUpper()
            true
        } catch (error: Exception) {
            emitError("Failed to bind preview surface: ${error.localizedMessage ?: "unknown"}")
            false
        }
    }

    private fun schedulePreviewRebindRetriesIfMonitoring() {
        if (
            !shouldSchedulePreviewRebindRetry(
                monitoring = monitoring,
                hasPreviewView = previewView != null,
                hasCameraProvider = cameraProvider != null,
            )
        ) {
            return
        }
        logRuntimeDiagnostic("scheduling preview rebind retries")
        cancelPreviewRebindRetries()
        previewRebindAttemptCount = 0
        val runnable = object : Runnable {
            override fun run() {
                if (
                    !shouldSchedulePreviewRebindRetry(
                        monitoring = monitoring,
                        hasPreviewView = previewView != null,
                        hasCameraProvider = cameraProvider != null,
                    )
                ) {
                    cancelPreviewRebindRetries()
                    return
                }
                previewRebindAttemptCount += 1
                val success = attemptPreviewRebind()
                if (!success) {
                    Log.w(TAG, "Preview rebind attempt $previewRebindAttemptCount failed.")
                } else {
                    logRuntimeDiagnostic("preview rebind attempt $previewRebindAttemptCount succeeded")
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

    private fun scheduleAnalyzerInactivityWatchdog() {
        cancelAnalyzerInactivityWatchdog()
        analyzerInactivityLastProcessedCount = processedFrameCount
        analyzerInactivityLastProgressElapsedMs = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                if (!monitoring) {
                    cancelAnalyzerInactivityWatchdog()
                    return
                }
                val nowMs = SystemClock.elapsedRealtime()
                if (processedFrameCount != analyzerInactivityLastProcessedCount) {
                    analyzerInactivityLastProcessedCount = processedFrameCount
                    analyzerInactivityLastProgressElapsedMs = nowMs
                } else if (nowMs - analyzerInactivityLastProgressElapsedMs >= ANALYZER_INACTIVITY_WARN_MS) {
                    emitDiagnostic(
                        "analyzer inactivity: no processed frames for ${nowMs - analyzerInactivityLastProgressElapsedMs}ms while monitoring=true (preview=${previewView != null})",
                    )
                    analyzerInactivityLastProgressElapsedMs = nowMs
                }
                mainHandler.postDelayed(this, ANALYZER_INACTIVITY_CHECK_MS)
            }
        }
        analyzerInactivityWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, ANALYZER_INACTIVITY_CHECK_MS)
    }

    private fun cancelAnalyzerInactivityWatchdog() {
        analyzerInactivityWatchdogRunnable?.let(mainHandler::removeCallbacks)
        analyzerInactivityWatchdogRunnable = null
    }

    private fun logRuntimeDiagnostic(message: String) {
        Log.d(TAG, "diag: $message")
    }

    private fun updateStreamTelemetry(frameSensorNanos: Long): Long {
        streamFrameCount += 1
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()
        val offsetSample = frameSensorNanos - elapsedNanos
        val smoothedOffset = offsetSmoother.update(offsetSample)
        lastSensorElapsedSampleNanos = frameSensorNanos - smoothedOffset
        lastSensorElapsedSampleCapturedAtNanos = elapsedNanos
        val fpsObservation = fpsMonitor.update(
            frameSensorNanos = frameSensorNanos,
        )
        observedFps = fpsObservation.observedFps
        hostSensorMinusElapsedNanos = smoothedOffset
        return smoothedOffset
    }

    private fun startGpsUpdatesIfAvailable() {
        if (gpsUpdatesStarted) {
            return
        }
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
            gpsUpdatesStarted = true
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
        gpsUpdatesStarted = false
    }

    private fun emitFrameStats(
        stats: NativeFrameStats,
        sensorMinusElapsedNanos: Long?,
        analysisWidth: Int,
        analysisHeight: Int,
    ) {
        emitEvent(
            SensorNativeEvent.FrameStats(
                stats = stats,
                analysisWidth = analysisWidth,
                analysisHeight = analysisHeight,
                streamFrameCount = streamFrameCount,
                processedFrameCount = processedFrameCount,
                observedFps = observedFps,
                cameraFpsMode = activeCameraFpsMode,
                targetFpsUpper = targetFpsUpper,
                hostSensorMinusElapsedNanos = sensorMinusElapsedNanos,
                gpsUtcOffsetNanos = gpsUtcOffsetNanos,
                gpsFixElapsedRealtimeNanos = gpsFixElapsedRealtimeNanos,
            ),
        )
    }

    private fun emitTrigger(trigger: NativeTriggerEvent) {
        emitEvent(SensorNativeEvent.Trigger(trigger = trigger))
    }

    private fun emitState(state: String) {
        emitEvent(
            SensorNativeEvent.State(
                state = state,
                monitoring = monitoring,
                hostSensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
                gpsUtcOffsetNanos = gpsUtcOffsetNanos,
                gpsFixElapsedRealtimeNanos = gpsFixElapsedRealtimeNanos,
            ),
        )
    }

    private fun emitDiagnostic(message: String) {
        emitEvent(SensorNativeEvent.Diagnostic(message = message))
    }

    private fun emitError(message: String) {
        emitEvent(SensorNativeEvent.Error(message = message))
    }

    private fun emitEvent(event: SensorNativeEvent) {
        val listener = eventListener ?: return
        mainHandler.post { listener(event) }
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun shouldSchedulePreviewRebindRetry(
    monitoring: Boolean,
    hasPreviewView: Boolean,
    hasCameraProvider: Boolean,
): Boolean {
    return monitoring && hasCameraProvider
}
