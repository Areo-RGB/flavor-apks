package com.paul.sprintsync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.core.models.SavedRunResult
import com.paul.sprintsync.features.race_session.SessionDevice
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionAnchorState
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionOperatingMode
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.features.race_session.sessionCameraFacingLabel
import com.paul.sprintsync.features.race_session.sessionDeviceRoleLabel
import com.paul.sprintsync.sensor_native.SensorNativePreviewViewFactory
import com.paul.sprintsync.ui.components.*
import com.paul.sprintsync.ui.theme.*
import com.paul.sprintsync.ui.theme.InterExtraBoldTabularTypography
import kotlin.math.roundToInt

data class SprintSyncUiState(
    val permissionGranted: Boolean = false,
    val setupBusy: Boolean = false,
    val deniedPermissions: List<String> = emptyList(),
    val stage: SessionStage = SessionStage.SETUP,
    val networkRole: SessionNetworkRole = SessionNetworkRole.NONE,
    val networkSummary: String = "Ready",
    val monitoringSummary: String = "Idle",
    val clockSummary: String = "Unlocked",
    val sessionSummary: String = "setup",
    val startedSensorNanos: Long? = null,
    val stoppedSensorNanos: Long? = null,
    val discoveredEndpoints: Map<String, String> = emptyMap(),
    val connectedEndpoints: Set<String> = emptySet(),
    val devices: List<SessionDevice> = emptyList(),
    val canStartMonitoring: Boolean = false,
    val isHost: Boolean = false,
    val localRole: SessionDeviceRole = SessionDeviceRole.UNASSIGNED,
    val userMonitoringEnabled: Boolean = true,
    val monitoringConnectionTypeLabel: String = "-",
    val monitoringSyncModeLabel: String = "-",
    val monitoringLatencyMs: Int? = null,
    val hasConnectedPeers: Boolean = false,
    val clockLockWarningText: String? = null,
    val runStatusLabel: String = "Ready",
    val runMarksCount: Int = 0,
    val elapsedDisplay: String = "00.00",
    val threshold: Double = 0.006,
    val roiCenterX: Double = 0.5,
    val roiWidth: Double = 0.03,
    val cooldownMs: Int = 900,
    val processEveryNFrames: Int = 2,
    val observedFps: Double? = null,
    val cameraFpsModeLabel: String = "INIT",
    val targetFpsUpper: Int? = null,
    val rawScore: Double? = null,
    val baseline: Double? = null,
    val effectiveScore: Double? = null,
    val frameSensorNanos: Long? = null,
    val streamFrameCount: Long = 0,
    val processedFrameCount: Long = 0,
    val triggerHistory: List<String> = emptyList(),
    val lastConnectionEvent: String? = null,
    val lastSensorEvent: String? = null,
    val recentEvents: List<String> = emptyList(),
    val operatingMode: SessionOperatingMode = SessionOperatingMode.NETWORK_RACE,
    val displayLapRows: List<DisplayLapRow> = emptyList(),
    val displayConnectedHostName: String? = null,
    val displayDiscoveryActive: Boolean = false,
    val isControllerOnlyHost: Boolean = false,
    val connectedDeviceMonitoringCards: List<ConnectedDeviceMonitoringCardUiState> = emptyList(),
    val anchorDeviceName: String? = null,
    val anchorState: SessionAnchorState = SessionAnchorState.READY,
    val clockLockReasonLabel: String = "-",
    val saveableRunDurationNanos: Long? = null,
    val savedRunResults: List<SavedRunResult> = emptyList(),
    val showSaveResultDialog: Boolean = false,
    val showSavedResultsDialog: Boolean = false,
    val saveResultNameDraft: String = "",
    val saveResultNameError: String? = null,
)

data class DisplayLapRow(
    val deviceName: String,
    val lapTimeLabel: String,
)

data class ConnectedDeviceMonitoringCardUiState(
    val stableDeviceId: String?,
    val endpointId: String,
    val deviceName: String,
    val role: SessionDeviceRole,
    val latencyMs: Int?,
    val clockSynced: Boolean,
    val sensitivity: Int,
    val connected: Boolean,
)

