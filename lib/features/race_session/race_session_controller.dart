import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:sprint_sync/core/services/newrelic_bridge.dart';
import 'package:sprint_sync/core/services/race_session_motion_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/core/services/wake_lock_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

class RaceSessionController extends ChangeNotifier {
  static const int _maxClockSyncRttNanos = 100000000;
  static const int _targetClockSyncRttNanos = 20000000;
  static const int _clockSyncBurstCount = 10;
  static const int _clockSyncBurstTimeoutNanos = 500000000;
  static const int _clockSyncStaleAfterNanos = 5000000000;
  static const int _gpsOffsetStaleAfterNanos = 10000000000;
  static const int _gpsSnapshotRebroadcastMinIntervalNanos = 1000000000;
  static const int _sensorElapsedProjectionMaxAgeNanos = 3000000000;
  static final Stopwatch _elapsedClock = Stopwatch()..start();

  static int _defaultElapsedNanos() {
    return _elapsedClock.elapsedMicroseconds * 1000;
  }

  RaceSessionController({
    required NearbyBridge nearbyBridge,
    required MotionDetectionController motionController,
    Future<void> Function()? startMonitoringAction,
    Future<void> Function()? stopMonitoringAction,
    int Function()? nowElapsedNanos,
    WakeLockBridge? wakeLockBridge,
    NewRelicBridge? newRelicBridge,
  }) : _nearbyBridge = nearbyBridge,
       _motionController = motionController,
       _startMonitoringAction = startMonitoringAction,
       _stopMonitoringAction = stopMonitoringAction,
       _nowElapsedNanos = nowElapsedNanos ?? _defaultElapsedNanos,
       _wakeLockBridge = wakeLockBridge ?? WakeLockBridge(),
       _newRelicBridge = newRelicBridge ?? const NewRelicBridge() {
    _eventsSubscription = _nearbyBridge.events.listen(_onNearbyEvent);
    _motionController.addListener(_onMotionControllerChanged);
    _devices[_localHostDeviceId] = const SessionDevice(
      id: _localHostDeviceId,
      name: 'This device',
      role: SessionDeviceRole.unassigned,
      isLocal: true,
    );
    unawaited(_refreshPermissionStatusFromPlatform());
  }
  static const String _serviceId = 'com.paul.sprintsync.nearby';
  static const String _localHostDeviceId = 'local-device';
  final NearbyBridge _nearbyBridge;
  final MotionDetectionController _motionController;
  final Future<void> Function()? _startMonitoringAction;
  final Future<void> Function()? _stopMonitoringAction;
  final int Function() _nowElapsedNanos;
  final WakeLockBridge _wakeLockBridge;
  final NewRelicBridge _newRelicBridge;
  final Map<String, NearbyEndpoint> _discovered = <String, NearbyEndpoint>{};
  final Set<String> _connectedEndpointIds = <String>{};
  final Map<String, SessionDevice> _devices = <String, SessionDevice>{};
  StreamSubscription<Map<String, dynamic>>? _eventsSubscription;
  SessionStage _stage = SessionStage.setup;
  SessionNetworkRole _networkRole = SessionNetworkRole.none;
  SessionRaceTimeline _timeline = SessionRaceTimeline.idle();
  String _localDeviceId = _localHostDeviceId;
  bool _busy = false;
  bool _permissionsGranted = false;
  bool _permissionsStatusKnown = false;
  bool _monitoringActive = false;
  int? _hostMinusClientElapsedNanos;
  int? _hostClockRoundTripNanos;
  int? _lastClockSyncElapsedNanos;
  int? _hostSensorMinusElapsedNanos;
  int? _hostGpsUtcOffsetNanos;
  int? _hostGpsFixAgeNanos;
  int? _lastClockSyncRequestNanos;
  final Set<int> _pendingClockSyncRequestSendNanos = <int>{};
  int? _clockSyncBurstStartedElapsedNanos;
  int? _bestClockSyncBurstRttNanos;
  int _activeClockSyncBurstResponseCount = 0;
  int _activeClockSyncBurstHighRttRejectCount = 0;
  bool _clockSyncInProgress = false;
  int? _lastBroadcastHostGpsUtcOffsetNanos;
  int? _lastBroadcastHostGpsFixAgeNanos;
  int? _lastGpsSnapshotBroadcastElapsedNanos;
  int? _lastSensorElapsedSampleNanos;
  int? _lastSensorElapsedSampleCapturedAtNanos;
  bool _wakeLockEnabled = false;
  bool _localMonitoringCaptureActive = false;
  String? _errorText;
  SessionStage get stage => _stage;
  SessionNetworkRole get networkRole => _networkRole;
  SessionRaceTimeline get timeline => _timeline;
  bool get isHost => _networkRole == SessionNetworkRole.host;
  bool get isClient => _networkRole == SessionNetworkRole.client;
  bool get busy => _busy;
  bool get permissionsGranted => _permissionsGranted;
  bool get permissionsStatusKnown => _permissionsStatusKnown;
  bool get shouldShowPermissionsButton =>
      _permissionsStatusKnown && !_permissionsGranted;
  bool get monitoringActive => _monitoringActive;
  String? get errorText => _errorText;
  bool get isClockLockWarningVisible =>
      isClient &&
      _monitoringActive &&
      _connectedEndpointIds.isNotEmpty &&
      localRole != SessionDeviceRole.unassigned &&
      !_isClockLockValid();
  String? get clockLockWarningText {
    if (!isClockLockWarningVisible) {
      return null;
    }
    final error = _errorText;
    if (error != null && error.startsWith('Clock sync failed:')) {
      return '$error Triggers from this device are being dropped until sync recovers.';
    }
    return 'Clock sync lock is invalid. Triggers from this device are being dropped until sync recovers.';
  }

