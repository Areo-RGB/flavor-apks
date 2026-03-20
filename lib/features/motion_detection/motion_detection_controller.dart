import 'dart:async';
import 'dart:math' as math;

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:sprint_sync/core/models/app_models.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

class MotionDetectionController extends ChangeNotifier {
  MotionDetectionController({
    required LocalRepository repository,
    void Function(MotionTriggerEvent event)? onTrigger,
  }) : _repository = repository,
       _onTrigger = onTrigger {
    _engine = MotionDetectionEngine(config: _config);
    unawaited(_loadInitialState());
  }

  final LocalRepository _repository;
  final void Function(MotionTriggerEvent event)? _onTrigger;

  MotionDetectionConfig _config = MotionDetectionConfig.defaults();
  late MotionDetectionEngine _engine;

  CameraController? _cameraController;
  MotionFrameStats? _latestStats;
  final List<MotionTriggerEvent> _triggerHistory = <MotionTriggerEvent>[];
  MotionRunSnapshot _runSnapshot = MotionRunSnapshot.ready();
  LastRunResult? _lastRun;
  Timer? _runTicker;

  Uint8List? _previousYPlane;
  bool _isStreaming = false;
  bool _isProcessingFrame = false;
  bool _isLoading = false;
  int _frameCounter = 0;
  String? _errorText;
  bool _isDisposed = false;

  MotionDetectionConfig get config => _config;
  CameraController? get cameraController => _cameraController;
  MotionFrameStats? get latestStats => _latestStats;
  List<MotionTriggerEvent> get triggerHistory =>
      List.unmodifiable(_triggerHistory);
  MotionRunSnapshot get runSnapshot => _runSnapshot;
  LastRunResult? get lastRun => _lastRun;
  bool get isRunActive => _runSnapshot.isActive;
  String get elapsedDisplay => formatDurationMicros(_runSnapshot.elapsedMicros);
  List<int> get currentSplitMicros =>
      List.unmodifiable(_runSnapshot.splitMicros);
  String get runStatusLabel => _runSnapshot.isActive ? 'running' : 'ready';
  bool get isStreaming => _isStreaming;
  bool get isLoading => _isLoading;
  String? get errorText => _errorText;

  Future<void> _loadInitialState() async {
    final loadedConfig = await _repository.loadMotionConfig();
    final loadedRun = await _repository.loadLastRun();
    _config = loadedConfig;
    _lastRun = loadedRun;
    _engine.updateConfig(_config);
    if (!_isDisposed) {
      notifyListeners();
    }
  }

  Future<void> initializeCamera() async {
    if (_cameraController?.value.isInitialized == true) {
      return;
    }
    _isLoading = true;
    _errorText = null;
    notifyListeners();

    try {
      final cameras = await availableCameras();
      if (cameras.isEmpty) {
        _errorText = 'No camera found on this device.';
      } else {
        final selected = cameras.firstWhere(
          (camera) => camera.lensDirection == CameraLensDirection.back,
          orElse: () => cameras.first,
        );
        final controller = CameraController(
          selected,
          ResolutionPreset.medium,
          enableAudio: false,
          imageFormatGroup: ImageFormatGroup.yuv420,
        );
        await controller.initialize();
        _cameraController = controller;
      }
    } catch (error) {
      _errorText = 'Camera initialization failed: $error';
    }

    _isLoading = false;
    notifyListeners();
  }

  Future<void> disposeCamera() async {
    await stopDetection();
    await _cameraController?.dispose();
    _cameraController = null;
    notifyListeners();
  }

  Future<void> startDetection() async {
    if (_cameraController == null ||
        _cameraController?.value.isInitialized != true) {
      await initializeCamera();
    }
    if (_cameraController == null || _isStreaming) {
      return;
    }

    _errorText = null;
    _previousYPlane = null;
    _frameCounter = 0;
    _engine.updateConfig(_config);
    await _cameraController!.startImageStream(_processImage);
    _isStreaming = true;
    notifyListeners();
  }

  Future<void> stopDetection() async {
    if (_cameraController?.value.isStreamingImages == true) {
      await _cameraController?.stopImageStream();
    }
    _isStreaming = false;
    _isProcessingFrame = false;
    _previousYPlane = null;
    _frameCounter = 0;
    notifyListeners();
  }

  void resetRace() {
    _engine.resetRace();
    _triggerHistory.clear();
    _runSnapshot = MotionRunSnapshot.ready();
    _stopRunTicker();
    notifyListeners();
  }

  Future<void> updateThreshold(double value) async {
    _config = _config.copyWith(threshold: value.clamp(0.02, 0.30));
    _engine.updateConfig(_config);
    await _repository.saveMotionConfig(_config);
    notifyListeners();
  }

  Future<void> updateRoiCenter(double value) async {
    _config = _config.copyWith(roiCenterX: value.clamp(0.20, 0.80));
    _engine.updateConfig(_config);
    await _repository.saveMotionConfig(_config);
    notifyListeners();
  }

  Future<void> updateRoiWidth(double value) async {
    _config = _config.copyWith(roiWidth: value.clamp(0.05, 0.40));
    _engine.updateConfig(_config);
    await _repository.saveMotionConfig(_config);
    notifyListeners();
  }

