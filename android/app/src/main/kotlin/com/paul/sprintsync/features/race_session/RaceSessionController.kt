package com.paul.sprintsync.features.race_session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paul.sprintsync.chirp_sync.AcousticChirpSyncEngine
import com.paul.sprintsync.chirp_sync.ChirpCalibrationResult
import com.paul.sprintsync.core.clock.ClockDomain
import com.paul.sprintsync.core.models.LastRunResult
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.NearbyConnectionsManager
import com.paul.sprintsync.core.services.NearbyEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

typealias RaceSessionLoadLastRun = suspend () -> LastRunResult?
typealias RaceSessionSaveLastRun = suspend (LastRunResult) -> Unit
typealias RaceSessionSendMessage = (endpointId: String, messageJson: String, onComplete: (Result<Unit>) -> Unit) -> Unit
typealias RaceSessionStartCalibration = (
    calibrationId: String,
    role: String,
    profile: String,
    sampleCount: Int,
    remoteSendElapsedNanos: Long?,
) -> ChirpCalibrationResult

data class SessionRaceTimeline(
    val hostStartSensorNanos: Long? = null,
    val hostSplitSensorNanos: List<Long> = emptyList(),
    val hostStopSensorNanos: Long? = null,
)

data class RaceSessionClockState(
    val hostMinusClientElapsedNanos: Long? = null,
    val hostSensorMinusElapsedNanos: Long? = null,
    val localSensorMinusElapsedNanos: Long? = null,
    val lastClockSyncElapsedNanos: Long? = null,
    val hostClockRoundTripNanos: Long? = null,
    val chirpHostMinusClientElapsedNanos: Long? = null,
    val chirpJitterNanos: Long? = null,
    val lastChirpSyncElapsedNanos: Long? = null,
)

data class RaceSessionUiState(
    val stage: SessionStage = SessionStage.SETUP,
    val networkRole: SessionNetworkRole = SessionNetworkRole.NONE,
    val deviceRole: SessionDeviceRole = SessionDeviceRole.UNASSIGNED,
    val monitoringActive: Boolean = false,
    val runId: String? = null,
    val timeline: SessionRaceTimeline = SessionRaceTimeline(),
    val devices: List<SessionDevice> = emptyList(),
    val discoveredEndpoints: Map<String, String> = emptyMap(),
    val connectedEndpoints: Set<String> = emptySet(),
    val clockSyncInProgress: Boolean = false,
    val chirpSyncInProgress: Boolean = false,
    val activeCalibrationId: String? = null,
    val lastError: String? = null,
    val lastEvent: String? = null,
)

