package com.paul.sprintsync.core.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.paul.sprintsync.features.race_session.SessionClockSyncRequestMessage
import com.paul.sprintsync.features.race_session.SessionClockSyncResponseMessage
import java.nio.charset.StandardCharsets

enum class NearbyRole {
    NONE,
    HOST,
    CLIENT,
}

enum class NearbyTransportStrategy(
    val wireValue: String,
    val nearbyStrategy: Strategy,
) {
    STAR("star", Strategy.P2P_STAR),
    POINT_TO_POINT("point_to_point", Strategy.P2P_POINT_TO_POINT),
    ;

    companion object {
        fun fromWireValue(rawValue: String?): NearbyTransportStrategy {
            return when (rawValue?.trim()) {
                POINT_TO_POINT.wireValue -> POINT_TO_POINT
                else -> STAR
            }
        }
    }
}

class NearbyConnectionsManager(
    context: Context,
    private val nowNativeClockSyncElapsedNanos: (requireSensorDomainClock: Boolean) -> Long?,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val connectedEndpointIds = mutableSetOf<String>()
    private val endpointNamesById = mutableMapOf<String, String>()

    @Volatile
    private var eventListener: ((NearbyEvent) -> Unit)? = null

    private var activeRole: NearbyRole = NearbyRole.NONE
    private var activeStrategy: NearbyTransportStrategy = NearbyTransportStrategy.STAR
    private var pendingEndpointId: String? = null
    private var requestedEndpointId: String? = null
    private var nativeClockSyncHostEnabled = false
    private var nativeClockSyncRequireSensorDomain = false

    fun setEventListener(listener: ((NearbyEvent) -> Unit)?) {
        eventListener = listener
    }

    fun currentRole(): NearbyRole = activeRole

    fun currentStrategy(): NearbyTransportStrategy = activeStrategy

    fun connectedEndpoints(): Set<String> = connectedEndpointIds.toSet()

    fun configureNativeClockSyncHost(enabled: Boolean, requireSensorDomainClock: Boolean) {
        nativeClockSyncHostEnabled = enabled
        nativeClockSyncRequireSensorDomain = requireSensorDomainClock
    }

    fun startHosting(
        serviceId: String,
        endpointName: String,
        strategy: NearbyTransportStrategy,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        normalizeForRole(NearbyRole.HOST, strategy)
        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy.nearbyStrategy)
            .build()
        connectionsClient
            .startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error ->
                clearTransientState()
                emitError("startHosting failed: ${error.localizedMessage ?: "unknown"}")
                onComplete(Result.failure(error))
            }
    }

    fun stopHosting() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
        activeStrategy = NearbyTransportStrategy.STAR
    }

    fun startDiscovery(
        serviceId: String,
        strategy: NearbyTransportStrategy,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        normalizeForRole(NearbyRole.CLIENT, strategy)
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy.nearbyStrategy)
            .build()
        connectionsClient
            .startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error ->
                clearTransientState()
                emitError("startDiscovery failed: ${error.localizedMessage ?: "unknown"}")
                onComplete(Result.failure(error))
            }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
        activeStrategy = NearbyTransportStrategy.STAR
    }

    fun requestConnection(
        endpointId: String,
        endpointName: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (activeRole != NearbyRole.CLIENT) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: not in client mode.")))
            return
        }
        if (connectedEndpointIds.isNotEmpty() && !connectedEndpointIds.contains(endpointId)) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: already connected to another endpoint.")))
            return
        }
        if (pendingEndpointId != null && pendingEndpointId != endpointId) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: another connection is pending.")))
            return
        }
        if (requestedEndpointId != null && requestedEndpointId != endpointId) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: request already in flight.")))
            return
        }

        requestedEndpointId = endpointId
        connectionsClient
            .requestConnection(endpointName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error ->
                if (requestedEndpointId == endpointId) {
                    requestedEndpointId = null
                }
                emitError("requestConnection failed: ${error.localizedMessage ?: "unknown"}")
                onComplete(Result.failure(error))
            }
    }

    fun sendMessage(
        endpointId: String,
        messageJson: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        if (!connectedEndpointIds.contains(endpointId)) {
            onComplete(Result.failure(IllegalStateException("sendMessage ignored: endpoint not connected ($endpointId).")))
            return
        }
        val payload = Payload.fromBytes(messageJson.toByteArray(StandardCharsets.UTF_8))
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error ->
                emitError("sendMessage failed: ${error.localizedMessage ?: "unknown"}")
                onComplete(Result.failure(error))
            }
    }

    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        clearEndpointState(endpointId)
        emitEvent(NearbyEvent.EndpointDisconnected(endpointId = endpointId))
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
        activeStrategy = NearbyTransportStrategy.STAR
    }

    private fun normalizeForRole(role: NearbyRole, strategy: NearbyTransportStrategy) {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = role
        activeStrategy = strategy
    }

    private fun isPointToPointHostBusy(endpointId: String): Boolean {
        if (activeRole != NearbyRole.HOST) {
            return false
        }
        if (activeStrategy != NearbyTransportStrategy.POINT_TO_POINT) {
            return false
        }
        if (pendingEndpointId != null && pendingEndpointId != endpointId) {
            return true
        }
        return connectedEndpointIds.any { connectedId -> connectedId != endpointId }
    }

    private fun clearTransientState() {
        pendingEndpointId = null
        requestedEndpointId = null
        connectedEndpointIds.clear()
        endpointNamesById.clear()
        nativeClockSyncHostEnabled = false
        nativeClockSyncRequireSensorDomain = false
    }

    private fun clearEndpointState(endpointId: String) {
        if (pendingEndpointId == endpointId) {
            pendingEndpointId = null
        }
        if (requestedEndpointId == endpointId) {
            requestedEndpointId = null
        }
        connectedEndpointIds.remove(endpointId)
        endpointNamesById.remove(endpointId)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (activeRole != NearbyRole.CLIENT) {
                return
            }
            emitEvent(
                NearbyEvent.EndpointFound(
                    endpointId = endpointId,
                    endpointName = info.endpointName,
                    serviceId = info.serviceId,
                ),
            )
        }

        override fun onEndpointLost(endpointId: String) {
            clearEndpointState(endpointId)
            emitEvent(NearbyEvent.EndpointLost(endpointId = endpointId))
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val endpointName = info.endpointName.trim().ifEmpty { endpointId }
            endpointNamesById[endpointId] = endpointName
            val hasPendingDifferent = pendingEndpointId != null && pendingEndpointId != endpointId
            val clientBusy = activeRole == NearbyRole.CLIENT &&
                connectedEndpointIds.isNotEmpty() &&
                !connectedEndpointIds.contains(endpointId)
            val pointToPointHostBusy = isPointToPointHostBusy(endpointId)

            if (hasPendingDifferent || clientBusy || pointToPointHostBusy) {
                val statusMessage = if (pointToPointHostBusy) {
                    "Connection rejected: point-to-point host already has a connected peer."
                } else {
                    "Connection rejected: competing connection state."
                }
                connectionsClient.rejectConnection(endpointId)
                emitEvent(
                    NearbyEvent.ConnectionResult(
                        endpointId = endpointId,
                        endpointName = endpointNamesById[endpointId],
                        connected = false,
                        statusCode = ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                        statusMessage = statusMessage,
                    ),
                )
                emitError(statusMessage)
                return
            }

            pendingEndpointId = endpointId
            if (activeRole == NearbyRole.CLIENT) {
                requestedEndpointId = endpointId
            }

            connectionsClient
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    clearEndpointState(endpointId)
                    emitEvent(
                        NearbyEvent.ConnectionResult(
                            endpointId = endpointId,
                            endpointName = endpointNamesById[endpointId],
                            connected = false,
                            statusCode = ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                            statusMessage = error.localizedMessage ?: "acceptConnection failed",
                        ),
                    )
                    emitError("acceptConnection failed: ${error.localizedMessage ?: "unknown"}")
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val status = resolution.status
            val isConnected = status.statusCode == ConnectionsStatusCodes.STATUS_OK
            val endpointName = endpointNamesById[endpointId]
            if (isConnected) {
                connectedEndpointIds.add(endpointId)
                clearEndpointState(endpointId)
                connectedEndpointIds.add(endpointId)
                if (activeRole == NearbyRole.CLIENT) {
                    connectionsClient.stopDiscovery()
                }
            } else {
                clearEndpointState(endpointId)
            }
            emitEvent(
                NearbyEvent.ConnectionResult(
                    endpointId = endpointId,
                    endpointName = endpointName,
                    connected = isConnected,
                    statusCode = status.statusCode,
                    statusMessage = status.statusMessage,
                ),
            )
        }

        override fun onDisconnected(endpointId: String) {
            clearEndpointState(endpointId)
            emitEvent(NearbyEvent.EndpointDisconnected(endpointId = endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val message = String(bytes, StandardCharsets.UTF_8)
            if (tryRespondToClockSyncRequest(endpointId, message)) {
                return
            }
            emitEvent(
                NearbyEvent.PayloadReceived(
                    endpointId = endpointId,
                    message = message,
                ),
            )
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payload progress is not required for race-session control messages.
        }
    }

    private fun tryRespondToClockSyncRequest(endpointId: String, message: String): Boolean {
        if (activeRole != NearbyRole.HOST || !nativeClockSyncHostEnabled) {
            return false
        }
        if (!connectedEndpointIds.contains(endpointId)) {
            return false
        }
        val request = SessionClockSyncRequestMessage.tryParse(message) ?: return false
        val hostReceiveElapsedNanos = nowNativeClockSyncElapsedNanos(nativeClockSyncRequireSensorDomain) ?: return true
        val hostSendElapsedNanos = nowNativeClockSyncElapsedNanos(nativeClockSyncRequireSensorDomain) ?: return true
        val response = SessionClockSyncResponseMessage(
            clientSendElapsedNanos = request.clientSendElapsedNanos,
            hostReceiveElapsedNanos = hostReceiveElapsedNanos,
            hostSendElapsedNanos = hostSendElapsedNanos,
        ).toJsonString()
        val payload = Payload.fromBytes(response.toByteArray(StandardCharsets.UTF_8))
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                emitError("native clock sync response failed: ${error.localizedMessage ?: "unknown"}")
            }
        return true
    }

    private fun emitError(message: String) {
        emitEvent(NearbyEvent.Error(message = message))
    }

    private fun emitEvent(event: NearbyEvent) {
        val listener = eventListener ?: return
        mainHandler.post { listener(event) }
    }
}
