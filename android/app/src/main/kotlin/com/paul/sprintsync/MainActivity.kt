package com.paul.sprintsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.NearbyEvent
import com.paul.sprintsync.core.services.NearbyConnectionsManager
import com.paul.sprintsync.core.services.NearbyTransportStrategy
import com.paul.sprintsync.features.motion_detection.MotionCameraFacing
import com.paul.sprintsync.features.motion_detection.MotionDetectionController
import com.paul.sprintsync.features.race_session.RaceSessionController
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.features.race_session.SessionLapResultMessage
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionOperatingMode
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.sensor_native.SensorNativeController
import com.paul.sprintsync.sensor_native.SensorNativeEvent
import com.paul.sprintsync.sensor_native.SensorNativePreviewViewFactory
import com.paul.sprintsync.features.race_session.sessionDeviceRoleLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), ActivityCompat.OnRequestPermissionsResultCallback {
    companion object {
        private const val DEFAULT_SERVICE_ID = "sync.sprint.nearby"
        private const val PERMISSIONS_REQUEST_CODE = 7301
        private const val SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS = 3_000_000_000L
        private const val TIMER_REFRESH_INTERVAL_MS = 100L
        private const val TAG = "SprintSyncRuntime"
    }

    private lateinit var sensorNativeController: SensorNativeController
    private lateinit var nearbyConnectionsManager: NearbyConnectionsManager
    private lateinit var motionDetectionController: MotionDetectionController
    private lateinit var raceSessionController: RaceSessionController
    private lateinit var previewViewFactory: SensorNativePreviewViewFactory
    private val uiState = mutableStateOf(SprintSyncUiState())
    private var pendingPermissionAction: (() -> Unit)? = null
    private var timerRefreshJob: Job? = null
    private var isAppResumed: Boolean = false
    private var localCaptureStartPending: Boolean = false
    private var userMonitoringEnabled: Boolean = true
    private var displayDiscoveryActive: Boolean = false
    private var displayConnectedHostEndpointId: String? = null
    private var displayConnectedHostName: String? = null
    private val displayDiscoveredHosts = linkedMapOf<String, String>()
    private val displayLatestLapByDevice = linkedMapOf<String, Long>()
    private var lastRelayedStopSensorNanos: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorNativeController = SensorNativeController(this)
        val localRepository = LocalRepository(this)
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
        previewViewFactory = SensorNativePreviewViewFactory(sensorNativeController)
        raceSessionController = RaceSessionController(
            localRepository = localRepository,
            nearbyConnectionsManager = nearbyConnectionsManager,
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
            com.paul.sprintsync.ui.theme.SprintSyncTheme {
                SprintSyncApp(
                uiState = uiState.value,
                previewViewFactory = previewViewFactory,
                onRequestPermissions = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        setSetupBusy(false)
                    }
                },
                onStartHosting = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        displayDiscoveryActive = false
                        displayConnectedHostEndpointId = null
                        displayConnectedHostName = null
                        displayDiscoveredHosts.clear()
                        raceSessionController.setNetworkRole(SessionNetworkRole.HOST)
                        nearbyConnectionsManager.configureNativeClockSyncHost(
                            enabled = true,
                            requireSensorDomainClock = false,
                        )
                        try {
                            nearbyConnectionsManager.startHosting(
                                serviceId = DEFAULT_SERVICE_ID,
                                endpointName = localEndpointName(),
                                strategy = NearbyTransportStrategy.POINT_TO_POINT,
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("host error: ${error.localizedMessage ?: "unknown"}")
                                }
                                setSetupBusy(false)
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            appendEvent("host error: ${error.localizedMessage ?: "unknown"}")
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    }
                },
                onStartDiscovery = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        displayDiscoveryActive = false
                        displayConnectedHostEndpointId = null
                        displayConnectedHostName = null
                        displayDiscoveredHosts.clear()
                        raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
                        try {
                            nearbyConnectionsManager.startDiscovery(
                                serviceId = DEFAULT_SERVICE_ID,
                                strategy = NearbyTransportStrategy.POINT_TO_POINT,
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("discovery error: ${error.localizedMessage ?: "unknown"}")
                                }
                                setSetupBusy(false)
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            appendEvent("discovery error: ${error.localizedMessage ?: "unknown"}")
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    }
                },
                onStartSingleDevice = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        nearbyConnectionsManager.stopAll()
                        displayDiscoveryActive = false
                        displayConnectedHostEndpointId = null
                        displayConnectedHostName = null
                        displayDiscoveredHosts.clear()
                        lastRelayedStopSensorNanos = null
                        raceSessionController.startSingleDeviceMonitoring()
                        userMonitoringEnabled = true
                        setSetupBusy(false)
                        syncControllerSummaries()
                    }
                },
                onStartDisplayHost = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        raceSessionController.startDisplayHostMode()
                        displayLatestLapByDevice.clear()
                        displayDiscoveryActive = false
                        displayConnectedHostEndpointId = null
                        displayConnectedHostName = null
                        displayDiscoveredHosts.clear()
                        nearbyConnectionsManager.configureNativeClockSyncHost(
                            enabled = false,
                            requireSensorDomainClock = false,
                        )
                        try {
                            nearbyConnectionsManager.startHosting(
                                serviceId = DEFAULT_SERVICE_ID,
                                endpointName = localEndpointName(),
                                strategy = NearbyTransportStrategy.POINT_TO_STAR,
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("display host error: ${error.localizedMessage ?: "unknown"}")
                                }
                                setSetupBusy(false)
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            appendEvent("display host error: ${error.localizedMessage ?: "unknown"}")
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    }
                },
                onStartMonitoring = {
                    requestPermissionsIfNeeded {
                        val started = raceSessionController.startMonitoring()
                        if (started) {
                            userMonitoringEnabled = true
                        }

                        logRuntimeDiagnostic(
                            "startMonitoring requested: started=$started role=${raceSessionController.localDeviceRole().name} " +
                                "shouldRunLocal=${shouldRunLocalMonitoring()} resumed=$isAppResumed",
                        )
                        syncControllerSummaries()
                    }
                },
                onStartDisplayDiscovery = {
                    requestPermissionsIfNeeded {
                        displayDiscoveryActive = true
                        displayDiscoveredHosts.clear()
                        try {
                            nearbyConnectionsManager.startDiscovery(
                                serviceId = DEFAULT_SERVICE_ID,
                                strategy = NearbyTransportStrategy.POINT_TO_STAR,
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("display discovery error: ${error.localizedMessage ?: "unknown"}")
                                    displayDiscoveryActive = false
                                }
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            displayDiscoveryActive = false
                            appendEvent("display discovery error: ${error.localizedMessage ?: "unknown"}")
                            syncControllerSummaries()
                        }
                    }
                },
                onConnectDisplayHost = { endpointId ->
                    try {
                        nearbyConnectionsManager.requestConnection(
                            endpointId = endpointId,
                            endpointName = localEndpointName(),
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("display connect error: ${error.localizedMessage ?: "unknown"}")
                            }
                            syncControllerSummaries()
                        }
                    } catch (error: Throwable) {
                        appendEvent("display connect error: ${error.localizedMessage ?: "unknown"}")
                        syncControllerSummaries()
                    }
                },
                onSetMonitoringEnabled = { enabled ->
                    userMonitoringEnabled = enabled
                    if (!enabled) {
                        localCaptureStartPending = false
                        motionDetectionController.stopMonitoring()
                    }
                    syncControllerSummaries()
                },
                onStopMonitoring = {
                    logRuntimeDiagnostic("stopMonitoring requested")
                    when (uiState.value.operatingMode) {
                        SessionOperatingMode.SINGLE_DEVICE -> {
                            raceSessionController.stopSingleDeviceMonitoring()
                            nearbyConnectionsManager.stopAll()
                            displayDiscoveryActive = false
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            displayDiscoveredHosts.clear()
                        }
                        SessionOperatingMode.DISPLAY_HOST -> {
                            raceSessionController.stopDisplayHostMode()
                            nearbyConnectionsManager.stopAll()
                            displayLatestLapByDevice.clear()
                        }
                        SessionOperatingMode.NETWORK_RACE -> raceSessionController.stopMonitoring()
                    }
                    syncControllerSummaries()
                },
                onResetRun = {
                    raceSessionController.resetRun()
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
                onUpdateThreshold = { value ->
                    motionDetectionController.updateThreshold(value)
                    syncControllerSummaries()
                },
                onUpdateRoiCenter = { value ->
                    motionDetectionController.updateRoiCenter(value)
                    syncControllerSummaries()
                },
                onUpdateRoiWidth = { value ->
                    motionDetectionController.updateRoiWidth(value)
                    syncControllerSummaries()
                },
                onUpdateCooldown = { value ->
                    motionDetectionController.updateCooldown(value)
                    syncControllerSummaries()
                },
                onStopHosting = {
                    if (uiState.value.operatingMode == SessionOperatingMode.DISPLAY_HOST) {
                        raceSessionController.stopDisplayHostMode()
                        displayLatestLapByDevice.clear()
                    } else {
                        raceSessionController.stopHostingAndReturnToSetup()
                    }
                    nearbyConnectionsManager.stopAll()
                    if (motionDetectionController.uiState.value.monitoring) {
                        motionDetectionController.stopMonitoring()
                    }
                    displayDiscoveryActive = false
                    displayConnectedHostEndpointId = null
                    displayConnectedHostName = null
                    displayDiscoveredHosts.clear()
                    updateUiState { copy(networkSummary = "Stopped") }
                    appendEvent("hosting stopped")
                    syncControllerSummaries()
                },
            )
            }
        }
    }

    override fun onPause() {
        isAppResumed = false
        stopTimerRefreshLoop()
        logRuntimeDiagnostic("host paused")
        sensorNativeController.onHostPaused()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isAppResumed = true
        logRuntimeDiagnostic("host resumed")
        sensorNativeController.onHostResumed()
        syncControllerSummaries()
    }

    override fun onDestroy() {
        stopTimerRefreshLoop()
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
            setSetupBusy(false)
            appendEvent("permissions denied: ${denied.joinToString()}")
        }
        pendingPermissionAction = null
    }

    private fun setSetupBusy(busy: Boolean) {
        updateUiState { copy(setupBusy = busy) }
    }

    private fun onNearbyEvent(event: NearbyEvent) {
        when (uiState.value.operatingMode) {
            SessionOperatingMode.NETWORK_RACE -> {
                raceSessionController.onNearbyEvent(event)
                val state = raceSessionController.uiState.value
                if (event is NearbyEvent.EndpointFound) {
                    val role = state.networkRole
                    if (role == SessionNetworkRole.CLIENT && state.connectedEndpoints.isEmpty()) {
                        try {
                            nearbyConnectionsManager.requestConnection(
                                endpointId = event.endpointId,
                                endpointName = localEndpointName(),
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("auto-connect error: ${error.localizedMessage}")
                                }
                            }
                        } catch (e: Exception) {
                            appendEvent("auto-connect error: ${e.localizedMessage}")
                        }
                    }
                } else if (event is NearbyEvent.EndpointDisconnected) {
                    if (state.networkRole == SessionNetworkRole.NONE) {
                        nearbyConnectionsManager.stopAll()
                    }
                }
            }
            SessionOperatingMode.SINGLE_DEVICE -> {
                when (event) {
                    is NearbyEvent.EndpointFound -> {
                        displayDiscoveredHosts[event.endpointId] = event.endpointName
                        if (displayConnectedHostEndpointId == null) {
                            try {
                                nearbyConnectionsManager.requestConnection(
                                    endpointId = event.endpointId,
                                    endpointName = localEndpointName(),
                                ) { result ->
                                    result.exceptionOrNull()?.let { error ->
                                        appendEvent("auto-display-connect error: ${error.localizedMessage ?: "unknown"}")
                                    }
                                }
                            } catch (error: Throwable) {
                                appendEvent("auto-display-connect error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                    is NearbyEvent.EndpointLost -> {
                        displayDiscoveredHosts.remove(event.endpointId)
                    }
                    is NearbyEvent.ConnectionResult -> {
                        if (event.connected) {
                            displayConnectedHostEndpointId = event.endpointId
                            displayConnectedHostName = event.endpointName ?: displayDiscoveredHosts[event.endpointId]
                            displayDiscoveryActive = false
                        } else if (displayConnectedHostEndpointId == event.endpointId) {
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                        }
                    }
                    is NearbyEvent.EndpointDisconnected -> {
                        if (displayConnectedHostEndpointId == event.endpointId) {
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                        }
                    }
                    is NearbyEvent.PayloadReceived, is NearbyEvent.Error -> Unit
                }
            }
            SessionOperatingMode.DISPLAY_HOST -> {
                when (event) {
                    is NearbyEvent.PayloadReceived -> {
                        SessionLapResultMessage.tryParse(event.message)?.let { result ->
                            val elapsedNanos = result.stoppedSensorNanos - result.startedSensorNanos
                            displayLatestLapByDevice[result.senderDeviceName] = elapsedNanos
                        }
                    }
                    is NearbyEvent.EndpointFound,
                    is NearbyEvent.EndpointLost,
                    is NearbyEvent.ConnectionResult,
                    is NearbyEvent.EndpointDisconnected,
                    is NearbyEvent.Error -> Unit
                }
            }
        }

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
        if (event is SensorNativeEvent.State || event is SensorNativeEvent.Error) {
            localCaptureStartPending = false
        }
        motionDetectionController.handleSensorEvent(event)
        val localOffsetNanos = when (event) {
            is SensorNativeEvent.FrameStats -> event.hostSensorMinusElapsedNanos
            is SensorNativeEvent.State -> event.hostSensorMinusElapsedNanos
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        val localGpsUtcOffsetNanos = when (event) {
            is SensorNativeEvent.FrameStats -> event.gpsUtcOffsetNanos
            is SensorNativeEvent.State -> event.gpsUtcOffsetNanos
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        val localGpsFixAgeNanos = when (event) {
            is SensorNativeEvent.FrameStats ->
                raceSessionController.computeGpsFixAgeNanos(event.gpsFixElapsedRealtimeNanos)
            is SensorNativeEvent.State ->
                raceSessionController.computeGpsFixAgeNanos(event.gpsFixElapsedRealtimeNanos)
            is SensorNativeEvent.Trigger -> null
            is SensorNativeEvent.Diagnostic -> null
            is SensorNativeEvent.Error -> null
        }
        if (localOffsetNanos != null) {
            val isHost = raceSessionController.uiState.value.networkRole == SessionNetworkRole.HOST
            raceSessionController.updateClockState(
                localSensorMinusElapsedNanos = localOffsetNanos,
                hostSensorMinusElapsedNanos = if (isHost) localOffsetNanos else raceSessionController.clockState.value.hostSensorMinusElapsedNanos,
                localGpsUtcOffsetNanos = localGpsUtcOffsetNanos
                    ?: raceSessionController.clockState.value.localGpsUtcOffsetNanos,
                localGpsFixAgeNanos = localGpsFixAgeNanos
                    ?: raceSessionController.clockState.value.localGpsFixAgeNanos,
                hostGpsUtcOffsetNanos = if (isHost) {
                    localGpsUtcOffsetNanos ?: raceSessionController.clockState.value.hostGpsUtcOffsetNanos
                } else {
                    raceSessionController.clockState.value.hostGpsUtcOffsetNanos
                },
                hostGpsFixAgeNanos = if (isHost) {
                    localGpsFixAgeNanos ?: raceSessionController.clockState.value.hostGpsFixAgeNanos
                } else {
                    raceSessionController.clockState.value.hostGpsFixAgeNanos
                },
            )
        } else if (localGpsUtcOffsetNanos != null || localGpsFixAgeNanos != null) {
            raceSessionController.updateClockState(
                localGpsUtcOffsetNanos = localGpsUtcOffsetNanos
                    ?: raceSessionController.clockState.value.localGpsUtcOffsetNanos,
                localGpsFixAgeNanos = localGpsFixAgeNanos
                    ?: raceSessionController.clockState.value.localGpsFixAgeNanos,
            )
        }
        if (event is SensorNativeEvent.Trigger) {
            raceSessionController.onLocalMotionTrigger(
                triggerType = event.trigger.triggerType,
                splitIndex = 0,
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
        val mode = raceState.operatingMode
        val shouldRunLocalCapture = shouldRunLocalMonitoring()

        if (raceState.stage == SessionStage.LOBBY || raceState.stage == SessionStage.MONITORING) {
            sensorNativeController.warmupGpsSync()
        }

        when (
            resolveLocalCaptureAction(
                monitoringActive = raceState.monitoringActive && mode != SessionOperatingMode.DISPLAY_HOST,
                isAppResumed = isAppResumed,
                shouldRunLocalCapture = shouldRunLocalCapture,
                isLocalMotionMonitoring = motionBefore.monitoring,
                localCaptureStartPending = localCaptureStartPending,
            )
        ) {
            LocalCaptureAction.START -> {
                localCaptureStartPending = true
                logRuntimeDiagnostic(
                    "local capture start: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                applyLocalMonitoringConfigFromSession()
                motionDetectionController.startMonitoring()
            }

            LocalCaptureAction.STOP -> {
                localCaptureStartPending = false
                logRuntimeDiagnostic(
                    "local capture stop: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                motionDetectionController.stopMonitoring()
            }

            LocalCaptureAction.NONE -> Unit
        }

        if (
            shouldKeepTimerRefreshActive(
                monitoringActive = raceState.monitoringActive && mode != SessionOperatingMode.DISPLAY_HOST,
                isAppResumed = isAppResumed,
                hasStopSensor = raceState.timeline.hostStopSensorNanos != null,
            )
        ) {
            startTimerRefreshLoop()
        } else {
            stopTimerRefreshLoop()
        }

        val motionState = motionDetectionController.uiState.value

        val monitoringSummary = if (motionState.monitoring) {
            "Monitoring"
        } else {
            "Idle"
        }
        val isHost = raceState.networkRole == SessionNetworkRole.HOST || mode == SessionOperatingMode.DISPLAY_HOST
        val isClient = raceState.networkRole == SessionNetworkRole.CLIENT
        val liveConnectedEndpoints = when (mode) {
            SessionOperatingMode.NETWORK_RACE -> raceState.connectedEndpoints
            SessionOperatingMode.SINGLE_DEVICE -> setOfNotNull(displayConnectedHostEndpointId)
            SessionOperatingMode.DISPLAY_HOST -> nearbyConnectionsManager.connectedEndpoints()
        }
        val hasPeers = liveConnectedEndpoints.isNotEmpty()
        val localRole = raceSessionController.localDeviceRole()
        val timelineForUi = if (
            mode == SessionOperatingMode.SINGLE_DEVICE &&
            raceState.timeline.hostStartSensorNanos == null &&
            raceState.latestCompletedTimeline != null
        ) {
            raceState.latestCompletedTimeline
        } else {
            raceState.timeline
        }

        if (mode == SessionOperatingMode.SINGLE_DEVICE) {
            val completed = raceState.latestCompletedTimeline
            val stopNanos = completed?.hostStopSensorNanos
            val startNanos = completed?.hostStartSensorNanos
            val hostEndpoint = displayConnectedHostEndpointId
            if (
                hostEndpoint != null &&
                startNanos != null &&
                stopNanos != null &&
                stopNanos != lastRelayedStopSensorNanos
            ) {
                val payload = SessionLapResultMessage(
                    senderDeviceName = localEndpointName(),
                    startedSensorNanos = startNanos,
                    stoppedSensorNanos = stopNanos,
                ).toJsonString()
                nearbyConnectionsManager.sendMessage(hostEndpoint, payload) { result ->
                    result.exceptionOrNull()?.let { error ->
                        appendEvent("lap relay error: ${error.localizedMessage ?: "unknown"}")
                    }
                }
                lastRelayedStopSensorNanos = stopNanos
            }
        }

        val monitoringSyncMode = when {
            !isClient || !hasPeers || raceState.stage == SessionStage.SETUP -> "-"
            raceSessionController.hasFreshGpsLock() -> "GPS"
            raceSessionController.hasFreshClockLock() -> "NTP"
            else -> "-"
        }
        val monitoringLatencyMs = if (
            isClient &&
            hasPeers &&
            monitoringSyncMode == "NTP" &&
            clockState.hostClockRoundTripNanos != null
        ) {
            (clockState.hostClockRoundTripNanos.toDouble() / 1_000_000.0).roundToInt()
        } else {
            null
        }

        val clockLockWarningText = if (
            isClient &&
            raceState.monitoringActive &&
            hasPeers &&
            localRole != SessionDeviceRole.UNASSIGNED &&
            !raceSessionController.hasFreshAnyClockLock()
        ) {
            "Clock sync lock is invalid. Triggers from this device are being dropped until sync recovers."
        } else {
            null
        }

        val runStatusLabel = when {
            timelineForUi.hostStartSensorNanos == null -> "Ready"
            timelineForUi.hostStopSensorNanos != null -> "Finished"
            raceState.monitoringActive -> "Running"
            else -> "Armed"
        }
        val marksCount = if (timelineForUi.hostStopSensorNanos != null) 1 else 0

        val elapsedDisplay = formatElapsedDisplay(
            startedSensorNanos = timelineForUi.hostStartSensorNanos,
            stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
            monitoringActive = raceState.monitoringActive,
        )

        val cameraModeLabel = if (motionState.observedFps == null) "INIT" else "NORMAL"
        val triggerHistory = motionState.triggerHistory.map { trigger ->
            val roleLabel = when (trigger.triggerType.lowercase()) {
                "start" -> "START"
                "stop" -> "STOP"
                else -> trigger.triggerType.uppercase()
            }
            "$roleLabel at ${trigger.triggerSensorNanos}ns (score ${"%.4f".format(trigger.score)})"
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
        val displayLapRows = displayLatestLapByDevice.entries.map { entry ->
            DisplayLapRow(
                deviceName = entry.key,
                lapTimeLabel = formatElapsedDuration(entry.value),
            )
        }
        updateUiState {
            copy(
                stage = raceState.stage,
                operatingMode = mode,
                networkRole = raceState.networkRole,
                sessionSummary = raceState.stage.name.lowercase(),
                monitoringSummary = monitoringSummary,
                userMonitoringEnabled = userMonitoringEnabled,
                clockSummary = clockSummary,
                startedSensorNanos = timelineForUi.hostStartSensorNanos,
                stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
                devices = raceState.devices,
                canStartMonitoring = mode == SessionOperatingMode.NETWORK_RACE && raceSessionController.canStartMonitoring(),
                isHost = isHost,
                localRole = localRole,
                monitoringConnectionTypeLabel = if (hasPeers) "Nearby (auto BT/Wi-Fi Direct)" else "-",
                monitoringSyncModeLabel = monitoringSyncMode,
                monitoringLatencyMs = monitoringLatencyMs,
                hasConnectedPeers = hasPeers,
                clockLockWarningText = clockLockWarningText,
                runStatusLabel = runStatusLabel,
                runMarksCount = marksCount,
                elapsedDisplay = elapsedDisplay,
                threshold = motionState.config.threshold,
                roiCenterX = motionState.config.roiCenterX,
                roiWidth = motionState.config.roiWidth,
                cooldownMs = motionState.config.cooldownMs,
                processEveryNFrames = motionState.config.processEveryNFrames,
                observedFps = motionState.observedFps,
                cameraFpsModeLabel = cameraModeLabel,
                targetFpsUpper = motionState.targetFpsUpper,
                rawScore = motionState.rawScore,
                baseline = motionState.baseline,
                effectiveScore = motionState.effectiveScore,
                frameSensorNanos = motionState.lastFrameSensorNanos,
                streamFrameCount = motionState.streamFrameCount,
                processedFrameCount = motionState.processedFrameCount,
                triggerHistory = triggerHistory,
                discoveredEndpoints = if (mode == SessionOperatingMode.SINGLE_DEVICE) {
                    displayDiscoveredHosts.toMap()
                } else {
                    raceState.discoveredEndpoints
                },
                connectedEndpoints = liveConnectedEndpoints,
                networkSummary = "${nearbyConnectionsManager.currentRole().name.lowercase()} mode, ${liveConnectedEndpoints.size} connected",
                displayLapRows = displayLapRows,
                displayConnectedHostName = displayConnectedHostName,
                displayDiscoveryActive = displayDiscoveryActive,
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
        val mode = raceSessionController.uiState.value.operatingMode
        if (mode == SessionOperatingMode.DISPLAY_HOST) {
            return false
        }
        return userMonitoringEnabled && raceSessionController.localDeviceRole() != SessionDeviceRole.UNASSIGNED
    }

    private fun applyLocalMonitoringConfigFromSession() {
        val current = motionDetectionController.uiState.value.config
        val cameraFacing = when (raceSessionController.localCameraFacing()) {
            SessionCameraFacing.FRONT -> MotionCameraFacing.FRONT
            SessionCameraFacing.REAR -> MotionCameraFacing.REAR
        }
        val next = current.copy(
            cameraFacing = cameraFacing,
        )
        motionDetectionController.updateConfig(next)
    }

    private fun formatElapsedDisplay(
        startedSensorNanos: Long?,
        stoppedSensorNanos: Long?,
        monitoringActive: Boolean,
    ): String {
        val started = startedSensorNanos ?: return "00:00.000"
        val terminal = stoppedSensorNanos ?: if (monitoringActive) {
            raceSessionController.estimateLocalSensorNanosNow()
        } else {
            started
        }
        val elapsedNanos = (terminal - started).coerceAtLeast(0L)
        val totalMillis = elapsedNanos / 1_000_000L
        val minutes = totalMillis / 60_000L
        val seconds = (totalMillis % 60_000L) / 1_000L
        val millis = totalMillis % 1_000L
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun formatElapsedDuration(durationNanos: Long): String {
        val totalMillis = (durationNanos / 1_000_000L).coerceAtLeast(0L)
        val minutes = totalMillis / 60_000L
        val seconds = (totalMillis % 60_000L) / 1_000L
        val millis = totalMillis % 1_000L
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun updateUiState(update: SprintSyncUiState.() -> SprintSyncUiState) {
        uiState.value = uiState.value.update()
    }

    private fun startTimerRefreshLoop() {
        if (timerRefreshJob?.isActive == true) {
            return
        }
        logRuntimeDiagnostic("timer refresh loop started")
        timerRefreshJob = lifecycleScope.launch {
            try {
                while (isActive) {
                    val raceState = raceSessionController.uiState.value
                    if (!isAppResumed || !raceState.monitoringActive) {
                        break
                    }
                    if (raceState.timeline.hostStartSensorNanos != null &&
                        raceState.timeline.hostStopSensorNanos == null
                    ) {
                        syncControllerSummaries()
                    }
                    delay(TIMER_REFRESH_INTERVAL_MS)
                }
            } finally {
                logRuntimeDiagnostic("timer refresh loop stopped")
                timerRefreshJob = null
            }
        }
    }

    private fun stopTimerRefreshLoop() {
        timerRefreshJob?.cancel()
        timerRefreshJob = null
    }

    private fun logRuntimeDiagnostic(message: String) {
        Log.d(TAG, "diag: $message")
    }
}

internal enum class LocalCaptureAction {
    START,
    STOP,
    NONE,
}

internal fun resolveLocalCaptureAction(
    monitoringActive: Boolean,
    isAppResumed: Boolean,
    shouldRunLocalCapture: Boolean,
    isLocalMotionMonitoring: Boolean,
    localCaptureStartPending: Boolean,
): LocalCaptureAction {
    if (
        monitoringActive &&
        isAppResumed &&
        shouldRunLocalCapture &&
        !isLocalMotionMonitoring &&
        !localCaptureStartPending
    ) {
        return LocalCaptureAction.START
    }
    if (
        (isLocalMotionMonitoring || localCaptureStartPending) &&
        (!monitoringActive || !isAppResumed || !shouldRunLocalCapture)
    ) {
        return LocalCaptureAction.STOP
    }
    return LocalCaptureAction.NONE
}

internal fun shouldKeepTimerRefreshActive(
    monitoringActive: Boolean,
    isAppResumed: Boolean,
    hasStopSensor: Boolean,
): Boolean {
    return monitoringActive && isAppResumed && !hasStopSensor
}
