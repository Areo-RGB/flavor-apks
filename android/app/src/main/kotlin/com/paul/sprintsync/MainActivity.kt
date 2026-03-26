package com.paul.sprintsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.paul.sprintsync.chirp_sync.AcousticChirpSyncEngine
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.NearbyEvent
import com.paul.sprintsync.core.services.NearbyConnectionsManager
import com.paul.sprintsync.core.services.NearbyTransportStrategy
import com.paul.sprintsync.features.motion_detection.MotionCameraFacing
import com.paul.sprintsync.features.motion_detection.MotionDetectionController
import com.paul.sprintsync.features.race_session.RaceSessionController
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.sensor_native.SensorNativeController
import com.paul.sprintsync.sensor_native.SensorNativeEvent

class MainActivity : ComponentActivity(), ActivityCompat.OnRequestPermissionsResultCallback {
    companion object {
        private const val DEFAULT_SERVICE_ID = "com.paul.sprintsync.nearby"
        private const val PERMISSIONS_REQUEST_CODE = 7301
        private const val SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS = 3_000_000_000L
    }

    private lateinit var sensorNativeController: SensorNativeController
    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var motionDetectionController: MotionDetectionController
    private lateinit var raceSessionController: RaceSessionController
    private val uiState = mutableStateOf(SprintSyncUiState())
    private var pendingPermissionAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorNativeController = SensorNativeController(this)
        val localRepository = LocalRepository(this)
        val chirpSyncEngine = AcousticChirpSyncEngine(this)
        nearbyConnectionsManager = NearbyConnectionsManager(
            context = this,
            nowNativeClockSyncElapsedNanos = { requireSensorDomain ->
                sensorNativeController.currentClockSyncElapsedNanos(
                    maxSensorSampleAgeNanos = SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS,
                    requireSensorDomain = requireSensorDomain,
                )
            },
        )
        motionDetectionController = MotionDetectionController(
            localRepository = localRepository,
            sensorNativeController = sensorNativeController,
        )
        raceSessionController = RaceSessionController(
            localRepository = localRepository,
            nearbyConnectionsManager = nearbyConnectionsManager,
            chirpSyncEngine = chirpSyncEngine,
        )
        raceSessionController.setLocalDeviceIdentity(localDeviceId(), localEndpointName())
        sensorNativeController.setEventListener(::onSensorEvent)
        nearbyConnectionsManager.setEventListener(::onNearbyEvent)

        val denied = deniedPermissions()
        updateUiState {
            copy(
                permissionGranted = denied.isEmpty(),
                deniedPermissions = denied,
                networkSummary = "Ready",
            )
        }

