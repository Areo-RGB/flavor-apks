package com.paul.sprintsync.core.services

import android.os.Handler
import android.os.Looper
import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryCodec
import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TcpConnectionsManager(
    private val hostIp: String,
    private val hostPort: Int,
    private val nowNativeClockSyncElapsedNanos: (requireSensorDomainClock: Boolean) -> Long?,
) : SessionConnectionsManager {
    companion object {
        private const val FRAME_KIND_MESSAGE: Byte = 1
        private const val FRAME_KIND_CLOCK_SYNC_BINARY: Byte = 2
        private const val FRAME_KIND_TELEMETRY_BINARY: Byte = 3
        private const val CONNECT_TIMEOUT_MS = 350
    }

    private val ioExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectedSockets = ConcurrentHashMap<String, Socket>()
    private val endpointNamesById = ConcurrentHashMap<String, String>()
    private val discoveryRunning = AtomicBoolean(false)

    @Volatile
    private var eventListener: ((SessionConnectionEvent) -> Unit)? = null

    @Volatile
    private var activeRole: SessionConnectionRole = SessionConnectionRole.NONE

    @Volatile
    private var activeStrategy: SessionConnectionStrategy = SessionConnectionStrategy.POINT_TO_POINT

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var nativeClockSyncHostEnabled = false

    @Volatile
    private var nativeClockSyncRequireSensorDomain = false

    override fun setEventListener(listener: ((SessionConnectionEvent) -> Unit)?) {
        eventListener = listener
    }

    override fun currentRole(): SessionConnectionRole = activeRole

    override fun currentStrategy(): SessionConnectionStrategy = activeStrategy

    override fun connectedEndpoints(): Set<String> = connectedSockets.keys.toSet()

    override fun configureNativeClockSyncHost(enabled: Boolean, requireSensorDomainClock: Boolean) {
        nativeClockSyncHostEnabled = enabled
        nativeClockSyncRequireSensorDomain = requireSensorDomainClock
    }

    override fun startHosting(
        serviceId: String,
        endpointName: String,
        strategy: SessionConnectionStrategy,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        stopAll()
        activeRole = SessionConnectionRole.HOST
        activeStrategy = strategy
        try {
            val socket = ServerSocket(hostPort)
            serverSocket = socket
            ioExecutor.execute {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        client.tcpNoDelay = true
                        val endpointId = endpointIdForSocket(client)
                        if (activeStrategy == SessionConnectionStrategy.POINT_TO_POINT && connectedSockets.isNotEmpty()) {
                            client.close()
                            emitEvent(
                                SessionConnectionEvent.ConnectionResult(
                                    endpointId = endpointId,
                                    endpointName = endpointId,
                                    connected = false,
                                    statusCode = -2,
                                    statusMessage = "point_to_point busy",
                                ),
                            )
                            continue
                        }
                        connectedSockets[endpointId] = client
                        endpointNamesById[endpointId] = endpointId
                        emitEvent(
                            SessionConnectionEvent.ConnectionResult(
                                endpointId = endpointId,
                                endpointName = endpointId,
                                connected = true,
                                statusCode = 0,
                                statusMessage = "connected",
                            ),
                        )
                        startReaderLoop(endpointId, client)
                    } catch (_: SocketException) {
                        break
                    } catch (error: Throwable) {
                        emitError("accept failed: ${error.localizedMessage ?: "unknown"}")
                    }
                }
            }
            onComplete(Result.success(Unit))
        } catch (error: Throwable) {
            emitError("startHosting failed: ${error.localizedMessage ?: "unknown"}")
            onComplete(Result.failure(error))
        }
    }

    override fun startDiscovery(serviceId: String, strategy: SessionConnectionStrategy, onComplete: (Result<Unit>) -> Unit) {
        connectedSockets.clear()
        endpointNamesById.clear()
        activeRole = SessionConnectionRole.CLIENT
        activeStrategy = strategy
        discoveryRunning.set(true)
        emitEvent(
            SessionConnectionEvent.EndpointFound(
                endpointId = hostIp,
                endpointName = hostIp,
                serviceId = serviceId,
            ),
        )
        onComplete(Result.success(Unit))
    }

    override fun requestConnection(endpointId: String, endpointName: String, onComplete: (Result<Unit>) -> Unit) {
        if (activeRole != SessionConnectionRole.CLIENT) {
            onComplete(Result.failure(IllegalStateException("requestConnection ignored: not in client mode.")))
            return
        }
        val targetHosts = resolveTargetHosts(endpointId)
        ioExecutor.execute {
            var lastError: Throwable? = null
            for (targetHost in targetHosts) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(targetHost, hostPort), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    val connectedId = endpointIdForSocket(socket)
                    val resolvedEndpointName = endpointName.ifBlank { targetHost }
                    connectedSockets[connectedId] = socket
                    endpointNamesById[connectedId] = resolvedEndpointName
                    discoveryRunning.set(false)
                    emitEvent(
                        SessionConnectionEvent.ConnectionResult(
                            endpointId = connectedId,
                            endpointName = resolvedEndpointName,
                            connected = true,
                            statusCode = 0,
                            statusMessage = "connected",
                        ),
                    )
                    startReaderLoop(connectedId, socket)
                    onComplete(Result.success(Unit))
                    return@execute
                } catch (error: Throwable) {
                    runCatching { socket.close() }
                    lastError = error
                }
            }

            val failure = lastError ?: IllegalStateException("connect failed")
            emitEvent(
                SessionConnectionEvent.ConnectionResult(
                    endpointId = endpointId,
                    endpointName = endpointName.ifBlank { endpointId },
                    connected = false,
                    statusCode = -1,
                    statusMessage = failure.localizedMessage ?: "connect failed",
                ),
            )
            onComplete(Result.failure(failure))
        }
    }

    private fun resolveTargetHosts(endpointId: String): List<String> {
        val rawTarget = endpointId.substringBefore(":").ifBlank { hostIp }.trim()
        if (rawTarget.isBlank()) {
            return listOf(hostIp)
        }

        val hosts = linkedSetOf<String>()
        rawTarget
            .split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                hosts.addAll(expandHostToken(token))
            }

        if (hosts.isEmpty()) {
            hosts += rawTarget
        }
        return hosts.toList()
    }

    private fun expandHostToken(token: String): List<String> {
        if (!token.contains('-')) {
            return listOf(token)
        }

        val parts = token.split('-', limit = 2).map { it.trim() }
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return listOf(token)
        }

        val startOctets = parseIpv4Octets(parts[0]) ?: return listOf(token)
        val startLast = startOctets[3]

        val endLast = parts[1].toIntOrNull() ?: run {
            val endOctets = parseIpv4Octets(parts[1]) ?: return listOf(token)
            if (startOctets[0] != endOctets[0] || startOctets[1] != endOctets[1] || startOctets[2] != endOctets[2]) {
                return listOf(token)
            }
            endOctets[3]
        }

        if (startLast !in 0..255 || endLast !in 0..255) {
            return listOf(token)
        }

        val prefix = "${startOctets[0]}.${startOctets[1]}.${startOctets[2]}"
        val range = if (startLast <= endLast) {
            startLast..endLast
        } else {
            endLast..startLast
        }
        return range.map { "$prefix.$it" }
    }

    private fun parseIpv4Octets(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) {
            return null
        }
        val octets = IntArray(4)
        for (index in 0 until 4) {
            val value = parts[index].toIntOrNull() ?: return null
            if (value !in 0..255) {
                return null
            }
            octets[index] = value
        }
        return octets
    }

    override fun sendMessage(endpointId: String, messageJson: String, onComplete: (Result<Unit>) -> Unit) {
        val payload = messageJson.toByteArray(StandardCharsets.UTF_8)
        sendFrame(endpointId, FRAME_KIND_MESSAGE, payload, onComplete)
    }

    override fun sendClockSyncPayload(endpointId: String, payloadBytes: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        sendFrame(endpointId, FRAME_KIND_CLOCK_SYNC_BINARY, payloadBytes, onComplete)
    }

    override fun sendTelemetryPayload(endpointId: String, payloadBytes: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        sendFrame(endpointId, FRAME_KIND_TELEMETRY_BINARY, payloadBytes, onComplete)
    }

    private fun sendFrame(endpointId: String, kind: Byte, payloadBytes: ByteArray, onComplete: (Result<Unit>) -> Unit) {
        val socket = connectedSockets[endpointId]
        if (socket == null) {
            onComplete(Result.failure(IllegalStateException("endpoint not connected ($endpointId)")))
            return
        }
        ioExecutor.execute {
            try {
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                synchronized(socket) {
                    out.writeByte(kind.toInt())
                    out.writeInt(payloadBytes.size)
                    out.write(payloadBytes)
                    out.flush()
                }
                onComplete(Result.success(Unit))
            } catch (error: Throwable) {
                onComplete(Result.failure(error))
                handleDisconnect(endpointId)
            }
        }
    }

    private fun startReaderLoop(endpointId: String, socket: Socket) {
        ioExecutor.execute {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                while (!socket.isClosed) {
                    val frameKind = input.readByte()
                    val frameLength = input.readInt()
                    if (frameLength <= 0 || frameLength > 1_048_576) {
                        throw IllegalStateException("invalid frame size: $frameLength")
                    }
                    val payload = ByteArray(frameLength)
                    input.readFully(payload)
                    when (frameKind) {
                        FRAME_KIND_MESSAGE -> {
                            emitEvent(
                                SessionConnectionEvent.PayloadReceived(
                                    endpointId = endpointId,
                                    message = String(payload, StandardCharsets.UTF_8),
                                ),
                            )
                        }
                        FRAME_KIND_CLOCK_SYNC_BINARY -> {
                            if (!tryHandleClockSyncPayload(endpointId, payload)) {
                                emitError("binary payload dropped: unsupported")
                            }
                        }
                        FRAME_KIND_TELEMETRY_BINARY -> {
                            emitEvent(
                                SessionConnectionEvent.TelemetryPayloadReceived(
                                    endpointId = endpointId,
                                    payloadBytes = payload,
                                ),
                            )
                        }
                        else -> emitError("payload dropped: unknown frame kind=$frameKind")
                    }
                }
            } catch (_: EOFException) {
                handleDisconnect(endpointId)
            } catch (_: SocketException) {
                handleDisconnect(endpointId)
            } catch (error: Throwable) {
                emitError("read failed: ${error.localizedMessage ?: "unknown"}")
                handleDisconnect(endpointId)
            }
        }
    }

    private fun tryHandleClockSyncPayload(endpointId: String, payloadBytes: ByteArray): Boolean {
        if (payloadBytes.isEmpty()) return false
        if (payloadBytes[0] != SessionClockSyncBinaryCodec.VERSION) return false
        val payloadType = payloadBytes.getOrNull(1)
        return when (payloadType) {
            SessionClockSyncBinaryCodec.TYPE_REQUEST -> tryRespondToClockSyncRequest(endpointId, payloadBytes)
            SessionClockSyncBinaryCodec.TYPE_RESPONSE -> tryEmitClockSyncResponse(endpointId, payloadBytes)
            else -> true
        }
    }

    private fun tryRespondToClockSyncRequest(endpointId: String, payloadBytes: ByteArray): Boolean {
        if (activeRole != SessionConnectionRole.HOST || !nativeClockSyncHostEnabled) {
            return true
        }
        if (!connectedSockets.containsKey(endpointId)) {
            return true
        }
        val request = SessionClockSyncBinaryCodec.decodeRequest(payloadBytes) ?: return true
        val hostReceiveElapsedNanos = nowNativeClockSyncElapsedNanos(nativeClockSyncRequireSensorDomain) ?: return true
        val hostSendElapsedNanos = nowNativeClockSyncElapsedNanos(nativeClockSyncRequireSensorDomain) ?: return true
        val response = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request.clientSendElapsedNanos,
            hostReceiveElapsedNanos = hostReceiveElapsedNanos,
            hostSendElapsedNanos = hostSendElapsedNanos,
        )
        val responseBytes = SessionClockSyncBinaryCodec.encodeResponse(response)
        sendClockSyncPayload(endpointId, responseBytes) { result ->
            result.exceptionOrNull()?.let { error ->
                emitError("clock sync response failed: ${error.localizedMessage ?: "unknown"}")
            }
        }
        return true
    }

    private fun tryEmitClockSyncResponse(endpointId: String, payloadBytes: ByteArray): Boolean {
        val response = SessionClockSyncBinaryCodec.decodeResponse(payloadBytes) ?: return true
        emitEvent(
            SessionConnectionEvent.ClockSyncSampleReceived(
                endpointId = endpointId,
                sample = response,
            ),
        )
        return true
    }

    override fun stopAll() {
        discoveryRunning.set(false)
        serverSocket?.close()
        serverSocket = null
        connectedSockets.forEach { (_, socket) ->
            runCatching { socket.close() }
        }
        connectedSockets.clear()
        endpointNamesById.clear()
        activeRole = SessionConnectionRole.NONE
        activeStrategy = SessionConnectionStrategy.POINT_TO_POINT
    }

    private fun handleDisconnect(endpointId: String) {
        val socket = connectedSockets.remove(endpointId)
        runCatching { socket?.close() }
        endpointNamesById.remove(endpointId)
        emitEvent(SessionConnectionEvent.EndpointDisconnected(endpointId = endpointId))
    }

    private fun endpointIdForSocket(socket: Socket): String {
        val host = socket.inetAddress?.hostAddress ?: "unknown"
        return "$host:${socket.port}"
    }

    private fun emitError(message: String) {
        emitEvent(SessionConnectionEvent.Error(message))
    }

    private fun emitEvent(event: SessionConnectionEvent) {
        val listener = eventListener ?: return
        mainHandler.post { listener(event) }
    }
}
