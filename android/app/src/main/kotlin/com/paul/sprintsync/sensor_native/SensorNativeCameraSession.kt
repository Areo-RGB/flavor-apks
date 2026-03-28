package com.paul.sprintsync.sensor_native

import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.SystemClock
import android.util.Range
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService

internal class SensorNativeCameraSession(
    private val activity: ComponentActivity,
    private val mainHandler: Handler,
    private val analyzerExecutor: ExecutorService,
    private val analyzer: ImageAnalysis.Analyzer,
    private val emitError: (String) -> Unit,
) {
    private var camera: Camera? = null
    private var bindGeneration = 0L
    private var pendingAeAwbLockRunnable: Runnable? = null
    private var previewUseCase: Preview? = null
    private var activeTargetFpsUpper: Int? = null

    fun stop(provider: ProcessCameraProvider?) {
        cancelPendingAeAwbLock()
        provider?.unbindAll()
        camera = null
        previewUseCase = null
        activeTargetFpsUpper = null
    }

    fun currentTargetFpsUpper(): Int? = activeTargetFpsUpper

    fun bindAndConfigure(
        provider: ProcessCameraProvider,
        previewView: PreviewView?,
        includePreview: Boolean,
        preferredFacing: NativeCameraFacing,
    ) {
        val binding = bindCameraUseCases(
            provider = provider,
            previewView = previewView,
            includePreview = includePreview,
            preferredFacing = preferredFacing,
        )
        applyUnlockedPolicy(binding)
    }

    private fun bindCameraUseCases(
        provider: ProcessCameraProvider,
        previewView: PreviewView?,
        includePreview: Boolean,
        preferredFacing: NativeCameraFacing,
    ): CameraBinding {
        cancelPendingAeAwbLock()
        provider.unbindAll()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        imageAnalysis.setAnalyzer(analyzerExecutor, analyzer)

        val facingSelection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = preferredFacing,
            hasRear = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
            hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
        ) ?: throw IllegalStateException("No camera available for native monitoring.")
        if (facingSelection.fallbackUsed) {
            emitError(
                "Requested ${preferredFacing.wireName} camera unavailable; using ${facingSelection.selected.wireName}.",
            )
        }
        val selector = when (facingSelection.selected) {
            NativeCameraFacing.REAR -> CameraSelector.DEFAULT_BACK_CAMERA
            NativeCameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        val localPreviewView = if (includePreview) previewView else null
        val preview = localPreviewView?.let { view ->
            Preview.Builder().build().also { useCase ->
                useCase.setSurfaceProvider(view.surfaceProvider)
            }
        }
        previewUseCase = preview

        val boundCamera = if (preview == null) {
            provider.bindToLifecycle(activity, selector, imageAnalysis)
        } else {
            provider.bindToLifecycle(activity, selector, preview, imageAnalysis)
        }
        camera = boundCamera
        bindGeneration += 1
        return CameraBinding(
            camera = boundCamera,
            previewBound = preview != null,
            generation = bindGeneration,
        )
    }

    private fun applyUnlockedPolicy(binding: CameraBinding) {
        val fpsRange = SensorNativeCameraPolicy.selectPreferredNormalFrameRateRange(
            binding.camera.cameraInfo.supportedFrameRateRanges,
        )
        if (fpsRange == null) {
            emitError("No supported FPS range reported; continuing with camera defaults.")
            activeTargetFpsUpper = null
            return
        }
        activeTargetFpsUpper = fpsRange.upper

        applyCamera2Options(
            binding = binding,
            fpsRange = fpsRange,
            lockAeAwb = false,
        ) { success, error ->
            if (!success) {
                handleUnlockedPolicyFailure(error ?: "unknown")
                return@applyCamera2Options
            }
            scheduleAeAwbLock(binding, fpsRange)
        }
    }

    private fun handleUnlockedPolicyFailure(reason: String) {
        emitError("Failed to apply max FPS controls; keeping preview with camera defaults: $reason")
    }

    private fun scheduleAeAwbLock(binding: CameraBinding, fpsRange: Range<Int>) {
        cancelPendingAeAwbLock()
        val warmupStartMs = SystemClock.elapsedRealtime()
        val lockRunnable = Runnable {
            if (!isCurrentBinding(binding)) {
                return@Runnable
            }
            val elapsedMs = SystemClock.elapsedRealtime() - warmupStartMs
            if (!SensorNativeCameraPolicy.shouldLockAeAwb(elapsedMs)) {
                return@Runnable
            }
            applyCamera2Options(
                binding = binding,
                fpsRange = fpsRange,
                lockAeAwb = true,
            ) { success, error ->
                if (!success) {
                    emitError("Failed to lock AE/AWB; continuing unlocked: ${error ?: "unknown"}")
                }
            }
        }
        pendingAeAwbLockRunnable = lockRunnable
        mainHandler.postDelayed(lockRunnable, SensorNativeCameraPolicy.AE_AWB_WARMUP_MS)
    }

    private fun applyCamera2Options(
        binding: CameraBinding,
        fpsRange: Range<Int>,
        lockAeAwb: Boolean,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        val requestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lockAeAwb)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lockAeAwb)
            .build()
        val control = Camera2CameraControl.from(binding.camera.cameraControl)
        val future = control.setCaptureRequestOptions(requestOptions)
        future.addListener(
            {
                if (!isCurrentBinding(binding)) {
                    return@addListener
                }
                try {
                    future.get()
                    onComplete(true, null)
                } catch (error: Exception) {
                    if (error is InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    onComplete(false, error.localizedMessage ?: "unknown")
                }
            },
            ContextCompat.getMainExecutor(activity),
        )
    }

    private fun cancelPendingAeAwbLock() {
        pendingAeAwbLockRunnable?.let(mainHandler::removeCallbacks)
        pendingAeAwbLockRunnable = null
    }

    private fun isCurrentBinding(binding: CameraBinding): Boolean {
        return binding.generation == bindGeneration && camera === binding.camera
    }

    private data class CameraBinding(
        val camera: Camera,
        val previewBound: Boolean,
        val generation: Long,
    )
}

