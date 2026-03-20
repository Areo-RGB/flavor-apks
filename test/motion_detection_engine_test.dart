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

    test('two consecutive qualifying frames emit START with tuned defaults', () {
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
      expect(latest.triggerEvent!.type, MotionTriggerType.start);
      expect(latest.triggerEvent!.splitIndex, 0);
    });

    test('re-arm and second trigger emits STOP', () {
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
      expect(latest.triggerEvent!.type, MotionTriggerType.stop);
    });

    test('additional detections are ignored after STOP until reset', () {
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
        engine.process(rawScore: 0.0, timestampMicros: 2400000 + (i * 100000));
      }

      MotionFrameStats? afterStop;
      for (int i = 0; i < 2; i++) {
        afterStop = engine.process(
          rawScore: 0.24,
          timestampMicros: 2800000 + (i * 100000),
        );
      }

      expect(afterStop, isNotNull);
      expect(afterStop!.triggerEvent, isNull);
    });
  });
}
