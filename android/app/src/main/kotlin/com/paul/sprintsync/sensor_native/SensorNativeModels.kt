package com.paul.sprintsync.sensor_native

import kotlin.math.max
import kotlin.math.min

enum class NativeCameraFacing(val wireName: String) {
    REAR("rear"),
    FRONT("front"),
}

enum class NativeCameraFpsMode(val wireName: String) {
    NORMAL("normal"),
    HS120("hs120"),
}

enum class HsScanDirection(val wireName: String) {
    FORWARD("forward"),
    BACKWARD("backward"),
}

data class NativeMonitoringConfig(
    val threshold: Double,
    val roiCenterX: Double,
    val roiWidth: Double,
    val cooldownMs: Int,
    val processEveryNFrames: Int,
    val cameraFacing: NativeCameraFacing,
    val highSpeedEnabled: Boolean,
) {
    companion object {
        fun defaults(): NativeMonitoringConfig {
            return NativeMonitoringConfig(
                threshold = 0.006,
                roiCenterX = 0.5,
                roiWidth = 0.12,
                cooldownMs = 900,
                processEveryNFrames = 1,
                cameraFacing = NativeCameraFacing.REAR,
                highSpeedEnabled = false,
            )
        }

        fun fromMap(raw: Any?): NativeMonitoringConfig {
            if (raw !is Map<*, *>) {
                return defaults()
            }
            val defaults = defaults()
            return NativeMonitoringConfig(
                threshold = clampDouble(
                    (raw["threshold"] as? Number)?.toDouble() ?: defaults.threshold,
                    0.001,
                    0.08,
                ),
                roiCenterX = clampDouble(
                    (raw["roiCenterX"] as? Number)?.toDouble() ?: defaults.roiCenterX,
                    0.20,
                    0.80,
                ),
                roiWidth = clampDouble(
                    (raw["roiWidth"] as? Number)?.toDouble() ?: defaults.roiWidth,
                    0.05,
                    0.40,
                ),
                cooldownMs = clampInt(
                    (raw["cooldownMs"] as? Number)?.toInt() ?: defaults.cooldownMs,
                    300,
                    2000,
                ),
                processEveryNFrames = clampInt(
                    (raw["processEveryNFrames"] as? Number)?.toInt()
                        ?: defaults.processEveryNFrames,
                    1,
                    5,
                ),
                cameraFacing = nativeCameraFacingFromWire(
                    raw["cameraFacing"]?.toString(),
                ) ?: defaults.cameraFacing,
                highSpeedEnabled = (raw["highSpeedEnabled"] as? Boolean)
                    ?: defaults.highSpeedEnabled,
            )
        }
    }
}

data class NativeTriggerEvent(
    val triggerSensorNanos: Long,
    val score: Double,
    val triggerType: String,
    val splitIndex: Int,
)

data class NativeFrameStats(
    val rawScore: Double,
    val baseline: Double,
    val effectiveScore: Double,
    val frameSensorNanos: Long,
    val triggerEvent: NativeTriggerEvent?,
)

object HsRecordingPolicy {
    const val MAX_SECONDS = 10
    const val TARGET_FPS = 120
    const val DEFAULT_CAPACITY_FRAMES = MAX_SECONDS * TARGET_FPS
    const val LIVE_ANALYSIS_STRIDE = 4
    const val DEFAULT_REFINEMENT_WINDOW_NANOS = 250_000_000L
}

data class HsRecordedRoiFrame(
    val timestampNanos: Long,
    val luma: ByteArray,
    val sampleCount: Int,
)

class HsRoiRecordingBuffer(
    capacityFrames: Int = HsRecordingPolicy.DEFAULT_CAPACITY_FRAMES,
) {
    private val boundedCapacityFrames = max(1, capacityFrames)
    private val frames = ArrayDeque<HsRecordedRoiFrame>(boundedCapacityFrames)

    @Synchronized
    fun append(frame: HsRecordedRoiFrame) {
        val safeSampleCount = min(frame.sampleCount, frame.luma.size)
        if (safeSampleCount <= 0) {
            return
        }
        while (frames.size >= boundedCapacityFrames) {
            frames.removeFirst()
        }
        val safeFrame = HsRecordedRoiFrame(
            timestampNanos = frame.timestampNanos,
            luma = frame.luma.copyOf(safeSampleCount),
            sampleCount = safeSampleCount,
        )
        frames.addLast(safeFrame)
    }

    @Synchronized
    fun clear() {
        frames.clear()
    }

    @Synchronized
    fun snapshot(): List<HsRecordedRoiFrame> {
        return frames.map { frame ->
            HsRecordedRoiFrame(
                timestampNanos = frame.timestampNanos,
                luma = frame.luma.copyOf(frame.sampleCount),
                sampleCount = frame.sampleCount,
            )
        }
    }
}

data class HsTriggerRefinementRequest(
    val triggerSensorNanos: Long,
    val triggerType: String,
    val splitIndex: Int,
    val windowNanos: Long? = null,
)

data class HsTriggerRefinementResult(
    val triggerType: String,
    val splitIndex: Int,
    val provisionalSensorNanos: Long,
    val refinedSensorNanos: Long,
    val refined: Boolean,
    val rawScore: Double? = null,
    val baseline: Double? = null,
    val effectiveScore: Double? = null,
)

data class HsRecordedVideoArtifact(
    val runId: String,
    val outputPath: String,
    val encodedPtsUs: List<Long>,
    val captureSensorNanos: List<Long>,
)

data class HsOfflineAnalysisMetric(
    val sensorNanos: Long,
    val rawScore: Double,
    val baseline: Double,
    val effectiveScore: Double,
)

data class HsOfflineAnalysisResult(
    val runId: String,
    val triggerType: String,
    val splitIndex: Int,
    val scanDirection: HsScanDirection,
    val resolved: Boolean,
    val localSensorNanos: Long?,
    val rawScore: Double? = null,
    val baseline: Double? = null,
    val effectiveScore: Double? = null,
    val diagnostics: String? = null,
)

private fun clampDouble(value: Double, minValue: Double, maxValue: Double): Double {
    return min(max(value, minValue), maxValue)
}

private fun clampInt(value: Int, minValue: Int, maxValue: Int): Int {
    return min(max(value, minValue), maxValue)
}

internal fun nativeCameraFacingFromWire(value: String?): NativeCameraFacing? {
    return NativeCameraFacing.values().firstOrNull { it.wireName == value }
}

internal fun hsScanDirectionFromWire(value: String?): HsScanDirection? {
    return HsScanDirection.values().firstOrNull { it.wireName == value }
}