        setContent {
            SprintSyncApp(
                uiState = uiState.value,
                onRequestPermissions = {
                    requestPermissionsIfNeeded {}
                },
                onConnectEndpoint = { endpointId ->
                    nearbyConnectionsManager.requestConnection(
                        endpointId = endpointId,
                        endpointName = localEndpointName(),
                    ) { result ->
                        result.exceptionOrNull()?.let { error ->
                            appendEvent("connect error: ${error.localizedMessage ?: "unknown"}")
                        }
                        syncControllerSummaries()
                    }
                },
                onGoToLobby = {
                    raceSessionController.goToLobby()
                    syncControllerSummaries()
                },
                onStartHosting = {
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.HOST)
                        nearbyConnectionsManager.configureNativeClockSyncHost(
                            enabled = true,
                            requireSensorDomainClock = false,
                        )
                        nearbyConnectionsManager.startHosting(
                            serviceId = DEFAULT_SERVICE_ID,
                            endpointName = localEndpointName(),
                            strategy = NearbyTransportStrategy.STAR,
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("host error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                },
                onStartHostingPointToPoint = {
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.HOST)
                        nearbyConnectionsManager.configureNativeClockSyncHost(
                            enabled = true,
                            requireSensorDomainClock = false,
                        )
                        nearbyConnectionsManager.startHosting(
                            serviceId = DEFAULT_SERVICE_ID,
                            endpointName = localEndpointName(),
                            strategy = NearbyTransportStrategy.POINT_TO_POINT,
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("host p2p error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                },
                onStartDiscovery = {
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
                        nearbyConnectionsManager.startDiscovery(
                            serviceId = DEFAULT_SERVICE_ID,
                            strategy = NearbyTransportStrategy.STAR,
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("discovery error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                },
                onStartDiscoveryPointToPoint = {
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
                        nearbyConnectionsManager.startDiscovery(
                            serviceId = DEFAULT_SERVICE_ID,
                            strategy = NearbyTransportStrategy.POINT_TO_POINT,
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("discovery p2p error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                },
                onStartMonitoring = {
                    requestPermissionsIfNeeded {
                        val started = raceSessionController.startMonitoring()
                        if (started && shouldRunLocalMonitoring()) {
                            applyLocalMonitoringConfigFromSession()
                            motionDetectionController.startMonitoring()
                        }
                        syncControllerSummaries()
                    }
                },
                onStopMonitoring = {
                    if (motionDetectionController.uiState.value.monitoring) {
                        motionDetectionController.stopMonitoring()
                    }
                    raceSessionController.stopMonitoring()
                    syncControllerSummaries()
                },
                onAssignRole = { deviceId, role ->
                    raceSessionController.assignRole(deviceId, role)
                    syncControllerSummaries()
                },
                onAssignCameraFacing = { deviceId, facing ->
                    raceSessionController.assignCameraFacing(deviceId, facing)
                    syncControllerSummaries()
                },
                onAssignHighSpeedEnabled = { deviceId, enabled ->
                    raceSessionController.assignHighSpeedEnabled(deviceId, enabled)
                    syncControllerSummaries()
                },
                onClockSyncBurst = {
                    val endpointId = firstConnectedEndpointId()
                    if (endpointId == null) {
                        appendEvent("clock sync ignored: no connected endpoint")
                    } else {
                        raceSessionController.startClockSyncBurst(endpointId)
                    }
                    syncControllerSummaries()
                },
                onChirpSync = {
                    val endpointId = firstConnectedEndpointId()
                    if (endpointId == null) {
                        appendEvent("chirp sync ignored: no connected endpoint")
                    } else {
                        raceSessionController.startChirpSync(endpointId)
                    }
                    syncControllerSummaries()
                },
                onStopAll = {
                    nearbyConnectionsManager.stopAll()
                    raceSessionController.setNetworkRole(SessionNetworkRole.NONE)
                    if (motionDetectionController.uiState.value.monitoring) {
                        motionDetectionController.stopMonitoring()
                    }
                    updateUiState { copy(networkSummary = "Stopped") }
                    appendEvent("all nearby sessions stopped")
                    syncControllerSummaries()
                },
            )
        }
    }

    override fun onPause() {
        sensorNativeController.onHostPaused()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        sensorNativeController.onHostResumed()
    }

    override fun onDestroy() {
        nearbyConnectionsManager.stopAll()
        nearbyConnectionsManager.setEventListener(null)
        sensorNativeController.setEventListener(null)
        sensorNativeController.dispose()
        super.onDestroy()
    }

    private fun requestPermissionsIfNeeded(onGranted: () -> Unit) {
        val denied = deniedPermissions()
        if (denied.isEmpty()) {
            updateUiState { copy(permissionGranted = true, deniedPermissions = emptyList()) }
            onGranted()
            return
        }
        pendingPermissionAction = onGranted
        ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return
        }
        val denied = deniedPermissions()
        val granted = denied.isEmpty()
        updateUiState {
            copy(
                permissionGranted = granted,
                deniedPermissions = denied,
            )
        }
        if (granted) {
            pendingPermissionAction?.invoke()
        } else {
            appendEvent("permissions denied: ${denied.joinToString()}")
        }
        pendingPermissionAction = null
    }

    private fun onNearbyEvent(event: NearbyEvent) {
        raceSessionController.onNearbyEvent(event)
        val type = when (event) {
            is NearbyEvent.EndpointFound -> "endpoint_found"
            is NearbyEvent.EndpointLost -> "endpoint_lost"
            is NearbyEvent.ConnectionResult -> "connection_result"
            is NearbyEvent.EndpointDisconnected -> "endpoint_disconnected"
            is NearbyEvent.PayloadReceived -> "payload_received"
            is NearbyEvent.Error -> "error"
        }
        val connectedCount = nearbyConnectionsManager.connectedEndpoints().size
        val role = nearbyConnectionsManager.currentRole().name.lowercase()
        updateUiState {
            copy(
                networkSummary = "$role mode, $connectedCount connected",
                lastNearbyEvent = type,
            )
        }
        syncControllerSummaries()
        appendEvent("nearby:$type")
    }

    private fun onSensorEvent(event: SensorNativeEvent) {
        motionDetectionController.handleSensorEvent(event)
        val localOffsetNanos = when (event) {
            is SensorNativeEvent.FrameStats -> event.hostSensorMinusElapsedNanos
            is SensorNativeEvent.State -> event.hostSensorMinusElapsedNanos
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        if (localOffsetNanos != null) {
            val isHost = raceSessionController.uiState.value.networkRole == SessionNetworkRole.HOST
            raceSessionController.updateClockState(
                localSensorMinusElapsedNanos = localOffsetNanos,
                hostSensorMinusElapsedNanos = if (isHost) localOffsetNanos else raceSessionController.clockState.value.hostSensorMinusElapsedNanos,
            )
        }
        if (event is SensorNativeEvent.Trigger) {
            raceSessionController.onLocalMotionTrigger(
                triggerType = event.trigger.triggerType,
                splitIndex = event.trigger.splitIndex,
                triggerSensorNanos = event.trigger.triggerSensorNanos,
            )
        }
        val type = when (event) {
            is SensorNativeEvent.FrameStats -> "native_frame_stats"
            is SensorNativeEvent.Trigger -> "native_trigger"
            is SensorNativeEvent.State -> "native_state"
            is SensorNativeEvent.Diagnostic -> "native_diagnostic"
            is SensorNativeEvent.Error -> "native_error"
        }
        updateUiState { copy(lastSensorEvent = type) }
        syncControllerSummaries()
        appendEvent("sensor:$type")
    }

    private fun firstConnectedEndpointId(): String? {
        return nearbyConnectionsManager.connectedEndpoints().firstOrNull()
    }

    private fun syncControllerSummaries() {
        val raceState = raceSessionController.uiState.value
        val clockState = raceSessionController.clockState.value
        val motionBefore = motionDetectionController.uiState.value

        if (raceState.monitoringActive && !motionBefore.monitoring && shouldRunLocalMonitoring()) {
            applyLocalMonitoringConfigFromSession()
            motionDetectionController.startMonitoring()
        }
        if (!raceState.monitoringActive && motionBefore.monitoring) {
            motionDetectionController.stopMonitoring()
        }

        val motionState = motionDetectionController.uiState.value

        val monitoringSummary = if (motionState.monitoring) {
            "Monitoring"
        } else {
            "Idle"
        }
        val clockSummary = when {
            raceSessionController.hasFreshClockLock() && clockState.hostMinusClientElapsedNanos != null -> {
                "Locked ${clockState.hostMinusClientElapsedNanos}ns"
            }

            clockState.hostMinusClientElapsedNanos != null -> {
                "Stale ${clockState.hostMinusClientElapsedNanos}ns"
            }

            else -> "Unlocked"
        }
        val chirpSummary = when {
            raceSessionController.hasFreshChirpLock() && clockState.chirpHostMinusClientElapsedNanos != null -> {
                "Locked ${clockState.chirpHostMinusClientElapsedNanos}ns"
            }

            clockState.chirpHostMinusClientElapsedNanos != null -> {
                "Stale ${clockState.chirpHostMinusClientElapsedNanos}ns"
            }

            else -> "Unlocked"
        }
        updateUiState {
            copy(
                stage = raceState.stage,
                networkRole = raceState.networkRole,
                sessionSummary = raceState.stage.name.lowercase(),
                monitoringSummary = monitoringSummary,
                clockSummary = clockSummary,
                chirpSummary = chirpSummary,
                startedSensorNanos = raceState.timeline.hostStartSensorNanos,
                splitSensorNanos = raceState.timeline.hostSplitSensorNanos,
                stoppedSensorNanos = raceState.timeline.hostStopSensorNanos,
                devices = raceState.devices,
                canGoToLobby = raceSessionController.canGoToLobby(),
                canStartMonitoring = raceSessionController.canStartMonitoring(),
                canShowSplitControls = raceSessionController.canShowSplitControls(),
                discoveredEndpoints = raceState.discoveredEndpoints,
                connectedEndpoints = raceState.connectedEndpoints,
                networkSummary = "${nearbyConnectionsManager.currentRole().name.lowercase()} mode, ${raceState.connectedEndpoints.size} connected",
            )
        }
    }

    private fun appendEvent(message: String) {
        val previous = uiState.value.recentEvents
        val updated = (listOf(message) + previous).take(10)
        updateUiState { copy(recentEvents = updated) }
    }

    private fun deniedPermissions(): List<String> {
        return requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        return permissions.distinct()
    }

    private fun localEndpointName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        if (model.isNotEmpty()) {
            return model
        }
        val device = Build.DEVICE?.trim().orEmpty()
        if (device.isNotEmpty()) {
            return device
        }
        return "Android Device"
    }

    private fun localDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
        if (androidId.isNotEmpty()) {
            return "android-$androidId"
        }
        return "local-${Build.DEVICE.orEmpty()}"
    }

    private fun shouldRunLocalMonitoring(): Boolean {
        return raceSessionController.localDeviceRole() != SessionDeviceRole.UNASSIGNED
    }

    private fun applyLocalMonitoringConfigFromSession() {
        val current = motionDetectionController.uiState.value.config
        val cameraFacing = when (raceSessionController.localCameraFacing()) {
            SessionCameraFacing.FRONT -> MotionCameraFacing.FRONT
            SessionCameraFacing.REAR -> MotionCameraFacing.REAR
        }
        val next = current.copy(
            cameraFacing = cameraFacing,
            highSpeedEnabled = raceSessionController.localHighSpeedEnabled(),
        )
        motionDetectionController.updateConfig(next)
    }

    private fun updateUiState(update: SprintSyncUiState.() -> SprintSyncUiState) {
        uiState.value = uiState.value.update()
    }
}
