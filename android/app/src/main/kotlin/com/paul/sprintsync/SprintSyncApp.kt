package com.paul.sprintsync

import com.paul.sprintsync.ui.components.*
import com.paul.sprintsync.ui.theme.*

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.features.race_session.SessionDevice
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.features.race_session.sessionCameraFacingLabel
import com.paul.sprintsync.features.race_session.sessionDeviceRoleLabel
import com.paul.sprintsync.sensor_native.SensorNativePreviewViewFactory

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
    val localHighSpeedEnabled: Boolean = false,
    val monitoringConnectionTypeLabel: String = "-",
    val monitoringSyncModeLabel: String = "-",
    val monitoringLatencyMs: Int? = null,
    val hasConnectedPeers: Boolean = false,
    val clockLockWarningText: String? = null,
    val runStatusLabel: String = "Ready",
    val runMarksCount: Int = 0,
    val elapsedDisplay: String = "00:00.000",
    val threshold: Double = 0.006,
    val roiCenterX: Double = 0.5,
    val roiWidth: Double = 0.12,
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
    val lastNearbyEvent: String? = null,
    val lastSensorEvent: String? = null,
    val recentEvents: List<String> = emptyList(),
)

@Composable
fun SprintSyncApp(
    uiState: SprintSyncUiState,
    previewViewFactory: SensorNativePreviewViewFactory,
    onRequestPermissions: () -> Unit,
    onStartHosting: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onResetRun: () -> Unit,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
    onUpdateThreshold: (Double) -> Unit,
    onUpdateRoiCenter: (Double) -> Unit,
    onUpdateRoiWidth: (Double) -> Unit,
    onUpdateCooldown: (Int) -> Unit,
    onStopHosting: () -> Unit,
) {
    var showPreview by rememberSaveable { mutableStateOf(true) }
    val previewAvailable = !uiState.localHighSpeedEnabled
    val effectiveShowPreview = previewAvailable && showPreview

    Scaffold(
        topBar = {},
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (uiState.stage) {
                            SessionStage.SETUP -> "Setup Session"
                            SessionStage.LOBBY -> "Race Lobby"
                            SessionStage.MONITORING -> "Monitoring"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (uiState.stage == SessionStage.MONITORING && uiState.isHost) {
                        SecondaryButton(text = "Stop", onClick = onStopMonitoring)
                    }
                }
            }

            if (uiState.stage != SessionStage.MONITORING) {
                item {
                    StatusCard(uiState)
                }
            }

            when (uiState.stage) {
                SessionStage.SETUP -> {
                    item {
                        SetupActionsCard(
                            permissionGranted = uiState.permissionGranted,
                            setupBusy = uiState.setupBusy,
                            onRequestPermissions = onRequestPermissions,
                            onStartHosting = onStartHosting,
                            onStartDiscovery = onStartDiscovery,
                        )
                    }
                    item {
                        ConnectedDevicesListCard(uiState.devices)
                    }
                }

                SessionStage.LOBBY -> {
                    item {
                        LobbyActionsCard(
                            isHost = uiState.isHost,
                            canStartMonitoring = uiState.canStartMonitoring,
                            timelineStarted = uiState.startedSensorNanos != null,
                            onStartMonitoring = onStartMonitoring,
                            onResetRun = onResetRun,
                            onStopHosting = onStopHosting,
                        )
                    }
                    if (uiState.hasConnectedPeers) {
                        item {
                            MonitoringConnectionCard(
                                connectionTypeLabel = uiState.monitoringConnectionTypeLabel,
                                syncModeLabel = uiState.monitoringSyncModeLabel,
                                latencyMs = uiState.monitoringLatencyMs,
                            )
                        }
                    }
                    item {
                        DeviceAssignmentsCard(
                            devices = uiState.devices,
                            editable = uiState.networkRole == SessionNetworkRole.HOST,
                            onAssignRole = onAssignRole,
                            onAssignCameraFacing = onAssignCameraFacing,
                        )
                    }
                    item {
                        TimelineCard(
                            startedSensorNanos = uiState.startedSensorNanos,
                            stoppedSensorNanos = uiState.stoppedSensorNanos,
                        )
                    }
                }

                SessionStage.MONITORING -> {
                    item {
                        MonitoringSummaryCard(
                            isHost = uiState.isHost,
                            localRole = uiState.localRole,
                            connectionTypeLabel = uiState.monitoringConnectionTypeLabel,
                            syncModeLabel = uiState.monitoringSyncModeLabel,
                            latencyMs = uiState.monitoringLatencyMs,
                            previewAvailable = previewAvailable,
                            effectiveShowPreview = effectiveShowPreview,
                            onShowPreviewChanged = { showPreview = it },
                            previewViewFactory = previewViewFactory,
                            roiCenterX = uiState.roiCenterX,
                            onResetRun = onResetRun,
                        )
                    }
                    if (uiState.clockLockWarningText != null) {
                        item {
                            ClockWarningCard(uiState.clockLockWarningText)
                        }
                    }
                    item {
                        RunMetricsCard(uiState)
                    }
                    item {
                        AdvancedDetectionCard(
                            uiState = uiState,
                            onUpdateThreshold = onUpdateThreshold,
                            onUpdateRoiCenter = onUpdateRoiCenter,
                            onUpdateRoiWidth = onUpdateRoiWidth,
                            onUpdateCooldown = onUpdateCooldown,
                        )
                    }
                }
            }

            if (uiState.stage != SessionStage.SETUP && uiState.connectedEndpoints.isNotEmpty()) {
                item {
                    ConnectedCard(uiState.connectedEndpoints)
                }
            }

            if (uiState.recentEvents.isNotEmpty()) {
                item {
                    EventsCard(uiState.recentEvents)
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
            MetricDisplay(label = "Motion", value = uiState.monitoringSummary)
            MetricDisplay(label = "Clock", value = uiState.clockSummary)
            uiState.lastNearbyEvent?.let { Text("Last Nearby: $it") }
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
private fun SetupActionsCard(
    permissionGranted: Boolean,
    setupBusy: Boolean,
    onRequestPermissions: () -> Unit,
    onStartHosting: () -> Unit,
    onStartDiscovery: () -> Unit,
) {
    val setupActionsEnabled = !setupBusy

    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Network Connection")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!permissionGranted) {
                    PrimaryButton(text = "Permissions", onClick = onRequestPermissions, enabled = setupActionsEnabled)
                }
                PrimaryButton(text = "Host", onClick = onStartHosting, enabled = setupActionsEnabled)
                PrimaryButton(text = "Join", onClick = onStartDiscovery, enabled = setupActionsEnabled)
            }
        }
    }
}

