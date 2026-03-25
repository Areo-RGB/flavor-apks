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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import io.flutter.embedding.android.FlutterActivity
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class Camera2HsRecordingManager(
    private val activity: FlutterActivity,
    private val mainHandler: Handler,
    private val emitError: (String) -> Unit,
    private val emitDiagnostic: (String) -> Unit,
) {
    companion object {
        private const val MAX_HS_OUTPUT_SURFACES = 1
        private const val ENCODER_MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEFAULT_TARGET_BITRATE = 10_000_000
        private const val EOS_TIMEOUT_MS = 3000L
    }

    private val cameraManager: CameraManager =
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var generation = 0L

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex: Int = -1
    private var muxerStarted = false
    private var outputFile: File? = null
    private var activeRunId: String? = null
    private var targetFpsUpper: Int? = null

    private val writeLock = Any()
    private val encodedPtsUs = mutableListOf<Long>()
    private val captureSensorNanos = mutableListOf<Long>()
    private var encoderEosLatch: CountDownLatch? = null

    fun currentTargetFpsUpper(): Int? = targetFpsUpper

    @SuppressLint("MissingPermission")
    fun start(
        runId: String,
        preferredFacing: NativeCameraFacing,
        onStarted: (Int) -> Unit,
        onStartError: (String) -> Unit,
    ) {
        stop()
        ensureCameraThread()
        val handler = cameraHandler
        if (handler == null) {
            onStartError("HS recording thread failed to initialize.")
            return
        }

        val sessionPlan = try {
            buildSessionPlan(preferredFacing = preferredFacing)
        } catch (error: Exception) {
            onStartError(error.localizedMessage ?: "Failed to build HS recording session.")
            return
        }
        if (sessionPlan.fallbackUsed) {
            emitDiagnostic(
                "hs_recording_camera_fallback: requested ${preferredFacing.wireName}, using ${sessionPlan.facing.wireName}.",
            )
        }

        val recordingFile = createOutputFile(runId)
        try {
            setupEncoder(
                width = sessionPlan.size.width,
                height = sessionPlan.size.height,
                targetFps = sessionPlan.fpsRange.upper,
                outputPath = recordingFile.absolutePath,
                callbackHandler = handler,
            )
        } catch (error: Exception) {
            clearEncodingResources()
            onStartError(error.localizedMessage ?: "Failed to initialize hardware encoder.")
            return
        }

        activeRunId = runId
        outputFile = recordingFile
        val currentGeneration = generation + 1
        generation = currentGeneration
        val startHandled = AtomicBoolean(false)

        try {
            cameraManager.openCamera(
                sessionPlan.cameraId,
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
                            plan = sessionPlan,
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
                        stop()
                        val message = "HS recording camera disconnected."
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
                        stop()
                        val message = "HS recording camera error code=$error."
                        emitError(message)
                        if (startHandled.compareAndSet(false, true)) {
                            onStartError(message)
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            stop()
            onStartError("Failed to open HS recording camera: ${error.localizedMessage ?: "unknown"}")
        }
    }

    fun stop(): HsRecordedVideoArtifact? {
        generation += 1
        closeCameraResources()
        return finalizeEncoding()
    }

    fun release() {
        stop()
        shutdownCameraThread()
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
        val inputSurface = encoderInputSurface
        if (handler == null || inputSurface == null) {
            if (startHandled.compareAndSet(false, true)) {
                onStartError("HS recording resources missing during session setup.")
            }
            return
        }
        try {
            val surfaces = listOf(inputSurface)
            if (surfaces.size > MAX_HS_OUTPUT_SURFACES) {
                val message = "HS recording requires <=$MAX_HS_OUTPUT_SURFACES output surface(s), got ${surfaces.size}."
                emitDiagnostic("hs_recording_surface_guard_violation: $message")
                if (startHandled.compareAndSet(false, true)) {
                    onStartError(message)
                }
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                if (startHandled.compareAndSet(false, true)) {
                    onStartError("Constrained high-speed recording requires API 23+.")
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
                                onStartError("Invalid HS recording capture session.")
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
                        stop()
                        if (startHandled.compareAndSet(false, true)) {
                            onStartError("HS recording capture session configuration failed.")
                        }
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            stop()
            if (startHandled.compareAndSet(false, true)) {
                onStartError("Failed to create HS recording session: ${error.localizedMessage ?: "unknown"}")
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
        val inputSurface = encoderInputSurface
        if (handler == null || inputSurface == null) {
            if (startHandled.compareAndSet(false, true)) {
                onStartError("HS recording request submission failed due to missing resources.")
            }
            return
        }
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(inputSurface)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
            val burst = hsSession.createHighSpeedRequestList(builder.build())
            hsSession.setRepeatingBurst(
                burst,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult,
                    ) {
                        val sensorNanos = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        synchronized(writeLock) {
                            captureSensorNanos.add(sensorNanos)
                        }
                    }
                },
                handler,
            )
            if (!isCurrentGeneration(generation)) {
                return
            }
            targetFpsUpper = plan.fpsRange.upper
            if (startHandled.compareAndSet(false, true)) {
                onStarted(plan.fpsRange.upper)
            }
        } catch (error: Exception) {
            stop()
            if (startHandled.compareAndSet(false, true)) {
                onStartError("Failed to start HS recording request: ${error.localizedMessage ?: "unknown"}")
            }
        }
    }

    private fun setupEncoder(
        width: Int,
        height: Int,
        targetFps: Int,
        outputPath: String,
        callbackHandler: Handler,
    ) {
        val outputFormat = MediaFormat.createVideoFormat(ENCODER_MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, max(DEFAULT_TARGET_BITRATE, width * height * targetFps))
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val codec = MediaCodec.createEncoderByType(ENCODER_MIME_AVC)
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Surface input encoder does not use byte-buffer input.
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo,
                ) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        synchronized(writeLock) {
                            if (muxerStarted) {
                                val dstInfo = MediaCodec.BufferInfo()
                                dstInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer?.writeSampleData(muxerTrackIndex, buffer, dstInfo)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    encodedPtsUs.add(info.presentationTimeUs)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderEosLatch?.countDown()
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    emitError("HS recording encoder error: ${e.localizedMessage ?: "unknown"}")
                    encoderEosLatch?.countDown()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    synchronized(writeLock) {
                        val activeMuxer = muxer ?: return
                        if (muxerStarted) {
                            return
                        }
                        muxerTrackIndex = activeMuxer.addTrack(format)
                        activeMuxer.start()
                        muxerStarted = true
                    }
                }
            },
            callbackHandler,
        )

        val nextMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()

        synchronized(writeLock) {
            encoder = codec
            encoderInputSurface = surface
            muxer = nextMuxer
            muxerTrackIndex = -1
            muxerStarted = false
            encodedPtsUs.clear()
            captureSensorNanos.clear()
        }
        codec.start()
    }

    private fun finalizeEncoding(): HsRecordedVideoArtifact? {
        val runId = activeRunId
        if (runId == null) {
            clearEncodingResources()
            return null
        }
        try {
            encoderEosLatch = CountDownLatch(1)
            encoder?.signalEndOfInputStream()
            encoderEosLatch?.await(EOS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Continue shutdown best-effort.
        } finally {
            encoderEosLatch = null
        }

        val artifact = synchronized(writeLock) {
            val file = outputFile
            val copiedPtsUs = encodedPtsUs.toList()
            val copiedSensor = captureSensorNanos.toList()
            if (file != null && file.exists() && copiedPtsUs.isNotEmpty() && copiedSensor.isNotEmpty()) {
                HsRecordedVideoArtifact(
                    runId = runId,
                    outputPath = file.absolutePath,
                    encodedPtsUs = copiedPtsUs,
                    captureSensorNanos = copiedSensor,
                )
            } else {
                null
            }
        }
        clearEncodingResources()
        return artifact
    }

    private fun clearEncodingResources() {
        synchronized(writeLock) {
            try {
                encoder?.stop()
            } catch (_: Exception) {
                // ignored
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
                // ignored
            }
            encoder = null
            encoderInputSurface?.release()
            encoderInputSurface = null
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (_: Exception) {
                // ignored
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
                // ignored
            }
            muxer = null
            muxerTrackIndex = -1
            muxerStarted = false
        }
        activeRunId = null
        outputFile = null
        targetFpsUpper = null
    }

    private fun closeCameraResources() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
            // ignored
        }
        try {
            captureSession?.abortCaptures()
        } catch (_: Exception) {
            // ignored
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
            // ignored
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
            // ignored
        }
        cameraDevice = null
    }

    private fun ensureCameraThread() {
        if (cameraThread != null && cameraHandler != null) {
            return
        }
        val thread = HandlerThread("HsRecordingCamera2Thread")
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun shutdownCameraThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun isCurrentGeneration(value: Long): Boolean {
        return generation == value
    }

    private fun createOutputFile(runId: String): File {
        val dir = File(activity.cacheDir, "hs_recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${runId}_${System.currentTimeMillis()}.mp4")
    }

    private fun buildSessionPlan(preferredFacing: NativeCameraFacing): HsSessionPlan {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw IllegalStateException("HS recording requires API 23+.")
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
            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
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
            throw IllegalStateException("No constrained high-speed recording camera path available.")
        }
        val preferredCandidate = candidates.firstOrNull { it.facing == preferredFacing }
        if (preferredCandidate != null) {
            return preferredCandidate
        }
        return candidates.first().copy(fallbackUsed = true)
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
        return sizes.maxByOrNull { it.width * it.height }
    }

    private data class HsSessionPlan(
        val cameraId: String,
        val facing: NativeCameraFacing,
        val fallbackUsed: Boolean,
        val size: Size,
        val fpsRange: android.util.Range<Int>,
    )
}
