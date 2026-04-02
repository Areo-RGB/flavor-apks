package com.paul.sprintsync.features.race_session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paul.sprintsync.BuildConfig
import com.paul.sprintsync.core.clock.ClockDomain
import com.paul.sprintsync.core.models.LastRunResult
import com.paul.sprintsync.core.repositories.LocalRepository
import com.paul.sprintsync.core.services.SessionConnectionEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

typealias RaceSessionLoadLastRun = suspend () -> LastRunResult?
typealias RaceSessionSaveLastRun = suspend (LastRunResult) -> Unit
typealias RaceSessionSendMessage = (endpointId: String, messageJson: String, onComplete: (Result<Unit>) -> Unit) -> Unit
typealias RaceSessionSendClockSyncPayload = (
    endpointId: String,
    payloadBytes: ByteArray,
    onComplete: (Result<Unit>) -> Unit,
) -> Unit
typealias RaceSessionClockSyncDelay = suspend (delayMillis: Long) -> Unit

private enum class ClockSyncBurstReason {
    MANUAL,
    STALE,
    DRIFT,
}

private enum class AdaptiveClockSyncDecision {
    START,
    RATE_LIMITED,
}

private data class AcceptedClockSyncSample(
    val offsetNanos: Long,
    val roundTripNanos: Long,
    val acceptOrder: Int,
)

data class SessionRaceTimeline(
    val hostStartSensorNanos: Long? = null,
    val hostStopSensorNanos: Long? = null,
    val hostSplitMarks: List<SessionSplitMark> = emptyList(),
)

data class RaceSessionClockState(
    val hostMinusClientElapsedNanos: Long? = null,
    val hostSensorMinusElapsedNanos: Long? = null,
    val localSensorMinusElapsedNanos: Long? = null,
    val localGpsUtcOffsetNanos: Long? = null,
    val localGpsFixAgeNanos: Long? = null,
    val hostGpsUtcOffsetNanos: Long? = null,
    val hostGpsFixAgeNanos: Long? = null,
    val lastClockSyncElapsedNanos: Long? = null,
    val hostClockRoundTripNanos: Long? = null,
    val lockReason: SessionClockLockReason = SessionClockLockReason.NO_ANCHOR,
)

data class SessionRemoteDeviceTelemetryState(
    val stableDeviceId: String,
    val deviceName: String,
    val role: SessionDeviceRole,
    val sensitivity: Int,
    val latencyMs: Int?,
    val clockSynced: Boolean,
    val analysisWidth: Int?,
    val analysisHeight: Int?,
    val connected: Boolean,
    val timestampMillis: Long,
)

data class RaceSessionUiState(
    val stage: SessionStage = SessionStage.SETUP,
    val operatingMode: SessionOperatingMode = SessionOperatingMode.NETWORK_RACE,
    val networkRole: SessionNetworkRole = SessionNetworkRole.NONE,
    val deviceRole: SessionDeviceRole = SessionDeviceRole.UNASSIGNED,
    val monitoringActive: Boolean = false,
    val runId: String? = null,
    val timeline: SessionRaceTimeline = SessionRaceTimeline(),
    val latestCompletedTimeline: SessionRaceTimeline? = null,
    val devices: List<SessionDevice> = emptyList(),
    val discoveredEndpoints: Map<String, String> = emptyMap(),
    val connectedEndpoints: Set<String> = emptySet(),
    val remoteDeviceTelemetry: Map<String, SessionRemoteDeviceTelemetryState> = emptyMap(),
    val clockSyncInProgress: Boolean = false,
    val pendingSensitivityUpdateFromHost: Int? = null,
    val anchorDeviceId: String? = null,
    val anchorState: SessionAnchorState = SessionAnchorState.READY,
    val lastError: String? = null,
    val lastEvent: String? = null,
)

