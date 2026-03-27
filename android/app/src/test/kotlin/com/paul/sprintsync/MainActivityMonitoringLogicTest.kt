package com.paul.sprintsync

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
}
