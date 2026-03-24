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

  test(
    'updateCameraFacing persists and pushes native config while streaming',
    () async {
      final bridge = _FakeNativeSensorBridge();
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: bridge,
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      await controller.startDetection();
      expect(bridge.startConfigs, isNotEmpty);
      expect(bridge.startConfigs.last['cameraFacing'], 'rear');
      expect(bridge.startConfigs.last['highSpeedEnabled'], isFalse);

      await controller.updateCameraFacing(MotionCameraFacing.front);

      expect(controller.config.cameraFacing, MotionCameraFacing.front);
      expect(bridge.updateConfigs, isNotEmpty);
      expect(bridge.updateConfigs.last['cameraFacing'], 'front');
      final savedConfig = await LocalRepository().loadMotionConfig();
      expect(savedConfig.cameraFacing, MotionCameraFacing.front);

      controller.dispose();
    },
  );

  test(
    'updateHighSpeedEnabled persists and pushes native config while streaming',
    () async {
      final bridge = _FakeNativeSensorBridge();
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: bridge,
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      await controller.startDetection();
      expect(bridge.startConfigs, isNotEmpty);
      expect(bridge.startConfigs.last['highSpeedEnabled'], isFalse);

      await controller.updateHighSpeedEnabled(true);

      expect(controller.config.highSpeedEnabled, isTrue);
      expect(bridge.updateConfigs, isNotEmpty);
      expect(bridge.updateConfigs.last['highSpeedEnabled'], isTrue);
      final savedConfig = await LocalRepository().loadMotionConfig();
      expect(savedConfig.highSpeedEnabled, isTrue);

      controller.dispose();
    },
  );

  test(
    'native frame stats update observed FPS and camera mode fields',
    () async {
      final bridge = _FakeNativeSensorBridge();
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: bridge,
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      bridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1000000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.01,
        'streamFrameCount': 1,
        'processedFrameCount': 1,
        'cameraFpsMode': 'normal',
        'targetFpsUpper': 60,
      });
      bridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1016666667,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.01,
        'streamFrameCount': 2,
        'processedFrameCount': 2,
        'cameraFpsMode': 'normal',
        'targetFpsUpper': 60,
      });
      await Future<void>.delayed(const Duration(milliseconds: 5));

      expect(controller.observedFps, isNotNull);
      expect(controller.observedFps!, greaterThan(55));
      expect(controller.observedFps!, lessThan(65));
      expect(controller.cameraFpsMode, 'normal');
      expect(controller.targetFpsUpper, 60);

      controller.dispose();
    },
  );

  test(
    'native frame stats prefers native observed FPS when provided',
    () async {
      final bridge = _FakeNativeSensorBridge();
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: bridge,
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      bridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1000000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.01,
        'streamFrameCount': 1,
        'processedFrameCount': 1,
        'observedFps': 117.3,
        'cameraFpsMode': 'hs120',
        'targetFpsUpper': 120,
      });
      bridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1100000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.01,
        'streamFrameCount': 2,
        'processedFrameCount': 2,
        'observedFps': 119.2,
        'cameraFpsMode': 'hs120',
        'targetFpsUpper': 120,
      });
      await Future<void>.delayed(const Duration(milliseconds: 5));

      expect(controller.observedFps, isNotNull);
      expect(controller.observedFps!, closeTo(119.2, 0.001));
      expect(controller.cameraFpsMode, 'hs120');
      expect(controller.targetFpsUpper, 120);

      controller.dispose();
    },
  );

  test(
    'native frame stats smooths large normal-mode observed FPS swings',
    () async {
      final bridge = _FakeNativeSensorBridge();
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: bridge,
      );
      await Future<void>.delayed(const Duration(milliseconds: 5));

      bridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1000000000,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.01,
        'streamFrameCount': 1,
        'processedFrameCount': 1,
        'observedFps': 60.0,
        'cameraFpsMode': 'normal',
        'targetFpsUpper': 60,
      });
      bridge.emitEvent(<String, dynamic>{
        'type': 'native_frame_stats',
        'frameSensorNanos': 1016666667,
        'rawScore': 0.01,
        'baseline': 0.01,
        'effectiveScore': 0.01,
        'streamFrameCount': 2,
        'processedFrameCount': 2,
        'observedFps': 20.0,
        'cameraFpsMode': 'normal',
        'targetFpsUpper': 60,
      });
      await Future<void>.delayed(const Duration(milliseconds: 5));

      expect(controller.observedFps, isNotNull);
      expect(controller.observedFps!, greaterThan(50.0));
      expect(controller.observedFps!, lessThan(60.0));
      expect(controller.cameraFpsMode, 'normal');
      controller.dispose();
    },
  );
}

class _FakeNativeSensorBridge extends NativeSensorBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();
  final List<Map<String, dynamic>> startConfigs = <Map<String, dynamic>>[];
  final List<Map<String, dynamic>> updateConfigs = <Map<String, dynamic>>[];
  int resetCalls = 0;

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

  void emitEvent(Map<String, dynamic> event) {
    _eventsController.add(event);
  }

  @override
  Future<void> startNativeMonitoring({
    required Map<String, dynamic> config,
  }) async {
    startConfigs.add(Map<String, dynamic>.from(config));
  }

  @override
  Future<void> stopNativeMonitoring() async {}

  @override
  Future<void> updateNativeConfig({
    required Map<String, dynamic> config,
  }) async {
    updateConfigs.add(Map<String, dynamic>.from(config));
  }

  @override
  Future<void> resetNativeRun() async {
    resetCalls += 1;
  }
}
