package com.paul.sprintsync

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val deniedPermissions: List<String> = emptyList(),
    val stage: SessionStage = SessionStage.SETUP,
    val networkRole: SessionNetworkRole = SessionNetworkRole.NONE,
    val networkSummary: String = "Ready",
    val monitoringSummary: String = "Idle",
    val clockSummary: String = "Unlocked",
    val chirpSummary: String = "Unlocked",
    val sessionSummary: String = "setup",
    val startedSensorNanos: Long? = null,
    val splitSensorNanos: List<Long> = emptyList(),
    val stoppedSensorNanos: Long? = null,
    val discoveredEndpoints: Map<String, String> = emptyMap(),
    val connectedEndpoints: Set<String> = emptySet(),
    val devices: List<SessionDevice> = emptyList(),
    val canGoToLobby: Boolean = false,
    val canStartMonitoring: Boolean = false,
    val canShowSplitControls: Boolean = false,
    val isHost: Boolean = false,
    val localRole: SessionDeviceRole = SessionDeviceRole.UNASSIGNED,
    val localHighSpeedEnabled: Boolean = false,
    val monitoringConnectionTypeLabel: String = "-",
    val monitoringSyncModeLabel: String = "-",
    val monitoringLatencyMs: Int? = null,
    val hasConnectedPeers: Boolean = false,
    val chirpSyncInProgress: Boolean = false,
    val chirpLockActive: Boolean = false,
    val chirpSyncStatusText: String = "Not calibrated",
    val chirpQualityUs: Int? = null,
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
    onStartHostingPointToPoint: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStartDiscoveryPointToPoint: () -> Unit,
    onConnectEndpoint: (String) -> Unit,
    onGoToLobby: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onResetRun: () -> Unit,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
    onAssignHighSpeedEnabled: (String, Boolean) -> Unit,
    onClockSyncBurst: () -> Unit,
    onStartChirpSync: () -> Unit,
    onEndChirpSync: () -> Unit,
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
                        OutlinedButton(onClick = onStopMonitoring) {
                            Text("Stop")
                        }
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
                            connectedCount = uiState.connectedEndpoints.size,
                            canGoToLobby = uiState.canGoToLobby,
                            onRequestPermissions = onRequestPermissions,
                            onStartHosting = onStartHosting,
                            onStartHostingPointToPoint = onStartHostingPointToPoint,
                            onStartDiscovery = onStartDiscovery,
                            onStartDiscoveryPointToPoint = onStartDiscoveryPointToPoint,
                            onGoToLobby = onGoToLobby,
                        )
                    }
                    if (uiState.discoveredEndpoints.isNotEmpty()) {
                        item {
                            Text("Discovered Devices", style = MaterialTheme.typography.titleMedium)
                        }
                        items(uiState.discoveredEndpoints.toList(), key = { it.first }) { endpoint ->
                            EndpointRow(
                                endpointId = endpoint.first,
                                endpointName = endpoint.second,
                                onConnect = onConnectEndpoint,
                            )
                        }
                    }
                    item {
                        ConnectedDevicesListCard(uiState.devices)
                    }
                }

                SessionStage.LOBBY -> {
                    item {
                        LobbyActionsCard(
                            isHost = uiState.isHost,
                            networkRole = uiState.networkRole,
                            connectedCount = uiState.connectedEndpoints.size,
                            canStartMonitoring = uiState.canStartMonitoring,
                            timelineStarted = uiState.startedSensorNanos != null,
                            onStartMonitoring = onStartMonitoring,
                            onResetRun = onResetRun,
                            onClockSyncBurst = onClockSyncBurst,
                            onStopHosting = onStopHosting,
                        )
                    }
                    item {
                        ChirpSyncCard(
                            isClient = uiState.networkRole == SessionNetworkRole.CLIENT,
                            hasConnectedPeers = uiState.hasConnectedPeers,
                            chirpSyncInProgress = uiState.chirpSyncInProgress,
                            chirpLockActive = uiState.chirpLockActive,
                            statusText = uiState.chirpSyncStatusText,
                            onStartChirpSync = onStartChirpSync,
                            onEndChirpSync = onEndChirpSync,
                        )
                    }
                    item {
                        DeviceAssignmentsCard(
                            devices = uiState.devices,
                            editable = uiState.networkRole == SessionNetworkRole.HOST,
                            canShowSplitControls = uiState.canShowSplitControls,
                            onAssignRole = onAssignRole,
                            onAssignCameraFacing = onAssignCameraFacing,
                            onAssignHighSpeedEnabled = onAssignHighSpeedEnabled,
                        )
                    }
                    item {
                        TimelineCard(
                            startedSensorNanos = uiState.startedSensorNanos,
                            splitSensorNanos = uiState.splitSensorNanos,
                            stoppedSensorNanos = uiState.stoppedSensorNanos,
                        )
                    }
                }

                SessionStage.MONITORING -> {
                    item {
                        MonitoringHeaderCard(
                            isHost = uiState.isHost,
                            localRole = uiState.localRole,
                            onResetRun = onResetRun,
                        )
                    }
                    item {
                        MonitoringConnectionCard(
                            connectionTypeLabel = uiState.monitoringConnectionTypeLabel,
                            syncModeLabel = uiState.monitoringSyncModeLabel,
                            latencyMs = uiState.monitoringLatencyMs,
                            chirpQualityUs = uiState.chirpQualityUs,
                        )
                    }
                    if (uiState.clockLockWarningText != null) {
                        item {
                            ClockWarningCard(uiState.clockLockWarningText)
                        }
                    }
                    item {
                        PreviewToggleCard(
                            previewAvailable = previewAvailable,
                            effectiveShowPreview = effectiveShowPreview,
                            onShowPreviewChanged = { showPreview = it },
                        )
                    }
                    if (effectiveShowPreview) {
                        item {
                            NativePreviewCard(
                                previewViewFactory = previewViewFactory,
                                roiCenterX = uiState.roiCenterX,
                            )
                        }
                    }
                    item {
                        StopwatchCard(uiState)
                    }
                    item {
                        CurrentRunMarksCard(uiState)
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
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Session Status", fontWeight = FontWeight.SemiBold)
            Text("Stage: ${uiState.sessionSummary}")
            Text("Network: ${uiState.networkSummary}")
            Text("Motion: ${uiState.monitoringSummary}")
            Text("Clock: ${uiState.clockSummary}")
            Text("Chirp: ${uiState.chirpSummary}")
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
    connectedCount: Int,
    canGoToLobby: Boolean,
    onRequestPermissions: () -> Unit,
    onStartHosting: () -> Unit,
    onStartHostingPointToPoint: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStartDiscoveryPointToPoint: () -> Unit,
    onGoToLobby: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Network Connection", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!permissionGranted) {
                    Button(onClick = onRequestPermissions) {
                        Text("Permissions")
                    }
                }
                Button(onClick = onStartHosting) {
                    Text("Host")
                }
                Button(onClick = onStartDiscovery) {
                    Text("Join")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStartHostingPointToPoint) {
                    Text("Host 1:1")
                }
                OutlinedButton(onClick = onStartDiscoveryPointToPoint) {
                    Text("Join 1:1")
                }
            }
            OutlinedButton(onClick = onGoToLobby, enabled = canGoToLobby && connectedCount > 0) {
                Text("Next")
            }
        }
    }
}

