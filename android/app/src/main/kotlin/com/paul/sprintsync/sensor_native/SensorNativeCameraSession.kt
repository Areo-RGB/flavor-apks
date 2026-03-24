package com.paul.sprintsync.sensor_native

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import io.flutter.embedding.android.FlutterActivity

internal class SensorNativeCameraSession(
    private val activity: FlutterActivity,
    private val mainHandler: Handler,
    private val emitError: (String) -> Unit,
    private val emitDiagnostic: (String) -> Unit,
    private val onImageAvailable: (Image) -> Unit,
) {
    companion object {
        val NORMAL_TARGET_SIZE: Size = Size(640, 480)
        val HS_TARGET_SIZE: Size = Size(1280, 720)
        private const val AE_AWB_WARMUP_MS = 400L
    }

    @Volatile
    private var activeFpsMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL

    @Volatile
    private var activeTargetFpsUpper = 0

    private val cameraManager: CameraManager by lazy {
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var generation = 0L
    private var pendingAeAwbLockRunnable: Runnable? = null

    private var lastRequestedFacing: NativeCameraFacing = NativeCameraFacing.REAR
    private var lastRequestedFpsMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL
    private var hsFallbackDiagnosticEmitted = false

    fun stop() {
        generation += 1
        cancelPendingAeAwbLock()
        closeActiveResources()
        shutdownCameraThread()
        hsFallbackDiagnosticEmitted = false
        activeFpsMode = NativeCameraFpsMode.NORMAL
        activeTargetFpsUpper = 0
    }

    fun bindAndConfigure(
        previewView: TextureView?,
        preferredFacing: NativeCameraFacing,
        preferredFpsMode: NativeCameraFpsMode,
        onConfigured: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val newGeneration = generation + 1
        generation = newGeneration
        lastRequestedFacing = preferredFacing
        lastRequestedFpsMode = preferredFpsMode
        cancelPendingAeAwbLock()
        closeActiveResources()

        ensureCameraThread()

        val plan = try {
            buildSessionPlan(preferredFacing, preferredFpsMode)
        } catch (error: Exception) {
            onError(error.localizedMessage ?: "Failed to build camera session plan.")
            return
        }

        if (plan.forcedRearRuntime) {
            emitDiagnostic("hs_forced_rear_runtime: selected facing has no HS support, forcing rear for this runtime session.")
        }

        if (preferredFpsMode == NativeCameraFpsMode.HS120 && plan.mode == CaptureMode.NORMAL) {
            emitHsFallbackDiagnosticOnce(
                "hs_fallback_normal: constrained HS unavailable for requested facing/runtime plan; using normal mode.",
            )
        }

        openCameraWithPlan(
            generation = newGeneration,
            previewView = previewView,
            plan = plan,
            allowHsFallback = true,
            onConfigured = onConfigured,
            onError = onError,
        )
    }

    fun currentCameraFpsMode(): NativeCameraFpsMode = activeFpsMode

    fun currentTargetFpsUpper(): Int = activeTargetFpsUpper

    private fun ensureCameraThread() {
        if (cameraThread != null) {
            return
        }
        val thread = HandlerThread("SensorNativeCamera2").apply { start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun shutdownCameraThread() {
        val thread = cameraThread
        if (thread != null) {
            thread.quitSafely()
            thread.join(500)
        }
        cameraThread = null
        cameraHandler = null
    }

    private fun closeActiveResources() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
        cameraDevice = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
        imageReader = null

        try {
            previewSurface?.release()
        } catch (_: Exception) {
            // Ignore cleanup errors.
        }
        previewSurface = null
    }

    private fun openCameraWithPlan(
        generation: Long,
        previewView: TextureView?,
        plan: SessionPlan,
        allowHsFallback: Boolean,
        onConfigured: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val handler = cameraHandler
        if (handler == null) {
            onError("Camera thread not initialized.")
            return
        }
        val cameraId = plan.cameraId
        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (!isCurrentGeneration(generation)) {
                            device.close()
                            return
                        }
                        cameraDevice = device
                        createCaptureSession(
                            generation = generation,
                            previewView = previewView,
                            plan = plan,
                            allowHsFallback = allowHsFallback,
                            onConfigured = onConfigured,
                            onError = onError,
                        )
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (isCurrentGeneration(generation)) {
                            onError("Camera device $cameraId disconnected.")
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (isCurrentGeneration(generation)) {
                            handlePlanFailure(
                                generation = generation,
                                plan = plan,
                                reason = "Camera open error code $error for camera $cameraId.",
                                previewView = previewView,
                                allowHsFallback = allowHsFallback,
                                onConfigured = onConfigured,
                                onError = onError,
                            )
                        }
                    }
                },
                handler,
            )
        } catch (error: SecurityException) {
            onError("Camera permission is missing: ${error.localizedMessage ?: "unknown"}")
        } catch (error: Exception) {
            onError("Failed to open camera $cameraId: ${error.localizedMessage ?: "unknown"}")
        }
    }

    private fun createCaptureSession(
        generation: Long,
        previewView: TextureView?,
        plan: SessionPlan,
        allowHsFallback: Boolean,
        onConfigured: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val handler = cameraHandler
        val device = cameraDevice
        if (handler == null || device == null) {
            onError("Camera is not ready.")
            return
        }
        val frameSize = if (plan.mode == CaptureMode.HS_CONSTRAINED) {
            HS_TARGET_SIZE
        } else {
            NORMAL_TARGET_SIZE
        }

        val reader = ImageReader.newInstance(
            frameSize.width,
            frameSize.height,
            ImageFormat.YUV_420_888,
            2,
        ).apply {
            setOnImageAvailableListener(
                { imageReader ->
                    val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (!isCurrentGeneration(generation)) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    try {
                        onImageAvailable(image)
                    } catch (error: Exception) {
                        image.close()
                        emitError(
                            "Native frame analysis failed: ${error.localizedMessage ?: "unknown"}",
                        )
                    }
                },
                handler,
            )
        }
        imageReader = reader

        val surfaces = mutableListOf<Surface>()
        surfaces += reader.surface

        val maybePreviewSurface = buildPreviewSurface(previewView, frameSize)
        if (maybePreviewSurface != null) {
            previewSurface = maybePreviewSurface
            surfaces += maybePreviewSurface
        } else {
            previewSurface = null
        }

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!isCurrentGeneration(generation)) {
                    session.close()
                    return
                }
                captureSession = session
                applyRepeatingRequest(
                    generation = generation,
                    plan = plan,
                    session = session,
                    surfaces = surfaces,
                    previewView = previewView,
                    allowHsFallback = allowHsFallback,
                    onConfigured = onConfigured,
                    onError = onError,
                )
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                if (!isCurrentGeneration(generation)) {
                    return
                }
                handlePlanFailure(
                    generation = generation,
                    plan = plan,
                    reason = "Capture session configuration failed for camera ${plan.cameraId}.",
                    previewView = previewView,
                    allowHsFallback = allowHsFallback,
                    onConfigured = onConfigured,
                    onError = onError,
                )
            }
        }

        try {
            if (plan.mode == CaptureMode.HS_CONSTRAINED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    sessionCallback,
                    handler,
                )
            } else {
                device.createCaptureSession(
                    surfaces,
                    sessionCallback,
                    handler,
                )
            }
        } catch (error: Exception) {
            handlePlanFailure(
                generation = generation,
                plan = plan,
                reason = "Failed to create capture session: ${error.localizedMessage ?: "unknown"}",
                previewView = previewView,
                allowHsFallback = allowHsFallback,
                onConfigured = onConfigured,
                onError = onError,
            )
        }
    }

    private fun applyRepeatingRequest(
        generation: Long,
        plan: SessionPlan,
        session: CameraCaptureSession,
        surfaces: List<Surface>,
        previewView: TextureView?,
        allowHsFallback: Boolean,
        onConfigured: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val device = cameraDevice
        val handler = cameraHandler
        if (device == null || handler == null) {
            onError("Camera lost before request submission.")
            return
        }
        try {
            if (plan.mode == CaptureMode.HS_CONSTRAINED) {
                if (session !is CameraConstrainedHighSpeedCaptureSession) {
                    throw IllegalStateException("Expected CameraConstrainedHighSpeedCaptureSession.")
                }
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                for (surface in surfaces) {
                    builder.addTarget(surface)
                }
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.targetFpsRange)
                val burst = session.createHighSpeedRequestList(builder.build())
                session.setRepeatingBurst(burst, null, handler)
                activeFpsMode = NativeCameraFpsMode.HS120
                activeTargetFpsUpper = plan.targetFpsRange.upper
                emitDiagnostic(
                    "hs_constrained_started: camera=${plan.cameraId} range=${plan.targetFpsRange.lower}-${plan.targetFpsRange.upper} size=${HS_TARGET_SIZE.width}x${HS_TARGET_SIZE.height}",
                )
            } else {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                for (surface in surfaces) {
                    builder.addTarget(surface)
                }
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.targetFpsRange)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                session.setRepeatingRequest(builder.build(), null, handler)
                activeFpsMode = NativeCameraFpsMode.NORMAL
                activeTargetFpsUpper = plan.targetFpsRange.upper
                scheduleAeAwbLock(generation, builder, session)
            }
            if (isCurrentGeneration(generation)) {
                onConfigured()
            }
        } catch (error: Exception) {
            handlePlanFailure(
                generation = generation,
                plan = plan,
                reason = "Failed to submit capture request: ${error.localizedMessage ?: "unknown"}",
                previewView = previewView,
                allowHsFallback = allowHsFallback,
                onConfigured = onConfigured,
                onError = onError,
            )
        }
    }

    private fun scheduleAeAwbLock(
        generation: Long,
        builder: CaptureRequest.Builder,
        session: CameraCaptureSession,
    ) {
        cancelPendingAeAwbLock()
        val lockRunnable = Runnable {
            val handler = cameraHandler ?: return@Runnable
            if (!isCurrentGeneration(generation)) {
                return@Runnable
            }
            try {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                session.setRepeatingRequest(builder.build(), null, handler)
            } catch (_: Exception) {
                // Continue unlocked when lock request fails.
            }
        }
        pendingAeAwbLockRunnable = lockRunnable
        mainHandler.postDelayed(lockRunnable, AE_AWB_WARMUP_MS)
    }

    private fun cancelPendingAeAwbLock() {
        pendingAeAwbLockRunnable?.let(mainHandler::removeCallbacks)
        pendingAeAwbLockRunnable = null
    }

    private fun buildPreviewSurface(previewView: TextureView?, targetSize: Size): Surface? {
        if (previewView == null || !previewView.isAvailable) {
            return null
        }
        val texture = previewView.surfaceTexture ?: return null
        texture.setDefaultBufferSize(targetSize.width, targetSize.height)
        return Surface(texture)
    }

    private fun buildSessionPlan(
        preferredFacing: NativeCameraFacing,
        preferredFpsMode: NativeCameraFpsMode,
    ): SessionPlan {
        val cameraInfos = loadCameraInfos()
        if (cameraInfos.isEmpty()) {
            throw IllegalStateException("No camera available for native monitoring.")
        }
        val preferredCameras = cameraInfos.filter { it.facing == preferredFacing }
        val rearCameras = cameraInfos.filter { it.facing == NativeCameraFacing.REAR }
        val preferredPrimary = preferredCameras.firstOrNull() ?: cameraInfos.first()

        if (preferredFpsMode == NativeCameraFpsMode.HS120) {
            val preferredHs = preferredCameras.firstOrNull { it.highSpeedRange720 != null }
            val rearHs = rearCameras.firstOrNull { it.highSpeedRange720 != null }
            val runtimeFacing = SensorNativeCameraPolicy.resolveHsRuntimeFacing(
                preferredFacing = preferredFacing,
                preferredFacingSupportsHs = preferredHs != null,
                rearFacingSupportsHs = rearHs != null,
            )
            val hsTargetCamera = when (runtimeFacing) {
                NativeCameraFacing.REAR -> rearHs
                NativeCameraFacing.FRONT -> preferredHs
            }
            val forcedRearRuntime = preferredFacing == NativeCameraFacing.FRONT &&
                runtimeFacing == NativeCameraFacing.REAR

            if (hsTargetCamera != null) {
                val normalFallbackRange = hsTargetCamera.normalRange ?: Range(15, 30)
                return SessionPlan(
                    cameraId = hsTargetCamera.cameraId,
                    runtimeFacing = runtimeFacing,
                    forcedRearRuntime = forcedRearRuntime,
                    mode = CaptureMode.HS_CONSTRAINED,
                    targetFpsRange = hsTargetCamera.highSpeedRange720!!,
                    normalFallbackRange = normalFallbackRange,
                )
            }

            val normalCamera = if (forcedRearRuntime) {
                rearCameras.firstOrNull() ?: preferredPrimary
            } else {
                preferredPrimary
            }
            return SessionPlan(
                cameraId = normalCamera.cameraId,
                runtimeFacing = normalCamera.facing,
                forcedRearRuntime = false,
                mode = CaptureMode.NORMAL,
                targetFpsRange = normalCamera.normalRange ?: Range(15, 30),
                normalFallbackRange = null,
            )
        }

        return SessionPlan(
            cameraId = preferredPrimary.cameraId,
            runtimeFacing = preferredPrimary.facing,
            forcedRearRuntime = false,
            mode = CaptureMode.NORMAL,
            targetFpsRange = preferredPrimary.normalRange ?: Range(15, 30),
            normalFallbackRange = null,
        )
    }

    private fun loadCameraInfos(): List<CameraInfo> {
        val infos = mutableListOf<CameraInfo>()
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = when (
                characteristics.get(CameraCharacteristics.LENS_FACING)
            ) {
                CameraMetadata.LENS_FACING_FRONT -> NativeCameraFacing.FRONT
                CameraMetadata.LENS_FACING_BACK -> NativeCameraFacing.REAR
                else -> continue
            }
            val aeRanges = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toSet()
                ?: emptySet()
            val normalRange = SensorNativeCameraPolicy.selectHighestNormalFrameRateRange(aeRanges)
                ?: SensorNativeCameraPolicy.selectHighestFrameRateRange(aeRanges)
            val highSpeedRange720 = selectHsRangeFor720(characteristics)
            infos += CameraInfo(
                cameraId = cameraId,
                facing = facing,
                normalRange = normalRange,
                highSpeedRange720 = highSpeedRange720,
            )
        }
        return infos
    }

    private fun selectHsRangeFor720(
        characteristics: CameraCharacteristics,
    ): Range<Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet()
            ?: emptySet()
        if (!capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
            )
        ) {
            return null
        }
        val streamMap = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        if (!streamMap.supportsHighSpeedSize(HS_TARGET_SIZE)) {
            return null
        }
        val ranges = streamMap.getHighSpeedVideoFpsRangesFor(HS_TARGET_SIZE)?.toSet() ?: emptySet()
        return SensorNativeCameraPolicy.selectPreferredHsRange(ranges)
    }

    private fun StreamConfigurationMap.supportsHighSpeedSize(targetSize: Size): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        return highSpeedVideoSizes?.any { it == targetSize } == true
    }

    private fun handlePlanFailure(
        generation: Long,
        plan: SessionPlan,
        reason: String,
        previewView: TextureView?,
        allowHsFallback: Boolean,
        onConfigured: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isCurrentGeneration(generation)) {
            return
        }
        if (allowHsFallback && plan.mode == CaptureMode.HS_CONSTRAINED) {
            val normalFallbackRange = plan.normalFallbackRange
            if (normalFallbackRange == null) {
                onError(reason)
                return
            }
            emitHsFallbackDiagnosticOnce("hs_fallback_normal: $reason")
            closeActiveResources()
            openCameraWithPlan(
                generation = generation,
                previewView = previewView,
                plan = plan.copy(
                    mode = CaptureMode.NORMAL,
                    targetFpsRange = normalFallbackRange,
                    normalFallbackRange = null,
                ),
                allowHsFallback = false,
                onConfigured = onConfigured,
                onError = onError,
            )
            return
        }
        onError(reason)
    }

    private fun emitHsFallbackDiagnosticOnce(message: String) {
        if (hsFallbackDiagnosticEmitted) {
            return
        }
        hsFallbackDiagnosticEmitted = true
        emitDiagnostic(message)
    }

    private fun isCurrentGeneration(targetGeneration: Long): Boolean {
        return targetGeneration == generation
    }

    private data class CameraInfo(
        val cameraId: String,
        val facing: NativeCameraFacing,
        val normalRange: Range<Int>?,
        val highSpeedRange720: Range<Int>?,
    )

    private enum class CaptureMode {
        NORMAL,
        HS_CONSTRAINED,
    }

    private data class SessionPlan(
        val cameraId: String,
        val runtimeFacing: NativeCameraFacing,
        val forcedRearRuntime: Boolean,
        val mode: CaptureMode,
        val targetFpsRange: Range<Int>,
        val normalFallbackRange: Range<Int>?,
    )
}

