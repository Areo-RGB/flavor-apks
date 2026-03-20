import 'package:flutter_test/flutter_test.dart';
import 'package:sprint_sync/features/race_sync/race_sync_models.dart';

void main() {
  test('race event message serializes and parses', () {
    const message = RaceEventMessage(
      type: RaceEventType.raceStopped,
      sessionId: 'session-1',
      elapsedMicros: 153200,
    );

    final encoded = message.toJsonString();
    final decoded = RaceEventMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.type, RaceEventType.raceStopped);
    expect(decoded.sessionId, 'session-1');
    expect(decoded.elapsedMicros, 153200);
  });

  test('race request payload serializes and parses', () {
    const message = RaceEventMessage(
      type: RaceEventType.raceStopRequest,
      sessionId: 'session-1',
      elapsedMicros: 501000,
    );

    final encoded = message.toJsonString();
    final decoded = RaceEventMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.type, RaceEventType.raceStopRequest);
    expect(decoded.sessionId, 'session-1');
    expect(decoded.elapsedMicros, 501000);
  });

  test('invalid payload returns null', () {
    expect(RaceEventMessage.tryParse('not-json'), isNull);
  });

  test('latency probe payload serializes and parses', () {
    const message = RaceEventMessage(
      type: RaceEventType.latencyProbe,
      sessionId: 'session-2',
      probeId: 'probe-3',
    );

    final encoded = message.toJsonString();
    final decoded = RaceEventMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.type, RaceEventType.latencyProbe);
    expect(decoded.probeId, 'probe-3');
  });

  test('latency pong payload serializes and parses', () {
    const message = RaceEventMessage(
      type: RaceEventType.latencyPong,
      sessionId: 'session-3',
      probeId: 'probe-4',
    );

    final encoded = message.toJsonString();
    final decoded = RaceEventMessage.tryParse(encoded);

    expect(decoded, isNotNull);
    expect(decoded!.type, RaceEventType.latencyPong);
    expect(decoded.probeId, 'probe-4');
  });
}