@Composable
private fun EndpointRow(
    endpointId: String,
    endpointName: String,
    onConnect: (String) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(endpointName, fontWeight = FontWeight.Medium)
                Text(endpointId, style = MaterialTheme.typography.bodySmall)
            }
            AssistChip(onClick = { onConnect(endpointId) }, label = { Text("Connect") })
        }
    }
}

@Composable
private fun LobbyActionsCard(
    isHost: Boolean,
    networkRole: SessionNetworkRole,
    connectedCount: Int,
    canStartMonitoring: Boolean,
    timelineStarted: Boolean,
    onStartMonitoring: () -> Unit,
    onResetRun: () -> Unit,
    onClockSyncBurst: () -> Unit,
    onStopHosting: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Session Actions", fontWeight = FontWeight.SemiBold)
            Text("Role: ${networkRole.name.lowercase()}")
            Text("Connected peers: $connectedCount")
            Button(
                onClick = onStartMonitoring,
                enabled = networkRole == SessionNetworkRole.HOST && canStartMonitoring,
            ) {
                Text("Start Monitoring")
            }
            if (isHost && timelineStarted) {
                OutlinedButton(onClick = onResetRun) {
                    Text("Reset Run")
                }
            }
            if (networkRole == SessionNetworkRole.CLIENT) {
                Text("Waiting for host to start monitoring", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onClockSyncBurst, enabled = connectedCount > 0) {
                Text("Clock Sync")
            }
            if (isHost) {
                OutlinedButton(onClick = onStopHosting, enabled = networkRole != SessionNetworkRole.NONE) {
                    Text("Stop Hosting")
                }
            }
        }
    }
}

