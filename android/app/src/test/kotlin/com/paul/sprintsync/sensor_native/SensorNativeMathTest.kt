package com.paul.sprintsync.sensor_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensorNativeMathTest {
    @Test
    fun `detection math emits split triggers with cooldown and rearm parity`() {
        val engine = NativeDetectionMath(NativeMonitoringConfig.defaults())

        var latest: NativeFrameStats? = null
        for (i in 0 until 8) {
            latest = engine.process(rawScore = 0.01, frameSensorNanos = i * 100_000_000L)
        }
        assertNotNull(latest)
        assertNull(latest?.triggerEvent)

        val firstTriggerFrame = engine.process(
            rawScore = 0.22,
            frameSensorNanos = 800_000_000L,
        )
        assertNotNull(firstTriggerFrame.triggerEvent)
        assertEquals("split", firstTriggerFrame.triggerEvent?.triggerType)
        assertEquals(1, firstTriggerFrame.triggerEvent?.splitIndex)

        for (i in 0 until 3) {
            engine.process(rawScore = 0.0, frameSensorNanos = 900_000_000L + (i * 100_000_000L))
        }
        val blockedByCooldown = engine.process(
            rawScore = 0.24,
            frameSensorNanos = 1_200_000_000L,
        )
        assertNull(blockedByCooldown.triggerEvent)

        val secondTriggerFrame = engine.process(
            rawScore = 0.25,
            frameSensorNanos = 1_700_000_000L,
        )
        assertNotNull(secondTriggerFrame.triggerEvent)
        assertEquals(2, secondTriggerFrame.triggerEvent?.splitIndex)
    }

    @Test
    fun `sensor elapsed helpers and offset smoothing are stable`() {
        val smoother = SensorOffsetSmoother()
        assertEquals(1200L, smoother.update(1200L))
        assertEquals(1300L, smoother.update(1600L))

        val sensorMinusElapsedNanos = 5_000_000L
        val sensorNanos = 12_000_000L
        val elapsed = SensorTimeMath.sensorToElapsedNanos(sensorNanos, sensorMinusElapsedNanos)
        assertEquals(7_000_000L, elapsed)

        val mappedBack = SensorTimeMath.elapsedToSensorNanos(elapsed, sensorMinusElapsedNanos)
        assertEquals(sensorNanos, mappedBack)
    }
}