internal object SensorNativeCameraPolicy {
    private const val NORMAL_MODE_MAX_UPPER_FPS = 60
    private const val AE_AWB_WARMUP_MS = 400L

    data class FrameRateSelection(
        val primaryRange: Range<Int>,
        val primaryMode: NativeCameraFpsMode,
        val fallbackRange: Range<Int>?,
        val fallbackActivated: Boolean,
    )

    data class FrameRateSelectionBounds(
        val primaryBounds: Pair<Int, Int>,
        val primaryMode: NativeCameraFpsMode,
        val fallbackBounds: Pair<Int, Int>?,
        val fallbackActivated: Boolean,
    )

    data class CameraFacingSelection(
        val selected: NativeCameraFacing,
        val fallbackUsed: Boolean,
    )

    fun selectFrameRateSelection(
        ranges: Set<Range<Int>>?,
        preferredMode: NativeCameraFpsMode,
    ): FrameRateSelection? {
        val bounds = ranges?.map { it.lower to it.upper }
        val selected = selectFrameRateSelectionBounds(bounds, preferredMode) ?: return null
        return FrameRateSelection(
            primaryRange = Range(selected.primaryBounds.first, selected.primaryBounds.second),
            primaryMode = selected.primaryMode,
            fallbackRange = selected.fallbackBounds?.let { Range(it.first, it.second) },
            fallbackActivated = selected.fallbackActivated,
        )
    }

