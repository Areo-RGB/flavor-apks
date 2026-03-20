import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:sprint_sync/core/models/app_models.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_sync/race_sync_models.dart';

class RaceSyncController extends ChangeNotifier {
  RaceSyncController({
    required LocalRepository repository,
    required NearbyBridge nearbyBridge,
    void Function(MotionTriggerEvent trigger)? onRemoteTrigger,
    Duration latencyProbeInterval = const Duration(seconds: 1),
  }) : _repository = repository,
       _nearbyBridge = nearbyBridge,
       _onRemoteTrigger = onRemoteTrigger,
       _latencyProbeInterval = latencyProbeInterval {
    _eventsSubscription = _nearbyBridge.events.listen(_onNearbyEvent);
    unawaited(_loadLastRun());
  }

  static const String _serviceId = 'com.paul.sprintsync.nearby';

  final LocalRepository _repository;
  final NearbyBridge _nearbyBridge;
  final void Function(MotionTriggerEvent trigger)? _onRemoteTrigger;
  final Duration _latencyProbeInterval;

  StreamSubscription<Map<String, dynamic>>? _eventsSubscription;
  Timer? _latencyProbeTimer;

  RaceRole _role = RaceRole.none;
  SessionState _sessionState = SessionState.initial();
  LastRunResult? _lastRun;
  String _sessionId = '';
  int _probeCounter = 0;

  final Map<String, NearbyEndpoint> _discovered = <String, NearbyEndpoint>{};
  final Set<String> _connectedEndpointIds = <String>{};
  final Map<String, int> _latencyByEndpointMs = <String, int>{};
  final Map<String, int> _pendingProbeSentAtMicrosByKey = <String, int>{};
  final List<String> _logs = <String>[];

  bool _busy = false;
  bool _permissionsGranted = false;
  String? _errorText;
  String? _lastConnectionStatus;

  RaceRole get role => _role;
  SessionState get sessionState => _sessionState;
  LastRunResult? get lastRun => _lastRun;
  List<NearbyEndpoint> get discoveredEndpoints => _discovered.values.toList();
  List<String> get connectedEndpointIds => _connectedEndpointIds.toList();
  List<String> get logs => List.unmodifiable(_logs);
  bool get busy => _busy;
  bool get permissionsGranted => _permissionsGranted;
  String? get errorText => _errorText;
  String? get lastConnectionStatus => _lastConnectionStatus;
  bool get hasConnectedPeers => _connectedEndpointIds.isNotEmpty;
  int? get worstPeerLatencyMs {
    if (_connectedEndpointIds.isEmpty) {
      return null;
    }
    final latencies = _connectedEndpointIds
        .map((endpointId) => _latencyByEndpointMs[endpointId])
        .whereType<int>();
    if (latencies.isEmpty) {
      return null;
    }
    return latencies.reduce(math.max);
  }

  ConnectionQuality get connectionQuality {
    if (!hasConnectedPeers) {
      return ConnectionQuality.offline;
    }
    final worstLatencyMs = worstPeerLatencyMs;
    if (worstLatencyMs == null) {
      return ConnectionQuality.warning;
    }
    if (worstLatencyMs < 100) {
      return ConnectionQuality.good;
    }
    if (worstLatencyMs > 250) {
      return ConnectionQuality.bad;
    }
    return ConnectionQuality.warning;
  }

  Future<void> _loadLastRun() async {
    _lastRun = await _repository.loadLastRun();
    notifyListeners();
  }