@Composable
private fun ChirpSyncCard(
    isClient: Boolean,
    hasConnectedPeers: Boolean,
    chirpSyncInProgress: Boolean,
    chirpLockActive: Boolean,
    statusText: String,
    onStartChirpSync: () -> Unit,
    onEndChirpSync: () -> Unit,
) {
    val canStart = isClient && hasConnectedPeers && !chirpSyncInProgress
    val canEnd = chirpLockActive || chirpSyncInProgress
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audio Chirp Sync", fontWeight = FontWeight.SemiBold)
            Text("Status: $statusText", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStartChirpSync, enabled = canStart) {
                    Text("Start Chirp Sync")
                }
                OutlinedButton(onClick = onEndChirpSync, enabled = canEnd) {
                    Text("End Chirp Sync")
                }
            }
        }
    }
}

@Composable
private fun DeviceAssignmentsCard(
    devices: List<SessionDevice>,
    editable: Boolean,
    canShowSplitControls: Boolean,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
    onAssignHighSpeedEnabled: (String, Boolean) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device Roles", fontWeight = FontWeight.SemiBold)
            devices.forEach { device ->
                DeviceAssignmentRow(
                    device = device,
                    editable = editable,
                    canShowSplitControls = canShowSplitControls,
                    onAssignRole = onAssignRole,
                    onAssignCameraFacing = onAssignCameraFacing,
                    onAssignHighSpeedEnabled = onAssignHighSpeedEnabled,
                )
            }
        }
    }
}

@Composable
private fun DeviceAssignmentRow(
    device: SessionDevice,
    editable: Boolean,
    canShowSplitControls: Boolean,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
    onAssignHighSpeedEnabled: (String, Boolean) -> Unit,
) {
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
            Text("Role: ${sessionDeviceRoleLabel(device.role)}")
            if (editable) {
                val roleOptions = buildList {
                    add(SessionDeviceRole.UNASSIGNED)
                    add(SessionDeviceRole.START)
                    if (canShowSplitControls) {
                        add(SessionDeviceRole.SPLIT)
                    }
                    add(SessionDeviceRole.STOP)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    roleOptions.forEach { option ->
                        AssistChip(
                            onClick = { onAssignRole(device.id, option) },
                            label = { Text(sessionDeviceRoleLabel(option)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { onAssignCameraFacing(device.id, SessionCameraFacing.REAR) },
                        label = { Text("Rear") },
                    )
                    AssistChip(
                        onClick = { onAssignCameraFacing(device.id, SessionCameraFacing.FRONT) },
                        label = { Text("Front") },
                    )
                    AssistChip(
                        onClick = { onAssignHighSpeedEnabled(device.id, !device.highSpeedEnabled) },
                        label = { Text(if (device.highSpeedEnabled) "HS On" else "HS Off") },
                    )
                }
            } else {
                Text("Camera: ${sessionCameraFacingLabel(device.cameraFacing)}")
                Text("High Speed: ${if (device.highSpeedEnabled) "On" else "Off"}")
            }
        }
    }
}

@Composable
private fun MonitoringHeaderCard(
    isHost: Boolean,
    localRole: SessionDeviceRole,
    onResetRun: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Role: ${sessionDeviceRoleLabel(localRole)}",
                fontWeight = FontWeight.Bold,
            )
            if (isHost) {
                OutlinedButton(onClick = onResetRun) {
                    Text("Reset Run")
                }
            } else {
                Text("Waiting for host...", color = Color.Gray, fontStyle = FontStyle.Italic)
            }
        }
    }
}

