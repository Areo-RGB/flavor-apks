import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
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
  static const int _clockSyncIntervalMicros = 3000000;
  static const int _maxAcceptedRemoteTriggerSkewMicros = 500000;
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
  String _localDeviceId = _localHostDeviceId;
  bool _busy = false;
  bool _permissionsGranted = false;
  bool _monitoringActive = false;
  String? _errorText;
  Future<void> _payloadQueue = Future<void>.value();
  int? _hostClockOffsetMicros;
  int? _lastClockSyncAtMicros;
  int? _lastClockSyncRequestAtMicros;
  SessionStage get stage => _stage;
  SessionNetworkRole get networkRole => _networkRole;
  SessionRaceTimeline get timeline => _motionController.timeline;
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
    _motionController.resetRace();
    notifyListeners();
    await _broadcastSnapshot();
    if (_startMonitoringAction != null) {
      await _startMonitoringAction();
    } else {
      await _motionController.startDetection();
    }
  }

  Future<void> stopMonitoring() async {
    if (!isHost || !_monitoringActive) return;
    _monitoringActive = false;
    _stage = SessionStage.lobby;
    notifyListeners();
    await _broadcastSnapshot();
    if (_stopMonitoringAction != null) {
      await _stopMonitoringAction();
    } else {
      await _motionController.stopDetection();
    }
  }

  Future<void> resetRun() async {
    if (!isHost) return;
    _motionController.resetRace();
    notifyListeners();
    await _broadcastTimelineUpdate();
  }

  Future<void> triggerManualEvent(SessionDeviceRole role) async {
    if (!isHost || _stage != SessionStage.lobby) return;
    if (role == SessionDeviceRole.split && !canShowSplitControls) return;
    await _applyRoleEvent(role: role, triggerMicros: _nowMicros());
  }

  Future<void> onLocalMotionPulse(MotionTriggerEvent trigger) async {
    if (!_monitoringActive) return;
    if (localRole == SessionDeviceRole.unassigned) {
      _motionController.ingestDetectedPulse(trigger);
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
      unawaited(_maybeRequestClockSync());
      await _nearbyBridge.sendBytes(
        endpointId: _connectedEndpointIds.first,
        messageJson: SessionTriggerRequestMessage(
          role: localRole,
          deviceTriggerMicros: trigger.triggerMicros,
          hostTriggerMicros: _estimatedHostTriggerMicros(trigger.triggerMicros),
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
    if (role == SessionDeviceRole.start) {
      if (timeline.hasStarted) return;
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerMicros: triggerMicros,
          score: 0,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );
    } else if (role == SessionDeviceRole.split) {
      if (!timeline.isRunning) return;
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerMicros: triggerMicros,
          score: 0,
          type: MotionTriggerType.split,
          splitIndex: timeline.splitMicros.length + 1,
        ),
      );
    } else if (role == SessionDeviceRole.stop) {
      if (!timeline.isRunning) return;
      _motionController.ingestTrigger(
        MotionTriggerEvent(
          triggerMicros: triggerMicros,
          score: 0,
          type: MotionTriggerType.stop,
          splitIndex: 0,
        ),
      );
    }
    notifyListeners();
    if (isHost) await _broadcastTimelineUpdate();
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
      if (isClient) {
        unawaited(_requestClockSync(force: true));
      }
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
        _enqueuePayload(message, endpointId: event['endpointId']?.toString());
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
      await _applyClientSnapshot(snapshot);
      return;
    }
    final timelineUpdate = SessionTimelineUpdateMessage.tryParse(raw);
    if (timelineUpdate != null && isClient) {
      if (_applyRemoteTimelineIfNewer(timelineUpdate.timeline)) {
        notifyListeners();
      }
      return;
    }
    final clockSyncRequest = SessionClockSyncRequestMessage.tryParse(raw);
    if (clockSyncRequest != null && isHost && endpointId != null) {
      final hostReceivedAtMicros = _nowMicros();
      await _nearbyBridge.sendBytes(
        endpointId: endpointId,
        messageJson: SessionClockSyncResponseMessage(
          clientSentAtMicros: clockSyncRequest.clientSentAtMicros,
          hostReceivedAtMicros: hostReceivedAtMicros,
          hostSentAtMicros: _nowMicros(),
        ).toJsonString(),
      );
      return;
    }
    final clockSyncResponse = SessionClockSyncResponseMessage.tryParse(raw);
    if (clockSyncResponse != null && isClient) {
      _syncHostClockOffset(clockSyncResponse);
      notifyListeners();
      return;
    }
    final triggerRequest = SessionTriggerRequestMessage.tryParse(raw);
    if (triggerRequest != null && isHost && endpointId != null) {
      final role = _devices[endpointId]?.role ?? SessionDeviceRole.unassigned;
      if (role == triggerRequest.role) {
        await _applyRoleEvent(
          role: role,
          triggerMicros: _canonicalizeRemoteTriggerMicros(triggerRequest),
        );
      }
    }
  }

  Future<void> _broadcastSnapshot() async {
    if (!isHost) return;
    final deviceSnapshot = _devices.values.toList();
    await _broadcastMessageToClients((endpointId) {
      return SessionSnapshotMessage(
        stage: _stage,
        monitoringActive: _monitoringActive,
        devices: deviceSnapshot,
        timeline: timeline,
        selfDeviceId: endpointId,
      ).toJsonString();
    });
  }

  Future<void> _broadcastTimelineUpdate() async {
    if (!isHost) return;
    await _broadcastMessageToClients((_) {
      return SessionTimelineUpdateMessage(timeline: timeline).toJsonString();
    });
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
    _monitoringActive = false;
    _discovered.clear();
    _connectedEndpointIds.clear();
    _hostClockOffsetMicros = null;
    _lastClockSyncAtMicros = null;
    _lastClockSyncRequestAtMicros = null;
    _devices
      ..clear()
      ..[_localHostDeviceId] = const SessionDevice(
        id: _localHostDeviceId,
        name: 'This device',
        role: SessionDeviceRole.unassigned,
        isLocal: true,
      );
    _localDeviceId = _localHostDeviceId;
    _motionController.resetRace(revision: 0);
  }

  @override
  void dispose() {
    _eventsSubscription?.cancel();
    super.dispose();
  }

  void _enqueuePayload(String raw, {required String? endpointId}) {
    _payloadQueue = _payloadQueue
        .then((_) => _onPayload(raw, endpointId: endpointId))
        .catchError((Object error, StackTrace stackTrace) {
          _errorText = 'Payload processing failed: $error';
          notifyListeners();
        });
  }

  Future<void> _applyClientSnapshot(SessionSnapshotMessage snapshot) async {
    final wasMonitoring = _monitoringActive;
    _stage = snapshot.stage;
    _monitoringActive = snapshot.monitoringActive;
    _localDeviceId = snapshot.selfDeviceId ?? _localDeviceId;
    _devices
      ..clear()
      ..addEntries(
        snapshot.devices.map((device) {
          final isLocal = device.id == _localDeviceId;
          return MapEntry(device.id, device.copyWith(isLocal: isLocal));
        }),
      );
    _applyRemoteTimelineIfNewer(snapshot.timeline);
    notifyListeners();
    if (!wasMonitoring && _monitoringActive) {
      unawaited(_requestClockSync(force: true));
      await _startLocalMonitoring();
      return;
    }
    if (wasMonitoring && !_monitoringActive) {
      await _stopLocalMonitoring();
      return;
    }
    if (_monitoringActive) {
      unawaited(_maybeRequestClockSync());
    }
  }

  bool _applyRemoteTimelineIfNewer(SessionRaceTimeline remoteTimeline) {
    if (remoteTimeline.revision < timeline.revision) {
      return false;
    }
    if (remoteTimeline.revision == timeline.revision &&
        _timelinesEqual(remoteTimeline, timeline)) {
      return false;
    }
    _motionController.applyTimeline(remoteTimeline);
    return true;
  }

  bool _timelinesEqual(SessionRaceTimeline left, SessionRaceTimeline right) {
    return left.startedAtEpochMs == right.startedAtEpochMs &&
        left.stopElapsedMicros == right.stopElapsedMicros &&
        left.revision == right.revision &&
        listEquals(left.splitMicros, right.splitMicros);
  }

  Future<void> _startLocalMonitoring() async {
    if (_startMonitoringAction != null) {
      await _startMonitoringAction();
      return;
    }
    await _motionController.startDetection();
  }

  Future<void> _stopLocalMonitoring() async {
    if (_stopMonitoringAction != null) {
      await _stopMonitoringAction();
      return;
    }
    await _motionController.stopDetection();
  }

  Future<void> _broadcastMessageToClients(
    String Function(String endpointId) buildMessage,
  ) async {
    final endpoints = _connectedEndpointIds.toList();
    if (endpoints.isEmpty) {
      return;
    }
    final futures = <Future<void>>[];
    for (final endpointId in endpoints) {
      futures.add(
        _nearbyBridge
            .sendBytes(
              endpointId: endpointId,
              messageJson: buildMessage(endpointId),
            )
            .catchError((Object error, StackTrace stackTrace) {
              _errorText = 'Nearby sync failed: $error';
            }),
      );
    }
    await Future.wait(futures);
    if (_errorText != null) {
      notifyListeners();
    }
  }

  Future<void> _maybeRequestClockSync() async {
    final nowMicros = _nowMicros();
    final lastSyncAtMicros = _lastClockSyncAtMicros;
    if (lastSyncAtMicros != null &&
        nowMicros - lastSyncAtMicros < _clockSyncIntervalMicros) {
      return;
    }
    await _requestClockSync();
  }

  Future<void> _requestClockSync({bool force = false}) async {
    if (!isClient || _connectedEndpointIds.isEmpty) {
      return;
    }
    final nowMicros = _nowMicros();
    final lastRequestedAtMicros = _lastClockSyncRequestAtMicros;
    if (!force &&
        lastRequestedAtMicros != null &&
        nowMicros - lastRequestedAtMicros < _clockSyncIntervalMicros) {
      return;
    }
    _lastClockSyncRequestAtMicros = nowMicros;
    await _nearbyBridge.sendBytes(
      endpointId: _connectedEndpointIds.first,
      messageJson: SessionClockSyncRequestMessage(
        clientSentAtMicros: nowMicros,
      ).toJsonString(),
    );
  }

  int? _estimatedHostTriggerMicros(int deviceTriggerMicros) {
    final offsetMicros = _hostClockOffsetMicros;
    final lastSyncAtMicros = _lastClockSyncAtMicros;
    if (offsetMicros == null || lastSyncAtMicros == null) {
      return null;
    }
    if (_nowMicros() - lastSyncAtMicros > (_clockSyncIntervalMicros * 2)) {
      return null;
    }
    return deviceTriggerMicros + offsetMicros;
  }

  int _canonicalizeRemoteTriggerMicros(SessionTriggerRequestMessage request) {
    final nowMicros = _nowMicros();
    final estimatedHostTriggerMicros = request.hostTriggerMicros;
    if (estimatedHostTriggerMicros == null) {
      return nowMicros;
    }
    final skew = (estimatedHostTriggerMicros - nowMicros).abs();
    if (skew > _maxAcceptedRemoteTriggerSkewMicros) {
      return nowMicros;
    }
    return estimatedHostTriggerMicros;
  }

  void _syncHostClockOffset(SessionClockSyncResponseMessage response) {
    final clientReceivedAtMicros = _nowMicros();
    final offsetMicros =
        ((response.hostReceivedAtMicros - response.clientSentAtMicros) +
            (response.hostSentAtMicros - clientReceivedAtMicros)) ~/
        2;
    _hostClockOffsetMicros = offsetMicros;
    _lastClockSyncAtMicros = clientReceivedAtMicros;
  }

  int _nowMicros() => DateTime.now().microsecondsSinceEpoch;
}
