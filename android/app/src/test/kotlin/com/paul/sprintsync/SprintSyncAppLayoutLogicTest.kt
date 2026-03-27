package com.paul.sprintsync

import com.paul.sprintsync.features.race_session.SessionOperatingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test

class SprintSyncAppLayoutLogicTest {
    @Test
    fun `setup permission warning only shows when permissions missing and denied list is not empty`() {
        assertTrue(
            shouldShowSetupPermissionWarning(
                permissionGranted = false,
                deniedPermissions = listOf("android.permission.CAMERA"),
            ),
        )

        assertFalse(
            shouldShowSetupPermissionWarning(
                permissionGranted = true,
                deniedPermissions = listOf("android.permission.CAMERA"),
            ),
        )

        assertFalse(
            shouldShowSetupPermissionWarning(
                permissionGranted = false,
                deniedPermissions = emptyList(),
            ),
        )
    }

    @Test
    fun `monitoring reset action only shows for host after run has finished`() {
        assertTrue(
            shouldShowMonitoringResetAction(
                isHost = true,
                startedSensorNanos = 10L,
                stoppedSensorNanos = 20L,
            ),
        )

        assertFalse(
            shouldShowMonitoringResetAction(
                isHost = false,
                startedSensorNanos = 10L,
                stoppedSensorNanos = 20L,
            ),
        )

        assertFalse(
            shouldShowMonitoringResetAction(
                isHost = true,
                startedSensorNanos = 10L,
                stoppedSensorNanos = null,
            ),
        )
    }

    @Test
    fun `display relay controls only show in single device mode`() {
        assertTrue(shouldShowDisplayRelayControls(SessionOperatingMode.SINGLE_DEVICE))
        assertFalse(shouldShowDisplayRelayControls(SessionOperatingMode.NETWORK_RACE))
        assertFalse(shouldShowDisplayRelayControls(SessionOperatingMode.DISPLAY_HOST))
    }

    @Test
    fun `display layout uses expected size tiers by row count`() {
        val one = displayLayoutSpecForCount(1)
        val two = displayLayoutSpecForCount(2)
        val three = displayLayoutSpecForCount(3)
        val many = displayLayoutSpecForCount(8)

        assertTrue(one.timeFont.value > two.timeFont.value)
        assertTrue(two.timeFont.value > three.timeFont.value)
        assertTrue(three.timeFont.value > many.timeFont.value)
        assertTrue(one.rowHeight > two.rowHeight)
        assertTrue(two.rowHeight > three.rowHeight)
        assertTrue(three.rowHeight > many.rowHeight)
    }

    @Test
    fun `display time font clamp respects row height budget`() {
        val density = Density(1f)
        val clamped = clampDisplayTimeFont(base = 128.sp, rowHeight = 120.dp, density = density)
        assertTrue(clamped.value <= 69.6f)
    }

    @Test
    fun `display label font clamp never drops below readable minimum`() {
        val density = Density(1f)
        val clamped = clampDisplayLabelFont(base = 26.sp, rowHeight = 40.dp, density = density)
        assertTrue(clamped.value >= 12f)
    }
}