@Composable
private fun LobbyActionsCard(
    isHost: Boolean,
    canStartMonitoring: Boolean,
    timelineStarted: Boolean,
    onStartMonitoring: () -> Unit,
    onResetRun: () -> Unit,
    onStopHosting: () -> Unit,
) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Session Actions")
            PrimaryButton(text = "Start Monitoring", onClick = onStartMonitoring, enabled = isHost && canStartMonitoring)
            if (isHost) {
                OutlinedButton(onClick = onStopHosting) {
                    Text("Stop Hosting")
                }
            }
            if (isHost && timelineStarted) {
                OutlinedButton(onClick = onResetRun) {
                    Text("Reset Run")
                }
            }
        }
    }
}

@Composable
private fun DeviceAssignmentsCard(
    devices: List<SessionDevice>,
    editable: Boolean,
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
            Text(device.id, style = MaterialTheme.typography.bodySmall)
            if (editable) {
                val roleOptions = buildList {
                    add(SessionDeviceRole.UNASSIGNED)
                    add(SessionDeviceRole.START)
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
    localRole: SessionDeviceRole,
    connectionTypeLabel: String,
    syncModeLabel: String,
    latencyMs: Int?,
    previewAvailable: Boolean,
    effectiveShowPreview: Boolean,
    onShowPreviewChanged: (Boolean) -> Unit,
    previewViewFactory: SensorNativePreviewViewFactory,
    roiCenterX: Double,
    onResetRun: () -> Unit,
) {
    val latencyLabel = when (syncModeLabel) {
        "NTP" -> if (latencyMs == null) "-" else "$latencyMs ms"
        "GPS" -> "GPS"
        else -> "-"
    }

    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Role: ${sessionDeviceRoleLabel(localRole)}",
                    fontWeight = FontWeight.Bold,
                )
                if (isHost) {
                    TextButton(onClick = onResetRun) {
                        Text("Reset")
                    }
                } else {
                    Text("Waiting for host...", color = Color.Gray, fontStyle = FontStyle.Italic)
                }
            }
            Text("Connection: $connectionTypeLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("Sync: $syncModeLabel · Latency: $latencyLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Preview", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = effectiveShowPreview,
                    enabled = previewAvailable,
                    onCheckedChange = onShowPreviewChanged,
                )
                if (!previewAvailable) {
                    Spacer(Modifier.width(8.dp))
                    Text("Disabled in HS", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (effectiveShowPreview) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
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

@Composable
private fun MonitoringConnectionCard(
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection: $connectionTypeLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("Sync: $syncModeLabel · Latency: $latencyLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
private fun PreviewSurface(
    previewViewFactory: SensorNativePreviewViewFactory,
    roiCenterX: Double,
) {
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
            }
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
                    valueRange = 0.05f..0.40f,
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
                MetricDisplay(label = "Frame Sensor Nanos", value = "${uiState.frameSensorNanos ?: "-"}")
                Text("Frames: ${uiState.processedFrameCount}/${uiState.streamFrameCount}")

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
private fun RunMetricsCard(uiState: SprintSyncUiState) {
    val fpsLabel = uiState.observedFps?.let { String.format("%.1f", it) } ?: "--.-"
    val targetSuffix = uiState.targetFpsUpper?.let { " · target $it" } ?: ""
    val finishValue = if (uiState.startedSensorNanos != null && uiState.stoppedSensorNanos != null) {
        formatDurationNanos((uiState.stoppedSensorNanos - uiState.startedSensorNanos).coerceAtLeast(0L))
    } else {
        "-"
    }
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader("Sprint Stopwatch")
            Text("Camera: $fpsLabel fps · ${uiState.cameraFpsModeLabel}$targetSuffix", style = MaterialTheme.typography.bodySmall)
            Text("Timer")
            Text(uiState.elapsedDisplay, style = MaterialTheme.typography.displaySmall.merge(TabularMonospaceTypography))
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetricDisplay(label = "Status", value = uiState.runStatusLabel)
                MetricDisplay(label = "Marks", value = uiState.runMarksCount.toString())
                MetricDisplay(label = "Finish", value = finishValue)
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
private fun ConnectedDevicesListCard(devices: List<SessionDevice>) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Connected Devices")
            devices.forEach { device ->
                Text(if (device.isLocal) "${device.name} (Local)" else device.name)
                Text(device.id, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventsCard(recentEvents: List<String>) {
    SprintSyncCard {
        Column() {
            SectionHeader("Recent Events")
            Spacer(Modifier.height(8.dp))
            recentEvents.forEach { event ->
                Text(event, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TimelineCard(
    startedSensorNanos: Long?,
    stoppedSensorNanos: Long?,
) {
    SprintSyncCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Race Timeline")
            if (startedSensorNanos == null) {
                Text("Ready to start.")
                return@Column
            }
            Text("Started Sensor Nanos: $startedSensorNanos")
            if (stoppedSensorNanos != null) {
                val elapsed = (stoppedSensorNanos - startedSensorNanos).coerceAtLeast(0L)
                Text("Finished: ${formatDurationNanos(elapsed)}", fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatDurationNanos(nanos: Long): String {
    val totalMillis = (nanos / 1_000_000L).coerceAtLeast(0L)
    val minutes = totalMillis / 60_000L
    val seconds = (totalMillis % 60_000L) / 1_000L
    val millis = totalMillis % 1_000L
    return String.format("%02d:%02d.%03d", minutes, seconds, millis)
}