    fun selectFrameRateSelectionBounds(
        bounds: Iterable<Pair<Int, Int>>?,
        preferredMode: NativeCameraFpsMode,
    ): FrameRateSelectionBounds? {
        if (bounds == null) {
            return null
        }
        val boundsList = bounds.toList()
        if (boundsList.isEmpty()) {
            return null
        }
        val normalBounds = selectHighestNormalFrameRateBounds(boundsList)
        val fixed120Bounds = boundsList.firstOrNull { it.first == 120 && it.second == 120 }
        val variable120Bounds = boundsList.firstOrNull { it.first == 30 && it.second == 120 }
        return when (preferredMode) {
            NativeCameraFpsMode.HS120 -> {
                val hsPrimary = fixed120Bounds ?: variable120Bounds
                if (hsPrimary != null) {
                    val fallback = normalBounds?.takeIf { it != hsPrimary }
                    FrameRateSelectionBounds(
                        primaryBounds = hsPrimary,
                        primaryMode = NativeCameraFpsMode.HS120,
                        fallbackBounds = fallback,
                        fallbackActivated = false,
                    )
                } else {
                    val fallback = normalBounds ?: selectHighestFrameRateBounds(boundsList)
                    if (fallback == null) {
                        null
                    } else {
                        FrameRateSelectionBounds(
                            primaryBounds = fallback,
                            primaryMode = NativeCameraFpsMode.NORMAL,
                            fallbackBounds = null,
                            fallbackActivated = true,
                        )
                    }
                }
            }

            NativeCameraFpsMode.NORMAL -> {
                val selected = normalBounds ?: selectHighestFrameRateBounds(boundsList) ?: return null
                FrameRateSelectionBounds(
                    primaryBounds = selected,
                    primaryMode = NativeCameraFpsMode.NORMAL,
                    fallbackBounds = null,
                    fallbackActivated = false,
                )
            }
        }
    }