  Future<void> requestPermissions() async {
    _busy = true;
    _errorText = null;
    notifyListeners();

    try {
      final status = await _nearbyBridge.requestPermissions();
      _permissionsGranted = status['granted'] == true;
      final deniedRaw = status['denied'];
      final denied = deniedRaw is List ? deniedRaw.join(', ') : '';
      _addLog(
        _permissionsGranted
            ? 'Permissions granted.'
            : 'Permissions denied: $denied',
      );
    } catch (error) {
      _errorText = 'Permission request failed: $error';
      _addLog(_errorText!);
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  Future<void> startHosting() async {
    await _ensurePermissions();
    if (!_permissionsGranted) {
      return;
    }

    _resetForRoleSwitch(role: RaceRole.host);
    _sessionId = 'session-${DateTime.now().millisecondsSinceEpoch}';
    _sessionState = SessionState.initial();
    notifyListeners();

    try {
      await _nearbyBridge.startHosting(
        serviceId: _serviceId,
        endpointName: 'SprintSyncHost',
      );
      _addLog('Hosting started ($_sessionId).');
    } catch (error) {
      _errorText = 'Start hosting failed: $error';
      _addLog(_errorText!);
      notifyListeners();
    }
  }

  Future<void> startDiscovery() async {
    await _ensurePermissions();
    if (!_permissionsGranted) {
      return;
    }

    _resetForRoleSwitch(role: RaceRole.client);
    _sessionId = '';
    _sessionState = SessionState.initial();
    notifyListeners();

    try {
      await _nearbyBridge.startDiscovery(
        serviceId: _serviceId,
        endpointName: 'SprintSyncClient',
      );
      _addLog('Discovery started.');
    } catch (error) {
      _errorText = 'Start discovery failed: $error';
      _addLog(_errorText!);
      notifyListeners();
    }
  }

  Future<void> requestConnection(String endpointId) async {
    try {
      await _nearbyBridge.requestConnection(
        endpointId: endpointId,
        endpointName: 'SprintSyncClient',
      );
      _addLog('Connection requested: $endpointId');
    } catch (error) {
      _errorText = 'Request connection failed: $error';
      _addLog(_errorText!);
      notifyListeners();
    }
  }

  Future<void> disconnect(String endpointId) async {
    try {
      await _nearbyBridge.disconnect(endpointId: endpointId);
      _connectedEndpointIds.remove(endpointId);
      _clearEndpointLatency(endpointId);
      _refreshLatencyProbeLoop();
      _addLog('Disconnected: $endpointId');
      notifyListeners();
    } catch (error) {
      _errorText = 'Disconnect failed: $error';
      _addLog(_errorText!);
      notifyListeners();
    }
  }

  Future<void> stopAll() async {
    try {
      await _nearbyBridge.stopAll();
      _connectedEndpointIds.clear();
      _discovered.clear();
      _resetLatencyState();
      _stopLatencyProbeLoop();
      _role = RaceRole.none;
      _addLog('Stopped all Nearby operations.');
      notifyListeners();
    } catch (error) {
      _errorText = 'Stop all failed: $error';
      _addLog(_errorText!);
      notifyListeners();
    }
  }

  Future<void> onMotionTrigger(MotionTriggerEvent trigger) async {
    final normalizedTrigger = trigger.type == MotionTriggerType.split
        ? MotionTriggerEvent(
            triggerMicros: trigger.triggerMicros,
            score: trigger.score,
            type: MotionTriggerType.stop,
            splitIndex: 0,
          )
        : trigger;

    if (_role == RaceRole.host) {
      await _handleHostTrigger(normalizedTrigger);
      return;
    }
    if (_role == RaceRole.client) {
      await _handleClientTrigger(normalizedTrigger);
    }
  }

  Future<void> _handleHostTrigger(MotionTriggerEvent trigger) async {
    if (trigger.type == MotionTriggerType.start) {
      if (_sessionState.raceStarted) {
        return;
      }
      final startedAtEpochMs = trigger.triggerMicros ~/ 1000;
      final sessionId = _ensureSessionId();
      _sessionState = SessionState(
        raceStarted: true,
        startedAtEpochMs: startedAtEpochMs,
        splitMicros: const <int>[],
      );
      await _persistRun();
      _addLog('Race started at $startedAtEpochMs ms.');
      notifyListeners();

      await _broadcast(
        RaceEventMessage(
          type: RaceEventType.raceStarted,
          sessionId: sessionId,
          startedAtEpochMs: startedAtEpochMs,
        ),
      );
      return;
    }

    if (trigger.type != MotionTriggerType.stop ||
        !_sessionState.raceStarted ||
        _sessionState.startedAtEpochMs == null ||
        _sessionState.splitMicros.isNotEmpty) {
      return;
    }

    final elapsedMicros = math.max(
      0,
      trigger.triggerMicros - (_sessionState.startedAtEpochMs! * 1000),
    );
    _sessionState = _sessionState.copyWith(splitMicros: <int>[elapsedMicros]);
    await _persistRun();

    _addLog('Race stopped at ${elapsedMicros}us');
    notifyListeners();

    final sessionId = _ensureSessionId();
    await _broadcast(
      RaceEventMessage(
        type: RaceEventType.raceStopped,
        sessionId: sessionId,
        elapsedMicros: elapsedMicros,
      ),
    );
  }

  Future<void> _handleClientTrigger(MotionTriggerEvent trigger) async {
    if (_connectedEndpointIds.isEmpty) {
      return;
    }

    if (trigger.type == MotionTriggerType.start) {
      if (_sessionState.raceStarted && _sessionState.splitMicros.isNotEmpty) {
        return;
      }
      final startedAtEpochMs = trigger.triggerMicros ~/ 1000;
      _sessionState = SessionState(
        raceStarted: true,
        startedAtEpochMs: startedAtEpochMs,
        splitMicros: const <int>[],
      );
      _addLog('Race start requested at $startedAtEpochMs ms.');
      notifyListeners();

      await _broadcast(
        RaceEventMessage(
          type: RaceEventType.raceStartRequest,
          sessionId: _ensureSessionId(),
          startedAtEpochMs: startedAtEpochMs,
        ),
      );
      return;
    }

    if (trigger.type != MotionTriggerType.stop ||
        !_sessionState.raceStarted ||
        _sessionState.startedAtEpochMs == null ||
        _sessionState.splitMicros.isNotEmpty) {
      return;
    }

    final elapsedMicros = math.max(
      0,
      trigger.triggerMicros - (_sessionState.startedAtEpochMs! * 1000),
    );
    _sessionState = _sessionState.copyWith(splitMicros: <int>[elapsedMicros]);
    _addLog('Race stop requested: ${elapsedMicros}us');
    notifyListeners();

    await _broadcast(
      RaceEventMessage(
        type: RaceEventType.raceStopRequest,
        sessionId: _ensureSessionId(),
        elapsedMicros: elapsedMicros,
      ),
    );
  }

  Future<void> _ensurePermissions() async {
    if (_permissionsGranted) {
      return;
    }
    await requestPermissions();
  }

  void _onNearbyEvent(Map<String, dynamic> event) {
    final type = event['type'];
    if (type is! String) {
      return;
    }

    switch (type) {
      case 'endpoint_found':
        final id = event['endpointId']?.toString();
        if (id == null || id.isEmpty) {
          break;
        }
        final endpoint = NearbyEndpoint(
          id: id,
          name: event['endpointName']?.toString() ?? 'Unknown',
          serviceId: event['serviceId']?.toString() ?? '',
        );
        _discovered[id] = endpoint;
        _addLog('Endpoint found: ${endpoint.name} ($id)');
        notifyListeners();
        break;
      case 'endpoint_lost':
        final id = event['endpointId']?.toString();
        if (id != null) {
          _discovered.remove(id);
          _connectedEndpointIds.remove(id);
          _clearEndpointLatency(id);
          _refreshLatencyProbeLoop();
          _addLog('Endpoint lost: $id');
          _lastConnectionStatus = 'Endpoint lost: $id';
          notifyListeners();
        }
        break;
      case 'connection_result':
        final connection = NearbyConnectionResultEvent.tryParse(event);
        if (connection == null) {
          _addLog('Ignored malformed connection_result event: $event');
          break;
        }
        final endpointId = connection.endpointId;
        if (connection.connected) {
          final wasNew = _connectedEndpointIds.add(endpointId);
          _discovered.remove(endpointId);
          _refreshLatencyProbeLoop();
          _lastConnectionStatus = 'Connected: $endpointId';
          _addLog(
            'Connection success: $endpointId'
            '${_statusSuffix(connection.statusCode, connection.statusMessage)}',
          );
          if (_role == RaceRole.host && wasNew) {
            unawaited(_syncConnectedEndpointWithRaceState(endpointId));
          }
        } else {
          _connectedEndpointIds.remove(endpointId);
          _discovered.remove(endpointId);
          _clearEndpointLatency(endpointId);
          _refreshLatencyProbeLoop();
          final status = _statusSuffix(
            connection.statusCode,
            connection.statusMessage,
          );
          _lastConnectionStatus = 'Connection failed: $endpointId$status';
          _addLog('Connection failed: $endpointId$status');
        }
        notifyListeners();
        break;
      case 'endpoint_disconnected':
        final endpointId = event['endpointId']?.toString();
        if (endpointId != null) {
          _connectedEndpointIds.remove(endpointId);
          _clearEndpointLatency(endpointId);
          _refreshLatencyProbeLoop();
          _addLog('Endpoint disconnected: $endpointId');
          _lastConnectionStatus = 'Endpoint disconnected: $endpointId';
          notifyListeners();
        }
        break;
      case 'permission_status':
        _permissionsGranted = event['granted'] == true;
        _addLog('Permission status updated: $_permissionsGranted');
        notifyListeners();
        break;
      case 'payload_received':
        final endpointId = event['endpointId']?.toString();
        final message = event['message']?.toString();
        if (message != null) {
          _handlePayload(message, endpointId: endpointId);
        }
        break;
      case 'error':
        _errorText = event['message']?.toString() ?? 'Unknown Nearby error';
        _lastConnectionStatus = _errorText;
        _addLog('Error: $_errorText');
        notifyListeners();
        break;
    }
  }

  void _handlePayload(String raw, {required String? endpointId}) {
    final message = RaceEventMessage.tryParse(raw);
    if (message == null) {
      _addLog('Ignored malformed payload: $raw');
      return;
    }

    if (message.type == RaceEventType.latencyProbe) {
      if (endpointId != null &&
          endpointId.isNotEmpty &&
          message.probeId != null) {
        unawaited(
          _sendPayload(
            endpointId: endpointId,
            payload: RaceEventMessage(
              type: RaceEventType.latencyPong,
              sessionId: _ensureSessionId(),
              probeId: message.probeId,
            ).toJsonString(),
          ),
        );
      }
      return;
    }

    if (message.type == RaceEventType.latencyPong) {
      if (endpointId != null &&
          endpointId.isNotEmpty &&
          message.probeId != null) {
        final key = _probeKey(
          endpointId: endpointId,
          probeId: message.probeId!,
        );
        final sentAtMicros = _pendingProbeSentAtMicrosByKey.remove(key);
        if (sentAtMicros != null) {
          final elapsedMicros =
              DateTime.now().microsecondsSinceEpoch - sentAtMicros;
          final latencyMs = math.max(0, (elapsedMicros / 1000).round());
          _latencyByEndpointMs[endpointId] = latencyMs;
          notifyListeners();
        }
      }
      return;
    }

    if (_sessionId.isEmpty) {
      _sessionId = message.sessionId;
    }

    switch (message.type) {
      case RaceEventType.raceStartRequest:
        if (_role != RaceRole.host || _sessionState.raceStarted) {
          return;
        }
        final startedAtEpochMs =
            message.startedAtEpochMs ?? DateTime.now().millisecondsSinceEpoch;
        _sessionState = SessionState(
          raceStarted: true,
          startedAtEpochMs: startedAtEpochMs,
          splitMicros: const <int>[],
        );
        _addLog('Race start requested by client.');
        unawaited(_persistRun());
        notifyListeners();
        unawaited(
          _broadcast(
            RaceEventMessage(
              type: RaceEventType.raceStarted,
              sessionId: _ensureSessionId(),
              startedAtEpochMs: startedAtEpochMs,
            ),
          ),
        );
        return;
      case RaceEventType.raceStopRequest:
        if (_role != RaceRole.host ||
            !_sessionState.raceStarted ||
            _sessionState.startedAtEpochMs == null ||
            _sessionState.splitMicros.isNotEmpty) {
          return;
        }
        final elapsedMicros = message.elapsedMicros;
        if (elapsedMicros == null) {
          return;
        }
        final safeElapsedMicros = math.max(0, elapsedMicros);
        _sessionState = _sessionState.copyWith(
          splitMicros: <int>[safeElapsedMicros],
        );
        _addLog('Race stop requested by client: ${safeElapsedMicros}us');
        unawaited(_persistRun());
        notifyListeners();
        unawaited(
          _broadcast(
            RaceEventMessage(
              type: RaceEventType.raceStopped,
              sessionId: _ensureSessionId(),
              elapsedMicros: safeElapsedMicros,
            ),
          ),
        );
        return;
      case RaceEventType.raceStarted:
        if (message.startedAtEpochMs == null) {
          return;
        }
        _sessionState = SessionState(
          raceStarted: true,
          startedAtEpochMs: message.startedAtEpochMs,
          splitMicros: const <int>[],
        );
        _addLog('Race started from host payload.');
        break;
      case RaceEventType.raceStopped:
      case RaceEventType.raceSplit:
        if (message.elapsedMicros == null) {
          return;
        }
        _sessionState = _sessionState.copyWith(
          raceStarted: true,
          splitMicros: <int>[message.elapsedMicros!],
        );
        _addLog('Race stopped from host payload: ${message.elapsedMicros}us');
        break;
      case RaceEventType.latencyProbe:
      case RaceEventType.latencyPong:
        return;
    }

    _emitRemoteTrigger(message);
    unawaited(_persistRun());
    notifyListeners();
  }

  void _emitRemoteTrigger(RaceEventMessage message) {
    if (_role != RaceRole.client) {
      return;
    }

    switch (message.type) {
      case RaceEventType.raceStarted:
        final startedAtEpochMs = message.startedAtEpochMs;
        if (startedAtEpochMs == null) {
          return;
        }
        _onRemoteTrigger?.call(
          MotionTriggerEvent(
            triggerMicros: startedAtEpochMs * 1000,
            score: 0,
            type: MotionTriggerType.start,
            splitIndex: 0,
          ),
        );
        break;
      case RaceEventType.raceStopped:
      case RaceEventType.raceSplit:
        final elapsedMicros = message.elapsedMicros;
        final startedAtEpochMs = _sessionState.startedAtEpochMs;
        if (elapsedMicros == null || startedAtEpochMs == null) {
          return;
        }
        _onRemoteTrigger?.call(
          MotionTriggerEvent(
            triggerMicros: (startedAtEpochMs * 1000) + elapsedMicros,
            score: 0,
            type: MotionTriggerType.stop,
            splitIndex: 0,
          ),
        );
        break;
      case RaceEventType.raceStartRequest:
      case RaceEventType.raceStopRequest:
      case RaceEventType.latencyProbe:
      case RaceEventType.latencyPong:
        return;
    }
  }

  Future<void> _broadcast(RaceEventMessage message) async {
    final payload = message.toJsonString();
    for (final endpointId in _connectedEndpointIds) {
      try {
        await _sendPayload(endpointId: endpointId, payload: payload);
      } catch (error) {
        _addLog('Broadcast failed to $endpointId: $error');
      }
    }
  }

  Future<void> _syncConnectedEndpointWithRaceState(String endpointId) async {
    if (!_sessionState.raceStarted || _sessionState.startedAtEpochMs == null) {
      return;
    }
    final sessionId = _ensureSessionId();
    await _sendEventToEndpoint(
      endpointId: endpointId,
      message: RaceEventMessage(
        type: RaceEventType.raceStarted,
        sessionId: sessionId,
        startedAtEpochMs: _sessionState.startedAtEpochMs,
      ),
    );
    if (_sessionState.splitMicros.isNotEmpty) {
      await _sendEventToEndpoint(
        endpointId: endpointId,
        message: RaceEventMessage(
          type: RaceEventType.raceStopped,
          sessionId: sessionId,
          elapsedMicros: _sessionState.splitMicros.first,
        ),
      );
    }
  }

  Future<void> _sendEventToEndpoint({
    required String endpointId,
    required RaceEventMessage message,
  }) {
    return _sendPayload(
      endpointId: endpointId,
      payload: message.toJsonString(),
    );
  }

  Future<void> _sendPayload({
    required String endpointId,
    required String payload,
  }) {
    return _nearbyBridge.sendBytes(
      endpointId: endpointId,
      messageJson: payload,
    );
  }

  Future<void> _sendLatencyProbe(String endpointId) async {
    if (!_connectedEndpointIds.contains(endpointId)) {
      return;
    }
    final probeId = 'probe-${_probeCounter++}';
    final key = _probeKey(endpointId: endpointId, probeId: probeId);
    _pendingProbeSentAtMicrosByKey[key] = DateTime.now().microsecondsSinceEpoch;

    try {
      await _sendPayload(
        endpointId: endpointId,
        payload: RaceEventMessage(
          type: RaceEventType.latencyProbe,
          sessionId: _ensureSessionId(),
          probeId: probeId,
        ).toJsonString(),
      );
    } catch (_) {
      _pendingProbeSentAtMicrosByKey.remove(key);
    }
  }

  void _refreshLatencyProbeLoop() {
    if (_connectedEndpointIds.isEmpty) {
      _stopLatencyProbeLoop();
      return;
    }
    _startLatencyProbeLoop();
  }

  void _startLatencyProbeLoop() {
    if (_latencyProbeTimer != null) {
      return;
    }
    _latencyProbeTimer = Timer.periodic(_latencyProbeInterval, (_) {
      _probeConnectedEndpoints();
    });
  }

  void _stopLatencyProbeLoop() {
    _latencyProbeTimer?.cancel();
    _latencyProbeTimer = null;
  }

  void _probeConnectedEndpoints() {
    for (final endpointId in _connectedEndpointIds.toList()) {
      unawaited(_sendLatencyProbe(endpointId));
    }
  }

  void _clearEndpointLatency(String endpointId) {
    _latencyByEndpointMs.remove(endpointId);
    final prefix = '$endpointId::';
    final staleKeys = _pendingProbeSentAtMicrosByKey.keys
        .where((key) => key.startsWith(prefix))
        .toList();
    for (final key in staleKeys) {
      _pendingProbeSentAtMicrosByKey.remove(key);
    }
  }

  void _resetLatencyState() {
    _latencyByEndpointMs.clear();
    _pendingProbeSentAtMicrosByKey.clear();
    _probeCounter = 0;
  }

  String _probeKey({required String endpointId, required String probeId}) {
    return '$endpointId::$probeId';
  }

  String _ensureSessionId() {
    if (_sessionId.isEmpty) {
      _sessionId = 'session-${DateTime.now().millisecondsSinceEpoch}';
    }
    return _sessionId;
  }

  void _resetForRoleSwitch({required RaceRole role}) {
    _role = role;
    _discovered.clear();
    _connectedEndpointIds.clear();
    _resetLatencyState();
    _stopLatencyProbeLoop();
    _errorText = null;
    _lastConnectionStatus = null;
  }

  String _statusSuffix(int? statusCode, String? statusMessage) {
    final hasCode = statusCode != null;
    final hasMessage = statusMessage != null && statusMessage.isNotEmpty;
    if (!hasCode && !hasMessage) {
      return '';
    }
    if (hasCode && hasMessage) {
      return ' (code $statusCode: $statusMessage)';
    }
    if (hasCode) {
      return ' (code $statusCode)';
    }
    return ' ($statusMessage)';
  }

  Future<void> _persistRun() async {
    if (_sessionState.startedAtEpochMs == null) {
      return;
    }
    final run = LastRunResult(
      startedAtEpochMs: _sessionState.startedAtEpochMs!,
      splitMicros: List<int>.from(_sessionState.splitMicros),
    );
    _lastRun = run;
    await _repository.saveLastRun(run);
  }

  void _addLog(String line) {
    _logs.insert(0, '${DateTime.now().toIso8601String()} $line');
    if (_logs.length > 80) {
      _logs.removeLast();
    }
  }

  @override
  void dispose() {
    _stopLatencyProbeLoop();
    _eventsSubscription?.cancel();
    super.dispose();
  }
}
