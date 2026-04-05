package com.paul.sprintsync

import com.paul.sprintsync.core.models.SavedRunResult
import com.paul.sprintsync.core.services.SessionConnectionEvent
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.features.race_session.SessionOperatingMode
import com.paul.sprintsync.features.race_session.SessionSplitMark
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.Locale

class MainActivityMonitoringLogicTest {
    @Test
    fun `shows wifi only guidance when tcp client connection fails`() {
        val message = connectionFailureGuidanceMessage(
            event = SessionConnectionEvent.ConnectionResult(
                endpointId = "host",
                endpointName = "Pad",
                connected = false,
                statusCode = -1,
                statusMessage = "connect failed",
            ),
            isTcpOnly = true,
            sessionNetworkRole = SessionNetworkRole.CLIENT,
        )

        assertEquals("Connection failed. Turn off mobile data / use Wi-Fi only.", message)
    }

    @Test
    fun `does not show wifi only guidance for non tcp or successful connection`() {
        val tcpSuccessMessage = connectionFailureGuidanceMessage(
            event = SessionConnectionEvent.ConnectionResult(
                endpointId = "host",
                endpointName = "Pad",
                connected = true,
                statusCode = 0,
                statusMessage = "connected",
            ),
            isTcpOnly = true,
            sessionNetworkRole = SessionNetworkRole.CLIENT,
        )
        val nonTcpMessage = connectionFailureGuidanceMessage(
            event = SessionConnectionEvent.ConnectionResult(
                endpointId = "host",
                endpointName = "Pad",
                connected = false,
                statusCode = -1,
                statusMessage = "connect failed",
            ),
            isTcpOnly = false,
            sessionNetworkRole = SessionNetworkRole.CLIENT,
        )
        val hostModeMessage = connectionFailureGuidanceMessage(
            event = SessionConnectionEvent.ConnectionResult(
                endpointId = "host",
                endpointName = "Pad",
                connected = false,
                statusCode = -1,
                statusMessage = "connect failed",
            ),
            isTcpOnly = true,
            sessionNetworkRole = SessionNetworkRole.HOST,
        )

        assertEquals(null, tcpSuccessMessage)
        assertEquals(null, nonTcpMessage)
        assertEquals(null, hostModeMessage)
    }

