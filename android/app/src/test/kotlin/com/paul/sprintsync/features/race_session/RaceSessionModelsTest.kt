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
    fun `snapshot round-trips host GPS fields`() {
        val original = SessionSnapshotMessage(
            stage = SessionStage.MONITORING,
            monitoringActive = true,
            devices = listOf(
                SessionDevice(
                    id = "local-device",
                    name = "This Device",
                    role = SessionDeviceRole.START,
                    isLocal = true,
                ),
            ),
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_000L,
            runId = "run-1",
            hostSensorMinusElapsedNanos = 120L,
            hostGpsUtcOffsetNanos = 8_000L,
            hostGpsFixAgeNanos = 600_000_000L,
            selfDeviceId = "peer-1",
        )

        val parsed = SessionSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(8_000L, parsed?.hostGpsUtcOffsetNanos)
        assertEquals(600_000_000L, parsed?.hostGpsFixAgeNanos)
    }

    @Test
    fun `timeline snapshot round-trips with optional fields`() {
        val original = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_500L,
            sentElapsedNanos = 90_000L,
        )

        val parsed = SessionTimelineSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(1_000L, parsed?.hostStartSensorNanos)
        assertEquals(2_500L, parsed?.hostStopSensorNanos)
        assertEquals(90_000L, parsed?.sentElapsedNanos)
    }

    @Test
    fun `trigger message parse rejects invalid payload`() {
        val invalid = """
            {"type":"session_trigger","triggerType":"","triggerSensorNanos":0}
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

    @Test
    fun `device identity message round-trips`() {
        val original = SessionDeviceIdentityMessage(
            stableDeviceId = "stable-device-1",
            deviceName = "Pixel 8 Pro",
        )

        val parsed = SessionDeviceIdentityMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals("stable-device-1", parsed?.stableDeviceId)
        assertEquals("Pixel 8 Pro", parsed?.deviceName)
    }
}
