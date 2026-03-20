import 'package:flutter/material.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/nearby_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';
import 'package:sprint_sync/features/race_sync/race_sync_controller.dart';
import 'package:sprint_sync/features/race_sync/race_sync_screen.dart';

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
  late final LocalRepository _repository;
  late final NearbyBridge _nearbyBridge;
  late final RaceSyncController _raceSyncController;
  late final MotionDetectionController _motionDetectionController;

  @override
  void initState() {
    super.initState();
    _repository = LocalRepository();
    _nearbyBridge = NearbyBridge();
    _motionDetectionController = MotionDetectionController(
      repository: _repository,
      onTrigger: _handleMotionTrigger,
    );
    _raceSyncController = RaceSyncController(
      repository: _repository,
      nearbyBridge: _nearbyBridge,
      onRemoteTrigger: _handleRemoteTrigger,
    );
  }

  void _handleMotionTrigger(MotionTriggerEvent event) {
    _raceSyncController.onMotionTrigger(event);
  }

  void _handleRemoteTrigger(MotionTriggerEvent event) {
    _motionDetectionController.ingestTrigger(event, forwardToSync: false);
  }

  @override
  void dispose() {
    _motionDetectionController.dispose();
    _raceSyncController.dispose();
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
      home: DefaultTabController(
        length: 2,
        child: Scaffold(
          appBar: AppBar(
            title: const Text('Sprint Sync'),
            bottom: const TabBar(
              tabs: [
                Tab(icon: Icon(Icons.camera_alt), text: 'Motion'),
                Tab(icon: Icon(Icons.bluetooth), text: 'Race Sync'),
              ],
            ),
          ),
          body: TabBarView(
            children: [
              MotionDetectionScreen(controller: _motionDetectionController),
              RaceSyncScreen(controller: _raceSyncController),
            ],
          ),
        ),
      ),
    );
  }
}
