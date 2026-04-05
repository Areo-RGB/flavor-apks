package com.paul.sprintsync.core.services

interface SessionConnectionsManager {
    fun setEventListener(listener: ((SessionConnectionEvent) -> Unit)?)

    fun currentRole(): SessionConnectionRole

    fun currentStrategy(): SessionConnectionStrategy

    fun connectedEndpoints(): Set<String>

    fun configureNativeClockSyncHost(enabled: Boolean, requireSensorDomainClock: Boolean)

    fun startHosting(
        serviceId: String,
        endpointName: String,
        strategy: SessionConnectionStrategy,
        onComplete: (Result<Unit>) -> Unit,
    )

    fun startDiscovery(serviceId: String, strategy: SessionConnectionStrategy, onComplete: (Result<Unit>) -> Unit)

    fun requestConnection(endpointId: String, endpointName: String, onComplete: (Result<Unit>) -> Unit)

    fun sendMessage(endpointId: String, messageJson: String, onComplete: (Result<Unit>) -> Unit)

    fun sendClockSyncPayload(endpointId: String, payloadBytes: ByteArray, onComplete: (Result<Unit>) -> Unit)

    fun stopAll()
}