@Composable
private fun MonitoringConnectionCard(
    connectionTypeLabel: String,
    syncModeLabel: String,
    latencyMs: Int?,
    chirpQualityUs: Int?,
) {
    val latencyLabel = when (syncModeLabel) {
        "NTP" -> if (latencyMs == null) "-" else "$latencyMs ms"
        "GPS" -> "GPS"
        "CHIRP" -> if (chirpQualityUs == null) "Chirp" else "$chirpQualityUs us"
        else -> "-"
    }
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connection: $connectionTypeLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("Sync: $syncModeLabel · Latency: $latencyLabel", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun ClockWarningCard(text: String) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("!", color = Color(0xFFD97706), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewToggleCard(
    previewAvailable: Boolean,
    effectiveShowPreview: Boolean,
    onShowPreviewChanged: (Boolean) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preview")
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
    }
}

@Composable
private fun NativePreviewCard(
    previewViewFactory: SensorNativePreviewViewFactory,
    roiCenterX: Double,
) {
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    Card {
        Box(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(modifier = Modifier.fillMaxWidth(0.34f)) {
                Box(modifier = Modifier.aspectRatio(9f / 16f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            previewViewFactory.createPreviewView(context).also { previewViewRef = it }
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
        }
    }
    androidx.compose.runtime.DisposableEffect(previewViewRef) {
        onDispose {
            previewViewRef?.let(previewViewFactory::detachPreviewView)
            previewViewRef = null
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
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced Detection", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (expanded) {
                Text("Threshold: ${String.format("%.3f", uiState.threshold)}")
                Slider(
                    value = uiState.threshold.toFloat(),
                    onValueChange = { onUpdateThreshold(it.toDouble()) },
                    valueRange = 0.001f..0.08f,
                )

                Text("ROI center: ${String.format("%.2f", uiState.roiCenterX)}")
                Slider(
                    value = uiState.roiCenterX.toFloat(),
                    onValueChange = { onUpdateRoiCenter(it.toDouble()) },
                    valueRange = 0.20f..0.80f,
                )

                Text("ROI width: ${String.format("%.2f", uiState.roiWidth)}")
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
                Text("Live Stats", fontWeight = FontWeight.Bold)
                Text("Raw score: ${uiState.rawScore?.let { String.format("%.4f", it) } ?: "-"}")
                Text("Baseline: ${uiState.baseline?.let { String.format("%.4f", it) } ?: "-"}")
                Text("Effective: ${uiState.effectiveScore?.let { String.format("%.4f", it) } ?: "-"}")
                Text("Frame Sensor Nanos: ${uiState.frameSensorNanos ?: "-"}")
                Text("Frames: ${uiState.processedFrameCount}/${uiState.streamFrameCount}")

                Spacer(Modifier.height(4.dp))
                Text("Recent Triggers", fontWeight = FontWeight.Bold)
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
private fun StopwatchCard(uiState: SprintSyncUiState) {
    val fpsLabel = uiState.observedFps?.let { String.format("%.1f", it) } ?: "--.-"
    val targetSuffix = uiState.targetFpsUpper?.let { " · target $it" } ?: ""
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sprint Stopwatch", fontWeight = FontWeight.Bold)
            Text("Status: ${uiState.runStatusLabel}")
            Text("Marks: ${uiState.runMarksCount}")
            Text("Camera: $fpsLabel fps · ${uiState.cameraFpsModeLabel}$targetSuffix")
            Text("Timer")
            Text(uiState.elapsedDisplay, style = MaterialTheme.typography.displaySmall)
        }
    }
}

@Composable
private fun CurrentRunMarksCard(uiState: SprintSyncUiState) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Current Run Marks", fontWeight = FontWeight.Bold)
            if (uiState.splitSensorNanos.isEmpty() && uiState.stoppedSensorNanos == null) {
                Text("No finish mark yet.")
                return@Column
            }
            val start = uiState.startedSensorNanos
            uiState.splitSensorNanos.forEachIndexed { index, splitSensorNanos ->
                val splitElapsed = if (start == null) 0L else (splitSensorNanos - start).coerceAtLeast(0L)
                Text("Split ${index + 1}: ${formatDurationNanos(splitElapsed)}")
            }
            val stop = uiState.stoppedSensorNanos
            if (start != null && stop != null) {
                Text("Finish: ${formatDurationNanos((stop - start).coerceAtLeast(0L))}")
            }
        }
    }
}

@Composable
private fun ConnectedCard(connectedEndpoints: Set<String>) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connected Devices", fontWeight = FontWeight.SemiBold)
            connectedEndpoints.forEach { endpointId ->
                Text(endpointId)
            }
        }
    }
}

@Composable
private fun ConnectedDevicesListCard(devices: List<SessionDevice>) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Connected Devices", fontWeight = FontWeight.SemiBold)
            devices.forEach { device ->
                Text(if (device.isLocal) "${device.name} (Local)" else device.name)
                Text(device.id, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventsCard(recentEvents: List<String>) {
    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Recent Events", fontWeight = FontWeight.SemiBold)
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
    splitSensorNanos: List<Long>,
    stoppedSensorNanos: Long?,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Race Timeline", fontWeight = FontWeight.SemiBold)
            if (startedSensorNanos == null) {
                Text("Ready to start.")
                return@Column
            }
            Text("Started Sensor Nanos: $startedSensorNanos")
            splitSensorNanos.forEachIndexed { index, split ->
                Text("Split ${index + 1}: ${formatDurationNanos((split - startedSensorNanos).coerceAtLeast(0L))}")
            }
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

