package com.paul.sprintsync.sensor_native

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorNativeControllerPreviewTimingTest {
    @Test
    fun `schedules retry only when monitoring normal mode and both preview and provider are ready`() {
        assertTrue(
            shouldSchedulePreviewRebindRetry(
                monitoring = true,
                highSpeedEnabled = false,
                hasPreviewView = true,
                hasCameraProvider = true,
            ),
        )
        assertFalse(
            shouldSchedulePreviewRebindRetry(
                monitoring = false,
                highSpeedEnabled = false,
                hasPreviewView = true,
                hasCameraProvider = true,
            ),
        )
        assertFalse(
            shouldSchedulePreviewRebindRetry(
                monitoring = true,
                highSpeedEnabled = true,
                hasPreviewView = true,
                hasCameraProvider = true,
            ),
        )
        assertFalse(
            shouldSchedulePreviewRebindRetry(
                monitoring = true,
                highSpeedEnabled = false,
                hasPreviewView = false,
                hasCameraProvider = true,
            ),
        )
        assertFalse(
            shouldSchedulePreviewRebindRetry(
                monitoring = true,
                highSpeedEnabled = false,
                hasPreviewView = true,
                hasCameraProvider = false,
            ),
        )
    }

    @Test
    fun `becomes retry eligible once preview attaches after provider is available`() {
        val beforeAttach = shouldSchedulePreviewRebindRetry(
            monitoring = true,
            highSpeedEnabled = false,
            hasPreviewView = false,
            hasCameraProvider = true,
        )
        val afterAttach = shouldSchedulePreviewRebindRetry(
            monitoring = true,
            highSpeedEnabled = false,
            hasPreviewView = true,
            hasCameraProvider = true,
        )

        assertFalse(beforeAttach)
        assertTrue(afterAttach)
    }
}
