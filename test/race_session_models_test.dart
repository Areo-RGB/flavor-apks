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
}