    fun selectPreferredHsRange(ranges: Set<Range<Int>>?): Range<Int>? {
        if (ranges == null || ranges.isEmpty()) {
            return null
        }
        return ranges.firstOrNull { it.lower == 120 && it.upper == 120 }
            ?: ranges.firstOrNull { it.lower == 30 && it.upper == 120 }
    }

    fun selectHighestFrameRateRange(ranges: Set<Range<Int>>?): Range<Int>? {
        val selectedBounds = selectHighestFrameRateBounds(ranges?.map { it.lower to it.upper })
        if (selectedBounds == null) {
            return null
        }
        return Range(selectedBounds.first, selectedBounds.second)
    }

    fun selectHighestFrameRateBounds(
        bounds: Iterable<Pair<Int, Int>>?,
    ): Pair<Int, Int>? {
        if (bounds == null) {
            return null
        }
        return bounds.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.second }, { it.first }))
    }

    fun selectHighestNormalFrameRateRange(ranges: Set<Range<Int>>?): Range<Int>? {
        val selectedBounds = selectHighestNormalFrameRateBounds(ranges?.map { it.lower to it.upper })
        if (selectedBounds == null) {
            return null
        }
        return Range(selectedBounds.first, selectedBounds.second)
    }

    fun selectHighestNormalFrameRateBounds(
        bounds: Iterable<Pair<Int, Int>>?,
    ): Pair<Int, Int>? {
        if (bounds == null) {
            return null
        }
        val normalBounds = bounds.filter { it.second <= NORMAL_MODE_MAX_UPPER_FPS }
        if (normalBounds.isEmpty()) {
            return null
        }
        return normalBounds.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.second }, { it.first }))
    }

    fun resolveHsRuntimeFacing(
        preferredFacing: NativeCameraFacing,
        preferredFacingSupportsHs: Boolean,
        rearFacingSupportsHs: Boolean,
    ): NativeCameraFacing {
        if (preferredFacingSupportsHs) {
            return preferredFacing
        }
        if (preferredFacing == NativeCameraFacing.FRONT && rearFacingSupportsHs) {
            return NativeCameraFacing.REAR
        }
        return preferredFacing
    }

    fun shouldLockAeAwb(elapsedMs: Long): Boolean {
        return elapsedMs >= AE_AWB_WARMUP_MS
    }

    fun selectCameraFacing(
        preferred: NativeCameraFacing,
        hasRear: Boolean,
        hasFront: Boolean,
    ): CameraFacingSelection? {
        if (!hasRear && !hasFront) {
            return null
        }
        return when (preferred) {
            NativeCameraFacing.REAR -> {
                if (hasRear) {
                    CameraFacingSelection(selected = NativeCameraFacing.REAR, fallbackUsed = false)
                } else {
                    CameraFacingSelection(selected = NativeCameraFacing.FRONT, fallbackUsed = true)
                }
            }

            NativeCameraFacing.FRONT -> {
                if (hasFront) {
                    CameraFacingSelection(selected = NativeCameraFacing.FRONT, fallbackUsed = false)
                } else {
                    CameraFacingSelection(selected = NativeCameraFacing.REAR, fallbackUsed = true)
                }
            }
        }
    }
}
