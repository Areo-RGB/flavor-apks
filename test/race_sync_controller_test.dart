import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_sync/race_sync_controller.dart';
import 'package:sprint_sync/features/race_sync/race_sync_models.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('host replays start and splits to newly connected endpoint', () async {
    final bridge = _FakeNearbyBridge();
    final controller = RaceSyncController(
      repository: LocalRepository(),
      nearbyBridge: bridge,
    );

    await controller.startHosting();

    await controller.onMotionTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1000000,
        score: 0.21,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );
    await controller.onMotionTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1500000,
        score: 0.22,
        type: MotionTriggerType.split,
        splitIndex: 1,
      ),
    );
    await controller.onMotionTrigger(
      const MotionTriggerEvent(
        triggerMicros: 2100000,
        score: 0.24,
        type: MotionTriggerType.split,
        splitIndex: 2,
      ),
    );

    expect(bridge.sentPayloads, isEmpty);

    bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'client-1',
      'connected': true,
      'statusCode': 0,
      'statusMessage': 'ok',
    });
    await _flushEvents();

    expect(bridge.sentPayloads.length, 3);
    final parsed = bridge.sentPayloads
        .map((call) => RaceEventMessage.tryParse(call.messageJson))
        .toList();
    expect(parsed.every((message) => message != null), isTrue);
    expect(parsed[0]!.type, RaceEventType.raceStarted);
    expect(parsed[1]!.type, RaceEventType.raceSplit);
    expect(parsed[1]!.splitIndex, 1);
    expect(parsed[1]!.elapsedMicros, 500000);
    expect(parsed[2]!.type, RaceEventType.raceSplit);
    expect(parsed[2]!.splitIndex, 2);
    expect(parsed[2]!.elapsedMicros, 1100000);
    expect(
      bridge.sentPayloads.every((call) => call.endpointId == 'client-1'),
      isTrue,
    );

    controller.dispose();
    await bridge.close();
  });

  test('client forwards host payloads as motion triggers', () async {
    final bridge = _FakeNearbyBridge();
    final mirroredTriggers = <MotionTriggerEvent>[];
    final controller = RaceSyncController(
      repository: LocalRepository(),
      nearbyBridge: bridge,
      onRemoteTrigger: mirroredTriggers.add,
    );

    await controller.startDiscovery();

    bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'message': const RaceEventMessage(
        type: RaceEventType.raceStarted,
        sessionId: 'session-1',
        startedAtEpochMs: 1000,
      ).toJsonString(),
    });
    bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'message': const RaceEventMessage(
        type: RaceEventType.raceSplit,
        sessionId: 'session-1',
        splitIndex: 1,
        elapsedMicros: 700000,
      ).toJsonString(),
    });
    await _flushEvents();

    expect(mirroredTriggers.length, 2);
    expect(mirroredTriggers[0].type, MotionTriggerType.start);
    expect(mirroredTriggers[0].triggerMicros, 1000000);
    expect(mirroredTriggers[1].type, MotionTriggerType.split);
    expect(mirroredTriggers[1].splitIndex, 1);
    expect(mirroredTriggers[1].triggerMicros, 1700000);

    controller.dispose();
    await bridge.close();
  });

  test('role switch clears stale discovery and connection state', () async {
    final bridge = _FakeNearbyBridge();
    final controller = RaceSyncController(
      repository: LocalRepository(),
      nearbyBridge: bridge,
    );

    await controller.startDiscovery();
    bridge.emitEvent(<String, dynamic>{
      'type': 'endpoint_found',
      'endpointId': 'host-a',
      'endpointName': 'Host A',
      'serviceId': 'svc',
    });
    bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-a',
      'connected': true,
    });
    await _flushEvents();

    expect(controller.connectedEndpointIds, contains('host-a'));

    await controller.startHosting();

    expect(controller.role.name, 'host');
    expect(controller.connectedEndpointIds, isEmpty);
    expect(controller.discoveredEndpoints, isEmpty);
    expect(controller.errorText, isNull);
    expect(controller.lastConnectionStatus, isNull);

    controller.dispose();
    await bridge.close();
  });

  test(
    'failed connection and endpoint_lost keep endpoint sets coherent',
    () async {
      final bridge = _FakeNearbyBridge();
      final controller = RaceSyncController(
        repository: LocalRepository(),
        nearbyBridge: bridge,
      );

      await controller.startDiscovery();
      bridge.emitEvent(<String, dynamic>{
        'type': 'endpoint_found',
        'endpointId': 'host-z',
        'endpointName': 'Host Z',
        'serviceId': 'svc',
      });
      await _flushEvents();
      expect(controller.discoveredEndpoints.length, 1);

      bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-z',
        'connected': false,
        'statusCode': 17,
        'statusMessage': 'rejected',
      });
      await _flushEvents();

      expect(controller.connectedEndpointIds, isEmpty);
      expect(controller.discoveredEndpoints, isEmpty);
      expect(controller.lastConnectionStatus, contains('Connection failed'));

      bridge.emitEvent(<String, dynamic>{
        'type': 'endpoint_lost',
        'endpointId': 'host-z',
      });
      await _flushEvents();

      expect(controller.connectedEndpointIds, isEmpty);
      expect(controller.discoveredEndpoints, isEmpty);

      controller.dispose();
      await bridge.close();
    },
  );

  test('malformed connection_result event is ignored safely', () async {
    final bridge = _FakeNearbyBridge();
    final controller = RaceSyncController(
      repository: LocalRepository(),
      nearbyBridge: bridge,
    );

    await controller.startDiscovery();
    bridge.emitEvent(<String, dynamic>{'type': 'connection_result'});
    await _flushEvents();

    expect(controller.connectedEndpointIds, isEmpty);
    expect(controller.discoveredEndpoints, isEmpty);
    expect(controller.logs.any((line) => line.contains('malformed')), isTrue);

    controller.dispose();
    await bridge.close();
  });
}

Future<void> _flushEvents() async {
  await Future<void>.delayed(const Duration(milliseconds: 1));
}

class _FakeNearbyBridge extends NearbyBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();
  final List<_SentPayload> sentPayloads = <_SentPayload>[];

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

  void emitEvent(Map<String, dynamic> event) {
    _eventsController.add(event);
  }

  @override
  Future<Map<String, dynamic>> requestPermissions() async {
    return <String, dynamic>{'granted': true, 'denied': <String>[]};
  }

  @override
  Future<void> startHosting({
    required String serviceId,
    required String endpointName,
  }) async {}

  @override
  Future<void> startDiscovery({
    required String serviceId,
    required String endpointName,
  }) async {}

  @override
  Future<void> requestConnection({
    required String endpointId,
    required String endpointName,
  }) async {}

  @override
  Future<void> sendBytes({
    required String endpointId,
    required String messageJson,
  }) async {
    sentPayloads.add(
      _SentPayload(endpointId: endpointId, messageJson: messageJson),
    );
  }

  @override
  Future<void> disconnect({required String endpointId}) async {}

  @override
  Future<void> stopAll() async {}

  Future<void> close() async {
    await _eventsController.close();
  }
}

class _SentPayload {
  const _SentPayload({required this.endpointId, required this.messageJson});

  final String endpointId;
  final String messageJson;
}
