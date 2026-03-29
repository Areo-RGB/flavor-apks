package com.paul.sprintsync

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionOperatingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `network race setup exposes explicit strategy buttons`() {
        val labels = networkRaceStrategyButtonLabels()
        assertTrue(labels.contains("Host (POINT_TO_POINT)"))
        assertTrue(labels.contains("Host (P2P_STAR)"))
        assertTrue(labels.contains("Join (POINT_TO_POINT)"))
        assertTrue(labels.contains("Join (P2P_STAR)"))
    }

    @Test
    fun `monitoring reset action shows for host once a run has started`() {
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

        assertTrue(
            shouldShowMonitoringResetAction(
                isHost = true,
                startedSensorNanos = 10L,
                stoppedSensorNanos = null,
            ),
        )

        assertFalse(
            shouldShowMonitoringResetAction(
                isHost = true,
                startedSensorNanos = null,
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
    fun `single-device mode hides role and monitoring toggles`() {
        assertFalse(shouldShowMonitoringRoleAndToggles(SessionOperatingMode.SINGLE_DEVICE))
        assertTrue(shouldShowMonitoringRoleAndToggles(SessionOperatingMode.NETWORK_RACE))
        assertTrue(shouldShowMonitoringRoleAndToggles(SessionOperatingMode.DISPLAY_HOST))
    }

    @Test
    fun `single-device mode shows local camera facing toggle`() {
        assertTrue(shouldShowSingleDeviceCameraFacingToggle(SessionOperatingMode.SINGLE_DEVICE))
        assertFalse(shouldShowSingleDeviceCameraFacingToggle(SessionOperatingMode.NETWORK_RACE))
        assertFalse(shouldShowSingleDeviceCameraFacingToggle(SessionOperatingMode.DISPLAY_HOST))
    }

    @Test
    fun `single-device mode can hide preview and shows preview switch`() {
        assertTrue(shouldShowMonitoringPreview(SessionOperatingMode.SINGLE_DEVICE, effectiveShowPreview = true))
        assertFalse(shouldShowMonitoringPreview(SessionOperatingMode.SINGLE_DEVICE, effectiveShowPreview = false))
        assertTrue(shouldShowMonitoringPreviewToggle(SessionOperatingMode.SINGLE_DEVICE))
        assertTrue(shouldShowMonitoringPreview(SessionOperatingMode.NETWORK_RACE, effectiveShowPreview = true))
        assertFalse(shouldShowMonitoringPreview(SessionOperatingMode.NETWORK_RACE, effectiveShowPreview = false))
        assertTrue(shouldShowMonitoringPreviewToggle(SessionOperatingMode.NETWORK_RACE))
        assertFalse(shouldShowMonitoringPreviewToggle(SessionOperatingMode.DISPLAY_HOST))
    }

    @Test
    fun `inline monitoring reset button shows for compact and wide monitoring modes`() {
        assertTrue(shouldShowInlineMonitoringResetButton(SessionOperatingMode.NETWORK_RACE))
        assertTrue(shouldShowInlineMonitoringResetButton(SessionOperatingMode.DISPLAY_HOST))
        assertFalse(shouldShowInlineMonitoringResetButton(SessionOperatingMode.SINGLE_DEVICE))
    }

    @Test
    fun `device role options include split and display roles`() {
        val options = deviceRoleOptions()
        assertTrue(options.contains(SessionDeviceRole.SPLIT))
        assertTrue(options.contains(SessionDeviceRole.DISPLAY))
    }

    @Test
    fun `passive display client view only shows for monitoring network race client display role`() {
        assertTrue(
            shouldShowPassiveDisplayClientView(
                stage = com.paul.sprintsync.features.race_session.SessionStage.MONITORING,
                operatingMode = SessionOperatingMode.NETWORK_RACE,
                networkRole = com.paul.sprintsync.features.race_session.SessionNetworkRole.CLIENT,
                localRole = SessionDeviceRole.DISPLAY,
            ),
        )
        assertFalse(
            shouldShowPassiveDisplayClientView(
                stage = com.paul.sprintsync.features.race_session.SessionStage.LOBBY,
                operatingMode = SessionOperatingMode.NETWORK_RACE,
                networkRole = com.paul.sprintsync.features.race_session.SessionNetworkRole.CLIENT,
                localRole = SessionDeviceRole.DISPLAY,
            ),
        )
    }

    @Test
    fun `passive display completed results only show when stop timestamp exists`() {
        assertFalse(shouldShowPassiveDisplayCompletedResults(stoppedSensorNanos = null))
        assertTrue(shouldShowPassiveDisplayCompletedResults(stoppedSensorNanos = 42L))
    }

    @Test
    fun `single-device mode hides run detail metrics and fps requires debug`() {
        assertFalse(shouldShowRunDetailMetrics(SessionOperatingMode.SINGLE_DEVICE))
        assertTrue(shouldShowRunDetailMetrics(SessionOperatingMode.NETWORK_RACE))
        assertTrue(shouldShowCameraFpsInfo(showDebugInfo = true))
        assertFalse(shouldShowCameraFpsInfo(showDebugInfo = false))
    }

    @Test
    fun `monitoring connection panel only shows when debug is on`() {
        assertTrue(shouldShowMonitoringConnectionDebugInfo(showDebugInfo = true))
        assertFalse(shouldShowMonitoringConnectionDebugInfo(showDebugInfo = false))
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
    fun `display host horizontal layout caps visible card slots`() {
        assertTrue(displayHorizontalVisibleCardSlots(1) == 1)
        assertTrue(displayHorizontalVisibleCardSlots(2) == 2)
        assertTrue(displayHorizontalVisibleCardSlots(3) == 3)
        assertTrue(displayHorizontalVisibleCardSlots(8) == 3)
    }

    @Test
    fun `display time font clamp respects row height budget`() {
        val density = Density(1f)
        val clamped = clampDisplayTimeFont(
            base = 128.sp,
            rowHeight = 120.dp,
            rowContentWidth = 800.dp,
            density = density,
        )
        assertTrue(clamped.value <= 88.8f)
    }

    @Test
    fun `display time font clamp also respects width budget`() {
        val density = Density(1f)
        val clamped = clampDisplayTimeFont(
            base = 140.sp,
            rowHeight = 320.dp,
            rowContentWidth = 330.dp,
            density = density,
        )
        assertTrue(clamped.value <= 67f)
    }

    @Test
    fun `display label font clamp never drops below readable minimum`() {
        val density = Density(1f)
        val clamped = clampDisplayLabelFont(base = 26.sp, rowHeight = 40.dp, density = density)
        assertTrue(clamped.value >= 12f)
    }
}