internal object SensorNativeCameraPolicy {
    const val AE_AWB_WARMUP_MS = 400L
    private const val NORMAL_TARGET_FPS_UPPER = 60

    data class CameraFacingSelection(
        val selected: NativeCameraFacing,
        val fallbackUsed: Boolean,
    )

    fun shouldLockAeAwb(elapsedMs: Long): Boolean {
        return elapsedMs >= AE_AWB_WARMUP_MS
    }

    fun selectPreferredNormalFrameRateRange(ranges: Set<Range<Int>>?): Range<Int>? {
        val selectedBounds = selectPreferredNormalFrameRateBounds(ranges?.map { it.lower to it.upper })
        if (selectedBounds == null) {
            return null
        }
        return Range(selectedBounds.first, selectedBounds.second)
    }

    fun selectPreferredNormalFrameRateBounds(bounds: Iterable<Pair<Int, Int>>?): Pair<Int, Int>? {
        if (bounds == null) {
            return null
        }
        val boundList = bounds.toList()
        if (boundList.isEmpty()) {
            return null
        }
        val exactTargetUpper = boundList
            .filter { it.second == NORMAL_TARGET_FPS_UPPER }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.first })
        if (exactTargetUpper != null) {
            return exactTargetUpper
        }
        return selectHighestFrameRateBounds(boundList)
    }

    fun selectHighestFrameRateRange(ranges: Set<Range<Int>>?): Range<Int>? {
        val selectedBounds = selectHighestFrameRateBounds(ranges?.map { it.lower to it.upper })
        if (selectedBounds == null) {
            return null
        }
        return Range(selectedBounds.first, selectedBounds.second)
    }

    fun selectHighestFrameRateBounds(bounds: Iterable<Pair<Int, Int>>?): Pair<Int, Int>? {
        if (bounds == null) {
            return null
        }
        return bounds.maxWithOrNull(compareBy<Pair<Int, Int>>({ it.second }, { it.first }))
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
