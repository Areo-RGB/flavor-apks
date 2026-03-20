import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('start trigger initializes run and elapsed ticker', () async {
    final controller = MotionDetectionController(repository: LocalRepository());
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1000000,
        score: 0.20,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );

    expect(controller.isRunActive, isTrue);
    expect(controller.runStatusLabel, 'running');
    expect(controller.currentSplitMicros, isEmpty);
    expect(controller.elapsedDisplay, '0.00s');

    await Future<void>.delayed(const Duration(milliseconds: 80));
    expect(controller.runSnapshot.elapsedMicros, greaterThan(0));

    await Future<void>.delayed(const Duration(milliseconds: 5));
    final savedRun = await LocalRepository().loadLastRun();
    expect(savedRun, isNotNull);
    expect(savedRun!.startedAtEpochMs, 1000);

    controller.dispose();
  });

  test(
    'stop trigger freezes elapsed time and persists final run time',
    () async {
      final controller = MotionDetectionController(
        repository: LocalRepository(),
      );
      await Future<void>.delayed(const Duration(milliseconds: 1));

      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerMicros: 2000000,
          score: 0.22,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );
      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerMicros: 2750000,
          score: 0.24,
          type: MotionTriggerType.stop,
          splitIndex: 0,
        ),
      );

      expect(controller.isRunActive, isFalse);
      expect(controller.runStatusLabel, 'stopped');
      expect(controller.currentSplitMicros, <int>[750000]);
      final frozenElapsed = controller.runSnapshot.elapsedMicros;
      await Future<void>.delayed(const Duration(milliseconds: 80));
      expect(controller.runSnapshot.elapsedMicros, frozenElapsed);

      await Future<void>.delayed(const Duration(milliseconds: 5));
      final savedRun = await LocalRepository().loadLastRun();
      expect(savedRun, isNotNull);
      expect(savedRun!.startedAtEpochMs, 2000);
      expect(savedRun.splitMicros, <int>[750000]);

      controller.dispose();
    },
  );

  test(
    'split trigger appends intermediate marks while run is active',
    () async {
      final controller = MotionDetectionController(
        repository: LocalRepository(),
      );
      await Future<void>.delayed(const Duration(milliseconds: 1));

      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerMicros: 1000000,
          score: 0.20,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
      );
      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerMicros: 1300000,
          score: 0.21,
          type: MotionTriggerType.split,
          splitIndex: 1,
        ),
      );
      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerMicros: 1650000,
          score: 0.22,
          type: MotionTriggerType.split,
          splitIndex: 2,
        ),
      );

      expect(controller.isRunActive, isTrue);
      expect(controller.currentSplitMicros, <int>[300000, 650000]);

      controller.dispose();
    },
  );

  test('manual reset clears active run, splits, and trigger history', () async {
    final controller = MotionDetectionController(repository: LocalRepository());
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1000000,
        score: 0.20,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1500000,
        score: 0.21,
        type: MotionTriggerType.stop,
        splitIndex: 0,
      ),
    );
    expect(controller.triggerHistory, isNotEmpty);
    expect(controller.currentSplitMicros, isNotEmpty);

    controller.resetRace();

    expect(controller.isRunActive, isFalse);
    expect(controller.runStatusLabel, 'ready');
    expect(controller.elapsedDisplay, '0.00s');
    expect(controller.currentSplitMicros, isEmpty);
    expect(controller.triggerHistory, isEmpty);

    controller.dispose();
  });

  test('loads latest saved run on startup', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'last_run_result_v1': jsonEncode({
        'startedAtEpochMs': 12345,
        'splitMicros': <int>[100000, 250000],
      }),
    });

    final controller = MotionDetectionController(repository: LocalRepository());
    await Future<void>.delayed(const Duration(milliseconds: 20));

    expect(controller.lastRun, isNotNull);
    expect(controller.lastRun!.startedAtEpochMs, 12345);
    expect(controller.lastRun!.splitMicros, <int>[100000, 250000]);

    controller.dispose();
  });

  test('new START after STOP begins a fresh run', () async {
    final controller = MotionDetectionController(repository: LocalRepository());
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1000000,
        score: 0.20,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1500000,
        score: 0.21,
        type: MotionTriggerType.stop,
        splitIndex: 0,
      ),
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 2100000,
        score: 0.22,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );
    expect(controller.runStatusLabel, 'running');
    expect(controller.isRunActive, isTrue);
    expect(controller.currentSplitMicros, isEmpty);
    expect(controller.runSnapshot.startedAtMicros, 2100000);

    controller.dispose();
  });

  test('detected pulses start a run and then append splits', () async {
    final controller = MotionDetectionController(repository: LocalRepository());
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.ingestDetectedPulse(
      const MotionTriggerEvent(
        triggerMicros: 5000000,
        score: 0.03,
        type: MotionTriggerType.split,
        splitIndex: 1,
      ),
    );
    controller.ingestDetectedPulse(
      const MotionTriggerEvent(
        triggerMicros: 5600000,
        score: 0.04,
        type: MotionTriggerType.split,
        splitIndex: 2,
      ),
    );

    expect(controller.isRunActive, isTrue);
    expect(controller.runSnapshot.startedAtMicros, 5000000);
    expect(controller.currentSplitMicros, <int>[600000]);

    controller.dispose();
  });

  test('applyTimeline hydrates authoritative timer state directly', () async {
    final controller = MotionDetectionController(repository: LocalRepository());
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.applyTimeline(
      const SessionRaceTimeline(
        startedAtEpochMs: 7000,
        splitMicros: <int>[250000, 500000],
        stopElapsedMicros: 900000,
        revision: 3,
      ),
    );

    expect(controller.isRunActive, isFalse);
    expect(controller.runStatusLabel, 'stopped');
    expect(controller.currentSplitMicros, <int>[250000, 500000, 900000]);
    expect(
      controller.triggerHistory.map((event) => event.type),
      <MotionTriggerType>[
        MotionTriggerType.stop,
        MotionTriggerType.split,
        MotionTriggerType.split,
        MotionTriggerType.start,
      ],
    );

    controller.dispose();
  });
}
