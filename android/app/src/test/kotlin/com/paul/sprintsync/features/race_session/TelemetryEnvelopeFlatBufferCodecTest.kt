package com.paul.sprintsync.features.race_session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryEnvelopeFlatBufferCodecTest {
    @Test
    fun `trigger request envelope round trips`() {
        val message = SessionTriggerRequestMessage(
            role = SessionDeviceRole.START,
            triggerSensorNanos = 100L,
            mappedHostSensorNanos = 110L,
            sourceDeviceId = "device-a",
            sourceElapsedNanos = 200L,
            mappedAnchorElapsedNanos = 210L,
        )

        val encoded = TelemetryEnvelopeFlatBufferCodec.encodeTriggerRequest(message)
        val decoded = TelemetryEnvelopeFlatBufferCodec.decode(encoded)

        val payload = decoded as? DecodedTelemetryEnvelope.TriggerRequest
        assertNotNull(payload)
        assertEquals(message, payload!!.message)
    }

    @Test
    fun `trigger envelope round trips with optional split index`() {
        val message = SessionTriggerMessage(
            triggerType = "split",
            splitIndex = 2,
            triggerSensorNanos = 999L,
        )

        val encoded = TelemetryEnvelopeFlatBufferCodec.encodeTrigger(message)
        val decoded = TelemetryEnvelopeFlatBufferCodec.decode(encoded)

        val payload = decoded as? DecodedTelemetryEnvelope.Trigger
        assertNotNull(payload)
        assertEquals(message, payload!!.message)
    }

    @Test
    fun `timeline snapshot envelope round trips optional fields`() {
        val message = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = null,
            hostSplitMarks = listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_500L),
                SessionSplitMark(role = SessionDeviceRole.SPLIT2, hostSensorNanos = 2_000L),
            ),
            sentElapsedNanos = 3_000L,
        )

        val encoded = TelemetryEnvelopeFlatBufferCodec.encodeTimelineSnapshot(message)
        val decoded = TelemetryEnvelopeFlatBufferCodec.decode(encoded)

        val payload = decoded as? DecodedTelemetryEnvelope.TimelineSnapshot
        assertNotNull(payload)
        assertEquals(message.hostStartSensorNanos, payload!!.message.hostStartSensorNanos)
        assertNull(payload.message.hostStopSensorNanos)
        assertEquals(message.hostSplitMarks, payload.message.hostSplitMarks)
        assertEquals(message.sentElapsedNanos, payload.message.sentElapsedNanos)
    }
}
