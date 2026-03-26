package com.paul.sprintsync.features.race_session

import com.paul.sprintsync.chirp_sync.ChirpCalibrationResult
import com.paul.sprintsync.core.services.NearbyEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            startCalibration = { calibrationId, _, profile, sampleCount, _ ->
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
            startCalibration = { calibrationId, _, profile, sampleCount, _ ->
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
    fun `timeline start split stop persists completed run`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var savedRunStarted: Long? = null
        var savedRunSplits: List<Long>? = null

        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { run ->
                savedRunStarted = run.startedSensorNanos
                savedRunSplits = run.splitElapsedNanos
            },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _ ->
                ChirpCalibrationResult(calibrationId, true, null, null, null, null, profile, sampleCount)
            },
            clearCalibration = { },
            ioDispatcher = dispatcher,
            nowElapsedNanos = { 1L },
        )

        controller.ingestLocalTrigger("start", splitIndex = 0, triggerSensorNanos = 1_000L, broadcast = false)
        controller.ingestLocalTrigger("split", splitIndex = 0, triggerSensorNanos = 1_500L, broadcast = false)
        controller.ingestLocalTrigger("stop", splitIndex = 0, triggerSensorNanos = 2_000L, broadcast = false)
        advanceUntilIdle()

        assertEquals(1_000L, savedRunStarted)
        assertEquals(listOf(500L), savedRunSplits)
    }

    @Test
    fun `timeline snapshot maps host sensor into local sensor in client mode`() = runTest {
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _ ->
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
            hostSplitSensorNanos = listOf(1_500L),
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
        assertEquals(listOf(1_100L), controller.uiState.value.timeline.hostSplitSensorNanos)
        assertEquals(1_600L, controller.uiState.value.timeline.hostStopSensorNanos)
    }

    @Test
    fun `accepted chirp result updates chirp lock fields`() = runTest {
        var now = 5_000L
        val controller = RaceSessionController(
            loadLastRun = { null },
            saveLastRun = { },
            sendMessage = { _, _, onComplete -> onComplete(Result.success(Unit)) },
            startCalibration = { calibrationId, _, profile, sampleCount, _ ->
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
}