  bool get hasConnectedPeers => _connectedEndpointIds.isNotEmpty;
  String get monitoringConnectionTypeLabel => 'Nearby (auto BT/Wi-Fi Direct)';
  String get monitoringSyncModeLabel {
    if (!isClient || !hasConnectedPeers || !_monitoringActive) {
      return '-';
    }
    if (_hasFreshGpsClockLock()) {
      return 'GPS';
    }
    if (_isNtpClockLockValid()) {
      return 'NTP';
    }
    return '-';
  }

  int? get monitoringLatencyMs {
    if (!isClient || !hasConnectedPeers || !_isNtpClockLockValid()) {
      return null;
    }
    final roundTripNanos = _hostClockRoundTripNanos;
    if (roundTripNanos == null) {
      return null;
    }
    return (roundTripNanos / 1000000).round();
  }

  List<NearbyEndpoint> get discoveredEndpoints => _discovered.values.toList();
  List<SessionDevice> get devices => _devices.values.toList();
  int get totalDeviceCount {
    if (isClient && _connectedEndpointIds.isNotEmpty) {
      return math.max(2, _devices.length);
    }
    return _devices.length;
  }

  bool get canGoToLobby => totalDeviceCount >= 2;
  bool get canShowSplitControls => totalDeviceCount > 2;
  bool get canStartMonitoring =>
      isHost &&
      _stage == SessionStage.lobby &&
      !_monitoringActive &&
      totalDeviceCount >= 2 &&
      _hasRequiredRoles();
  SessionDeviceRole get localRole =>
      _devices[_localDeviceId]?.role ?? SessionDeviceRole.unassigned;
  Future<void> requestPermissions() async {
    _busy = true;
    _errorText = null;
    notifyListeners();
    try {
      final status = await _nearbyBridge.requestPermissions();
      _permissionsGranted = _isPermissionsGranted(status);
      _permissionsStatusKnown = true;
      if (_permissionsGranted) {
        unawaited(_motionController.warmupGpsSync());
      }
      _trackNearby(
        action: 'permission_status',
        errorMessage: _permissionsGranted
            ? null
            : 'Nearby permissions were denied by user.',
      );
    } catch (error) {
      _errorText = 'Permission request failed: $error';
      _trackNearby(action: 'permission_failed', errorMessage: error.toString());
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<void> createLobby() async {
    await _ensurePermissions();
    if (!_permissionsGranted) return;
    _busy = true;
    notifyListeners();
    try {
      await _nearbyBridge.stopAll();
      await _resetSession(SessionNetworkRole.host);
      await _nearbyBridge.startHosting(
        serviceId: _serviceId,
        endpointName: 'SprintSyncHost',
      );
      _trackNearby(action: 'hosting_started', serviceId: _serviceId);
    } catch (error) {
      _errorText = 'Create lobby failed: $error';
      _trackNearby(
        action: 'hosting_failed',
        serviceId: _serviceId,
        errorMessage: error.toString(),
      );
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<void> joinLobby() async {
    await _ensurePermissions();
    if (!_permissionsGranted) return;
    _busy = true;
    notifyListeners();
    try {
      await _nearbyBridge.stopAll();
      await _resetSession(SessionNetworkRole.client);
      await _nearbyBridge.startDiscovery(
        serviceId: _serviceId,
        endpointName: 'SprintSyncClient',
      );
      _trackNearby(action: 'discovery_started', serviceId: _serviceId);
    } catch (error) {
      _errorText = 'Join lobby failed: $error';
      _trackNearby(
        action: 'discovery_failed',
        serviceId: _serviceId,
        errorMessage: error.toString(),
      );
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<void> connect(String endpointId) async {
    try {
      _trackNearby(action: 'connection_requested', endpointId: endpointId);
      await _nearbyBridge.requestConnection(
        endpointId: endpointId,
        endpointName: 'SprintSyncClient',
      );
    } catch (error) {
      _errorText = 'Connect failed: $error';
      _trackNearby(
        action: 'connection_request_failed',
        endpointId: endpointId,
        errorMessage: error.toString(),
      );
      notifyListeners();
    }
  }

  void goToLobby() {
    if (!canGoToLobby) return;
    _stage = SessionStage.lobby;
    notifyListeners();
    if (isHost) unawaited(_broadcastSnapshot());
  }

  void assignRole(String deviceId, SessionDeviceRole role) {
    if (!isHost || _monitoringActive) return;
    if (!_devices.containsKey(deviceId)) return;
    if (totalDeviceCount <= 2 && role == SessionDeviceRole.split) return;
    if (role == SessionDeviceRole.start || role == SessionDeviceRole.stop) {
      for (final entry in _devices.entries.toList()) {
        if (entry.key == deviceId) continue;
        if (entry.value.role == role) {
          _devices[entry.key] = entry.value.copyWith(
            role: SessionDeviceRole.unassigned,
          );
        }
      }
    }
    _devices[deviceId] = _devices[deviceId]!.copyWith(role: role);
    notifyListeners();
    unawaited(_broadcastSnapshot());
  }

  void assignCameraFacing(String deviceId, SessionCameraFacing cameraFacing) {
    if (!isHost || _monitoringActive) return;
    final device = _devices[deviceId];
    if (device == null || device.cameraFacing == cameraFacing) return;
    _devices[deviceId] = device.copyWith(cameraFacing: cameraFacing);
    notifyListeners();
    if (deviceId == _localDeviceId) {
      unawaited(_syncLocalCameraFacingFromDevices());
    }
    unawaited(_broadcastSnapshot());
  }

  Future<void> startMonitoring() async {
    if (!canStartMonitoring) return;
    await _syncLocalCameraFacingFromDevices();
    _monitoringActive = true;
    _stage = SessionStage.monitoring;
    await _setWakeLockEnabled(true);
    _timeline = SessionRaceTimeline.idle();
    _motionController.resetRace();
    notifyListeners();
    await _startLocalMonitoringCaptureIfAssigned();
    await _broadcastSnapshot();
  }

  Future<void> stopMonitoring() async {
    if (!isHost || !_monitoringActive) return;
    await _stopLocalMonitoringCaptureIfRunning();
    _monitoringActive = false;
    _stage = SessionStage.lobby;
    await _setWakeLockEnabled(false);
    notifyListeners();
    await _broadcastSnapshot();
  }

  Future<void> resetRun() async {
    if (!isHost) return;
    _timeline = SessionRaceTimeline.idle();
    _motionController.resetRace();
    notifyListeners();
    await _broadcastSnapshot();
  }

  Future<void> triggerManualEvent(SessionDeviceRole role) async {
    if (!isHost || _stage != SessionStage.lobby) return;
    if (role == SessionDeviceRole.split && !canShowSplitControls) return;
    await _applyRoleEvent(
      role: role,
      triggerSensorNanos: _estimateLocalSensorNanosNow(),
    );
  }

  Future<void> onLocalMotionPulse(MotionTriggerEvent trigger) async {
    if (!_monitoringActive) return;
    if (localRole == SessionDeviceRole.unassigned) {
      ingestStandalonePulse(_motionController, trigger);
      return;
    }
    if (isHost) {
      await _applyRoleEvent(
        role: localRole,
        triggerSensorNanos: trigger.triggerSensorNanos,
      );
      return;
    }
    if (isClient &&
        _connectedEndpointIds.isNotEmpty &&
        localRole != SessionDeviceRole.unassigned) {
      final mappedHostSensorNanos = _mapClientSensorToHostSensor(
        trigger.triggerSensorNanos,
      );
      if (mappedHostSensorNanos == null) {
        _errorText =
            'Trigger rejected: no valid clock lock (no sync, stale sync, or RTT > ${_maxClockSyncRttNanos ~/ 1000000}ms).';
        notifyListeners();
        return;
      }
      await _nearbyBridge.sendBytes(
        endpointId: _connectedEndpointIds.first,
        messageJson: SessionTriggerRequestMessage(
          role: localRole,
          triggerSensorNanos: trigger.triggerSensorNanos,
          mappedHostSensorNanos: mappedHostSensorNanos,
        ).toJsonString(),
      );
    }
  }

  void _onMotionControllerChanged() {
    if (isHost && _monitoringActive && _connectedEndpointIds.isNotEmpty) {
      _maybeBroadcastSnapshotForGpsUpdate();
    }
    if (!isClient || !_monitoringActive) {
      return;
    }
    final now = _nowElapsedNanos();
    final lastReq = _lastClockSyncRequestNanos ?? 0;

    final needsSync = !_isClockLockValid() || (now - lastReq > 2000000000);

    if (_shouldRunNtpSync() && needsSync && (now - lastReq > 1000000000)) {
      _lastClockSyncRequestNanos = now;
      unawaited(_requestClockSync());
    }
  }

  Future<void> _applyRoleEvent({
    required SessionDeviceRole role,
    required int triggerSensorNanos,
  }) async {
    if (role == SessionDeviceRole.unassigned) return;
    if (role == SessionDeviceRole.split && !canShowSplitControls) return;
    final startedSensorNanos = _timeline.startedSensorNanos;
    if (role == SessionDeviceRole.start) {
      if (_timeline.hasStarted) return;
      _timeline = _timeline.copyWith(
        startedSensorNanos: triggerSensorNanos,
        splitElapsedNanos: <int>[],
        clearStopElapsedNanos: true,
      );
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerSensorNanos: triggerSensorNanos,
          score: 0,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
        forwardToSync: false,
      );
    } else if (role == SessionDeviceRole.split) {
      if (!_timeline.isRunning || startedSensorNanos == null) return;
      if (_timeline.splitElapsedNanos.isNotEmpty) return;
      final elapsedNanos = math.max(0, triggerSensorNanos - startedSensorNanos);
      _timeline = _timeline.copyWith(
        splitElapsedNanos: <int>[..._timeline.splitElapsedNanos, elapsedNanos],
      );
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerSensorNanos: triggerSensorNanos,
          score: 0,
          type: MotionTriggerType.split,
          splitIndex: _timeline.splitElapsedNanos.length,
        ),
        forwardToSync: false,
      );
    } else if (role == SessionDeviceRole.stop) {
      if (!_timeline.isRunning || startedSensorNanos == null) return;
      final elapsedNanos = math.max(0, triggerSensorNanos - startedSensorNanos);
      _timeline = _timeline.copyWith(stopElapsedNanos: elapsedNanos);
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerSensorNanos: triggerSensorNanos,
          score: 0,
          type: MotionTriggerType.stop,
          splitIndex: 0,
        ),
        forwardToSync: false,
      );
    }
    notifyListeners();
    if (isHost) await _broadcastSnapshot();
  }

  void _onNearbyEvent(Map<String, dynamic> event) {
    final type = event['type']?.toString();
    if (type == null) return;
    if (type == 'endpoint_found') {
      final endpointId = event['endpointId']?.toString();
      if (endpointId == null || endpointId.isEmpty) return;
      _trackNearby(
        action: 'endpoint_found',
        endpointId: endpointId,
        endpointName: event['endpointName']?.toString(),
        serviceId: event['serviceId']?.toString(),
      );
      _discovered[endpointId] = NearbyEndpoint(
        id: endpointId,
        name: event['endpointName']?.toString() ?? endpointId,
        serviceId: event['serviceId']?.toString() ?? '',
      );
      notifyListeners();
      return;
    }
    if (type == 'endpoint_lost' || type == 'endpoint_disconnected') {
      final endpointId = event['endpointId']?.toString();
      if (endpointId == null) return;
      _trackNearby(
        action: type,
        endpointId: endpointId,
        endpointName: event['endpointName']?.toString(),
      );
      _discovered.remove(endpointId);
      _connectedEndpointIds.remove(endpointId);
      _devices.remove(endpointId);
      notifyListeners();
      if (isHost) unawaited(_broadcastSnapshot());
      return;
    }
    if (type == 'connection_result') {
      final connection = NearbyConnectionResultEvent.tryParse(event);
      if (connection == null) return;
      _trackNearby(
        action: 'connection_result',
        endpointId: connection.endpointId,
        endpointName: connection.endpointName,
        connected: connection.connected,
        statusCode: connection.statusCode,
        statusMessage: connection.statusMessage,
      );
      if (connection.connected) {
        _connectedEndpointIds.add(connection.endpointId);
        if (isHost) {
          final connectionName = connection.endpointName?.trim();
          final discoveredName = _discovered
              .remove(connection.endpointId)
              ?.name;
          final name = (connectionName != null && connectionName.isNotEmpty)
              ? connectionName
              : ((discoveredName != null && discoveredName.isNotEmpty)
                    ? discoveredName
                    : 'Device ${connection.endpointId}');
          _devices[connection.endpointId] = SessionDevice(
            id: connection.endpointId,
            name: name,
            role: SessionDeviceRole.unassigned,
            isLocal: false,
          );
        } else if (isClient) {
          if (_shouldRunNtpSync()) {
            unawaited(_requestClockSync());
          }
        }
      } else {
        _connectedEndpointIds.remove(connection.endpointId);
        _devices.remove(connection.endpointId);
        if (_connectedEndpointIds.isEmpty) {
          _clearClockSyncLock();
          _hostSensorMinusElapsedNanos = null;
          _hostGpsUtcOffsetNanos = null;
          _hostGpsFixAgeNanos = null;
        }
      }
      notifyListeners();
      if (isHost) unawaited(_broadcastSnapshot());
      return;
    }
    if (type == 'permission_status') {
      _permissionsGranted = _isPermissionsGranted(event);
      _permissionsStatusKnown = true;
      _trackNearby(
        action: 'permission_status_event',
        errorMessage: _permissionsGranted
            ? null
            : 'Nearby permission status event reported denied.',
      );
      notifyListeners();
      return;
    }
    if (type == 'payload_received') {
      final message = event['message']?.toString();
      if (message != null) {
        unawaited(
          _onPayload(message, endpointId: event['endpointId']?.toString()),
        );
      }
      return;
    }
    if (type == 'error') {
      _errorText = event['message']?.toString() ?? 'Nearby error';
      _trackNearby(action: 'nearby_error', errorMessage: _errorText);
      notifyListeners();
    }
  }

  void _trackNearby({
    required String action,
    String? endpointId,
    String? endpointName,
    String? serviceId,
    bool? connected,
    int? statusCode,
    String? statusMessage,
    String? errorMessage,
  }) {
    unawaited(
      _newRelicBridge.recordNearbyEvent(
        action: action,
        networkRole: _networkRole.name,
        stage: _stage.name,
        endpointId: endpointId,
        endpointName: endpointName,
        serviceId: serviceId,
        connected: connected,
        statusCode: statusCode,
        statusMessage: statusMessage,
        errorMessage: errorMessage,
      ),
    );
  }

  Future<void> _onPayload(String raw, {required String? endpointId}) async {
    final snapshot = SessionSnapshotMessage.tryParse(raw);
    if (snapshot != null && isClient) {
      final wasMonitoring = _monitoringActive;
      final previousTimeline = _timeline;
      _stage = snapshot.stage;
      _monitoringActive = snapshot.monitoringActive;
      _timeline = snapshot.timeline;
      final previousHostSensorMinusElapsedNanos = _hostSensorMinusElapsedNanos;
      final previousHostGpsUtcOffsetNanos = _hostGpsUtcOffsetNanos;
      final previousHostGpsFixAgeNanos = _hostGpsFixAgeNanos;
      _hostSensorMinusElapsedNanos =
          snapshot.hostSensorMinusElapsedNanos ?? _hostSensorMinusElapsedNanos;
      _hostGpsUtcOffsetNanos =
          snapshot.hostGpsUtcOffsetNanos ?? _hostGpsUtcOffsetNanos;
      _hostGpsFixAgeNanos = snapshot.hostGpsFixAgeNanos ?? _hostGpsFixAgeNanos;
      _localDeviceId = snapshot.selfDeviceId ?? _localDeviceId;
      _devices
        ..clear()
        ..addEntries(
          snapshot.devices.map((device) {
            final isLocal = device.id == _localDeviceId;
            return MapEntry(device.id, device.copyWith(isLocal: isLocal));
          }),
        );
      await _syncLocalCameraFacingFromDevices();
      if (!wasMonitoring && _monitoringActive) {
        await _setWakeLockEnabled(true);
        _clearClockSyncLock();
        await _startLocalMonitoringCaptureIfAssigned();
        if (_shouldRunNtpSync()) {
          unawaited(_requestClockSync());
        }
      } else if (wasMonitoring && !_monitoringActive) {
        await _stopLocalMonitoringCaptureIfRunning();
        await _setWakeLockEnabled(false);
      }
      final timelineChanged =
          previousTimeline.startedSensorNanos != _timeline.startedSensorNanos ||
          previousTimeline.stopElapsedNanos != _timeline.stopElapsedNanos ||
          !listEquals(
            previousTimeline.splitElapsedNanos,
            _timeline.splitElapsedNanos,
          );
      final hostOffsetChanged =
          previousHostSensorMinusElapsedNanos != _hostSensorMinusElapsedNanos;
      final hostGpsChanged =
          previousHostGpsUtcOffsetNanos != _hostGpsUtcOffsetNanos ||
          previousHostGpsFixAgeNanos != _hostGpsFixAgeNanos;
      if (timelineChanged || hostOffsetChanged || hostGpsChanged) {
        _syncMotionControllerFromTimeline();
      }
      if (_monitoringActive && _shouldRunNtpSync() && !_isClockLockValid()) {
        unawaited(_requestClockSync());
      }
      notifyListeners();
      return;
    }
    final clockSyncRequest = SessionClockSyncRequestMessage.tryParse(raw);
    if (clockSyncRequest != null && isHost && endpointId != null) {
      final hostReceiveElapsedNanos = _nowClockSyncElapsedNanos(
        requireSensorDomainIfMonitoring: true,
      );
      if (hostReceiveElapsedNanos == null) {
        return;
      }
      final hostSendElapsedNanos = _nowClockSyncElapsedNanos(
        requireSensorDomainIfMonitoring: true,
      );
      if (hostSendElapsedNanos == null) {
        return;
      }
      await _nearbyBridge.sendBytes(
        endpointId: endpointId,
        messageJson: SessionClockSyncResponseMessage(
          clientSendElapsedNanos: clockSyncRequest.clientSendElapsedNanos,
          hostReceiveElapsedNanos: hostReceiveElapsedNanos,
          hostSendElapsedNanos: hostSendElapsedNanos,
        ).toJsonString(),
      );
      return;
    }
    final clockSyncResponse = SessionClockSyncResponseMessage.tryParse(raw);
    if (clockSyncResponse != null && isClient) {
      if (!_pendingClockSyncRequestSendNanos.remove(
        clockSyncResponse.clientSendElapsedNanos,
      )) {
        return;
      }
      _activeClockSyncBurstResponseCount += 1;
      final clientReceiveElapsedNanos = _nowClockSyncElapsedNanos(
        requireSensorDomainIfMonitoring: true,
      );
      if (clientReceiveElapsedNanos == null) {
        return;
      }
      _updateHostClockOffset(
        clientSendElapsedNanos: clockSyncResponse.clientSendElapsedNanos,
        hostReceiveElapsedNanos: clockSyncResponse.hostReceiveElapsedNanos,
        clientReceiveElapsedNanos: clientReceiveElapsedNanos,
      );
      return;
    }
    final triggerRequest = SessionTriggerRequestMessage.tryParse(raw);
    if (triggerRequest != null && isHost && endpointId != null) {
      final role = _devices[endpointId]?.role ?? SessionDeviceRole.unassigned;
      if (role == triggerRequest.role) {
        final mappedHostSensorNanos = triggerRequest.mappedHostSensorNanos;
        if (mappedHostSensorNanos == null) {
          _errorText =
              'Rejected trigger from $endpointId: missing mappedHostSensorNanos.';
          notifyListeners();
          return;
        }
        await _applyRoleEvent(
          role: role,
          triggerSensorNanos: mappedHostSensorNanos,
        );
      }
      return;
    }
  }

  Future<void> _requestClockSync() async {
    if (!isClient ||
        _connectedEndpointIds.isEmpty ||
        !_shouldRunNtpSync() ||
        _clockSyncInProgress) {
      return;
    }
    _clockSyncInProgress = true;
    try {
      final endpointId = _connectedEndpointIds.first;
      final firstBurst = await _runClockSyncBurst(endpointId: endpointId);
      if (_shouldRunNtpSync() &&
          firstBurst.bestRttNanos != null &&
          firstBurst.bestRttNanos! > _targetClockSyncRttNanos) {
        await _runClockSyncBurst(endpointId: endpointId);
      }
      if (firstBurst.bestRttNanos == null &&
          firstBurst.responseCount > 0 &&
          firstBurst.highRttRejectCount == firstBurst.responseCount) {
        _errorText =
            'Clock sync failed: all RTT samples exceeded ${_maxClockSyncRttNanos ~/ 1000000}ms.';
        notifyListeners();
      }
    } finally {
      _clockSyncInProgress = false;
    }
  }

  Future<({int? bestRttNanos, int responseCount, int highRttRejectCount})>
  _runClockSyncBurst({required String endpointId}) async {
    final burstStartElapsedNanos = _nowClockSyncElapsedNanos(
      requireSensorDomainIfMonitoring: true,
    );
    if (burstStartElapsedNanos == null) {
      return (bestRttNanos: null, responseCount: 0, highRttRejectCount: 0);
    }
    if (_pendingClockSyncRequestSendNanos.isNotEmpty) {
      final activeBurstStartedElapsedNanos = _clockSyncBurstStartedElapsedNanos;
      final activeBurstAgeNanos = activeBurstStartedElapsedNanos == null
          ? _clockSyncBurstTimeoutNanos + 1
          : burstStartElapsedNanos - activeBurstStartedElapsedNanos;
      if (activeBurstAgeNanos >= 0 &&
          activeBurstAgeNanos <= _clockSyncBurstTimeoutNanos) {
        return (bestRttNanos: null, responseCount: 0, highRttRejectCount: 0);
      }
      _pendingClockSyncRequestSendNanos.clear();
    }
    _clockSyncBurstStartedElapsedNanos = burstStartElapsedNanos;
    _bestClockSyncBurstRttNanos = null;
    _activeClockSyncBurstResponseCount = 0;
    _activeClockSyncBurstHighRttRejectCount = 0;
    for (var i = 0; i < _clockSyncBurstCount; i += 1) {
      final sampledClientSendElapsedNanos = i == 0
          ? burstStartElapsedNanos
          : _nowClockSyncElapsedNanos(requireSensorDomainIfMonitoring: true);
      if (sampledClientSendElapsedNanos == null) {
        break;
      }
      var uniqueClientSendElapsedNanos = sampledClientSendElapsedNanos;
      while (_pendingClockSyncRequestSendNanos.contains(
        uniqueClientSendElapsedNanos,
      )) {
        uniqueClientSendElapsedNanos += 1;
      }
      _pendingClockSyncRequestSendNanos.add(uniqueClientSendElapsedNanos);
      await _nearbyBridge.sendBytes(
        endpointId: endpointId,
        messageJson: SessionClockSyncRequestMessage(
          clientSendElapsedNanos: uniqueClientSendElapsedNanos,
        ).toJsonString(),
      );
    }
    final timeoutStopwatch = Stopwatch()..start();
    while (_pendingClockSyncRequestSendNanos.isNotEmpty) {
      if ((timeoutStopwatch.elapsedMicroseconds * 1000) >=
          _clockSyncBurstTimeoutNanos) {
        _pendingClockSyncRequestSendNanos.clear();
        break;
      }
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    final result = (
      bestRttNanos: _bestClockSyncBurstRttNanos,
      responseCount: _activeClockSyncBurstResponseCount,
      highRttRejectCount: _activeClockSyncBurstHighRttRejectCount,
    );
    _clockSyncBurstStartedElapsedNanos = null;
    _bestClockSyncBurstRttNanos = null;
    _activeClockSyncBurstResponseCount = 0;
    _activeClockSyncBurstHighRttRejectCount = 0;
    return result;
  }

  void _updateHostClockOffset({
    required int clientSendElapsedNanos,
    required int hostReceiveElapsedNanos,
    required int clientReceiveElapsedNanos,
  }) {
    if (clientReceiveElapsedNanos < clientSendElapsedNanos) {
      _errorText = 'Clock sync sample ignored: receive time moved backwards.';
      notifyListeners();
      return;
    }
    final roundTripNanos = math.max(
      0,
      clientReceiveElapsedNanos - clientSendElapsedNanos,
    );
    if (roundTripNanos > _maxClockSyncRttNanos) {
      _activeClockSyncBurstHighRttRejectCount += 1;
      _errorText =
          'Clock sync sample ignored: RTT ${(roundTripNanos / 1000000).toStringAsFixed(1)}ms exceeds ${_maxClockSyncRttNanos ~/ 1000000}ms.';
      notifyListeners();
      return;
    }
    final bestBurstRttNanos = _bestClockSyncBurstRttNanos;
    if (bestBurstRttNanos != null && roundTripNanos >= bestBurstRttNanos) {
      return;
    }
    _bestClockSyncBurstRttNanos = roundTripNanos;
    final estimatedClientAtHostReceiveElapsedNanos =
        clientSendElapsedNanos + (roundTripNanos ~/ 2);
    final sampleOffsetNanos =
        hostReceiveElapsedNanos - estimatedClientAtHostReceiveElapsedNanos;
    _hostMinusClientElapsedNanos = sampleOffsetNanos;
    _hostClockRoundTripNanos = roundTripNanos;
    _lastClockSyncElapsedNanos = clientReceiveElapsedNanos;
    _errorText = null;
    if (isClient && _timeline.hasStarted) {
      _syncMotionControllerFromTimeline();
      notifyListeners();
    }
  }

  int? _mapClientSensorToHostSensor(int clientSensorNanos) {
    if (!_isClockLockValid()) {
      return null;
    }
    final clientSensorMinusElapsedNanos =
        _motionController.sensorMinusElapsedNanos;
    final hostSensorMinusElapsedNanos = _hostSensorMinusElapsedNanos;
    final hostMinusClientElapsedNanos =
        _gpsHostMinusClientElapsedNanosIfFresh() ??
        _hostMinusClientElapsedNanos;
    if (clientSensorMinusElapsedNanos == null ||
        hostMinusClientElapsedNanos == null ||
        hostSensorMinusElapsedNanos == null) {
      return null;
    }
    final clientElapsedNanos =
        clientSensorNanos - clientSensorMinusElapsedNanos;
    final hostElapsedNanos = clientElapsedNanos + hostMinusClientElapsedNanos;
    return hostElapsedNanos + hostSensorMinusElapsedNanos;
  }

  int? _mapHostSensorToLocalSensor(int hostSensorNanos) {
    if (!_isClockLockValid()) {
      return null;
    }
    final hostSensorMinusElapsedNanos = _hostSensorMinusElapsedNanos;
    final hostMinusClientElapsedNanos =
        _gpsHostMinusClientElapsedNanosIfFresh() ??
        _hostMinusClientElapsedNanos;
    final localSensorMinusElapsedNanos =
        _motionController.sensorMinusElapsedNanos;
    if (hostSensorMinusElapsedNanos == null ||
        hostMinusClientElapsedNanos == null ||
        localSensorMinusElapsedNanos == null) {
      return null;
    }
    final hostElapsedNanos = hostSensorNanos - hostSensorMinusElapsedNanos;
    final localElapsedNanos = hostElapsedNanos - hostMinusClientElapsedNanos;
    return localElapsedNanos + localSensorMinusElapsedNanos;
  }

  void _syncMotionControllerFromTimeline() {
    if (!_timeline.hasStarted) {
      syncMotionControllerFromTimeline(_motionController, _timeline);
      return;
    }
    if (!isClient) {
      syncMotionControllerFromTimeline(_motionController, _timeline);
      return;
    }
    final hostStartSensorNanos = _timeline.startedSensorNanos;
    if (hostStartSensorNanos == null) {
      return;
    }
    final localStartSensorNanos = _mapHostSensorToLocalSensor(
      hostStartSensorNanos,
    );
    if (localStartSensorNanos == null) {
      return;
    }
    syncMotionControllerFromTimeline(
      _motionController,
      _timeline,
      startedSensorNanosOverride: localStartSensorNanos,
    );
  }

  bool _isClockLockValid() {
    return _hasFreshGpsClockLock() || _isNtpClockLockValid();
  }

  bool _shouldRunNtpSync() {
    return !_hasFreshGpsClockLock();
  }

  int? _gpsHostMinusClientElapsedNanosIfFresh() {
    if (!_hasFreshGpsClockLock()) {
      return null;
    }
    final clientGpsUtcOffsetNanos = _motionController.gpsUtcOffsetNanos;
    final hostGpsUtcOffsetNanos = _hostGpsUtcOffsetNanos;
    if (clientGpsUtcOffsetNanos == null || hostGpsUtcOffsetNanos == null) {
      return null;
    }
    return clientGpsUtcOffsetNanos - hostGpsUtcOffsetNanos;
  }

  bool _hasFreshGpsClockLock() {
    if (_motionController.sensorMinusElapsedNanos == null ||
        _hostSensorMinusElapsedNanos == null) {
      return false;
    }
    if (_motionController.gpsUtcOffsetNanos == null ||
        _hostGpsUtcOffsetNanos == null) {
      return false;
    }
    final localGpsFixAgeNanos = _localGpsFixAgeNanos();
    if (localGpsFixAgeNanos == null ||
        localGpsFixAgeNanos > _gpsOffsetStaleAfterNanos) {
      return false;
    }
    final hostGpsFixAgeNanos = _hostGpsFixAgeNanos;
    if (hostGpsFixAgeNanos == null ||
        hostGpsFixAgeNanos < 0 ||
        hostGpsFixAgeNanos > _gpsOffsetStaleAfterNanos) {
      return false;
    }
    return true;
  }

  bool _isNtpClockLockValid() {
    final offset = _hostMinusClientElapsedNanos;
    final roundTripNanos = _hostClockRoundTripNanos;
    final lastSyncElapsedNanos = _lastClockSyncElapsedNanos;
    if (offset == null ||
        roundTripNanos == null ||
        lastSyncElapsedNanos == null) {
      return false;
    }
    if (roundTripNanos > _maxClockSyncRttNanos) {
      return false;
    }
    final nowElapsedNanos = _nowClockSyncElapsedNanos(
      requireSensorDomainIfMonitoring: _monitoringActive,
    );
    if (nowElapsedNanos == null) {
      return false;
    }
    final ageNanos = nowElapsedNanos - lastSyncElapsedNanos;
    if (ageNanos < 0) {
      return false;
    }
    if (ageNanos > _clockSyncStaleAfterNanos) {
      return false;
    }
    if (_motionController.sensorMinusElapsedNanos == null) {
      return false;
    }
    return _hostSensorMinusElapsedNanos != null;
  }

  int? _localGpsFixAgeNanos() {
    return _computeGpsFixAgeNanos(_motionController.gpsFixElapsedRealtimeNanos);
  }

  int? _computeGpsFixAgeNanos(int? gpsFixElapsedRealtimeNanos) {
    if (gpsFixElapsedRealtimeNanos == null) {
      return null;
    }
    final nowElapsedNanos = _nowClockSyncElapsedNanos(
      requireSensorDomainIfMonitoring: _monitoringActive,
    );
    if (nowElapsedNanos == null) {
      return null;
    }
    final ageNanos = nowElapsedNanos - gpsFixElapsedRealtimeNanos;
    if (ageNanos < 0) {
      return null;
    }
    return ageNanos;
  }

  int _estimateLocalSensorNanosNow() {
    final sensorMinusElapsedNanos = _motionController.sensorMinusElapsedNanos;
    final nowElapsedNanos = _nowElapsedNanos();
    if (sensorMinusElapsedNanos == null) {
      return nowElapsedNanos;
    }
    return nowElapsedNanos + sensorMinusElapsedNanos;
  }

  int? _sensorDerivedElapsedNanos() {
    final sensorMinusElapsedNanos = _motionController.sensorMinusElapsedNanos;
    final latestFrameSensorNanos =
        _motionController.latestStats?.frameSensorNanos;
    if (sensorMinusElapsedNanos != null && latestFrameSensorNanos != null) {
      final sampledElapsedNanos =
          latestFrameSensorNanos - sensorMinusElapsedNanos;
      _lastSensorElapsedSampleNanos = sampledElapsedNanos;
      _lastSensorElapsedSampleCapturedAtNanos = _nowElapsedNanos();
      return sampledElapsedNanos;
    }
    final lastSampledElapsedNanos = _lastSensorElapsedSampleNanos;
    final lastCapturedAtNanos = _lastSensorElapsedSampleCapturedAtNanos;
    if (lastSampledElapsedNanos == null || lastCapturedAtNanos == null) {
      return null;
    }
    final nowElapsedNanos = _nowElapsedNanos();
    final sampleAgeNanos = nowElapsedNanos - lastCapturedAtNanos;
    if (sampleAgeNanos < 0 ||
        sampleAgeNanos > _sensorElapsedProjectionMaxAgeNanos) {
      return null;
    }
    return lastSampledElapsedNanos + sampleAgeNanos;
  }

  int? _nowClockSyncElapsedNanos({
    bool requireSensorDomainIfMonitoring = false,
  }) {
    final sensorElapsedNanos = _sensorDerivedElapsedNanos();
    if (sensorElapsedNanos != null) {
      return sensorElapsedNanos;
    }
    if (requireSensorDomainIfMonitoring && _monitoringActive) {
      return null;
    }
    return _nowElapsedNanos();
  }

  void _maybeBroadcastSnapshotForGpsUpdate() {
    final hostGpsUtcOffsetNanos = _motionController.gpsUtcOffsetNanos;
    final hostGpsFixAgeNanos = _localGpsFixAgeNanos();
    final gpsStateChanged =
        hostGpsUtcOffsetNanos != _lastBroadcastHostGpsUtcOffsetNanos ||
        hostGpsFixAgeNanos != _lastBroadcastHostGpsFixAgeNanos;
    if (!gpsStateChanged) {
      return;
    }
    final nowElapsedNanos = _nowClockSyncElapsedNanos(
      requireSensorDomainIfMonitoring: true,
    );
    if (nowElapsedNanos == null) {
      return;
    }
    final lastBroadcastElapsedNanos = _lastGpsSnapshotBroadcastElapsedNanos;
    if (lastBroadcastElapsedNanos != null &&
        nowElapsedNanos - lastBroadcastElapsedNanos <
            _gpsSnapshotRebroadcastMinIntervalNanos) {
      return;
    }
    _lastGpsSnapshotBroadcastElapsedNanos = nowElapsedNanos;
    unawaited(_broadcastSnapshot());
  }

  Future<void> _broadcastSnapshot() async {
    if (!isHost) return;
    final deviceSnapshot = _devices.values.toList();
    final hostSensorMinusElapsedNanos =
        _motionController.sensorMinusElapsedNanos;
    final hostGpsUtcOffsetNanos = _motionController.gpsUtcOffsetNanos;
    final hostGpsFixAgeNanos = _localGpsFixAgeNanos();
    for (final endpointId in _connectedEndpointIds.toList()) {
      await _nearbyBridge.sendBytes(
        endpointId: endpointId,
        messageJson: SessionSnapshotMessage(
          stage: _stage,
          monitoringActive: _monitoringActive,
          devices: deviceSnapshot,
          timeline: _timeline,
          hostSensorMinusElapsedNanos: hostSensorMinusElapsedNanos,
          hostGpsUtcOffsetNanos: hostGpsUtcOffsetNanos,
          hostGpsFixAgeNanos: hostGpsFixAgeNanos,
          selfDeviceId: endpointId,
        ).toJsonString(),
      );
    }
    _lastBroadcastHostGpsUtcOffsetNanos = hostGpsUtcOffsetNanos;
    _lastBroadcastHostGpsFixAgeNanos = hostGpsFixAgeNanos;
    _lastGpsSnapshotBroadcastElapsedNanos = _nowClockSyncElapsedNanos(
      requireSensorDomainIfMonitoring: _monitoringActive,
    );
  }

  Future<void> _syncLocalCameraFacingFromDevices() async {
    final localDevice = _devices[_localDeviceId];
    if (localDevice == null) {
      return;
    }
    final localFacing = _toMotionCameraFacing(localDevice.cameraFacing);
    if (_motionController.config.cameraFacing == localFacing) {
      return;
    }
    await _motionController.updateCameraFacing(localFacing);
  }

  MotionCameraFacing _toMotionCameraFacing(SessionCameraFacing cameraFacing) {
    switch (cameraFacing) {
      case SessionCameraFacing.rear:
        return MotionCameraFacing.rear;
      case SessionCameraFacing.front:
        return MotionCameraFacing.front;
    }
  }

  Future<void> _ensurePermissions() async {
    if (!_permissionsGranted) await requestPermissions();
  }

  Future<void> _refreshPermissionStatusFromPlatform() async {
    try {
      final status = await _nearbyBridge.getPermissionStatus();
      final granted = _isPermissionsGranted(status);
      if (!_permissionsStatusKnown || _permissionsGranted != granted) {
        _permissionsStatusKnown = true;
        _permissionsGranted = granted;
        notifyListeners();
      }
    } catch (_) {
      // Ignore passive status check failures; explicit request flow still works.
    }
  }

  bool _isPermissionsGranted(Map<String, dynamic> status) {
    final grantedRaw = status['granted'];
    if (grantedRaw is bool) {
      return grantedRaw;
    }
    final deniedRaw = status['denied'];
    if (deniedRaw is List) {
      return deniedRaw.isEmpty;
    }
    return false;
  }

  Future<void> _setWakeLockEnabled(bool enabled, {bool force = false}) async {
    if (!force && _wakeLockEnabled == enabled) {
      return;
    }
    try {
      if (enabled) {
        await _wakeLockBridge.enable();
      } else {
        await _wakeLockBridge.disable();
      }
      _wakeLockEnabled = enabled;
    } catch (_) {
      if (!enabled && force) {
        _wakeLockEnabled = false;
      }
    }
  }

  Future<void> _startLocalMonitoringCaptureIfAssigned() async {
    if (localRole == SessionDeviceRole.unassigned) {
      _localMonitoringCaptureActive = false;
      return;
    }
    if (_startMonitoringAction != null) {
      await _startMonitoringAction();
    } else {
      await _motionController.initializeCamera();
      await _motionController.startDetection();
    }
    _localMonitoringCaptureActive = true;
  }

  Future<void> _stopLocalMonitoringCaptureIfRunning() async {
    if (!_localMonitoringCaptureActive) {
      return;
    }
    try {
      if (_stopMonitoringAction != null) {
        await _stopMonitoringAction();
      } else {
        await _motionController.stopDetection();
      }
    } finally {
      _localMonitoringCaptureActive = false;
    }
  }

  bool _hasRequiredRoles() {
    int starts = 0;
    int stops = 0;
    int splits = 0;
    for (final role in _devices.values.map((device) => device.role)) {
      if (role == SessionDeviceRole.start) starts += 1;
      if (role == SessionDeviceRole.stop) stops += 1;
      if (role == SessionDeviceRole.split) splits += 1;
    }
    if (starts != 1 || stops != 1) return false;
    if (totalDeviceCount <= 2) return splits == 0;
    return true;
  }

  Future<void> _resetSession(SessionNetworkRole networkRole) async {
    _networkRole = networkRole;
    _stage = SessionStage.setup;
    _timeline = SessionRaceTimeline.idle();
    _monitoringActive = false;
    await _setWakeLockEnabled(false, force: true);
    _clearClockSyncLock();
    _lastClockSyncRequestNanos = null;
    _hostSensorMinusElapsedNanos = null;
    _hostGpsUtcOffsetNanos = null;
    _hostGpsFixAgeNanos = null;
    _localMonitoringCaptureActive = false;
    _lastBroadcastHostGpsUtcOffsetNanos = null;
    _lastBroadcastHostGpsFixAgeNanos = null;
    _lastGpsSnapshotBroadcastElapsedNanos = null;
    _lastSensorElapsedSampleNanos = null;
    _lastSensorElapsedSampleCapturedAtNanos = null;
    _discovered.clear();
    _connectedEndpointIds.clear();
    _devices
      ..clear()
      ..[_localHostDeviceId] = const SessionDevice(
        id: _localHostDeviceId,
        name: 'This device',
        role: SessionDeviceRole.unassigned,
        isLocal: true,
      );
    _localDeviceId = _localHostDeviceId;
    _motionController.resetRace();
  }

  void _clearClockSyncLock() {
    _hostMinusClientElapsedNanos = null;
    _hostClockRoundTripNanos = null;
    _lastClockSyncElapsedNanos = null;
    _pendingClockSyncRequestSendNanos.clear();
    _clockSyncBurstStartedElapsedNanos = null;
    _bestClockSyncBurstRttNanos = null;
    _activeClockSyncBurstResponseCount = 0;
    _activeClockSyncBurstHighRttRejectCount = 0;
    _clockSyncInProgress = false;
  }

  @override
  void dispose() {
    unawaited(_setWakeLockEnabled(false, force: true));
    _motionController.removeListener(_onMotionControllerChanged);
    _eventsSubscription?.cancel();
    super.dispose();
  }
}
