import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';

void main() {
  setUpAll(_setUpPlatformViewsMock);
  tearDownAll(_clearPlatformViewsMock);

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
    expect(
      find.byKey(const ValueKey<String>('camera_status_text')),
      findsOneWidget,
    );
    expect(find.text('Camera: --.- fps · INIT'), findsOneWidget);

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

  testWidgets('native preview marker is shown and tracks roi center updates', (
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
            showPreview: true,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey<String>('native_preview_card')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('preview_tripwire_line')),
      findsOneWidget,
    );
    final compactPreviewContainer = tester.widget<FractionallySizedBox>(
      find.byType(FractionallySizedBox),
    );
    expect(compactPreviewContainer.widthFactor, closeTo(0.34, 0.001));

    final alignFinder = find.byKey(
      const ValueKey<String>('preview_tripwire_alignment'),
    );
    expect(alignFinder, findsOneWidget);
    final initialAlignment =
        tester.widget<Align>(alignFinder).alignment as Alignment;
    expect(initialAlignment.x, closeTo(0.0, 0.001));

    await controller.updateRoiCenter(0.80);
    await tester.pumpAndSettle();

    final updatedAlignment =
        tester.widget<Align>(alignFinder).alignment as Alignment;
    expect(updatedAlignment.x, closeTo(0.60, 0.001));

    controller.dispose();
  });

  testWidgets('preview card is hidden when preview mode is disabled', (
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
      find.byKey(const ValueKey<String>('native_preview_card')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('preview_tripwire_line')),
      findsNothing,
    );

    controller.dispose();
  });

  testWidgets('camera status updates while preview is hidden', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final bridge = _FakeNativeSensorBridge();
    final controller = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: bridge,
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
    await tester.pump(const Duration(milliseconds: 10));

    expect(
      find.byKey(const ValueKey<String>('camera_status_text')),
      findsOneWidget,
    );
    expect(find.textContaining('fps · NORMAL · target 60'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('native_preview_card')),
      findsNothing,
    );

    controller.dispose();
  });
}

class _FakeNativeSensorBridge extends NativeSensorBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

  void emitEvent(Map<String, dynamic> event) {
    _eventsController.add(event);
  }

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

Future<void> _setUpPlatformViewsMock() async {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform_views, (
        MethodCall call,
      ) async {
        switch (call.method) {
          case 'create':
            return 1;
          case 'dispose':
          case 'resize':
          case 'offset':
          case 'setDirection':
          case 'clearFocus':
          case 'synchronizeToNativeViewHierarchy':
            return null;
          default:
            return null;
        }
      });
}

Future<void> _clearPlatformViewsMock() async {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(SystemChannels.platform_views, null);
}
