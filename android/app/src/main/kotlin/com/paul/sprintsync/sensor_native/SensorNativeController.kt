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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class SensorNativeController(
    private val activity: ComponentActivity,
) : ImageAnalysis.Analyzer {
    companion object {
        private const val TAG = "SensorNativeController"
        private const val PREVIEW_REBIND_RETRY_DELAY_MS = 200L
        private const val PREVIEW_REBIND_MAX_ATTEMPTS = 3
        private const val HS_FALLBACK_TRIGGER_FPS = 80.0
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameDiffer = RoiFrameDiffer()
    private val offsetSmoother = SensorOffsetSmoother()
    private val fpsMonitor = SensorNativeFpsMonitor(lowFpsThreshold = HS_FALLBACK_TRIGGER_FPS)
    private val analysisInFlight = AtomicBoolean(false)
    private val hsRoiRecorder = HsRoiRecordingBuffer()

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
                "preview attached: monitoring=$monitoring hasProvider=${cameraProvider != null} highSpeed=${config.highSpeedEnabled}",
            )
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
            logRuntimeDiagnostic(
                "preview detached: monitoring=$monitoring hasProvider=${cameraProvider != null} highSpeed=${config.highSpeedEnabled}",
            )
            cancelPreviewRebindRetries()
            if (!config.highSpeedEnabled) {
                rebindCameraUseCasesIfMonitoring()
            }
        }
    }

    fun currentClockSyncElapsedNanos(
        maxSensorSampleAgeNanos: Long,
        requireSensorDomain: Boolean,
    ): Long? {
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

    fun startNativeMonitoring(
        monitoringConfig: NativeMonitoringConfig,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            val message = "Camera permission is required before starting native monitoring."
            emitError(message)
            onComplete(Result.failure(IllegalStateException(message)))
            return
        }
        config = monitoringConfig.copy(highSpeedEnabled = false)
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
        emitState(if (monitoring) "monitoring" else "idle")
    }

    fun stopNativeMonitoring() {
        stopNativeMonitoringInternal()
    }

    fun updateNativeConfig(monitoringConfig: NativeMonitoringConfig) {
        val previousFacing = config.cameraFacing
        config = monitoringConfig.copy(highSpeedEnabled = false)
        detectionMath.updateConfig(config)

        if (monitoring && config.cameraFacing != previousFacing) {
            rebindCameraUseCasesIfMonitoring()
        }
        emitState(if (monitoring) "monitoring" else "idle")
    }

    fun resetNativeRun() {
        resetNativeRunInternal()
    }

    fun refineHsTriggers(requests: List<HsTriggerRefinementRequest>): HsTriggerRefinementResponse {
        if (requests.isEmpty()) {
            return HsTriggerRefinementResponse(
                results = emptyList(),
                recordedFrameCount = 0,
            )
        }

        val recordedFrames = hsRoiRecorder.snapshot()
        val refined = HsPostRaceRefiner.refineRequests(
            recordedFrames = recordedFrames,
            requests = requests,
            config = config,
            defaultWindowNanos = HsRecordingPolicy.DEFAULT_REFINEMENT_WINDOW_NANOS,
        )
        return HsTriggerRefinementResponse(
            results = refined,
            recordedFrameCount = recordedFrames.size,
        )
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
        val shouldAnalyzeLiveFrame = HsAnalysisPolicy.shouldAnalyzeLiveFrame(streamFrameCount)
        hsSession.requestReadback(activeConfig.roiCenterX, activeConfig.roiWidth) { result ->
            if (result == null) {
                return@requestReadback
            }
            hsRoiRecorder.append(
                HsRecordedRoiFrame(
                    timestampNanos = result.timestampNanos,
                    luma = result.luma,
                    sampleCount = result.sampleCount,
                ),
            )
            if (!shouldAnalyzeLiveFrame) {
                return@requestReadback
            }
            if (!analysisInFlight.compareAndSet(false, true)) {
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

    private fun startMonitoringBackend(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        // Live analysis always runs normal mode; dedicated HS recording is a separate workflow.
        startNormalBackend(onStarted = onStarted, onError = onError)
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

    private fun restartMonitoringBackend() {
        cameraSession.stop(cameraProvider)
        cameraProvider = null
        hsSession.stop()
        analysisInFlight.set(false)
        hsRoiRecorder.clear()
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

    private fun resetNativeRunInternal() {
        streamFrameCount = 0L
        processedFrameCount = 0L
        detectionMath.resetRun()
        frameDiffer.reset()
        hsRoiRecorder.clear()
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
        hsRoiRecorder.clear()
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
        if (
            !shouldSchedulePreviewRebindRetry(
                monitoring = monitoring,
                highSpeedEnabled = config.highSpeedEnabled,
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
                        highSpeedEnabled = config.highSpeedEnabled,
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
            SensorNativeEvent.FrameStats(
                stats = stats,
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

internal fun shouldSchedulePreviewRebindRetry(
    monitoring: Boolean,
    highSpeedEnabled: Boolean,
    hasPreviewView: Boolean,
    hasCameraProvider: Boolean,
): Boolean {
    return monitoring && !highSpeedEnabled && hasPreviewView && hasCameraProvider
}

data class HsTriggerRefinementResponse(
    val results: List<HsTriggerRefinementResult>,
    val recordedFrameCount: Int,
)
