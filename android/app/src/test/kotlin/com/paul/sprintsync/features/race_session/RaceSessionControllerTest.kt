package com.paul.sprintsync.features.race_session

import com.paul.sprintsync.core.services.NearbyEvent
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RaceSessionControllerTest {
    @Test
    fun `clock sync burst computes lock from accepted samples`() {
        var now = 10_000_000_000L
        val sentMessages = mutableListOf<String>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                sentMessages += messageJson
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                now += 1_000_000L
                now
            },
        )

        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 3)
        assertTrue(controller.uiState.value.clockSyncInProgress)

        val requests = sentMessages.mapNotNull { SessionClockSyncRequestMessage.tryParse(it) }
        assertEquals(3, requests.size)

        requests.forEach { request ->
            val response = SessionClockSyncResponseMessage(
                clientSendElapsedNanos = request.clientSendElapsedNanos,
                hostReceiveElapsedNanos = request.clientSendElapsedNanos + 200_000L,
                hostSendElapsedNanos = request.clientSendElapsedNanos + 250_000L,
            )
            controller.onNearbyEvent(
                NearbyEvent.PayloadReceived(
                    endpointId = "ep-1",
                    message = response.toJsonString(),
                ),
            )
        }

        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertNotNull(controller.clockState.value.hostMinusClientElapsedNanos)
        assertTrue(controller.hasFreshClockLock())
        assertEquals("clock_sync_complete", controller.uiState.value.lastEvent)
    }

    @Test
    fun `clock sync burst rejects unconnected endpoint`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.startClockSyncBurst(endpointId = "missing", sampleCount = 3)

        assertEquals("Clock sync ignored: endpoint not connected", controller.uiState.value.lastError)
    }

    @Test
    fun `timeline start stop persists completed run`() {
        var savedRunStarted: Long? = null
        var savedRunStopped: Long? = null
        val latch = CountDownLatch(1)

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { run ->
                savedRunStarted = run.startedSensorNanos
                savedRunStopped = run.stoppedSensorNanos
                latch.countDown()
            },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("stop", splitIndex = 0, triggerSensorNanos = 2_000L, broadcast = false)
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertEquals(1_000L, savedRunStarted)
        assertEquals(2_000L, savedRunStopped)
    }

    @Test
    fun `timeline snapshot maps host sensor into local sensor in client mode`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
        )

        val snapshot = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_000L,
            sentElapsedNanos = 10L,
        )

        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = snapshot.toJsonString(),
            ),
        )

        assertEquals(600L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals(1_600L, controller.uiState.value.timeline.hostStopSensorNanos)
    }

    @Test
    fun `auto ticker does not start NTP burst when fresh GPS lock exists`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                if (SessionClockSyncRequestMessage.tryParse(messageJson) != null) {
                    sentClockSyncRequests.incrementAndGet()
                }
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.updateClockState(
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            localGpsUtcOffsetNanos = 1_000L,
            localGpsFixAgeNanos = 1_000_000_000L,
            hostGpsUtcOffsetNanos = 900L,
            hostGpsFixAgeNanos = 1_000_000_000L,
        )

        Thread.sleep(2500)
        assertEquals(0, sentClockSyncRequests.get())
    }

    @Test
    fun `auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val firstRequestSent = CountDownLatch(1)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                if (SessionClockSyncRequestMessage.tryParse(messageJson) != null) {
                    sentClockSyncRequests.incrementAndGet()
                    firstRequestSent.countDown()
                }
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        assertTrue(firstRequestSent.await(3, TimeUnit.SECONDS))
        assertTrue(sentClockSyncRequests.get() >= 1)
    }

    @Test
    fun `in-progress NTP burst is not cancelled when GPS becomes fresh`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                if (SessionClockSyncRequestMessage.tryParse(messageJson) != null) {
                    sentClockSyncRequests.incrementAndGet()
                }
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 3)
        assertTrue(controller.uiState.value.clockSyncInProgress)
        assertEquals(3, sentClockSyncRequests.get())

        controller.updateClockState(
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            localGpsUtcOffsetNanos = 1_000L,
            localGpsFixAgeNanos = 1_000_000_000L,
            hostGpsUtcOffsetNanos = 900L,
            hostGpsFixAgeNanos = 1_000_000_000L,
        )

        Thread.sleep(2500)
        assertTrue(controller.uiState.value.clockSyncInProgress)
        assertEquals(3, sentClockSyncRequests.get())
    }
}
