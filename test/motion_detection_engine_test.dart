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
        latest = engine.process(rawScore: 0.01, timestampMicros: i * 100000);
      }

      expect(latest, isNotNull);
      expect(latest!.triggerEvent, isNull);
    });

    test('two consecutive qualifying frames emit first pulse event', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );
      MotionFrameStats? latest;

      for (int i = 0; i < 8; i++) {
        engine.process(rawScore: 0.01, timestampMicros: i * 100000);
      }

      for (int i = 0; i < 2; i++) {
        latest = engine.process(
          rawScore: 0.22,
          timestampMicros: 900000 + (i * 100000),
        );
      }

      expect(latest, isNotNull);
      expect(latest!.triggerEvent, isNotNull);
      expect(latest.triggerEvent!.type, MotionTriggerType.split);
      expect(latest.triggerEvent!.splitIndex, 1);
    });

    test('re-arm and second pulse increments index', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );

      for (int i = 0; i < 8; i++) {
        engine.process(rawScore: 0.01, timestampMicros: i * 100000);
      }

      for (int i = 0; i < 2; i++) {
        engine.process(rawScore: 0.23, timestampMicros: 900000 + (i * 100000));
      }

      for (int i = 0; i < 4; i++) {
        engine.process(rawScore: 0.0, timestampMicros: 1300000 + (i * 100000));
      }

      MotionFrameStats? latest;
      for (int i = 0; i < 2; i++) {
        latest = engine.process(
          rawScore: 0.25,
          timestampMicros: 2200000 + (i * 100000),
        );
      }

      expect(latest, isNotNull);
      expect(latest!.triggerEvent, isNotNull);
      expect(latest.triggerEvent!.type, MotionTriggerType.split);
      expect(latest.triggerEvent!.splitIndex, 2);
    });

    test('additional detections continue producing pulses after re-arm', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );

      for (int i = 0; i < 8; i++) {
        engine.process(rawScore: 0.01, timestampMicros: i * 100000);
      }

      for (int i = 0; i < 2; i++) {
        engine.process(rawScore: 0.23, timestampMicros: 900000 + (i * 100000));
      }

      for (int i = 0; i < 4; i++) {
        engine.process(rawScore: 0.0, timestampMicros: 1300000 + (i * 100000));
      }

      for (int i = 0; i < 2; i++) {
        engine.process(rawScore: 0.24, timestampMicros: 2200000 + (i * 100000));
      }

      for (int i = 0; i < 4; i++) {
        engine.process(rawScore: 0.0, timestampMicros: 2800000 + (i * 100000));
      }

      MotionFrameStats? afterStop;
      for (int i = 0; i < 2; i++) {
        afterStop = engine.process(
          rawScore: 0.24,
          timestampMicros: 3300000 + (i * 100000),
        );
      }

      expect(afterStop!.triggerEvent, isNotNull);
      expect(afterStop.triggerEvent!.type, MotionTriggerType.split);
      expect(afterStop.triggerEvent!.splitIndex, 3);
    });

    test('resetRace clears baseline so next session detects motion immediately', () {
      final engine = MotionDetectionEngine(
        config: MotionDetectionConfig.defaults(),
      );

      for (int i = 0; i < 20; i++) {
        engine.process(rawScore: 0.04, timestampMicros: i * 100000);
      }
      final baselineBefore = engine.process(
        rawScore: 0.04,
        timestampMicros: 2100000,
      ).baseline;
      expect(baselineBefore, greaterThan(0.03));

      engine.resetRace();

      final afterReset = engine.process(rawScore: 0.001, timestampMicros: 3000000);
      expect(afterReset.baseline, 0.001);

      MotionFrameStats? triggerFrame;
      for (int i = 0; i < 2; i++) {
        triggerFrame = engine.process(
          rawScore: 0.22,
          timestampMicros: 3200000 + (i * 100000),
        );
      }

      expect(triggerFrame!.triggerEvent, isNotNull,
          reason: 'Motion should be detected immediately after resetRace');
    });
  });
}
