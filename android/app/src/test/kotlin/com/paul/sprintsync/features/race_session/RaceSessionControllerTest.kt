package com.paul.sprintsync.features.race_session

import com.paul.sprintsync.core.services.NearbyEvent
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RaceSessionControllerTest {
    @Test
    fun `host xiaomi pins oneplus start and pixel stop`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.START, isLocal = true),
            SessionDevice(id = "ep1", name = "OnePlus 12", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Pixel 8", role = SessionDeviceRole.START, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "local" }.role)
        assertEquals(SessionDeviceRole.START, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.STOP, mapped.first { it.id == "ep2" }.role)
    }

    @Test
    fun `host xiaomi pins cph oneplus alias to start and pixel to stop`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.UNASSIGNED, isLocal = true),
            SessionDevice(id = "ep1", name = "CPH2399", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Pixel 7", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.START, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.STOP, mapped.first { it.id == "ep2" }.role)
        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "local" }.role)
    }

    @Test
    fun `host xiaomi pins oneplus start and remaining remote stop when pixel label is missing`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.UNASSIGNED, isLocal = true),
            SessionDevice(id = "ep1", name = "CPH2399", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Device B", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.START, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.STOP, mapped.first { it.id == "ep2" }.role)
        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "local" }.role)
    }

    @Test
    fun `non xiaomi profile keeps current roles`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host", role = SessionDeviceRole.START, isLocal = true),
            SessionDevice(id = "ep1", name = "OnePlus 12", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Pixel 8", role = SessionDeviceRole.STOP, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "client_pixel")

        assertEquals(devices, mapped)
    }

    @Test
    fun `clock sync burst selects minimum RTT sample and breaks ties by earliest accepted`() {
        val scriptedNow = ArrayDeque(
            listOf(
                1_000L,
                2_000L,
                3_000L,
                5_000L,
                6_000L,
                9_000L,
                10_000L,
            ),
        )
        var fallbackNow = 10_000L
        val sentPayloads = mutableListOf<ByteArray>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete ->
                onComplete(Result.success(Unit))
            },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                if (scriptedNow.isEmpty()) {
                    fallbackNow += 1_000L
                    fallbackNow
                } else {
                    scriptedNow.removeFirst()
                }
            },
            clockSyncDelay = { _ -> },
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

        val requests = sentPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        assertEquals(3, requests.size)

        val request1 = requests[0]
        val request2 = requests[1]
        val request3 = requests[2]

        val response2 = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request2.clientSendElapsedNanos,
            hostReceiveElapsedNanos = request2.clientSendElapsedNanos + 100L,
            hostSendElapsedNanos = request2.clientSendElapsedNanos + 310L,
        )
        val response3 = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request3.clientSendElapsedNanos,
            hostReceiveElapsedNanos = request3.clientSendElapsedNanos + 100L,
            hostSendElapsedNanos = request3.clientSendElapsedNanos + 510L,
        )
        val response1 = SessionClockSyncBinaryResponse(
            clientSendElapsedNanos = request1.clientSendElapsedNanos,
            hostReceiveElapsedNanos = request1.clientSendElapsedNanos + 100L,
            hostSendElapsedNanos = request1.clientSendElapsedNanos + 310L,
        )

        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response2,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response3,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response1,
            ),
        )

        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertNotNull(controller.clockState.value.hostMinusClientElapsedNanos)
        assertEquals(-1_295L, controller.clockState.value.hostMinusClientElapsedNanos)
        assertEquals(3_000L, controller.clockState.value.hostClockRoundTripNanos)
        assertTrue(controller.hasFreshClockLock())
        assertEquals("clock_sync_complete", controller.uiState.value.lastEvent)
    }

    @Test
    fun `clock sync burst staggers sends by 50ms and finishes only after all pending samples resolve`() {
        var now = 10_000L
        val sentPayloads = mutableListOf<ByteArray>()
        val delayCalls = mutableListOf<Long>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                now += 1_000L
                now
            },
            clockSyncDelay = { delayCalls += it },
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
        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 4)

        assertEquals(listOf(50L, 50L, 50L), delayCalls)
        val requests = sentPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        assertEquals(4, requests.size)
        assertTrue(controller.uiState.value.clockSyncInProgress)

        requests.take(3).forEach { request ->
            controller.onNearbyEvent(
                NearbyEvent.ClockSyncSampleReceived(
                    endpointId = "ep-1",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
        }
        assertTrue(controller.uiState.value.clockSyncInProgress)

        val lastRequest = requests.last()
        controller.onNearbyEvent(
            NearbyEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = SessionClockSyncBinaryResponse(
                    clientSendElapsedNanos = lastRequest.clientSendElapsedNanos,
                    hostReceiveElapsedNanos = lastRequest.clientSendElapsedNanos + 100L,
                    hostSendElapsedNanos = lastRequest.clientSendElapsedNanos + 200L,
                ),
            ),
        )

        assertFalse(controller.uiState.value.clockSyncInProgress)
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
    fun `snapshot ignores host timeline when client is unsynced`() {
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
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionTimelineSnapshotMessage(
                    hostStartSensorNanos = 1_000L,
                    hostStopSensorNanos = null,
                    sentElapsedNanos = 10L,
                ).toJsonString(),
            ),
        )
        assertEquals(600L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)

        controller.updateClockState(
            hostMinusClientElapsedNanos = null,
            hostSensorMinusElapsedNanos = null,
            localSensorMinusElapsedNanos = null,
            hostGpsUtcOffsetNanos = null,
            localGpsUtcOffsetNanos = null,
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionSnapshotMessage(
                    stage = SessionStage.MONITORING,
                    monitoringActive = true,
                    devices = listOf(
                        SessionDevice(
                            id = "local",
                            name = "Local",
                            role = SessionDeviceRole.STOP,
                            isLocal = true,
                        ),
                    ),
                    hostStartSensorNanos = 5_000L,
                    hostStopSensorNanos = 7_000L,
                    runId = "run-1",
                    hostSensorMinusElapsedNanos = null,
                    hostGpsUtcOffsetNanos = null,
                    hostGpsFixAgeNanos = null,
                    selfDeviceId = "local",
                    anchorDeviceId = "local",
                    anchorState = SessionAnchorState.ACTIVE,
                ).toJsonString(),
            ),
        )

        assertEquals(600L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)
        assertEquals("snapshot_applied_unsynced_timeline_ignored", controller.uiState.value.lastEvent)
    }

    @Test
    fun `single device mode auto resets active timeline and retains latest completed lap`() {
        val sentMessages = mutableListOf<String>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                sentMessages += messageJson
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.startSingleDeviceMonitoring()
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 1_000L)
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 2_000L)

        val state = controller.uiState.value
        assertEquals(SessionOperatingMode.SINGLE_DEVICE, state.operatingMode)
        assertEquals(SessionStage.MONITORING, state.stage)
        assertTrue(state.monitoringActive)
        assertNull(state.timeline.hostStartSensorNanos)
        assertNull(state.timeline.hostStopSensorNanos)
        assertEquals(1_000L, state.latestCompletedTimeline?.hostStartSensorNanos)
        assertEquals(2_000L, state.latestCompletedTimeline?.hostStopSensorNanos)
        assertEquals("single_device_stop", state.lastEvent)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun `single device mode ignores non-monotonic stop trigger`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.startSingleDeviceMonitoring()
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 2_000L)
        controller.onLocalMotionTrigger("motion", splitIndex = 0, triggerSensorNanos = 1_000L)

        val state = controller.uiState.value
        assertEquals(2_000L, state.timeline.hostStartSensorNanos)
        assertNull(state.timeline.hostStopSensorNanos)
        assertNull(state.latestCompletedTimeline)
    }

    @Test
    fun `clock sync sample can be ingested from non-nearby transport`() {
        var nowNanos = 0L
        val sentPayloads = mutableListOf<ByteArray>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = {
                nowNanos += 1_000L
                nowNanos
            },
            clockSyncDelay = { _ -> },
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

        val requests = sentPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        assertEquals(3, requests.size)
        requests.forEach { request ->
            controller.onClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = SessionClockSyncBinaryResponse(
                    clientSendElapsedNanos = request.clientSendElapsedNanos,
                    hostReceiveElapsedNanos = request.clientSendElapsedNanos + 200L,
                    hostSendElapsedNanos = request.clientSendElapsedNanos + 300L,
                ),
            )
        }

        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertEquals("clock_sync_complete", controller.uiState.value.lastEvent)
        assertNotNull(controller.clockState.value.hostMinusClientElapsedNanos)
    }

    @Test
    fun `auto ticker does not start NTP burst when fresh GPS lock exists`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete ->
                sentClockSyncRequests.incrementAndGet()
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
            clockSyncDelay = { _ -> },
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

        val requestsAfterFreshLock = sentClockSyncRequests.get()
        Thread.sleep(2500)
        assertEquals(requestsAfterFreshLock, sentClockSyncRequests.get())
    }

    @Test
    fun `auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val firstRequestSent = CountDownLatch(1)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete ->
                sentClockSyncRequests.incrementAndGet()
                firstRequestSent.countDown()
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
            clockSyncDelay = { _ -> },
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
    fun `in-progress NTP burst eventually completes when samples never return`() {
        val sentClockSyncRequests = AtomicInteger(0)
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete ->
                sentClockSyncRequests.incrementAndGet()
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
            clockSyncDelay = { _ -> },
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
        assertTrue(sentClockSyncRequests.get() >= 3)

        controller.updateClockState(
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            localGpsUtcOffsetNanos = 1_000L,
            localGpsFixAgeNanos = 1_000_000_000L,
            hostGpsUtcOffsetNanos = 900L,
            hostGpsFixAgeNanos = 1_000_000_000L,
        )

        Thread.sleep(2500)
        assertFalse(controller.uiState.value.clockSyncInProgress)
        assertTrue(sentClockSyncRequests.get() >= 3)
    }

    @Test
    fun `host freezes trigger acceptance when anchor disconnects mid-run`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "start-ep",
                endpointName = "start-device",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "stop-ep",
                endpointName = "stop-device",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("start-ep", SessionDeviceRole.START)
        controller.assignRole("stop-ep", SessionDeviceRole.STOP)
        assertTrue(controller.startMonitoring())

        controller.onNearbyEvent(NearbyEvent.EndpointDisconnected(endpointId = "start-ep"))
        assertEquals(SessionAnchorState.LOST, controller.uiState.value.anchorState)

        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "stop-ep",
                message = SessionTriggerRequestMessage(
                    role = SessionDeviceRole.STOP,
                    triggerSensorNanos = 2_000L,
                    mappedHostSensorNanos = 2_000L,
                    sourceDeviceId = "stop-ep",
                    sourceElapsedNanos = 123L,
                    mappedAnchorElapsedNanos = 123L,
                ).toJsonString(),
            ),
        )

        assertEquals("trigger_request_rejected_anchor_lost", controller.uiState.value.lastEvent)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)
    }

    @Test
    fun `host rejects non-start triggers when strict clock lock is stale`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "start-ep",
                endpointName = "start-device",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "stop-ep",
                endpointName = "stop-device",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("start-ep", SessionDeviceRole.START)
        controller.assignRole("stop-ep", SessionDeviceRole.STOP)
        assertTrue(controller.startMonitoring())
        assertFalse(controller.hasFreshAnyClockLock())

        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "stop-ep",
                message = SessionTriggerRequestMessage(
                    role = SessionDeviceRole.STOP,
                    triggerSensorNanos = 2_000L,
                    mappedHostSensorNanos = 2_000L,
                    sourceDeviceId = "stop-ep",
                    sourceElapsedNanos = 456L,
                    mappedAnchorElapsedNanos = 456L,
                ).toJsonString(),
            ),
        )

        assertEquals("trigger_request_rejected_unsynced", controller.uiState.value.lastEvent)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)
    }

    @Test
    fun `client stop trigger emits request telemetry with mapped host sensor presence`() {
        val sentMessages = mutableListOf<String>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, payload, onComplete ->
                sentMessages += payload
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 10_000L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "host-ep",
                endpointName = "host",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("local-device", SessionDeviceRole.STOP)
        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 900L,
            localSensorMinusElapsedNanos = 500L,
            lastClockSyncElapsedNanos = 10_000L,
        )
        assertTrue(controller.startMonitoring())

        controller.onLocalMotionTrigger(
            triggerType = "split",
            splitIndex = 0,
            triggerSensorNanos = 3_000L,
        )

        assertEquals("trigger_request_sent_stop_mapped_present", controller.uiState.value.lastEvent)
        val request = sentMessages
            .asReversed()
            .mapNotNull { SessionTriggerRequestMessage.tryParse(it) }
            .firstOrNull()
        assertNotNull(request)
        assertEquals(SessionDeviceRole.STOP, request!!.role)
        assertNotNull(request.mappedHostSensorNanos)
    }

    @Test
    fun `client start monitoring triggers immediate clock sync burst when connected`() {
        val sentClockSyncPayloads = mutableListOf<ByteArray>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, payloadBytes, onComplete ->
                sentClockSyncPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1_000_000L },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "host-ep",
                endpointName = "host",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val initialRequests = sentClockSyncPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        initialRequests.forEach { request ->
            controller.onNearbyEvent(
                NearbyEvent.ClockSyncSampleReceived(
                    endpointId = "host-ep",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
        }
        assertFalse(controller.uiState.value.clockSyncInProgress)

        val beforeStartMonitoringCount = sentClockSyncPayloads.size
        assertTrue(controller.startMonitoring())

        assertTrue(sentClockSyncPayloads.size > beforeStartMonitoringCount)
        assertEquals("clock_sync_burst_started", controller.uiState.value.lastEvent)
    }
}
