package com.paul.sprintsync.sensor_native

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
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
        const val METHOD_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_methods"
        const val EVENT_CHANNEL_NAME = "com.paul.sprintsync/sensor_native_events"
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

    private var cameraProvider: ProcessCameraProvider? = null
    private val detectionMath = NativeDetectionMath(config)

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
        stopNativeMonitoringInternal()
        analyzerExecutor.shutdown()
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
        offsetSmoother.reset()
        detectionMath.resetRun()
        frameDiffer.reset()

        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    bindAnalysisUseCase(provider)
                    cameraProvider = provider
                    monitoring = true
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
        monitoring = false
        cameraProvider?.unbindAll()
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
        config = NativeMonitoringConfig.fromMap(call.argument<Any>("config"))
        detectionMath.updateConfig(config)
        emitState(if (monitoring) "monitoring" else "idle")
    }

    private fun resetNativeRun() {
        streamFrameCount = 0L
        processedFrameCount = 0L
        detectionMath.resetRun()
        frameDiffer.reset()
        emitState(if (monitoring) "monitoring" else "idle")
    }

    private fun bindAnalysisUseCase(provider: ProcessCameraProvider) {
        provider.unbindAll()
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        imageAnalysis.setAnalyzer(analyzerExecutor, this)

        val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                CameraSelector.DEFAULT_FRONT_CAMERA
            else -> throw IllegalStateException("No camera available for native monitoring.")
        }

        provider.bindToLifecycle(activity, selector, imageAnalysis)
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
