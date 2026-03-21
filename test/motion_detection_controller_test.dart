import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('start trigger initializes run and persists nanos timeline', () async {
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: _FakeNativeSensorBridge(),
    );
    await Future<void>.delayed(const Duration(milliseconds: 5));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 1000000000,
        score: 0.20,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );

    expect(controller.isRunActive, isTrue);
    expect(controller.runStatusLabel, 'running');
    expect(controller.currentSplitElapsedNanos, isEmpty);
    expect(controller.elapsedDisplay, '0.00s');

    await Future<void>.delayed(const Duration(milliseconds: 5));
    final savedRun = await LocalRepository().loadLastRun();
    expect(savedRun, isNotNull);
    expect(savedRun!.startedSensorNanos, 1000000000);

    controller.dispose();
  });

  test('stop trigger freezes elapsed nanos and persists finish', () async {
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: _FakeNativeSensorBridge(),
    );
    await Future<void>.delayed(const Duration(milliseconds: 5));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 2000000000,
        score: 0.22,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 2750000000,
        score: 0.24,
        type: MotionTriggerType.stop,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );

    expect(controller.isRunActive, isFalse);
    expect(controller.runStatusLabel, 'stopped');
    expect(controller.currentSplitElapsedNanos, <int>[750000000]);
    expect(controller.runSnapshot.elapsedNanos, 750000000);

    await Future<void>.delayed(const Duration(milliseconds: 5));
    final savedRun = await LocalRepository().loadLastRun();
    expect(savedRun, isNotNull);
    expect(savedRun!.startedSensorNanos, 2000000000);
    expect(savedRun.splitElapsedNanos, <int>[750000000]);

    controller.dispose();
  });

  test(
    'split trigger appends intermediate marks while run is active',
    () async {
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: _FakeNativeSensorBridge(),
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerSensorNanos: 1000000000,
          score: 0.20,
          type: MotionTriggerType.start,
          splitIndex: 0,
        ),
        forwardToSync: false,
      );
      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerSensorNanos: 1300000000,
          score: 0.21,
          type: MotionTriggerType.split,
          splitIndex: 1,
        ),
        forwardToSync: false,
      );
      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerSensorNanos: 1650000000,
          score: 0.22,
          type: MotionTriggerType.split,
          splitIndex: 2,
        ),
        forwardToSync: false,
      );

      expect(controller.isRunActive, isTrue);
      expect(controller.currentSplitElapsedNanos, <int>[300000000, 650000000]);

      controller.dispose();
    },
  );

  test('manual reset clears active run, splits, and trigger history', () async {
    final bridge = _FakeNativeSensorBridge();
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: bridge,
    );
    await Future<void>.delayed(const Duration(milliseconds: 5));

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 1000000000,
        score: 0.20,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 1500000000,
        score: 0.21,
        type: MotionTriggerType.stop,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );
    expect(controller.triggerHistory, isNotEmpty);
    expect(controller.currentSplitElapsedNanos, isNotEmpty);

    controller.resetRace();

    expect(controller.isRunActive, isFalse);
    expect(controller.runStatusLabel, 'ready');
    expect(controller.elapsedDisplay, '0.00s');
    expect(controller.currentSplitElapsedNanos, isEmpty);
    expect(controller.triggerHistory, isEmpty);
    expect(bridge.resetCalls, 1);

    controller.dispose();
  });

  test('loads latest saved nanos run on startup', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'last_run_result_v2_nanos': jsonEncode({
        'startedSensorNanos': 1234500000,
        'splitElapsedNanos': <int>[100000000, 250000000],
      }),
    });

    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: _FakeNativeSensorBridge(),
    );
    await Future<void>.delayed(const Duration(milliseconds: 20));

    expect(controller.lastRun, isNotNull);
    expect(controller.lastRun!.startedSensorNanos, 1234500000);
    expect(controller.lastRun!.splitElapsedNanos, <int>[100000000, 250000000]);

    controller.dispose();
  });

  test(
    'onTrigger callback that starts run prevents duplicate split at time 0',
    () async {
      late MotionDetectionController controller;
      controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: _FakeNativeSensorBridge(),
        onTrigger: (event) {
          controller.ingestTrigger(
            MotionTriggerEvent(
              triggerSensorNanos: event.triggerSensorNanos,
              score: 0,
              type: MotionTriggerType.start,
              splitIndex: 0,
            ),
            forwardToSync: false,
          );
        },
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      controller.ingestTrigger(
        const MotionTriggerEvent(
          triggerSensorNanos: 5000000000,
          score: 0.03,
          type: MotionTriggerType.split,
          splitIndex: 1,
        ),
      );

      expect(controller.isRunActive, isTrue);
      expect(controller.runSnapshot.startedSensorNanos, 5000000000);
      expect(controller.currentSplitElapsedNanos, isEmpty);

      controller.dispose();
    },
  );
}

class _FakeNativeSensorBridge extends NativeSensorBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();
  int resetCalls = 0;

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

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
  Future<void> resetNativeRun() async {
    resetCalls += 1;
  }
}
