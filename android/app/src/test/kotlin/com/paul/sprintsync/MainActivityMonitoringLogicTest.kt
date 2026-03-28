package com.paul.sprintsync

import com.paul.sprintsync.features.race_session.SessionOperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityMonitoringLogicTest {
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
    fun `display rows show formatted lap for connected endpoints with lap`() {
        val rows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = linkedSetOf("ep-1"),
            deviceNamesByEndpointId = mapOf("ep-1" to "Pixel 7"),
            elapsedByEndpointId = mapOf("ep-1" to 1_730_000_000L),
        )

        assertEquals(1, rows.size)
        assertEquals("Pixel 7", rows[0].deviceName)
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
}