  Future<void> updateCooldown(int value) async {
    _config = _config.copyWith(cooldownMs: value.clamp(300, 2000));
    _engine.updateConfig(_config);
    await _repository.saveMotionConfig(_config);
    notifyListeners();
  }

  void _processImage(CameraImage image) {
    if (_isProcessingFrame) {
      return;
    }
    _isProcessingFrame = true;

    try {
      _frameCounter += 1;
      if (_frameCounter % _config.processEveryNFrames != 0) {
        return;
      }
      if (image.planes.isEmpty) {
        return;
      }

      final plane = image.planes.first;
      final currentBytes = plane.bytes;
      final previousBytes = _previousYPlane;
      _previousYPlane = Uint8List.fromList(currentBytes);

      if (previousBytes == null ||
          previousBytes.length != currentBytes.length) {
        return;
      }

      final rawScore = _computeNormalizedDelta(
        current: currentBytes,
        previous: previousBytes,
        width: image.width,
        height: image.height,
        bytesPerRow: plane.bytesPerRow,
        roiCenterX: _config.roiCenterX,
        roiWidth: _config.roiWidth,
      );

      final timestampMicros = DateTime.now().microsecondsSinceEpoch;
      final stats = _engine.process(
        rawScore: rawScore,
        timestampMicros: timestampMicros,
      );
      _latestStats = stats;

      final trigger = stats.triggerEvent;
      if (trigger != null) {
        ingestTrigger(trigger);
      }
      notifyListeners();
    } catch (error) {
      _errorText = 'Frame processing failed: $error';
      notifyListeners();
    } finally {
      _isProcessingFrame = false;
    }
  }

  void ingestTrigger(MotionTriggerEvent trigger, {bool forwardToSync = true}) {
    _addTriggerToHistory(trigger);
    if (forwardToSync) {
      _onTrigger?.call(trigger);
    }

    if (trigger.type == MotionTriggerType.start) {
      _runSnapshot = MotionRunSnapshot(
        isActive: true,
        startedAtMicros: trigger.triggerMicros,
        elapsedMicros: 0,
        splitMicros: const <int>[],
      );
      _startRunTicker();
      unawaited(_persistCurrentRun());
      notifyListeners();
      return;
    }

    if (!_runSnapshot.isActive || _runSnapshot.startedAtMicros == null) {
      return;
    }

    final elapsedMicros = math.max(
      0,
      trigger.triggerMicros - _runSnapshot.startedAtMicros!,
    );
    final splitMicros = List<int>.from(_runSnapshot.splitMicros)
      ..add(elapsedMicros);
    _runSnapshot = _runSnapshot.copyWith(
      elapsedMicros: elapsedMicros,
      splitMicros: splitMicros,
    );
    unawaited(_persistCurrentRun());
    notifyListeners();
  }

  void _addTriggerToHistory(MotionTriggerEvent trigger) {
    _triggerHistory.insert(0, trigger);
    if (_triggerHistory.length > 20) {
      _triggerHistory.removeLast();
    }
  }

  void _startRunTicker() {
    _runTicker?.cancel();
    _runTicker = Timer.periodic(const Duration(milliseconds: 50), (_) {
      final startedAtMicros = _runSnapshot.startedAtMicros;
      if (!_runSnapshot.isActive || startedAtMicros == null) {
        return;
      }
      final elapsedMicros =
          DateTime.now().microsecondsSinceEpoch - startedAtMicros;
      final safeElapsed = math.max(0, elapsedMicros);
      if (safeElapsed == _runSnapshot.elapsedMicros) {
        return;
      }
      _runSnapshot = _runSnapshot.copyWith(elapsedMicros: safeElapsed);
      notifyListeners();
    });
  }

  void _stopRunTicker() {
    _runTicker?.cancel();
    _runTicker = null;
  }

  Future<void> _persistCurrentRun() async {
    final startedAtMicros = _runSnapshot.startedAtMicros;
    if (startedAtMicros == null) {
      return;
    }
    final run = LastRunResult(
      startedAtEpochMs: startedAtMicros ~/ 1000,
      splitMicros: List<int>.from(_runSnapshot.splitMicros),
    );
    _lastRun = run;
    if (!_isDisposed) {
      notifyListeners();
    }
    await _repository.saveLastRun(run);
  }

  @override
  void dispose() {
    _isDisposed = true;
    _stopRunTicker();
    _cameraController?.dispose();
    super.dispose();
  }
}

double _computeNormalizedDelta({
  required Uint8List current,
  required Uint8List previous,
  required int width,
  required int height,
  required int bytesPerRow,
  required double roiCenterX,
  required double roiWidth,
}) {
  final centerX = (width * roiCenterX).round();
  final halfWidth = (width * roiWidth / 2).round();
  final startX = (centerX - halfWidth).clamp(0, width - 1);
  final endX = (centerX + halfWidth).clamp(0, width - 1);

  int sumDiff = 0;
  int samples = 0;

  for (int y = 0; y < height; y += 2) {
    final rowBase = y * bytesPerRow;
    for (int x = startX; x <= endX; x += 2) {
      final index = rowBase + x;
      if (index >= current.length || index >= previous.length) {
        continue;
      }
      final diff = (current[index] - previous[index]).abs();
      sumDiff += diff;
      samples += 1;
    }
  }

  if (samples == 0) {
    return 0;
  }
  return (sumDiff / samples) / 255.0;
}
