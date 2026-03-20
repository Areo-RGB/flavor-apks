import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('setup requires at least two devices before Next is enabled', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();

    expect(fixture.controller.totalDeviceCount, 1);
    expect(fixture.controller.canGoToLobby, isFalse);

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    await _flushEvents();

    expect(fixture.controller.totalDeviceCount, 2);
    expect(fixture.controller.canGoToLobby, isTrue);

    fixture.dispose();
  });

  test('createLobby sets host role and joinLobby sets client role', () async {
    final hostFixture = _ControllerFixture.create();
    await hostFixture.controller.createLobby();
    expect(hostFixture.controller.isHost, isTrue);
    hostFixture.dispose();

    final clientFixture = _ControllerFixture.create();
    await clientFixture.controller.joinLobby();
    expect(clientFixture.controller.isClient, isTrue);
    clientFixture.dispose();
  });

  test('host assigns roles and START uniqueness is enforced', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();

    fixture.controller.assignRole('local-device', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-1', SessionDeviceRole.stop);
    expect(
      _roleOf(fixture.controller, 'local-device'),
      SessionDeviceRole.start,
    );
    expect(_roleOf(fixture.controller, 'peer-1'), SessionDeviceRole.stop);

    fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
    expect(_roleOf(fixture.controller, 'peer-1'), SessionDeviceRole.start);
    expect(
      _roleOf(fixture.controller, 'local-device'),
      SessionDeviceRole.unassigned,
    );

    fixture.dispose();
  });

  test('two-device lobby disallows split role assignment', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();

    fixture.controller.assignRole('local-device', SessionDeviceRole.split);
    expect(
      _roleOf(fixture.controller, 'local-device'),
      SessionDeviceRole.unassigned,
    );
    expect(fixture.controller.canShowSplitControls, isFalse);

    fixture.dispose();
  });

  test(
    'multiple split roles are supported and append split timeline events',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.createLobby();
      for (final endpointId in <String>['peer-1', 'peer-2', 'peer-3']) {
        fixture.bridge.emitEvent(<String, dynamic>{
          'type': 'connection_result',
          'endpointId': endpointId,
          'connected': true,
        });
      }
      await _flushEvents();
      fixture.controller.goToLobby();

      fixture.controller.assignRole('local-device', SessionDeviceRole.start);
      fixture.controller.assignRole('peer-1', SessionDeviceRole.stop);
      fixture.controller.assignRole('peer-2', SessionDeviceRole.split);
      fixture.controller.assignRole('peer-3', SessionDeviceRole.split);

      await fixture.controller.triggerManualEvent(SessionDeviceRole.start);
      await fixture.controller.triggerManualEvent(SessionDeviceRole.split);
      await fixture.controller.triggerManualEvent(SessionDeviceRole.split);
      await fixture.controller.triggerManualEvent(SessionDeviceRole.stop);

      expect(fixture.controller.timeline.hasStarted, isTrue);
      expect(fixture.controller.timeline.splitMicros.length, 2);
      expect(fixture.controller.timeline.stopElapsedMicros, isNotNull);

      fixture.dispose();
    },
  );

  test('client cannot trigger host-only lobby race actions', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();

    await fixture.controller.triggerManualEvent(SessionDeviceRole.start);
    expect(fixture.controller.timeline.hasStarted, isFalse);

    fixture.dispose();
  });

  test('roles are locked during monitoring', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();

    fixture.controller.assignRole('local-device', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-1', SessionDeviceRole.stop);
    await fixture.controller.startMonitoring();

    final before = _roleOf(fixture.controller, 'peer-1');
    fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
    final after = _roleOf(fixture.controller, 'peer-1');

    expect(fixture.controller.monitoringActive, isTrue);
    expect(fixture.controller.stage, SessionStage.monitoring);
    expect(after, before);

    fixture.dispose();
  });

  test('stopMonitoring returns to lobby and preserves device roles', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();

    fixture.controller.assignRole('local-device', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-1', SessionDeviceRole.stop);
    await fixture.controller.startMonitoring();
    await fixture.controller.stopMonitoring();

    expect(fixture.controller.stage, SessionStage.lobby);
    expect(fixture.controller.monitoringActive, isFalse);
    expect(
      _roleOf(fixture.controller, 'local-device'),
      SessionDeviceRole.start,
    );
    expect(_roleOf(fixture.controller, 'peer-1'), SessionDeviceRole.stop);

    fixture.dispose();
  });

  test(
    'startMonitoring notifies clients before local startup completes',
    () async {
      final startMonitoringCompleter = Completer<void>();
      final fixture = _ControllerFixture.create(
        startMonitoringAction: () => startMonitoringCompleter.future,
      );
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.controller.goToLobby();
      fixture.controller.assignRole('local-device', SessionDeviceRole.start);
      fixture.controller.assignRole('peer-1', SessionDeviceRole.stop);
      fixture.bridge.sentMessages.clear();

      unawaited(fixture.controller.startMonitoring());
      await _flushEvents();

      final monitoringSnapshots = fixture.bridge.sentMessages
          .map((sent) => SessionSnapshotMessage.tryParse(sent.messageJson))
          .whereType<SessionSnapshotMessage>()
          .where((snapshot) => snapshot.monitoringActive)
          .toList();
      expect(monitoringSnapshots, isNotEmpty);
      expect(monitoringSnapshots.last.stage, SessionStage.monitoring);

      startMonitoringCompleter.complete();
      await _flushEvents();
      fixture.dispose();
    },
  );

  test(
    'host applies client trigger requests using canonical host time',
    () async {
      final fixture = _ControllerFixture.create();
      final canonicalHostTriggerMicros =
          DateTime.now().microsecondsSinceEpoch - 1000;
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.controller.goToLobby();
      fixture.controller.assignRole('peer-1', SessionDeviceRole.start);

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'peer-1',
        'message': SessionTriggerRequestMessage(
          role: SessionDeviceRole.start,
          deviceTriggerMicros: 111000,
          hostTriggerMicros: canonicalHostTriggerMicros,
        ).toJsonString(),
      });
      await _flushEvents();

      expect(
        fixture.controller.timeline.startedAtEpochMs,
        canonicalHostTriggerMicros ~/ 1000,
      );

      fixture.dispose();
    },
  );

  test('client ignores stale timeline updates', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': SessionSnapshotMessage(
        stage: SessionStage.monitoring,
        monitoringActive: true,
        devices: const <SessionDevice>[
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
          SessionDevice(
            id: 'client-1',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
        ],
        timeline: const SessionRaceTimeline(
          startedAtEpochMs: 1000,
          splitMicros: <int>[250000],
          revision: 2,
        ),
        selfDeviceId: 'client-1',
      ).toJsonString(),
    });
    await _flushEvents();

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': SessionTimelineUpdateMessage(
        timeline: const SessionRaceTimeline(
          startedAtEpochMs: 900,
          splitMicros: <int>[],
          revision: 1,
        ),
      ).toJsonString(),
    });
    await _flushEvents();

    expect(fixture.controller.timeline.revision, 2);
    expect(fixture.controller.timeline.startedAtEpochMs, 1000);
    expect(fixture.controller.timeline.splitMicros, <int>[250000]);

    fixture.dispose();
  });

  test(
    'client sends host-adjusted trigger timestamps after clock sync',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();

      final initialClockSync = SessionClockSyncRequestMessage.tryParse(
        fixture.bridge.sentMessages.last.messageJson,
      );
      expect(initialClockSync, isNotNull);

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
            SessionDevice(
              id: 'client-1',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(revision: 1),
          selfDeviceId: 'client-1',
        ).toJsonString(),
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionClockSyncResponseMessage(
          clientSentAtMicros: initialClockSync!.clientSentAtMicros,
          hostReceivedAtMicros: DateTime.now().microsecondsSinceEpoch - 1200,
          hostSentAtMicros: DateTime.now().microsecondsSinceEpoch - 600,
        ).toJsonString(),
      });
      await _flushEvents();
      fixture.bridge.sentMessages.clear();

      await fixture.controller.onLocalMotionPulse(
        const MotionTriggerEvent(
          triggerMicros: 5000000,
          score: 0.12,
          type: MotionTriggerType.split,
          splitIndex: 1,
        ),
      );

      final triggerRequest = SessionTriggerRequestMessage.tryParse(
        fixture.bridge.sentMessages.last.messageJson,
      );
      expect(triggerRequest, isNotNull);
      expect(triggerRequest!.hostTriggerMicros, isNotNull);

      fixture.dispose();
    },
  );
}

