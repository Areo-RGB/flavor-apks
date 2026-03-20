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
  int _streamFrameCount = 0;
  int _processedFrameCount = 0;
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
  String get runStatusLabel {
    if (_runSnapshot.isActive) {
      return 'running';
    }
    if (_runSnapshot.startedAtMicros != null) {
      return 'stopped';
    }
    return 'ready';
  }
  bool get isStreaming => _isStreaming;
  int get streamFrameCount => _streamFrameCount; int get processedFrameCount => _processedFrameCount;
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
    final controller = _cameraController;
    if (controller == null || _isStreaming) {
      return;
    }
    if (controller.value.isStreamingImages) {
      _isStreaming = true;
      notifyListeners();
      return;
    }
    _errorText = null;
    _latestStats = null;
    _previousYPlane = null;
    _frameCounter = 0;
    _streamFrameCount = 0;
    _processedFrameCount = 0;
    _engine.updateConfig(_config);
    try {
      await controller.startImageStream(_processImage);
      _isStreaming = controller.value.isStreamingImages;
    } catch (error) {
      _isStreaming = false;
      _errorText = 'Start detection failed: $error';
    }
    notifyListeners();
  }
  Future<void> stopDetection() async {
    try {
      if (_cameraController?.value.isStreamingImages == true) {
        await _cameraController?.stopImageStream();
      }
    } catch (error) {
      _errorText = 'Stop detection failed: $error';
    }
    _isStreaming = _cameraController?.value.isStreamingImages == true;
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
    _config = _config.copyWith(threshold: value.clamp(0.001, 0.08));
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
    _streamFrameCount += 1;
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
      if (previousBytes == null) {
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
      _processedFrameCount += 1;
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
    if (forwardToSync && _onTrigger != null) {
      final snapshotBefore = _runSnapshot;
      _onTrigger!(trigger);
      if (!identical(snapshotBefore, _runSnapshot)) {
        return;
      }
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
    if (trigger.type == MotionTriggerType.split) {
      _runSnapshot = _runSnapshot.copyWith(
        elapsedMicros: elapsedMicros,
        splitMicros: <int>[..._runSnapshot.splitMicros, elapsedMicros],
      );
      unawaited(_persistCurrentRun());
      notifyListeners();
      return;
    }
    if (trigger.type == MotionTriggerType.stop) {
      _runSnapshot = _runSnapshot.copyWith(
        isActive: false,
        elapsedMicros: elapsedMicros,
        splitMicros: <int>[..._runSnapshot.splitMicros, elapsedMicros],
      );
      _stopRunTicker();
    }
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
  final verticalCenter = (width * roiCenterX).round();
  final verticalHalf = (width * roiWidth / 2).round();
  final verticalStart = (verticalCenter - verticalHalf).clamp(0, width - 1);
  final verticalEnd = (verticalCenter + verticalHalf).clamp(0, width - 1);
  final horizontalCenter = (height * roiCenterX).round();
  final horizontalHalf = (height * roiWidth / 2).round();
  final horizontalStart = (horizontalCenter - horizontalHalf).clamp(
    0,
    height - 1,
  );
  final horizontalEnd = (horizontalCenter + horizontalHalf).clamp(
    0,
    height - 1,
  );
  final verticalScore = _averageNormalizedAbsDelta(
    current: current,
    previous: previous,
    width: width,
    height: height,
    bytesPerRow: bytesPerRow,
    startPrimary: verticalStart,
    endPrimary: verticalEnd,
    primaryIsXAxis: true,
  );
  final horizontalScore = _averageNormalizedAbsDelta(
    current: current,
    previous: previous,
    width: width,
    height: height,
    bytesPerRow: bytesPerRow,
    startPrimary: horizontalStart,
    endPrimary: horizontalEnd,
    primaryIsXAxis: false,
  );
  return math.max(verticalScore, horizontalScore);
}
double _averageNormalizedAbsDelta({
  required Uint8List current,
  required Uint8List previous,
  required int width,
  required int height,
  required int bytesPerRow,
  required num startPrimary,
  required num endPrimary,
  required bool primaryIsXAxis,
}) {
  int sumDiff = 0;
  int samples = 0;
  for (int y = 0; y < height; y += 2) {
    final rowBase = y * bytesPerRow;
    for (int x = 0; x < width; x += 2) {
      final primary = primaryIsXAxis ? x : y;
      if (primary < startPrimary || primary > endPrimary) {
        continue;
      }
      final index = rowBase + x;
      if (index >= current.length || index >= previous.length) continue;
      sumDiff += (current[index] - previous[index]).abs();
      samples += 1;
    }
  }
  if (samples == 0) return 0;
  return (sumDiff / samples) / 255.0;
}
