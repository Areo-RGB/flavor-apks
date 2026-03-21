package com.paul.sprintsync.sensor_native

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class NativeDetectionMath(
    initialConfig: NativeMonitoringConfig,
    private val emaAlpha: Double = 0.08,
) {
    @Volatile
    private var config: NativeMonitoringConfig = initialConfig

    private var baseline = 0.0
    private var aboveCount = 0
    private var armed = true
    private var belowSinceNanos: Long? = null
    private var lastTriggerNanos: Long? = null
    private var pulseCounter = 0

    @Synchronized
    fun updateConfig(next: NativeMonitoringConfig) {
        config = next
    }

    @Synchronized
    fun resetRun() {
        baseline = 0.0
        aboveCount = 0
        armed = true
        belowSinceNanos = null
        lastTriggerNanos = null
        pulseCounter = 0
    }

    @Synchronized
    fun process(rawScore: Double, frameSensorNanos: Long): NativeFrameStats {
        if (baseline == 0.0) {
            baseline = rawScore
        } else {
            baseline = (rawScore * emaAlpha) + (baseline * (1.0 - emaAlpha))
        }

        val effectiveScore = max(0.0, rawScore - baseline)
        val triggerThreshold = config.threshold
        val rearmsBelow = triggerThreshold * 0.6

        if (!armed) {
            if (effectiveScore < rearmsBelow) {
                if (belowSinceNanos == null) {
                    belowSinceNanos = frameSensorNanos
                }
                val elapsedNanos = frameSensorNanos - (belowSinceNanos ?: frameSensorNanos)
                if (elapsedNanos >= 200_000_000L) {
                    armed = true
                    aboveCount = 0
                    belowSinceNanos = null
                }
            } else {
                belowSinceNanos = null
            }
        }

        if (effectiveScore > triggerThreshold) {
            aboveCount += 1
        } else {
            aboveCount = 0
        }

        val cooldownNanos = config.cooldownMs.toLong() * 1_000_000L
        val cooldownPassed = lastTriggerNanos == null ||
            (frameSensorNanos - (lastTriggerNanos ?: 0L)) >= cooldownNanos

        var triggerEvent: NativeTriggerEvent? = null
        if (armed && cooldownPassed && aboveCount >= 1) {
            lastTriggerNanos = frameSensorNanos
            aboveCount = 0
            armed = false
            belowSinceNanos = null
            pulseCounter += 1
            triggerEvent = NativeTriggerEvent(
                triggerSensorNanos = frameSensorNanos,
                score = effectiveScore,
                triggerType = "split",
                splitIndex = pulseCounter,
            )
        }

        return NativeFrameStats(
            rawScore = rawScore,
            baseline = baseline,
            effectiveScore = effectiveScore,
            frameSensorNanos = frameSensorNanos,
            triggerEvent = triggerEvent,
        )
    }
}

class RoiFrameDiffer {
    private var previousRoiLuma: ByteArray? = null

    @Synchronized
    fun reset() {
        previousRoiLuma = null
    }

    @Synchronized
    fun scoreLumaPlane(
        lumaBuffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        roiCenterX: Double,
        roiWidth: Double,
    ): Double {
        val roiCenterPx = (roiCenterX * width).toInt()
        val roiWidthPx = max(1, (roiWidth * width).toInt())
        val startX = max(0, roiCenterPx - (roiWidthPx / 2))
        val endX = min(width, startX + roiWidthPx)
        if (endX <= startX) {
            return 0.0
        }

        val xStep = 2
        val yStep = 2
        val sampleWidth = ((endX - startX) + (xStep - 1)) / xStep
        val sampleHeight = (height + (yStep - 1)) / yStep
        val sampleCount = sampleWidth * sampleHeight
        val current = ByteArray(sampleCount)

        var index = 0
        for (y in 0 until height step yStep) {
            val rowOffset = y * rowStride
            for (x in startX until endX step xStep) {
                current[index] = lumaBuffer.get(rowOffset + (x * pixelStride))
                index += 1
            }
        }

        if (index == 0) {
            previousRoiLuma = null
            return 0.0
        }

        val previous = previousRoiLuma
        val currentSized = if (index == current.size) current else current.copyOf(index)
        previousRoiLuma = currentSized

        if (previous == null || previous.size != currentSized.size) {
            return 0.0
        }

        var diffSum = 0L
        for (i in currentSized.indices) {
            val now = currentSized[i].toInt() and 0xFF
            val before = previous[i].toInt() and 0xFF
            diffSum += abs(now - before)
        }
        return diffSum.toDouble() / (currentSized.size.toDouble() * 255.0)
    }
}

class SensorOffsetSmoother {
    private var current: Long? = null

    @Synchronized
    fun reset() {
        current = null
    }

    @Synchronized
    fun update(sample: Long): Long {
        current = if (current == null) {
            sample
        } else {
            ((current!! * 3L) + sample) / 4L
        }
        return current ?: sample
    }
}

object SensorTimeMath {
    fun sensorToElapsedNanos(sensorNanos: Long, sensorMinusElapsedNanos: Long): Long {
        return sensorNanos - sensorMinusElapsedNanos
    }

    fun elapsedToSensorNanos(elapsedNanos: Long, sensorMinusElapsedNanos: Long): Long {
        return elapsedNanos + sensorMinusElapsedNanos
    }
}
