import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';

void main() {
  testWidgets('default stopwatch shows ready status and 0.00s timer', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final controller = MotionDetectionController(repository: LocalRepository());

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

  testWidgets('split rows render stopwatch-formatted values', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final controller = MotionDetectionController(repository: LocalRepository());

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
        triggerMicros: 1000000,
        score: 0.21,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );
    controller.ingestTrigger(
      const MotionTriggerEvent(
        triggerMicros: 1750000,
        score: 0.22,
        type: MotionTriggerType.split,
        splitIndex: 1,
      ),
    );
    await tester.pump(const Duration(milliseconds: 10));

    expect(
      find.byKey(const ValueKey<String>('current_split_1')),
      findsOneWidget,
    );
    final currentSplit = tester.widget<Text>(
      find.byKey(const ValueKey<String>('current_split_1')),
    );
    expect(currentSplit.data, 'Split 1: 0.75s');

    controller.dispose();
  });

  testWidgets('threshold slider updates motion config', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final controller = MotionDetectionController(repository: LocalRepository());

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

  testWidgets('latest saved run renders in last run section', (tester) async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'last_run_result_v1': jsonEncode({
        'startedAtEpochMs': 2000,
        'splitMicros': <int>[500000],
      }),
    });
    final controller = MotionDetectionController(repository: LocalRepository());

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
    await tester.pump(const Duration(milliseconds: 20));

    expect(find.byKey(const ValueKey<String>('saved_split_1')), findsOneWidget);
    final savedSplit = tester.widget<Text>(
      find.byKey(const ValueKey<String>('saved_split_1')),
    );
    expect(savedSplit.data, 'Split 1: 0.50s');

    controller.dispose();
  });
}
