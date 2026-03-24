import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/core/services/wake_lock_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';
import 'package:sprint_sync/features/race_session/race_session_screen.dart';

void main() {
  setUpAll(_setUpPlatformViewsMock);
  tearDownAll(_clearPlatformViewsMock);

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

  testWidgets('permissions button is hidden when all permissions are granted', (
    tester,
  ) async {
    final fixture = _ScreenFixture.create(initialPermissionsGranted: true);

    await tester.pumpWidget(
      MaterialApp(
        home: RaceSessionScreen(
          controller: fixture.controller,
          motionController: fixture.motionController,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 20));

    expect(
      find.byKey(const ValueKey<String>('permissions_button')),
      findsNothing,
    );

    fixture.dispose();
  });

  testWidgets('permissions button is shown when permissions are denied', (
    tester,
  ) async {
    final fixture = _ScreenFixture.create(initialPermissionsGranted: false);

    await tester.pumpWidget(
      MaterialApp(
        home: RaceSessionScreen(
          controller: fixture.controller,
          motionController: fixture.motionController,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 20));

    expect(
      find.byKey(const ValueKey<String>('permissions_button')),
      findsOneWidget,
    );

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

  testWidgets('lobby row shows camera toggle next to role control', (
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
    await tester.pump(const Duration(milliseconds: 20));
    await tester.tap(find.text('Next'));
    await tester.pump(const Duration(milliseconds: 20));

    expect(
      find.byKey(const ValueKey<String>('camera_facing_toggle_local-device')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('camera_facing_toggle_peer-1')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('camera_facing_front_local-device')),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('camera_facing_front_local-device')),
    );
    await tester.pump(const Duration(milliseconds: 20));

    final localDevice = fixture.controller.devices.firstWhere(
      (device) => device.id == 'local-device',
    );
    expect(localDevice.cameraFacing, SessionCameraFacing.front);

    fixture.dispose();
  });

  testWidgets('monitoring stage shows preview marker overlay', (tester) async {
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
    await tester.pump(const Duration(milliseconds: 20));

    await tester.tap(find.text('Next'));
    await tester.pump(const Duration(milliseconds: 20));

    fixture.controller.assignRole('local-device', SessionDeviceRole.start);
    fixture.controller.assignRole('peer-1', SessionDeviceRole.stop);
    await tester.pump(const Duration(milliseconds: 20));

    await tester.tap(find.text('Start Monitoring'));
    await tester.pump(const Duration(milliseconds: 120));

    expect(find.text('Monitoring'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('preview_tripwire_line')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('monitoring_connection_info')),
      findsOneWidget,
    );
    expect(
      find.text('Connection: Nearby (auto BT/Wi-Fi Direct)'),
      findsOneWidget,
    );
    expect(find.text('Sync: - · Latency: -'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('monitoring_preview_toggle')),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey<String>('monitoring_preview_toggle')),
    );
    await tester.pump(const Duration(milliseconds: 20));
    expect(
      find.byKey(const ValueKey<String>('preview_tripwire_line')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('camera_status_text')),
      findsOneWidget,
    );

    fixture.dispose();
  });

  testWidgets(
    'monitoring shows warning banner when client clock lock is invalid',
    (tester) async {
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

      await fixture.controller.joinLobby();
      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'connection_result',
        'endpointId': 'host-1',
        'connected': true,
      });
      await tester.pump(const Duration(milliseconds: 20));

      fixture.bridge.emitEvent(<String, dynamic>{
        'type': 'payload_received',
        'endpointId': 'host-1',
        'message': SessionSnapshotMessage(
          stage: SessionStage.monitoring,
          monitoringActive: true,
          devices: const <SessionDevice>[
            SessionDevice(
              id: 'local-device',
              name: 'Client',
              role: SessionDeviceRole.start,
              isLocal: false,
            ),
            SessionDevice(
              id: 'host-1',
              name: 'Host',
              role: SessionDeviceRole.stop,
              isLocal: false,
            ),
          ],
          timeline: SessionRaceTimeline.idle(),
          hostSensorMinusElapsedNanos: 120000000,
          selfDeviceId: 'local-device',
        ).toJsonString(),
      });
      await tester.pump(const Duration(milliseconds: 20));

      expect(
        find.byKey(const ValueKey<String>('clock_lock_warning_banner')),
        findsOneWidget,
      );
      expect(
        find.textContaining('Triggers from this device are being dropped'),
        findsOneWidget,
      );

      fixture.dispose();
    },
  );
}

class _ScreenFixture {
  _ScreenFixture({
    required this.bridge,
    required this.nativeBridge,
    required this.wakeLockBridge,
    required this.motionController,
    required this.controller,
  });

  final _FakeNearbyBridge bridge;
  final _FakeNativeSensorBridge nativeBridge;
  final _NoopWakeLockBridge wakeLockBridge;
  final MotionDetectionController motionController;
  final RaceSessionController controller;

  factory _ScreenFixture.create({bool initialPermissionsGranted = true}) {
    final bridge = _FakeNearbyBridge(
      initialPermissionsGranted: initialPermissionsGranted,
    );
    final nativeBridge = _FakeNativeSensorBridge();
    final wakeLockBridge = _NoopWakeLockBridge();
    final motionController = MotionDetectionController(
      repository: LocalRepository(),
      nativeSensorBridge: nativeBridge,
    );
    final controller = RaceSessionController(
      nearbyBridge: bridge,
      motionController: motionController,
      startMonitoringAction: () async {},
      stopMonitoringAction: () async {},
      wakeLockBridge: wakeLockBridge,
    );
    return _ScreenFixture(
      bridge: bridge,
      nativeBridge: nativeBridge,
      wakeLockBridge: wakeLockBridge,
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

class _NoopWakeLockBridge extends WakeLockBridge {
  @override
  Future<void> enable() async {}

  @override
  Future<void> disable() async {}

  @override
  Future<void> toggle({required bool enable}) async {}
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
  _FakeNearbyBridge({required bool initialPermissionsGranted})
    : _permissionsGranted = initialPermissionsGranted;

  final StreamController<Map<String, dynamic>> _eventsController =
      StreamController<Map<String, dynamic>>.broadcast();
  bool _permissionsGranted;

  @override
  Stream<Map<String, dynamic>> get events => _eventsController.stream;

  void emitEvent(Map<String, dynamic> event) {
    _eventsController.add(event);
  }

  @override
  Future<Map<String, dynamic>> requestPermissions() async {
    _permissionsGranted = true;
    return <String, dynamic>{'granted': true, 'denied': <String>[]};
  }

  @override
  Future<Map<String, dynamic>> getPermissionStatus() async {
    return <String, dynamic>{
      'granted': _permissionsGranted,
      'denied': _permissionsGranted
          ? <String>[]
          : <String>['android.permission.CAMERA'],
    };
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
