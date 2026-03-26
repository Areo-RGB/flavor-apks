package com.paul.sprintsync.features.race_session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RaceSessionModelsTest {
    @Test
    fun `timeline snapshot round-trips with optional fields`() {
        val original = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostSplitSensorNanos = listOf(1_500L, 2_000L),
            hostStopSensorNanos = 2_500L,
            sentElapsedNanos = 90_000L,
        )

        val parsed = SessionTimelineSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(1_000L, parsed?.hostStartSensorNanos)
        assertEquals(listOf(1_500L, 2_000L), parsed?.hostSplitSensorNanos)
        assertEquals(2_500L, parsed?.hostStopSensorNanos)
        assertEquals(90_000L, parsed?.sentElapsedNanos)
    }

    @Test
    fun `trigger message parse rejects invalid payload`() {
        val invalid = """
            {"type":"session_trigger","triggerType":"","splitIndex":-1,"triggerSensorNanos":0}
        """.trimIndent()

        val parsed = SessionTriggerMessage.tryParse(invalid)

        assertNull(parsed)
    }

    @Test
    fun `chirp calibration start parser clamps sample count to minimum`() {
        val raw = SessionChirpCalibrationStartMessage(
            calibrationId = "cal-1",
            role = "responder",
            profile = "fallback",
            sampleCount = 1,
            remoteSendElapsedNanos = 123L,
        ).toJsonString()

        val parsed = SessionChirpCalibrationStartMessage.tryParse(raw)

        assertNotNull(parsed)
        assertEquals(3, parsed?.sampleCount)
        assertEquals(123L, parsed?.remoteSendElapsedNanos)
    }

    @Test
    fun `chirp clear parser accepts null calibration id`() {
        val raw = SessionChirpClearMessage(calibrationId = null).toJsonString()

        val parsed = SessionChirpClearMessage.tryParse(raw)

        assertNotNull(parsed)
        assertNull(parsed?.calibrationId)
    }
}
