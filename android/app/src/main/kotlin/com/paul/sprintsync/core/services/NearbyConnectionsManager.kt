package com.paul.sprintsync.core.services

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
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
import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryCodec
import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryResponse
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
    POINT_TO_POINT("point_to_point", Strategy.P2P_POINT_TO_POINT),
    POINT_TO_STAR("point_to_star", Strategy.P2P_STAR),
    ;

    companion object {
        fun fromWireValue(rawValue: String?): NearbyTransportStrategy {
            return values().firstOrNull { it.wireValue == rawValue } ?: POINT_TO_POINT
        }
    }
}

class NearbyConnectionsManager(
    context: Context,
    private val nowNativeClockSyncElapsedNanos: (requireSensorDomainClock: Boolean) -> Long?,
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context),
) : SessionConnectionsManager {
    companion object {
        private const val CONNECTION_REQUEST_STALE_TIMEOUT_MILLIS = 10_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectedEndpointIds = mutableSetOf<String>()
    private val endpointNamesById = mutableMapOf<String, String>()

    @Volatile
    private var eventListener: ((NearbyEvent) -> Unit)? = null

    private var activeRole: NearbyRole = NearbyRole.NONE
    private var activeStrategy: NearbyTransportStrategy = NearbyTransportStrategy.POINT_TO_POINT
    private var pendingEndpointId: String? = null
    private var pendingEndpointSetAtMillis: Long = 0L
    private var requestedEndpointId: String? = null
    private var requestedEndpointSetAtMillis: Long = 0L
    private var nativeClockSyncHostEnabled = false
    private var nativeClockSyncRequireSensorDomain = false
    override fun setEventListener(listener: ((NearbyEvent) -> Unit)?) {
        eventListener = listener
    }
    override fun currentRole(): NearbyRole = activeRole
    override fun currentStrategy(): NearbyTransportStrategy = activeStrategy
    override fun connectedEndpoints(): Set<String> = connectedEndpointIds.toSet()
    override fun configureNativeClockSyncHost(enabled: Boolean, requireSensorDomainClock: Boolean) {
        nativeClockSyncHostEnabled = enabled
        nativeClockSyncRequireSensorDomain = requireSensorDomainClock
    }

    @VisibleForTesting
    internal fun nativeClockSyncHostConfigForTest(): Pair<Boolean, Boolean> =
        nativeClockSyncHostEnabled to nativeClockSyncRequireSensorDomain
    override fun startHosting(
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
        activeStrategy = NearbyTransportStrategy.POINT_TO_POINT
    }
    override fun startDiscovery(serviceId: String, strategy: NearbyTransportStrategy, onComplete: (Result<Unit>) -> Unit) {
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
        activeStrategy = NearbyTransportStrategy.POINT_TO_POINT
    }
    override fun requestConnection(endpointId: String, endpointName: String, onComplete: (Result<Unit>) -> Unit) {
        expireStaleConnectionRequestState()
        if (activeRole != NearbyRole.CLIENT) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: not in client mode.")))
            return
        }
        if (connectedEndpointIds.isNotEmpty() && !connectedEndpointIds.contains(endpointId)) {
            onComplete(
                Result.failure(
                    IllegalStateException("requestConnection ignored: already connected to another endpoint."),
                ),
            )
            return
        }
        if (pendingEndpointId != null && pendingEndpointId != endpointId) {
            onComplete(
                Result.failure(IllegalStateException("requestConnection ignored: another connection is pending.")),
            )
            return
        }
        if (requestedEndpointId != null && requestedEndpointId != endpointId) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: request already in flight.")))
            return
        }

        requestedEndpointId = endpointId
        requestedEndpointSetAtMillis = SystemClock.elapsedRealtime()
        connectionsClient
            .requestConnection(endpointName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error ->
                if (requestedEndpointId == endpointId) {
                    requestedEndpointId = null
                    requestedEndpointSetAtMillis = 0L
                }
                emitError("requestConnection failed: ${error.localizedMessage ?: "unknown"}")
                onComplete(Result.failure(error))
            }
    }
    override fun sendMessage(endpointId: String, messageJson: String, onComplete: (Result<Unit>) -> Unit) {
        if (!connectedEndpointIds.contains(endpointId)) {
            onComplete(
                Result.failure(IllegalStateException("sendMessage ignored: endpoint not connected ($endpointId).")),
            )
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
    override fun sendClockSyncPayload(endpointId: String, payloadBytes: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        if (!connectedEndpointIds.contains(endpointId)) {
            onComplete(
                Result.failure(
                    IllegalStateException("sendClockSyncPayload ignored: endpoint not connected ($endpointId)."),
                ),
            )
            return
        }
        val payload = Payload.fromBytes(payloadBytes)
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { error ->
                emitError("sendClockSyncPayload failed: ${error.localizedMessage ?: "unknown"}")
                onComplete(Result.failure(error))
            }
    }

    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        clearEndpointState(endpointId)
        emitEvent(NearbyEvent.EndpointDisconnected(endpointId = endpointId))
    }
    override fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        clearTransientState()
        activeRole = NearbyRole.NONE
        activeStrategy = NearbyTransportStrategy.POINT_TO_POINT
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
        pendingEndpointSetAtMillis = 0L
        requestedEndpointId = null
        requestedEndpointSetAtMillis = 0L
        connectedEndpointIds.clear()
        endpointNamesById.clear()
    }

    private fun clearEndpointState(endpointId: String) {
        if (pendingEndpointId == endpointId) {
            pendingEndpointId = null
            pendingEndpointSetAtMillis = 0L
        }
        if (requestedEndpointId == endpointId) {
            requestedEndpointId = null
            requestedEndpointSetAtMillis = 0L
        }
        connectedEndpointIds.remove(endpointId)
        endpointNamesById.remove(endpointId)
    }

    private fun expireStaleConnectionRequestState() {
        if (connectedEndpointIds.isNotEmpty()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (
            pendingEndpointId != null &&
            pendingEndpointSetAtMillis > 0 &&
            now - pendingEndpointSetAtMillis > CONNECTION_REQUEST_STALE_TIMEOUT_MILLIS
        ) {
            pendingEndpointId = null
            pendingEndpointSetAtMillis = 0L
        }
        if (
            requestedEndpointId != null &&
            requestedEndpointSetAtMillis > 0 &&
            now - requestedEndpointSetAtMillis > CONNECTION_REQUEST_STALE_TIMEOUT_MILLIS
        ) {
            requestedEndpointId = null
            requestedEndpointSetAtMillis = 0L
        }
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
            pendingEndpointSetAtMillis = SystemClock.elapsedRealtime()
            if (activeRole == NearbyRole.CLIENT) {
                requestedEndpointId = endpointId
                requestedEndpointSetAtMillis = pendingEndpointSetAtMillis
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
            if (tryHandleClockSyncPayload(endpointId, bytes)) {
                return
            }
            val message = String(bytes, StandardCharsets.UTF_8)
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

    private fun tryHandleClockSyncPayload(endpointId: String, payloadBytes: ByteArray): Boolean {
        if (payloadBytes.isEmpty()) {
            return false
        }
        if (payloadBytes[0] != SessionClockSyncBinaryCodec.VERSION) {
            return false
        }
        val payloadType = payloadBytes.getOrNull(1)
        return when (payloadType) {
            SessionClockSyncBinaryCodec.TYPE_REQUEST -> tryRespondToClockSyncRequest(endpointId, payloadBytes)
            SessionClockSyncBinaryCodec.TYPE_RESPONSE -> tryEmitClockSyncResponse(endpointId, payloadBytes)
            else -> {
                emitError("clock sync payload dropped: unsupported type")
                true
            }
        }
    }

    private fun tryRespondToClockSyncRequest(endpointId: String, payloadBytes: ByteArray): Boolean {
        if (activeRole != NearbyRole.HOST || !nativeClockSyncHostEnabled) {
            return true
        }
        if (!connectedEndpointIds.contains(endpointId)) {
            return true
        }
        val request = SessionClockSyncBinaryCodec.decodeRequest(payloadBytes)
        if (request == null) {
            emitError("clock sync payload dropped: malformed request")
            return true
        }
        val hostReceiveElapsedNanos = nowNativeClockSyncElapsedNanos(nativeClockSyncRequireSensorDomain) ?: return true
        val hostSendElapsedNanos = nowNativeClockSyncElapsedNanos(nativeClockSyncRequireSensorDomain) ?: return true
        val response = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request.clientSendElapsedNanos,
            hostReceiveElapsedNanos = hostReceiveElapsedNanos,
            hostSendElapsedNanos = hostSendElapsedNanos,
        )
        val responseBytes = SessionClockSyncBinaryCodec.encodeResponse(response)
        val payload = Payload.fromBytes(responseBytes)
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                emitError("native clock sync response failed: ${error.localizedMessage ?: "unknown"}")
            }
        return true
    }

    private fun tryEmitClockSyncResponse(endpointId: String, payloadBytes: ByteArray): Boolean {
        if (!connectedEndpointIds.contains(endpointId)) {
            return true
        }
        val response = SessionClockSyncBinaryCodec.decodeResponse(payloadBytes)
        if (response == null) {
            emitError("clock sync payload dropped: malformed response")
            return true
        }
        emitEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = endpointId,
                sample = response,
            ),
        )
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

