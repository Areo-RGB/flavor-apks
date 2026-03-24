import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/core/services/wake_lock_bridge.dart';
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

  test('host startMonitoring enables wake lock once', () async {
    var startMonitoringCalls = 0;
    final fixture = _ControllerFixture.create(
      startMonitoringAction: () async {
        startMonitoringCalls += 1;
      },
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
    fixture.wakeLockBridge.resetCounts();

    await fixture.controller.startMonitoring();
    await _flushEvents();

    expect(fixture.wakeLockBridge.enableCalls, 1);
    expect(fixture.wakeLockBridge.disableCalls, 0);
    expect(startMonitoringCalls, 1);
    fixture.dispose();
  });

  test('host stopMonitoring disables wake lock', () async {
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
    fixture.wakeLockBridge.resetCounts();
    await fixture.controller.startMonitoring();
    await _flushEvents();

    await fixture.controller.stopMonitoring();
    await _flushEvents();

    expect(fixture.wakeLockBridge.enableCalls, 1);
    expect(fixture.wakeLockBridge.disableCalls, 1);
    fixture.dispose();
  });

  test(
    'host startMonitoring with local unassigned role skips local monitoring action',
    () async {
      var startMonitoringCalls = 0;
      var stopMonitoringCalls = 0;
      final fixture = _ControllerFixture.create(
        startMonitoringAction: () async {
          startMonitoringCalls += 1;
        },
        stopMonitoringAction: () async {
          stopMonitoringCalls += 1;
        },
      );
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-start',
        'connected': true,
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-stop',
        'connected': true,
      });
      await _flushEvents();
      fixture.controller.goToLobby();
      fixture.controller.assignRole('peer-start', SessionDeviceRole.start);
      fixture.controller.assignRole('peer-stop', SessionDeviceRole.stop);
      fixture.wakeLockBridge.resetCounts();

      await fixture.controller.startMonitoring();
      await _flushEvents();
      await fixture.controller.stopMonitoring();
      await _flushEvents();

      expect(fixture.controller.localRole, SessionDeviceRole.unassigned);
      expect(fixture.wakeLockBridge.enableCalls, 1);
      expect(fixture.wakeLockBridge.disableCalls, 1);
      expect(startMonitoringCalls, 0);
      expect(stopMonitoringCalls, 0);
      fixture.dispose();
    },
  );

  test('client snapshot transition to monitoring enables wake lock', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.wakeLockBridge.resetCounts();

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': SessionSnapshotMessage(
        stage: SessionStage.monitoring,
        monitoringActive: true,
        devices: const <SessionDevice>[
          SessionDevice(
            id: 'local-device',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
        ],
        timeline: SessionRaceTimeline.idle(),
        hostSensorMinusElapsedNanos: 120000000,
        selfDeviceId: 'local-device',
      ).toJsonString(),
    });
    await _flushEvents();

    expect(fixture.wakeLockBridge.enableCalls, 1);
    fixture.dispose();
  });

  test(
    'client snapshot transition to monitoring with unassigned role skips local monitoring action',
    () async {
      var startMonitoringCalls = 0;
      final fixture = _ControllerFixture.create(
        startMonitoringAction: () async {
          startMonitoringCalls += 1;
        },
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.wakeLockBridge.resetCounts();

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.unassigned,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      expect(fixture.controller.monitoringActive, isTrue);
      expect(fixture.wakeLockBridge.enableCalls, 1);
      expect(startMonitoringCalls, 0);
      fixture.dispose();
    },
  );

  test(
    'client snapshot transition out of monitoring disables wake lock',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.wakeLockBridge.resetCounts();

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.lobby,
          monitoringActive: false,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      expect(fixture.wakeLockBridge.enableCalls, 1);
      expect(fixture.wakeLockBridge.disableCalls, 1);
      fixture.dispose();
    },
  );

  test('dispose releases wake lock when monitoring is active', () async {
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
    fixture.wakeLockBridge.resetCounts();
    await fixture.controller.startMonitoring();
    await _flushEvents();

    fixture.controller.dispose();
    await _flushEvents();

    expect(fixture.wakeLockBridge.enableCalls, 1);
    expect(fixture.wakeLockBridge.disableCalls, 1);
    fixture.motionController.dispose();
    fixture.bridge.dispose();
    fixture.nativeBridge.dispose();
  });

  test(
    'reset session path releases wake lock from active monitoring',
    () async {
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
      fixture.wakeLockBridge.resetCounts();
      await fixture.controller.startMonitoring();
      await _flushEvents();

      await fixture.controller.joinLobby();
      await _flushEvents();

      expect(fixture.wakeLockBridge.enableCalls, 1);
      expect(fixture.wakeLockBridge.disableCalls, 1);
      expect(fixture.controller.monitoringActive, isFalse);
      fixture.dispose();
    },
  );

  test(
    'host uses endpointName from connection_result for device label',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.createLobby();

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'endpointName': 'Pixel 7',
        'connected': true,
      });
      await _flushEvents();

      final peer = fixture.controller.devices.firstWhere(
        (device) => device.id == 'peer-1',
      );
      expect(peer.name, 'Pixel 7');
      fixture.dispose();
    },
  );

  test(
    'host applies mapped host sensor timestamp from client trigger request',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.controller.goToLobby();
      fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
      fixture.controller.assignRole('local-device', SessionDeviceRole.stop);

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'peer-1',
        'message': const SessionTriggerRequestMessage(
          role: SessionDeviceRole.start,
          triggerSensorNanos: 100,
          mappedHostSensorNanos: 5000000000,
        ).toJsonString(),
      });
      await _flushEvents();

      expect(fixture.controller.timeline.startedSensorNanos, 5000000000);
      fixture.dispose();
    },
  );

  test(
    'host applies split fallback timestamp when mapped host sensor is missing',
    () async {
      int nowElapsedNanos = 5300000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-2',
        'connected': true,
      });
      await _flushEvents();
      fixture.controller.goToLobby();
      fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
      fixture.controller.assignRole('peer-2', SessionDeviceRole.split);
      fixture.controller.assignRole('local-device', SessionDeviceRole.stop);

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'peer-1',
        'message': const SessionTriggerRequestMessage(
          role: SessionDeviceRole.start,
          triggerSensorNanos: 100,
          mappedHostSensorNanos: 5000000000,
        ).toJsonString(),
      });
      await _flushEvents();

      nowElapsedNanos = 5300000000;
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'peer-2',
        'message': const SessionTriggerRequestMessage(
          role: SessionDeviceRole.split,
          triggerSensorNanos: 200,
          mappedHostSensorNanos: null,
        ).toJsonString(),
      });
      await _flushEvents();

      expect(fixture.controller.timeline.splitElapsedNanos, <int>[300000000]);
      fixture.dispose();
    },
  );

  test('split role only records first split per run', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-2',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();
    fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-2', SessionDeviceRole.split);
    fixture.controller.assignRole('local-device', SessionDeviceRole.stop);

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-1',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.start,
        triggerSensorNanos: 100,
        mappedHostSensorNanos: 5000000000,
      ).toJsonString(),
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 200,
        mappedHostSensorNanos: 5300000000,
      ).toJsonString(),
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 300,
        mappedHostSensorNanos: 5900000000,
      ).toJsonString(),
    });
    await _flushEvents();

    expect(fixture.controller.timeline.splitElapsedNanos, <int>[300000000]);
    expect(fixture.motionController.runSnapshot.splitElapsedNanos, <int>[
      300000000,
    ]);
    fixture.dispose();
  });

  test('host ignores duplicate split trigger requests from client', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-2',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();
    fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-2', SessionDeviceRole.split);
    fixture.controller.assignRole('local-device', SessionDeviceRole.stop);

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-1',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.start,
        triggerSensorNanos: 100,
        mappedHostSensorNanos: 5000000000,
      ).toJsonString(),
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 200,
        mappedHostSensorNanos: 5400000000,
      ).toJsonString(),
    });
    await _flushEvents();
    expect(fixture.controller.timeline.splitElapsedNanos, <int>[400000000]);

    fixture.bridge.sentPayloads.clear();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 300,
        mappedHostSensorNanos: 5600000000,
      ).toJsonString(),
    });
    await _flushEvents();

    final duplicateSnapshots = fixture.bridge.sentPayloads
        .map((payload) => SessionSnapshotMessage.tryParse(payload.messageJson))
        .whereType<SessionSnapshotMessage>()
        .toList();
    expect(duplicateSnapshots, isEmpty);
    expect(fixture.controller.timeline.splitElapsedNanos, <int>[400000000]);
    fixture.dispose();
  });

  test(
    'split role still records after total device count drops during monitoring',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-2',
        'connected': true,
      });
      await _flushEvents();
      fixture.controller.goToLobby();
      fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
      fixture.controller.assignRole('peer-2', SessionDeviceRole.split);
      fixture.controller.assignRole('local-device', SessionDeviceRole.stop);

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'peer-1',
        'message': const SessionTriggerRequestMessage(
          role: SessionDeviceRole.start,
          triggerSensorNanos: 100,
          mappedHostSensorNanos: 5000000000,
        ).toJsonString(),
      });
      await _flushEvents();

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'endpoint_disconnected',
        'endpointId': 'peer-1',
      });
      await _flushEvents();
      expect(fixture.controller.totalDeviceCount, 2);

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'peer-2',
        'message': const SessionTriggerRequestMessage(
          role: SessionDeviceRole.split,
          triggerSensorNanos: 200,
          mappedHostSensorNanos: 5300000000,
        ).toJsonString(),
      });
      await _flushEvents();

      expect(fixture.controller.timeline.splitElapsedNanos, <int>[300000000]);
      fixture.dispose();
    },
  );

  test('split allowance resets on new run after reset', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.createLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-2',
      'connected': true,
    });
    await _flushEvents();
    fixture.controller.goToLobby();
    fixture.controller.assignRole('peer-1', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-2', SessionDeviceRole.split);
    fixture.controller.assignRole('local-device', SessionDeviceRole.stop);

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-1',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.start,
        triggerSensorNanos: 100,
        mappedHostSensorNanos: 5000000000,
      ).toJsonString(),
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 200,
        mappedHostSensorNanos: 5300000000,
      ).toJsonString(),
    });
    await _flushEvents();
    expect(fixture.controller.timeline.splitElapsedNanos, <int>[300000000]);

    await fixture.controller.resetRun();
    await _flushEvents();
    expect(fixture.controller.timeline.splitElapsedNanos, isEmpty);

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-1',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.start,
        triggerSensorNanos: 400,
        mappedHostSensorNanos: 7000000000,
      ).toJsonString(),
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 500,
        mappedHostSensorNanos: 7400000000,
      ).toJsonString(),
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'peer-2',
      'message': const SessionTriggerRequestMessage(
        role: SessionDeviceRole.split,
        triggerSensorNanos: 600,
        mappedHostSensorNanos: 7800000000,
      ).toJsonString(),
    });
    await _flushEvents();

    expect(fixture.controller.timeline.startedSensorNanos, 7000000000);
    expect(fixture.controller.timeline.splitElapsedNanos, <int>[400000000]);
    fixture.dispose();
  });

  test(
    'client maps local sensor trigger into host sensor domain after sync',
    () async {
      int nowElapsedNanos = 1200000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();

      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 700000000,
      });
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 2000000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.0,
      });
      await _flushEvents();

      fixture.bridge.sentPayloads.clear();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();
      final syncRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(syncRequests, hasLength(10));
      final syncRequest = syncRequests.first;
      nowElapsedNanos = 1300000000;
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionClockSyncResponseMessage(
          clientSendElapsedNanos: syncRequest.clientSendElapsedNanos,
          hostReceiveElapsedNanos: 5050000000,
          hostSendElapsedNanos: 5050000010,
        ).toJsonString(),
      });
      await _flushEvents();
      expect(fixture.controller.monitoringLatencyMs, isNotNull);
      expect(fixture.controller.monitoringSyncModeLabel, 'NTP');
      fixture.bridge.sentPayloads.clear();

      await fixture.controller.onLocalMotionPulse(
        const MotionTriggerEvent(
          triggerSensorNanos: 2000000000,
          score: 0.1,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );

      final triggerRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionTriggerRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionTriggerRequestMessage>()
          .toList();
      expect(triggerRequests, isNotEmpty);
      expect(triggerRequests.last.mappedHostSensorNanos, 5170000000);
      fixture.dispose();
    },
  );

  test('client uses GPS offset when both devices have fresh GPS', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.bridge.sentPayloads.clear();

    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 700000000,
      'gpsUtcOffsetNanos': 3000000000,
      'gpsFixElapsedRealtimeNanos': 1295000000,
    });
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_frame_stats',
      'frameSensorNanos': 2000000000,
      'rawScore': 0.01,
      'baseline': 0.01,
      'effectiveScore': 0.0,
      'gpsUtcOffsetNanos': 3000000000,
      'gpsFixElapsedRealtimeNanos': 1295000000,
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
            id: 'local-device',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
        ],
        timeline: SessionRaceTimeline.idle(),
        hostSensorMinusElapsedNanos: 120000000,
        hostGpsUtcOffsetNanos: 2800000000,
        hostGpsFixAgeNanos: 1000000,
        selfDeviceId: 'local-device',
      ).toJsonString(),
    });
    await _flushEvents();

    final syncRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionClockSyncRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionClockSyncRequestMessage>()
        .toList();
    expect(syncRequests, isEmpty);
    expect(fixture.controller.monitoringSyncModeLabel, 'GPS');
    expect(fixture.controller.monitoringLatencyMs, isNull);

    await fixture.controller.onLocalMotionPulse(
      const MotionTriggerEvent(
        triggerSensorNanos: 2000000000,
        score: 0.1,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );

    final triggerRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionTriggerRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionTriggerRequestMessage>()
        .toList();
    expect(triggerRequests, isNotEmpty);
    expect(triggerRequests.last.mappedHostSensorNanos, 1620000000);
    fixture.dispose();
  });

  test('client falls back to NTP when GPS is unavailable', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.bridge.sentPayloads.clear();

    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 700000000,
    });
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_frame_stats',
      'frameSensorNanos': 2000000000,
      'rawScore': 0.01,
      'baseline': 0.01,
      'effectiveScore': 0.0,
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
            id: 'local-device',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
        ],
        timeline: SessionRaceTimeline.idle(),
        hostSensorMinusElapsedNanos: 120000000,
        selfDeviceId: 'local-device',
      ).toJsonString(),
    });
    await _flushEvents();

    final syncRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionClockSyncRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionClockSyncRequestMessage>()
        .toList();
    expect(syncRequests, hasLength(10));
    fixture.dispose();
  });

  test('client falls back to NTP when GPS is stale', () async {
    final fixture = _ControllerFixture.create();
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.bridge.sentPayloads.clear();

    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 700000000,
      'gpsUtcOffsetNanos': 3000000000,
      'gpsFixElapsedRealtimeNanos': 1000000000,
    });
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_frame_stats',
      'frameSensorNanos': 15000000000,
      'rawScore': 0.01,
      'baseline': 0.01,
      'effectiveScore': 0.0,
      'gpsUtcOffsetNanos': 3000000000,
      'gpsFixElapsedRealtimeNanos': 1000000000,
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
            id: 'local-device',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
        ],
        timeline: SessionRaceTimeline.idle(),
        hostSensorMinusElapsedNanos: 120000000,
        hostGpsUtcOffsetNanos: 2800000000,
        hostGpsFixAgeNanos: 1000000,
        selfDeviceId: 'local-device',
      ).toJsonString(),
    });
    await _flushEvents();

    final syncRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionClockSyncRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionClockSyncRequestMessage>()
        .toList();
    expect(syncRequests, hasLength(10));
    fixture.dispose();
  });

  test('GPS offset is preferred over NTP when both are available', () async {
    int nowElapsedNanos = 1200000000;
    final fixture = _ControllerFixture.create(
      nowElapsedNanos: () => nowElapsedNanos,
    );
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();

    final initialSyncRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionClockSyncRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionClockSyncRequestMessage>()
        .toList();
    expect(initialSyncRequests, hasLength(10));
    final syncRequest = initialSyncRequests.first;
    nowElapsedNanos = 1300000000;
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': SessionClockSyncResponseMessage(
        clientSendElapsedNanos: syncRequest.clientSendElapsedNanos,
        hostReceiveElapsedNanos: 5050000000,
        hostSendElapsedNanos: 5050000010,
      ).toJsonString(),
    });
    await _flushEvents();

    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 700000000,
      'gpsUtcOffsetNanos': 3000000000,
      'gpsFixElapsedRealtimeNanos': 1295000000,
    });
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_frame_stats',
      'frameSensorNanos': 2000000000,
      'rawScore': 0.01,
      'baseline': 0.01,
      'effectiveScore': 0.0,
      'gpsUtcOffsetNanos': 3000000000,
      'gpsFixElapsedRealtimeNanos': 1295000000,
    });
    await _flushEvents();
    fixture.bridge.sentPayloads.clear();

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': SessionSnapshotMessage(
        stage: SessionStage.monitoring,
        monitoringActive: true,
        devices: const <SessionDevice>[
          SessionDevice(
            id: 'local-device',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
        ],
        timeline: SessionRaceTimeline.idle(),
        hostSensorMinusElapsedNanos: 120000000,
        hostGpsUtcOffsetNanos: 2800000000,
        hostGpsFixAgeNanos: 1000000,
        selfDeviceId: 'local-device',
      ).toJsonString(),
    });
    await _flushEvents();
    fixture.bridge.sentPayloads.clear();

    await fixture.controller.onLocalMotionPulse(
      const MotionTriggerEvent(
        triggerSensorNanos: 2000000000,
        score: 0.1,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );

    final triggerRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionTriggerRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionTriggerRequestMessage>()
        .toList();
    expect(triggerRequests, isNotEmpty);
    expect(triggerRequests.last.mappedHostSensorNanos, 1620000000);
    expect(fixture.controller.monitoringSyncModeLabel, 'GPS');
    expect(fixture.controller.monitoringLatencyMs, isNull);
    fixture.dispose();
  });

  test('host rebroadcasts snapshot when host GPS offset changes', () async {
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
    await _flushEvents();
    fixture.bridge.sentPayloads.clear();

    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 500000000,
      'gpsUtcOffsetNanos': 4000000000,
      'gpsFixElapsedRealtimeNanos': 2500000000,
    });
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_frame_stats',
      'frameSensorNanos': 3000000000,
      'rawScore': 0.01,
      'baseline': 0.01,
      'effectiveScore': 0.0,
      'gpsUtcOffsetNanos': 4000000000,
      'gpsFixElapsedRealtimeNanos': 2500000000,
    });
    await _flushEvents();

    final firstSnapshots = fixture.bridge.sentPayloads
        .map((payload) => SessionSnapshotMessage.tryParse(payload.messageJson))
        .whereType<SessionSnapshotMessage>()
        .toList();
    expect(firstSnapshots, isNotEmpty);
    expect(firstSnapshots.last.hostGpsUtcOffsetNanos, 4000000000);
    fixture.bridge.sentPayloads.clear();

    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 500000000,
      'gpsUtcOffsetNanos': 4100000000,
      'gpsFixElapsedRealtimeNanos': 3500000000,
    });
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_frame_stats',
      'frameSensorNanos': 5000000000,
      'rawScore': 0.01,
      'baseline': 0.01,
      'effectiveScore': 0.0,
      'gpsUtcOffsetNanos': 4100000000,
      'gpsFixElapsedRealtimeNanos': 3500000000,
    });
    await _flushEvents();

    final secondSnapshots = fixture.bridge.sentPayloads
        .map((payload) => SessionSnapshotMessage.tryParse(payload.messageJson))
        .whereType<SessionSnapshotMessage>()
        .toList();
    expect(secondSnapshots, isNotEmpty);
    expect(secondSnapshots.last.hostGpsUtcOffsetNanos, 4100000000);
    fixture.dispose();
  });

  test(
    'client trigger is rejected when there is no valid clock sync',
    () async {
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => 2000000000,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 500000000,
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();
      fixture.bridge.sentPayloads.clear();

      await fixture.controller.onLocalMotionPulse(
        const MotionTriggerEvent(
          triggerSensorNanos: 2000000000,
          score: 0.1,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );

      final triggerRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionTriggerRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionTriggerRequestMessage>()
          .toList();
      expect(triggerRequests, isEmpty);
      expect(fixture.controller.errorText, contains('no valid clock lock'));
      expect(fixture.controller.isClockLockWarningVisible, isTrue);
      expect(
        fixture.controller.clockLockWarningText,
        contains('being dropped'),
      );
      fixture.dispose();
    },
  );

  test(
    'client timeline sync maps host start into local sensor domain',
    () async {
      int nowElapsedNanos = 1200000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();

      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 700000000,
      });
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 2000000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.0,
      });
      await _flushEvents();

      fixture.bridge.sentPayloads.clear();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();
      final syncRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(syncRequests, hasLength(10));
      final syncRequest = syncRequests.first;
      nowElapsedNanos = 1300000000;
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionClockSyncResponseMessage(
          clientSendElapsedNanos: syncRequest.clientSendElapsedNanos,
          hostReceiveElapsedNanos: 5050000000,
          hostSendElapsedNanos: 5050000010,
        ).toJsonString(),
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
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
          ],
          timeline: const SessionRaceTimeline(
            startedSensorNanos: 5000000000,
            splitElapsedNanos: <int>[],
            stopElapsedNanos: null,
          ),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      expect(
        fixture.motionController.runSnapshot.startedSensorNanos,
        1830000000,
      );
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1900000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.0,
      });
      await _flushEvents();
      expect(
        fixture.motionController.runSnapshot.elapsedNanos,
        inInclusiveRange(60000000, 80000000),
      );
      fixture.dispose();
    },
  );

  test(
    'client keeps stopwatch elapsed sane when host/client uptimes differ',
    () async {
      int nowElapsedNanos = 1000000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.bridge.sentPayloads.clear();

      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 100000000,
      });
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 391037540000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.0,
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
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 100000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      final syncRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(syncRequests, hasLength(10));
      final syncRequest = syncRequests.first;
      nowElapsedNanos = 1100000000;
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionClockSyncResponseMessage(
          clientSendElapsedNanos: syncRequest.clientSendElapsedNanos,
          hostReceiveElapsedNanos: 7050000000,
          hostSendElapsedNanos: 7050000010,
        ).toJsonString(),
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
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
          ],
          timeline: const SessionRaceTimeline(
            startedSensorNanos: 7050000000,
            splitElapsedNanos: <int>[],
            stopElapsedNanos: null,
          ),
          hostSensorMinusElapsedNanos: 100000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 391044490000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.0,
      });
      await _flushEvents();

      expect(
        fixture.motionController.runSnapshot.elapsedNanos,
        inInclusiveRange(6000000000, 8000000000),
      );
      fixture.dispose();
    },
  );

  test(
    'client trigger is rejected when clock sync RTT exceeds 250ms',
    () async {
      int nowElapsedNanos = 1000000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 500000000,
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();
      final syncRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(syncRequests, hasLength(10));

      for (final request in syncRequests) {
        final highRttClientReceiveElapsedNanos =
            request.clientSendElapsedNanos + 280000000;
        fixture.nativeBridge.emitEvent(<String, dynamic>{
          'type': 'native_frame_stats',
          'frameSensorNanos': highRttClientReceiveElapsedNanos + 500000000,
          'rawScore': 0.01,
          'baseline': 0.01,
          'effectiveScore': 0.0,
        });
        await _flushEvents();
        nowElapsedNanos = highRttClientReceiveElapsedNanos;
        fixture.bridge.emitEvent(<String, dynamic>{
          'type': 'payload_received',
          'endpointId': 'host-1',
          'message': SessionClockSyncResponseMessage(
            clientSendElapsedNanos: request.clientSendElapsedNanos,
            hostReceiveElapsedNanos: 5000000000,
            hostSendElapsedNanos: 5000000010,
          ).toJsonString(),
        });
        await _flushEvents();
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
      expect(fixture.controller.clockLockWarningText, isNotNull);
      fixture.bridge.sentPayloads.clear();

      await fixture.controller.onLocalMotionPulse(
        const MotionTriggerEvent(
          triggerSensorNanos: 2000000000,
          score: 0.1,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );

      final triggerRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionTriggerRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionTriggerRequestMessage>()
          .toList();
      expect(triggerRequests, isEmpty);
      expect(fixture.controller.isClockLockWarningVisible, isTrue);
      fixture.dispose();
    },
  );

  test(
    'client runs second sync burst when first best RTT is above 20ms target',
    () async {
      int nowElapsedNanos = 1000000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 500000000,
      });
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      final firstBurstRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(firstBurstRequests, hasLength(10));

      for (final request in firstBurstRequests) {
        final firstBurstClientReceiveElapsedNanos =
            request.clientSendElapsedNanos + 40000000;
        fixture.nativeBridge.emitEvent(<String, dynamic>{
          'type': 'native_frame_stats',
          'frameSensorNanos': firstBurstClientReceiveElapsedNanos + 500000000,
          'rawScore': 0.01,
          'baseline': 0.01,
          'effectiveScore': 0.0,
        });
        await _flushEvents();
        nowElapsedNanos = firstBurstClientReceiveElapsedNanos;
        fixture.bridge.emitEvent(<String, dynamic>{
          'type': 'payload_received',
          'endpointId': 'host-1',
          'message': SessionClockSyncResponseMessage(
            clientSendElapsedNanos: request.clientSendElapsedNanos,
            hostReceiveElapsedNanos: 5000000000,
            hostSendElapsedNanos: 5000000010,
          ).toJsonString(),
        });
        await _flushEvents();
      }
      await Future<void>.delayed(const Duration(milliseconds: 650));

      final allRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(allRequests.length, greaterThanOrEqualTo(20));
      fixture.dispose();
    },
  );

  test(
    'client uses the lowest RTT sample from a sync burst for trigger mapping',
    () async {
      int nowElapsedNanos = 1200000000;
      final fixture = _ControllerFixture.create(
        nowElapsedNanos: () => nowElapsedNanos,
      );
      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await _flushEvents();

      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_state',
        'hostSensorMinusElapsedNanos': 700000000,
      });
      fixture.nativeBridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 2000000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.0,
      });
      await _flushEvents();

      fixture.bridge.sentPayloads.clear();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      final syncRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionClockSyncRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionClockSyncRequestMessage>()
          .toList();
      expect(syncRequests, hasLength(10));

      const bestSampleIndex = 3;
      const bestRttNanos = 8000000;
      final bestRequest = syncRequests[bestSampleIndex];
      final expectedOffsetNanos =
          5000000000 -
          (bestRequest.clientSendElapsedNanos + (bestRttNanos ~/ 2));
      final expectedMappedHostSensorNanos =
          (2000000000 - 700000000) + expectedOffsetNanos + 120000000;

      for (var i = 0; i < syncRequests.length; i += 1) {
        final request = syncRequests[i];
        final rttNanos = i == bestSampleIndex ? bestRttNanos : 30000000;
        fixture.nativeBridge.emitEvent(<String, dynamic>{
          'type': 'native_frame_stats',
          'frameSensorNanos':
              700000000 + request.clientSendElapsedNanos + rttNanos,
          'rawScore': 0.01,
          'baseline': 0.01,
          'effectiveScore': 0.0,
        });
        fixture.bridge.emitEvent(<String, dynamic>{
          'type': 'payload_received',
          'endpointId': 'host-1',
          'message': SessionClockSyncResponseMessage(
            clientSendElapsedNanos: request.clientSendElapsedNanos,
            hostReceiveElapsedNanos: 5000000000,
            hostSendElapsedNanos: 5000000010,
          ).toJsonString(),
        });
      }
      await _flushEvents();
      fixture.bridge.sentPayloads.clear();

      await fixture.controller.onLocalMotionPulse(
        const MotionTriggerEvent(
          triggerSensorNanos: 2000000000,
          score: 0.1,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );

      final triggerRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionTriggerRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionTriggerRequestMessage>()
          .toList();
      expect(triggerRequests, isNotEmpty);
      expect(
        triggerRequests.last.mappedHostSensorNanos,
        expectedMappedHostSensorNanos,
      );
      fixture.dispose();
    },
  );

  test('client trigger is rejected when clock sync becomes stale', () async {
    int nowElapsedNanos = 1200000000;
    final fixture = _ControllerFixture.create(
      nowElapsedNanos: () => nowElapsedNanos,
    );
    await fixture.controller.joinLobby();
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'host-1',
      'connected': true,
    });
    await _flushEvents();
    fixture.nativeBridge.emitEvent(<String, dynamic>{
      'type': 'native_state',
      'hostSensorMinusElapsedNanos': 700000000,
    });
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': SessionSnapshotMessage(
        stage: SessionStage.monitoring,
        monitoringActive: true,
        devices: const <SessionDevice>[
          SessionDevice(
            id: 'local-device',
            name: 'Client',
            role: SessionDeviceRole.start,
            isLocal: false,
          ),
          SessionDevice(
            id: 'host-1',
            name: 'Host',
            role: SessionDeviceRole.stop,
            isLocal: false,
          ),
        ],
        timeline: SessionRaceTimeline.idle(),
        hostSensorMinusElapsedNanos: 120000000,
        selfDeviceId: 'local-device',
      ).toJsonString(),
    });
    await _flushEvents();

    nowElapsedNanos = 1300000000;
    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'payload_received',
      'endpointId': 'host-1',
      'message': const SessionClockSyncResponseMessage(
        clientSendElapsedNanos: 1200000000,
        hostReceiveElapsedNanos: 5000000000,
        hostSendElapsedNanos: 5000000010,
      ).toJsonString(),
    });
    await _flushEvents();

    nowElapsedNanos = 7000000000;
    fixture.bridge.sentPayloads.clear();
    await fixture.controller.onLocalMotionPulse(
      const MotionTriggerEvent(
        triggerSensorNanos: 7600000000,
        score: 0.1,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );

    final triggerRequests = fixture.bridge.sentPayloads
        .map(
          (payload) =>
              SessionTriggerRequestMessage.tryParse(payload.messageJson),
        )
        .whereType<SessionTriggerRequestMessage>()
        .toList();
    expect(triggerRequests, isEmpty);
    expect(fixture.controller.errorText, contains('no valid clock lock'));
    expect(fixture.controller.isClockLockWarningVisible, isTrue);
    fixture.dispose();
  });

  test(
    'host camera-facing assignment updates device and snapshot payload',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.bridge.sentPayloads.clear();

      fixture.controller.assignCameraFacing(
        'local-device',
        SessionCameraFacing.front,
      );
      await _flushEvents();

      final localDevice = fixture.controller.devices.firstWhere(
        (device) => device.id == 'local-device',
      );
      expect(localDevice.cameraFacing, SessionCameraFacing.front);
      final snapshots = fixture.bridge.sentPayloads
          .map(
            (payload) => SessionSnapshotMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionSnapshotMessage>()
          .toList();
      expect(snapshots, isNotEmpty);
      final localDeviceInSnapshot = snapshots.last.devices.firstWhere(
        (device) => device.id == 'local-device',
      );
      expect(localDeviceInSnapshot.cameraFacing, SessionCameraFacing.front);
      fixture.dispose();
    },
  );

  test(
    'client applies snapshot camera-facing before monitoring starts',
    () async {
      MotionCameraFacing? facingAtStart;
      late _ControllerFixture fixture;
      fixture = _ControllerFixture.create(
        startMonitoringAction: () async {
          facingAtStart = fixture.motionController.config.cameraFacing;
        },
      );

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
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              cameraFacing: SessionCameraFacing.front,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              cameraFacing: SessionCameraFacing.rear,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      expect(facingAtStart, MotionCameraFacing.front);
      expect(
        fixture.motionController.config.cameraFacing,
        MotionCameraFacing.front,
      );
      fixture.dispose();
    },
  );

  test(
    'client split trigger is sent when clock lock is invalid',
    () async {
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
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.split,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();
      fixture.bridge.sentPayloads.clear();

      await fixture.controller.onLocalMotionPulse(
        const MotionTriggerEvent(
          triggerSensorNanos: 2000000000,
          score: 0.1,
          type: MotionTriggerType.split,
          splitIndex: 1,
        ),
      );

      final triggerRequests = fixture.bridge.sentPayloads
          .map(
            (payload) =>
                SessionTriggerRequestMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionTriggerRequestMessage>()
          .toList();
      expect(triggerRequests, hasLength(1));
      expect(triggerRequests.single.role, SessionDeviceRole.split);
      expect(triggerRequests.single.mappedHostSensorNanos, isNull);
      fixture.dispose();
    },
  );

  test(
    'host high-speed assignment updates device and snapshot payload',
    () async {
      final fixture = _ControllerFixture.create();
      await fixture.controller.createLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'peer-1',
        'connected': true,
      });
      await _flushEvents();
      fixture.bridge.sentPayloads.clear();

      fixture.controller.assignHighSpeedEnabled('local-device', true);
      await _flushEvents();

      final localDevice = fixture.controller.devices.firstWhere(
        (device) => device.id == 'local-device',
      );
      expect(localDevice.highSpeedEnabled, isTrue);
      final snapshots = fixture.bridge.sentPayloads
          .map(
            (payload) => SessionSnapshotMessage.tryParse(payload.messageJson),
          )
          .whereType<SessionSnapshotMessage>()
          .toList();
      expect(snapshots, isNotEmpty);
      final localDeviceInSnapshot = snapshots.last.devices.firstWhere(
        (device) => device.id == 'local-device',
      );
      expect(localDeviceInSnapshot.highSpeedEnabled, isTrue);
      fixture.dispose();
    },
  );

  test(
    'client applies snapshot high-speed mode before monitoring starts',
    () async {
      bool? highSpeedAtStart;
      late _ControllerFixture fixture;
      fixture = _ControllerFixture.create(
        startMonitoringAction: () async {
          highSpeedAtStart = fixture.motionController.config.highSpeedEnabled;
        },
      );

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
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              cameraFacing: SessionCameraFacing.front,
              highSpeedEnabled: true,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              cameraFacing: SessionCameraFacing.rear,
              highSpeedEnabled: false,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await _flushEvents();

      expect(highSpeedAtStart, isTrue);
      expect(fixture.motionController.config.highSpeedEnabled, isTrue);
      fixture.dispose();
    },
  );
}

