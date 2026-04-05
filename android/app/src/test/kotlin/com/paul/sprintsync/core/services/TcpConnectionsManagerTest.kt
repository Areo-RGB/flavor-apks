package com.paul.sprintsync.core.services

import android.os.Looper
import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryCodec
import com.paul.sprintsync.features.race_session.SessionClockSyncBinaryRequest
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TcpConnectionsManagerTest {
    private fun awaitWithMainLooper(latch: CountDownLatch, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (latch.await(50, TimeUnit.MILLISECONDS)) {
                return true
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        return latch.count == 0L
    }

    @Test
    fun `client can connect and send message to host`() {
        val port = ServerSocket(0).use { it.localPort }
        val host = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val client = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val payloadLatch = CountDownLatch(1)
        val clientConnected = CountDownLatch(1)
        var hostPayload: String? = null
        var clientEndpointId: String? = null

        host.setEventListener { event ->
            if (event is SessionConnectionEvent.PayloadReceived) {
                hostPayload = event.message
                payloadLatch.countDown()
            }
        }
        client.setEventListener { event ->
            if (event is SessionConnectionEvent.ConnectionResult && event.connected) {
                clientEndpointId = event.endpointId
                clientConnected.countDown()
            }
        }

        host.startHosting("svc", "host", SessionConnectionStrategy.POINT_TO_STAR) {
            assertTrue(it.isSuccess)
        }
        client.startDiscovery("svc", SessionConnectionStrategy.POINT_TO_STAR) {
            assertTrue(it.isSuccess)
        }
        client.requestConnection("127.0.0.1", "client") {
            assertTrue(it.isSuccess)
        }
        assertTrue(awaitWithMainLooper(clientConnected, timeoutMs = 5_000))

        client.sendMessage(clientEndpointId!!, """{"kind":"hello"}""") {
            assertTrue(it.isSuccess)
        }
        assertTrue(awaitWithMainLooper(payloadLatch, timeoutMs = 5_000))
        assertEquals("""{"kind":"hello"}""", hostPayload)

        client.stopAll()
        host.stopAll()
    }

    @Test
    fun `client can send telemetry payload to host`() {
        val port = ServerSocket(0).use { it.localPort }
        val host = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val client = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val payloadLatch = CountDownLatch(1)
        val clientConnected = CountDownLatch(1)
        var hostPayload: ByteArray? = null
        var clientEndpointId: String? = null

        host.setEventListener { event ->
            if (event is SessionConnectionEvent.TelemetryPayloadReceived) {
                hostPayload = event.payloadBytes
                payloadLatch.countDown()
            }
        }
        client.setEventListener { event ->
            if (event is SessionConnectionEvent.ConnectionResult && event.connected) {
                clientEndpointId = event.endpointId
                clientConnected.countDown()
            }
        }

        host.startHosting("svc", "host", SessionConnectionStrategy.POINT_TO_STAR) {
            assertTrue(it.isSuccess)
        }
        client.startDiscovery("svc", SessionConnectionStrategy.POINT_TO_STAR) {
            assertTrue(it.isSuccess)
        }
        client.requestConnection("127.0.0.1", "client") {
            assertTrue(it.isSuccess)
        }
        assertTrue(awaitWithMainLooper(clientConnected, timeoutMs = 5_000))

        val telemetryPayload = byteArrayOf(0x0F, 0x01, 0x02)
        client.sendTelemetryPayload(clientEndpointId!!, telemetryPayload) {
            assertTrue(it.isSuccess)
        }

        assertTrue(awaitWithMainLooper(payloadLatch, timeoutMs = 5_000))
        assertContentEquals(telemetryPayload, hostPayload)

        client.stopAll()
        host.stopAll()
    }

    @Test
    fun `host responds to clock sync request`() {
        val port = ServerSocket(0).use { it.localPort }
        var now = 1000L
        val host = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { now++ },
        )
        val client = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val responseLatch = CountDownLatch(1)
        val connectedLatch = CountDownLatch(1)
        var clientEndpointId: String? = null

        client.setEventListener { event ->
            when (event) {
                is SessionConnectionEvent.ConnectionResult -> if (event.connected) {
                    clientEndpointId = event.endpointId
                    connectedLatch.countDown()
                }
                is SessionConnectionEvent.ClockSyncSampleReceived -> responseLatch.countDown()
                else -> Unit
            }
        }

        host.configureNativeClockSyncHost(enabled = true, requireSensorDomainClock = false)
        host.startHosting("svc", "host", SessionConnectionStrategy.POINT_TO_POINT) { assertTrue(it.isSuccess) }
        client.startDiscovery("svc", SessionConnectionStrategy.POINT_TO_POINT) { assertTrue(it.isSuccess) }
        client.requestConnection("127.0.0.1", "client") { assertTrue(it.isSuccess) }
        assertTrue(awaitWithMainLooper(connectedLatch, timeoutMs = 5_000))

        val request = SessionClockSyncBinaryCodec.encodeRequest(
            SessionClockSyncBinaryRequest(clientSendElapsedNanos = 42L),
        )
        client.sendClockSyncPayload(clientEndpointId!!, request) { assertTrue(it.isSuccess) }
        assertTrue(awaitWithMainLooper(responseLatch, timeoutMs = 5_000))

        client.stopAll()
        host.stopAll()
    }

    @Test
    fun `client scans host range and connects to reachable ip`() {
        val port = ServerSocket(0).use { it.localPort }
        val host = TcpConnectionsManager(
            hostIp = "127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val client = TcpConnectionsManager(
            hostIp = "192.168.0.100-192.168.0.102,127.0.0.1",
            hostPort = port,
            nowNativeClockSyncElapsedNanos = { null },
        )
        val connectedLatch = CountDownLatch(1)
        var clientEndpointId: String? = null

        client.setEventListener { event ->
            if (event is SessionConnectionEvent.ConnectionResult && event.connected) {
                clientEndpointId = event.endpointId
                connectedLatch.countDown()
            }
        }

        host.startHosting("svc", "host", SessionConnectionStrategy.POINT_TO_POINT) {
            assertTrue(it.isSuccess)
        }
        client.startDiscovery("svc", SessionConnectionStrategy.POINT_TO_POINT) {
            assertTrue(it.isSuccess)
        }
        client.requestConnection("192.168.0.100-192.168.0.102,127.0.0.1", "client") {
            assertTrue(it.isSuccess)
        }

        assertTrue(awaitWithMainLooper(connectedLatch, timeoutMs = 5_000))
        assertTrue(clientEndpointId?.startsWith("127.0.0.1:") == true)

        client.stopAll()
        host.stopAll()
    }
}
