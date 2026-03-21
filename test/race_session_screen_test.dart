import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_screen.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  testWidgets('setup screen starts with Next disabled', (tester) async {
    final fixture = _ScreenFixture.create();

    await tester.pumpWidget(
      MaterialApp(
        home: RaceSessionScreen(
          controller: fixture.controller,
          motionController: fixture.motionController,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 20));

    expect(find.text('Next'), findsNothing);

    fixture.dispose();
  });

  testWidgets('setup transitions to lobby after two devices are connected', (
    tester,
  ) async {
    final fixture = _ScreenFixture.create();

    await tester.pumpWidget(
      MaterialApp(
        home: RaceSessionScreen(
          controller: fixture.controller,
          motionController: fixture.motionController,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 20));

    await tester.tap(find.text('Host'));
    await tester.pump(const Duration(milliseconds: 20));

    fixture.bridge.emitEvent(<String, dynamic>{
      'type': 'connection_result',
      'endpointId': 'peer-1',
      'connected': true,
    });
    await tester.pump(const Duration(milliseconds: 5));

    expect(find.text('Next'), findsOneWidget);

    await tester.tap(find.text('Next'));
    await tester.pump(const Duration(milliseconds: 20));
    expect(find.text('Race Lobby'), findsOneWidget);

    fixture.dispose();
  });
}

class _ScreenFixture {
  _ScreenFixture({
    required this.bridge,
    required this.nativeBridge,
    required this.motionController,
    required this.controller,
  });

  final _FakeNearbyBridge bridge;
  final _FakeNativeSensorBridge nativeBridge;
  final MotionDetectionController motionController;
  final RaceSessionController controller;

  factory _ScreenFixture.create() {
    final bridge = _FakeNearbyBridge();
    final nativeBridge = _FakeNativeSensorBridge();
    final motionController = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: nativeBridge,
    );
    final controller = RaceSessionController(
      nearbyBridge: bridge,
      motionController: motionController,
      startMonitoringAction: () async {},
      stopMonitoringAction: () async {},
    );
    return _ScreenFixture(
      bridge: bridge,
      nativeBridge: nativeBridge,
      motionController: motionController,
      controller: controller,
    );
  }

  void dispose() {
    controller.dispose();
    motionController.dispose();
    bridge.dispose();
    nativeBridge.dispose();
  }
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

  void dispose() {
    _eventsController.close();
  }
}

class _FakeNearbyBridge extends NearbyBridge {
  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

  void emitEvent(Map<String, dynamic> event) {
    _eventsController.add(event);
  }

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

  void dispose() {
    _eventsController.close();
  }
}
