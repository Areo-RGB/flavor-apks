import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

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

  test('stop trigger freezes elapsed time and persists final run time', () async {
    final controller = MotionDetectionController(repository: LocalRepository());
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
  });

  test('split trigger appends intermediate marks while run is active', () async {
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
  });

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

  test('onTrigger callback that starts run prevents duplicate split at time 0', () async {
    late MotionDetectionController controller;
    controller = MotionDetectionController(
      repository: LocalRepository(),
      onTrigger: (event) {
        controller.ingestTrigger(
          MotionTriggerEvent(
            triggerMicros: event.triggerMicros,
            score: 0,
            type: MotionTriggerType.start,
            splitIndex: 0,
          ),
          forwardToSync: false,
        );
      },
    );
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 5000000,
        score: 0.03,
        type: MotionTriggerType.split,
        splitIndex: 1,
      ),
    );

    expect(controller.isRunActive, isTrue);
    expect(controller.runSnapshot.startedAtMicros, 5000000);
    expect(controller.currentSplitMicros, isEmpty,
        reason: 'Should not have a spurious split at time 0');

    controller.dispose();
  });

  test('onTrigger callback that adds split prevents duplicate split', () async {
    late MotionDetectionController controller;
    controller = MotionDetectionController(
      repository: LocalRepository(),
      onTrigger: (event) {
        if (!controller.isRunActive) return;
        controller.ingestTrigger(
          MotionTriggerEvent(
            triggerMicros: event.triggerMicros,
            score: 0,
            type: MotionTriggerType.split,
            splitIndex: controller.currentSplitMicros.length + 1,
          ),
          forwardToSync: false,
        );
      },
    );
    await Future<void>.delayed(const Duration(milliseconds: 1));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1000000,
        score: 0.20,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1500000,
        score: 0.03,
        type: MotionTriggerType.split,
        splitIndex: 1,
      ),
    );

    expect(controller.currentSplitMicros, <int>[500000],
        reason: 'Should have exactly one split, not a duplicate');

    controller.dispose();
  });
}