class RaceSessionController(
    private val loadLastRun: RaceSessionLoadLastRun,
    private val saveLastRun: RaceSessionSaveLastRun,
    private val sendMessage: RaceSessionSendMessage,
    private val startCalibration: RaceSessionStartCalibration,
    private val clearCalibration: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
) : ViewModel() {
    companion object {
        private const val MAX_ACCEPTED_ROUND_TRIP_NANOS = 120_000_000L
        private const val DEFAULT_CLOCK_SYNC_SAMPLE_COUNT = 8
        private const val CLOCK_LOCK_VALIDITY_NANOS = 6_000_000_000L
        private const val CHIRP_LOCK_VALIDITY_NANOS = 20_000_000_000L
        private const val DEFAULT_LOCAL_DEVICE_ID = "local-device"
        private const val DEFAULT_LOCAL_DEVICE_NAME = "This Device"
    }

    constructor(
        localRepository: LocalRepository,
        nearbyConnectionsManager: NearbyConnectionsManager,
        chirpSyncEngine: AcousticChirpSyncEngine,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
    ) : this(
        loadLastRun = { localRepository.loadLastRun() },
        saveLastRun = { run -> localRepository.saveLastRun(run) },
        sendMessage = { endpointId, messageJson, onComplete ->
            nearbyConnectionsManager.sendMessage(endpointId, messageJson, onComplete)
        },
        startCalibration = { calibrationId, role, profile, sampleCount, remoteSendElapsedNanos ->
            chirpSyncEngine.startCalibration(
                calibrationId = calibrationId,
                role = role,
                profile = profile,
                sampleCount = sampleCount,
                remoteSendElapsedNanos = remoteSendElapsedNanos,
            )
        },
        clearCalibration = { chirpSyncEngine.clear() },
        ioDispatcher = ioDispatcher,
        nowElapsedNanos = nowElapsedNanos,
    )

    private val _uiState = MutableStateFlow(
        RaceSessionUiState(
            devices = listOf(
                SessionDevice(
                    id = DEFAULT_LOCAL_DEVICE_ID,
                    name = DEFAULT_LOCAL_DEVICE_NAME,
                    role = SessionDeviceRole.UNASSIGNED,
                    isLocal = true,
                ),
            ),
        ),
    )
    val uiState: StateFlow<RaceSessionUiState> = _uiState.asStateFlow()

    private val _clockState = MutableStateFlow(RaceSessionClockState())
    val clockState: StateFlow<RaceSessionClockState> = _clockState.asStateFlow()

    private val pendingClockSyncSamplesByClientSendNanos = mutableMapOf<Long, Long>()
    private val acceptedClockOffsetSamples = mutableListOf<Long>()
    private val acceptedClockRoundTripSamples = mutableListOf<Long>()

    private var localDeviceId = DEFAULT_LOCAL_DEVICE_ID

    init {
        viewModelScope.launch(ioDispatcher) {
            val persisted = loadLastRun() ?: return@launch
            val persistedTimeline = SessionRaceTimeline(
                hostStartSensorNanos = persisted.startedSensorNanos,
                hostSplitSensorNanos = persisted.splitElapsedNanos.scanSplits(persisted.startedSensorNanos),
                hostStopSensorNanos = null,
            )
            _uiState.value = _uiState.value.copy(timeline = persistedTimeline)
        }
    }

    fun setLocalDeviceIdentity(deviceId: String, deviceName: String) {
        if (deviceId.isBlank() || deviceName.isBlank()) {
            return
        }
        localDeviceId = deviceId
        _uiState.value = _uiState.value.copy(
            devices = _uiState.value.devices
                .filterNot { it.isLocal }
                .plus(
                    SessionDevice(
                        id = deviceId,
                        name = deviceName,
                        role = localDeviceRole(),
                        cameraFacing = localCameraFacing(),
                        highSpeedEnabled = localHighSpeedEnabled(),
                        isLocal = true,
                    ),
                )
                .distinctBy { it.id },
            deviceRole = localDeviceRole(),
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun setSessionStage(stage: SessionStage) {
        _uiState.value = _uiState.value.copy(stage = stage)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun setNetworkRole(role: SessionNetworkRole) {
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            current = _uiState.value.devices,
        )
        _uiState.value = _uiState.value.copy(
            networkRole = role,
            stage = SessionStage.SETUP,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            devices = local,
            connectedEndpoints = emptySet(),
            deviceRole = localDeviceRole(),
            lastError = null,
        )
    }

    fun setDeviceRole(role: SessionDeviceRole) {
        assignRole(localDeviceId, role)
    }

    fun onNearbyEvent(event: NearbyEvent) {
        when (event) {
            is NearbyEvent.EndpointFound -> {
                _uiState.value = _uiState.value.copy(
                    discoveredEndpoints = _uiState.value.discoveredEndpoints + (event.endpointId to event.endpointName),
                    lastEvent = "endpoint_found",
                )
            }

            is NearbyEvent.EndpointLost -> {
                _uiState.value = _uiState.value.copy(
                    discoveredEndpoints = _uiState.value.discoveredEndpoints - event.endpointId,
                    lastEvent = "endpoint_lost",
                )
            }

            is NearbyEvent.ConnectionResult -> {
                handleConnectionResult(event)
            }

            is NearbyEvent.EndpointDisconnected -> {
                val nextConnected = _uiState.value.connectedEndpoints - event.endpointId
                val nextDevices = _uiState.value.devices.filterNot { it.id == event.endpointId }
                _uiState.value = _uiState.value.copy(
                    connectedEndpoints = nextConnected,
                    devices = ensureLocalDevice(localDeviceFromState(), nextDevices),
                    lastEvent = "endpoint_disconnected",
                )
                if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
                    broadcastSnapshotIfHost()
                }
            }

            is NearbyEvent.PayloadReceived -> {
                handleIncomingPayload(endpointId = event.endpointId, rawMessage = event.message)
            }

            is NearbyEvent.Error -> {
                _uiState.value = _uiState.value.copy(lastError = event.message, lastEvent = "error")
            }
        }
    }

    fun assignRole(deviceId: String, role: SessionDeviceRole) {
        var nextDevices = _uiState.value.devices
        if (role != SessionDeviceRole.UNASSIGNED) {
            nextDevices = nextDevices.map { existing ->
                if (existing.id != deviceId && existing.role == role) {
                    existing.copy(role = SessionDeviceRole.UNASSIGNED)
                } else {
                    existing
                }
            }
        }
        nextDevices = nextDevices.map { existing ->
            if (existing.id == deviceId) {
                existing.copy(role = role)
            } else {
                existing
            }
        }
        _uiState.value = _uiState.value.copy(
            devices = nextDevices,
            deviceRole = localDeviceRole(),
            lastEvent = "role_assigned",
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun assignCameraFacing(deviceId: String, facing: SessionCameraFacing) {
        val nextDevices = _uiState.value.devices.map { existing ->
            if (existing.id == deviceId) {
                existing.copy(cameraFacing = facing)
            } else {
                existing
            }
        }
        _uiState.value = _uiState.value.copy(devices = nextDevices)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun assignHighSpeedEnabled(deviceId: String, enabled: Boolean) {
        val nextDevices = _uiState.value.devices.map { existing ->
            if (existing.id == deviceId) {
                existing.copy(highSpeedEnabled = enabled)
            } else {
                existing
            }
        }
        _uiState.value = _uiState.value.copy(devices = nextDevices)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun goToLobby() {
        if (!canGoToLobby()) {
            _uiState.value = _uiState.value.copy(lastError = "Need at least 2 devices")
            return
        }
        _uiState.value = _uiState.value.copy(stage = SessionStage.LOBBY, lastError = null)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun startMonitoring(): Boolean {
        if (_uiState.value.networkRole == SessionNetworkRole.HOST && !canStartMonitoring()) {
            _uiState.value = _uiState.value.copy(lastError = "Assign start and stop devices before monitoring")
            return false
        }

        val nextRunId = UUID.randomUUID().toString()
        val hostOffset = if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            _clockState.value.hostSensorMinusElapsedNanos ?: _clockState.value.localSensorMinusElapsedNanos ?: 0L
        } else {
            _clockState.value.hostSensorMinusElapsedNanos
        }
        _clockState.value = _clockState.value.copy(hostSensorMinusElapsedNanos = hostOffset)
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.MONITORING,
            monitoringActive = true,
            runId = nextRunId,
            timeline = SessionRaceTimeline(),
            lastError = null,
        )

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
        return true
    }

    fun stopMonitoring() {
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.LOBBY,
            monitoringActive = false,
            lastError = null,
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun onLocalMotionTrigger(triggerType: String, splitIndex: Int, triggerSensorNanos: Long) {
        if (!_uiState.value.monitoringActive) {
            return
        }

        val role = localDeviceRole()
        if (role == SessionDeviceRole.UNASSIGNED) {
            ingestLocalTrigger(
                triggerType = triggerType,
                splitIndex = splitIndex,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = _uiState.value.networkRole == SessionNetworkRole.HOST,
            )
            if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
                broadcastSnapshotIfHost()
            }
            return
        }

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            val mappedType = roleToTriggerType(role)
            val mappedSplitIndex = if (mappedType == "split") {
                _uiState.value.timeline.hostSplitSensorNanos.size
            } else {
                0
            }
            ingestLocalTrigger(
                triggerType = mappedType,
                splitIndex = mappedSplitIndex,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = true,
            )
            broadcastSnapshotIfHost()
            return
        }

        if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            val request = SessionTriggerRequestMessage(
                role = role,
                triggerSensorNanos = triggerSensorNanos,
                mappedHostSensorNanos = mapClientSensorToHostSensor(triggerSensorNanos),
            ).toJsonString()
            sendToHost(request)
        }
    }

    fun canGoToLobby(): Boolean {
        return totalDeviceCount() >= 2
    }

    fun totalDeviceCount(): Int {
        return _uiState.value.devices.size
    }

    fun canShowSplitControls(): Boolean {
        return totalDeviceCount() > 2
    }

    fun canStartMonitoring(): Boolean {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return false
        }
        val roles = _uiState.value.devices.map { it.role }
        return roles.contains(SessionDeviceRole.START) && roles.contains(SessionDeviceRole.STOP)
    }

    fun localDeviceRole(): SessionDeviceRole {
        return localDeviceFromState().role
    }

    fun localCameraFacing(): SessionCameraFacing {
        return localDeviceFromState().cameraFacing
    }

    fun localHighSpeedEnabled(): Boolean {
        return localDeviceFromState().highSpeedEnabled
    }

    fun startClockSyncBurst(endpointId: String, sampleCount: Int = DEFAULT_CLOCK_SYNC_SAMPLE_COUNT) {
        if (!_uiState.value.connectedEndpoints.contains(endpointId)) {
            _uiState.value = _uiState.value.copy(lastError = "Clock sync ignored: endpoint not connected")
            return
        }
        _uiState.value = _uiState.value.copy(clockSyncInProgress = true, lastError = null)
        pendingClockSyncSamplesByClientSendNanos.clear()
        acceptedClockOffsetSamples.clear()
        acceptedClockRoundTripSamples.clear()

        repeat(sampleCount.coerceAtLeast(3)) {
            val sendElapsedNanos = nowElapsedNanos()
            pendingClockSyncSamplesByClientSendNanos[sendElapsedNanos] = sendElapsedNanos
            val message = SessionClockSyncRequestMessage(
                clientSendElapsedNanos = sendElapsedNanos,
            ).toJsonString()
            sendMessage(endpointId, message) { result ->
                result.exceptionOrNull()?.let { error ->
                    _uiState.value = _uiState.value.copy(
                        clockSyncInProgress = false,
                        lastError = "Clock sync send failed: ${error.localizedMessage ?: "unknown"}",
                    )
                }
            }
        }
    }

    fun startChirpSync(
        endpointId: String,
        profile: String = AcousticChirpSyncEngine.PROFILE_NEAR_ULTRASOUND,
        sampleCount: Int = 5,
    ) {
        if (!_uiState.value.connectedEndpoints.contains(endpointId)) {
            _uiState.value = _uiState.value.copy(lastError = "Chirp sync ignored: endpoint not connected")
            return
        }

        val calibrationId = UUID.randomUUID().toString()
        _uiState.value = _uiState.value.copy(
            chirpSyncInProgress = true,
            activeCalibrationId = calibrationId,
            lastError = null,
        )

        val remoteSendElapsedNanos = nowElapsedNanos()
        val startMessage = SessionChirpCalibrationStartMessage(
            calibrationId = calibrationId,
            role = "responder",
            profile = profile,
            sampleCount = sampleCount,
            remoteSendElapsedNanos = remoteSendElapsedNanos,
        ).toJsonString()
        sendMessage(endpointId, startMessage) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    chirpSyncInProgress = false,
                    activeCalibrationId = null,
                    lastError = "Failed to start chirp sync: ${error.localizedMessage ?: "unknown"}",
                )
            }
        }

        startCalibration(
            calibrationId,
            "initiator",
            profile,
            sampleCount,
            null,
        )
    }

    fun clearChirpLock(broadcast: Boolean = false) {
        clearCalibration()
        updateClockState(
            chirpHostMinusClientElapsedNanos = null,
            chirpJitterNanos = null,
            lastChirpSyncElapsedNanos = null,
        )
        _uiState.value = _uiState.value.copy(
            chirpSyncInProgress = false,
            activeCalibrationId = null,
        )
        if (broadcast) {
            broadcastToConnected(SessionChirpClearMessage(calibrationId = null).toJsonString())
        }
    }

    fun ingestLocalTrigger(triggerType: String, splitIndex: Int, triggerSensorNanos: Long, broadcast: Boolean = true) {
        val updated = applyTrigger(
            timeline = _uiState.value.timeline,
            triggerType = triggerType,
            splitIndex = splitIndex,
            triggerSensorNanos = triggerSensorNanos,
        ) ?: return

        _uiState.value = _uiState.value.copy(
            timeline = updated,
            lastEvent = "local_trigger",
        )

        maybePersistCompletedRun(updated)

        if (!broadcast) {
            return
        }
        val message = SessionTriggerMessage(
            triggerType = triggerType,
            splitIndex = splitIndex,
            triggerSensorNanos = triggerSensorNanos,
        ).toJsonString()
        broadcastToConnected(message)
        broadcastTimelineSnapshot(updated)
    }

    fun updateClockState(
        hostMinusClientElapsedNanos: Long? = _clockState.value.hostMinusClientElapsedNanos,
        hostSensorMinusElapsedNanos: Long? = _clockState.value.hostSensorMinusElapsedNanos,
        localSensorMinusElapsedNanos: Long? = _clockState.value.localSensorMinusElapsedNanos,
        lastClockSyncElapsedNanos: Long? = _clockState.value.lastClockSyncElapsedNanos,
        hostClockRoundTripNanos: Long? = _clockState.value.hostClockRoundTripNanos,
        chirpHostMinusClientElapsedNanos: Long? = _clockState.value.chirpHostMinusClientElapsedNanos,
        chirpJitterNanos: Long? = _clockState.value.chirpJitterNanos,
        lastChirpSyncElapsedNanos: Long? = _clockState.value.lastChirpSyncElapsedNanos,
    ) {
        _clockState.value = RaceSessionClockState(
            hostMinusClientElapsedNanos = hostMinusClientElapsedNanos,
            hostSensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
            localSensorMinusElapsedNanos = localSensorMinusElapsedNanos,
            lastClockSyncElapsedNanos = lastClockSyncElapsedNanos,
            hostClockRoundTripNanos = hostClockRoundTripNanos,
            chirpHostMinusClientElapsedNanos = chirpHostMinusClientElapsedNanos,
            chirpJitterNanos = chirpJitterNanos,
            lastChirpSyncElapsedNanos = lastChirpSyncElapsedNanos,
        )
    }

    fun mapClientSensorToHostSensor(clientSensorNanos: Long): Long? {
        val state = _clockState.value
        val hostSensorMinusElapsedNanos = state.hostSensorMinusElapsedNanos ?: return null
        val hostMinusClientElapsedNanos = state.hostMinusClientElapsedNanos ?: return null
        val localSensorMinusElapsedNanos = state.localSensorMinusElapsedNanos ?: return null

        val clientElapsedNanos = ClockDomain.sensorToElapsedNanos(
            sensorNanos = clientSensorNanos,
            sensorMinusElapsedNanos = localSensorMinusElapsedNanos,
        )
        val hostElapsedNanos = clientElapsedNanos + hostMinusClientElapsedNanos
        return ClockDomain.elapsedToSensorNanos(
            elapsedNanos = hostElapsedNanos,
            sensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
        )
    }

    fun mapHostSensorToLocalSensor(hostSensorNanos: Long): Long? {
        val state = _clockState.value
        val hostSensorMinusElapsedNanos = state.hostSensorMinusElapsedNanos ?: return null
        val hostMinusClientElapsedNanos = state.hostMinusClientElapsedNanos ?: return null
        val localSensorMinusElapsedNanos = state.localSensorMinusElapsedNanos ?: return null

        val hostElapsedNanos = ClockDomain.sensorToElapsedNanos(
            sensorNanos = hostSensorNanos,
            sensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
        )
        val clientElapsedNanos = hostElapsedNanos - hostMinusClientElapsedNanos
        return ClockDomain.elapsedToSensorNanos(
            elapsedNanos = clientElapsedNanos,
            sensorMinusElapsedNanos = localSensorMinusElapsedNanos,
        )
    }

    fun computeGpsFixAgeNanos(gpsFixElapsedRealtimeNanos: Long?): Long? {
        return ClockDomain.computeGpsFixAgeNanos(gpsFixElapsedRealtimeNanos)
    }

    fun estimateLocalSensorNanosNow(): Long {
        val now = ClockDomain.nowElapsedNanos()
        val localSensorMinusElapsedNanos = _clockState.value.localSensorMinusElapsedNanos
            ?: return now
        return ClockDomain.elapsedToSensorNanos(
            elapsedNanos = now,
            sensorMinusElapsedNanos = localSensorMinusElapsedNanos,
        )
    }

    fun hasFreshClockLock(maxAgeNanos: Long = CLOCK_LOCK_VALIDITY_NANOS): Boolean {
        val lockAt = _clockState.value.lastClockSyncElapsedNanos ?: return false
        return nowElapsedNanos() - lockAt <= maxAgeNanos
    }

    fun hasFreshChirpLock(maxAgeNanos: Long = CHIRP_LOCK_VALIDITY_NANOS): Boolean {
        val lockAt = _clockState.value.lastChirpSyncElapsedNanos ?: return false
        return nowElapsedNanos() - lockAt <= maxAgeNanos
    }

    private fun handleIncomingPayload(endpointId: String, rawMessage: String) {
        SessionClockSyncRequestMessage.tryParse(rawMessage)?.let { request ->
            handleIncomingClockSyncRequest(endpointId, request)
            return
        }

        SessionClockSyncResponseMessage.tryParse(rawMessage)?.let { response ->
            handleClockSyncResponseSample(response)
            return
        }

        SessionSnapshotMessage.tryParse(rawMessage)?.let { snapshot ->
            applySnapshot(snapshot)
            return
        }

        SessionTriggerRequestMessage.tryParse(rawMessage)?.let { request ->
            handleTriggerRequest(request)
            return
        }

        SessionTriggerRefinementMessage.tryParse(rawMessage)?.let { refinement ->
            handleTriggerRefinement(refinement)
            return
        }

        SessionTriggerMessage.tryParse(rawMessage)?.let { trigger ->
            val triggerSensorNanos = if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
                mapHostSensorToLocalSensor(trigger.triggerSensorNanos) ?: trigger.triggerSensorNanos
            } else {
                trigger.triggerSensorNanos
            }
            ingestLocalTrigger(
                triggerType = trigger.triggerType,
                splitIndex = trigger.splitIndex,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = false,
            )
            return
        }

        SessionTimelineSnapshotMessage.tryParse(rawMessage)?.let { snapshot ->
            ingestTimelineSnapshot(snapshot)
            return
        }

        SessionChirpCalibrationStartMessage.tryParse(rawMessage)?.let { message ->
            handleIncomingChirpStart(endpointId, message)
            return
        }

        SessionChirpCalibrationResultMessage.tryParse(rawMessage)?.let { result ->
            applyChirpResult(result)
            return
        }

        SessionChirpClearMessage.tryParse(rawMessage)?.let {
            clearChirpLock(broadcast = false)
            return
        }
    }

    private fun handleConnectionResult(event: NearbyEvent.ConnectionResult) {
        val nextConnected = if (event.connected) {
            _uiState.value.connectedEndpoints + event.endpointId
        } else {
            _uiState.value.connectedEndpoints - event.endpointId
        }
        val nextDevices = if (event.connected) {
            val endpointName = event.endpointName
                ?: _uiState.value.discoveredEndpoints[event.endpointId]
                ?: event.endpointId
            ensureLocalDevice(
                localDeviceFromState(),
                _uiState.value.devices + SessionDevice(
                    id = event.endpointId,
                    name = endpointName,
                    role = SessionDeviceRole.UNASSIGNED,
                    isLocal = false,
                ),
            )
        } else {
            ensureLocalDevice(
                localDeviceFromState(),
                _uiState.value.devices.filterNot { it.id == event.endpointId },
            )
        }

        _uiState.value = _uiState.value.copy(
            connectedEndpoints = nextConnected,
            devices = nextDevices,
            deviceRole = localDeviceRole(),
            lastError = if (event.connected) null else (event.statusMessage ?: "Connection failed"),
            lastEvent = "connection_result",
        )

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    private fun handleIncomingClockSyncRequest(
        endpointId: String,
        request: SessionClockSyncRequestMessage,
    ) {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        val receiveElapsedNanos = nowElapsedNanos()
        val response = SessionClockSyncResponseMessage(
            clientSendElapsedNanos = request.clientSendElapsedNanos,
            hostReceiveElapsedNanos = receiveElapsedNanos,
            hostSendElapsedNanos = nowElapsedNanos(),
        ).toJsonString()
        sendMessage(endpointId, response) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "Clock sync response failed: ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
    }

    private fun handleTriggerRequest(request: SessionTriggerRequestMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST || !_uiState.value.monitoringActive) {
            return
        }
        val mappedType = roleToTriggerType(request.role)
        val mappedSplitIndex = if (mappedType == "split") {
            _uiState.value.timeline.hostSplitSensorNanos.size
        } else {
            0
        }
        val hostSensorNanos = request.mappedHostSensorNanos ?: request.triggerSensorNanos
        ingestLocalTrigger(
            triggerType = mappedType,
            splitIndex = mappedSplitIndex,
            triggerSensorNanos = hostSensorNanos,
            broadcast = true,
        )
        broadcastSnapshotIfHost()
    }

    private fun handleTriggerRefinement(refinement: SessionTriggerRefinementMessage) {
        if (_uiState.value.runId != refinement.runId) {
            return
        }
        val mappedType = roleToTriggerType(refinement.role)
        val nextTimeline = applyRefinement(
            timeline = _uiState.value.timeline,
            triggerType = mappedType,
            splitIndex = refinement.splitIndex,
            refinedSensorNanos = refinement.refinedHostSensorNanos,
        ) ?: return
        _uiState.value = _uiState.value.copy(timeline = nextTimeline, lastEvent = "trigger_refinement")
        maybePersistCompletedRun(nextTimeline)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    private fun applySnapshot(snapshot: SessionSnapshotMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }

        snapshot.hostSensorMinusElapsedNanos?.let { hostOffset ->
            updateClockState(hostSensorMinusElapsedNanos = hostOffset)
        }

        val resolvedSelfId = snapshot.selfDeviceId ?: localDeviceId
        localDeviceId = resolvedSelfId
        val mappedDevices = snapshot.devices.map { device ->
            device.copy(isLocal = device.id == resolvedSelfId)
        }

        val timeline = SessionRaceTimeline(
            hostStartSensorNanos = snapshot.hostStartSensorNanos?.let { mapHostSensorToLocalSensor(it) ?: it },
            hostSplitSensorNanos = snapshot.hostSplitSensorNanos.map { mapHostSensorToLocalSensor(it) ?: it },
            hostStopSensorNanos = snapshot.hostStopSensorNanos?.let { mapHostSensorToLocalSensor(it) ?: it },
        )

        _uiState.value = _uiState.value.copy(
            stage = snapshot.stage,
            monitoringActive = snapshot.monitoringActive,
            runId = snapshot.runId,
            devices = ensureLocalDevice(
                SessionDevice(
                    id = resolvedSelfId,
                    name = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.name ?: localDeviceName(),
                    role = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.role ?: SessionDeviceRole.UNASSIGNED,
                    cameraFacing = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.cameraFacing ?: SessionCameraFacing.REAR,
                    highSpeedEnabled = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.highSpeedEnabled ?: false,
                    isLocal = true,
                ),
                mappedDevices,
            ),
            deviceRole = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.role ?: SessionDeviceRole.UNASSIGNED,
            timeline = timeline,
            lastEvent = "snapshot_applied",
            lastError = null,
        )

        maybePersistCompletedRun(timeline)
    }

    private fun handleClockSyncResponseSample(response: SessionClockSyncResponseMessage) {
        val receiveElapsedNanos = nowElapsedNanos()
        val sentElapsedNanos = pendingClockSyncSamplesByClientSendNanos.remove(response.clientSendElapsedNanos)
            ?: return
        val roundTripNanos = receiveElapsedNanos - sentElapsedNanos
        if (roundTripNanos > MAX_ACCEPTED_ROUND_TRIP_NANOS) {
            maybeFinishClockSyncBurst()
            return
        }
        val offset = AcousticChirpSyncEngine.computeOffsetFromFourTimestamps(
            clientSendElapsedNanos = response.clientSendElapsedNanos,
            hostReceiveElapsedNanos = response.hostReceiveElapsedNanos,
            hostSendElapsedNanos = response.hostSendElapsedNanos,
            clientReceiveElapsedNanos = receiveElapsedNanos,
        )
        acceptedClockOffsetSamples += offset
        acceptedClockRoundTripSamples += roundTripNanos
        maybeFinishClockSyncBurst()
    }

    private fun maybeFinishClockSyncBurst() {
        if (pendingClockSyncSamplesByClientSendNanos.isNotEmpty()) {
            return
        }
        val offset = AcousticChirpSyncEngine.medianNanos(acceptedClockOffsetSamples)
        val roundTrip = AcousticChirpSyncEngine.medianNanos(acceptedClockRoundTripSamples)
        if (offset != null && roundTrip != null) {
            updateClockState(
                hostMinusClientElapsedNanos = offset,
                hostClockRoundTripNanos = roundTrip,
                lastClockSyncElapsedNanos = nowElapsedNanos(),
            )
            _uiState.value = _uiState.value.copy(clockSyncInProgress = false, lastEvent = "clock_sync_complete")
        } else {
            _uiState.value = _uiState.value.copy(
                clockSyncInProgress = false,
                lastError = "Clock sync failed: no acceptable samples",
            )
        }
        acceptedClockOffsetSamples.clear()
        acceptedClockRoundTripSamples.clear()
    }

    private fun handleIncomingChirpStart(
        endpointId: String,
        message: SessionChirpCalibrationStartMessage,
    ) {
        val result = startCalibration(
            message.calibrationId,
            message.role,
            message.profile,
            message.sampleCount,
            message.remoteSendElapsedNanos,
        )
        val response = result.toWireMessage().toJsonString()
        sendMessage(endpointId, response) { sendResult ->
            sendResult.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "Failed sending chirp result: ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
    }

    private fun applyChirpResult(result: SessionChirpCalibrationResultMessage) {
        if (result.accepted && result.hostMinusClientElapsedNanos != null) {
            updateClockState(
                hostMinusClientElapsedNanos = result.hostMinusClientElapsedNanos,
                chirpHostMinusClientElapsedNanos = result.hostMinusClientElapsedNanos,
                chirpJitterNanos = result.jitterNanos,
                lastChirpSyncElapsedNanos = result.completedAtElapsedNanos ?: nowElapsedNanos(),
            )
            _uiState.value = _uiState.value.copy(
                chirpSyncInProgress = false,
                activeCalibrationId = null,
                lastEvent = "chirp_sync_complete",
                lastError = null,
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            chirpSyncInProgress = false,
            activeCalibrationId = null,
            lastError = result.reason ?: "Chirp calibration rejected",
        )
    }

    private fun ingestTimelineSnapshot(snapshot: SessionTimelineSnapshotMessage) {
        val localTimeline = if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            SessionRaceTimeline(
                hostStartSensorNanos = snapshot.hostStartSensorNanos?.let { mapHostSensorToLocalSensor(it) ?: it },
                hostSplitSensorNanos = snapshot.hostSplitSensorNanos.map { hostSplit ->
                    mapHostSensorToLocalSensor(hostSplit) ?: hostSplit
                },
                hostStopSensorNanos = snapshot.hostStopSensorNanos?.let { mapHostSensorToLocalSensor(it) ?: it },
            )
        } else {
            SessionRaceTimeline(
                hostStartSensorNanos = snapshot.hostStartSensorNanos,
                hostSplitSensorNanos = snapshot.hostSplitSensorNanos,
                hostStopSensorNanos = snapshot.hostStopSensorNanos,
            )
        }
        _uiState.value = _uiState.value.copy(timeline = localTimeline, lastEvent = "timeline_snapshot")
        maybePersistCompletedRun(localTimeline)
    }

    private fun applyTrigger(
        timeline: SessionRaceTimeline,
        triggerType: String,
        splitIndex: Int,
        triggerSensorNanos: Long,
    ): SessionRaceTimeline? {
        return when (triggerType.lowercase()) {
            "start" -> {
                if (timeline.hostStartSensorNanos != null) {
                    null
                } else {
                    timeline.copy(hostStartSensorNanos = triggerSensorNanos)
                }
            }

            "split" -> {
                if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
                    null
                } else {
                    val requiredSize = splitIndex + 1
                    val mutableSplits = timeline.hostSplitSensorNanos.toMutableList()
                    while (mutableSplits.size < requiredSize) {
                        mutableSplits += timeline.hostStartSensorNanos
                    }
                    mutableSplits[splitIndex] = triggerSensorNanos
                    timeline.copy(hostSplitSensorNanos = mutableSplits)
                }
            }

            "stop" -> {
                if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
                    null
                } else {
                    timeline.copy(hostStopSensorNanos = triggerSensorNanos)
                }
            }

            else -> null
        }
    }

    private fun applyRefinement(
        timeline: SessionRaceTimeline,
        triggerType: String,
        splitIndex: Int,
        refinedSensorNanos: Long,
    ): SessionRaceTimeline? {
        return when (triggerType.lowercase()) {
            "start" -> timeline.copy(hostStartSensorNanos = refinedSensorNanos)
            "split" -> {
                if (timeline.hostStartSensorNanos == null) {
                    null
                } else {
                    val requiredSize = splitIndex + 1
                    val mutableSplits = timeline.hostSplitSensorNanos.toMutableList()
                    while (mutableSplits.size < requiredSize) {
                        mutableSplits += timeline.hostStartSensorNanos
                    }
                    mutableSplits[splitIndex] = refinedSensorNanos
                    timeline.copy(hostSplitSensorNanos = mutableSplits)
                }
            }

            "stop" -> {
                if (timeline.hostStartSensorNanos == null) {
                    null
                } else {
                    timeline.copy(hostStopSensorNanos = refinedSensorNanos)
                }
            }

            else -> null
        }
    }

    private fun maybePersistCompletedRun(timeline: SessionRaceTimeline) {
        val started = timeline.hostStartSensorNanos ?: return
        val stopped = timeline.hostStopSensorNanos ?: return
        if (stopped <= started) {
            return
        }
        val splitElapsed = timeline.hostSplitSensorNanos
            .map { split -> (split - started).coerceAtLeast(0L) }
        val run = LastRunResult(
            startedSensorNanos = started,
            splitElapsedNanos = splitElapsed,
        )
        viewModelScope.launch(ioDispatcher) {
            saveLastRun(run)
        }
    }

    private fun broadcastTimelineSnapshot(timeline: SessionRaceTimeline) {
        val payload = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = timeline.hostStartSensorNanos,
            hostSplitSensorNanos = timeline.hostSplitSensorNanos,
            hostStopSensorNanos = timeline.hostStopSensorNanos,
            sentElapsedNanos = nowElapsedNanos(),
        ).toJsonString()
        broadcastToConnected(payload)
    }

    private fun broadcastSnapshotIfHost() {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        val targetEndpoints = _uiState.value.connectedEndpoints
        targetEndpoints.forEach { endpointId ->
            val payload = SessionSnapshotMessage(
                stage = _uiState.value.stage,
                monitoringActive = _uiState.value.monitoringActive,
                devices = _uiState.value.devices,
                hostStartSensorNanos = _uiState.value.timeline.hostStartSensorNanos,
                hostSplitSensorNanos = _uiState.value.timeline.hostSplitSensorNanos,
                hostStopSensorNanos = _uiState.value.timeline.hostStopSensorNanos,
                runId = _uiState.value.runId,
                hostSensorMinusElapsedNanos = _clockState.value.hostSensorMinusElapsedNanos,
                selfDeviceId = endpointId,
            ).toJsonString()
            sendMessage(endpointId, payload) { result ->
                result.exceptionOrNull()?.let { error ->
                    _uiState.value = _uiState.value.copy(
                        lastError = "send failed ($endpointId): ${error.localizedMessage ?: "unknown"}",
                    )
                }
            }
        }
    }

    private fun broadcastToConnected(message: String) {
        _uiState.value.connectedEndpoints.forEach { endpointId ->
            sendMessage(endpointId, message) { result ->
                result.exceptionOrNull()?.let { error ->
                    _uiState.value = _uiState.value.copy(
                        lastError = "send failed ($endpointId): ${error.localizedMessage ?: "unknown"}",
                    )
                }
            }
        }
    }

    private fun sendToHost(message: String) {
        val hostEndpointId = _uiState.value.connectedEndpoints.firstOrNull() ?: return
        sendMessage(hostEndpointId, message) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "send failed ($hostEndpointId): ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
    }

    private fun roleToTriggerType(role: SessionDeviceRole): String {
        return when (role) {
            SessionDeviceRole.START -> "start"
            SessionDeviceRole.SPLIT -> "split"
            SessionDeviceRole.STOP -> "stop"
            SessionDeviceRole.UNASSIGNED -> "split"
        }
    }

    private fun ensureLocalDevice(local: SessionDevice, current: List<SessionDevice>): List<SessionDevice> {
        val withoutLocal = current.filterNot { it.id == local.id || it.isLocal }
        return withoutLocal + local.copy(isLocal = true)
    }

    private fun localDeviceFromState(): SessionDevice {
        return _uiState.value.devices.firstOrNull { it.id == localDeviceId || it.isLocal }
            ?: SessionDevice(
                id = localDeviceId,
                name = DEFAULT_LOCAL_DEVICE_NAME,
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            )
    }

    private fun localDeviceName(): String {
        return localDeviceFromState().name
    }

    private fun List<Long>.scanSplits(startedSensorNanos: Long): List<Long> {
        return map { elapsed -> startedSensorNanos + elapsed.coerceAtLeast(0L) }
    }

    private fun ChirpCalibrationResult.toWireMessage(): SessionChirpCalibrationResultMessage {
        return SessionChirpCalibrationResultMessage(
            calibrationId = calibrationId,
            accepted = accepted,
            hostMinusClientElapsedNanos = hostMinusClientElapsedNanos,
            jitterNanos = jitterNanos,
            reason = reason,
            completedAtElapsedNanos = completedAtElapsedNanos,
            profile = profile,
            sampleCount = sampleCount,
        )
    }
}
