import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:newrelic_mobile/config.dart';
import 'package:newrelic_mobile/newrelic_mobile.dart';
import 'package:newrelic_mobile/newrelic_navigation_observer.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: '.env', isOptional: true);

  var appToken = '';
  if (Platform.isIOS) {
    appToken = dotenv.env['NEW_RELIC_IOS_TOKEN'] ?? '';
  } else if (Platform.isAndroid) {
    appToken = dotenv.env['NEW_RELIC_ANDROID_TOKEN'] ?? '';
  }

  if (appToken.isEmpty) {
    runApp(const SprintSyncApp());
    return;
  }

  final config = Config(
    accessToken: appToken,
    analyticsEventEnabled: true,
    webViewInstrumentation: true,
    networkErrorRequestEnabled: true,
    networkRequestEnabled: true,
    crashReportingEnabled: true,
    interactionTracingEnabled: true,
    httpResponseBodyCaptureEnabled: true,
    loggingEnabled: true,
    printStatementAsEventsEnabled: true,
    httpInstrumentationEnabled: true,
  );

  NewrelicMobile.instance.start(config, () {
    runApp(const SprintSyncApp(enableNewRelic: true));
  });
}

class SprintSyncApp extends StatefulWidget {
  const SprintSyncApp({super.key, this.enableNewRelic = false});

  final bool enableNewRelic;

  @override
  State<SprintSyncApp> createState() => _SprintSyncAppState();
}

class _SprintSyncAppState extends State<SprintSyncApp> {
  late final MotionDetectionController _motionDetectionController;
  late final RaceSessionController _raceSessionController;

  @override
  void initState() {
    super.initState();
    final repository = LocalRepository();
    final nearbyBridge = NearbyBridge();
    final nativeSensorBridge = NativeSensorBridge();
    RaceSessionController? sessionController;
    _motionDetectionController = MotionDetectionController(
      repository: repository,
      nativeSensorBridge: nativeSensorBridge,
      onTrigger: (event) {
        sessionController?.onLocalMotionPulse(event);
      },
    );
    _raceSessionController = RaceSessionController(
      nearbyBridge: nearbyBridge,
      motionController: _motionDetectionController,
    );
    sessionController = _raceSessionController;
  }

  @override
  void dispose() {
    _motionDetectionController.dispose();
    _raceSessionController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Sprint Sync',
      navigatorObservers: widget.enableNewRelic
          ? [NewRelicNavigationObserver()]
          : const <NavigatorObserver>[],
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF005A8D)),
        useMaterial3: true,
      ),
      home: RaceSessionScreen(
        controller: _raceSessionController,
        motionController: _motionDetectionController,
      ),
    );
  }
}
