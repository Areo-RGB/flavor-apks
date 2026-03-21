import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';

void main() {
  testWidgets('default stopwatch shows ready status and 0.00s timer', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: _FakeNativeSensorBridge(),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MotionDetectionScreen(
            controller: controller,
            showPreview: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey<String>('run_status_text')),
      findsOneWidget,
    );
    expect(find.text('Status: ready'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('stopwatch_timer_text')),
      findsOneWidget,
    );
    expect(find.text('0.00s'), findsOneWidget);

    controller.dispose();
  });

  testWidgets('finish row renders stopwatch-formatted nanos value', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: _FakeNativeSensorBridge(),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MotionDetectionScreen(
            controller: controller,
            showPreview: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 1000000000,
        score: 0.21,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerSensorNanos: 1750000000,
        score: 0.22,
        type: MotionTriggerType.stop,
        splitIndex: 0,
      ),
      forwardToSync: false,
    );
    await tester.pump(const Duration(milliseconds: 10));

    expect(
      find.byKey(const ValueKey<String>('current_split_1')),
      findsOneWidget,
    );
    final currentSplit = tester.widget<Text>(
      find.byKey(const ValueKey<String>('current_split_1')),
    );
    expect(currentSplit.data, 'Finish: 0.75s');

    controller.dispose();
  });

  testWidgets('threshold slider updates motion config', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: _FakeNativeSensorBridge(),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MotionDetectionScreen(
            controller: controller,
            showPreview: false,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.scrollUntilVisible(find.byType(ExpansionTile), 200);
    await tester.tap(find.byType(ExpansionTile));
    await tester.pumpAndSettle();

    final before = controller.config.threshold;
    final thresholdSlider = tester.widget<Slider>(
      find.byKey(const ValueKey<String>('threshold_slider')),
    );
    thresholdSlider.onChanged?.call(0.12);
    await tester.pumpAndSettle();

    expect(controller.config.threshold, isNot(before));
    controller.dispose();
  });

  testWidgets(
    'native preview notice is shown only when preview mode is enabled',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final controller = MotionDetectionController(
        repository: LocalRepository(),
        nativeSensorBridge: _FakeNativeSensorBridge(),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: MotionDetectionScreen(
              controller: controller,
              showPreview: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.text('Camera preview is disabled in native monitoring mode.'),
        findsOneWidget,
      );

      controller.dispose();
    },
  );
}

class _FakeNativeSensorBridge extends NativeSensorBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();

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
  Future<void> resetNativeRun() async {}
}