Future<void> _flushEvents() async {
  await Future<void>.delayed(const Duration(milliseconds: 1));
}

class _ControllerFixture {
  _ControllerFixture({
    required this.bridge,
    required this.nativeBridge,
    required this.wakeLockBridge,
    required this.motionController,
    required this.controller,
  });

  final _FakeNearbyBridge bridge;
  final _FakeNativeSensorBridge nativeBridge;
  final _FakeWakeLockBridge wakeLockBridge;
  final MotionDetectionController motionController;
  final RaceSessionController controller;

  factory _ControllerFixture.create({
    int Function()? nowElapsedNanos,
    Future<void> Function()? startMonitoringAction,
    Future<void> Function()? stopMonitoringAction,
  }) {
    final bridge = _FakeNearbyBridge();
    final nativeBridge = _FakeNativeSensorBridge();
    final wakeLockBridge = _FakeWakeLockBridge();
    final motionController = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: nativeBridge,
    );
    final controller = RaceSessionController(
      nearbyBridge: bridge,
      motionController: motionController,
      startMonitoringAction: startMonitoringAction ?? () async {},
      stopMonitoringAction: stopMonitoringAction ?? () async {},
      nowElapsedNanos: nowElapsedNanos,
      wakeLockBridge: wakeLockBridge,
    );
    return _ControllerFixture(
      bridge: bridge,
      nativeBridge: nativeBridge,
      wakeLockBridge: wakeLockBridge,
      motionController: motionController,
      controller: controller,
    );
  }

  void dispose() {
    controller.dispose();
    motionController.dispose();
    bridge.dispose();
    nativeBridge.dispose();
  }
}