Future<void> _flushEvents() async {
  await Future<void>.delayed(const Duration(milliseconds: 1));
}

SessionDeviceRole _roleOf(RaceSessionController controller, String deviceId) {
  final match = controller.devices.firstWhere(
    (device) => device.id == deviceId,
  );
  return match.role;
}

class _ControllerFixture {
  _ControllerFixture({
    required this.bridge,
    required this.motionController,
    required this.controller,
  });

  final _FakeNearbyBridge bridge;
  final MotionDetectionController motionController;
  final RaceSessionController controller;

  factory _ControllerFixture.create({
    Future<void> Function()? startMonitoringAction,
    Future<void> Function()? stopMonitoringAction,
  }) {
    final bridge = _FakeNearbyBridge();
    final motionController = MotionDetectionController(
      repository: LocalRepository(),
    );
    final controller = RaceSessionController(
      nearbyBridge: bridge,
      motionController: motionController,
      startMonitoringAction: startMonitoringAction ?? () async {},
      stopMonitoringAction: stopMonitoringAction ?? () async {},
    );
    return _ControllerFixture(
      bridge: bridge,
      motionController: motionController,
      controller: controller,
    );
  }

  void dispose() {
    controller.dispose();
    motionController.dispose();
    bridge.dispose();
  }
}

class _FakeNearbyBridge extends NearbyBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();
  final List<_SentMessage> sentMessages = <_SentMessage>[];

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
    sentMessages.add(
      _SentMessage(endpointId: endpointId, messageJson: messageJson),
    );
  }

  @override
  Future<void> disconnect({required String endpointId}) async {}

  @override
  Future<void> stopAll() async {}

  void dispose() {
    _eventsController.close();
  }
}

class _SentMessage {
  const _SentMessage({required this.endpointId, required this.messageJson});

  final String endpointId;
  final String messageJson;
}
