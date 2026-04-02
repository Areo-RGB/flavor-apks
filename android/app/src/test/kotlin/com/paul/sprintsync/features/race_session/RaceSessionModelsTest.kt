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
            hostSplitMarks = listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_500L),
            ),
            runId = "run-1",
            hostSensorMinusElapsedNanos = 120L,
            hostGpsUtcOffsetNanos = 8_000L,
            hostGpsFixAgeNanos = 600_000_000L,
            selfDeviceId = "peer-1",
            anchorDeviceId = "local-device",
            anchorState = SessionAnchorState.ACTIVE,
        )

        val parsed = SessionSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(8_000L, parsed?.hostGpsUtcOffsetNanos)
        assertEquals(600_000_000L, parsed?.hostGpsFixAgeNanos)
        assertEquals(
            listOf(SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_500L)),
            parsed?.hostSplitMarks,
        )
    }

    @Test
    fun `timeline snapshot round-trips with optional fields`() {
        val original = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_500L,
            hostSplitMarks = listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_400L),
                SessionSplitMark(role = SessionDeviceRole.SPLIT2, hostSensorNanos = 2_000L),
            ),
            sentElapsedNanos = 90_000L,
        )

        val parsed = SessionTimelineSnapshotMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals(1_000L, parsed?.hostStartSensorNanos)
        assertEquals(2_500L, parsed?.hostStopSensorNanos)
        assertEquals(
            listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_400L),
                SessionSplitMark(role = SessionDeviceRole.SPLIT2, hostSensorNanos = 2_000L),
            ),
            parsed?.hostSplitMarks,
        )
        assertEquals(90_000L, parsed?.sentElapsedNanos)
    }

    @Test
    fun `timeline snapshot parser maps legacy split array onto explicit checkpoints`() {
        val legacyPayload = """
            {"type":"timeline_snapshot","hostStartSensorNanos":1000,"hostStopSensorNanos":2500,"hostSplitSensorNanos":[1400,2000],"sentElapsedNanos":90000}
        """.trimIndent()

        val parsed = SessionTimelineSnapshotMessage.tryParse(legacyPayload)

        assertNotNull(parsed)
        assertEquals(
            listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_400L),
                SessionSplitMark(role = SessionDeviceRole.SPLIT2, hostSensorNanos = 2_000L),
            ),
            parsed?.hostSplitMarks,
        )
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
    fun `clock sync binary request and response round-trip`() {
        val request = SessionClockSyncBinaryRequest(clientSendElapsedNanos = 100L)
        val response = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = 100L,
            hostReceiveElapsedNanos = 220L,
            hostSendElapsedNanos = 260L,
        )

        val parsedRequest = SessionClockSyncBinaryCodec.decodeRequest(
            SessionClockSyncBinaryCodec.encodeRequest(request),
        )
        val parsedResponse = SessionClockSyncBinaryCodec.decodeResponse(
            SessionClockSyncBinaryCodec.encodeResponse(response),
        )

        assertNotNull(parsedRequest)
        assertEquals(100L, parsedRequest?.clientSendElapsedNanos)
        assertNotNull(parsedResponse)
        assertEquals(220L, parsedResponse?.hostReceiveElapsedNanos)
        assertEquals(260L, parsedResponse?.hostSendElapsedNanos)
    }

    @Test
    fun `clock sync binary codec rejects wrong version type and length`() {
        val validRequest = SessionClockSyncBinaryCodec.encodeRequest(
            SessionClockSyncBinaryRequest(clientSendElapsedNanos = 1L),
        )
        val wrongVersionRequest = validRequest.copyOf().apply { this[0] = 9 }
        val wrongTypeRequest = validRequest.copyOf().apply { this[1] = SessionClockSyncBinaryCodec.TYPE_RESPONSE }
        val wrongLengthRequest = validRequest.copyOf(9)

        val validResponse = SessionClockSyncBinaryCodec.encodeResponse(
            SessionClockSyncBinaryResponse(
                clientSendElapsedNanos = 1L,
                hostReceiveElapsedNanos = 2L,
                hostSendElapsedNanos = 3L,
            ),
        )
        val wrongVersionResponse = validResponse.copyOf().apply { this[0] = 9 }
        val wrongTypeResponse = validResponse.copyOf().apply { this[1] = SessionClockSyncBinaryCodec.TYPE_REQUEST }
        val wrongLengthResponse = validResponse.copyOf(25)

        assertNull(SessionClockSyncBinaryCodec.decodeRequest(wrongVersionRequest))
        assertNull(SessionClockSyncBinaryCodec.decodeRequest(wrongTypeRequest))
        assertNull(SessionClockSyncBinaryCodec.decodeRequest(wrongLengthRequest))
        assertNull(SessionClockSyncBinaryCodec.decodeResponse(wrongVersionResponse))
        assertNull(SessionClockSyncBinaryCodec.decodeResponse(wrongTypeResponse))
        assertNull(SessionClockSyncBinaryCodec.decodeResponse(wrongLengthResponse))
    }

    @Test
    fun `trigger refinement parser rejects missing run id`() {
        val invalid = """
            {"type":"trigger_refinement","runId":"","role":"start","provisionalHostSensorNanos":1,"refinedHostSensorNanos":2}
        """.trimIndent()

        val parsed = SessionTriggerRefinementMessage.tryParse(invalid)

        assertNull(parsed)
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

    @Test
    fun `device identity parser accepts legacy payload without udp endpoint`() {
        val legacyPayload = """
            {"type":"device_identity","stableDeviceId":"stable-device-2","deviceName":"Legacy Phone"}
        """.trimIndent()

        val parsed = SessionDeviceIdentityMessage.tryParse(legacyPayload)

        assertNotNull(parsed)
        assertEquals("stable-device-2", parsed?.stableDeviceId)
        assertEquals("Legacy Phone", parsed?.deviceName)
    }

    @Test
    fun `device telemetry message round-trips`() {
        val original = SessionDeviceTelemetryMessage(
            stableDeviceId = "stable-device-3",
            role = SessionDeviceRole.SPLIT1,
            sensitivity = 72,
            latencyMs = 18,
            clockSynced = true,
            timestampMillis = 123456789L,
        )

        val parsed = SessionDeviceTelemetryMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals("stable-device-3", parsed?.stableDeviceId)
        assertEquals(SessionDeviceRole.SPLIT1, parsed?.role)
        assertEquals(72, parsed?.sensitivity)
        assertEquals(18, parsed?.latencyMs)
        assertEquals(true, parsed?.clockSynced)
        assertEquals(123456789L, parsed?.timestampMillis)
    }

    @Test
    fun `device config update message round-trips`() {
        val original = SessionDeviceConfigUpdateMessage(
            targetStableDeviceId = "stable-device-4",
            sensitivity = 41,
        )

        val parsed = SessionDeviceConfigUpdateMessage.tryParse(original.toJsonString())

        assertNotNull(parsed)
        assertEquals("stable-device-4", parsed?.targetStableDeviceId)
        assertEquals(41, parsed?.sensitivity)
    }

    @Test
    fun `device telemetry parser rejects invalid fields`() {
        val missingId = """
            {"type":"device_telemetry","stableDeviceId":"","role":"start","sensitivity":50,"latencyMs":10,"timestampMillis":1}
        """.trimIndent()
        val invalidRole = """
            {"type":"device_telemetry","stableDeviceId":"abc","role":"bad","sensitivity":50,"latencyMs":10,"timestampMillis":1}
        """.trimIndent()
        val invalidSensitivity = """
            {"type":"device_telemetry","stableDeviceId":"abc","role":"stop","sensitivity":101,"latencyMs":10,"timestampMillis":1}
        """.trimIndent()
        val invalidLatency = """
            {"type":"device_telemetry","stableDeviceId":"abc","role":"stop","sensitivity":50,"latencyMs":-1,"timestampMillis":1}
        """.trimIndent()

        assertNull(SessionDeviceTelemetryMessage.tryParse(missingId))
        assertNull(SessionDeviceTelemetryMessage.tryParse(invalidRole))
        assertNull(SessionDeviceTelemetryMessage.tryParse(invalidSensitivity))
        assertNull(SessionDeviceTelemetryMessage.tryParse(invalidLatency))
    }

    @Test
    fun `device config update parser rejects invalid fields`() {
        val missingTarget = """{"type":"device_config_update","targetStableDeviceId":"","sensitivity":55}"""
        val invalidSensitivity = """{"type":"device_config_update","targetStableDeviceId":"abc","sensitivity":0}"""

        assertNull(SessionDeviceConfigUpdateMessage.tryParse(missingTarget))
        assertNull(SessionDeviceConfigUpdateMessage.tryParse(invalidSensitivity))
    }

    @Test
    fun `device role parsing and labels include explicit split checkpoints and display`() {
        assertEquals(SessionDeviceRole.SPLIT1, sessionDeviceRoleFromName("split1"))
        assertEquals("Split 1", sessionDeviceRoleLabel(SessionDeviceRole.SPLIT1))
        assertEquals(SessionDeviceRole.SPLIT2, sessionDeviceRoleFromName("split2"))
        assertEquals("Split 2", sessionDeviceRoleLabel(SessionDeviceRole.SPLIT2))
        assertEquals(SessionDeviceRole.SPLIT3, sessionDeviceRoleFromName("split3"))
        assertEquals("Split 3", sessionDeviceRoleLabel(SessionDeviceRole.SPLIT3))
        assertEquals(SessionDeviceRole.SPLIT4, sessionDeviceRoleFromName("split4"))
        assertEquals("Split 4", sessionDeviceRoleLabel(SessionDeviceRole.SPLIT4))
        assertEquals(SessionDeviceRole.DISPLAY, sessionDeviceRoleFromName("display"))
        assertEquals("Display", sessionDeviceRoleLabel(SessionDeviceRole.DISPLAY))
    }
}
