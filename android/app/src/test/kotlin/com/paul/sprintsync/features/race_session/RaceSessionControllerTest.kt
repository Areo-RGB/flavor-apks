package com.paul.sprintsync.features.race_session

import com.paul.sprintsync.core.services.SessionConnectionEvent
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
    fun `host xiaomi pins oneplus start huawei split and pixel stop`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.START, isLocal = true),
            SessionDevice(id = "ep1", name = "OnePlus 12", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Huawei P30", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep3", name = "Pixel 8", role = SessionDeviceRole.START, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "local" }.role)
        assertEquals(SessionDeviceRole.START, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.SPLIT2, mapped.first { it.id == "ep2" }.role)
        assertEquals(SessionDeviceRole.STOP, mapped.first { it.id == "ep3" }.role)
    }

    @Test
    fun `host xiaomi pins cph oneplus alias and honor alias`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.UNASSIGNED, isLocal = true),
            SessionDevice(id = "ep1", name = "CPH2399", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "HONOR 70", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep3", name = "Pixel 7", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.START, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.SPLIT2, mapped.first { it.id == "ep2" }.role)
        assertEquals(SessionDeviceRole.STOP, mapped.first { it.id == "ep3" }.role)
        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "local" }.role)
    }

    @Test
    fun `host xiaomi pins known devices even when full trio is not present`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.UNASSIGNED, isLocal = true),
            SessionDevice(id = "ep1", name = "CPH2399", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Device B", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "local" }.role)
        assertEquals(SessionDeviceRole.START, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "ep2" }.role)
    }

    @Test
    fun `host xiaomi pins huawei split2 and clears duplicate split roles`() {
        val devices = listOf(
            SessionDevice(id = "local", name = "Host Xiaomi", role = SessionDeviceRole.UNASSIGNED, isLocal = true),
            SessionDevice(id = "ep1", name = "HUAWEI EML-L29", role = SessionDeviceRole.UNASSIGNED, isLocal = false),
            SessionDevice(id = "ep2", name = "Unknown Device", role = SessionDeviceRole.SPLIT2, isLocal = false),
        )

        val mapped = applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")

        assertEquals(SessionDeviceRole.SPLIT2, mapped.first { it.id == "ep1" }.role)
        assertEquals(SessionDeviceRole.UNASSIGNED, mapped.first { it.id == "ep2" }.role)
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

        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
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

        controller.onConnectionEvent(
            SessionConnectionEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response2,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.ClockSyncSampleReceived(
                endpointId = "ep-1",
                sample = response3,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.ClockSyncSampleReceived(
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

        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
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
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ClockSyncSampleReceived(
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

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
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
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
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
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
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
    fun `clock sync sample can be ingested from tcp transport`() {
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
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
    fun `auto ticker does not start NTP burst when RTT lock is valid`() {
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            hostClockRoundTripNanos = 90_000_000L,
        )

        val requestsAfterFreshLock = sentClockSyncRequests.get()
        Thread.sleep(2500)
        assertEquals(requestsAfterFreshLock, sentClockSyncRequests.get())
    }

    @Test
    fun `auto ticker starts NTP burst when RTT lock is invalid`() {
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
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
    fun `adaptive ticker starts stale burst after lock ages out`() {
        var now = 0L
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
            nowElapsedNanos = { now },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val initialRequests = sentClockSyncPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        now = 1_000_000_000L
        initialRequests.forEach { request ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
                    endpointId = "ep-1",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
            now += 1_000_000L
        }
        assertFalse(controller.uiState.value.clockSyncInProgress)

        val beforeAdaptiveTick = sentClockSyncPayloads.size
        now = 20_000_000_000L
        controller.runAdaptiveClockSyncTickForTest()

        assertTrue(sentClockSyncPayloads.size > beforeAdaptiveTick)
        assertEquals("clock_sync_burst_started_stale", controller.uiState.value.lastEvent)
    }

    @Test
    fun `adaptive ticker starts drift burst after cooldown when host offset jumps`() {
        var now = 0L
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
            nowElapsedNanos = { now },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val initialRequests = sentClockSyncPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        now = 1_000_000_000L
        initialRequests.forEach { request ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
                    endpointId = "ep-1",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
            now += 1_000_000L
        }

        now = 20_000_000_000L
        controller.updateClockState(hostSensorMinusElapsedNanos = 1_000L)
        now = 20_100_000_000L
        controller.updateClockState(hostSensorMinusElapsedNanos = 30_000_000L)

        val beforeDriftTick = sentClockSyncPayloads.size
        now = 21_000_000_000L
        controller.runAdaptiveClockSyncTickForTest()
        assertEquals(beforeDriftTick, sentClockSyncPayloads.size)

        now = 22_500_000_000L
        controller.runAdaptiveClockSyncTickForTest()

        assertTrue(sentClockSyncPayloads.size > beforeDriftTick)
        assertEquals("clock_sync_burst_started_drift", controller.uiState.value.lastEvent)
    }

    @Test
    fun `adaptive ticker emits rate limit event for drift burst inside minimum spacing`() {
        var now = 0L
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
            nowElapsedNanos = { now },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setSessionStage(SessionStage.LOBBY)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val initialRequests = sentClockSyncPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        now = 1_000_000_000L
        initialRequests.forEach { request ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
                    endpointId = "ep-1",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
            now += 1_000_000L
        }

        now = 30_000_000_000L
        controller.startClockSyncBurst(endpointId = "ep-1", sampleCount = 3)
        val manualRequests = sentClockSyncPayloads
            .drop(initialRequests.size)
            .mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        manualRequests.forEach { request ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
                    endpointId = "ep-1",
                    sample = SessionClockSyncBinaryResponse(
                        clientSendElapsedNanos = request.clientSendElapsedNanos,
                        hostReceiveElapsedNanos = request.clientSendElapsedNanos + 100L,
                        hostSendElapsedNanos = request.clientSendElapsedNanos + 200L,
                    ),
                ),
            )
        }

        controller.updateClockState(hostSensorMinusElapsedNanos = 1_000L)
        now = 30_100_000_000L
        controller.updateClockState(hostSensorMinusElapsedNanos = 30_000_000L)
        val beforeRateLimitedTick = sentClockSyncPayloads.size

        now = 33_000_000_000L
        controller.runAdaptiveClockSyncTickForTest()

        assertEquals(beforeRateLimitedTick, sentClockSyncPayloads.size)
        assertEquals("clock_sync_skipped_rate_limited", controller.uiState.value.lastEvent)
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "start-ep",
                endpointName = "OnePlus 12",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "stop-ep",
                endpointName = "Pixel 7",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("start-ep", SessionDeviceRole.START)
        controller.assignRole("stop-ep", SessionDeviceRole.STOP)
        assertTrue(controller.startMonitoring())

        controller.onConnectionEvent(SessionConnectionEvent.EndpointDisconnected(endpointId = "start-ep"))
        assertEquals(SessionAnchorState.LOST, controller.uiState.value.anchorState)

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
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
    fun `host accepts non-start trigger requests when mapped host sensor is present`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "start-ep",
                endpointName = "OnePlus 12",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "stop-ep",
                endpointName = "Pixel 7",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("start-ep", SessionDeviceRole.START)
        controller.assignRole("stop-ep", SessionDeviceRole.STOP)
        assertTrue(controller.startMonitoring())
        assertFalse(controller.hasFreshAnyClockLock())

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "start-ep",
                message = SessionTriggerRequestMessage(
                    role = SessionDeviceRole.START,
                    triggerSensorNanos = 1_000L,
                    mappedHostSensorNanos = 1_000L,
                    sourceDeviceId = "start-ep",
                    sourceElapsedNanos = 123L,
                    mappedAnchorElapsedNanos = 123L,
                ).toJsonString(),
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
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

        assertEquals(1_000L, controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals(2_000L, controller.uiState.value.timeline.hostStopSensorNanos)
    }

    @Test
    fun `host ingests telemetry and sends remote sensitivity update to mapped endpoint`() {
        val sentMessages = mutableListOf<Pair<String, String>>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { endpointId, payload, onComplete ->
                sentMessages += endpointId to payload
                onComplete(Result.success(Unit))
            },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "Huawei",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-huawei",
                    deviceName = "Huawei P30",
                ).toJsonString(),
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "ep-1",
                message = SessionDeviceTelemetryMessage(
                    stableDeviceId = "stable-huawei",
                    role = SessionDeviceRole.SPLIT2,
                    sensitivity = 63,
                    latencyMs = 21,
                    clockSynced = true,
                    timestampMillis = 10L,
                ).toJsonString(),
            ),
        )

        val telemetry = controller.uiState.value.remoteDeviceTelemetry["stable-huawei"]
        assertNotNull(telemetry)
        assertEquals(SessionDeviceRole.SPLIT2, telemetry?.role)
        assertEquals(63, telemetry?.sensitivity)
        assertEquals(21, telemetry?.latencyMs)
        assertTrue(telemetry?.clockSynced == true)
        assertEquals("stable-huawei", controller.stableDeviceIdForEndpoint("ep-1"))

        assertTrue(controller.sendRemoteSensitivityUpdate("stable-huawei", 44))
        val sentConfig = sentMessages
            .asReversed()
            .mapNotNull { (_, payload) -> SessionDeviceConfigUpdateMessage.tryParse(payload) }
            .firstOrNull()
        assertNotNull(sentConfig)
        assertEquals("stable-huawei", sentConfig?.targetStableDeviceId)
        assertEquals(44, sentConfig?.sensitivity)
        assertEquals("ep-1", sentMessages.last().first)
        assertEquals(44, controller.uiState.value.remoteDeviceTelemetry["stable-huawei"]?.sensitivity)
    }

    @Test
    fun `host auto assigns connected huawei endpoint to split2 role`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )
        controller.setNetworkRole(SessionNetworkRole.HOST)

        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-huawei",
                endpointName = "Huawei P30",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val huawei = controller.uiState.value.devices.firstOrNull { it.id == "ep-huawei" }
        assertNotNull(huawei)
        assertEquals(SessionDeviceRole.SPLIT2, huawei?.role)
    }

    @Test
    fun `client applies unsynced start trigger as local fallback when monitoring active`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 123_000L },
        )
        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.startMonitoring()

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "host-ep",
                message = SessionTriggerMessage(
                    triggerType = "start",
                    triggerSensorNanos = 5_000L,
                ).toJsonString(),
            ),
        )

        assertNotNull(controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals("trigger_start_applied_unsynced", controller.uiState.value.lastEvent)
    }

    @Test
    fun `client applies unsynced stop trigger as local fallback when start already exists`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 456_000L },
        )
        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.startMonitoring()
        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 100_000L, broadcast = false)

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "host-ep",
                message = SessionTriggerMessage(
                    triggerType = "stop",
                    triggerSensorNanos = 5_000L,
                ).toJsonString(),
            ),
        )

        assertNotNull(controller.uiState.value.timeline.hostStopSensorNanos)
        assertEquals("trigger_stop_applied_unsynced", controller.uiState.value.lastEvent)
    }

    @Test
    fun `host auto assigns Topaz split1 Huawei split2 and fallback split3`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )
        controller.setNetworkRole(SessionNetworkRole.HOST)

        listOf(
            "ep-1" to "Runner 1",
            "ep-2" to "Runner 2",
            "ep-3" to "Topaz",
            "ep-4" to "Huawei P30",
            "ep-5" to "Runner 5",
        ).forEach { (endpointId, endpointName) ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ConnectionResult(
                    endpointId = endpointId,
                    endpointName = endpointName,
                    connected = true,
                    statusCode = 0,
                    statusMessage = null,
                ),
            )
        }

        val rolesById = controller.uiState.value.devices.associate { it.id to it.role }
        assertEquals(SessionDeviceRole.START, rolesById["ep-1"])
        assertEquals(SessionDeviceRole.STOP, rolesById["ep-2"])
        assertEquals(SessionDeviceRole.SPLIT1, rolesById["ep-3"])
        assertEquals(SessionDeviceRole.SPLIT2, rolesById["ep-4"])
        assertEquals(SessionDeviceRole.SPLIT3, rolesById["ep-5"])
    }

    @Test
    fun `split checkpoint trigger is accepted only after start and before stop`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 900L, broadcast = false)
        assertTrue(controller.uiState.value.timeline.hostSplitMarks.isEmpty())

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 1_400L, broadcast = false)
        controller.ingestLocalTrigger("stop", splitIndex = 0, triggerSensorNanos = 2_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 2_200L, broadcast = false)

        assertEquals(
            listOf(SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_400L)),
            controller.uiState.value.timeline.hostSplitMarks,
        )
    }

    @Test
    fun `multiple split checkpoints record independently and do not finish run`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 1_200L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 1, triggerSensorNanos = 1_700L, broadcast = false)

        val timeline = controller.uiState.value.timeline
        assertEquals(
            listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_200L),
                SessionSplitMark(role = SessionDeviceRole.SPLIT2, hostSensorNanos = 1_700L),
            ),
            timeline.hostSplitMarks,
        )
        assertNull(timeline.hostStopSensorNanos)
    }

    @Test
    fun `client applies targeted device config update and exposes pending sensitivity once`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )
        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        controller.setLocalDeviceIdentity(deviceId = "stable-local", deviceName = "Pixel")

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "host-ep",
                message = SessionDeviceConfigUpdateMessage(
                    targetStableDeviceId = "stable-local",
                    sensitivity = 37,
                ).toJsonString(),
            ),
        )

        assertEquals(37, controller.consumePendingSensitivityUpdateFromHost())
        assertNull(controller.consumePendingSensitivityUpdateFromHost())

        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "host-ep",
                message = SessionDeviceConfigUpdateMessage(
                    targetStableDeviceId = "someone-else",
                    sensitivity = 90,
                ).toJsonString(),
            ),
        )
        assertNull(controller.consumePendingSensitivityUpdateFromHost())
    }

    @Test
    fun `host telemetry mapping remains stable across disconnect and reconnect`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-old",
                endpointName = "Huawei",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "ep-old",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-huawei",
                    deviceName = "Huawei P30",
                ).toJsonString(),
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "ep-old",
                message = SessionDeviceTelemetryMessage(
                    stableDeviceId = "stable-huawei",
                    role = SessionDeviceRole.SPLIT2,
                    sensitivity = 20,
                    latencyMs = 30,
                    clockSynced = false,
                    timestampMillis = 10L,
                ).toJsonString(),
            ),
        )
        assertNotNull(controller.uiState.value.remoteDeviceTelemetry["stable-huawei"])

        controller.onConnectionEvent(SessionConnectionEvent.EndpointDisconnected(endpointId = "ep-old"))
        assertNull(controller.uiState.value.remoteDeviceTelemetry["stable-huawei"])
        assertNull(controller.stableDeviceIdForEndpoint("ep-old"))

        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "ep-new",
                endpointName = "Huawei",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "ep-new",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-huawei",
                    deviceName = "Huawei P30",
                ).toJsonString(),
            ),
        )
        controller.onConnectionEvent(
            SessionConnectionEvent.PayloadReceived(
                endpointId = "ep-new",
                message = SessionDeviceTelemetryMessage(
                    stableDeviceId = "stable-huawei",
                    role = SessionDeviceRole.SPLIT2,
                    sensitivity = 33,
                    latencyMs = 12,
                    clockSynced = true,
                    timestampMillis = 11L,
                ).toJsonString(),
            ),
        )

        assertEquals("stable-huawei", controller.stableDeviceIdForEndpoint("ep-new"))
        assertEquals(33, controller.uiState.value.remoteDeviceTelemetry["stable-huawei"]?.sensitivity)
        assertEquals(12, controller.uiState.value.remoteDeviceTelemetry["stable-huawei"]?.latencyMs)
        assertTrue(controller.uiState.value.remoteDeviceTelemetry["stable-huawei"]?.clockSynced == true)
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
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
            hostClockRoundTripNanos = 150_000_000L,
        )
        assertFalse(controller.hasFreshAnyClockLock())
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "host-ep",
                endpointName = "host",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val initialRequests = sentClockSyncPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        initialRequests.forEach { request ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
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
        assertEquals("clock_sync_burst_started_stale", controller.uiState.value.lastEvent)
    }

    @Test
    fun `client reset run triggers immediate clock sync burst when connected`() {
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
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "host-ep",
                endpointName = "host",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val initialRequests = sentClockSyncPayloads.mapNotNull { SessionClockSyncBinaryCodec.decodeRequest(it) }
        initialRequests.forEach { request ->
            controller.onConnectionEvent(
                SessionConnectionEvent.ClockSyncSampleReceived(
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

        val beforeResetCount = sentClockSyncPayloads.size
        controller.resetRun()

        assertTrue(sentClockSyncPayloads.size > beforeResetCount)
        assertEquals("clock_sync_burst_started_stale", controller.uiState.value.lastEvent)
    }

    @Test
    fun `telemetry payload event decodes and applies trigger`() {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendClockSyncPayload = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 1_000L },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        val payloadBytes = TelemetryEnvelopeFlatBufferCodec.encodeTrigger(
            SessionTriggerMessage(
                triggerType = "start",
                splitIndex = null,
                triggerSensorNanos = 1_234L,
            ),
        )

        controller.onConnectionEvent(
            SessionConnectionEvent.TelemetryPayloadReceived(
                endpointId = "ep-1",
                payloadBytes = payloadBytes,
            ),
        )

        assertEquals(1_234L, controller.uiState.value.timeline.hostStartSensorNanos)
    }

    @Test
    fun `binary telemetry mode broadcasts trigger and timeline envelopes`() {
        val sentMessages = mutableListOf<String>()
        val sentTelemetryPayloads = mutableListOf<ByteArray>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, message, onComplete ->
                sentMessages += message
                onComplete(Result.success(Unit))
            },
            sendClockSyncPayload = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            sendTelemetryPayload = { _, payloadBytes, onComplete ->
                sentTelemetryPayloads += payloadBytes
                onComplete(Result.success(Unit))
            },
            enableBinaryTelemetry = true,
            ioDispatcher = Dispatchers.Unconfined,
            nowElapsedNanos = { 5_000L },
            clockSyncDelay = { _ -> },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onConnectionEvent(
            SessionConnectionEvent.ConnectionResult(
                endpointId = "peer-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        controller.ingestLocalTrigger(
            triggerType = "start",
            splitIndex = 0,
            triggerSensorNanos = 123L,
            broadcast = true,
        )

        assertEquals(2, sentTelemetryPayloads.size)

        val firstPayload = TelemetryEnvelopeFlatBufferCodec.decode(sentTelemetryPayloads[0])
        val secondPayload = TelemetryEnvelopeFlatBufferCodec.decode(sentTelemetryPayloads[1])
        assertTrue(firstPayload is DecodedTelemetryEnvelope.Trigger)
        assertTrue(secondPayload is DecodedTelemetryEnvelope.TimelineSnapshot)
    }
}
