import 'package:flutter/material.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SprintSyncApp());
}

class SprintSyncApp extends StatefulWidget {
  const SprintSyncApp({super.key});

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