class RaceSessionController(
    private val loadLastRun: RaceSessionLoadLastRun,
    private val saveLastRun: RaceSessionSaveLastRun,
    private val sendMessage: RaceSessionSendMessage,
    private val sendClockSyncPayload: RaceSessionSendClockSyncPayload,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
    private val clockSyncDelay: RaceSessionClockSyncDelay = { delayMillis -> kotlinx.coroutines.delay(delayMillis) },
) : ViewModel() {
    companion object {
        private const val MAX_ACCEPTED_ROUND_TRIP_NANOS = 120_000_000L
        private const val MAX_VALID_LOCK_ROUND_TRIP_NANOS = 100_000_000L
        private const val DEFAULT_CLOCK_SYNC_SAMPLE_COUNT = 8
        private const val CLOCK_SYNC_BURST_STAGGER_MILLIS = 50L
        private const val CLOCK_SYNC_SAMPLE_EXPIRY_MILLIS = 1_500L
        private const val ADAPTIVE_CLOCK_SYNC_TICK_MILLIS = 500L
        private const val BASE_STALE_CHECK_INTERVAL_MS = 8_000L
        private const val POST_DRIFT_COOLDOWN_MS = 2_000L
        private const val MIN_BURST_SPACING_MS = 15_000L
        private const val DRIFT_JUMP_THRESHOLD_NANOS = 25_000_000L
        private const val RECENT_HOST_OFFSET_SAMPLE_LIMIT = 6
        private const val DEFAULT_LOCAL_DEVICE_ID = "local-device"
        private const val DEFAULT_LOCAL_DEVICE_NAME = "This Device"
    }

    constructor(
        loadLastRun: RaceSessionLoadLastRun,
        saveLastRun: RaceSessionSaveLastRun,
        sendMessage: RaceSessionSendMessage,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        nowElapsedNanos: () -> Long = { ClockDomain.nowElapsedNanos() },
        clockSyncDelay: RaceSessionClockSyncDelay = { delayMillis -> kotlinx.coroutines.delay(delayMillis) },
    ) : this(
        loadLastRun = loadLastRun,
        saveLastRun = saveLastRun,
        sendMessage = sendMessage,
        sendClockSyncPayload = { endpointId, payloadBytes, onComplete ->
            val payload = String(payloadBytes, StandardCharsets.ISO_8859_1)
            sendMessage(endpointId, payload, onComplete)
        },
        ioDispatcher = ioDispatcher,
        nowElapsedNanos = nowElapsedNanos,
        clockSyncDelay = clockSyncDelay,
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

    private val pendingClockSyncSamplesByClientSendNanos = ConcurrentHashMap<Long, Long>()
    private val acceptedClockSyncSamples = mutableListOf<AcceptedClockSyncSample>()
    private val endpointIdByStableDeviceId = mutableMapOf<String, String>()
    private val stableDeviceIdByEndpointId = mutableMapOf<String, String>()
    private var localSensitivity = 100
    private var localAnalysisWidth: Int? = null
    private var localAnalysisHeight: Int? = null
    private var lastPublishedTelemetryFingerprint: String? = null
    private var clockSyncBurstDispatchCompleted = false
    private var acceptedClockSampleCounter = 0
    private val recentHostOffsetSamplesNanos = ArrayDeque<Long>()
    private var lastBurstStartedElapsedNanos: Long? = null
    private var lastBurstReason: ClockSyncBurstReason? = null
    private var driftRiseDetectedAtElapsedNanos: Long? = null
    private var nextStaleBurstCheckElapsedNanos: Long = 0L
    private var lastRateLimitedEventElapsedNanos: Long? = null
    private var activeClockSyncEndpointId: String? = null
    private var activeClockSyncBurstToken: Long = 0L

    private var localDeviceId = DEFAULT_LOCAL_DEVICE_ID

    init {
        viewModelScope.launch(ioDispatcher) {
            val persisted = loadLastRun() ?: return@launch
            val persistedTimeline = SessionRaceTimeline(
                hostStartSensorNanos = persisted.startedSensorNanos,
                hostStopSensorNanos = persisted.stoppedSensorNanos,
            )
            _uiState.value = _uiState.value.copy(timeline = persistedTimeline)
        }

        viewModelScope.launch(ioDispatcher) {
            while (true) {
                kotlinx.coroutines.delay(ADAPTIVE_CLOCK_SYNC_TICK_MILLIS)
                maybeRunAdaptiveClockSyncTick()
            }
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
                        isLocal = true,
                    ),
                )
                .distinctBy { it.id },
            deviceRole = localDeviceRole(),
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        } else {
            publishDeviceTelemetryIfClient(force = true)
        }
    }

    fun onLocalSensitivityChanged(sensitivity: Int) {
        val clamped = sensitivity.coerceIn(1, 100)
        if (localSensitivity == clamped) {
            return
        }
        localSensitivity = clamped
        publishDeviceTelemetryIfClient(force = true)
    }

    fun onLocalAnalysisResolutionChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (localAnalysisWidth == width && localAnalysisHeight == height) {
            return
        }
        localAnalysisWidth = width
        localAnalysisHeight = height
        publishDeviceTelemetryIfClient(force = true)
    }

    fun stableDeviceIdForEndpoint(endpointId: String): String? = stableDeviceIdByEndpointId[endpointId]

    fun consumePendingSensitivityUpdateFromHost(): Int? {
        val pending = _uiState.value.pendingSensitivityUpdateFromHost ?: return null
        _uiState.value = _uiState.value.copy(pendingSensitivityUpdateFromHost = null)
        return pending
    }

    fun sendRemoteSensitivityUpdate(targetStableDeviceId: String, sensitivity: Int): Boolean {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return false
        }
        val endpointId = endpointIdByStableDeviceId[targetStableDeviceId] ?: return false
        val payload = SessionDeviceConfigUpdateMessage(
            targetStableDeviceId = targetStableDeviceId,
            sensitivity = sensitivity.coerceIn(1, 100),
        ).toJsonString()
        sendMessage(endpointId, payload) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "remote sensitivity send failed: ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
        _uiState.value.remoteDeviceTelemetry[targetStableDeviceId]?.let { existing ->
            val updated = existing.copy(
                sensitivity = sensitivity.coerceIn(1, 100),
                timestampMillis = System.currentTimeMillis(),
            )
            _uiState.value = _uiState.value.copy(
                remoteDeviceTelemetry = _uiState.value.remoteDeviceTelemetry + (targetStableDeviceId to updated),
            )
        }
        return true
    }

    fun requestRemoteClockResync(targetEndpointId: String, sampleCount: Int = DEFAULT_CLOCK_SYNC_SAMPLE_COUNT): Boolean {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return false
        }
        if (!_uiState.value.connectedEndpoints.contains(targetEndpointId)) {
            _uiState.value = _uiState.value.copy(lastError = "resync failed: endpoint not connected")
            return false
        }
        val payload = SessionClockResyncRequestMessage(
            sampleCount = sampleCount.coerceIn(3, 24),
        ).toJsonString()
        sendMessage(targetEndpointId, payload) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "resync request failed: ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
        _uiState.value = _uiState.value.copy(lastEvent = "clock_resync_requested")
        return true
    }

    fun setSessionStage(stage: SessionStage) {
        _uiState.value = _uiState.value.copy(stage = stage)
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun setNetworkRole(role: SessionNetworkRole) {
        endpointIdByStableDeviceId.clear()
        stableDeviceIdByEndpointId.clear()
        resetAdaptiveClockSyncPolicyState()
        lastPublishedTelemetryFingerprint = null
        activeClockSyncEndpointId = null
        activeClockSyncBurstToken += 1L
        clearClockSyncBurstQueues()
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            current = _uiState.value.devices,
        )
        val initialStage = if (role == SessionNetworkRole.HOST) SessionStage.LOBBY else SessionStage.SETUP
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.NETWORK_RACE,
            networkRole = role,
            stage = initialStage,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            latestCompletedTimeline = null,
            devices = local,
            connectedEndpoints = emptySet(),
            remoteDeviceTelemetry = emptyMap(),
            deviceRole = localDeviceRole(),
            pendingSensitivityUpdateFromHost = null,
            anchorDeviceId = null,
            anchorState = SessionAnchorState.READY,
            lastError = null,
        )
        _clockState.value = _clockState.value.copy(lockReason = SessionClockLockReason.NO_ANCHOR)
    }

    fun startSingleDeviceMonitoring() {
        endpointIdByStableDeviceId.clear()
        stableDeviceIdByEndpointId.clear()
        lastPublishedTelemetryFingerprint = null
        val local = SessionDevice(
            id = localDeviceId,
            name = localDeviceName(),
            role = SessionDeviceRole.START,
            isLocal = true,
        )
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.SINGLE_DEVICE,
            networkRole = SessionNetworkRole.NONE,
            stage = SessionStage.MONITORING,
            monitoringActive = true,
            runId = UUID.randomUUID().toString(),
            timeline = SessionRaceTimeline(),
            devices = ensureLocalDevice(local, _uiState.value.devices),
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            remoteDeviceTelemetry = emptyMap(),
            deviceRole = SessionDeviceRole.START,
            pendingSensitivityUpdateFromHost = null,
            anchorDeviceId = null,
            anchorState = SessionAnchorState.READY,
            lastError = null,
            lastEvent = "single_device_started",
        )
    }

    fun stopSingleDeviceMonitoring() {
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            _uiState.value.devices,
        )
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.SINGLE_DEVICE,
            networkRole = SessionNetworkRole.NONE,
            stage = SessionStage.SETUP,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            devices = local,
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            remoteDeviceTelemetry = emptyMap(),
            deviceRole = SessionDeviceRole.UNASSIGNED,
            pendingSensitivityUpdateFromHost = null,
            anchorDeviceId = null,
            anchorState = SessionAnchorState.READY,
            lastError = null,
            lastEvent = "single_device_stopped",
        )
    }

    fun startDisplayHostMode() {
        endpointIdByStableDeviceId.clear()
        stableDeviceIdByEndpointId.clear()
        lastPublishedTelemetryFingerprint = null
        val local = ensureLocalDevice(
            SessionDevice(
                id = localDeviceId,
                name = localDeviceName(),
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = true,
            ),
            _uiState.value.devices,
        )
        _uiState.value = _uiState.value.copy(
            operatingMode = SessionOperatingMode.DISPLAY_HOST,
            networkRole = SessionNetworkRole.NONE,
            stage = SessionStage.MONITORING,
            monitoringActive = false,
            runId = null,
            timeline = SessionRaceTimeline(),
            devices = local,
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            remoteDeviceTelemetry = emptyMap(),
            deviceRole = SessionDeviceRole.UNASSIGNED,
            pendingSensitivityUpdateFromHost = null,
            anchorDeviceId = null,
            anchorState = SessionAnchorState.READY,
            lastError = null,
            lastEvent = "display_host_started",
        )
    }

    fun stopDisplayHostMode() {
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.SETUP,
            monitoringActive = false,
            discoveredEndpoints = emptyMap(),
            connectedEndpoints = emptySet(),
            remoteDeviceTelemetry = emptyMap(),
            pendingSensitivityUpdateFromHost = null,
            anchorDeviceId = null,
            anchorState = SessionAnchorState.READY,
            lastError = null,
            lastEvent = "display_host_stopped",
        )
    }

    fun setDeviceRole(role: SessionDeviceRole) {
        assignRole(localDeviceId, role)
    }

    fun onConnectionEvent(event: SessionConnectionEvent) {
        when (event) {
            is SessionConnectionEvent.EndpointFound -> {
                _uiState.value = _uiState.value.copy(
                    discoveredEndpoints = _uiState.value.discoveredEndpoints + (event.endpointId to event.endpointName),
                    lastEvent = "endpoint_found",
                )
            }

            is SessionConnectionEvent.EndpointLost -> {
                _uiState.value = _uiState.value.copy(
                    discoveredEndpoints = _uiState.value.discoveredEndpoints - event.endpointId,
                    lastEvent = "endpoint_lost",
                )
            }

            is SessionConnectionEvent.ConnectionResult -> {
                handleConnectionResult(event)
            }

            is SessionConnectionEvent.EndpointDisconnected -> {
                val wasClient = _uiState.value.networkRole == SessionNetworkRole.CLIENT
                val stableId = stableDeviceIdByEndpointId[event.endpointId]
                clearIdentityMappingForEndpoint(event.endpointId)
                val nextConnected = _uiState.value.connectedEndpoints - event.endpointId
                val nextDevices = ensureLocalDevice(
                    localDeviceFromState(),
                    pruneOrphanedNonLocalDevices(
                        devices = _uiState.value.devices,
                        connectedEndpoints = nextConnected,
                    ),
                )

                var nextStage = _uiState.value.stage
                var nextRole = _uiState.value.networkRole
                var nextAnchorState = _uiState.value.anchorState
                var nextError: String? = null

                if (_uiState.value.networkRole == SessionNetworkRole.CLIENT && nextConnected.isEmpty()) {
                    nextStage = SessionStage.SETUP
                    nextRole = SessionNetworkRole.NONE
                    nextAnchorState = SessionAnchorState.READY
                }
                if (
                    _uiState.value.monitoringActive &&
                    _uiState.value.anchorDeviceId != null &&
                    _uiState.value.anchorDeviceId == event.endpointId
                ) {
                    nextAnchorState = SessionAnchorState.LOST
                    nextError = "Start anchor disconnected. Run is frozen until reconnect/reset."
                }

                _uiState.value = _uiState.value.copy(
                    connectedEndpoints = nextConnected,
                    devices = nextDevices,
                    remoteDeviceTelemetry = if (stableId == null) {
                        _uiState.value.remoteDeviceTelemetry
                    } else {
                        _uiState.value.remoteDeviceTelemetry - stableId
                    },
                    stage = nextStage,
                    networkRole = nextRole,
                    deviceRole = localDeviceRole(),
                    anchorState = nextAnchorState,
                    lastError = nextError,
                    lastEvent = "endpoint_disconnected",
                )
                if (wasClient) {
                    val nextClockEndpoint = if (activeClockSyncEndpointId == event.endpointId) {
                        nextConnected.firstOrNull()
                    } else {
                        activeClockSyncEndpointId
                    }
                    switchClockSyncEndpoint(nextClockEndpoint)
                }
                refreshLockReasonFromState()
                if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
                    broadcastSnapshotIfHost()
                } else {
                    lastPublishedTelemetryFingerprint = null
                }
            }

            is SessionConnectionEvent.PayloadReceived -> {
                handleIncomingPayload(endpointId = event.endpointId, rawMessage = event.message)
            }

            is SessionConnectionEvent.ClockSyncSampleReceived -> {
                onClockSyncSampleReceived(endpointId = event.endpointId, sample = event.sample)
            }

            is SessionConnectionEvent.Error -> {
                _uiState.value = _uiState.value.copy(lastError = event.message, lastEvent = "error")
            }
        }
    }

    fun onClockSyncSampleReceived(endpointId: String, sample: SessionClockSyncBinaryResponse) {
        if (!_uiState.value.connectedEndpoints.contains(endpointId)) {
            return
        }
        val sourceEndpoint = activeClockSyncEndpointId
        if (sourceEndpoint != null && sourceEndpoint != endpointId) {
            return
        }
        if (sourceEndpoint == null) {
            activeClockSyncEndpointId = endpointId
        }
        handleClockSyncResponseSample(sample)
    }

    fun assignRole(deviceId: String, role: SessionDeviceRole) {
        var nextDevices = _uiState.value.devices
        if (
            role == SessionDeviceRole.START ||
            role == SessionDeviceRole.STOP ||
            role == SessionDeviceRole.DISPLAY
        ) {
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
        val updatedAnchorId = nextDevices.firstOrNull { it.role == SessionDeviceRole.START }?.id
        _uiState.value = _uiState.value.copy(
            anchorDeviceId = updatedAnchorId,
            anchorState = when {
                updatedAnchorId == null -> SessionAnchorState.READY
                _uiState.value.monitoringActive -> SessionAnchorState.ACTIVE
                else -> SessionAnchorState.READY
            },
        )
        refreshLockReasonFromState()
        if (_uiState.value.networkRole == SessionNetworkRole.CLIENT && _uiState.value.connectedEndpoints.isNotEmpty()) {
            val endpointId = _uiState.value.connectedEndpoints.first()
            if (!_uiState.value.clockSyncInProgress) {
                startClockSyncBurstInternal(endpointId, DEFAULT_CLOCK_SYNC_SAMPLE_COUNT, ClockSyncBurstReason.STALE)
            }
            publishDeviceTelemetryIfClient(force = true)
        }
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

    fun startMonitoring(): Boolean {
        if (_uiState.value.networkRole == SessionNetworkRole.HOST && !canStartMonitoring()) {
            _uiState.value = _uiState.value.copy(lastError = "Assign start and stop devices before monitoring")
            return false
        }
        val anchorDeviceId = _uiState.value.devices.firstOrNull { it.role == SessionDeviceRole.START }?.id
        if (_uiState.value.networkRole == SessionNetworkRole.HOST && anchorDeviceId == null) {
            _uiState.value = _uiState.value.copy(lastError = "Start role anchor is required before monitoring")
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
            anchorDeviceId = anchorDeviceId,
            anchorState = if (anchorDeviceId == null) SessionAnchorState.READY else SessionAnchorState.ACTIVE,
            lastError = null,
        )
        refreshLockReasonFromState()

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        } else if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            val endpointId = _uiState.value.connectedEndpoints.firstOrNull()
            if (endpointId != null && !_uiState.value.clockSyncInProgress) {
                startClockSyncBurstInternal(endpointId, DEFAULT_CLOCK_SYNC_SAMPLE_COUNT, ClockSyncBurstReason.STALE)
            }
            publishDeviceTelemetryIfClient(force = true)
        }
        return true
    }

    fun stopMonitoring() {
        _uiState.value = _uiState.value.copy(
            stage = SessionStage.LOBBY,
            monitoringActive = false,
            anchorState = SessionAnchorState.READY,
            lastError = null,
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        }
    }

    fun stopHostingAndReturnToSetup() {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        if (_uiState.value.monitoringActive) {
            stopMonitoring()
        }
        setNetworkRole(SessionNetworkRole.NONE)
    }

    fun resetRun() {
        val nextRunId = if (_uiState.value.monitoringActive) UUID.randomUUID().toString() else null
        _uiState.value = _uiState.value.copy(
            timeline = SessionRaceTimeline(),
            latestCompletedTimeline = null,
            runId = nextRunId,
            lastEvent = "run_reset",
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        } else if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            val endpointId = _uiState.value.connectedEndpoints.firstOrNull()
            if (endpointId != null && !_uiState.value.clockSyncInProgress) {
                startClockSyncBurstInternal(endpointId, DEFAULT_CLOCK_SYNC_SAMPLE_COUNT, ClockSyncBurstReason.STALE)
            }
            publishDeviceTelemetryIfClient(force = true)
        }
    }

    fun onLocalMotionTrigger(triggerType: String, splitIndex: Int, triggerSensorNanos: Long) {
        if (!_uiState.value.monitoringActive) {
            return
        }

        if (_uiState.value.operatingMode == SessionOperatingMode.SINGLE_DEVICE) {
            handleSingleDeviceTrigger(triggerSensorNanos)
            return
        }

        val role = localDeviceRole()
        if (role == SessionDeviceRole.UNASSIGNED) {
            return
        }
        val roleLabel = role.name.lowercase()
        _uiState.value = _uiState.value.copy(lastEvent = "local_trigger_detected_$roleLabel")

        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            val mappedType = roleToTriggerType(role)
            if (mappedType == null) {
                return
            }
            ingestLocalTrigger(
                triggerType = mappedType,
                splitIndex = splitIndexForRole(role) ?: splitIndex,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = true,
            )
            broadcastSnapshotIfHost()
            return
        }

        if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            val sourceElapsedNanos = _clockState.value.localSensorMinusElapsedNanos?.let { localOffset ->
                ClockDomain.sensorToElapsedNanos(triggerSensorNanos, localOffset)
            } ?: nowElapsedNanos()
            val mappedHostSensorNanos = mapClientSensorToHostSensor(triggerSensorNanos)
            val request = SessionTriggerRequestMessage(
                role = role,
                triggerSensorNanos = triggerSensorNanos,
                mappedHostSensorNanos = mappedHostSensorNanos,
                sourceDeviceId = localDeviceId,
                sourceElapsedNanos = sourceElapsedNanos,
                mappedAnchorElapsedNanos = null,
            ).toJsonString()
            val mappedState = if (mappedHostSensorNanos != null) "present" else "missing"
            _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_sent_${roleLabel}_mapped_$mappedState")
            sendToHost(request)
        }
    }

    fun totalDeviceCount(): Int {
        return _uiState.value.devices.size
    }

    fun canShowSplitControls(): Boolean {
        return localDeviceRole().isSplitCheckpointRole() ||
            _uiState.value.devices.any { it.role.isSplitCheckpointRole() }
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

    fun startClockSyncBurst(endpointId: String, sampleCount: Int = DEFAULT_CLOCK_SYNC_SAMPLE_COUNT) {
        startClockSyncBurstInternal(endpointId, sampleCount, ClockSyncBurstReason.MANUAL)
    }

    private fun startClockSyncBurstInternal(
        endpointId: String,
        sampleCount: Int,
        reason: ClockSyncBurstReason,
    ) {
        if (!_uiState.value.connectedEndpoints.contains(endpointId)) {
            _uiState.value = _uiState.value.copy(lastError = "Clock sync ignored: endpoint not connected")
            return
        }
        switchClockSyncEndpoint(endpointId)
        activeClockSyncBurstToken += 1L
        val burstToken = activeClockSyncBurstToken
        val now = nowElapsedNanos()
        lastBurstStartedElapsedNanos = now
        lastBurstReason = reason
        nextStaleBurstCheckElapsedNanos = now + millisToNanos(BASE_STALE_CHECK_INTERVAL_MS)
        if (reason == ClockSyncBurstReason.DRIFT) {
            driftRiseDetectedAtElapsedNanos = null
        }
        _uiState.value = _uiState.value.copy(clockSyncInProgress = true, lastError = null)
        _uiState.value = _uiState.value.copy(
            lastEvent = when (reason) {
                ClockSyncBurstReason.MANUAL -> "clock_sync_burst_started"
                ClockSyncBurstReason.STALE -> "clock_sync_burst_started_stale"
                ClockSyncBurstReason.DRIFT -> "clock_sync_burst_started_drift"
            },
        )
        _clockState.value = _clockState.value.copy(lockReason = SessionClockLockReason.LOCK_STALE)
        clearClockSyncBurstQueues()
        clockSyncBurstDispatchCompleted = false

        val totalSamples = sampleCount.coerceAtLeast(3)
        viewModelScope.launch(ioDispatcher) {
            repeat(totalSamples) { sampleIndex ->
                if (burstToken != activeClockSyncBurstToken) {
                    return@launch
                }
                if (sampleIndex > 0) {
                    clockSyncDelay(CLOCK_SYNC_BURST_STAGGER_MILLIS)
                }
                if (burstToken != activeClockSyncBurstToken) {
                    return@launch
                }
                sendClockSyncRequest(endpointId)
            }
            if (burstToken != activeClockSyncBurstToken) {
                return@launch
            }
            clockSyncBurstDispatchCompleted = true
            maybeFinishClockSyncBurst()
        }
    }

    private fun sendClockSyncRequest(endpointId: String) {
        val sendElapsedNanos = nextUniqueClientSendElapsedNanos()
        pendingClockSyncSamplesByClientSendNanos[sendElapsedNanos] = sendElapsedNanos
        scheduleClockSyncSampleExpiry(sendElapsedNanos)
        val requestBytes = SessionClockSyncBinaryCodec.encodeRequest(
            SessionClockSyncBinaryRequest(clientSendElapsedNanos = sendElapsedNanos),
        )
        sendClockSyncPayload(endpointId, requestBytes) { result ->
            result.exceptionOrNull()?.let { error ->
                pendingClockSyncSamplesByClientSendNanos.remove(sendElapsedNanos)
                _uiState.value = _uiState.value.copy(
                    lastError = "Clock sync send failed: ${error.localizedMessage ?: "unknown"}",
                )
                maybeFinishClockSyncBurst()
            }
        }
    }

    private fun scheduleClockSyncSampleExpiry(sendElapsedNanos: Long) {
        viewModelScope.launch(ioDispatcher) {
            kotlinx.coroutines.delay(CLOCK_SYNC_SAMPLE_EXPIRY_MILLIS)
            val removed = pendingClockSyncSamplesByClientSendNanos.remove(sendElapsedNanos)
            if (removed != null) {
                maybeFinishClockSyncBurst()
            }
        }
    }

    private fun nextUniqueClientSendElapsedNanos(): Long {
        var candidate = nowElapsedNanos()
        while (pendingClockSyncSamplesByClientSendNanos.containsKey(candidate)) {
            candidate += 1L
        }
        return candidate
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
            splitIndex = splitIndex.takeIf { triggerType.equals("split", ignoreCase = true) },
            triggerSensorNanos = triggerSensorNanos,
        ).toJsonString()
        broadcastToConnected(message)
        broadcastTimelineSnapshot(updated)
    }

    fun updateClockState(
        hostMinusClientElapsedNanos: Long? = _clockState.value.hostMinusClientElapsedNanos,
        hostSensorMinusElapsedNanos: Long? = _clockState.value.hostSensorMinusElapsedNanos,
        localSensorMinusElapsedNanos: Long? = _clockState.value.localSensorMinusElapsedNanos,
        localGpsUtcOffsetNanos: Long? = _clockState.value.localGpsUtcOffsetNanos,
        localGpsFixAgeNanos: Long? = _clockState.value.localGpsFixAgeNanos,
        hostGpsUtcOffsetNanos: Long? = _clockState.value.hostGpsUtcOffsetNanos,
        hostGpsFixAgeNanos: Long? = _clockState.value.hostGpsFixAgeNanos,
        lastClockSyncElapsedNanos: Long? = _clockState.value.lastClockSyncElapsedNanos,
        hostClockRoundTripNanos: Long? = _clockState.value.hostClockRoundTripNanos,
    ) {
        val previousHostOffset = _clockState.value.hostSensorMinusElapsedNanos

        _clockState.value = RaceSessionClockState(
            hostMinusClientElapsedNanos = hostMinusClientElapsedNanos,
            hostSensorMinusElapsedNanos = hostSensorMinusElapsedNanos,
            localSensorMinusElapsedNanos = localSensorMinusElapsedNanos,
            localGpsUtcOffsetNanos = localGpsUtcOffsetNanos,
            localGpsFixAgeNanos = localGpsFixAgeNanos,
            hostGpsUtcOffsetNanos = hostGpsUtcOffsetNanos,
            hostGpsFixAgeNanos = hostGpsFixAgeNanos,
            lastClockSyncElapsedNanos = lastClockSyncElapsedNanos,
            hostClockRoundTripNanos = hostClockRoundTripNanos,
        )
        updateRecentHostOffsetSamples(hostSensorMinusElapsedNanos)
        detectHostOffsetDriftRise(previousHostOffset, hostSensorMinusElapsedNanos)

        // If we are the host and our camera just booted or heavily drifted/restarted, inform clients so they can map.
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            val oldVal = previousHostOffset ?: 0L
            val newVal = hostSensorMinusElapsedNanos ?: 0L
            if (previousHostOffset == null && hostSensorMinusElapsedNanos != null) {
                broadcastSnapshotIfHost()
            } else if (previousHostOffset != null && hostSensorMinusElapsedNanos != null) {
                if (abs(newVal - oldVal) > 50_000_000L) {
                    broadcastSnapshotIfHost()
                }
            }
        }
        refreshLockReasonFromState()
        publishDeviceTelemetryIfClient()
    }

    internal fun runAdaptiveClockSyncTickForTest() {
        maybeRunAdaptiveClockSyncTick()
    }

    private fun maybeRunAdaptiveClockSyncTick() {
        val state = _uiState.value
        if (state.networkRole != SessionNetworkRole.CLIENT) {
            return
        }
        if (state.stage != SessionStage.LOBBY && state.stage != SessionStage.MONITORING) {
            return
        }
        val endpointId = preferredClockSyncEndpoint(state.connectedEndpoints) ?: return
        if (state.clockSyncInProgress) {
            return
        }
        val now = nowElapsedNanos()
        val reason = shouldStartAdaptiveBurst(now) ?: return
        when (evaluateAdaptiveBurstDecision(now)) {
            AdaptiveClockSyncDecision.START -> {
                if (reason == ClockSyncBurstReason.STALE) {
                    nextStaleBurstCheckElapsedNanos = now + millisToNanos(BASE_STALE_CHECK_INTERVAL_MS)
                }
                startClockSyncBurstInternal(endpointId, DEFAULT_CLOCK_SYNC_SAMPLE_COUNT, reason)
            }

            AdaptiveClockSyncDecision.RATE_LIMITED -> {
                if (reason == ClockSyncBurstReason.STALE) {
                    nextStaleBurstCheckElapsedNanos = now + millisToNanos(BASE_STALE_CHECK_INTERVAL_MS)
                }
                maybeEmitRateLimitedEvent(now)
            }
        }
    }

    private fun shouldStartAdaptiveBurst(nowElapsedNanos: Long): ClockSyncBurstReason? {
        val driftDetectedAt = driftRiseDetectedAtElapsedNanos
        if (driftDetectedAt != null) {
            if (nowElapsedNanos - driftDetectedAt >= millisToNanos(POST_DRIFT_COOLDOWN_MS)) {
                return ClockSyncBurstReason.DRIFT
            }
            return null
        }
        val lockStale = !hasFreshClockLock()
        if (!lockStale) {
            return null
        }
        if (nowElapsedNanos < nextStaleBurstCheckElapsedNanos) {
            return null
        }
        return ClockSyncBurstReason.STALE
    }

    private fun evaluateAdaptiveBurstDecision(nowElapsedNanos: Long): AdaptiveClockSyncDecision {
        val lastStartedAt = lastBurstStartedElapsedNanos
        if (lastStartedAt != null &&
            nowElapsedNanos - lastStartedAt < millisToNanos(MIN_BURST_SPACING_MS)
        ) {
            return AdaptiveClockSyncDecision.RATE_LIMITED
        }
        return AdaptiveClockSyncDecision.START
    }

    private fun maybeEmitRateLimitedEvent(nowElapsedNanos: Long) {
        val lastEventAt = lastRateLimitedEventElapsedNanos
        if (lastEventAt != null &&
            nowElapsedNanos - lastEventAt < millisToNanos(POST_DRIFT_COOLDOWN_MS)
        ) {
            return
        }
        lastRateLimitedEventElapsedNanos = nowElapsedNanos
        _uiState.value = _uiState.value.copy(lastEvent = "clock_sync_skipped_rate_limited")
    }

    private fun updateRecentHostOffsetSamples(hostSensorMinusElapsedNanos: Long?) {
        val sample = hostSensorMinusElapsedNanos ?: return
        recentHostOffsetSamplesNanos.addLast(sample)
        while (recentHostOffsetSamplesNanos.size > RECENT_HOST_OFFSET_SAMPLE_LIMIT) {
            recentHostOffsetSamplesNanos.removeFirst()
        }
    }

    private fun detectHostOffsetDriftRise(previousHostOffset: Long?, hostSensorMinusElapsedNanos: Long?) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }
        val previous = previousHostOffset ?: return
        val current = hostSensorMinusElapsedNanos ?: return
        if (abs(current - previous) <= DRIFT_JUMP_THRESHOLD_NANOS) {
            return
        }
        driftRiseDetectedAtElapsedNanos = nowElapsedNanos()
    }

    private fun resetAdaptiveClockSyncPolicyState() {
        recentHostOffsetSamplesNanos.clear()
        lastBurstStartedElapsedNanos = null
        lastBurstReason = null
        driftRiseDetectedAtElapsedNanos = null
        nextStaleBurstCheckElapsedNanos = 0L
        lastRateLimitedEventElapsedNanos = null
    }

    private fun clearClockSyncBurstQueues() {
        pendingClockSyncSamplesByClientSendNanos.clear()
        acceptedClockSyncSamples.clear()
        acceptedClockSampleCounter = 0
    }

    private fun preferredClockSyncEndpoint(connectedEndpoints: Set<String>): String? {
        val active = activeClockSyncEndpointId
        if (active != null && connectedEndpoints.contains(active)) {
            return active
        }
        return connectedEndpoints.firstOrNull()
    }

    private fun switchClockSyncEndpoint(endpointId: String?) {
        val normalizedEndpointId = endpointId?.trim()?.ifBlank { null }
        if (activeClockSyncEndpointId == normalizedEndpointId) {
            return
        }

        activeClockSyncEndpointId = normalizedEndpointId
        activeClockSyncBurstToken += 1L
        clearClockSyncBurstQueues()
        clockSyncBurstDispatchCompleted = false
        resetAdaptiveClockSyncPolicyState()

        _clockState.value = _clockState.value.copy(
            hostMinusClientElapsedNanos = null,
            hostSensorMinusElapsedNanos = null,
            hostGpsUtcOffsetNanos = null,
            hostGpsFixAgeNanos = null,
            lastClockSyncElapsedNanos = null,
            hostClockRoundTripNanos = null,
            lockReason = SessionClockLockReason.LOCK_STALE,
        )
        _uiState.value = _uiState.value.copy(clockSyncInProgress = false)
    }

    private fun millisToNanos(value: Long): Long = value * 1_000_000L

    private fun refreshLockReasonFromState() {
        val nextReason = when {
            _uiState.value.anchorState == SessionAnchorState.LOST -> SessionClockLockReason.ANCHOR_LOST
            _uiState.value.anchorDeviceId == null -> SessionClockLockReason.NO_ANCHOR
            _uiState.value.networkRole == SessionNetworkRole.HOST -> SessionClockLockReason.OK
            hasFreshAnyClockLock() -> SessionClockLockReason.OK
            else -> SessionClockLockReason.LOCK_STALE
        }
        _clockState.value = _clockState.value.copy(lockReason = nextReason)
    }

    fun mapClientSensorToHostSensor(clientSensorNanos: Long): Long? {
        val state = _clockState.value
        val hostSensorMinusElapsedNanos = state.hostSensorMinusElapsedNanos ?: return null
        val hostMinusClientElapsedNanos = currentHostMinusClientElapsedNanos() ?: return null
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
        val hostMinusClientElapsedNanos = currentHostMinusClientElapsedNanos() ?: return null
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

    fun hasFreshClockLock(maxAcceptedRoundTripNanos: Long = MAX_VALID_LOCK_ROUND_TRIP_NANOS): Boolean {
        val state = _clockState.value
        val hostMinusClientElapsedNanos = state.hostMinusClientElapsedNanos ?: return false
        if (hostMinusClientElapsedNanos == Long.MIN_VALUE) {
            return false
        }
        val roundTripNanos = state.hostClockRoundTripNanos ?: return false
        return roundTripNanos in 0L..maxAcceptedRoundTripNanos
    }

    fun hasFreshAnyClockLock(): Boolean {
        return hasFreshClockLock()
    }

    private fun currentHostMinusClientElapsedNanos(): Long? {
        return _clockState.value.hostMinusClientElapsedNanos
    }

    private fun handleIncomingPayload(endpointId: String, rawMessage: String) {
        SessionClockResyncRequestMessage.tryParse(rawMessage)?.let { resyncRequest ->
            handleClockResyncRequest(endpointId, resyncRequest)
            return
        }

        SessionDeviceConfigUpdateMessage.tryParse(rawMessage)?.let { configUpdate ->
            handleRemoteConfigUpdate(configUpdate)
            return
        }

        SessionDeviceTelemetryMessage.tryParse(rawMessage)?.let { telemetry ->
            handleDeviceTelemetry(endpointId, telemetry)
            return
        }

        SessionDeviceIdentityMessage.tryParse(rawMessage)?.let { identity ->
            handleDeviceIdentity(endpointId, identity)
            return
        }

        SessionSnapshotMessage.tryParse(rawMessage)?.let { snapshot ->
            applySnapshot(snapshot)
            return
        }

        SessionTriggerRequestMessage.tryParse(rawMessage)?.let { request ->
            handleTriggerRequest(endpointId, request)
            return
        }

        SessionTriggerMessage.tryParse(rawMessage)?.let { trigger ->
            val triggerSensorNanos = if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
                val mapped = mapHostSensorToLocalSensor(trigger.triggerSensorNanos)
                if (mapped == null) {
                    if (
                        trigger.triggerType.equals("start", ignoreCase = true) &&
                        _uiState.value.monitoringActive &&
                        _uiState.value.timeline.hostStartSensorNanos == null
                    ) {
                        val fallbackStart = estimateLocalSensorNanosNow()
                        ingestLocalTrigger(
                            triggerType = "start",
                            splitIndex = 0,
                            triggerSensorNanos = fallbackStart,
                            broadcast = false,
                        )
                        _uiState.value = _uiState.value.copy(lastEvent = "trigger_start_applied_unsynced")
                    } else if (
                        trigger.triggerType.equals("stop", ignoreCase = true) &&
                        _uiState.value.monitoringActive &&
                        _uiState.value.timeline.hostStartSensorNanos != null &&
                        _uiState.value.timeline.hostStopSensorNanos == null
                    ) {
                        val started = _uiState.value.timeline.hostStartSensorNanos ?: estimateLocalSensorNanosNow()
                        val fallbackStop = maxOf(estimateLocalSensorNanosNow(), started + 1L)
                        ingestLocalTrigger(
                            triggerType = "stop",
                            splitIndex = 0,
                            triggerSensorNanos = fallbackStop,
                            broadcast = false,
                        )
                        _uiState.value = _uiState.value.copy(lastEvent = "trigger_stop_applied_unsynced")
                    } else {
                        _uiState.value = _uiState.value.copy(lastEvent = "trigger_dropped_unsynced")
                    }
                    return
                }
                mapped
            } else {
                trigger.triggerSensorNanos
            }
            ingestLocalTrigger(
                triggerType = trigger.triggerType,
                splitIndex = trigger.splitIndex ?: 0,
                triggerSensorNanos = triggerSensorNanos,
                broadcast = false,
            )
            return
        }

        SessionTimelineSnapshotMessage.tryParse(rawMessage)?.let { snapshot ->
            ingestTimelineSnapshot(snapshot)
            return
        }
    }

    private fun handleConnectionResult(event: SessionConnectionEvent.ConnectionResult) {
        val stableIdForEndpoint = stableDeviceIdByEndpointId[event.endpointId]
        val nextConnected = if (event.connected) {
            if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
                _uiState.value.connectedEndpoints + event.endpointId
            } else {
                setOf(event.endpointId)
            }
        } else {
            _uiState.value.connectedEndpoints - event.endpointId
        }
        if (!event.connected) {
            clearIdentityMappingForEndpoint(event.endpointId)
            lastPublishedTelemetryFingerprint = null
        }
        val nextDevices = if (event.connected) {
            val endpointName = event.endpointName
                ?: _uiState.value.discoveredEndpoints[event.endpointId]
                ?: event.endpointId
            val knownStableDeviceId = stableDeviceIdByEndpointId[event.endpointId]
            val stableEndpoint = knownStableDeviceId?.let { endpointIdByStableDeviceId[it] }
            val stableEntry = stableEndpoint?.let { stableId ->
                _uiState.value.devices.firstOrNull { existing -> !existing.isLocal && existing.id == stableId }
            }
            val existingForEndpoint = _uiState.value.devices.firstOrNull { existing ->
                !existing.isLocal && existing.id == event.endpointId
            }
            val preserved = stableEntry ?: existingForEndpoint
            val reconciled = (
                preserved ?: SessionDevice(
                    id = event.endpointId,
                    name = endpointName,
                    role = SessionDeviceRole.UNASSIGNED,
                    isLocal = false,
                )
                ).copy(
                id = event.endpointId,
                name = endpointName,
                isLocal = false,
            )
            val dedupedDevices = _uiState.value.devices.filterNot { existing ->
                !existing.isLocal && (
                    existing.id == event.endpointId ||
                        (stableEndpoint != null && stableEndpoint != event.endpointId && existing.id == stableEndpoint)
                    )
            } + reconciled
            ensureLocalDevice(
                localDeviceFromState(),
                pruneOrphanedNonLocalDevices(
                    devices = dedupedDevices,
                    connectedEndpoints = nextConnected,
                ),
            )
        } else {
            ensureLocalDevice(
                localDeviceFromState(),
                pruneOrphanedNonLocalDevices(
                    devices = _uiState.value.devices,
                    connectedEndpoints = nextConnected,
                ),
            )
        }

        val devicesWithDefaults = if (event.connected && _uiState.value.networkRole == SessionNetworkRole.HOST) {
            applyHostAutoRoleDefaults(nextDevices)
        } else {
            nextDevices
        }

        _uiState.value = _uiState.value.copy(
            connectedEndpoints = nextConnected,
            devices = devicesWithDefaults,
            remoteDeviceTelemetry = if (!event.connected && stableIdForEndpoint != null) {
                _uiState.value.remoteDeviceTelemetry - stableIdForEndpoint
            } else {
                _uiState.value.remoteDeviceTelemetry
            },
            deviceRole = localDeviceRole(),
            anchorDeviceId = devicesWithDefaults.firstOrNull { it.role == SessionDeviceRole.START }?.id,
            anchorState = when {
                _uiState.value.monitoringActive && _uiState.value.anchorState == SessionAnchorState.LOST -> _uiState.value.anchorState
                _uiState.value.monitoringActive -> SessionAnchorState.ACTIVE
                else -> SessionAnchorState.READY
            },
            lastError = if (event.connected) null else (event.statusMessage ?: "Connection failed"),
            lastEvent = "connection_result",
        )

        if (event.connected) {
            sendIdentityHandshake(event.endpointId)
            if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
                switchClockSyncEndpoint(event.endpointId)
                if (!_uiState.value.clockSyncInProgress) {
                    startClockSyncBurstInternal(event.endpointId, DEFAULT_CLOCK_SYNC_SAMPLE_COUNT, ClockSyncBurstReason.STALE)
                }
                publishDeviceTelemetryIfClient(force = true)
            }
            if (_uiState.value.anchorDeviceId != null) {
                _clockState.value = _clockState.value.copy(lockReason = SessionClockLockReason.LOCK_STALE)
                if (_uiState.value.monitoringActive && _uiState.value.anchorState == SessionAnchorState.LOST) {
                    _uiState.value = _uiState.value.copy(anchorState = SessionAnchorState.ACTIVE, lastError = null)
                }
            }
        } else if (_uiState.value.networkRole == SessionNetworkRole.CLIENT && activeClockSyncEndpointId == event.endpointId) {
            switchClockSyncEndpoint(nextConnected.firstOrNull())
        }
        refreshLockReasonFromState()
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        } else {
            publishDeviceTelemetryIfClient()
        }
    }

    private fun handleTriggerRequest(endpointId: String, request: SessionTriggerRequestMessage) {
        _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_received_${request.role.name.lowercase()}")
        if (_uiState.value.networkRole != SessionNetworkRole.HOST || !_uiState.value.monitoringActive) {
            return
        }
        if (_uiState.value.anchorState == SessionAnchorState.LOST) {
            _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_rejected_anchor_lost")
            return
        }
        val senderRole = _uiState.value.devices
            .firstOrNull { !it.isLocal && it.id == endpointId }
            ?.role
            ?: SessionDeviceRole.UNASSIGNED
        if (senderRole == SessionDeviceRole.UNASSIGNED || senderRole != request.role) {
            _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_rejected")
            return
        }
        if (_uiState.value.anchorDeviceId == null) {
            _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_rejected_no_anchor")
            return
        }
        if (senderRole == SessionDeviceRole.START && _uiState.value.anchorDeviceId != endpointId) {
            _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_rejected_anchor_mismatch")
            return
        }
        if (request.mappedHostSensorNanos == null) {
            _uiState.value = _uiState.value.copy(lastEvent = "trigger_request_rejected_unsynced")
            return
        }
        val mappedType = roleToTriggerType(senderRole)
        if (mappedType == null) {
            return
        }
        val hostSensorNanos = request.mappedHostSensorNanos
        ingestLocalTrigger(
            triggerType = mappedType,
            splitIndex = splitIndexForRole(senderRole) ?: 0,
            triggerSensorNanos = hostSensorNanos,
            broadcast = true,
        )
        broadcastSnapshotIfHost()
    }

    private fun applySnapshot(snapshot: SessionSnapshotMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }

        updateClockState(
            hostSensorMinusElapsedNanos = snapshot.hostSensorMinusElapsedNanos
                ?: _clockState.value.hostSensorMinusElapsedNanos,
            hostGpsUtcOffsetNanos = snapshot.hostGpsUtcOffsetNanos
                ?: _clockState.value.hostGpsUtcOffsetNanos,
            hostGpsFixAgeNanos = snapshot.hostGpsFixAgeNanos
                ?: _clockState.value.hostGpsFixAgeNanos,
        )

        val resolvedSelfId = snapshot.selfDeviceId ?: localDeviceId
        localDeviceId = resolvedSelfId
        val mappedDevices = snapshot.devices.map { device ->
            device.copy(isLocal = device.id == resolvedSelfId)
        }

        val mappedStart = snapshot.hostStartSensorNanos?.let { mapHostSensorToLocalSensor(it) }
        val mappedStop = snapshot.hostStopSensorNanos?.let { mapHostSensorToLocalSensor(it) }
        val mappedSplits = snapshot.hostSplitMarks.mapNotNull { splitMark ->
            mapHostSensorToLocalSensor(splitMark.hostSensorNanos)?.let { mappedSensor ->
                splitMark.copy(hostSensorNanos = mappedSensor)
            }
        }
        val mappingAvailable =
            (snapshot.hostStartSensorNanos == null || mappedStart != null) &&
                (snapshot.hostStopSensorNanos == null || mappedStop != null) &&
                (snapshot.hostSplitMarks.size == mappedSplits.size)
        val timeline = if (mappingAvailable) {
            SessionRaceTimeline(
                hostStartSensorNanos = mappedStart,
                hostStopSensorNanos = mappedStop,
                hostSplitMarks = mappedSplits,
            )
        } else {
            _uiState.value.timeline
        }
        val snapshotEvent = if (mappingAvailable) {
            "snapshot_applied"
        } else {
            "snapshot_applied_unsynced_timeline_ignored"
        }

        _uiState.value = _uiState.value.copy(
            stage = snapshot.stage,
            monitoringActive = snapshot.monitoringActive,
            runId = snapshot.runId,
            anchorDeviceId = snapshot.anchorDeviceId ?: _uiState.value.anchorDeviceId,
            anchorState = snapshot.anchorState ?: _uiState.value.anchorState,
            devices = ensureLocalDevice(
                SessionDevice(
                    id = resolvedSelfId,
                    name = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.name ?: localDeviceName(),
                    role = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.role ?: SessionDeviceRole.UNASSIGNED,
                    cameraFacing = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.cameraFacing ?: SessionCameraFacing.REAR,
                    isLocal = true,
                ),
                mappedDevices,
            ),
            deviceRole = mappedDevices.firstOrNull { it.id == resolvedSelfId }?.role ?: SessionDeviceRole.UNASSIGNED,
            timeline = timeline,
            lastEvent = snapshotEvent,
            lastError = null,
        )
        refreshLockReasonFromState()

        maybePersistCompletedRun(timeline)
        publishDeviceTelemetryIfClient(force = true)
    }

    private fun handleClockSyncResponseSample(response: SessionClockSyncBinaryResponse) {
        val receiveElapsedNanos = nowElapsedNanos()
        val sentElapsedNanos = pendingClockSyncSamplesByClientSendNanos.remove(response.clientSendElapsedNanos)
            ?: return
        val roundTripNanos = receiveElapsedNanos - sentElapsedNanos
        if (roundTripNanos > MAX_ACCEPTED_ROUND_TRIP_NANOS) {
            maybeFinishClockSyncBurst()
            return
        }
        val offset = (
            (response.hostReceiveElapsedNanos - response.clientSendElapsedNanos) +
                (response.hostSendElapsedNanos - receiveElapsedNanos)
            ) / 2L
        acceptedClockSyncSamples += AcceptedClockSyncSample(
            offsetNanos = offset,
            roundTripNanos = roundTripNanos,
            acceptOrder = acceptedClockSampleCounter++,
        )
        maybeFinishClockSyncBurst()
    }

    private fun maybeFinishClockSyncBurst() {
        if (!clockSyncBurstDispatchCompleted) {
            return
        }
        if (pendingClockSyncSamplesByClientSendNanos.isNotEmpty()) {
            return
        }
        val selectedSample = acceptedClockSyncSamples.minWithOrNull(
            compareBy<AcceptedClockSyncSample> { it.roundTripNanos }
                .thenBy { it.acceptOrder },
        )
        if (selectedSample != null) {
            updateClockState(
                hostMinusClientElapsedNanos = selectedSample.offsetNanos,
                hostClockRoundTripNanos = selectedSample.roundTripNanos,
                lastClockSyncElapsedNanos = nowElapsedNanos(),
            )
            _uiState.value = _uiState.value.copy(clockSyncInProgress = false, lastEvent = "clock_sync_complete")
        } else {
            _clockState.value = _clockState.value.copy(lockReason = SessionClockLockReason.LOCK_STALE)
            _uiState.value = _uiState.value.copy(
                clockSyncInProgress = false,
                lastEvent = "clock_sync_failed",
                lastError = "Clock sync failed: no acceptable samples",
            )
        }
        refreshLockReasonFromState()
        acceptedClockSyncSamples.clear()
        clockSyncBurstDispatchCompleted = false
    }

    private fun ingestTimelineSnapshot(snapshot: SessionTimelineSnapshotMessage) {
        val localTimeline = if (_uiState.value.networkRole == SessionNetworkRole.CLIENT) {
            val localStart = snapshot.hostStartSensorNanos?.let { hostStart ->
                mapHostSensorToLocalSensor(hostStart)
            }
            if (snapshot.hostStartSensorNanos != null && localStart == null) {
                _uiState.value = _uiState.value.copy(lastEvent = "timeline_snapshot_dropped_unsynced")
                return
            }
            val localStop = snapshot.hostStopSensorNanos?.let { hostStop ->
                mapHostSensorToLocalSensor(hostStop)
            }
            if (snapshot.hostStopSensorNanos != null && localStop == null) {
                _uiState.value = _uiState.value.copy(lastEvent = "timeline_snapshot_dropped_unsynced")
                return
            }
            SessionRaceTimeline(
                hostStartSensorNanos = localStart,
                hostStopSensorNanos = localStop,
                hostSplitMarks = snapshot.hostSplitMarks.mapNotNull { splitMark ->
                    mapHostSensorToLocalSensor(splitMark.hostSensorNanos)?.let { mappedSensor ->
                        splitMark.copy(hostSensorNanos = mappedSensor)
                    }
                },
            )
        } else {
            SessionRaceTimeline(
                hostStartSensorNanos = snapshot.hostStartSensorNanos,
                hostStopSensorNanos = snapshot.hostStopSensorNanos,
                hostSplitMarks = snapshot.hostSplitMarks,
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

            "stop" -> {
                if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
                    null
                } else {
                    timeline.copy(hostStopSensorNanos = triggerSensorNanos)
                }
            }

            "split" -> {
                if (timeline.hostStartSensorNanos == null || timeline.hostStopSensorNanos != null) {
                    null
                } else {
                    val splitRole = splitRoleFromIndex(splitIndex)
                    if (timeline.hostSplitMarks.any { it.role == splitRole }) {
                        return null
                    }
                    val lastMarker = timeline.hostSplitMarks.lastOrNull()?.hostSensorNanos ?: timeline.hostStartSensorNanos
                    if (triggerSensorNanos <= lastMarker) {
                        null
                    } else {
                        timeline.copy(
                            hostSplitMarks = timeline.hostSplitMarks + SessionSplitMark(
                                role = splitRole,
                                hostSensorNanos = triggerSensorNanos,
                            ),
                        )
                    }
                }
            }

            else -> null
        }
    }

    private fun handleSingleDeviceTrigger(triggerSensorNanos: Long) {
        val current = _uiState.value.timeline
        if (current.hostStartSensorNanos == null) {
            _uiState.value = _uiState.value.copy(
                timeline = current.copy(hostStartSensorNanos = triggerSensorNanos),
                runId = UUID.randomUUID().toString(),
                lastEvent = "single_device_start",
            )
            return
        }
        if (current.hostStopSensorNanos != null) {
            return
        }
        if (triggerSensorNanos <= current.hostStartSensorNanos) {
            return
        }
        val completed = current.copy(hostStopSensorNanos = triggerSensorNanos)
        maybePersistCompletedRun(completed)
        _uiState.value = _uiState.value.copy(
            timeline = SessionRaceTimeline(),
            latestCompletedTimeline = completed,
            runId = UUID.randomUUID().toString(),
            lastEvent = "single_device_stop",
        )
    }

    private fun maybePersistCompletedRun(timeline: SessionRaceTimeline) {
        val started = timeline.hostStartSensorNanos ?: return
        val stopped = timeline.hostStopSensorNanos ?: return
        if (stopped <= started) {
            return
        }
        val run = LastRunResult(
            startedSensorNanos = started,
            stoppedSensorNanos = stopped,
        )
        viewModelScope.launch(ioDispatcher) {
            saveLastRun(run)
        }
    }

    private fun broadcastTimelineSnapshot(timeline: SessionRaceTimeline) {
        val payload = SessionTimelineSnapshotMessage(
            hostStartSensorNanos = timeline.hostStartSensorNanos,
            hostStopSensorNanos = timeline.hostStopSensorNanos,
            hostSplitMarks = timeline.hostSplitMarks,
            sentElapsedNanos = nowElapsedNanos(),
        ).toJsonString()
        broadcastToConnected(payload)
    }

    private fun broadcastSnapshotIfHost() {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        val targetEndpoints = _uiState.value.connectedEndpoints
        val canonicalDevices = ensureLocalDevice(
            localDeviceFromState(),
            pruneOrphanedNonLocalDevices(
                devices = _uiState.value.devices,
                connectedEndpoints = targetEndpoints,
            ),
        )
        if (canonicalDevices != _uiState.value.devices) {
            _uiState.value = _uiState.value.copy(
                devices = canonicalDevices,
                deviceRole = localDeviceRole(),
            )
        }
        val devicesForSnapshot = _uiState.value.devices
        targetEndpoints.forEach { endpointId ->
            val payload = SessionSnapshotMessage(
                stage = _uiState.value.stage,
                monitoringActive = _uiState.value.monitoringActive,
                devices = devicesForSnapshot,
                hostStartSensorNanos = _uiState.value.timeline.hostStartSensorNanos,
                hostStopSensorNanos = _uiState.value.timeline.hostStopSensorNanos,
                hostSplitMarks = _uiState.value.timeline.hostSplitMarks,
                runId = _uiState.value.runId,
                hostSensorMinusElapsedNanos = _clockState.value.hostSensorMinusElapsedNanos,
                hostGpsUtcOffsetNanos = _clockState.value.hostGpsUtcOffsetNanos,
                hostGpsFixAgeNanos = _clockState.value.hostGpsFixAgeNanos,
                selfDeviceId = endpointId,
                anchorDeviceId = _uiState.value.anchorDeviceId,
                anchorState = _uiState.value.anchorState,
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

    private fun sendIdentityHandshake(endpointId: String) {
        val payload = SessionDeviceIdentityMessage(
            stableDeviceId = localDeviceId,
            deviceName = localDeviceName(),
        ).toJsonString()
        sendMessage(endpointId, payload) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "identity send failed ($endpointId): ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
    }

    private fun handleDeviceIdentity(endpointId: String, identity: SessionDeviceIdentityMessage) {
        val previousEndpointId = endpointIdByStableDeviceId[identity.stableDeviceId]
        mapStableIdentityToEndpoint(identity.stableDeviceId, endpointId)

        val current = _uiState.value
        val preservedDevice = current.devices.firstOrNull { existing ->
            !existing.isLocal && (
                existing.id == endpointId ||
                    (previousEndpointId != null && previousEndpointId != endpointId && existing.id == previousEndpointId)
                )
        }
        val reconciledDevice = (
            preservedDevice ?: SessionDevice(
                id = endpointId,
                name = identity.deviceName,
                role = SessionDeviceRole.UNASSIGNED,
                isLocal = false,
            )
            ).copy(
            id = endpointId,
            name = identity.deviceName,
            isLocal = false,
        )
        val dedupedDevices = current.devices.filterNot { existing ->
            !existing.isLocal && (
                existing.id == endpointId ||
                    (previousEndpointId != null && previousEndpointId != endpointId && existing.id == previousEndpointId)
                )
        } + reconciledDevice
        val nextDevices = ensureLocalDevice(
            localDeviceFromState(),
            pruneOrphanedNonLocalDevices(
                devices = dedupedDevices,
                connectedEndpoints = current.connectedEndpoints,
            ),
        )
        val devicesWithDefaults = if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            applyHostAutoRoleDefaults(nextDevices)
        } else {
            nextDevices
        }
        _uiState.value = current.copy(
            devices = devicesWithDefaults,
            deviceRole = localDeviceRole(),
            anchorDeviceId = devicesWithDefaults.firstOrNull { it.role == SessionDeviceRole.START }?.id,
            remoteDeviceTelemetry = if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
                val existing = current.remoteDeviceTelemetry[identity.stableDeviceId]
                if (existing == null) {
                    current.remoteDeviceTelemetry
                } else {
                    current.remoteDeviceTelemetry + (
                        identity.stableDeviceId to existing.copy(
                            deviceName = identity.deviceName,
                            role = devicesWithDefaults.firstOrNull { it.id == endpointId }?.role ?: existing.role,
                            connected = true,
                        )
                        )
                }
            } else {
                current.remoteDeviceTelemetry
            },
            lastEvent = "device_identity",
        )
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            broadcastSnapshotIfHost()
        } else {
            publishDeviceTelemetryIfClient(force = true)
        }
    }

    private fun handleDeviceTelemetry(endpointId: String, telemetry: SessionDeviceTelemetryMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.HOST) {
            return
        }
        mapStableIdentityToEndpoint(telemetry.stableDeviceId, endpointId)
        val deviceName =
            _uiState.value.devices.firstOrNull { it.id == endpointId }?.name
                ?: _uiState.value.remoteDeviceTelemetry[telemetry.stableDeviceId]?.deviceName
                ?: telemetry.stableDeviceId
        val next = SessionRemoteDeviceTelemetryState(
            stableDeviceId = telemetry.stableDeviceId,
            deviceName = deviceName,
            role = telemetry.role,
            sensitivity = telemetry.sensitivity.coerceIn(1, 100),
            latencyMs = telemetry.latencyMs,
            clockSynced = telemetry.clockSynced,
            analysisWidth = telemetry.analysisWidth,
            analysisHeight = telemetry.analysisHeight,
            connected = _uiState.value.connectedEndpoints.contains(endpointId),
            timestampMillis = telemetry.timestampMillis,
        )
        _uiState.value = _uiState.value.copy(
            remoteDeviceTelemetry = _uiState.value.remoteDeviceTelemetry + (telemetry.stableDeviceId to next),
            lastEvent = "device_telemetry",
        )
    }

    private fun handleRemoteConfigUpdate(configUpdate: SessionDeviceConfigUpdateMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }
        if (configUpdate.targetStableDeviceId != localDeviceId) {
            return
        }
        _uiState.value = _uiState.value.copy(
            pendingSensitivityUpdateFromHost = configUpdate.sensitivity.coerceIn(1, 100),
            lastEvent = "device_config_update",
        )
    }

    private fun handleClockResyncRequest(endpointId: String, request: SessionClockResyncRequestMessage) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }
        val clockEndpoint = preferredClockSyncEndpoint(_uiState.value.connectedEndpoints) ?: return
        if (endpointId != clockEndpoint) {
            return
        }
        switchClockSyncEndpoint(clockEndpoint)
        startClockSyncBurst(endpointId = clockEndpoint, sampleCount = request.sampleCount.coerceIn(3, 24))
    }

    private fun publishDeviceTelemetryIfClient(force: Boolean = false) {
        if (_uiState.value.networkRole != SessionNetworkRole.CLIENT) {
            return
        }
        val hostEndpointId = _uiState.value.connectedEndpoints.firstOrNull() ?: return
        val latencyMs = _clockState.value.hostClockRoundTripNanos?.let { (it.toDouble() / 1_000_000.0).roundToInt() }
        val message = SessionDeviceTelemetryMessage(
            stableDeviceId = localDeviceId,
            role = localDeviceRole(),
            sensitivity = localSensitivity.coerceIn(1, 100),
            latencyMs = latencyMs,
            clockSynced = hasFreshAnyClockLock(),
            analysisWidth = localAnalysisWidth,
            analysisHeight = localAnalysisHeight,
            timestampMillis = System.currentTimeMillis(),
        )
        val fingerprint = "$hostEndpointId|${message.role.name}|${message.sensitivity}|${message.latencyMs}|${message.clockSynced}|${message.analysisWidth}|${message.analysisHeight}"
        if (!force && fingerprint == lastPublishedTelemetryFingerprint) {
            return
        }
        sendMessage(hostEndpointId, message.toJsonString()) { result ->
            result.exceptionOrNull()?.let { error ->
                _uiState.value = _uiState.value.copy(
                    lastError = "telemetry send failed: ${error.localizedMessage ?: "unknown"}",
                )
            }
        }
        lastPublishedTelemetryFingerprint = fingerprint
    }

    private fun mapStableIdentityToEndpoint(stableDeviceId: String, endpointId: String) {
        val previousForStableDevice = endpointIdByStableDeviceId.put(stableDeviceId, endpointId)
        if (previousForStableDevice != null && previousForStableDevice != endpointId) {
            stableDeviceIdByEndpointId.remove(previousForStableDevice)
        }
        val previousStableForEndpoint = stableDeviceIdByEndpointId.put(endpointId, stableDeviceId)
        if (previousStableForEndpoint != null && previousStableForEndpoint != stableDeviceId) {
            endpointIdByStableDeviceId.remove(previousStableForEndpoint)
        }
    }

    private fun clearIdentityMappingForEndpoint(endpointId: String) {
        val stableDeviceId = stableDeviceIdByEndpointId.remove(endpointId) ?: return
        if (endpointIdByStableDeviceId[stableDeviceId] == endpointId) {
            endpointIdByStableDeviceId.remove(stableDeviceId)
        }
        if (_uiState.value.networkRole == SessionNetworkRole.HOST) {
            _uiState.value = _uiState.value.copy(
                remoteDeviceTelemetry = _uiState.value.remoteDeviceTelemetry - stableDeviceId,
            )
        }
    }

    private fun pruneOrphanedNonLocalDevices(
        devices: List<SessionDevice>,
        connectedEndpoints: Set<String>,
    ): List<SessionDevice> {
        return devices.filter { device ->
            device.isLocal || connectedEndpoints.contains(device.id)
        }
    }

    private fun applyJoinOrderAutoRoleDefaults(devices: List<SessionDevice>): List<SessionDevice> {
        var nextDevices = devices
        val local = nextDevices.firstOrNull { it.isLocal } ?: return devices
        val remotes = nextDevices.filterNot { it.isLocal }
        if (remotes.isEmpty()) {
            return devices
        }

        val remoteStartStopCandidates = nextDevices
            .filterNot { it.isLocal }
            .filter {
                it.role == SessionDeviceRole.UNASSIGNED &&
                    !isTopazClientName(it.name) &&
                    !isHuaweiClientName(it.name)
            }
        if (remoteStartStopCandidates.isNotEmpty()) {
            nextDevices = nextDevices.map { existing ->
                when {
                    existing.id == local.id &&
                        existing.role == SessionDeviceRole.START &&
                        nextDevices.none { !it.isLocal && it.role == SessionDeviceRole.START } -> {
                        existing.copy(role = SessionDeviceRole.UNASSIGNED)
                    }
                    existing.id == local.id &&
                        existing.role == SessionDeviceRole.STOP &&
                        nextDevices.none { !it.isLocal && it.role == SessionDeviceRole.STOP } -> {
                        existing.copy(role = SessionDeviceRole.UNASSIGNED)
                    }
                    else -> existing
                }
            }
        }

        if (nextDevices.none { it.role == SessionDeviceRole.START }) {
            val preferredStartId = nextDevices
                .filterNot { it.isLocal }
                .firstOrNull {
                    it.role == SessionDeviceRole.UNASSIGNED &&
                        !isTopazClientName(it.name) &&
                        !isHuaweiClientName(it.name)
                }
                ?.id
                ?: if (local.role == SessionDeviceRole.UNASSIGNED) local.id else null
            if (preferredStartId != null) {
                nextDevices = nextDevices.map { existing ->
                    if (existing.id == preferredStartId && existing.role == SessionDeviceRole.UNASSIGNED) {
                        existing.copy(role = SessionDeviceRole.START)
                    } else {
                        existing
                    }
                }
            }
        }
        if (nextDevices.none { it.role == SessionDeviceRole.STOP }) {
            val preferredStopId = nextDevices
                .filterNot { it.isLocal }
                .firstOrNull {
                    it.role == SessionDeviceRole.UNASSIGNED &&
                        !isTopazClientName(it.name) &&
                        !isHuaweiClientName(it.name)
                }
                ?.id
                ?: if (nextDevices.any { it.id == local.id && it.role == SessionDeviceRole.UNASSIGNED }) local.id else null
            if (preferredStopId != null) {
                nextDevices = nextDevices.map { existing ->
                    if (existing.id == preferredStopId && existing.role == SessionDeviceRole.UNASSIGNED) {
                        existing.copy(role = SessionDeviceRole.STOP)
                    } else {
                        existing
                    }
                }
            }
        }

        val preferredSplitRolesByPredicate = listOf(
            SessionDeviceRole.SPLIT1 to ::isTopazClientName,
            SessionDeviceRole.SPLIT2 to ::isHuaweiClientName,
        )
        preferredSplitRolesByPredicate.forEach { (role, matcher) ->
            if (nextDevices.none { it.role == role }) {
                val preferredDeviceId = nextDevices
                    .filterNot { it.isLocal }
                    .firstOrNull { it.role == SessionDeviceRole.UNASSIGNED && matcher(it.name) }
                    ?.id
                if (preferredDeviceId != null) {
                    nextDevices = nextDevices.map { existing ->
                        if (existing.id == preferredDeviceId && existing.role == SessionDeviceRole.UNASSIGNED) {
                            existing.copy(role = role)
                        } else {
                            existing
                        }
                    }
                }
            }
        }

        explicitSplitRoles().forEach { splitRole ->
            if (nextDevices.none { it.role == splitRole }) {
                val nextSplitId = nextDevices
                    .filterNot { it.isLocal }
                    .firstOrNull { it.role == SessionDeviceRole.UNASSIGNED }
                    ?.id
                if (nextSplitId != null) {
                    nextDevices = nextDevices.map { existing ->
                        if (existing.id == nextSplitId && existing.role == SessionDeviceRole.UNASSIGNED) {
                            existing.copy(role = splitRole)
                        } else {
                            existing
                        }
                    }
                }
            }
        }

        return nextDevices
    }

    private fun applyHostAutoRoleDefaults(devices: List<SessionDevice>): List<SessionDevice> {
        val shouldPinByFlavor = BuildConfig.HOST_CONTROLLER_ONLY && BuildConfig.AUTO_START_ROLE == "host"
        val pinned = if (shouldPinByFlavor) {
            applyPinnedHostRolesForDeviceProfile(devices, deviceProfile = "host_xiaomi")
        } else {
            applyPinnedHostRolesForDeviceProfile(devices, BuildConfig.DEVICE_PROFILE)
        }
        return applyJoinOrderAutoRoleDefaults(pinned)
    }

    private fun roleToTriggerType(role: SessionDeviceRole): String? {
        return when (role) {
            SessionDeviceRole.START -> "start"
            SessionDeviceRole.SPLIT1,
            SessionDeviceRole.SPLIT2,
            SessionDeviceRole.SPLIT3,
            SessionDeviceRole.SPLIT4,
            -> "split"
            SessionDeviceRole.STOP -> "stop"
            SessionDeviceRole.UNASSIGNED -> null
            SessionDeviceRole.DISPLAY -> null
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
}

internal fun applyPinnedHostRolesForDeviceProfile(
    devices: List<SessionDevice>,
    deviceProfile: String,
): List<SessionDevice> {
    if (!deviceProfile.equals("host_xiaomi", ignoreCase = true)) {
        return devices
    }

    val remoteDevices = devices.filterNot { it.isLocal }
    val oneplusDevice = remoteDevices.firstOrNull { isOnePlusClientName(it.name) }
    val topazDevice = remoteDevices.firstOrNull { isTopazClientName(it.name) }
    val huaweiDevice = remoteDevices.firstOrNull { isHuaweiClientName(it.name) }
    val pixelDevice = remoteDevices.firstOrNull { isPixelClientName(it.name) }

    var mapped = devices.map { device ->
        when (device.id) {
            oneplusDevice?.id -> device.copy(role = SessionDeviceRole.START)
            topazDevice?.id -> device.copy(role = SessionDeviceRole.SPLIT1)
            huaweiDevice?.id -> device.copy(role = SessionDeviceRole.SPLIT2)
            pixelDevice?.id -> device.copy(role = SessionDeviceRole.STOP)
            else -> device
        }
    }

    oneplusDevice?.let { pinned ->
        mapped = mapped.map { existing ->
            if (existing.id != pinned.id && existing.role == SessionDeviceRole.START) {
                existing.copy(role = SessionDeviceRole.UNASSIGNED)
            } else {
                existing
            }
        }
    }
    topazDevice?.let { pinned ->
        mapped = mapped.map { existing ->
            if (existing.id != pinned.id && existing.role == SessionDeviceRole.SPLIT1) {
                existing.copy(role = SessionDeviceRole.UNASSIGNED)
            } else {
                existing
            }
        }
    }
    huaweiDevice?.let { pinned ->
        mapped = mapped.map { existing ->
            if (existing.id != pinned.id && existing.role == SessionDeviceRole.SPLIT2) {
                existing.copy(role = SessionDeviceRole.UNASSIGNED)
            } else {
                existing
            }
        }
    }
    pixelDevice?.let { pinned ->
        mapped = mapped.map { existing ->
            if (existing.id != pinned.id && existing.role == SessionDeviceRole.STOP) {
                existing.copy(role = SessionDeviceRole.UNASSIGNED)
            } else {
                existing
            }
        }
    }

    return mapped
}

internal fun isOnePlusClientName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.contains("oneplus", ignoreCase = true)) {
        return true
    }
    return Regex("^cph\\d{4,}$", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
}

internal fun isHuaweiClientName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.contains("huawei", ignoreCase = true) || trimmed.contains("honor", ignoreCase = true)) {
        return true
    }
    return Regex("^(ane|els|lya|vtr|evr|noh|yas)-", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
}

internal fun isTopazClientName(name: String): Boolean = name.trim().contains("topaz", ignoreCase = true)

internal fun isPixelClientName(name: String): Boolean = name.trim().contains("pixel", ignoreCase = true)
