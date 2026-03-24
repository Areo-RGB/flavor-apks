package com.paul.sprintsync.sensor_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `sensor utc helpers round-trip using gps offset`() {
        val sensorNanos = 12_345_678_901L
        val sensorMinusElapsedNanos = 98_765_432L
        val gpsUtcOffsetNanos = 1_700_000_000_000_000_000L

        val utcNanos = SensorTimeMath.sensorToUtcNanos(
            sensorNanos = sensorNanos,
            sensorMinusElapsedNanos = sensorMinusElapsedNanos,
            gpsUtcOffsetNanos = gpsUtcOffsetNanos,
        )
        val mappedBackSensorNanos = SensorTimeMath.utcToSensorNanos(
            utcNanos = utcNanos,
            sensorMinusElapsedNanos = sensorMinusElapsedNanos,
            gpsUtcOffsetNanos = gpsUtcOffsetNanos,
        )

        assertEquals(sensorNanos, mappedBackSensorNanos)
    }

    @Test
    fun `selectHighestFrameRateBounds prefers highest upper then lower`() {
        val bounds = setOf(
            24 to 30,
            30 to 30,
            30 to 60,
            45 to 60,
            15 to 24,
        )

        val selected = SensorNativeCameraPolicy.selectHighestFrameRateBounds(bounds)

        assertEquals(45, selected?.first)
        assertEquals(60, selected?.second)
    }

    @Test
    fun `selectHighestFrameRateBounds returns null for null or empty`() {
        assertNull(SensorNativeCameraPolicy.selectHighestFrameRateBounds(null))
        assertNull(SensorNativeCameraPolicy.selectHighestFrameRateBounds(emptySet()))
    }

    @Test
    fun `shouldLockAeAwb returns true only at and after warmup`() {
        assertFalse(SensorNativeCameraPolicy.shouldLockAeAwb(0))
        assertFalse(SensorNativeCameraPolicy.shouldLockAeAwb(399))
        assertTrue(SensorNativeCameraPolicy.shouldLockAeAwb(400))
        assertTrue(SensorNativeCameraPolicy.shouldLockAeAwb(401))
    }

    @Test
    fun `selectCameraFacing chooses preferred camera when available`() {
        val rearSelection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.REAR,
            hasRear = true,
            hasFront = true,
        )
        val frontSelection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.FRONT,
            hasRear = true,
            hasFront = true,
        )

        assertEquals(NativeCameraFacing.REAR, rearSelection?.selected)
        assertFalse(rearSelection?.fallbackUsed ?: true)
        assertEquals(NativeCameraFacing.FRONT, frontSelection?.selected)
        assertFalse(frontSelection?.fallbackUsed ?: true)
    }

    @Test
    fun `selectCameraFacing falls back to available camera`() {
        val fallbackToFront = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.REAR,
            hasRear = false,
            hasFront = true,
        )
        val fallbackToRear = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.FRONT,
            hasRear = true,
            hasFront = false,
        )

        assertEquals(NativeCameraFacing.FRONT, fallbackToFront?.selected)
        assertTrue(fallbackToFront?.fallbackUsed ?: false)
        assertEquals(NativeCameraFacing.REAR, fallbackToRear?.selected)
        assertTrue(fallbackToRear?.fallbackUsed ?: false)
    }

    @Test
    fun `selectCameraFacing returns null when no camera is available`() {
        val selection = SensorNativeCameraPolicy.selectCameraFacing(
            preferred = NativeCameraFacing.REAR,
            hasRear = false,
            hasFront = false,
        )

        assertNull(selection)
    }
}
