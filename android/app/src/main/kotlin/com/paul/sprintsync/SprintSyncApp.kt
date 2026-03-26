package com.paul.sprintsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.features.race_session.SessionDevice
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.features.race_session.sessionCameraFacingLabel
import com.paul.sprintsync.features.race_session.sessionDeviceRoleLabel

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
    val lastNearbyEvent: String? = null,
    val lastSensorEvent: String? = null,
    val recentEvents: List<String> = emptyList(),
)

@Composable
fun SprintSyncApp(
    uiState: SprintSyncUiState,
    onRequestPermissions: () -> Unit,
    onStartHosting: () -> Unit,
    onStartHostingPointToPoint: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStartDiscoveryPointToPoint: () -> Unit,
    onConnectEndpoint: (String) -> Unit,
    onGoToLobby: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onAssignRole: (String, SessionDeviceRole) -> Unit,
    onAssignCameraFacing: (String, SessionCameraFacing) -> Unit,
    onAssignHighSpeedEnabled: (String, Boolean) -> Unit,
    onClockSyncBurst: () -> Unit,
    onChirpSync: () -> Unit,
    onStopAll: () -> Unit,
) {
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
                Text(
                    text = when (uiState.stage) {
                        SessionStage.SETUP -> "Setup Session"
                        SessionStage.LOBBY -> "Race Lobby"
                        SessionStage.MONITORING -> "Monitoring"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            item {
                StatusCard(uiState)
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
                }

                SessionStage.LOBBY -> {
                    item {
                        LobbyActionsCard(
                            networkRole = uiState.networkRole,
                            connectedCount = uiState.connectedEndpoints.size,
                            canStartMonitoring = uiState.canStartMonitoring,
                            onStartMonitoring = onStartMonitoring,
                            onClockSyncBurst = onClockSyncBurst,
                            onChirpSync = onChirpSync,
                            onStopAll = onStopAll,
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
                        MonitoringActionsCard(
                            onStopMonitoring = onStopMonitoring,
                            onClockSyncBurst = onClockSyncBurst,
                            onChirpSync = onChirpSync,
                            onStopAll = onStopAll,
                        )
                    }
                    item {
                        DeviceAssignmentsCard(
                            devices = uiState.devices,
                            editable = false,
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
            }

            if (uiState.connectedEndpoints.isNotEmpty()) {
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
                    Text("Host Star")
                }
                Button(onClick = onStartDiscovery) {
                    Text("Join Star")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStartHostingPointToPoint) {
                    Text("Host P2P")
                }
                OutlinedButton(onClick = onStartDiscoveryPointToPoint) {
                    Text("Join P2P")
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
    networkRole: SessionNetworkRole,
    connectedCount: Int,
    canStartMonitoring: Boolean,
    onStartMonitoring: () -> Unit,
    onClockSyncBurst: () -> Unit,
    onChirpSync: () -> Unit,
    onStopAll: () -> Unit,
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
            if (networkRole == SessionNetworkRole.CLIENT) {
                Text("Waiting for host to start monitoring", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClockSyncBurst, enabled = connectedCount > 0) {
                    Text("Clock Sync")
                }
                OutlinedButton(onClick = onChirpSync, enabled = connectedCount > 0) {
                    Text("Chirp Sync")
                }
            }
            OutlinedButton(onClick = onStopAll, enabled = networkRole != SessionNetworkRole.NONE) {
                Text("Stop Session")
            }
        }
    }
}

@Composable
private fun MonitoringActionsCard(
    onStopMonitoring: () -> Unit,
    onClockSyncBurst: () -> Unit,
    onChirpSync: () -> Unit,
    onStopAll: () -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Monitoring Actions", fontWeight = FontWeight.SemiBold)
            Button(onClick = onStopMonitoring) {
                Text("Stop")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClockSyncBurst) {
                    Text("Clock Sync")
                }
                OutlinedButton(onClick = onChirpSync) {
                    Text("Chirp Sync")
                }
            }
            OutlinedButton(onClick = onStopAll) {
                Text("Stop All")
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
            Text("Device Assignments", fontWeight = FontWeight.SemiBold)
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
            Text("Camera: ${sessionCameraFacingLabel(device.cameraFacing)}")
            Text("High Speed: ${if (device.highSpeedEnabled) "On" else "Off"}")
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onAssignRole(device.id, nextRole(device.role, canShowSplitControls))
                        },
                    ) {
                        Text("Role")
                    }
                    OutlinedButton(
                        onClick = {
                            val next = if (device.cameraFacing == SessionCameraFacing.REAR) {
                                SessionCameraFacing.FRONT
                            } else {
                                SessionCameraFacing.REAR
                            }
                            onAssignCameraFacing(device.id, next)
                        },
                    ) {
                        Text("Camera")
                    }
                    OutlinedButton(
                        onClick = {
                            onAssignHighSpeedEnabled(device.id, !device.highSpeedEnabled)
                        },
                    ) {
                        Text("HS")
                    }
                }
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
                Text("Split ${index + 1}: $split")
            }
            if (stoppedSensorNanos != null) {
                Text("Stopped Sensor Nanos: $stoppedSensorNanos", fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun nextRole(current: SessionDeviceRole, canShowSplitControls: Boolean): SessionDeviceRole {
    return if (canShowSplitControls) {
        when (current) {
            SessionDeviceRole.UNASSIGNED -> SessionDeviceRole.START
            SessionDeviceRole.START -> SessionDeviceRole.SPLIT
            SessionDeviceRole.SPLIT -> SessionDeviceRole.STOP
            SessionDeviceRole.STOP -> SessionDeviceRole.UNASSIGNED
        }
    } else {
        when (current) {
            SessionDeviceRole.UNASSIGNED -> SessionDeviceRole.START
            SessionDeviceRole.START -> SessionDeviceRole.STOP
            SessionDeviceRole.SPLIT -> SessionDeviceRole.STOP
            SessionDeviceRole.STOP -> SessionDeviceRole.UNASSIGNED
        }
    }
}
