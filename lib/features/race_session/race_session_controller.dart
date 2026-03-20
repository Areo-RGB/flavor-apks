import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:sprint_sync/core/services/race_session_motion_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

class RaceSessionController extends ChangeNotifier {
  RaceSessionController({
    required NearbyBridge nearbyBridge,
    required MotionDetectionController motionController,
    Future<void> Function()? startMonitoringAction,
    Future<void> Function()? stopMonitoringAction,
  }) : _nearbyBridge = nearbyBridge,
       _motionController = motionController,
       _startMonitoringAction = startMonitoringAction,
       _stopMonitoringAction = stopMonitoringAction {
    _eventsSubscription = _nearbyBridge.events.listen(_onNearbyEvent);
    _devices[_localHostDeviceId] = const SessionDevice(
      id: _localHostDeviceId,
      name: 'This device',
      role: SessionDeviceRole.unassigned,
      isLocal: true,
    );
  }
  static const String _serviceId = 'com.paul.sprintsync.nearby';
  static const String _localHostDeviceId = 'local-device';
  final NearbyBridge _nearbyBridge;
  final MotionDetectionController _motionController;
  final Future<void> Function()? _startMonitoringAction;
  final Future<void> Function()? _stopMonitoringAction;
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
  bool _monitoringActive = false;
  String? _errorText;
  SessionStage get stage => _stage;
  SessionNetworkRole get networkRole => _networkRole;
  SessionRaceTimeline get timeline => _timeline;
  bool get isHost => _networkRole == SessionNetworkRole.host;
  bool get isClient => _networkRole == SessionNetworkRole.client;
  bool get busy => _busy;
  bool get permissionsGranted => _permissionsGranted;
  bool get monitoringActive => _monitoringActive;
  String? get errorText => _errorText;
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
      _permissionsGranted = status['granted'] == true;
    } catch (error) {
      _errorText = 'Permission request failed: $error';
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
      _resetSession(SessionNetworkRole.host);
      await _nearbyBridge.startHosting(
        serviceId: _serviceId,
        endpointName: 'SprintSyncHost',
      );
    } catch (error) {
      _errorText = 'Create lobby failed: $error';
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
      _resetSession(SessionNetworkRole.client);
      await _nearbyBridge.startDiscovery(
        serviceId: _serviceId,
        endpointName: 'SprintSyncClient',
      );
    } catch (error) {
      _errorText = 'Join lobby failed: $error';
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<void> connect(String endpointId) async {
    try {
      await _nearbyBridge.requestConnection(
        endpointId: endpointId,
        endpointName: 'SprintSyncClient',
      );
    } catch (error) {
      _errorText = 'Connect failed: $error';
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

  Future<void> startMonitoring() async {
    if (!canStartMonitoring) return;
    _monitoringActive = true;
    _stage = SessionStage.monitoring;
    _timeline = SessionRaceTimeline.idle();
    _motionController.resetRace();
    notifyListeners();
    if (_startMonitoringAction != null) {
      await _startMonitoringAction();
    } else {
      await _motionController.initializeCamera();
      await _motionController.startDetection();
    }
    await _broadcastSnapshot();
  }

  Future<void> stopMonitoring() async {
    if (!isHost || !_monitoringActive) return;
    if (_stopMonitoringAction != null) {
      await _stopMonitoringAction();
    } else {
      await _motionController.stopDetection();
    }
    _monitoringActive = false;
    _stage = SessionStage.lobby;
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
      triggerMicros: DateTime.now().microsecondsSinceEpoch,
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
        triggerMicros: trigger.triggerMicros,
      );
      return;
    }
    if (isClient &&
        _connectedEndpointIds.isNotEmpty &&
        localRole != SessionDeviceRole.unassigned) {
      await _nearbyBridge.sendBytes(
        endpointId: _connectedEndpointIds.first,
        messageJson: SessionTriggerRequestMessage(
          role: localRole,
          triggerMicros: trigger.triggerMicros,
        ).toJsonString(),
      );
    }
  }

  Future<void> _applyRoleEvent({
    required SessionDeviceRole role,
    required int triggerMicros,
  }) async {
    if (role == SessionDeviceRole.unassigned) return;
    if (role == SessionDeviceRole.split && !canShowSplitControls) return;
    final startedAt = _timeline.startedAtEpochMs;
    if (role == SessionDeviceRole.start) {
      if (_timeline.hasStarted) return;
      _timeline = _timeline.copyWith(
        startedAtEpochMs: triggerMicros ~/ 1000,
        splitMicros: <int>[],
        clearStopElapsed: true,
      );
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerMicros: triggerMicros,
          score: 0,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
        forwardToSync: false,
      );
    } else if (role == SessionDeviceRole.split) {
      if (!_timeline.isRunning || startedAt == null) return;
      final elapsedMicros = math.max(0, triggerMicros - (startedAt * 1000));
      _timeline = _timeline.copyWith(
        splitMicros: <int>[..._timeline.splitMicros, elapsedMicros],
      );
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerMicros: triggerMicros,
          score: 0,
          type: MotionTriggerType.split,
          splitIndex: _timeline.splitMicros.length,
        ),
        forwardToSync: false,
      );
    } else if (role == SessionDeviceRole.stop) {
      if (!_timeline.isRunning || startedAt == null) return;
      final elapsedMicros = math.max(0, triggerMicros - (startedAt * 1000));
      _timeline = _timeline.copyWith(stopElapsedMicros: elapsedMicros);
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerMicros: triggerMicros,
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
      if (connection.connected) {
        _connectedEndpointIds.add(connection.endpointId);
        if (isHost) {
          final name =
              _discovered.remove(connection.endpointId)?.name ??
              'Device ${connection.endpointId}';
          _devices[connection.endpointId] = SessionDevice(
            id: connection.endpointId,
            name: name,
            role: SessionDeviceRole.unassigned,
            isLocal: false,
          );
        }
      } else {
        _connectedEndpointIds.remove(connection.endpointId);
        _devices.remove(connection.endpointId);
      }
      notifyListeners();
      if (isHost) unawaited(_broadcastSnapshot());
      return;
    }
    if (type == 'permission_status') {
      _permissionsGranted = event['granted'] == true;
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
      notifyListeners();
    }
  }

  Future<void> _onPayload(String raw, {required String? endpointId}) async {
    final snapshot = SessionSnapshotMessage.tryParse(raw);
    if (snapshot != null && isClient) {
      final wasMonitoring = _monitoringActive;
      final previousTimeline = _timeline;
      _stage = snapshot.stage;
      _monitoringActive = snapshot.monitoringActive;
      _timeline = snapshot.timeline;
      _localDeviceId = snapshot.selfDeviceId ?? _localDeviceId;
      _devices
        ..clear()
        ..addEntries(
          snapshot.devices.map((device) {
            final isLocal = device.id == _localDeviceId;
            return MapEntry(device.id, device.copyWith(isLocal: isLocal));
          }),
        );
      if (!wasMonitoring && _monitoringActive) {
        if (_startMonitoringAction != null) {
          await _startMonitoringAction();
        } else {
          await _motionController.initializeCamera();
          await _motionController.startDetection();
        }
      } else if (wasMonitoring && !_monitoringActive) {
        if (_stopMonitoringAction != null) {
          await _stopMonitoringAction();
        } else {
          await _motionController.stopDetection();
        }
      }
      final timelineChanged =
          previousTimeline.startedAtEpochMs != _timeline.startedAtEpochMs ||
          previousTimeline.stopElapsedMicros != _timeline.stopElapsedMicros ||
          !listEquals(previousTimeline.splitMicros, _timeline.splitMicros);
      if (timelineChanged) {
        syncMotionControllerFromTimeline(_motionController, _timeline);
      }
      notifyListeners();
      return;
    }
    final triggerRequest = SessionTriggerRequestMessage.tryParse(raw);
    if (triggerRequest != null && isHost && endpointId != null) {
      final role = _devices[endpointId]?.role ?? SessionDeviceRole.unassigned;
      if (role == triggerRequest.role) {
        await _applyRoleEvent(
          role: role,
          triggerMicros: triggerRequest.triggerMicros,
        );
      }
    }
  }

  Future<void> _broadcastSnapshot() async {
    if (!isHost) return;
    final deviceSnapshot = _devices.values.toList();
    for (final endpointId in _connectedEndpointIds.toList()) {
      await _nearbyBridge.sendBytes(
        endpointId: endpointId,
        messageJson: SessionSnapshotMessage(
          stage: _stage,
          monitoringActive: _monitoringActive,
          devices: deviceSnapshot,
          timeline: _timeline,
          selfDeviceId: endpointId,
        ).toJsonString(),
      );
    }
  }

  Future<void> _ensurePermissions() async {
    if (!_permissionsGranted) await requestPermissions();
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

  void _resetSession(SessionNetworkRole networkRole) {
    _networkRole = networkRole;
    _stage = SessionStage.setup;
    _timeline = SessionRaceTimeline.idle();
    _monitoringActive = false;
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

  @override
  void dispose() {
    _eventsSubscription?.cancel();
    super.dispose();
  }
}
