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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

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
    private val hsRoiRecorder = HsRoiRecordingBuffer()

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

    private val hsRecordingManager: Camera2HsRecordingManager by lazy {
        Camera2HsRecordingManager(
            activity = activity,
            mainHandler = mainHandler,
            emitError = ::emitError,
            emitDiagnostic = ::emitDiagnostic,
        )
    }

    @Volatile
    private var recordingRunId: String? = null
    private val recordedArtifactsByRunId = mutableMapOf<String, HsRecordedVideoArtifact>()

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
        clearRecordedArtifacts()
        hsRecordingManager.release()
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
            "refineHsTriggers" -> {
                refineHsTriggers(call, result)
            }
            "startHighSpeedRecording" -> {
                startHighSpeedRecording(call, result)
            }
            "stopHighSpeedRecording" -> {
                stopHighSpeedRecording(result)
            }
            "analyzeHighSpeedRecording" -> {
                analyzeHighSpeedRecording(call, result)
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
            .copy(highSpeedEnabled = false)
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

    private fun refineHsTriggers(call: MethodCall, result: MethodChannel.Result) {
        try {
            val triggerRequests = parseHsRefinementRequests(call.argument("requests"))
            if (triggerRequests.isEmpty()) {
                result.success(
                    mapOf(
                        "results" to emptyList<Map<String, Any?>>(),
                        "recordedFrameCount" to 0,
                    ),
                )
                return
            }

            val recordedFrames = hsRoiRecorder.snapshot()
            val refined = HsPostRaceRefiner.refineRequests(
                recordedFrames = recordedFrames,
                requests = triggerRequests,
                config = config,
                defaultWindowNanos = HsRecordingPolicy.DEFAULT_REFINEMENT_WINDOW_NANOS,
            )
            val responseResults = refined.map { refinedResult ->
                mapOf<String, Any?>(
                    "triggerType" to refinedResult.triggerType,
                    "splitIndex" to refinedResult.splitIndex,
                    "provisionalSensorNanos" to refinedResult.provisionalSensorNanos,
                    "refinedSensorNanos" to refinedResult.refinedSensorNanos,
                    "refined" to refinedResult.refined,
                    "rawScore" to refinedResult.rawScore,
                    "baseline" to refinedResult.baseline,
                    "effectiveScore" to refinedResult.effectiveScore,
                )
            }
            result.success(
                mapOf(
                    "results" to responseResults,
                    "recordedFrameCount" to recordedFrames.size,
                ),
            )
        } catch (error: Exception) {
            result.error(
                "hs_refine_failed",
                error.localizedMessage ?: "Failed to refine HS triggers.",
                null,
            )
        }
    }

    private fun parseHsRefinementRequests(raw: Any?): List<HsTriggerRefinementRequest> {
        if (raw !is List<*>) {
            return emptyList()
        }
        return raw.mapNotNull { item ->
            if (item !is Map<*, *>) {
                return@mapNotNull null
            }
            val triggerSensorNanos = (item["triggerSensorNanos"] as? Number)?.toLong()
                ?: return@mapNotNull null
            val triggerType = item["triggerType"]?.toString()?.ifBlank { null }
                ?: return@mapNotNull null
            val splitIndex = (item["splitIndex"] as? Number)?.toInt()
                ?: return@mapNotNull null
            val windowNanos = (item["windowNanos"] as? Number)?.toLong()
            HsTriggerRefinementRequest(
                triggerSensorNanos = triggerSensorNanos,
                triggerType = triggerType,
                splitIndex = splitIndex,
                windowNanos = windowNanos,
            )
        }
    }

    private fun startHighSpeedRecording(call: MethodCall, result: MethodChannel.Result) {
        val permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            val message = "Camera permission is required before starting HS recording."
            emitError(message)
            result.error("camera_permission_denied", message, null)
            return
        }
        if (monitoring) {
            val message = "Cannot start HS recording while live monitoring is active."
            result.error("hs_recording_conflict", message, null)
            return
        }
        if (recordingRunId != null) {
            result.error("hs_recording_conflict", "HS recording is already active.", null)
            return
        }
        val runId = call.argument<String>("runId")?.trim().orEmpty()
        if (runId.isEmpty()) {
            result.error("invalid_args", "runId is required.", null)
            return
        }
        val preferredFacing = nativeCameraFacingFromWire(call.argument<String>("cameraFacing"))
            ?: config.cameraFacing
        val handled = AtomicBoolean(false)
        hsRecordingManager.start(
            runId = runId,
            preferredFacing = preferredFacing,
            onStarted = { fpsUpper ->
                mainHandler.post {
                    if (!handled.compareAndSet(false, true)) {
                        return@post
                    }
                    recordingRunId = runId
                    activeCameraFpsMode = NativeCameraFpsMode.HS120
                    targetFpsUpper = fpsUpper
                    emitRecordingState(runId = runId, state = "recording", targetFps = fpsUpper)
                    result.success(
                        mapOf(
                            "started" to true,
                            "runId" to runId,
                            "targetFpsUpper" to fpsUpper,
                        ),
                    )
                }
            },
            onStartError = { message ->
                mainHandler.post {
                    if (!handled.compareAndSet(false, true)) {
                        return@post
                    }
                    recordingRunId = null
                    activeCameraFpsMode = NativeCameraFpsMode.NORMAL
                    targetFpsUpper = null
                    emitRecordingState(runId = runId, state = "error", message = message)
                    result.error("hs_recording_start_failed", message, null)
                }
            },
        )
    }

    private fun stopHighSpeedRecording(result: MethodChannel.Result) {
        val runId = recordingRunId
        val artifact = hsRecordingManager.stop()
        recordingRunId = null
        activeCameraFpsMode = NativeCameraFpsMode.NORMAL
        targetFpsUpper = null
        if (artifact != null) {
            synchronized(recordedArtifactsByRunId) {
                recordedArtifactsByRunId[artifact.runId] = artifact
            }
            emitRecordingState(
                runId = artifact.runId,
                state = "stopped",
                recordedFrameCount = min(artifact.captureSensorNanos.size, artifact.encodedPtsUs.size),
            )
            result.success(
                mapOf(
                    "stopped" to true,
                    "runId" to artifact.runId,
                    "outputPath" to artifact.outputPath,
                    "recordedFrameCount" to min(artifact.captureSensorNanos.size, artifact.encodedPtsUs.size),
                ),
            )
            return
        }
        if (runId != null) {
            emitRecordingState(runId = runId, state = "stopped", message = "No recording artifact produced.")
        }
        result.success(
            mapOf(
                "stopped" to true,
                "runId" to runId,
                "recordedFrameCount" to 0,
            ),
        )
    }

    private fun analyzeHighSpeedRecording(call: MethodCall, result: MethodChannel.Result) {
        try {
            val runId = call.argument<String>("runId")?.trim().orEmpty()
            if (runId.isEmpty()) {
                result.error("invalid_args", "runId is required.", null)
                return
            }
            val triggerType = call.argument<String>("triggerType")?.trim().orEmpty()
            if (triggerType.isEmpty()) {
                result.error("invalid_args", "triggerType is required.", null)
                return
            }
            val splitIndex = (call.argument<Number>("splitIndex")?.toInt()) ?: 0
            val direction = hsScanDirectionFromWire(call.argument<String>("scanDirection"))
                ?: HsScanDirection.FORWARD
            val artifact = synchronized(recordedArtifactsByRunId) {
                recordedArtifactsByRunId[runId]
            }
            if (artifact == null) {
                result.success(
                    mapOf(
                        "runId" to runId,
                        "triggerType" to triggerType,
                        "splitIndex" to splitIndex,
                        "scanDirection" to direction.wireName,
                        "resolved" to false,
                        "diagnostics" to "Recording artifact not available.",
                    ),
                )
                return
            }
            val analysis = HsRecordedVideoAnalyzer.analyze(
                artifact = artifact,
                triggerType = triggerType,
                splitIndex = splitIndex,
                scanDirection = direction,
                threshold = config.threshold,
                roiCenterX = config.roiCenterX,
                roiWidth = config.roiWidth,
            )
            synchronized(recordedArtifactsByRunId) {
                recordedArtifactsByRunId.remove(runId)
            }
            File(artifact.outputPath).delete()
            emitRecordingState(
                runId = runId,
                state = "analyzed",
                message = if (analysis.resolved) "Resolved." else (analysis.diagnostics ?: "Unresolved."),
            )
            result.success(
                mapOf(
                    "runId" to analysis.runId,
                    "triggerType" to analysis.triggerType,
                    "splitIndex" to analysis.splitIndex,
                    "scanDirection" to analysis.scanDirection.wireName,
                    "resolved" to analysis.resolved,
                    "localSensorNanos" to analysis.localSensorNanos,
                    "rawScore" to analysis.rawScore,
                    "baseline" to analysis.baseline,
                    "effectiveScore" to analysis.effectiveScore,
                    "diagnostics" to analysis.diagnostics,
                ),
            )
        } catch (error: Exception) {
            result.error(
                "hs_recording_analyze_failed",
                error.localizedMessage ?: "Failed to analyze HS recording.",
                null,
            )
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
        val recordingArtifact = hsRecordingManager.stop()
        if (recordingArtifact != null) {
            synchronized(recordedArtifactsByRunId) {
                recordedArtifactsByRunId[recordingArtifact.runId] = recordingArtifact
            }
        }
        recordingRunId = null
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
        config = NativeMonitoringConfig.fromMap(call.argument<Any>("config"))
            .copy(highSpeedEnabled = false)
        detectionMath.updateConfig(config)

        if (monitoring) {
            if (config.cameraFacing != previousFacing) {
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

    private fun resetNativeRun() {
        streamFrameCount = 0L
        processedFrameCount = 0L
        detectionMath.resetRun()
        frameDiffer.reset()
        hsRoiRecorder.clear()
        clearRecordedArtifacts()
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
        hsRoiRecorder.clear()
        offsetSmoother.reset()
        fpsMonitor.reset()
        detectionMath.resetRun()
        frameDiffer.reset()
    }

    private fun clearRecordedArtifacts() {
        val artifacts = synchronized(recordedArtifactsByRunId) {
            val copied = recordedArtifactsByRunId.values.toList()
            recordedArtifactsByRunId.clear()
            copied
        }
        for (artifact in artifacts) {
            File(artifact.outputPath).delete()
        }
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

    private fun emitRecordingState(
        runId: String,
        state: String,
        targetFps: Int? = null,
        recordedFrameCount: Int? = null,
        message: String? = null,
    ) {
        emitEvent(
            mapOf(
                "type" to "hs_recording_state",
                "runId" to runId,
                "state" to state,
                "targetFpsUpper" to targetFps,
                "recordedFrameCount" to recordedFrameCount,
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