    @Test
    fun `starts local capture when monitoring active resumed assigned and local capture is idle`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.START, action)
    }

    @Test
    fun `stops local capture when app pauses during monitoring`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = false,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.STOP, action)
    }

    @Test
    fun `stops local capture when local role becomes unassigned during monitoring`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = false,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.STOP, action)
    }

    @Test
    fun `keeps local capture unchanged when monitoring state is already satisfied`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.NONE, action)
    }

    @Test
    fun `timer refresh runs only during active in-progress resumed monitoring`() {
        assertTrue(
            shouldKeepTimerRefreshActive(
                monitoringActive = true,
                isAppResumed = true,
                hasStopSensor = false,
            ),
        )
        assertFalse(
            shouldKeepTimerRefreshActive(
                monitoringActive = true,
                isAppResumed = false,
                hasStopSensor = false,
            ),
        )
        assertFalse(
            shouldKeepTimerRefreshActive(
                monitoringActive = true,
                isAppResumed = true,
                hasStopSensor = true,
            ),
        )
    }

    @Test
    fun `does not start capture again while start is pending`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = true,
        )

        assertEquals(LocalCaptureAction.NONE, action)
    }

    @Test
    fun `does not start local capture when user monitoring toggle is off`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = false,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.NONE, action)
    }

    @Test
    fun `stops local capture when user monitoring toggle is turned off during monitoring`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = false,
            isLocalMotionMonitoring = true,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.STOP, action)
    }

    @Test
    fun `re-enabling user monitoring toggle allows local capture start when guards are met`() {
        val action = resolveLocalCaptureAction(
            monitoringActive = true,
            isAppResumed = true,
            shouldRunLocalCapture = true,
            isLocalMotionMonitoring = false,
            localCaptureStartPending = false,
        )

        assertEquals(LocalCaptureAction.START, action)
    }

    @Test
    fun `wifi lock policy enables only for active network race monitoring`() {
        assertTrue(
            shouldHoldMonitoringWifiLock(
                operatingMode = SessionOperatingMode.NETWORK_RACE,
                stage = SessionStage.MONITORING,
                monitoringActive = true,
            ),
        )
        assertFalse(
            shouldHoldMonitoringWifiLock(
                operatingMode = SessionOperatingMode.NETWORK_RACE,
                stage = SessionStage.LOBBY,
                monitoringActive = true,
            ),
        )
        assertFalse(
            shouldHoldMonitoringWifiLock(
                operatingMode = SessionOperatingMode.NETWORK_RACE,
                stage = SessionStage.MONITORING,
                monitoringActive = false,
            ),
        )
        assertFalse(
            shouldHoldMonitoringWifiLock(
                operatingMode = SessionOperatingMode.SINGLE_DEVICE,
                stage = SessionStage.MONITORING,
                monitoringActive = true,
            ),
        )
        assertFalse(
            shouldHoldMonitoringWifiLock(
                operatingMode = SessionOperatingMode.DISPLAY_HOST,
                stage = SessionStage.MONITORING,
                monitoringActive = true,
            ),
        )
    }

    @Test
    fun `wifi lock mode prefers low latency on api 29 plus with high perf fallback`() {
        assertEquals(MonitoringWifiLockMode.HIGH_PERF, selectMonitoringWifiLockMode(apiLevel = 28))
        assertEquals(MonitoringWifiLockMode.LOW_LATENCY, selectMonitoringWifiLockMode(apiLevel = 29))
        assertEquals(MonitoringWifiLockMode.LOW_LATENCY, selectMonitoringWifiLockMode(apiLevel = 35))
    }

    @Test
    fun `display host mode prefers landscape orientation`() {
        assertTrue(shouldUseLandscapeForMode(SessionOperatingMode.DISPLAY_HOST))
        assertFalse(shouldUseLandscapeForMode(SessionOperatingMode.SINGLE_DEVICE))
        assertFalse(shouldUseLandscapeForMode(SessionOperatingMode.NETWORK_RACE))
    }

    @Test
    fun `display host mode uses immersive fullscreen and other modes do not`() {
        assertTrue(shouldUseImmersiveModeForMode(SessionOperatingMode.DISPLAY_HOST))
        assertFalse(shouldUseImmersiveModeForMode(SessionOperatingMode.SINGLE_DEVICE))
        assertFalse(shouldUseImmersiveModeForMode(SessionOperatingMode.NETWORK_RACE))
    }

    @Test
    fun `timer display uses ss cc below one minute and no three-digit milliseconds`() {
        assertEquals("00.00", formatElapsedTimerDisplay(totalMillis = 0))
        assertEquals("01.67", formatElapsedTimerDisplay(totalMillis = 1_678))
        assertEquals("59.99", formatElapsedTimerDisplay(totalMillis = 59_999))
    }

    @Test
    fun `timer display prepends minutes from one minute onward with centiseconds`() {
        assertEquals("01:00.00", formatElapsedTimerDisplay(totalMillis = 60_000))
        assertEquals("02:05.43", formatElapsedTimerDisplay(totalMillis = 125_432))
    }

    @Test
    fun `split history renders explicit split checkpoint labels with elapsed time`() {
        val history = buildSplitHistoryForTimeline(
            startedSensorNanos = 1_000_000_000L,
            splitMarks = listOf(
                SessionSplitMark(role = com.paul.sprintsync.features.race_session.SessionDeviceRole.SPLIT1, hostSensorNanos = 11_000_000_000L),
                SessionSplitMark(role = com.paul.sprintsync.features.race_session.SessionDeviceRole.SPLIT2, hostSensorNanos = 21_000_000_000L),
            ),
        )

        assertEquals(listOf("Split 1: 10.00", "Split 2: 20.00"), history)
    }

    @Test
    fun `details button is enabled only when stop exists`() {
        assertFalse(shouldEnableRunDetailsButton(stoppedSensorNanos = null))
        assertTrue(shouldEnableRunDetailsButton(stoppedSensorNanos = 123L))
    }

    @Test
    fun `run details distance validation requires increasing positive distances`() {
        val roles = listOf(SessionDeviceRole.SPLIT1, SessionDeviceRole.SPLIT2, SessionDeviceRole.STOP)
        val missing = validateRunDetailsDistanceInputs(
            checkpointRoles = roles,
            distancesByRole = mapOf(
                SessionDeviceRole.SPLIT1 to 5.0,
                SessionDeviceRole.SPLIT2 to null,
                SessionDeviceRole.STOP to 20.0,
            ),
        )
        val nonIncreasing = validateRunDetailsDistanceInputs(
            checkpointRoles = roles,
            distancesByRole = mapOf(
                SessionDeviceRole.SPLIT1 to 5.0,
                SessionDeviceRole.SPLIT2 to 4.0,
                SessionDeviceRole.STOP to 20.0,
            ),
        )
        val valid = validateRunDetailsDistanceInputs(
            checkpointRoles = roles,
            distancesByRole = mapOf(
                SessionDeviceRole.SPLIT1 to 5.0,
                SessionDeviceRole.SPLIT2 to 10.0,
                SessionDeviceRole.STOP to 20.0,
            ),
        )

        assertTrue(missing?.contains("Split 2") == true)
        assertTrue(nonIncreasing?.contains("strictly increasing") == true)
        assertEquals(null, valid)
    }

    @Test
    fun `run details calculations use segment speed and delta-v over delta-t acceleration`() {
        val results = calculateRunDetailsResults(
            startedSensorNanos = 0L,
            splitMarks = listOf(
                SessionSplitMark(role = SessionDeviceRole.SPLIT1, hostSensorNanos = 1_000_000_000L),
                SessionSplitMark(role = SessionDeviceRole.SPLIT2, hostSensorNanos = 2_000_000_000L),
            ),
            stoppedSensorNanos = 3_000_000_000L,
            checkpointRoles = listOf(
                SessionDeviceRole.SPLIT1,
                SessionDeviceRole.SPLIT2,
                SessionDeviceRole.STOP,
            ),
            distancesByRole = mapOf(
                SessionDeviceRole.SPLIT1 to 5.0,
                SessionDeviceRole.SPLIT2 to 10.0,
                SessionDeviceRole.STOP to 20.0,
            ),
        )

        assertEquals(3, results.size)
        assertEquals(SessionDeviceRole.SPLIT1, results[0].role)
        assertEquals(1.0, results[0].totalTimeSec, 1e-6)
        assertEquals(1.0, results[0].splitTimeSec, 1e-6)
        assertEquals(18.0, results[0].avgSpeedKmh, 1e-6)
        assertEquals(5.0, results[0].accelerationMs2, 1e-6)

        assertEquals(SessionDeviceRole.SPLIT2, results[1].role)
        assertEquals(1.0, results[1].splitTimeSec, 1e-6)
        assertEquals(18.0, results[1].avgSpeedKmh, 1e-6)
        assertEquals(0.0, results[1].accelerationMs2, 1e-6)

        assertEquals(SessionDeviceRole.STOP, results[2].role)
        assertEquals(1.0, results[2].splitTimeSec, 1e-6)
        assertEquals(36.0, results[2].avgSpeedKmh, 1e-6)
        assertEquals(5.0, results[2].accelerationMs2, 1e-6)
    }

    @Test
    fun `normalizes athlete name for save`() {
        val (name, error) = normalizeAthleteNameForResult("  Alex Runner  ")
        assertEquals("Alex_Runner", name)
        assertEquals(null, error)
    }

    @Test
    fun `rejects empty athlete name for save`() {
        val (name, error) = normalizeAthleteNameForResult("   ")
        assertEquals(null, name)
        assertEquals("Athlete name is required.", error)
    }

    @Test
    fun `builds athlete date result name with dd MM yyyy`() {
        val result = buildAthleteDateResultName(
            athleteName = "Alex",
            now = Date(0L),
            locale = Locale.US,
        )
        assertEquals("Alex_01_01_1970", result)
    }

    @Test
    fun `applies live local camera facing update when local monitoring active`() {
        assertTrue(
            shouldApplyLiveLocalCameraFacingUpdate(
                isLocalMotionMonitoring = true,
                assignedDeviceId = "local-1",
                localDeviceId = "local-1",
            ),
        )
    }

    @Test
    fun `does not apply live local camera facing update when monitoring inactive`() {
        assertFalse(
            shouldApplyLiveLocalCameraFacingUpdate(
                isLocalMotionMonitoring = false,
                assignedDeviceId = "local-1",
                localDeviceId = "local-1",
            ),
        )
    }

    @Test
    fun `does not apply live local camera facing update for non local device`() {
        assertFalse(
            shouldApplyLiveLocalCameraFacingUpdate(
                isLocalMotionMonitoring = true,
                assignedDeviceId = "remote-1",
                localDeviceId = "local-1",
            ),
        )
    }

    @Test
    fun `display rows show READY for connected endpoints with no lap yet`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = emptyMap(),
        )

        assertEquals(1, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
        assertEquals("READY", rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows show formatted checkpoint time for connected endpoints with lap`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Split 1"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
        )

        assertEquals(1, rows.size)
        assertEquals("Split 1", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
    }

    @Test
    fun `display rows include mixed connected devices with lap and READY`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1", "ep-2"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7", "ep-2" to "CPH2399"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
        )

        assertEquals(2, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_730L), rows[0].lapTimeLabel)
        assertEquals("CPH2399", rows[1].deviceName)
        assertEquals("READY", rows[1].lapTimeLabel)
    }

    @Test
    fun `display rows only include currently connected endpoints`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-2"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7", "ep-2" to "CPH2399"),
            elapsedByEndpointId = mapOf(
                "ep-1" to 1_730_000_000L,
                "ep-2" to 1_770_000_000L,
            ),
        )

        assertEquals(1, rows.size)
        assertEquals("CPH2399", rows[0].deviceName)
        assertEquals(formatElapsedTimerDisplay(1_770L), rows[0].lapTimeLabel)
    }

    @Test
    fun `derive saveable duration only when run completed with positive duration`() {
        assertEquals(500L, deriveSaveableRunDurationNanos(startedSensorNanos = 100L, stoppedSensorNanos = 600L))
        assertEquals(null, deriveSaveableRunDurationNanos(startedSensorNanos = null, stoppedSensorNanos = 600L))
        assertEquals(null, deriveSaveableRunDurationNanos(startedSensorNanos = 100L, stoppedSensorNanos = null))
        assertEquals(null, deriveSaveableRunDurationNanos(startedSensorNanos = 100L, stoppedSensorNanos = 100L))
    }

    @Test
    fun `normalize save name trims value and rejects blank`() {
        val normalized = normalizeSavedRunName("  Alice  ")
        val blank = normalizeSavedRunName("   ")

        assertEquals("Alice", normalized.first)
        assertEquals(null, normalized.second)
        assertEquals(null, blank.first)
        assertEquals("Name is required.", blank.second)
    }

    @Test
    fun `sort saved results orders by lowest duration then save time`() {
        val sorted = sortSavedRunResults(
            listOf(
                SavedRunResult(id = "3", name = "C", durationNanos = 2_000L, savedAtMillis = 30L),
                SavedRunResult(id = "1", name = "A", durationNanos = 1_000L, savedAtMillis = 20L),
                SavedRunResult(id = "2", name = "B", durationNanos = 1_000L, savedAtMillis = 10L),
            ),
        )

        assertEquals(listOf("2", "1", "3"), sorted.map { it.id })
    }
}
