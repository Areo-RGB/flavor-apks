import 'package:flutter_test/flutter_test.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

void main() {
  test('trigger request supports mapped host sensor nanos', () {
    const message = SessionTriggerRequestMessage(
      role: SessionDeviceRole.start,
      triggerSensorNanos: 1200,
      mappedHostSensorNanos: 9876,
    );

    final encoded = message.toJsonString();
    final decoded = SessionTriggerRequestMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.role, SessionDeviceRole.start);
    expect(decoded.triggerSensorNanos, 1200);
    expect(decoded.mappedHostSensorNanos, 9876);
  });

  test('clock sync request serializes and parses nanos', () {
    const message = SessionClockSyncRequestMessage(
      clientSendElapsedNanos: 123456,
    );

    final encoded = message.toJsonString();
    final decoded = SessionClockSyncRequestMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.clientSendElapsedNanos, 123456);
  });

  test('clock sync response serializes and parses nanos', () {
    const message = SessionClockSyncResponseMessage(
      clientSendElapsedNanos: 10,
      hostReceiveElapsedNanos: 20,
      hostSendElapsedNanos: 21,
    );

    final encoded = message.toJsonString();
    final decoded = SessionClockSyncResponseMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.clientSendElapsedNanos, 10);
    expect(decoded.hostReceiveElapsedNanos, 20);
    expect(decoded.hostSendElapsedNanos, 21);
  });

  test('session device camera facing serializes and parses', () {
    const device = SessionDevice(
      id: 'device-1',
      name: 'Device 1',
      role: SessionDeviceRole.start,
      cameraFacing: SessionCameraFacing.front,
      highSpeedEnabled: true,
      isLocal: false,
    );

    final encoded = device.toJson();
    final decoded = SessionDevice.fromJson(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.cameraFacing, SessionCameraFacing.front);
    expect(decoded.highSpeedEnabled, isTrue);
  });

  test(
    'session device camera facing defaults to rear and high speed to false when missing',
    () {
      final decoded = SessionDevice.fromJson(<String, dynamic>{
        'id': 'device-1',
        'name': 'Device 1',
        'role': SessionDeviceRole.stop.name,
        'isLocal': true,
      });

      expect(decoded, isNotNull);
      expect(decoded!.cameraFacing, SessionCameraFacing.rear);
      expect(decoded.highSpeedEnabled, isFalse);
    },
  );

  test('snapshot serializes and parses runId', () {
    const message = SessionSnapshotMessage(
      stage: SessionStage.monitoring,
      monitoringActive: true,
      devices: <SessionDevice>[
        SessionDevice(
          id: 'local-device',
          name: 'Local',
          role: SessionDeviceRole.start,
          isLocal: true,
        ),
      ],
      timeline: SessionRaceTimeline(
        startedSensorNanos: 1000,
        splitElapsedNanos: <int>[200],
      ),
      runId: 'run_123',
    );

    final decoded = SessionSnapshotMessage.tryParse(message.toJsonString());

    expect(decoded, isNotNull);
    expect(decoded!.runId, 'run_123');
  });

  test('trigger refinement message serializes and parses', () {
    const message = SessionTriggerRefinementMessage(
      runId: 'run_42',
      role: SessionDeviceRole.split,
      provisionalHostSensorNanos: 5000,
      refinedHostSensorNanos: 4980,
      splitIndex: 1,
    );

    final decoded = SessionTriggerRefinementMessage.tryParse(
      message.toJsonString(),
    );

    expect(decoded, isNotNull);
    expect(decoded!.runId, 'run_42');
    expect(decoded.role, SessionDeviceRole.split);
    expect(decoded.provisionalHostSensorNanos, 5000);
    expect(decoded.refinedHostSensorNanos, 4980);
    expect(decoded.splitIndex, 1);
  });
}
