package com.paul.sprintsync.features.race_session

import SprintSync.Schema.SessionDeviceRole as FlatBufferSessionDeviceRole
import SprintSync.Schema.SessionSplitMark as FlatBufferSessionSplitMark
import SprintSync.Schema.SessionTimelineSnapshot as FlatBufferSessionTimelineSnapshot
import SprintSync.Schema.SessionTrigger as FlatBufferSessionTrigger
import SprintSync.Schema.SessionTriggerRequest as FlatBufferSessionTriggerRequest
import SprintSync.Schema.TelemetryEnvelope
import SprintSync.Schema.TelemetryPayload
import com.google.flatbuffers.FlatBufferBuilder
import java.nio.ByteBuffer

sealed interface DecodedTelemetryEnvelope {
    data class TriggerRequest(val message: SessionTriggerRequestMessage) : DecodedTelemetryEnvelope

    data class Trigger(val message: SessionTriggerMessage) : DecodedTelemetryEnvelope

    data class TimelineSnapshot(val message: SessionTimelineSnapshotMessage) : DecodedTelemetryEnvelope
}

object TelemetryEnvelopeFlatBufferCodec {
    private const val MISSING_OPTIONAL_LONG = -1L
    private const val MISSING_OPTIONAL_INT = -1

    fun encodeTriggerRequest(message: SessionTriggerRequestMessage): ByteArray {
        val builder = FlatBufferBuilder(256)
        val sourceDeviceIdOffset = builder.createString(message.sourceDeviceId)
        val payloadOffset = FlatBufferSessionTriggerRequest.createSessionTriggerRequest(
            builder,
            roleToByte(message.role),
            message.triggerSensorNanos,
            message.mappedHostSensorNanos ?: MISSING_OPTIONAL_LONG,
            sourceDeviceIdOffset,
            message.sourceElapsedNanos,
            message.mappedAnchorElapsedNanos ?: MISSING_OPTIONAL_LONG,
        )
        return finishEnvelope(builder, TelemetryPayload.SessionTriggerRequest, payloadOffset)
    }

    fun encodeTrigger(message: SessionTriggerMessage): ByteArray {
        val builder = FlatBufferBuilder(128)
        val triggerTypeOffset = builder.createString(message.triggerType)
        val payloadOffset = FlatBufferSessionTrigger.createSessionTrigger(
            builder,
            triggerTypeOffset,
            message.splitIndex ?: MISSING_OPTIONAL_INT,
            message.triggerSensorNanos,
        )
        return finishEnvelope(builder, TelemetryPayload.SessionTrigger, payloadOffset)
    }

    fun encodeTimelineSnapshot(message: SessionTimelineSnapshotMessage): ByteArray {
        val builder = FlatBufferBuilder(256)
        val splitMarkOffsets = IntArray(message.hostSplitMarks.size) { index ->
            val splitMark = message.hostSplitMarks[index]
            FlatBufferSessionSplitMark.createSessionSplitMark(
                builder,
                roleToByte(splitMark.role),
                splitMark.hostSensorNanos,
            )
        }
        val splitMarksVectorOffset = if (splitMarkOffsets.isNotEmpty()) {
            FlatBufferSessionTimelineSnapshot.createHostSplitMarksVector(builder, splitMarkOffsets)
        } else {
            0
        }
        val payloadOffset = FlatBufferSessionTimelineSnapshot.createSessionTimelineSnapshot(
            builder,
            message.hostStartSensorNanos ?: MISSING_OPTIONAL_LONG,
            message.hostStopSensorNanos ?: MISSING_OPTIONAL_LONG,
            splitMarksVectorOffset,
            message.sentElapsedNanos,
        )
        return finishEnvelope(builder, TelemetryPayload.SessionTimelineSnapshot, payloadOffset)
    }