class _FakeWakeLockBridge extends WakeLockBridge {
  int enableCalls = 0;
  int disableCalls = 0;
  int toggleCalls = 0;

  void resetCounts() {
    enableCalls = 0;
    disableCalls = 0;
    toggleCalls = 0;
  }

  @override
  Future<void> enable() async {
    enableCalls += 1;
  }

  @override
  Future<void> disable() async {
    disableCalls += 1;
  }

  @override
  Future<void> toggle({required bool enable}) async {
    toggleCalls += 1;
    if (enable) {
      enableCalls += 1;
    } else {
      disableCalls += 1;
    }
  }
}

class _FakeNativeSensorBridge extends NativeSensorBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

  void emitEvent(Map<String, dynamic> event) {
    _eventsController.add(event);
  }

  @override
  Future<void> startNativeMonitoring({
    required Map<String, dynamic> config,
  }) async {}

  @override
  Future<void> stopNativeMonitoring() async {}

  @override
  Future<void> updateNativeConfig({
    required Map<String, dynamic> config,
  }) async {}

  @override
  Future<void> resetNativeRun() async {}

  void dispose() {
    _eventsController.close();
  }
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

  void dispose() {
    _eventsController.close();
  }
}

class _SentPayload {
  const _SentPayload({required this.endpointId, required this.messageJson});

  final String endpointId;
  final String messageJson;
}
