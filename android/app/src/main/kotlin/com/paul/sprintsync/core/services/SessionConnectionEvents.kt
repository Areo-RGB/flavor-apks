package com.paul.sprintsync.core.services

import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryResponse

sealed interface SessionConnectionEvent {
    data class EndpointFound(
        val endpointId: String,
        val endpointName: String,
        val serviceId: String,
    ) : SessionConnectionEvent

    data class EndpointLost(
        val endpointId: String,
    ) : SessionConnectionEvent

    data class ConnectionResult(
        val endpointId: String,
        val endpointName: String?,
        val connected: Boolean,
        val statusCode: Int,
        val statusMessage: String?,
    ) : SessionConnectionEvent

    data class EndpointDisconnected(
        val endpointId: String,
    ) : SessionConnectionEvent

    data class PayloadReceived(
        val endpointId: String,
        val message: String,
    ) : SessionConnectionEvent

    data class ClockSyncSampleReceived(
        val endpointId: String,
        val sample: SessionClockSyncBinaryResponse,
    ) : SessionConnectionEvent

    data class Error(
        val message: String,
    ) : SessionConnectionEvent
}
