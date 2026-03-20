import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';
import 'package:sprint_sync/features/race_sync/race_sync_controller.dart';
import 'package:sprint_sync/features/race_sync/race_sync_models.dart';

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

  testWidgets('finish row renders stopwatch-formatted value', (tester) async {
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
        type: MotionTriggerType.stop,
        splitIndex: 0,
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
    expect(currentSplit.data, 'Finish: 0.75s');

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
    expect(savedSplit.data, 'Finish: 0.50s');

    controller.dispose();
  });

  testWidgets('preview overlay tracks tripwire and latency border colors', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    final motionController = MotionDetectionController(
      repository: LocalRepository(),
    );
    final raceController = _StubRaceSyncController();

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MotionDetectionScreen(
            controller: motionController,
            raceSyncController: raceController,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(_previewBorderColor(tester), Colors.grey);
    expect(_tripwireColor(tester), Colors.grey);
    expect(_tripwireAlignmentX(tester), closeTo(0, 0.001));

    await motionController.updateRoiCenter(0.2);
    await tester.pumpAndSettle();
    expect(_tripwireAlignmentX(tester), closeTo(-0.6, 0.001));

    raceController.setConnectionQuality(ConnectionQuality.good);
    await tester.pump();
    expect(_previewBorderColor(tester), Colors.green);
    expect(_tripwireColor(tester), Colors.green);

    raceController.setConnectionQuality(ConnectionQuality.warning);
    await tester.pump();
    expect(_previewBorderColor(tester), Colors.orange);
    expect(_tripwireColor(tester), Colors.orange);

    raceController.setConnectionQuality(ConnectionQuality.bad);
    await tester.pump();
    expect(_previewBorderColor(tester), Colors.red);
    expect(_tripwireColor(tester), Colors.red);

    raceController.setConnectionQuality(ConnectionQuality.offline);
    await tester.pump();
    expect(_previewBorderColor(tester), Colors.grey);
    expect(_tripwireColor(tester), Colors.grey);

    motionController.dispose();
    raceController.dispose();
  });
}

Color _previewBorderColor(WidgetTester tester) {
  final decoratedBox = tester.widget<DecoratedBox>(
    find.byKey(const ValueKey<String>('preview_status_border')),
  );
  final decoration = decoratedBox.decoration as BoxDecoration;
  final border = decoration.border as Border;
  return border.top.color;
}

Color? _tripwireColor(WidgetTester tester) {
  final tripwire = tester.widget<Container>(
    find.byKey(const ValueKey<String>('preview_tripwire_line')),
  );
  return tripwire.color;
}

double _tripwireAlignmentX(WidgetTester tester) {
  final tripwireAlignment = tester.widget<Align>(
    find.byKey(const ValueKey<String>('preview_tripwire_alignment')),
  );
  return (tripwireAlignment.alignment as Alignment).x;
}

class _StubRaceSyncController extends RaceSyncController {
  _StubRaceSyncController()
    : super(repository: LocalRepository(), nearbyBridge: _NoopNearbyBridge());

  ConnectionQuality _connectionQuality = ConnectionQuality.offline;

  @override
  ConnectionQuality get connectionQuality => _connectionQuality;

  void setConnectionQuality(ConnectionQuality quality) {
    _connectionQuality = quality;
    notifyListeners();
  }
}

class _NoopNearbyBridge extends NearbyBridge {
  @override
  Stream<Map<String, dynamic>> get events =>
      const Stream<Map<String, dynamic>>.empty();

  @override
  Future<Map<String, dynamic>> requestPermissions() async {
    return <String, dynamic>{'granted': true, 'denied': <String>[]};
  }

  @override
  Future<void> startHosting({
    required String serviceId,
    required String endpointName,
  }) async {}

  @override
  Future<void> startDiscovery({
    required String serviceId,
    required String endpointName,
  }) async {}

  @override
  Future<void> requestConnection({
    required String endpointId,
    required String endpointName,
  }) async {}

  @override
  Future<void> sendBytes({
    required String endpointId,
    required String messageJson,
  }) async {}

  @override
  Future<void> disconnect({required String endpointId}) async {}

  @override
  Future<void> stopAll() async {}
}