@Composable
fun SprintSyncApp(
    uiState: SprintSyncUiState,
    previewViewFactory: SensorNativePreviewViewFactory,
    onRequestPermissions: () -> Unit,
    onStartHosting: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStartSingleDevice: () -> Unit,
    onStartDisplayHost: () -> Unit,
    onReconnectClient: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStartDisplayDiscovery: () -> Unit,
    onConnectDisplayHost: (String) -> Unit,
    onSetMonitoringEnabled: (Boolean) -> Unit,
    onStopMonitoring: () -> Unit,
    onResetRun: () -> Unit,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
    onUpdateThreshold: (Double) -> Unit,
    onUpdateRemoteSensitivity: (String, Int) -> Unit,
    onUpdateRoiCenter: (Double) -> Unit,
    onUpdateRoiWidth: (Double) -> Unit,
    onUpdateCooldown: (Int) -> Unit,
    onStopHosting: () -> Unit,
    onOpenSaveResultDialog: () -> Unit,
    onDismissSaveResultDialog: () -> Unit,
    onSaveResultNameChanged: (String) -> Unit,
    onConfirmSaveResult: () -> Unit,
    onOpenSavedResultsDialog: () -> Unit,
    onDismissSavedResultsDialog: () -> Unit,
    onDeleteSavedResult: (String) -> Unit,
) {
    var showPreview by rememberSaveable { mutableStateOf(true) }
    var showDebugInfo by rememberSaveable { mutableStateOf(false) }
    val effectiveShowPreview = showPreview
    val localDevice = uiState.devices.firstOrNull { it.isLocal }
    val isDisplayHostMode =
        uiState.stage == SessionStage.MONITORING &&
            uiState.operatingMode == SessionOperatingMode.DISPLAY_HOST

    Scaffold(
        topBar = {},
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (isDisplayHostMode) 6.dp else 16.dp,
                        vertical = if (isDisplayHostMode) 6.dp else 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isDisplayHostMode) 4.dp else 12.dp),
            ) {
            item {
                if (!isDisplayHostMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                uiState.operatingMode == SessionOperatingMode.DISPLAY_HOST -> "Display Monitor"
                                uiState.operatingMode == SessionOperatingMode.SINGLE_DEVICE -> "Single Device"
                                uiState.stage == SessionStage.SETUP -> "Setup Session"
                                uiState.stage == SessionStage.LOBBY -> "Race Lobby"
                                else -> "Monitoring"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (uiState.stage == SessionStage.LOBBY && uiState.isHost) {
                                TextButton(onClick = onStopHosting) {
                                    Text("Stop Hosting")
                                }
                            }
                            if (
                                uiState.stage == SessionStage.MONITORING &&
                                (uiState.isHost || uiState.operatingMode != SessionOperatingMode.NETWORK_RACE)
                            ) {
                                SecondaryButton(text = "Stop", onClick = onStopMonitoring)
                                if (
                                    shouldShowMonitoringTopResetRunButton(
                                        stage = uiState.stage,
                                        isHost = uiState.isHost,
                                        operatingMode = uiState.operatingMode,
                                        deviceProfile = BuildConfig.DEVICE_PROFILE,
                                    )
                                ) {
                                    TextButton(onClick = onResetRun) {
                                        Text("Reset Run")
                                    }
                                }
                                if (
                                    shouldShowMonitoringTopSavedResultsButton(
                                        stage = uiState.stage,
                                        isHost = uiState.isHost,
                                        operatingMode = uiState.operatingMode,
                                        deviceProfile = BuildConfig.DEVICE_PROFILE,
                                    )
                                ) {
                                    TextButton(onClick = onOpenSavedResultsDialog) {
                                        Text("Show Results")
                                    }
                                }
                            }
                            TextButton(onClick = { showDebugInfo = !showDebugInfo }) {
                                Text(if (showDebugInfo) "Debug On" else "Debug Off")
                            }
                        }
                    }
                }
            }

            if (showDebugInfo && uiState.stage != SessionStage.MONITORING && !isDisplayHostMode) {
                item {
                    StatusCard(uiState)
                }
            }

            when (uiState.stage) {
                SessionStage.SETUP -> {
                    if (shouldShowSetupPermissionWarning(uiState.permissionGranted, uiState.deniedPermissions)) {
                        item {
                            PermissionWarningCard(uiState.deniedPermissions)
                        }
                    }
                    item {
                        SetupActionsCard(
                            permissionGranted = uiState.permissionGranted,
                            setupBusy = uiState.setupBusy,
                            onRequestPermissions = onRequestPermissions,
                            onStartHosting = onStartHosting,
                            onStartDiscovery = onStartDiscovery,
                            onStartSingleDevice = onStartSingleDevice,
                            onStartDisplayHost = onStartDisplayHost,
                            onReconnectClient = onReconnectClient,
                            showReconnectClientAction = BuildConfig.AUTO_START_ROLE == "client",
                            hideSingleDeviceAction = uiState.isControllerOnlyHost,
                        )
                    }
                    item {
                        ConnectedDevicesListCard(
                            devices = uiState.devices,
                            showDebugInfo = showDebugInfo,
                        )
                    }
                }

                SessionStage.LOBBY -> {
                    item {
                        LobbyActionsCard(
                            isHost = uiState.isHost,
                            canStartMonitoring = uiState.canStartMonitoring,
                            timelineFinished = uiState.startedSensorNanos != null && uiState.stoppedSensorNanos != null,
                            onStartMonitoring = onStartMonitoring,
                            onResetRun = onResetRun,
                        )
                    }
                    item {
                        LobbyPeersCard(
                            devices = uiState.devices,
                            hasConnectedPeers = uiState.hasConnectedPeers,
                            connectionTypeLabel = uiState.monitoringConnectionTypeLabel,
                            syncModeLabel = uiState.monitoringSyncModeLabel,
                            latencyMs = uiState.monitoringLatencyMs,
                        )
                    }
                    item {
                        val hideLocalDeviceInAssignments = uiState.isControllerOnlyHost ||
                            (BuildConfig.HOST_CONTROLLER_ONLY && uiState.networkRole == SessionNetworkRole.HOST)
                        DeviceAssignmentsCard(
                            devices = if (hideLocalDeviceInAssignments) {
                                uiState.devices.filterNot { it.isLocal || it.id == localDevice?.id }
                            } else {
                                uiState.devices
                            },
                            editable = uiState.networkRole == SessionNetworkRole.HOST,
                            showDebugInfo = showDebugInfo,
                            onAssignRole = onAssignRole,
                            onAssignCameraFacing = onAssignCameraFacing,
                        )
                    }
                }

                SessionStage.MONITORING -> {
                    if (uiState.operatingMode == SessionOperatingMode.DISPLAY_HOST) {
                        item {
                            DisplayResultsCard(
                                rows = uiState.displayLapRows,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillParentMaxHeight(),
                            )
                        }
                    } else {
                        item {
                            RunMetricsCard(
                                uiState = uiState,
                                isHost = uiState.isHost,
                                showDebugInfo = showDebugInfo,
                                onResetRun = onResetRun,
                                onOpenSaveResultDialog = onOpenSaveResultDialog,
                            )
                        }
                        if (uiState.clockLockWarningText != null) {
                            item {
                                ClockWarningCard(uiState.clockLockWarningText)
                            }
                        }
                        item {
                            MonitoringSummaryCard(
                                isHost = uiState.isHost,
                                controllerOnlyHost = uiState.isControllerOnlyHost,
                                localRole = uiState.localRole,
                                localCameraFacing = localDevice?.cameraFacing ?: SessionCameraFacing.REAR,
                                showDebugInfo = showDebugInfo,
                                connectionTypeLabel = uiState.monitoringConnectionTypeLabel,
                                syncModeLabel = uiState.monitoringSyncModeLabel,
                                latencyMs = uiState.monitoringLatencyMs,
                                userMonitoringEnabled = uiState.userMonitoringEnabled,
                                onSetMonitoringEnabled = onSetMonitoringEnabled,
                                onAssignLocalCameraFacing = { facing ->
                                    localDevice?.let { device ->
                                        onAssignCameraFacing(device.id, facing)
                                    }
                                },
                                effectiveShowPreview = effectiveShowPreview,
                                onShowPreviewChanged = { showPreview = it },
                                sensitivity = thresholdToSensitivity(uiState.threshold),
                                onUpdateSensitivity = { nextSensitivity ->
                                    onUpdateThreshold(sensitivityToThreshold(nextSensitivity.toInt()))
                                },
                                previewViewFactory = previewViewFactory,
                                roiCenterX = uiState.roiCenterX,
                                operatingMode = uiState.operatingMode,
                                discoveredDisplayHosts = uiState.discoveredEndpoints,
                                displayConnectedHostName = uiState.displayConnectedHostName,
                                displayDiscoveryActive = uiState.displayDiscoveryActive,
                                anchorDeviceName = uiState.anchorDeviceName,
                                anchorState = uiState.anchorState,
                                clockLockReasonLabel = uiState.clockLockReasonLabel,
                                onStartDisplayDiscovery = onStartDisplayDiscovery,
                                onConnectDisplayHost = onConnectDisplayHost,
                                onResetRun = onResetRun,
                            )
                        }
                        if (
                            shouldShowHostConnectedDeviceCards(
                                stage = uiState.stage,
                                operatingMode = uiState.operatingMode,
                                isHost = uiState.isHost,
                                deviceProfile = BuildConfig.DEVICE_PROFILE,
                            ) && uiState.connectedDeviceMonitoringCards.isNotEmpty()
                        ) {
                            item {
                                HostConnectedDeviceCards(
                                    cards = uiState.connectedDeviceMonitoringCards,
                                    onUpdateRemoteSensitivity = onUpdateRemoteSensitivity,
                                )
                            }
                        }
                        if (showDebugInfo && !uiState.isControllerOnlyHost) {
                            item {
                                AdvancedDetectionCard(
                                    uiState = uiState,
                                    showDebugInfo = showDebugInfo,
                                    onUpdateThreshold = onUpdateThreshold,
                                    onUpdateRoiCenter = onUpdateRoiCenter,
                                    onUpdateRoiWidth = onUpdateRoiWidth,
                                    onUpdateCooldown = onUpdateCooldown,
                                )
                            }
                        }
                    }
                }
            }

            if (showDebugInfo && uiState.connectedEndpoints.isNotEmpty()) {
                item {
                    ConnectedCard(uiState.connectedEndpoints)
                }
            }

            if (showDebugInfo && uiState.recentEvents.isNotEmpty()) {
                item {
                    EventsCard(uiState.recentEvents)
                }
            }
            }

            if (uiState.showSaveResultDialog) {
                SaveResultDialog(
                    value = uiState.saveResultNameDraft,
                    error = uiState.saveResultNameError,
                    onValueChange = onSaveResultNameChanged,
                    onDismiss = onDismissSaveResultDialog,
                    onConfirm = onConfirmSaveResult,
                )
            }

            if (uiState.showSavedResultsDialog) {
                SavedResultsDialog(
                    rows = uiState.savedRunResults,
                    onDismiss = onDismissSavedResultsDialog,
                    onDelete = onDeleteSavedResult,
                )
            }

            if (isDisplayHostMode) {
                OutlinedButton(
                    onClick = onStopMonitoring,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 20.dp, end = 20.dp),
                    border = BorderStroke(2.5.dp, Color.Black),
                    shape = RoundedCornerShape(50.dp),
                ) {
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        ),
                        color = Color.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(uiState: SprintSyncUiState) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader("Session Status")
            MetricDisplay(label = "Stage", value = uiState.sessionSummary)
            MetricDisplay(label = "Network", value = uiState.networkSummary)
            if (!uiState.isControllerOnlyHost) {
                MetricDisplay(label = "Motion", value = uiState.monitoringSummary)
            }
            MetricDisplay(label = "Clock", value = uiState.clockSummary)
            uiState.lastConnectionEvent?.let { Text("Last Connection: $it") }
            uiState.lastSensorEvent?.let { Text("Last Sensor: $it") }
            if (!uiState.permissionGranted && uiState.deniedPermissions.isNotEmpty()) {
                Text(
                    "Missing permissions: ${uiState.deniedPermissions.joinToString()}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PermissionWarningCard(deniedPermissions: List<String>) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader("Permissions Needed")
            Text(
                text = "Grant permissions to host or join devices.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = deniedPermissions.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SetupActionsCard(
    permissionGranted: Boolean,
    setupBusy: Boolean,
    onRequestPermissions: () -> Unit,
    onStartHosting: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStartSingleDevice: () -> Unit,
    onStartDisplayHost: () -> Unit,
    onReconnectClient: () -> Unit,
    showReconnectClientAction: Boolean = false,
    hideSingleDeviceAction: Boolean = false,
) {
    val setupActionsEnabled = !setupBusy

    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Network Connection")
            if (!permissionGranted) {
                PrimaryButton(
                    text = "Grant Permissions",
                    onClick = onRequestPermissions,
                    enabled = setupActionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryButton(
                text = "Host",
                onClick = onStartHosting,
                enabled = setupActionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                text = "Join",
                onClick = onStartDiscovery,
                enabled = setupActionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showReconnectClientAction) {
                PrimaryButton(
                    text = "Reconnect to Pad",
                    onClick = onReconnectClient,
                    enabled = setupActionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!hideSingleDeviceAction) {
                PrimaryButton(
                    text = "Single Device",
                    onClick = onStartSingleDevice,
                    enabled = setupActionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryButton(
                text = "Display",
                onClick = onStartDisplayHost,
                enabled = setupActionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!permissionGranted) {
                Text(
                    text = "Camera and network permissions are required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun LobbyActionsCard(
    isHost: Boolean,
    canStartMonitoring: Boolean,
    timelineFinished: Boolean,
    onStartMonitoring: () -> Unit,
    onResetRun: () -> Unit,
) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Session Actions")
            PrimaryButton(
                text = "Start Monitoring",
                onClick = onStartMonitoring,
                enabled = isHost && canStartMonitoring,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isHost && timelineFinished) {
                OutlinedButton(onClick = onResetRun) {
                    Text("Reset Run")
                }
            }
        }
    }
}

@Composable
private fun LobbyPeersCard(
    devices: List<SessionDevice>,
    hasConnectedPeers: Boolean,
    connectionTypeLabel: String,
    syncModeLabel: String,
    latencyMs: Int?,
) {
    val latencyLabel = when (syncModeLabel) {
        "NTP" -> if (latencyMs == null) "-" else "$latencyMs ms"
        "GPS" -> "GPS"
        else -> "-"
    }
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader("Peers")
            val peerNames = devices
                .filterNot { it.isLocal }
                .map { it.name }
            if (peerNames.isEmpty()) {
                Text("No peers connected yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                peerNames.forEach { name ->
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = if (hasConnectedPeers) "Connection: $connectionTypeLabel" else "Connection: Waiting for peers",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Text(
                text = "Sync: $syncModeLabel · Latency: $latencyLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun DeviceAssignmentsCard(
    devices: List<SessionDevice>,
    editable: Boolean,
    showDebugInfo: Boolean,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Device Roles")
            if (editable) {
                Text(
                    "Assign roles to connected devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            devices.forEach { device ->
                DeviceAssignmentRow(
                    device = device,
                    editable = editable,
                    showDebugInfo = showDebugInfo,
                    onAssignRole = onAssignRole,
                    onAssignCameraFacing = onAssignCameraFacing,
                )
            }
        }
    }
}

@Composable
private fun DeviceAssignmentRow(
    device: SessionDevice,
    editable: Boolean,
    showDebugInfo: Boolean,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
) {
    var roleMenuExpanded by remember(device.id) { mutableStateOf(false) }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (device.isLocal) "${device.name} (Local)" else device.name,
                fontWeight = FontWeight.Medium,
            )
            if (showDebugInfo) {
                Text(device.id, style = MaterialTheme.typography.bodySmall)
            }
            if (editable) {
                val roleOptions = buildList {
                    add(SessionDeviceRole.UNASSIGNED)
                    add(SessionDeviceRole.START)
                    add(SessionDeviceRole.SPLIT)
                    add(SessionDeviceRole.STOP)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            onClick = { onAssignCameraFacing(device.id, SessionCameraFacing.REAR) },
                            selected = device.cameraFacing == SessionCameraFacing.REAR,
                            label = { Text("Rear") },
                        )
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            onClick = { onAssignCameraFacing(device.id, SessionCameraFacing.FRONT) },
                            selected = device.cameraFacing == SessionCameraFacing.FRONT,
                            label = { Text("Front") },
                        )
                    }

                    Box {
                        AssistChip(
                            onClick = { roleMenuExpanded = true },
                            label = { Text(sessionDeviceRoleLabel(device.role)) },
                        )
                        DropdownMenu(
                            expanded = roleMenuExpanded,
                            onDismissRequest = { roleMenuExpanded = false },
                        ) {
                            roleOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(sessionDeviceRoleLabel(option)) },
                                    onClick = {
                                        onAssignRole(device.id, option)
                                        roleMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                MetricDisplay(label = "Role", value = sessionDeviceRoleLabel(device.role))
                MetricDisplay(label = "Camera", value = sessionCameraFacingLabel(device.cameraFacing))
            }
        }
    }
}

@Composable
private fun MonitoringSummaryCard(
    isHost: Boolean,
    controllerOnlyHost: Boolean,
    localRole: SessionDeviceRole,
    localCameraFacing: SessionCameraFacing,
    showDebugInfo: Boolean,
    connectionTypeLabel: String,
    syncModeLabel: String,
    latencyMs: Int?,
    userMonitoringEnabled: Boolean,
    onSetMonitoringEnabled: (Boolean) -> Unit,
    onAssignLocalCameraFacing: (SessionCameraFacing) -> Unit,
    effectiveShowPreview: Boolean,
    onShowPreviewChanged: (Boolean) -> Unit,
    sensitivity: Float,
    onUpdateSensitivity: (Float) -> Unit,
    previewViewFactory: SensorNativePreviewViewFactory,
    roiCenterX: Double,
    operatingMode: SessionOperatingMode,
    discoveredDisplayHosts: Map<String, String>,
    displayConnectedHostName: String?,
    displayDiscoveryActive: Boolean,
    anchorDeviceName: String?,
    anchorState: SessionAnchorState,
    clockLockReasonLabel: String,
    onStartDisplayDiscovery: () -> Unit,
    onConnectDisplayHost: (String) -> Unit,
    onResetRun: () -> Unit,
) {
    val latencyLabel = when (syncModeLabel) {
        "NTP" -> if (latencyMs == null) "-" else "$latencyMs ms"
        "GPS" -> "GPS"
        else -> "-"
    }

    SprintSyncCard {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (controllerOnlyHost && operatingMode != SessionOperatingMode.SINGLE_DEVICE) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Controller mode active",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "This host handles connections and race orchestration only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    if (shouldShowMonitoringConnectionDebugInfo(showDebugInfo)) {
                        Text(
                            "Connection: $connectionTypeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text(
                            "Sync: $syncModeLabel · Latency: $latencyLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text(
                            "Anchor: ${anchorDeviceName ?: "-"} · State: ${anchorState.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text(
                            "Clock Lock: $clockLockReasonLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                }
            } else if (operatingMode == SessionOperatingMode.SINGLE_DEVICE) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (shouldShowMonitoringConnectionDebugInfo(showDebugInfo)) {
                        Text(
                            "Connection: $connectionTypeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text(
                            "Sync: $syncModeLabel · Latency: $latencyLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text(
                            "Anchor: ${anchorDeviceName ?: "-"} · State: ${anchorState.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text(
                            "Clock Lock: $clockLockReasonLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    if (shouldShowMonitoringSensitivityControl(controllerOnlyHost, operatingMode)) {
                        Text(
                            "Sensitivity ${String.format("%.0f", sensitivity)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Slider(
                            value = sensitivity,
                            onValueChange = onUpdateSensitivity,
                            valueRange = SENSITIVITY_MIN.toFloat()..SENSITIVITY_MAX.toFloat(),
                            steps = SENSITIVITY_MAX - SENSITIVITY_MIN - 1,
                        )
                    }
                    if (shouldShowSingleDeviceCameraFacingToggle(operatingMode)) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                    onClick = { onAssignLocalCameraFacing(SessionCameraFacing.REAR) },
                                    selected = localCameraFacing == SessionCameraFacing.REAR,
                                    label = { Text("Rear") },
                                )
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                    onClick = { onAssignLocalCameraFacing(SessionCameraFacing.FRONT) },
                                    selected = localCameraFacing == SessionCameraFacing.FRONT,
                                    label = { Text("Front") },
                                )
                            }
                        }
                    }
                    if (shouldShowMonitoringPreview(operatingMode, effectiveShowPreview)) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            PreviewSurface(
                                previewViewFactory = previewViewFactory,
                                roiCenterX = roiCenterX,
                            )
                        }
                    }
                    if (shouldShowDisplayRelayControls(operatingMode)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PrimaryButton(
                                text = if (displayDiscoveryActive) "Display: Discovering" else "Display",
                                onClick = onStartDisplayDiscovery,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = onResetRun,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Reset")
                            }
                        }
                        if (displayConnectedHostName != null) {
                            Text(
                                text = "Connected to $displayConnectedHostName",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        val hosts = discoveredDisplayHosts.entries.toList()
                        if (hosts.isNotEmpty()) {
                            hosts.forEach { host ->
                                OutlinedButton(
                                    onClick = { onConnectDisplayHost(host.key) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Join ${host.value}")
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MonitoringPreviewInfoPanel(
                        isHost = isHost,
                        controllerOnlyHost = controllerOnlyHost,
                        localRole = localRole,
                        localCameraFacing = localCameraFacing,
                        showDebugInfo = showDebugInfo,
                        connectionTypeLabel = connectionTypeLabel,
                        syncModeLabel = syncModeLabel,
                        latencyLabel = latencyLabel,
                        userMonitoringEnabled = userMonitoringEnabled,
                        onSetMonitoringEnabled = onSetMonitoringEnabled,
                        onAssignLocalCameraFacing = onAssignLocalCameraFacing,
                        effectiveShowPreview = effectiveShowPreview,
                        onShowPreviewChanged = onShowPreviewChanged,
                        sensitivity = sensitivity,
                        onUpdateSensitivity = onUpdateSensitivity,
                        operatingMode = operatingMode,
                        discoveredDisplayHosts = discoveredDisplayHosts,
                        displayConnectedHostName = displayConnectedHostName,
                        displayDiscoveryActive = displayDiscoveryActive,
                        onStartDisplayDiscovery = onStartDisplayDiscovery,
                        onConnectDisplayHost = onConnectDisplayHost,
                    )
                    if (shouldShowMonitoringPreview(operatingMode, effectiveShowPreview)) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            PreviewSurface(
                                previewViewFactory = previewViewFactory,
                                roiCenterX = roiCenterX,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostConnectedDeviceCards(
    cards: List<ConnectedDeviceMonitoringCardUiState>,
    onUpdateRemoteSensitivity: (String, Int) -> Unit,
) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Connected Device Cards")
            cards.forEach { card ->
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(card.deviceName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Role: ${sessionDeviceRoleLabel(card.role)} · Latency: ${card.latencyMs?.let { "$it ms" } ?: "-"} · Sync: ${if (card.clockSynced) "✓" else "✗"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                        Text("Sensitivity ${card.sensitivity}", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = card.sensitivity.toFloat(),
                            onValueChange = { updated ->
                                card.stableDeviceId?.let { stableDeviceId ->
                                    onUpdateRemoteSensitivity(stableDeviceId, updated.roundToInt())
                                }
                            },
                            valueRange = SENSITIVITY_MIN.toFloat()..SENSITIVITY_MAX.toFloat(),
                            steps = SENSITIVITY_MAX - SENSITIVITY_MIN - 1,
                            enabled = card.connected && card.stableDeviceId != null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringPreviewInfoPanel(
    isHost: Boolean,
    controllerOnlyHost: Boolean,
    localRole: SessionDeviceRole,
    localCameraFacing: SessionCameraFacing,
    showDebugInfo: Boolean,
    connectionTypeLabel: String,
    syncModeLabel: String,
    latencyLabel: String,
    userMonitoringEnabled: Boolean,
    onSetMonitoringEnabled: (Boolean) -> Unit,
    onAssignLocalCameraFacing: (SessionCameraFacing) -> Unit,
    effectiveShowPreview: Boolean,
    onShowPreviewChanged: (Boolean) -> Unit,
    sensitivity: Float,
    onUpdateSensitivity: (Float) -> Unit,
    operatingMode: SessionOperatingMode,
    discoveredDisplayHosts: Map<String, String>,
    displayConnectedHostName: String?,
    displayDiscoveryActive: Boolean,
    onStartDisplayDiscovery: () -> Unit,
    onConnectDisplayHost: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (shouldShowMonitoringRoleAndToggles(operatingMode)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Role: ${sessionDeviceRoleLabel(localRole)}",
                    fontWeight = FontWeight.Bold,
                )
                if (!isHost) {
                    Text("Waiting for host...", color = Color.Gray, fontStyle = FontStyle.Italic)
                }
            }
        }
        if (shouldShowMonitoringConnectionDebugInfo(showDebugInfo)) {
            Text("Connection: $connectionTypeLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                "Sync: $syncModeLabel · Latency: $latencyLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
        if (shouldShowMonitoringSensitivityControl(controllerOnlyHost, operatingMode)) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Sensitivity ${String.format("%.0f", sensitivity)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = sensitivity,
                onValueChange = onUpdateSensitivity,
                valueRange = SENSITIVITY_MIN.toFloat()..SENSITIVITY_MAX.toFloat(),
                steps = SENSITIVITY_MAX - SENSITIVITY_MIN - 1,
            )
        }
        if (!controllerOnlyHost && shouldShowMonitoringPreviewToggle(operatingMode)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Preview", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = effectiveShowPreview,
                    enabled = true,
                    onCheckedChange = onShowPreviewChanged,
                )
            }
        }
        if (!controllerOnlyHost && shouldShowSingleDeviceCameraFacingToggle(operatingMode)) {
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    onClick = { onAssignLocalCameraFacing(SessionCameraFacing.REAR) },
                    selected = localCameraFacing == SessionCameraFacing.REAR,
                    label = { Text("Rear") },
                )
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    onClick = { onAssignLocalCameraFacing(SessionCameraFacing.FRONT) },
                    selected = localCameraFacing == SessionCameraFacing.FRONT,
                    label = { Text("Front") },
                )
            }
        }
        if (shouldShowDisplayRelayControls(operatingMode)) {
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                text = if (displayDiscoveryActive) "Display: Discovering" else "Display",
                onClick = onStartDisplayDiscovery,
                modifier = Modifier.fillMaxWidth(),
            )
            if (displayConnectedHostName != null) {
                Text(
                    text = "Connected to $displayConnectedHostName",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            val hosts = discoveredDisplayHosts.entries.toList()
            if (hosts.isNotEmpty()) {
                hosts.forEach { host ->
                    OutlinedButton(
                        onClick = { onConnectDisplayHost(host.key) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Join ${host.value}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockWarningCard(text: String) {
    SprintSyncCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Text("!", color = Color(0xFFD97706), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewSurface(previewViewFactory: SensorNativePreviewViewFactory, roiCenterX: Double) {
    Box(
        modifier = Modifier
            .width(180.dp)
            .height(120.dp)
            .clip(MaterialTheme.shapes.medium),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { context ->
                previewViewFactory.createPreviewView(context)
            },
            onRelease = { view ->
                previewViewFactory.detachPreviewView(view)
            },
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val normalized = roiCenterX.coerceIn(0.0, 1.0).toFloat()
            val x = size.width * normalized
            drawLine(
                color = Color(0xFF005A8D),
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}

@Composable
private fun AdvancedDetectionCard(
    uiState: SprintSyncUiState,
    showDebugInfo: Boolean,
    onUpdateThreshold: (Double) -> Unit,
    onUpdateRoiCenter: (Double) -> Unit,
    onUpdateRoiWidth: (Double) -> Unit,
    onUpdateCooldown: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader("Advanced Detection")
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (expanded) {
                MetricDisplay(label = "Threshold", value = String.format("%.3f", uiState.threshold))
                Slider(
                    value = uiState.threshold.toFloat(),
                    onValueChange = { onUpdateThreshold(it.toDouble()) },
                    valueRange = 0.001f..0.08f,
                )

                MetricDisplay(label = "ROI center", value = String.format("%.2f", uiState.roiCenterX))
                Slider(
                    value = uiState.roiCenterX.toFloat(),
                    onValueChange = { onUpdateRoiCenter(it.toDouble()) },
                    valueRange = 0.20f..0.80f,
                )

                MetricDisplay(label = "ROI width", value = String.format("%.2f", uiState.roiWidth))
                Slider(
                    value = uiState.roiWidth.toFloat(),
                    onValueChange = { onUpdateRoiWidth(it.toDouble()) },
                    valueRange = 0.03f..0.40f,
                )

                Text("Cooldown: ${uiState.cooldownMs} ms")
                Slider(
                    value = uiState.cooldownMs.toFloat(),
                    onValueChange = { onUpdateCooldown(it.toInt()) },
                    valueRange = 300f..2000f,
                )

                Spacer(Modifier.height(4.dp))
                SectionHeader("Live Stats")
                Text("Raw score: ${uiState.rawScore?.let { String.format("%.4f", it) } ?: "-"}")
                Text("Baseline: ${uiState.baseline?.let { String.format("%.4f", it) } ?: "-"}")
                Text("Effective: ${uiState.effectiveScore?.let { String.format("%.4f", it) } ?: "-"}")
                if (showDebugInfo) {
                    MetricDisplay(label = "Frame Sensor Nanos", value = "${uiState.frameSensorNanos ?: "-"}")
                    Text("Frames: ${uiState.processedFrameCount}/${uiState.streamFrameCount}")
                }

                Spacer(Modifier.height(4.dp))
                SectionHeader("Recent Triggers")
                if (uiState.triggerHistory.isEmpty()) {
                    Text("No trigger events yet.")
                } else {
                    uiState.triggerHistory.forEach { event ->
                        Text(event, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunMetricsCard(
    uiState: SprintSyncUiState,
    isHost: Boolean,
    showDebugInfo: Boolean,
    onResetRun: () -> Unit,
    onOpenSaveResultDialog: () -> Unit,
) {
    val fpsLabel = uiState.observedFps?.let { String.format("%.1f", it) } ?: "--.-"
    val targetSuffix = uiState.targetFpsUpper?.let { " · target $it" } ?: ""
    SprintSyncCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (shouldShowCameraFpsInfo(showDebugInfo)) {
                Text(
                    "Camera: $fpsLabel fps · ${uiState.cameraFpsModeLabel}$targetSuffix",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = uiState.elapsedDisplay,
                style = MaterialTheme.typography.displayLarge
                    .copy(fontSize = MaterialTheme.typography.displayLarge.fontSize * 1.12f)
                    .merge(TabularMonospaceTypography),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (
                shouldShowMonitoringTopSavedResultsButton(
                    stage = uiState.stage,
                    isHost = uiState.isHost,
                    operatingMode = uiState.operatingMode,
                    deviceProfile = BuildConfig.DEVICE_PROFILE,
                )
            ) {
                TextButton(
                    onClick = onOpenSaveResultDialog,
                    enabled = uiState.saveableRunDurationNanos != null,
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SaveResultDialog(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Result") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SavedResultsDialog(
    rows: List<SavedRunResult>,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Results") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("Time", modifier = Modifier.width(88.dp), fontWeight = FontWeight.SemiBold)
                    Text("Action", modifier = Modifier.width(60.dp), fontWeight = FontWeight.SemiBold)
                }
                if (rows.isEmpty()) {
                    Text("No saved results yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(row.name, modifier = Modifier.weight(1f))
                            Text(
                                formatElapsedTimerDisplay((row.durationNanos / 1_000_000L).coerceAtLeast(0L)),
                                modifier = Modifier.width(88.dp),
                            )
                            TextButton(
                                onClick = { onDelete(row.id) },
                                modifier = Modifier.width(60.dp),
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun DisplayResultsCard(rows: List<DisplayLapRow>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val displayCardBackground = Color(0xFFFFCC00)
        val displayTimeColor = Color(0xFF000000)
        val displayDeviceColor = Color(0xFF000000)
        val density = LocalDensity.current
        val layout = displayLayoutSpecForCount(rows.size)
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "WAITING FOR LAP RESULTS",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        val count = rows.size.coerceAtLeast(1)
        val visibleCards = displayHorizontalVisibleCardSlots(count)
        val availableHeight = maxHeight.takeIf { it > 0.dp } ?: layout.rowHeight
        val cardHeight = availableHeight.coerceAtLeast(layout.minRowHeight)
        val cardWidth = ((maxWidth - (layout.rowSpacing * (visibleCards - 1))) / visibleCards)
            .coerceAtLeast(layout.minRowHeight)
        val rowContentWidth = (cardWidth - (layout.horizontalPadding * 2)).coerceAtLeast(1.dp)
        val clampedTimeFont = clampDisplayTimeFont(layout.timeFont, cardHeight, rowContentWidth, density)
        val clampedDeviceFont = clampDisplayLabelFont(layout.deviceFont, cardHeight, density)

        LazyRow(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(layout.rowSpacing),
        ) {
            items(rows) { row ->
                Column(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(RoundedCornerShape(24.dp))
                        .background(displayCardBackground)
                        .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = row.deviceName,
                        style = MaterialTheme.typography.bodySmall.merge(
                            TextStyle(
                                fontSize = clampedDeviceFont,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                            ),
                        ),
                        color = displayDeviceColor,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = row.lapTimeLabel,
                        style = MaterialTheme.typography.displayLarge.merge(
                            InterExtraBoldTabularTypography.merge(
                                TextStyle(
                                    fontSize = clampedTimeFont,
                                ),
                            ),
                        ),
                        color = displayTimeColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedCard(connectedEndpoints: Set<String>) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Connected Devices")
            connectedEndpoints.forEach { endpointId ->
                Text(endpointId)
            }
        }
    }
}

@Composable
private fun ConnectedDevicesListCard(devices: List<SessionDevice>, showDebugInfo: Boolean) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Connected Devices")
            devices.forEach { device ->
                Text(if (device.isLocal) "${device.name} (Local)" else device.name)
                if (showDebugInfo) {
                    Text(device.id, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EventsCard(recentEvents: List<String>) {
    SprintSyncCard {
        Column {
            SectionHeader("Recent Events")
            Spacer(Modifier.height(8.dp))
            recentEvents.forEach { event ->
                Text(event, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun shouldShowSetupPermissionWarning(permissionGranted: Boolean, deniedPermissions: List<String>): Boolean =
    !permissionGranted && deniedPermissions.isNotEmpty()

internal fun shouldShowMonitoringResetAction(
    isHost: Boolean,
    startedSensorNanos: Long?,
    stoppedSensorNanos: Long?,
): Boolean = isHost && startedSensorNanos != null

internal fun shouldShowMonitoringTopResetRunButton(
    stage: SessionStage,
    isHost: Boolean,
    operatingMode: SessionOperatingMode,
    deviceProfile: String,
): Boolean {
    val canStopMonitoring =
        stage == SessionStage.MONITORING &&
            (isHost || operatingMode != SessionOperatingMode.NETWORK_RACE)
    return canStopMonitoring && deviceProfile == "host_xiaomi"
}

internal fun shouldShowMonitoringTopSavedResultsButton(
    stage: SessionStage,
    isHost: Boolean,
    operatingMode: SessionOperatingMode,
    deviceProfile: String,
): Boolean {
    val canStopMonitoring =
        stage == SessionStage.MONITORING &&
            (isHost || operatingMode != SessionOperatingMode.NETWORK_RACE)
    return canStopMonitoring && deviceProfile == "host_xiaomi"
}

internal fun shouldShowHostConnectedDeviceCards(
    stage: SessionStage,
    operatingMode: SessionOperatingMode,
    isHost: Boolean,
    deviceProfile: String,
): Boolean {
    return stage == SessionStage.MONITORING &&
        operatingMode == SessionOperatingMode.NETWORK_RACE &&
        isHost &&
        deviceProfile == "host_xiaomi"
}

internal fun shouldShowDisplayRelayControls(mode: SessionOperatingMode): Boolean =
    mode == SessionOperatingMode.SINGLE_DEVICE

internal fun shouldShowMonitoringRoleAndToggles(mode: SessionOperatingMode): Boolean =
    mode != SessionOperatingMode.SINGLE_DEVICE

internal fun shouldShowSingleDeviceCameraFacingToggle(mode: SessionOperatingMode): Boolean =
    mode == SessionOperatingMode.SINGLE_DEVICE

internal fun shouldShowMonitoringConnectionDebugInfo(showDebugInfo: Boolean): Boolean = showDebugInfo

internal fun shouldShowMonitoringSensitivityControl(
    controllerOnlyHost: Boolean,
    mode: SessionOperatingMode,
): Boolean = !controllerOnlyHost && mode != SessionOperatingMode.DISPLAY_HOST

private const val SENSITIVITY_MIN = 1
private const val SENSITIVITY_MAX = 100
private const val THRESHOLD_MIN = 0.001
private const val THRESHOLD_MAX = 0.08

internal fun sensitivityToThreshold(sensitivity: Int): Double {
    val clamped = sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
    val normalized = (clamped - SENSITIVITY_MIN).toDouble() / (SENSITIVITY_MAX - SENSITIVITY_MIN).toDouble()
    return THRESHOLD_MAX - normalized * (THRESHOLD_MAX - THRESHOLD_MIN)
}

internal fun thresholdToSensitivity(threshold: Double): Float {
    val clamped = threshold.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
    val normalized = (clamped - THRESHOLD_MIN) / (THRESHOLD_MAX - THRESHOLD_MIN)
    return (SENSITIVITY_MAX - normalized * (SENSITIVITY_MAX - SENSITIVITY_MIN)).toFloat()
}

internal fun shouldShowMonitoringPreview(mode: SessionOperatingMode, effectiveShowPreview: Boolean): Boolean =
    effectiveShowPreview

internal fun shouldShowMonitoringPreviewToggle(mode: SessionOperatingMode): Boolean =
    mode != SessionOperatingMode.DISPLAY_HOST

internal fun shouldShowRunDetailMetrics(mode: SessionOperatingMode): Boolean =
    mode != SessionOperatingMode.SINGLE_DEVICE

internal fun shouldShowCameraFpsInfo(showDebugInfo: Boolean): Boolean = showDebugInfo

internal data class DisplayLayoutSpec(
    val rowHeight: Dp,
    val minRowHeight: Dp,
    val rowSpacing: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val timeFont: TextUnit,
    val deviceFont: TextUnit,
)

internal fun displayLayoutSpecForCount(count: Int): DisplayLayoutSpec {
    return when {
        count <= 1 -> DisplayLayoutSpec(
            rowHeight = 420.dp,
            minRowHeight = 300.dp,
            rowSpacing = 24.dp,
            horizontalPadding = 26.dp,
            verticalPadding = 22.dp,
            timeFont = 168.sp,
            deviceFont = 26.sp,
        )
        count == 2 -> DisplayLayoutSpec(
            rowHeight = 330.dp,
            minRowHeight = 230.dp,
            rowSpacing = 18.dp,
            horizontalPadding = 22.dp,
            verticalPadding = 18.dp,
            timeFont = 138.sp,
            deviceFont = 22.sp,
        )
        count in 3..4 -> DisplayLayoutSpec(
            rowHeight = 245.dp,
            minRowHeight = 170.dp,
            rowSpacing = 12.dp,
            horizontalPadding = 18.dp,
            verticalPadding = 14.dp,
            timeFont = 104.sp,
            deviceFont = 18.sp,
        )
        else -> DisplayLayoutSpec(
            rowHeight = 182.dp,
            minRowHeight = 130.dp,
            rowSpacing = 8.dp,
            horizontalPadding = 14.dp,
            verticalPadding = 10.dp,
            timeFont = 72.sp,
            deviceFont = 15.sp,
        )
    }
}

internal fun displayHorizontalVisibleCardSlots(count: Int): Int = when {
    count <= 1 -> 1
    count == 2 -> 2
    else -> 3
}

internal fun clampDisplayTimeFont(
    base: TextUnit,
    rowHeight: Dp,
    rowContentWidth: Dp,
    density: androidx.compose.ui.unit.Density,
): TextUnit {
    val maxByHeight = with(density) { (rowHeight * 0.74f).toSp() }
    val maxChars = 8f // "MM:SS.cc"
    val widthFactor = 0.62f // Approximate monospace glyph width in ems.
    val maxByWidth = with(density) { (rowContentWidth / (maxChars * widthFactor)).toSp() }
    return minOf(base.value, maxByHeight.value, maxByWidth.value).sp
}

internal fun clampDisplayLabelFont(base: TextUnit, rowHeight: Dp, density: androidx.compose.ui.unit.Density): TextUnit {
    val maxByHeight = with(density) { (rowHeight * 0.16f).toSp() }
    val minReadable = 12.sp
    val clamped = minOf(base.value, maxByHeight.value).sp
    return maxOf(clamped.value, minReadable.value).sp
}

private fun formatDurationNanos(nanos: Long): String {
    val totalMillis = (nanos / 1_000_000L).coerceAtLeast(0L)
    return formatElapsedTimerDisplay(totalMillis)
}
