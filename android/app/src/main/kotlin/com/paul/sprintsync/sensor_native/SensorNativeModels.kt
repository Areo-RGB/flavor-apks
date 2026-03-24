package com.paul.sprintsync.sensor_native

import kotlin.math.max
import kotlin.math.min

enum class NativeCameraFacing(val wireName: String) {
    REAR("rear"),
    FRONT("front"),
}

data class NativeMonitoringConfig(
    val threshold: Double,
    val roiCenterX: Double,
    val roiWidth: Double,
    val cooldownMs: Int,
    val processEveryNFrames: Int,
    val cameraFacing: NativeCameraFacing,
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

private fun clampDouble(value: Double, minValue: Double, maxValue: Double): Double {
    return min(max(value, minValue), maxValue)
}

private fun clampInt(value: Int, minValue: Int, maxValue: Int): Int {
    return min(max(value, minValue), maxValue)
}

private fun nativeCameraFacingFromWire(value: String?): NativeCameraFacing? {
    return NativeCameraFacing.values().firstOrNull { it.wireName == value }
}