    fun decode(payloadBytes: ByteArray): DecodedTelemetryEnvelope? {
        return runCatching {
            val byteBuffer = ByteBuffer.wrap(payloadBytes)
            val envelope = TelemetryEnvelope.getRootAsTelemetryEnvelope(byteBuffer)
            when (envelope.payloadType) {
                TelemetryPayload.SessionTriggerRequest -> {
                    val payload = envelope.payload(FlatBufferSessionTriggerRequest()) as? FlatBufferSessionTriggerRequest
                        ?: return null
                    DecodedTelemetryEnvelope.TriggerRequest(
                        SessionTriggerRequestMessage(
                            role = byteToRole(payload.role),
                            triggerSensorNanos = payload.triggerSensorNanos,
                            mappedHostSensorNanos = payload.mappedHostSensorNanos.takeUnless {
                                it == MISSING_OPTIONAL_LONG
                            },
                            sourceDeviceId = payload.sourceDeviceId,
                            sourceElapsedNanos = payload.sourceElapsedNanos,
                            mappedAnchorElapsedNanos = payload.mappedAnchorElapsedNanos.takeUnless {
                                it == MISSING_OPTIONAL_LONG
                            },
                        ),
                    )
                }

                TelemetryPayload.SessionTrigger -> {
                    val payload = envelope.payload(FlatBufferSessionTrigger()) as? FlatBufferSessionTrigger
                        ?: return null
                    DecodedTelemetryEnvelope.Trigger(
                        SessionTriggerMessage(
                            triggerType = payload.triggerType,
                            splitIndex = payload.splitIndex.takeUnless { it == MISSING_OPTIONAL_INT },
                            triggerSensorNanos = payload.triggerSensorNanos,
                        ),
                    )
                }

                TelemetryPayload.SessionTimelineSnapshot -> {
                    val payload = envelope.payload(FlatBufferSessionTimelineSnapshot()) as? FlatBufferSessionTimelineSnapshot
                        ?: return null
                    val hostSplitMarks = buildList {
                        for (index in 0 until payload.hostSplitMarksLength) {
                            val splitMark = payload.hostSplitMarks(index) ?: continue
                            val role = byteToRole(splitMark.role)
                            if (!role.isSplitCheckpointRole()) {
                                continue
                            }
                            add(
                                SessionSplitMark(
                                    role = role,
                                    hostSensorNanos = splitMark.hostSensorNanos,
                                ),
                            )
                        }
                    }
                    DecodedTelemetryEnvelope.TimelineSnapshot(
                        SessionTimelineSnapshotMessage(
                            hostStartSensorNanos = payload.hostStartSensorNanos.takeUnless {
                                it == MISSING_OPTIONAL_LONG
                            },
                            hostStopSensorNanos = payload.hostStopSensorNanos.takeUnless {
                                it == MISSING_OPTIONAL_LONG
                            },
                            hostSplitMarks = hostSplitMarks,
                            sentElapsedNanos = payload.sentElapsedNanos,
                        ),
                    )
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun finishEnvelope(builder: FlatBufferBuilder, payloadType: UByte, payloadOffset: Int): ByteArray {
        val envelopeOffset = TelemetryEnvelope.createTelemetryEnvelope(builder, payloadType, payloadOffset)
        TelemetryEnvelope.finishTelemetryEnvelopeBuffer(builder, envelopeOffset)
        return builder.sizedByteArray()
    }

    private fun roleToByte(role: SessionDeviceRole): Byte {
        return when (role) {
            SessionDeviceRole.UNASSIGNED -> FlatBufferSessionDeviceRole.UNASSIGNED
            SessionDeviceRole.START -> FlatBufferSessionDeviceRole.START
            SessionDeviceRole.SPLIT1 -> FlatBufferSessionDeviceRole.SPLIT1
            SessionDeviceRole.SPLIT2 -> FlatBufferSessionDeviceRole.SPLIT2
            SessionDeviceRole.SPLIT3 -> FlatBufferSessionDeviceRole.SPLIT3
            SessionDeviceRole.SPLIT4 -> FlatBufferSessionDeviceRole.SPLIT4
            SessionDeviceRole.STOP -> FlatBufferSessionDeviceRole.STOP
            SessionDeviceRole.DISPLAY -> FlatBufferSessionDeviceRole.DISPLAY
        }
    }

    private fun byteToRole(role: Byte): SessionDeviceRole {
        return when (role) {
            FlatBufferSessionDeviceRole.START -> SessionDeviceRole.START
            FlatBufferSessionDeviceRole.SPLIT1 -> SessionDeviceRole.SPLIT1
            FlatBufferSessionDeviceRole.SPLIT2 -> SessionDeviceRole.SPLIT2
            FlatBufferSessionDeviceRole.SPLIT3 -> SessionDeviceRole.SPLIT3
            FlatBufferSessionDeviceRole.SPLIT4 -> SessionDeviceRole.SPLIT4
            FlatBufferSessionDeviceRole.STOP -> SessionDeviceRole.STOP
            FlatBufferSessionDeviceRole.DISPLAY -> SessionDeviceRole.DISPLAY
            else -> SessionDeviceRole.UNASSIGNED
        }
    }
}
