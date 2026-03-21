import 'package:flutter_test/flutter_test.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

void main() {
  group('MotionDetectionEngine', () {
    test('does not trigger for static low scores', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );
      MotionFrameStats? latest;

      for (int i = 0; i < 10; i++) {
        latest = engine.process(
          rawScore: 0.01,
          frameSensorNanos: i * 100000000,
        );
      }

      expect(latest, isNotNull);
      expect(latest!.triggerEvent, isNull);
    });

    test('first qualifying pulse emits split trigger', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );

      for (int i = 0; i < 8; i++) {
        engine.process(rawScore: 0.01, frameSensorNanos: i * 100000000);
      }

      final trigger = engine.process(
        rawScore: 0.23,
        frameSensorNanos: 800000000,
      );
      expect(trigger.triggerEvent, isNotNull);
      expect(trigger.triggerEvent!.type, MotionTriggerType.split);
      expect(trigger.triggerEvent!.splitIndex, 1);
    });

    test('re-arm and cooldown allow subsequent triggers', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );

      for (int i = 0; i < 8; i++) {
        engine.process(rawScore: 0.01, frameSensorNanos: i * 100000000);
      }
      engine.process(rawScore: 0.23, frameSensorNanos: 800000000);

      for (int i = 0; i < 3; i++) {
        engine.process(
          rawScore: 0.0,
          frameSensorNanos: 900000000 + (i * 100000000),
        );
      }

      final blocked = engine.process(
        rawScore: 0.24,
        frameSensorNanos: 1200000000,
      );
      expect(blocked.triggerEvent, isNull);

      final next = engine.process(rawScore: 0.24, frameSensorNanos: 1700000000);
      expect(next.triggerEvent, isNotNull);
      expect(next.triggerEvent!.splitIndex, 2);
    });

    test('resetRace clears baseline and pulse index', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );

      for (int i = 0; i < 20; i++) {
        engine.process(rawScore: 0.04, frameSensorNanos: i * 100000000);
      }

      final baselineBefore = engine
          .process(rawScore: 0.04, frameSensorNanos: 2100000000)
          .baseline;
      expect(baselineBefore, greaterThan(0.03));

      engine.resetRace();

      final firstAfterReset = engine.process(
        rawScore: 0.001,
        frameSensorNanos: 3000000000,
      );
      expect(firstAfterReset.baseline, 0.001);

      final trigger = engine.process(
        rawScore: 0.24,
        frameSensorNanos: 3200000000,
      );
      expect(trigger.triggerEvent, isNotNull);
      expect(trigger.triggerEvent!.splitIndex, 1);
    });
  });
}
