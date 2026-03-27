package com.paul.sprintsync.features.race_session

import com.paul.sprintsync.chirp_sync.ChirpCalibrationResult
import com.paul.sprintsync.core.services.NearbyEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RaceSessionControllerTest {
    @Test
    fun `clock sync burst computes lock from accepted samples`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 10_000_000_000L
        val sentMessages = mutableListOf<String>()

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, messageJson, onComplete ->
                sentMessages += messageJson
                onComplete(Result.success(Unit))
            },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(
                    calibrationId = calibrationId,
                    accepted = true,
                    hostMinusClientElapsedNanos = 101L,
                    jitterNanos = 2L,
                    reason = null,
                    completedAtElapsedNanos = now,
                    profile = profile,
                    sampleCount = sampleCount,
                )
            },
            clearCalibration = { },
            ioDispatcher = dispatcher,
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
    }

    @Test
    fun `clock sync burst rejects unconnected endpoint`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.startClockSyncBurst(endpointId = "missing", sampleCount = 3)

        assertEquals("Clock sync ignored: endpoint not connected", controller.uiState.value.lastError)
    }

    @Test
    fun `timeline start stop persists completed run`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var savedRunStarted: Long? = null
        var savedRunStopped: Long? = null

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { run ->
                savedRunStarted = run.startedSensorNanos
                savedRunStopped = run.stoppedSensorNanos
            },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = dispatcher,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("stop", splitIndex = 0, triggerSensorNanos = 2_000L, broadcast = false)
        advanceUntilIdle()

        assertEquals(1_000L, savedRunStarted)
        assertEquals(2_000L, savedRunStopped)
    }

    @Test
    fun `timeline snapshot maps host sensor into local sensor in client mode`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
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
    fun `accepted chirp result updates chirp lock fields`() = runTest {
        var now = 5_000L
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = {
                now += 1L
                now
            },
        )

        val result = SessionChirpCalibrationResultMessage(
            calibrationId = "cal-1",
            accepted = true,
            hostMinusClientElapsedNanos = 321L,
            jitterNanos = 8L,
            reason = null,
            completedAtElapsedNanos = 4_000L,
            profile = "fallback",
            sampleCount = 5,
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "ep-1",
                message = result.toJsonString(),
            ),
        )

        assertEquals(321L, controller.clockState.value.chirpHostMinusClientElapsedNanos)
        assertEquals(8L, controller.clockState.value.chirpJitterNanos)
        assertTrue(controller.hasFreshChirpLock(maxAgeNanos = 2_000L))
    }

    @Test
    fun `gps lock takes precedence over ntp lock for sensor mapping`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.updateClockState(
            hostMinusClientElapsedNanos = 100L,
            hostSensorMinusElapsedNanos = 500L,
            localSensorMinusElapsedNanos = 200L,
            localGpsUtcOffsetNanos = 10_000L,
            hostGpsUtcOffsetNanos = 9_920L,
            localGpsFixAgeNanos = 1_000_000_000L,
            hostGpsFixAgeNanos = 900_000_000L,
        )

        val mapped = controller.mapClientSensorToHostSensor(1_200L)

        assertEquals(1_580L, mapped)
        assertTrue(controller.hasFreshGpsLock())
    }

    @Test
    fun `stop hosting returns session to setup and clears monitoring`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.assignRole("local-device", SessionDeviceRole.START)
        controller.assignRole("peer-1", SessionDeviceRole.STOP)
        controller.goToLobby()
        assertTrue(controller.startMonitoring())

        controller.stopHostingAndReturnToSetup()

        assertEquals(SessionNetworkRole.NONE, controller.uiState.value.networkRole)
        assertEquals(SessionStage.SETUP, controller.uiState.value.stage)
        assertFalse(controller.uiState.value.monitoringActive)
        assertEquals(0, controller.uiState.value.connectedEndpoints.size)
    }

    @Test
    fun `client drops trigger update when sync mapping is unavailable`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        val raw = SessionTriggerMessage(
            triggerType = "start",
            splitIndex = 0,
            triggerSensorNanos = 2_000L,
        ).toJsonString()
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "host-1",
                message = raw,
            ),
        )

        assertNull(controller.uiState.value.timeline.hostStartSensorNanos)
        assertEquals("trigger_dropped_unsynced", controller.uiState.value.lastEvent)
    }

    @Test
    fun `client drops timeline snapshot when sync mapping is unavailable`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.CLIENT)
        val raw = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = 1_000L,
            hostStopSensorNanos = 2_000L,
            sentElapsedNanos = 2L,
        ).toJsonString()
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "host-1",
                message = raw,
            ),
        )

        assertNull(controller.uiState.value.timeline.hostStartSensorNanos)
        assertNull(controller.uiState.value.timeline.hostStopSensorNanos)
        assertEquals("timeline_snapshot_dropped_unsynced", controller.uiState.value.lastEvent)
    }

    @Test
    fun `host chirp start broadcasts to all connected endpoints`() = runTest {
        val sentMessages = mutableListOf<Pair<String, String>>()
        var startedCalibrationId: String? = null
        var startedRole: String? = null
        var startedProfile: String? = null
        var startedSampleCount: Int? = null

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { endpointId, messageJson, onComplete ->
                sentMessages += endpointId to messageJson
                onComplete(Result.success(Unit))
            },
            startCalibration = { calibrationId, role, profile, sampleCount, _, _ ->
                startedCalibrationId = calibrationId
                startedRole = role
                startedProfile = profile
                startedSampleCount = sampleCount
                ChirpCalibrationResult(
                    calibrationId = calibrationId,
                    accepted = true,
                    hostMinusClientElapsedNanos = null,
                    jitterNanos = null,
                    reason = null,
                    completedAtElapsedNanos = null,
                    profile = profile,
                    sampleCount = sampleCount,
                )
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1_000L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-1",
                endpointName = "peer-1",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "ep-2",
                endpointName = "peer-2",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        controller.startChirpSyncAllConnected(profile = "fallback", sampleCount = 4)

        assertTrue(controller.uiState.value.chirpSyncInProgress)
        assertEquals(setOf("ep-1", "ep-2"), sentMessages.map { it.first }.toSet())

        val startMessages = sentMessages.mapNotNull { (_, raw) ->
            SessionChirpCalibrationStartMessage.tryParse(raw)
        }
        assertEquals(2, startMessages.size)
        assertEquals(1, startMessages.map { it.calibrationId }.toSet().size)
        assertEquals("responder", startMessages.first().role)
        assertEquals("fallback", startMessages.first().profile)
        assertEquals(4, startMessages.first().sampleCount)
        assertNotNull(startMessages.first().remoteSendElapsedNanos)

        assertEquals(startMessages.first().calibrationId, startedCalibrationId)
        assertEquals("initiator", startedRole)
        assertEquals("fallback", startedProfile)
        assertEquals(4, startedSampleCount)
    }

    @Test
    fun `host chirp start rejects when no connected endpoints`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.startChirpSyncAllConnected()

        assertEquals("Chirp sync ignored: no connected endpoints", controller.uiState.value.lastError)
        assertFalse(controller.uiState.value.chirpSyncInProgress)
    }

    @Test
    fun `reconnect with new endpoint id preserves assigned role after identity handshake`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-old",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "peer-old",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-peer",
                    deviceName = "peer",
                ).toJsonString(),
            ),
        )
        controller.assignRole("peer-old", SessionDeviceRole.STOP)
        controller.onNearbyEvent(NearbyEvent.EndpointDisconnected(endpointId = "peer-old"))
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-new",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "peer-new",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-peer",
                    deviceName = "peer",
                ).toJsonString(),
            ),
        )

        val peers = controller.uiState.value.devices.filterNot { it.isLocal }
        assertEquals(1, peers.size)
        assertEquals("peer-new", peers.first().id)
        assertEquals(SessionDeviceRole.STOP, peers.first().role)
    }

    @Test
    fun `identity reconciliation updates endpoint without duplicating peer rows`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-old",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "peer-old",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-peer",
                    deviceName = "peer",
                ).toJsonString(),
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-new",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.PayloadReceived(
                endpointId = "peer-new",
                message = SessionDeviceIdentityMessage(
                    stableDeviceId = "stable-peer",
                    deviceName = "peer",
                ).toJsonString(),
            ),
        )

        val peerIds = controller.uiState.value.devices.filterNot { it.isLocal }.map { it.id }
        assertEquals(listOf("peer-new"), peerIds)
    }

    @Test
    fun `disconnect prunes non-local peer immediately in p2p mode`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(NearbyEvent.EndpointDisconnected(endpointId = "peer-1"))

        val peerIds = controller.uiState.value.devices.filterNot { it.isLocal }.map { it.id }
        assertTrue(peerIds.isEmpty())
    }

    @Test
    fun `new connection replaces previous non-local peer in p2p mode`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-1",
                endpointName = "peer-1",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-2",
                endpointName = "peer-2",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        val peerIds = controller.uiState.value.devices.filterNot { it.isLocal }.map { it.id }
        assertEquals(listOf("peer-2"), peerIds)
    }

    @Test
    fun `host snapshot contains only local and currently connected peer after replacement`() = runTest {
        val sentMessages = mutableListOf<Pair<String, String>>()
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { endpointId, messageJson, onComplete ->
                sentMessages += endpointId to messageJson
                onComplete(Result.success(Unit))
            },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-1",
                endpointName = "peer-1",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-2",
                endpointName = "peer-2",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val latestSnapshotToPeer2 = sentMessages
            .filter { it.first == "peer-2" }
            .mapNotNull { (_, raw) -> SessionSnapshotMessage.tryParse(raw) }
            .lastOrNull()

        assertNotNull(latestSnapshotToPeer2)
        val snapshotDeviceIds = latestSnapshotToPeer2!!.devices.map { it.id }.toSet()
        assertTrue(snapshotDeviceIds.contains("local-device"))
        assertFalse(snapshotDeviceIds.contains("peer-1"))
        assertTrue(snapshotDeviceIds.contains("peer-2"))
    }

    @Test
    fun `host auto assigns start stop by join order and manual reassignment remains available`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nowElapsedNanos = { 1L },
        )

        controller.setNetworkRole(SessionNetworkRole.HOST)
        controller.onNearbyEvent(
            NearbyEvent.ConnectionResult(
                endpointId = "peer-1",
                endpointName = "peer",
                connected = true,
                statusCode = 0,
                statusMessage = null,
            ),
        )

        val localBeforeManual = controller.uiState.value.devices.first { it.isLocal }
        val remoteBeforeManual = controller.uiState.value.devices.first { !it.isLocal }
        assertEquals(SessionDeviceRole.START, localBeforeManual.role)
        assertEquals(SessionDeviceRole.STOP, remoteBeforeManual.role)

        controller.assignRole("local-device", SessionDeviceRole.STOP)

        val localAfterManual = controller.uiState.value.devices.first { it.isLocal }
        val remoteAfterManual = controller.uiState.value.devices.first { !it.isLocal }
        assertEquals(SessionDeviceRole.STOP, localAfterManual.role)
        assertEquals(SessionDeviceRole.START, remoteAfterManual.role)
    }
}
