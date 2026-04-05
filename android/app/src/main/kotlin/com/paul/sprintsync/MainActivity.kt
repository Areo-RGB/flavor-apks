package com.paul.sprintsync

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.paul.sprintsync.core.models.SavedRunResult
import com.paul.sprintsync.core.models.SavedRunCheckpointResult
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.SessionConnectionEvent
import com.paul.sprintsync.core.services.SessionConnectionStrategy
import com.paul.sprintsync.core.services.SessionConnectionsManager
import com.paul.sprintsync.core.services.TcpConnectionsManager
import com.paul.sprintsync.features.motion_detection.MotionCameraFacing
import com.paul.sprintsync.features.motion_detection.MotionDetectionController
import com.paul.sprintsync.features.race_session.RaceSessionController
import com.paul.sprintsync.features.race_session.RaceSessionUiState
import com.paul.sprintsync.features.race_session.SessionCameraFacing
import com.paul.sprintsync.features.race_session.SessionClockLockReason
import com.paul.sprintsync.features.race_session.SessionDeviceRole
import com.paul.sprintsync.features.race_session.SessionLapResultMessage
import com.paul.sprintsync.features.race_session.SessionNetworkRole
import com.paul.sprintsync.features.race_session.SessionOperatingMode
import com.paul.sprintsync.features.race_session.SessionAnchorState
import com.paul.sprintsync.features.race_session.SessionSplitMark
import com.paul.sprintsync.features.race_session.SessionStage
import com.paul.sprintsync.features.race_session.explicitSplitRoles
import com.paul.sprintsync.features.race_session.isSplitCheckpointRole
import com.paul.sprintsync.features.race_session.splitIndexForRole
import com.paul.sprintsync.features.race_session.sessionDeviceRoleLabel
import com.paul.sprintsync.sensor_native.SensorNativeController
import com.paul.sprintsync.sensor_native.SensorNativeEvent
import com.paul.sprintsync.sensor_native.SensorNativePreviewViewFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), ActivityCompat.OnRequestPermissionsResultCallback {
    companion object {
        private const val DEFAULT_SERVICE_ID = "sync.sprint.tcp"
        private const val PC_RECONNECT_ENDPOINT_ID = "192.168.0.100"
        private const val PERMISSIONS_REQUEST_CODE = 7301
        private const val SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS = 3_000_000_000L
        private const val TIMER_REFRESH_INTERVAL_MS = 100L
        private const val TAG = "SprintSyncRuntime"
        private const val MAX_PENDING_LAPS = 100
        private const val LOCAL_CAPTURE_RETRY_BACKOFF_MS = 1_500L
        private const val LOCAL_CAPTURE_FRAME_WATCHDOG_MS = 2_500L
        private const val LOW_LATENCY_WIFI_MIN_API = 29
        private const val MONITORING_WIFI_LOCK_TAG = "SprintSyncMonitoringWifiLock"
    }

    private lateinit var sensorNativeController: SensorNativeController
    private lateinit var connectionsManager: SessionConnectionsManager
    private lateinit var motionDetectionController: MotionDetectionController
    private lateinit var raceSessionController: RaceSessionController
    private lateinit var previewViewFactory: SensorNativePreviewViewFactory
    private lateinit var localRepository: LocalRepository
    private val uiState = mutableStateOf(SprintSyncUiState())
    private var pendingPermissionAction: (() -> Unit)? = null
    private var timerRefreshJob: Job? = null
    private var isAppResumed: Boolean = false
    private var localCaptureStartPending: Boolean = false
    private var localCaptureRetryBlockedUntilMs: Long = 0L
    private var userMonitoringEnabled: Boolean = true
    private var displayDiscoveryActive: Boolean = false
    private var displayConnectedHostEndpointId: String? = null
    private var displayConnectedHostName: String? = null
    private val displayDiscoveredHosts = linkedMapOf<String, String>()
    private val displayHostDeviceNamesByEndpointId = linkedMapOf<String, String>()
    private val displayLatestLapByEndpointId = linkedMapOf<String, Long>()
    private var lastRelayedStopSensorNanos: Long? = null
    private var displayReconnectionPending: Boolean = false
    private var preferredClientEndpointId: String? = null
    private val pendingLapResults = ArrayDeque<SessionLapResultMessage>()
    private var pendingPermissionScope: PermissionScope = PermissionScope.NETWORK_ONLY
    private var lastNativeFrameStatsRealtimeMs: Long = 0L
    private var lastAnalysisWidth: Int? = null
    private var lastAnalysisHeight: Int? = null
    private var localCaptureWatchdogRestartRunId: String? = null
    private var lastCaptureDebugKey: String? = null
    private var monitoringWifiLock: WifiManager.WifiLock? = null
    private var monitoringWifiLockMode: MonitoringWifiLockMode? = null
    private val isControllerOnlyHost: Boolean
        get() = BuildConfig.HOST_CONTROLLER_ONLY && BuildConfig.AUTO_START_ROLE == "host"

    private enum class PermissionScope {
        NETWORK_ONLY,
        CAMERA_AND_NETWORK,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorNativeController = SensorNativeController(this)
        localRepository = LocalRepository(this)
        val nativeClockSyncElapsedNanos: (Boolean) -> Long? = { requireSensorDomain ->
            sensorNativeController.currentClockSyncElapsedNanos(
                maxSensorSampleAgeNanos = SENSOR_ELAPSED_PROJECTION_MAX_AGE_NANOS,
                requireSensorDomain = requireSensorDomain,
            )
        }
        connectionsManager = TcpConnectionsManager(
            hostIp = BuildConfig.TCP_HOST_IP,
            hostPort = BuildConfig.TCP_HOST_PORT,
            nowNativeClockSyncElapsedNanos = nativeClockSyncElapsedNanos,
        )
        motionDetectionController = MotionDetectionController(
            localRepository = localRepository,
            sensorNativeController = sensorNativeController,
        )
        previewViewFactory = SensorNativePreviewViewFactory(sensorNativeController)
        raceSessionController = RaceSessionController(
            loadLastRun = { localRepository.loadLastRun() },
            saveLastRun = { run -> localRepository.saveLastRun(run) },
            sendMessage = { endpointId, payload, onComplete ->
                connectionsManager.sendMessage(endpointId, payload, onComplete)
            },
            sendClockSyncPayload = { endpointId, payloadBytes, onComplete ->
                connectionsManager.sendClockSyncPayload(endpointId, payloadBytes, onComplete)
            },
        )
        raceSessionController.setLocalDeviceIdentity(localDeviceId(), localEndpointName())
        sensorNativeController.setEventListener(::onSensorEvent)
        connectionsManager.setEventListener(::onConnectionEvent)

        val denied = deniedPermissions(PermissionScope.NETWORK_ONLY)
        updateUiState {
            copy(
                permissionGranted = denied.isEmpty(),
                deniedPermissions = denied,
                networkSummary = "Ready",
            )
        }
        lifecycleScope.launch {
            val persistedResults = localRepository.loadSavedRunResults()
            updateUiState { copy(savedRunResults = sortSavedRunResults(persistedResults)) }
        }

        setContent {
            com.paul.sprintsync.ui.theme.SprintSyncTheme {
                SprintSyncApp(
                    uiState = uiState.value,
                    previewViewFactory = previewViewFactory,
                    onRequestPermissions = {
                        if (uiState.value.setupBusy) return@SprintSyncApp
                        setSetupBusy(true)
                        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                            setSetupBusy(false)
                        }
                    },
                    onStartHosting = {
                        if (uiState.value.setupBusy) return@SprintSyncApp
                        setSetupBusy(true)
                        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                            clearDisplayRelayReconnectionState()
                            displayDiscoveryActive = false
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            displayDiscoveredHosts.clear()
                            raceSessionController.setNetworkRole(SessionNetworkRole.HOST)
                            connectionsManager.configureNativeClockSyncHost(
                                enabled = true,
                                requireSensorDomainClock = false,
                            )
                            try {
                                connectionsManager.startHosting(
                                    serviceId = DEFAULT_SERVICE_ID,
                                    endpointName = localEndpointName(),
                                    strategy = SessionConnectionStrategy.POINT_TO_STAR,
                                ) { result ->
                                    if (result.isSuccess) {
                                        // Defensive re-apply after startHosting normalization.
                                        connectionsManager.configureNativeClockSyncHost(
                                            enabled = true,
                                            requireSensorDomainClock = false,
                                        )
                                    }
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
                        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                            clearDisplayRelayReconnectionState()
                            displayDiscoveryActive = false
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            displayDiscoveredHosts.clear()
                            raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
                            connectionsManager.configureNativeClockSyncHost(
                                enabled = false,
                                requireSensorDomainClock = false,
                            )
                            try {
                                connectionsManager.startDiscovery(
                                    serviceId = DEFAULT_SERVICE_ID,
                                    strategy = SessionConnectionStrategy.POINT_TO_POINT,
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
                        if (isControllerOnlyHost) {
                            appendEvent("single device mode unavailable on controller host")
                            return@SprintSyncApp
                        }
                        if (uiState.value.setupBusy) return@SprintSyncApp
                        setSetupBusy(true)
                        requestPermissionsIfNeeded(PermissionScope.CAMERA_AND_NETWORK) {
                            clearDisplayRelayReconnectionState()
                            connectionsManager.stopAll()
                            connectionsManager.configureNativeClockSyncHost(
                                enabled = false,
                                requireSensorDomainClock = false,
                            )
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
                        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                            clearDisplayRelayReconnectionState()
                            raceSessionController.startDisplayHostMode()
                            clearDisplayHostLapState()
                            displayDiscoveryActive = false
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            displayDiscoveredHosts.clear()
                            connectionsManager.configureNativeClockSyncHost(
                                enabled = false,
                                requireSensorDomainClock = false,
                            )
                            try {
                                connectionsManager.startHosting(
                                    serviceId = DEFAULT_SERVICE_ID,
                                    endpointName = localEndpointName(),
                                    strategy = SessionConnectionStrategy.POINT_TO_STAR,
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
                    onReconnectClient = {
                        if (uiState.value.setupBusy) return@SprintSyncApp
                        startClientDiscoveryFlow(errorPrefix = "client reconnect", setBusy = true)
                    },
                    onReconnectPc = {
                        if (uiState.value.setupBusy) return@SprintSyncApp
                        startClientDiscoveryFlow(
                            errorPrefix = "client pc reconnect",
                            setBusy = true,
                            preferredEndpointId = PC_RECONNECT_ENDPOINT_ID,
                        )
                    },
                    onStartMonitoring = {
                        val scope = if (isControllerOnlyHost) PermissionScope.NETWORK_ONLY else PermissionScope.CAMERA_AND_NETWORK
                        requestPermissionsIfNeeded(scope) {
                            val started = raceSessionController.startMonitoring()
                            if (started) {
                                userMonitoringEnabled = !isControllerOnlyHost
                            }

                            logRuntimeDiagnostic(
                                "startMonitoring requested: started=$started role=${raceSessionController.localDeviceRole().name} " +
                                    "shouldRunLocal=${shouldRunLocalMonitoring()} resumed=$isAppResumed",
                            )
                            syncControllerSummaries()
                        }
                    },
                    onStartDisplayDiscovery = {
                        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                            displayDiscoveryActive = true
                            displayDiscoveredHosts.clear()
                            try {
                                connectionsManager.startDiscovery(
                                    serviceId = DEFAULT_SERVICE_ID,
                                    strategy = SessionConnectionStrategy.POINT_TO_STAR,
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
                            connectionsManager.requestConnection(
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
                        if (isControllerOnlyHost) {
                            userMonitoringEnabled = false
                            syncControllerSummaries()
                            return@SprintSyncApp
                        }
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
                                connectionsManager.stopAll()
                                connectionsManager.configureNativeClockSyncHost(
                                    enabled = false,
                                    requireSensorDomainClock = false,
                                )
                                clearDisplayRelayReconnectionState()
                                displayDiscoveryActive = false
                                displayConnectedHostEndpointId = null
                                displayConnectedHostName = null
                                displayDiscoveredHosts.clear()
                            }
                            SessionOperatingMode.DISPLAY_HOST -> {
                                raceSessionController.stopDisplayHostMode()
                                connectionsManager.stopAll()
                                connectionsManager.configureNativeClockSyncHost(
                                    enabled = false,
                                    requireSensorDomainClock = false,
                                )
                                clearDisplayRelayReconnectionState()
                                clearDisplayHostLapState()
                            }
                            SessionOperatingMode.NETWORK_RACE -> raceSessionController.stopMonitoring()
                        }
                        syncControllerSummaries()
                    },
                    onResetRun = {
                        raceSessionController.resetRun()
                        updateUiState {
                            copy(
                                showRunDetailsOverlay = false,
                                showRunDetailsSaveDialog = false,
                                runDetailsResults = emptyList(),
                                runDetailsValidationError = null,
                                runDetailsSaveError = null,
                            )
                        }
                        syncControllerSummaries()
                    },
                    onAssignRole = { deviceId, role ->
                        raceSessionController.assignRole(deviceId, role)
                        syncControllerSummaries()
                    },
                    onAssignCameraFacing = { deviceId, facing ->
                        raceSessionController.assignCameraFacing(deviceId, facing)
                        if (
                            shouldApplyLiveLocalCameraFacingUpdate(
                                isLocalMotionMonitoring = motionDetectionController.uiState.value.monitoring,
                                assignedDeviceId = deviceId,
                                localDeviceId = localDeviceId(),
                            )
                        ) {
                            applyLocalMonitoringConfigFromSession()
                        }
                        syncControllerSummaries()
                    },
                    onUpdateThreshold = { value ->
                        if (isControllerOnlyHost) return@SprintSyncApp
                        motionDetectionController.updateThreshold(value)
                        syncControllerSummaries()
                    },
                    onUpdateRemoteSensitivity = { stableDeviceId, sensitivity ->
                        if (!isControllerOnlyHost && uiState.value.networkRole != SessionNetworkRole.HOST) {
                            return@SprintSyncApp
                        }
                        raceSessionController.sendRemoteSensitivityUpdate(
                            targetStableDeviceId = stableDeviceId,
                            sensitivity = sensitivity,
                        )
                        syncControllerSummaries()
                    },
                    onRequestRemoteResync = { endpointId ->
                        if (!isControllerOnlyHost && uiState.value.networkRole != SessionNetworkRole.HOST) {
                            return@SprintSyncApp
                        }
                        raceSessionController.requestRemoteClockResync(endpointId)
                        syncControllerSummaries()
                    },
                    onUpdateRoiCenter = { value ->
                        if (isControllerOnlyHost) return@SprintSyncApp
                        motionDetectionController.updateRoiCenter(value)
                        syncControllerSummaries()
                    },
                    onUpdateRoiWidth = { value ->
                        if (isControllerOnlyHost) return@SprintSyncApp
                        motionDetectionController.updateRoiWidth(value)
                        syncControllerSummaries()
                    },
                    onUpdateCooldown = { value ->
                        if (isControllerOnlyHost) return@SprintSyncApp
                        motionDetectionController.updateCooldown(value)
                        syncControllerSummaries()
                    },
                    onStopHosting = {
                        if (uiState.value.operatingMode == SessionOperatingMode.DISPLAY_HOST) {
                            raceSessionController.stopDisplayHostMode()
                            clearDisplayHostLapState()
                        } else {
                            raceSessionController.stopHostingAndReturnToSetup()
                        }
                        connectionsManager.stopAll()
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
                    onOpenSaveResultDialog = {
                        if (uiState.value.saveableRunDurationNanos == null) return@SprintSyncApp
                        updateUiState {
                            copy(
                                showSaveResultDialog = true,
                                saveResultNameDraft = "",
                                saveResultNameError = null,
                            )
                        }
                    },
                    onDismissSaveResultDialog = {
                        updateUiState {
                            copy(
                                showSaveResultDialog = false,
                                saveResultNameError = null,
                            )
                        }
                    },
                    onSaveResultNameChanged = { value ->
                        updateUiState {
                            copy(
                                saveResultNameDraft = value.take(40),
                                saveResultNameError = null,
                            )
                        }
                    },
                    onConfirmSaveResult = {
                        val durationNanos = uiState.value.saveableRunDurationNanos ?: return@SprintSyncApp
                        val (normalizedName, errorMessage) = normalizeSavedRunName(uiState.value.saveResultNameDraft)
                        if (errorMessage != null) {
                            updateUiState { copy(saveResultNameError = errorMessage) }
                            return@SprintSyncApp
                        }
                        val entry = SavedRunResult(
                            id = UUID.randomUUID().toString(),
                            name = normalizedName!!,
                            durationNanos = durationNanos,
                            savedAtMillis = System.currentTimeMillis(),
                        )
                        lifecycleScope.launch {
                            localRepository.addSavedRunResult(entry)
                            val refreshed = localRepository.loadSavedRunResults()
                            updateUiState {
                                copy(
                                    savedRunResults = sortSavedRunResults(refreshed),
                                    showSaveResultDialog = false,
                                    saveResultNameDraft = "",
                                    saveResultNameError = null,
                                    showSavedResultsDialog = true,
                                )
                            }
                        }
                    },
                    onOpenSavedResultsDialog = {
                        updateUiState { copy(showSavedResultsDialog = true) }
                    },
                    onDismissSavedResultsDialog = {
                        updateUiState { copy(showSavedResultsDialog = false) }
                    },
                    onDeleteSavedResult = { id ->
                        lifecycleScope.launch {
                            localRepository.deleteSavedRunResult(id)
                            val refreshed = localRepository.loadSavedRunResults()
                            updateUiState { copy(savedRunResults = sortSavedRunResults(refreshed)) }
                        }
                    },
                    onOpenSavedRunResultDetails = { result ->
                        updateUiState {
                            copy(
                                selectedSavedRunResult = result,
                                showSavedRunResultDetailsDialog = true,
                            )
                        }
                    },
                    onDismissSavedRunResultDetails = {
                        updateUiState {
                            copy(
                                showSavedRunResultDetailsDialog = false,
                                selectedSavedRunResult = null,
                            )
                        }
                    },
                    onOpenRunDetailsOverlay = {
                        val timeline = raceSessionController.uiState.value.timeline
                        val checkpointRoles = buildRunDetailsCheckpointRoles(
                            splitMarks = timeline.hostSplitMarks,
                            stoppedSensorNanos = timeline.hostStopSensorNanos,
                        )
                        if (checkpointRoles.isEmpty() || timeline.hostStopSensorNanos == null) {
                            return@SprintSyncApp
                        }
                        val mergedDistances = mergeRunDetailsDistances(
                            existing = uiState.value.runDetailsDistancesByRole,
                            checkpointRoles = checkpointRoles,
                        )
                        updateUiState {
                            copy(
                                showRunDetailsOverlay = true,
                                runDetailsCheckpointRoles = checkpointRoles,
                                runDetailsDistancesByRole = mergedDistances,
                                runDetailsValidationError = null,
                                runDetailsSaveError = null,
                            )
                        }
                    },
                    onDismissRunDetailsOverlay = {
                        updateUiState {
                            copy(showRunDetailsOverlay = false)
                        }
                    },
                    onUpdateRunDetailsDistance = { role, distanceMeters ->
                        val nextDistances = uiState.value.runDetailsDistancesByRole.toMutableMap().apply {
                            if (distanceMeters == null) {
                                remove(role)
                            } else {
                                this[role] = distanceMeters
                            }
                        }
                        updateUiState {
                            copy(
                                runDetailsDistancesByRole = nextDistances,
                                runDetailsValidationError = null,
                            )
                        }
                    },
                    onCalculateRunDetails = {
                        val raceState = raceSessionController.uiState.value
                        val timeline = raceState.timeline
                        val checkpointRoles = buildRunDetailsCheckpointRoles(
                            splitMarks = timeline.hostSplitMarks,
                            stoppedSensorNanos = timeline.hostStopSensorNanos,
                        )
                        val distances = uiState.value.runDetailsDistancesByRole
                        val validationError = validateRunDetailsDistanceInputs(
                            checkpointRoles = checkpointRoles,
                            distancesByRole = checkpointRoles.associateWith { distances[it] },
                        )
                        if (validationError != null) {
                            updateUiState {
                                copy(
                                    runDetailsResults = emptyList(),
                                    runDetailsValidationError = validationError,
                                )
                            }
                            return@SprintSyncApp
                        }
                        val results = calculateRunDetailsResults(
                            startedSensorNanos = timeline.hostStartSensorNanos,
                            splitMarks = timeline.hostSplitMarks,
                            stoppedSensorNanos = timeline.hostStopSensorNanos,
                            checkpointRoles = checkpointRoles,
                            distancesByRole = distances,
                        )
                        updateUiState {
                            copy(
                                runDetailsCheckpointRoles = checkpointRoles,
                                runDetailsResults = results,
                                runDetailsValidationError = if (results.isEmpty()) {
                                    "Could not calculate results from the current timeline."
                                } else {
                                    null
                                },
                            )
                        }
                    },
                    onOpenRunDetailsSaveDialog = {
                        if (uiState.value.saveableRunDurationNanos == null || uiState.value.runDetailsResults.isEmpty()) {
                            return@SprintSyncApp
                        }
                        updateUiState {
                            copy(
                                showRunDetailsSaveDialog = true,
                                runDetailsAthleteNameDraft = "",
                                runDetailsSaveError = null,
                            )
                        }
                    },
                    onDismissRunDetailsSaveDialog = {
                        updateUiState {
                            copy(
                                showRunDetailsSaveDialog = false,
                                runDetailsSaveError = null,
                            )
                        }
                    },
                    onRunDetailsAthleteNameChanged = { value ->
                        updateUiState {
                            copy(
                                runDetailsAthleteNameDraft = value.take(40),
                                runDetailsSaveError = null,
                            )
                        }
                    },
                    onConfirmRunDetailsSave = {
                        val durationNanos = uiState.value.saveableRunDurationNanos ?: return@SprintSyncApp
                        val (athleteName, errorMessage) = normalizeAthleteNameForResult(raw = uiState.value.runDetailsAthleteNameDraft)
                        if (errorMessage != null) {
                            updateUiState { copy(runDetailsSaveError = errorMessage) }
                            return@SprintSyncApp
                        }
                        val savedName = buildAthleteDateResultName(athleteName!!)
                        val entry = SavedRunResult(
                            id = UUID.randomUUID().toString(),
                            name = savedName,
                            durationNanos = durationNanos,
                            savedAtMillis = System.currentTimeMillis(),
                            checkpointResults = uiState.value.runDetailsResults.map { runResult ->
                                SavedRunCheckpointResult(
                                    checkpointLabel = sessionDeviceRoleLabel(runResult.role),
                                    distanceMeters = runResult.distanceMeters,
                                    totalTimeSec = runResult.totalTimeSec,
                                    splitTimeSec = runResult.splitTimeSec,
                                    avgSpeedKmh = runResult.avgSpeedKmh,
                                    accelerationMs2 = runResult.accelerationMs2,
                                )
                            },
                        )
                        lifecycleScope.launch {
                            localRepository.addSavedRunResult(entry)
                            val refreshed = localRepository.loadSavedRunResults()
                            updateUiState {
                                copy(
                                    savedRunResults = sortSavedRunResults(refreshed),
                                    showRunDetailsSaveDialog = false,
                                    showSavedResultsDialog = true,
                                    runDetailsAthleteNameDraft = "",
                                    runDetailsSaveError = null,
                                )
                            }
                        }
                    },
                )
            }
        }

        if (BuildConfig.AUTO_START_ROLE != "none") {
            lifecycleScope.launch {
                delay(250)
                if (BuildConfig.AUTO_START_ROLE == "host") {
                    setSetupBusy(true)
                    requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
                        clearDisplayRelayReconnectionState()
                        displayDiscoveryActive = false
                        displayConnectedHostEndpointId = null
                        displayConnectedHostName = null
                        displayDiscoveredHosts.clear()
                        raceSessionController.setNetworkRole(SessionNetworkRole.HOST)
                        connectionsManager.configureNativeClockSyncHost(
                            enabled = true,
                            requireSensorDomainClock = false,
                        )
                        connectionsManager.startHosting(
                            serviceId = DEFAULT_SERVICE_ID,
                            endpointName = localEndpointName(),
                            strategy = SessionConnectionStrategy.POINT_TO_STAR,
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("host auto-start error: ${error.localizedMessage ?: "unknown"}")
                            }
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    }
                } else if (BuildConfig.AUTO_START_ROLE == "client") {
                    startClientDiscoveryFlow(errorPrefix = "client auto-start", setBusy = true)
                }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            return
        }
        applySystemUiForMode(raceSessionController.uiState.value.operatingMode)
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopTimerRefreshLoop()
        releaseMonitoringWifiLock()
        connectionsManager.stopAll()
        connectionsManager.setEventListener(null)
        sensorNativeController.setEventListener(null)
        sensorNativeController.dispose()
        super.onDestroy()
    }

    private fun requestPermissionsIfNeeded(scope: PermissionScope, onGranted: () -> Unit) {
        val denied = deniedPermissions(scope)
        if (denied.isEmpty()) {
            updateUiState { copy(permissionGranted = true, deniedPermissions = emptyList()) }
            onGranted()
            return
        }
        pendingPermissionScope = scope
        pendingPermissionAction = onGranted
        ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return
        }
        val denied = deniedPermissions(pendingPermissionScope)
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
        pendingPermissionScope = PermissionScope.NETWORK_ONLY
    }

    private fun setSetupBusy(busy: Boolean) {
        updateUiState { copy(setupBusy = busy) }
    }

    private fun startClientDiscoveryFlow(errorPrefix: String, setBusy: Boolean, preferredEndpointId: String? = null) {
        if (setBusy) {
            setSetupBusy(true)
        }
        requestPermissionsIfNeeded(PermissionScope.NETWORK_ONLY) {
            preferredClientEndpointId = preferredEndpointId?.trim()?.ifBlank { null }
            clearDisplayRelayReconnectionState()
            displayDiscoveryActive = false
            displayConnectedHostEndpointId = null
            displayConnectedHostName = null
            displayDiscoveredHosts.clear()
            raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
            connectionsManager.configureNativeClockSyncHost(
                enabled = false,
                requireSensorDomainClock = false,
            )
            connectionsManager.startDiscovery(
                serviceId = DEFAULT_SERVICE_ID,
                strategy = SessionConnectionStrategy.POINT_TO_POINT,
            ) { result ->
                result.exceptionOrNull()?.let { error ->
                    appendEvent("$errorPrefix error: ${error.localizedMessage ?: "unknown"}")
                }
                if (setBusy) {
                    setSetupBusy(false)
                }
                syncControllerSummaries()
            }
        }
    }

    private fun onConnectionEvent(event: SessionConnectionEvent) {
        val operatingMode = raceSessionController.uiState.value.operatingMode
        when (operatingMode) {
            SessionOperatingMode.NETWORK_RACE -> {
                raceSessionController.onConnectionEvent(event)
                val state = raceSessionController.uiState.value
                if (event is SessionConnectionEvent.EndpointFound) {
                    val role = state.networkRole
                    if (role == SessionNetworkRole.CLIENT && state.connectedEndpoints.isEmpty()) {
                        val endpointId = preferredClientEndpointId ?: event.endpointId
                        preferredClientEndpointId = null
                        try {
                            connectionsManager.requestConnection(
                                endpointId = endpointId,
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
                } else if (event is SessionConnectionEvent.EndpointDisconnected) {
                    if (state.networkRole == SessionNetworkRole.NONE) {
                        connectionsManager.stopAll()
                    }
                }
            }
            SessionOperatingMode.SINGLE_DEVICE -> {
                when (event) {
                    is SessionConnectionEvent.EndpointFound -> {
                        displayDiscoveredHosts[event.endpointId] = event.endpointName
                        // Auto-connect if not connected or if reconnection is pending
                        if (displayConnectedHostEndpointId == null || displayReconnectionPending) {
                            try {
                                connectionsManager.requestConnection(
                                    endpointId = event.endpointId,
                                    endpointName = localEndpointName(),
                                ) { result ->
                                    result.exceptionOrNull()?.let { error ->
                                        appendEvent(
                                            "auto-display-connect error: ${error.localizedMessage ?: "unknown"}",
                                        )
                                    }
                                }
                            } catch (error: Throwable) {
                                appendEvent("auto-display-connect error: ${error.localizedMessage ?: "unknown"}")
                            }
                        }
                    }
                    is SessionConnectionEvent.EndpointLost -> {
                        displayDiscoveredHosts.remove(event.endpointId)
                    }
                    is SessionConnectionEvent.ConnectionResult -> {
                        if (event.connected) {
                            displayConnectedHostEndpointId = event.endpointId
                            displayConnectedHostName = event.endpointName ?: displayDiscoveredHosts[event.endpointId]
                            displayDiscoveryActive = false
                            // Clear reconnection flag and flush any pending laps
                            if (displayReconnectionPending) {
                                displayReconnectionPending = false
                                flushPendingLapResults()
                            }
                        } else if (displayConnectedHostEndpointId == event.endpointId) {
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                        }
                    }
                    is SessionConnectionEvent.EndpointDisconnected -> {
                        if (displayConnectedHostEndpointId == event.endpointId) {
                            displayConnectedHostEndpointId = null
                            displayConnectedHostName = null
                            displayReconnectionPending = true
                            // Keep discovery active to find the host again or a new one
                            if (!displayDiscoveryActive) {
                                displayDiscoveryActive = true
                                displayDiscoveredHosts.clear()
                                try {
                                    connectionsManager.startDiscovery(
                                        serviceId = DEFAULT_SERVICE_ID,
                                        strategy = SessionConnectionStrategy.POINT_TO_STAR,
                                    ) { result ->
                                        result.exceptionOrNull()?.let { error ->
                                            appendEvent(
                                                "reconnect discovery error: ${error.localizedMessage ?: "unknown"}",
                                            )
                                            displayDiscoveryActive = false
                                        }
                                        syncControllerSummaries()
                                    }
                                } catch (error: Throwable) {
                                    displayDiscoveryActive = false
                                    appendEvent("reconnect discovery error: ${error.localizedMessage ?: "unknown"}")
                                    syncControllerSummaries()
                                }
                            }
                        }
                    }
                    is SessionConnectionEvent.PayloadReceived, is SessionConnectionEvent.ClockSyncSampleReceived, is SessionConnectionEvent.Error -> Unit
                }
            }
            SessionOperatingMode.DISPLAY_HOST -> {
                when (event) {
                    is SessionConnectionEvent.EndpointFound -> {
                        if (event.endpointName.isNotBlank()) {
                            displayHostDeviceNamesByEndpointId[event.endpointId] = event.endpointName
                        }
                    }
                    is SessionConnectionEvent.EndpointLost -> {
                        displayHostDeviceNamesByEndpointId.remove(event.endpointId)
                        displayLatestLapByEndpointId.remove(event.endpointId)
                    }
                    is SessionConnectionEvent.ConnectionResult -> {
                        if (event.connected) {
                            val endpointName = event.endpointName?.trim().orEmpty()
                            if (endpointName.isNotEmpty()) {
                                displayHostDeviceNamesByEndpointId[event.endpointId] = endpointName
                            }
                        } else {
                            displayHostDeviceNamesByEndpointId.remove(event.endpointId)
                            displayLatestLapByEndpointId.remove(event.endpointId)
                        }
                    }
                    is SessionConnectionEvent.EndpointDisconnected -> {
                        displayHostDeviceNamesByEndpointId.remove(event.endpointId)
                        displayLatestLapByEndpointId.remove(event.endpointId)
                    }
                    is SessionConnectionEvent.PayloadReceived -> {
                        SessionLapResultMessage.tryParse(event.message)?.let { result ->
                            val elapsedNanos = result.stoppedSensorNanos - result.startedSensorNanos
                            val senderDeviceName = result.senderDeviceName.trim()
                            if (senderDeviceName.isNotEmpty()) {
                                displayHostDeviceNamesByEndpointId[event.endpointId] = senderDeviceName
                            }
                            displayLatestLapByEndpointId[event.endpointId] = elapsedNanos
                        }
                    }
                    is SessionConnectionEvent.ClockSyncSampleReceived,
                    is SessionConnectionEvent.Error,
                    -> Unit
                }
            }
        }

        val type = when (event) {
            is SessionConnectionEvent.EndpointFound -> "endpoint_found"
            is SessionConnectionEvent.EndpointLost -> "endpoint_lost"
            is SessionConnectionEvent.ConnectionResult -> "connection_result"
            is SessionConnectionEvent.EndpointDisconnected -> "endpoint_disconnected"
            is SessionConnectionEvent.PayloadReceived -> "payload_received"
            is SessionConnectionEvent.ClockSyncSampleReceived -> "clock_sync_sample_received"
            is SessionConnectionEvent.Error -> "error"
        }
        val connectedCount = connectionsManager.connectedEndpoints().size
        val role = connectionsManager.currentRole().name.lowercase()
        updateUiState {
            copy(
                networkSummary = "$role mode, $connectedCount connected",
                lastConnectionEvent = type,
            )
        }
        syncControllerSummaries()
        appendEvent("connection:$type")
        connectionFailureGuidanceMessage(
            event = event,
            isTcpOnly = true,
            sessionNetworkRole = raceSessionController.uiState.value.networkRole,
        )?.let(::appendEvent)
    }

    private fun onSensorEvent(event: SensorNativeEvent) {
        if (isControllerOnlyHost) {
            val type = when (event) {
                is SensorNativeEvent.FrameStats -> "native_frame_stats"
                is SensorNativeEvent.Trigger -> "native_trigger"
                is SensorNativeEvent.State -> "native_state"
                is SensorNativeEvent.Diagnostic -> "native_diagnostic"
                is SensorNativeEvent.Error -> "native_error"
            }
            updateUiState { copy(lastSensorEvent = type) }
            return
        }
        if (event is SensorNativeEvent.Error) {
            if (localCaptureStartPending) {
                localCaptureStartPending = false
                localCaptureRetryBlockedUntilMs = SystemClock.elapsedRealtime() + LOCAL_CAPTURE_RETRY_BACKOFF_MS
            }
            logRuntimeDiagnostic("sensor error: ${event.message}")
        }
        if (event is SensorNativeEvent.State && event.monitoring) {
            localCaptureStartPending = false
            localCaptureRetryBlockedUntilMs = 0L
        }
        if (event is SensorNativeEvent.FrameStats) {
            lastNativeFrameStatsRealtimeMs = SystemClock.elapsedRealtime()
            lastAnalysisWidth = event.analysisWidth
            lastAnalysisHeight = event.analysisHeight
            raceSessionController.onLocalAnalysisResolutionChanged(event.analysisWidth, event.analysisHeight)
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
            val splitIndex = splitIndexForRole(raceSessionController.localDeviceRole()) ?: 0
            raceSessionController.onLocalMotionTrigger(
                triggerType = event.trigger.triggerType,
                splitIndex = splitIndex,
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
        val controllerEvent = raceSessionController.uiState.value.lastEvent ?: "-"
        val motionMonitoring = motionDetectionController.uiState.value.monitoring
        logRuntimeDiagnostic("sensor:$type controllerEvent=$controllerEvent motionMonitoring=$motionMonitoring")
        updateUiState { copy(lastSensorEvent = type) }
        syncControllerSummaries()
        appendEvent("sensor:$type")
    }

    private fun firstConnectedEndpointId(): String? {
        return connectionsManager.connectedEndpoints().firstOrNull()
    }

    private fun syncControllerSummaries() {
        val raceState = raceSessionController.uiState.value
        val clockState = raceSessionController.clockState.value
        val motionBefore = motionDetectionController.uiState.value
        val mode = raceState.operatingMode
        syncMonitoringWifiLock(raceState)
        applyRequestedOrientationForMode(mode)
        val shouldRunLocalCapture = shouldRunLocalMonitoring()
        val startRetryBlocked = SystemClock.elapsedRealtime() < localCaptureRetryBlockedUntilMs

        if (raceState.stage == SessionStage.LOBBY || raceState.stage == SessionStage.MONITORING) {
            sensorNativeController.warmupGpsSync()
        }

        when (
            resolveLocalCaptureAction(
                monitoringActive = raceState.monitoringActive && mode != SessionOperatingMode.DISPLAY_HOST,
                isAppResumed = isAppResumed,
                shouldRunLocalCapture = shouldRunLocalCapture,
                isLocalMotionMonitoring = motionBefore.monitoring,
                localCaptureStartPending = localCaptureStartPending || startRetryBlocked,
            )
        ) {
            LocalCaptureAction.START -> {
                localCaptureStartPending = true
                localCaptureRetryBlockedUntilMs = 0L
                if (lastNativeFrameStatsRealtimeMs == 0L) {
                    lastNativeFrameStatsRealtimeMs = SystemClock.elapsedRealtime()
                }
                logRuntimeDiagnostic(
                    "local capture start: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                applyLocalMonitoringConfigFromSession()
                motionDetectionController.startMonitoring()
            }

            LocalCaptureAction.STOP -> {
                localCaptureStartPending = false
                localCaptureRetryBlockedUntilMs = 0L
                logRuntimeDiagnostic(
                    "local capture stop: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                motionDetectionController.stopMonitoring()
            }

            LocalCaptureAction.NONE -> Unit
        }

        maybeRestartLocalCaptureIfFrameStatsStalled(
            raceState = raceState,
            motionMonitoring = motionBefore.monitoring,
            shouldRunLocalCapture = shouldRunLocalCapture,
        )

        val captureDebugKey =
            "${raceState.lastEvent}|${motionBefore.monitoring}|${raceSessionController.localDeviceRole().name}|${raceState.stage.name}"
        if (lastCaptureDebugKey != captureDebugKey) {
            lastCaptureDebugKey = captureDebugKey
            logRuntimeDiagnostic(
                "capture summary: controllerEvent=${raceState.lastEvent ?: "-"} motionMonitoring=${motionBefore.monitoring} role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
            )
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
        val localSensitivity = thresholdToSensitivity(motionState.config.threshold).roundToInt().coerceIn(1, 100)
        raceSessionController.onLocalSensitivityChanged(localSensitivity)
        raceSessionController.consumePendingSensitivityUpdateFromHost()?.let { pendingSensitivity ->
            if (!isControllerOnlyHost) {
                motionDetectionController.updateThreshold(sensitivityToThreshold(pendingSensitivity))
            }
        }

        val monitoringSummary = if (isControllerOnlyHost) {
            "Controller"
        } else if (motionState.monitoring) {
            "Monitoring"
        } else {
            "Idle"
        }
        val isHost = raceState.networkRole == SessionNetworkRole.HOST || mode == SessionOperatingMode.DISPLAY_HOST
        val isClient = raceState.networkRole == SessionNetworkRole.CLIENT
        val liveConnectedEndpoints = when (mode) {
            SessionOperatingMode.NETWORK_RACE -> raceState.connectedEndpoints
            SessionOperatingMode.SINGLE_DEVICE -> setOfNotNull(displayConnectedHostEndpointId)
            SessionOperatingMode.DISPLAY_HOST -> connectionsManager.connectedEndpoints()
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
        val saveableRunDurationNanos = if (
            shouldShowMonitoringTopSavedResultsButton(
                stage = raceState.stage,
                isHost = isHost,
                operatingMode = mode,
                deviceProfile = BuildConfig.DEVICE_PROFILE,
            )
        ) {
            deriveSaveableRunDurationNanos(
                startedSensorNanos = timelineForUi.hostStartSensorNanos,
                stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
            )
        } else {
            null
        }

        if (mode == SessionOperatingMode.SINGLE_DEVICE) {
            val completed = raceState.latestCompletedTimeline
            val stopNanos = completed?.hostStopSensorNanos
            val startNanos = completed?.hostStartSensorNanos
            val hostEndpoint = displayConnectedHostEndpointId
            if (
                startNanos != null &&
                stopNanos != null &&
                stopNanos != lastRelayedStopSensorNanos
            ) {
                val lapMessage = SessionLapResultMessage(
                    senderDeviceName = localEndpointName(),
                    startedSensorNanos = startNanos,
                    stoppedSensorNanos = stopNanos,
                )

                if (hostEndpoint != null) {
                    // Connected - send immediately
                    connectionsManager.sendMessage(hostEndpoint, lapMessage.toJsonString()) { result ->
                        result.exceptionOrNull()?.let { error ->
                            appendEvent("lap relay error: ${error.localizedMessage ?: "unknown"}")
                        }
                    }
                    lastRelayedStopSensorNanos = stopNanos
                } else if (displayReconnectionPending) {
                    // Disconnected but trying to reconnect - cache for later
                    if (pendingLapResults.size >= MAX_PENDING_LAPS) {
                        pendingLapResults.removeFirst() // Drop oldest to make room
                    }
                    pendingLapResults.addLast(lapMessage)
                    lastRelayedStopSensorNanos = stopNanos
                }
                // If disconnected and NOT trying to reconnect, don't cache (original behavior)
            }
        }

        val monitoringSyncMode = when {
            !isClient || !hasPeers || raceState.stage == SessionStage.SETUP -> "-"
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
            (!raceSessionController.hasFreshAnyClockLock() || raceState.anchorState == SessionAnchorState.LOST)
        ) {
            if (raceState.anchorState == SessionAnchorState.LOST) {
                "Start anchor disconnected. Run is frozen until anchor reconnects or run is reset."
            } else {
                "Clock sync lock is invalid (latency > 100ms). Triggers continue using last valid sync values."
            }
        } else {
            null
        }
        val anchorDeviceName = raceState.anchorDeviceId?.let { anchorId ->
            raceState.devices.firstOrNull { it.id == anchorId }?.name ?: anchorId
        }
        val clockLockReasonLabel = when (clockState.lockReason) {
            SessionClockLockReason.OK -> "OK"
            SessionClockLockReason.NO_ANCHOR -> "No anchor"
            SessionClockLockReason.LOCK_STALE -> "Stale/invalid"
            SessionClockLockReason.ANCHOR_LOST -> "Anchor lost"
        }

        val runStatusLabel = when {
            timelineForUi.hostStartSensorNanos == null -> "Ready"
            timelineForUi.hostStopSensorNanos != null -> "Finished"
            raceState.monitoringActive -> "Running"
            else -> "Armed"
        }
        val marksCount = timelineForUi.hostSplitMarks.size + if (timelineForUi.hostStopSensorNanos != null) 1 else 0

        val elapsedDisplay = formatElapsedDisplay(
            startedSensorNanos = timelineForUi.hostStartSensorNanos,
            stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
            monitoringActive = raceState.monitoringActive,
        )

        val cameraModeLabel = if (motionState.observedFps == null) "INIT" else "NORMAL"
        val triggerHistory = motionState.triggerHistory.map { trigger ->
            val roleLabel = when (trigger.triggerType.lowercase()) {
                "start" -> "START"
                "split" -> "SPLIT"
                "stop" -> "STOP"
                else -> trigger.triggerType.uppercase()
            }
            "$roleLabel at ${trigger.triggerSensorNanos}ns (score ${"%.4f".format(trigger.score)})"
        }
        val splitHistory = buildSplitHistoryForTimeline(
            startedSensorNanos = timelineForUi.hostStartSensorNanos,
            splitMarks = timelineForUi.hostSplitMarks,
        )
        val runDetailsCheckpointRoles = buildRunDetailsCheckpointRoles(
            splitMarks = timelineForUi.hostSplitMarks,
            stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
        )
        val runDetailsDistances = mergeRunDetailsDistances(
            existing = uiState.value.runDetailsDistancesByRole,
            checkpointRoles = runDetailsCheckpointRoles,
        )

        val clockSummary = when {
            raceSessionController.hasFreshClockLock() && clockState.hostMinusClientElapsedNanos != null -> {
                "Locked ${clockState.hostMinusClientElapsedNanos}ns"
            }

            clockState.hostMinusClientElapsedNanos != null -> {
                "Stale ${clockState.hostMinusClientElapsedNanos}ns"
            }

            else -> "Unlocked"
        }
        val displayLapRows = buildDisplayLapRowsForConnectedDevices(
            connectedEndpointIds = connectionsManager.connectedEndpoints(),
            deviceNamesByEndpointId = displayHostDeviceNamesByEndpointId,
            elapsedByEndpointId = displayLatestLapByEndpointId,
        )
        val connectedDeviceMonitoringCards = raceState.devices
            .filter { device -> !device.isLocal && raceState.connectedEndpoints.contains(device.id) }
            .mapNotNull { device ->
                val stableDeviceId = raceSessionController.stableDeviceIdForEndpoint(device.id)
                val telemetry = stableDeviceId?.let { raceState.remoteDeviceTelemetry[it] }
                val effectiveRole = telemetry?.role ?: device.role
                if (effectiveRole == SessionDeviceRole.UNASSIGNED) {
                    return@mapNotNull null
                }
                ConnectedDeviceMonitoringCardUiState(
                    stableDeviceId = stableDeviceId,
                    endpointId = device.id,
                    deviceName = telemetry?.deviceName ?: device.name,
                    role = effectiveRole,
                    latencyMs = telemetry?.latencyMs,
                    clockSynced = telemetry?.clockSynced == true,
                    analysisWidth = telemetry?.analysisWidth,
                    analysisHeight = telemetry?.analysisHeight,
                    sensitivity = telemetry?.sensitivity ?: 100,
                    connected = telemetry?.connected ?: true,
                )
            }
            .sortedWith(
                compareBy<ConnectedDeviceMonitoringCardUiState> { card ->
                    when (card.role) {
                        SessionDeviceRole.START -> 0
                        SessionDeviceRole.SPLIT1 -> 1
                        SessionDeviceRole.SPLIT2 -> 2
                        SessionDeviceRole.SPLIT3 -> 3
                        SessionDeviceRole.SPLIT4 -> 4
                        SessionDeviceRole.STOP -> 5
                        SessionDeviceRole.DISPLAY -> 6
                        SessionDeviceRole.UNASSIGNED -> 7
                    }
                }.thenBy { it.deviceName.lowercase() },
            )
        updateUiState {
            copy(
                stage = raceState.stage,
                operatingMode = mode,
                networkRole = raceState.networkRole,
                sessionSummary = raceState.stage.name.lowercase(),
                monitoringSummary = monitoringSummary,
                userMonitoringEnabled = userMonitoringEnabled,
                isControllerOnlyHost = isControllerOnlyHost,
                connectedDeviceMonitoringCards = connectedDeviceMonitoringCards,
                clockSummary = clockSummary,
                startedSensorNanos = timelineForUi.hostStartSensorNanos,
                stoppedSensorNanos = timelineForUi.hostStopSensorNanos,
                devices = raceState.devices,
                canStartMonitoring = mode == SessionOperatingMode.NETWORK_RACE && raceSessionController.canStartMonitoring(),
                isHost = isHost,
                localRole = localRole,
                monitoringConnectionTypeLabel = if (hasPeers) {
                    "TCP (${BuildConfig.TCP_HOST_IP}:${BuildConfig.TCP_HOST_PORT})"
                } else {
                    "-"
                },
                monitoringSyncModeLabel = monitoringSyncMode,
                monitoringLatencyMs = monitoringLatencyMs,
                localAnalysisWidth = lastAnalysisWidth,
                localAnalysisHeight = lastAnalysisHeight,
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
                splitHistory = splitHistory,
                runDetailsCheckpointRoles = runDetailsCheckpointRoles,
                runDetailsDistancesByRole = runDetailsDistances,
                discoveredEndpoints = if (mode == SessionOperatingMode.SINGLE_DEVICE) {
                    displayDiscoveredHosts.toMap()
                } else {
                    raceState.discoveredEndpoints
                },
                connectedEndpoints = liveConnectedEndpoints,
                networkSummary = "${connectionsManager.currentRole().name.lowercase()} mode, ${liveConnectedEndpoints.size} connected",
                displayLapRows = displayLapRows,
                displayConnectedHostName = displayConnectedHostName,
                displayDiscoveryActive = displayDiscoveryActive,
                anchorDeviceName = anchorDeviceName,
                anchorState = raceState.anchorState,
                clockLockReasonLabel = clockLockReasonLabel,
                saveableRunDurationNanos = saveableRunDurationNanos,
            )
        }
    }

    private fun syncMonitoringWifiLock(raceState: RaceSessionUiState) {
        val shouldHoldLock = shouldHoldMonitoringWifiLock(
            operatingMode = raceState.operatingMode,
            stage = raceState.stage,
            monitoringActive = raceState.monitoringActive,
        )
        if (!shouldHoldLock) {
            releaseMonitoringWifiLock()
            return
        }

        val desiredMode = selectMonitoringWifiLockMode(
            apiLevel = Build.VERSION.SDK_INT,
            lowLatencyMinApi = LOW_LATENCY_WIFI_MIN_API,
        )
        if (monitoringWifiLock == null || monitoringWifiLockMode != desiredMode) {
            releaseMonitoringWifiLock()
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            if (wifiManager == null) {
                logRuntimeDiagnostic("wifi lock unavailable: wifi manager missing")
                return
            }
            monitoringWifiLock = wifiManager.createWifiLock(
                wifiLockModeValue(desiredMode),
                MONITORING_WIFI_LOCK_TAG,
            ).apply {
                setReferenceCounted(false)
            }
            monitoringWifiLockMode = desiredMode
        }

        val lock = monitoringWifiLock ?: return
        if (lock.isHeld) {
            return
        }
        runCatching {
            lock.acquire()
        }.onSuccess {
            logRuntimeDiagnostic("wifi lock acquired: ${desiredMode.name.lowercase()}")
        }.onFailure { error ->
            logRuntimeDiagnostic("wifi lock acquire failed: ${error.localizedMessage ?: "unknown"}")
            monitoringWifiLock = null
            monitoringWifiLockMode = null
        }
    }

    private fun releaseMonitoringWifiLock() {
        val lock = monitoringWifiLock
        if (lock != null && lock.isHeld) {
            runCatching {
                lock.release()
            }.onSuccess {
                logRuntimeDiagnostic("wifi lock released")
            }.onFailure { error ->
                logRuntimeDiagnostic("wifi lock release failed: ${error.localizedMessage ?: "unknown"}")
            }
        }
        monitoringWifiLock = null
        monitoringWifiLockMode = null
    }

    @Suppress("DEPRECATION")
    private fun wifiLockModeValue(mode: MonitoringWifiLockMode): Int {
        return when (mode) {
            MonitoringWifiLockMode.LOW_LATENCY -> WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            MonitoringWifiLockMode.HIGH_PERF -> WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }

    private fun appendEvent(message: String) {
        val previous = uiState.value.recentEvents
        val updated = (listOf(message) + previous).take(10)
        updateUiState { copy(recentEvents = updated) }
    }

    private fun deniedPermissions(scope: PermissionScope): List<String> {
        return requiredPermissions(scope).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(scope: PermissionScope): List<String> {
        val permissions = mutableListOf<String>()
        if (scope == PermissionScope.CAMERA_AND_NETWORK) {
            permissions += Manifest.permission.CAMERA
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
        if (isControllerOnlyHost) {
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val mode = raceSessionController.uiState.value.operatingMode
        if (mode == SessionOperatingMode.DISPLAY_HOST) {
            return false
        }
        return userMonitoringEnabled && raceSessionController.localDeviceRole() != SessionDeviceRole.UNASSIGNED
    }

    private fun applyLocalMonitoringConfigFromSession() {
        if (isControllerOnlyHost) {
            return
        }
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
        val started = startedSensorNanos ?: return "00.00"
        val terminal = stoppedSensorNanos ?: if (monitoringActive) {
            raceSessionController.estimateLocalSensorNanosNow()
        } else {
            started
        }
        val elapsedNanos = (terminal - started).coerceAtLeast(0L)
        val totalMillis = elapsedNanos / 1_000_000L
        return formatElapsedTimerDisplay(totalMillis)
    }

    private fun formatElapsedDuration(durationNanos: Long): String {
        val totalMillis = (durationNanos / 1_000_000L).coerceAtLeast(0L)
        return formatElapsedTimerDisplay(totalMillis)
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

    private fun maybeRestartLocalCaptureIfFrameStatsStalled(
        raceState: RaceSessionUiState,
        motionMonitoring: Boolean,
        shouldRunLocalCapture: Boolean,
    ) {
        val runId = raceState.runId ?: return
        val role = raceSessionController.localDeviceRole()
        if (
            raceState.stage != SessionStage.MONITORING ||
            !raceState.monitoringActive ||
            role == SessionDeviceRole.UNASSIGNED ||
            !shouldRunLocalCapture ||
            !motionMonitoring ||
            localCaptureWatchdogRestartRunId == runId
        ) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (lastNativeFrameStatsRealtimeMs == 0L) {
            return
        }
        val staleForMs = nowMs - lastNativeFrameStatsRealtimeMs
        if (staleForMs < LOCAL_CAPTURE_FRAME_WATCHDOG_MS) {
            return
        }
        localCaptureWatchdogRestartRunId = runId
        localCaptureStartPending = false
        localCaptureRetryBlockedUntilMs = 0L
        logRuntimeDiagnostic("watchdog restart local capture: staleFrameStatsMs=$staleForMs role=${role.name} runId=$runId")
        appendEvent("watchdog: restart local capture ($staleForMs ms stale)")
        motionDetectionController.stopMonitoring()
        applyLocalMonitoringConfigFromSession()
        motionDetectionController.startMonitoring()
    }

    private fun applyRequestedOrientationForMode(mode: SessionOperatingMode) {
        val targetOrientation = requestedOrientationForMode(mode)
        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
        }
        applySystemUiForMode(mode)
    }

    private fun applySystemUiForMode(mode: SessionOperatingMode) {
        val immersive = shouldUseImmersiveModeForMode(mode)
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun flushPendingLapResults() {
        val hostEndpoint = displayConnectedHostEndpointId ?: return

        while (pendingLapResults.isNotEmpty()) {
            val lapMessage = pendingLapResults.removeFirst()
            connectionsManager.sendMessage(hostEndpoint, lapMessage.toJsonString()) { result ->
                if (result.isFailure) {
                    // Re-queue at front if send fails, will retry on next flush
                    pendingLapResults.addFirst(lapMessage)
                }
            }
        }
    }

    private fun clearDisplayRelayReconnectionState() {
        displayReconnectionPending = false
        pendingLapResults.clear()
    }

    private fun clearDisplayHostLapState() {
        displayHostDeviceNamesByEndpointId.clear()
        displayLatestLapByEndpointId.clear()
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

internal enum class MonitoringWifiLockMode {
    LOW_LATENCY,
    HIGH_PERF,
}

internal fun shouldHoldMonitoringWifiLock(
    operatingMode: SessionOperatingMode,
    stage: SessionStage,
    monitoringActive: Boolean,
): Boolean {
    return operatingMode == SessionOperatingMode.NETWORK_RACE &&
        stage == SessionStage.MONITORING &&
        monitoringActive
}

internal fun selectMonitoringWifiLockMode(
    apiLevel: Int,
    lowLatencyMinApi: Int = 29,
): MonitoringWifiLockMode {
    return if (apiLevel >= lowLatencyMinApi) {
        MonitoringWifiLockMode.LOW_LATENCY
    } else {
        MonitoringWifiLockMode.HIGH_PERF
    }
}

internal fun shouldKeepTimerRefreshActive(
    monitoringActive: Boolean,
    isAppResumed: Boolean,
    hasStopSensor: Boolean,
): Boolean {
    return monitoringActive && isAppResumed && !hasStopSensor
}

internal fun shouldUseLandscapeForMode(mode: SessionOperatingMode): Boolean = mode == SessionOperatingMode.DISPLAY_HOST

internal fun shouldUseImmersiveModeForMode(mode: SessionOperatingMode): Boolean =
    mode == SessionOperatingMode.DISPLAY_HOST

internal fun shouldApplyLiveLocalCameraFacingUpdate(
    isLocalMotionMonitoring: Boolean,
    assignedDeviceId: String,
    localDeviceId: String,
): Boolean {
    return isLocalMotionMonitoring && assignedDeviceId == localDeviceId
}

internal fun buildDisplayLapRowsForConnectedDevices(
    connectedEndpointIds: Set<String>,
    deviceNamesByEndpointId: Map<String, String>,
    elapsedByEndpointId: Map<String, Long>,
): List<DisplayLapRow> {
    return connectedEndpointIds.map { endpointId ->
        val deviceName = deviceNamesByEndpointId[endpointId]?.takeIf { it.isNotBlank() } ?: endpointId
        val lapTimeLabel = elapsedByEndpointId[endpointId]?.let { elapsedNanos ->
            val totalMillis = (elapsedNanos / 1_000_000L).coerceAtLeast(0L)
            formatElapsedTimerDisplay(totalMillis)
        } ?: "READY"
        DisplayLapRow(
            deviceName = deviceName,
            lapTimeLabel = lapTimeLabel,
        )
    }
}

internal fun buildSplitHistoryForTimeline(
    startedSensorNanos: Long?,
    splitMarks: List<SessionSplitMark>,
): List<String> {
    val started = startedSensorNanos ?: return emptyList()
    return splitMarks.mapNotNull { splitMark ->
        if (splitMark.hostSensorNanos <= started || !splitMark.role.isSplitCheckpointRole()) {
            null
        } else {
            val elapsedMillis = (splitMark.hostSensorNanos - started) / 1_000_000L
            "${sessionDeviceRoleLabel(splitMark.role)}: ${formatElapsedTimerDisplay(elapsedMillis)}"
        }
    }
}

internal data class RunDetailsCheckpointTiming(
    val role: SessionDeviceRole,
    val sensorNanos: Long,
)

internal fun buildRunDetailsCheckpointRoles(
    splitMarks: List<SessionSplitMark>,
    stoppedSensorNanos: Long?,
): List<SessionDeviceRole> {
    val roles = mutableListOf<SessionDeviceRole>()
    explicitSplitRoles().forEach { splitRole ->
        if (splitMarks.any { it.role == splitRole }) {
            roles += splitRole
        }
    }
    if (stoppedSensorNanos != null) {
        roles += SessionDeviceRole.STOP
    }
    return roles
}

internal fun mergeRunDetailsDistances(
    existing: Map<SessionDeviceRole, Double>,
    checkpointRoles: List<SessionDeviceRole>,
): Map<SessionDeviceRole, Double> {
    val merged = existing.toMutableMap()
    checkpointRoles.forEach { role ->
        if (merged[role] == null) {
            defaultRunDetailsDistanceForRole(role)?.let { merged[role] = it }
        }
    }
    return merged
}

internal fun defaultRunDetailsDistanceForRole(role: SessionDeviceRole): Double? {
    return when (role) {
        SessionDeviceRole.SPLIT1 -> 5.0
        SessionDeviceRole.SPLIT2 -> 10.0
        SessionDeviceRole.STOP -> 20.0
        else -> null
    }
}

internal fun calculateRunDetailsResults(
    startedSensorNanos: Long?,
    splitMarks: List<SessionSplitMark>,
    stoppedSensorNanos: Long?,
    checkpointRoles: List<SessionDeviceRole>,
    distancesByRole: Map<SessionDeviceRole, Double>,
): List<RunDetailsCheckpointResult> {
    val started = startedSensorNanos ?: return emptyList()
    val splitByRole = splitMarks.associateBy { it.role }
    val checkpoints = checkpointRoles.mapNotNull { role ->
        when (role) {
            SessionDeviceRole.STOP -> stoppedSensorNanos?.let { RunDetailsCheckpointTiming(role, it) }
            else -> splitByRole[role]?.let { RunDetailsCheckpointTiming(role, it.hostSensorNanos) }
        }
    }
    if (checkpoints.size != checkpointRoles.size) {
        return emptyList()
    }

    val results = mutableListOf<RunDetailsCheckpointResult>()
    var previousTimeNanos = started
    var previousDistanceMeters = 0.0
    var previousSpeedMs = 0.0
    for (checkpoint in checkpoints) {
        val distanceMeters = distancesByRole[checkpoint.role] ?: return emptyList()
        if (distanceMeters <= previousDistanceMeters) {
            return emptyList()
        }
        if (checkpoint.sensorNanos <= previousTimeNanos || checkpoint.sensorNanos <= started) {
            return emptyList()
        }

        val totalTimeSec = (checkpoint.sensorNanos - started) / 1_000_000_000.0
        val splitTimeSec = (checkpoint.sensorNanos - previousTimeNanos) / 1_000_000_000.0
        if (splitTimeSec <= 0.0 || totalTimeSec <= 0.0) {
            return emptyList()
        }
        val splitDistanceMeters = distanceMeters - previousDistanceMeters
        if (splitDistanceMeters <= 0.0) {
            return emptyList()
        }
        val avgSpeedMs = splitDistanceMeters / splitTimeSec
        val avgSpeedKmh = avgSpeedMs * 3.6
        val accelMs2 = (avgSpeedMs - previousSpeedMs) / splitTimeSec

        results += RunDetailsCheckpointResult(
            role = checkpoint.role,
            distanceMeters = distanceMeters,
            totalTimeSec = totalTimeSec,
            splitTimeSec = splitTimeSec,
            avgSpeedKmh = avgSpeedKmh,
            accelerationMs2 = accelMs2,
        )

        previousTimeNanos = checkpoint.sensorNanos
        previousDistanceMeters = distanceMeters
        previousSpeedMs = avgSpeedMs
    }
    return results
}

internal fun connectionFailureGuidanceMessage(
    event: SessionConnectionEvent,
    isTcpOnly: Boolean,
    sessionNetworkRole: SessionNetworkRole,
): String? {
    val connectionResult = event as? SessionConnectionEvent.ConnectionResult ?: return null
    if (!isTcpOnly || sessionNetworkRole != SessionNetworkRole.CLIENT || connectionResult.connected) {
        return null
    }
    return "Connection failed. Turn off mobile data / use Wi-Fi only."
}

internal fun deriveSaveableRunDurationNanos(
    startedSensorNanos: Long?,
    stoppedSensorNanos: Long?,
): Long? {
    val started = startedSensorNanos ?: return null
    val stopped = stoppedSensorNanos ?: return null
    val duration = stopped - started
    return duration.takeIf { it > 0L }
}

internal fun normalizeSavedRunName(raw: String): Pair<String?, String?> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return null to "Name is required."
    }
    if (trimmed.length > 40) {
        return null to "Name must be 40 characters or fewer."
    }
    return trimmed to null
}

internal fun normalizeAthleteNameForResult(raw: String): Pair<String?, String?> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return null to "Athlete name is required."
    }
    val collapsed = trimmed.replace("\\s+".toRegex(), "_")
    val cleaned = collapsed.replace("[^A-Za-z0-9_]+".toRegex(), "")
    if (cleaned.isEmpty()) {
        return null to "Athlete name must include letters or numbers."
    }
    return cleaned.take(30) to null
}

internal fun buildAthleteDateResultName(
    athleteName: String,
    now: Date = Date(),
    locale: Locale = Locale.getDefault(),
): String {
    val formattedDate = SimpleDateFormat("dd_MM_yyyy", locale).format(now)
    return "${athleteName}_${formattedDate}"
}

internal fun sortSavedRunResults(results: List<SavedRunResult>): List<SavedRunResult> {
    return results.sortedWith(
        compareBy<SavedRunResult> { it.durationNanos }
            .thenBy { it.savedAtMillis },
    )
}

internal fun formatElapsedTimerDisplay(totalMillis: Long): String {
    val clamped = totalMillis.coerceAtLeast(0L)
    val totalSeconds = clamped / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centiseconds = (clamped % 1_000L) / 10L
    return if (minutes > 0L) {
        String.format("%02d:%02d.%02d", minutes, seconds, centiseconds)
    } else {
        String.format("%02d.%02d", seconds, centiseconds)
    }
}

internal fun requestedOrientationForMode(mode: SessionOperatingMode): Int = if (shouldUseLandscapeForMode(mode)) {
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
} else {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

