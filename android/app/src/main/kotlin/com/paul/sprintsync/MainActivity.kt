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
import com.paul.sprintsync.sensor_native.SensorNativePreviewViewFactory
import com.paul.sprintsync.features.race_session.sessionDeviceRoleLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), ActivityCompat.OnRequestPermissionsResultCallback {
    companion object {
        private const val DEFAULT_SERVICE_ID = "com.paul.sprintsync.nearby"
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
        previewViewFactory = SensorNativePreviewViewFactory(sensorNativeController)
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
                previewViewFactory = previewViewFactory,
                onRequestPermissions = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        setSetupBusy(false)
                    }
                },
                onConnectEndpoint = { endpointId ->
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    try {
                        nearbyConnectionsManager.requestConnection(
                            endpointId = endpointId,
                            endpointName = localEndpointName(),
                        ) { result ->
                            result.exceptionOrNull()?.let { error ->
                                appendEvent("connect error: ${error.localizedMessage ?: "unknown"}")
                            }
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    } catch (error: Throwable) {
                        appendEvent("connect error: ${error.localizedMessage ?: "unknown"}")
                        setSetupBusy(false)
                        syncControllerSummaries()
                    }
                },
                onGoToLobby = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    raceSessionController.goToLobby()
                    syncControllerSummaries()
                },
                onStartHosting = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.HOST)
                        nearbyConnectionsManager.configureNativeClockSyncHost(
                            enabled = true,
                            requireSensorDomainClock = false,
                        )
                        try {
                            nearbyConnectionsManager.startHosting(
                                serviceId = DEFAULT_SERVICE_ID,
                                endpointName = localEndpointName(),
                                strategy = NearbyTransportStrategy.STAR,
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
                onStartHostingPointToPoint = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
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
                                    appendEvent("host p2p error: ${error.localizedMessage ?: "unknown"}")
                                }
                                setSetupBusy(false)
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            appendEvent("host p2p error: ${error.localizedMessage ?: "unknown"}")
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    }
                },
                onStartDiscovery = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
                        try {
                            nearbyConnectionsManager.startDiscovery(
                                serviceId = DEFAULT_SERVICE_ID,
                                strategy = NearbyTransportStrategy.STAR,
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
                onStartDiscoveryPointToPoint = {
                    if (uiState.value.setupBusy) return@SprintSyncApp
                    setSetupBusy(true)
                    requestPermissionsIfNeeded {
                        raceSessionController.setNetworkRole(SessionNetworkRole.CLIENT)
                        try {
                            nearbyConnectionsManager.startDiscovery(
                                serviceId = DEFAULT_SERVICE_ID,
                                strategy = NearbyTransportStrategy.POINT_TO_POINT,
                            ) { result ->
                                result.exceptionOrNull()?.let { error ->
                                    appendEvent("discovery p2p error: ${error.localizedMessage ?: "unknown"}")
                                }
                                setSetupBusy(false)
                                syncControllerSummaries()
                            }
                        } catch (error: Throwable) {
                            appendEvent("discovery p2p error: ${error.localizedMessage ?: "unknown"}")
                            setSetupBusy(false)
                            syncControllerSummaries()
                        }
                    }
                },
                onStartMonitoring = {
                    requestPermissionsIfNeeded {
                        val started = raceSessionController.startMonitoring()
                        logRuntimeDiagnostic(
                            "startMonitoring requested: started=$started role=${raceSessionController.localDeviceRole().name} " +
                                "shouldRunLocal=${shouldRunLocalMonitoring()} resumed=$isAppResumed",
                        )
                        syncControllerSummaries()
                    }
                },
                onStopMonitoring = {
                    logRuntimeDiagnostic("stopMonitoring requested")
                    raceSessionController.stopMonitoring()
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
                onStartChirpSync = {
                    if (raceSessionController.uiState.value.networkRole == SessionNetworkRole.HOST) {
                        if (raceSessionController.uiState.value.connectedEndpoints.isEmpty()) {
                            appendEvent("chirp sync ignored: no connected endpoint")
                        } else {
                            raceSessionController.startChirpSyncAllConnected()
                        }
                    } else {
                        val endpointId = firstConnectedEndpointId()
                        if (endpointId == null) {
                            appendEvent("chirp sync ignored: no connected endpoint")
                        } else {
                            raceSessionController.startChirpSync(endpointId)
                        }
                    }
                    syncControllerSummaries()
                },
                onEndChirpSync = {
                    raceSessionController.clearChirpLock(broadcast = true)
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
                    raceSessionController.stopHostingAndReturnToSetup()
                    nearbyConnectionsManager.stopAll()
                    if (motionDetectionController.uiState.value.monitoring) {
                        motionDetectionController.stopMonitoring()
                    }
                    updateUiState { copy(networkSummary = "Stopped") }
                    appendEvent("hosting stopped")
                    syncControllerSummaries()
                },
            )
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
        val shouldRunLocalCapture = shouldRunLocalMonitoring()

        when (
            resolveLocalCaptureAction(
                monitoringActive = raceState.monitoringActive,
                isAppResumed = isAppResumed,
                shouldRunLocalCapture = shouldRunLocalCapture,
                isLocalMotionMonitoring = motionBefore.monitoring,
            )
        ) {
            LocalCaptureAction.START -> {
                logRuntimeDiagnostic(
                    "local capture start: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                applyLocalMonitoringConfigFromSession()
                motionDetectionController.startMonitoring()
            }

            LocalCaptureAction.STOP -> {
                logRuntimeDiagnostic(
                    "local capture stop: role=${raceSessionController.localDeviceRole().name} stage=${raceState.stage.name}",
                )
                motionDetectionController.stopMonitoring()
            }

            LocalCaptureAction.NONE -> Unit
        }

        if (
            shouldKeepTimerRefreshActive(
                monitoringActive = raceState.monitoringActive,
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
        val isHost = raceState.networkRole == SessionNetworkRole.HOST
        val isClient = raceState.networkRole == SessionNetworkRole.CLIENT
        val hasPeers = raceState.connectedEndpoints.isNotEmpty()
        val localRole = raceSessionController.localDeviceRole()

        val monitoringSyncMode = when {
            !isClient || !hasPeers || !raceState.monitoringActive -> "-"
            raceSessionController.hasFreshGpsLock() -> "GPS"
            raceSessionController.hasFreshChirpLock() -> "CHIRP"
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
            raceState.timeline.hostStartSensorNanos == null -> "Ready"
            raceState.timeline.hostStopSensorNanos != null -> "Finished"
            raceState.monitoringActive -> "Running"
            else -> "Armed"
        }
        val marksCount = raceState.timeline.hostSplitSensorNanos.size +
            if (raceState.timeline.hostStopSensorNanos != null) 1 else 0

        val elapsedDisplay = formatElapsedDisplay(
            startedSensorNanos = raceState.timeline.hostStartSensorNanos,
            stoppedSensorNanos = raceState.timeline.hostStopSensorNanos,
            monitoringActive = raceState.monitoringActive,
        )

        val cameraModeLabel = when (motionState.cameraFpsMode.wireName) {
            "hs120" -> "HS"
            else -> if (motionState.observedFps == null) "INIT" else "NORMAL"
        }
        val triggerHistory = motionState.triggerHistory.map { trigger ->
            val roleLabel = when (trigger.triggerType.lowercase()) {
                "start" -> "START"
                "stop" -> "STOP"
                "split" -> "SPLIT ${trigger.splitIndex + 1}"
                else -> trigger.triggerType.uppercase()
            }
            "$roleLabel at ${trigger.triggerSensorNanos}ns (score ${"%.4f".format(trigger.score)})"
        }

        val chirpSyncStatusText = when {
            raceState.chirpSyncInProgress -> "Calibrating..."
            raceSessionController.hasFreshChirpLock() && clockState.chirpJitterNanos != null ->
                "Locked (+/-${clockState.chirpJitterNanos / 1000L} us)"
            raceSessionController.hasFreshChirpLock() -> "Locked"
            clockState.chirpJitterNanos != null -> "Stale (+/-${clockState.chirpJitterNanos / 1000L} us)"
            else -> "Not calibrated"
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
                isHost = isHost,
                localRole = localRole,
                localHighSpeedEnabled = raceSessionController.localHighSpeedEnabled(),
                monitoringConnectionTypeLabel = if (hasPeers) "Nearby (auto BT/Wi-Fi Direct)" else "-",
                monitoringSyncModeLabel = monitoringSyncMode,
                monitoringLatencyMs = monitoringLatencyMs,
                hasConnectedPeers = hasPeers,
                chirpSyncInProgress = raceState.chirpSyncInProgress,
                chirpLockActive = raceSessionController.hasFreshChirpLock(),
                chirpSyncStatusText = chirpSyncStatusText,
                chirpQualityUs = clockState.chirpJitterNanos?.let { (it / 1000L).toInt() },
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
): LocalCaptureAction {
    if (monitoringActive && isAppResumed && shouldRunLocalCapture && !isLocalMotionMonitoring) {
        return LocalCaptureAction.START
    }
    if (isLocalMotionMonitoring && (!monitoringActive || !isAppResumed || !shouldRunLocalCapture)) {
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
