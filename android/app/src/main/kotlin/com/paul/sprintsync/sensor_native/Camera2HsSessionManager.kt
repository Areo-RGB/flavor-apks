package com.paul.sprintsync.sensor_native

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicBoolean

internal class Camera2HsSessionManager(
    private val activity: ComponentActivity,
    private val mainHandler: Handler,
    private val emitError: (String) -> Unit,
    private val emitDiagnostic: (String) -> Unit,
) {
    companion object {
        private const val MAX_HS_OUTPUT_SURFACES = 1
    }

    private val cameraManager: CameraManager =
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var generation = 0L

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var glExtractor: GlLumaExtractor? = null
    private var targetFpsUpper: Int? = null
    private var activeMode: NativeCameraFpsMode = NativeCameraFpsMode.NORMAL

    fun currentTargetFpsUpper(): Int? = targetFpsUpper

    fun currentMode(): NativeCameraFpsMode = activeMode

    fun requestReadback(
        roiCenterX: Double,
        roiWidth: Double,
        callback: (GlLumaExtractor.LumaReadbackResult?) -> Unit,
    ) {
        val extractor = glExtractor
        if (extractor == null) {
            callback(null)
            return
        }
        extractor.requestReadback(
            roiCenterX = roiCenterX,
            roiWidth = roiWidth,
            callback = callback,
        )
    }

    fun stop() {
        generation += 1
        closeActiveResources()
        shutdownCameraThread()
        targetFpsUpper = null
        activeMode = NativeCameraFpsMode.NORMAL
    }

    @SuppressLint("MissingPermission")
    fun start(
        preferredFacing: NativeCameraFacing,
        onFrameConsumed: (Long) -> Unit,
        onStarted: (Int) -> Unit,
        onStartError: (String) -> Unit,
    ) {
        stop()
        ensureCameraThread()
        val currentGeneration = generation + 1
        generation = currentGeneration
        val handler = cameraHandler
        if (handler == null) {
            onStartError("HS camera thread failed to initialize.")
            return
        }

        val plan = try {
            buildSessionPlan(preferredFacing = preferredFacing)
        } catch (error: Exception) {
            onStartError(error.localizedMessage ?: "Failed to build HS session plan.")
            return
        }

        if (plan.fallbackUsed) {
            emitDiagnostic(
                "hs_forced_camera_runtime: requested ${preferredFacing.wireName} not HS-capable; using ${plan.facing.wireName}.",
            )
        }

        val extractor = GlLumaExtractor(
            frameWidth = plan.size.width,
            frameHeight = plan.size.height,
            onFrameConsumed = onFrameConsumed,
            emitError = emitError,
        )
        try {
            extractor.start()
        } catch (error: Exception) {
            onStartError(error.localizedMessage ?: "Failed to initialize HS GL extractor.")
            return
        }
        glExtractor = extractor

        val startHandled = AtomicBoolean(false)
        try {
            cameraManager.openCamera(
                plan.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (!isCurrentGeneration(currentGeneration)) {
                            device.close()
                            return
                        }
                        cameraDevice = device
                        createConstrainedSession(
                            generation = currentGeneration,
                            device = device,
                            plan = plan,
                            startHandled = startHandled,
                            onStarted = onStarted,
                            onStartError = onStartError,
                        )
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (!isCurrentGeneration(currentGeneration)) {
                            return
                        }
                        closeActiveResources()
                        val message = "HS camera disconnected."
                        emitError(message)
                        if (startHandled.compareAndSet(false, true)) {
                            onStartError(message)
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (!isCurrentGeneration(currentGeneration)) {
                            return
                        }
                        closeActiveResources()
                        val message = "HS camera error code=$error."
                        emitError(message)
                        if (startHandled.compareAndSet(false, true)) {
                            onStartError(message)
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            closeActiveResources()
            onStartError("Failed to open HS camera: ${error.localizedMessage ?: "unknown"}")
        }
    }

    private fun createConstrainedSession(
        generation: Long,
        device: CameraDevice,
        plan: HsSessionPlan,
        startHandled: AtomicBoolean,
        onStarted: (Int) -> Unit,
        onStartError: (String) -> Unit,
    ) {
        val handler = cameraHandler
        val extractor = glExtractor
        if (handler == null || extractor == null) {
            if (startHandled.compareAndSet(false, true)) {
                onStartError("HS camera resources missing during session creation.")
            }
            return
        }
        try {
            val surfaces = listOf(extractor.getSurface())
            if (surfaces.size > MAX_HS_OUTPUT_SURFACES) {
                val message = "HS constrained session requires <=$MAX_HS_OUTPUT_SURFACES surface(s), got ${surfaces.size}."
                emitDiagnostic("hs_surface_guard_violation: $message")
                if (startHandled.compareAndSet(false, true)) {
                    onStartError(message)
                }
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                if (startHandled.compareAndSet(false, true)) {
                    onStartError("Constrained high-speed session requires API 23+.")
                }
                return
            }
            device.createConstrainedHighSpeedCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isCurrentGeneration(generation)) {
                            session.close()
                            return
                        }
                        val hsSession = session as? CameraConstrainedHighSpeedCaptureSession
                        if (hsSession == null) {
                            session.close()
                            if (startHandled.compareAndSet(false, true)) {
                                onStartError("Invalid HS capture session type.")
                            }
                            return
                        }
                        captureSession = hsSession
                        submitRepeatingBurst(
                            generation = generation,
                            device = device,
                            hsSession = hsSession,
                            plan = plan,
                            startHandled = startHandled,
                            onStarted = onStarted,
                            onStartError = onStartError,
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (!isCurrentGeneration(generation)) {
                            return
                        }
                        closeActiveResources()
                        if (startHandled.compareAndSet(false, true)) {
                            onStartError("HS capture session configuration failed.")
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            closeActiveResources()
            if (startHandled.compareAndSet(false, true)) {
                onStartError("Failed to create HS session: ${error.localizedMessage ?: "unknown"}")
            }
        }
    }

    private fun submitRepeatingBurst(
        generation: Long,
        device: CameraDevice,
        hsSession: CameraConstrainedHighSpeedCaptureSession,
        plan: HsSessionPlan,
        startHandled: AtomicBoolean,
        onStarted: (Int) -> Unit,
        onStartError: (String) -> Unit,
    ) {
        val handler = cameraHandler
        val extractor = glExtractor
        if (handler == null || extractor == null) {
            closeActiveResources()
            if (startHandled.compareAndSet(false, true)) {
                onStartError("HS request submission failed due to missing resources.")
            }
            return
        }
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val surface = extractor.getSurface()
            builder.addTarget(surface)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
            val burst = hsSession.createHighSpeedRequestList(builder.build())
            hsSession.setRepeatingBurst(burst, null, handler)
            if (!isCurrentGeneration(generation)) {
                return
            }
            targetFpsUpper = plan.fpsRange.upper
            activeMode = NativeCameraFpsMode.HS120
            if (startHandled.compareAndSet(false, true)) {
                onStarted(plan.fpsRange.upper)
            }
        } catch (error: Exception) {
            closeActiveResources()
            if (startHandled.compareAndSet(false, true)) {
                onStartError("Failed to start HS repeating request: ${error.localizedMessage ?: "unknown"}")
            }
        }
    }

    private fun buildSessionPlan(preferredFacing: NativeCameraFacing): HsSessionPlan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw IllegalStateException("HS mode requires API 23+.")
        }

        val candidates = mutableListOf<HsSessionPlan>()
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: continue
            val facing = when (lensFacing) {
                CameraMetadata.LENS_FACING_FRONT -> NativeCameraFacing.FRONT
                CameraMetadata.LENS_FACING_BACK -> NativeCameraFacing.REAR
                else -> continue
            }
            val capabilities = characteristics
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toSet()
                ?: emptySet()
            if (!capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
                )
            ) {
                continue
            }
            val streamMap = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: continue
            val size = selectPreferredSize(streamMap) ?: continue
            val fpsRange = SensorNativeCameraPolicy.selectPreferredHsRange(
                streamMap.getHighSpeedVideoFpsRangesFor(size)?.asIterable(),
            ) ?: continue
            candidates += HsSessionPlan(
                cameraId = cameraId,
                facing = facing,
                fallbackUsed = false,
                size = size,
                fpsRange = fpsRange,
            )
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException("No constrained high-speed camera path available.")
        }

        val preferredCandidate = candidates.firstOrNull { it.facing == preferredFacing }
        if (preferredCandidate != null) {
            return preferredCandidate
        }
        val selected = candidates.first()
        return selected.copy(fallbackUsed = true)
    }

    private fun selectPreferredSize(streamMap: StreamConfigurationMap): Size? {
        val sizes = streamMap.highSpeedVideoSizes?.toList() ?: return null
        if (sizes.isEmpty()) {
            return null
        }
        val exact720 = Size(1280, 720)
        if (sizes.any { it == exact720 }) {
            val range = SensorNativeCameraPolicy.selectPreferredHsRange(
                streamMap.getHighSpeedVideoFpsRangesFor(exact720)?.asIterable(),
            )
            if (range != null) {
                return exact720
            }
        }
        return sizes
            .sortedByDescending { it.width * it.height }
            .firstOrNull { size ->
                SensorNativeCameraPolicy.selectPreferredHsRange(
                    streamMap.getHighSpeedVideoFpsRangesFor(size)?.asIterable(),
                ) != null
            }
    }

    private fun ensureCameraThread() {
        if (cameraThread != null) {
            return
        }
        val thread = HandlerThread("Camera2HsThread")
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun shutdownCameraThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun closeActiveResources() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
            // ignored
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        glExtractor?.release()
        glExtractor = null
        targetFpsUpper = null
        activeMode = NativeCameraFpsMode.NORMAL
    }

    private fun isCurrentGeneration(targetGeneration: Long): Boolean {
        return targetGeneration == generation
    }

    private data class HsSessionPlan(
        val cameraId: String,
        val facing: NativeCameraFacing,
        val fallbackUsed: Boolean,
        val size: Size,
        val fpsRange: Range<Int>,
    )
}
