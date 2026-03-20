import 'dart:async';
import 'dart:math' as math;

import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart';
import 'package:sprint_sync/core/models/app_models.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

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

  SessionRaceTimeline _timeline = SessionRaceTimeline.idle();
  LastRunResult? _lastRun;
  Timer? _runTicker;
  int _lastTickElapsedMicros = 0;

  Uint8List? _previousYPlane;

  bool _isInitializing = false;
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

  SessionRaceTimeline get timeline => _timeline;
  MotionRunSnapshot get runSnapshot =>
      _snapshotFromTimeline(_timeline, nowMicros: _nowMicros());
  LastRunResult? get lastRun => _lastRun;
  bool get isRunActive => _timeline.isRunning;
  String get elapsedDisplay => formatDurationMicros(runSnapshot.elapsedMicros);
  List<int> get currentSplitMicros => runSnapshot.splitMicros;

  String get runStatusLabel {
    if (_timeline.isRunning) {
      return 'running';
    }
    if (_timeline.hasStarted) {
      return 'stopped';
    }
    return 'ready';
  }

  bool get isStreaming => _isStreaming;
  int get streamFrameCount => _streamFrameCount;
  int get processedFrameCount => _processedFrameCount;
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
    if (_cameraController?.value.isInitialized == true || _isInitializing) {
      return;
    }

    _isInitializing = true;
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
    } finally {
      _isInitializing = false;
      _isLoading = false;
      notifyListeners();
    }
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
      if (_cameraController?.value.isInitialized == true &&
          _cameraController?.value.isStreamingImages == true) {
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

  void resetRace({int? revision}) {
    _engine.resetRace();
    _applyTimelineState(
      SessionRaceTimeline.idle(revision: revision ?? (_timeline.revision + 1)),
      rebuildHistory: false,
      clearHistory: true,
    );
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
        if (_onTrigger != null) {
          _onTrigger(trigger);
        } else {
          ingestDetectedPulse(trigger);
        }
      }
      notifyListeners();
    } catch (error) {
      _errorText = 'Frame processing failed: $error';
      notifyListeners();
    } finally {
      _isProcessingFrame = false;
    }
  }

  void ingestDetectedPulse(MotionTriggerEvent trigger) {
    final type = _timeline.isRunning
        ? MotionTriggerType.split
        : MotionTriggerType.start;
    ingestTrigger(
      MotionTriggerEvent(
        triggerMicros: trigger.triggerMicros,
        score: trigger.score,
        type: type,
        splitIndex: type == MotionTriggerType.split
            ? _timeline.splitMicros.length + 1
            : 0,
      ),
    );
  }

  void ingestTrigger(MotionTriggerEvent trigger) {
    final nextTimeline = _timelineForTrigger(_timeline, trigger);
    if (_isSameTimeline(_timeline, nextTimeline)) {
      return;
    }
    _addTriggerToHistory(trigger);
    _applyTimelineState(nextTimeline, rebuildHistory: false);
  }

  void applyTimeline(SessionRaceTimeline timeline) {
    _applyTimelineState(timeline, rebuildHistory: true);
  }

  void _addTriggerToHistory(MotionTriggerEvent trigger) {
    _triggerHistory.insert(0, trigger);
    if (_triggerHistory.length > 20) {
      _triggerHistory.removeLast();
    }
  }

  void _startRunTicker() {
    if (_runTicker != null && _timeline.isRunning) {
      return;
    }
    _runTicker?.cancel();
    _lastTickElapsedMicros = _timeline.elapsedMicrosAt(_nowMicros());
    _runTicker = Timer.periodic(const Duration(milliseconds: 33), (_) {
      if (!_timeline.isRunning) {
        return;
      }
      final elapsedMicros = _timeline.elapsedMicrosAt(_nowMicros());
      if (elapsedMicros == _lastTickElapsedMicros) {
        return;
      }
      _lastTickElapsedMicros = elapsedMicros;
      notifyListeners();
    });
  }

  void _stopRunTicker() {
    _runTicker?.cancel();
    _runTicker = null;
    _lastTickElapsedMicros = _timeline.elapsedMicrosAt(_nowMicros());
  }

  Future<void> _persistCurrentRun() async {
    final startedAtEpochMs = _timeline.startedAtEpochMs;
    if (startedAtEpochMs == null) {
      return;
    }
    final run = LastRunResult(
      startedAtEpochMs: startedAtEpochMs,
      splitMicros: List<int>.from(_timeline.displaySplitMicros),
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

  void _applyTimelineState(
    SessionRaceTimeline timeline, {
    required bool rebuildHistory,
    bool clearHistory = false,
  }) {
    final shouldNotify =
        !_isSameTimeline(_timeline, timeline) ||
        (clearHistory && _triggerHistory.isNotEmpty);
    if (!shouldNotify) {
      _syncTickerWithTimeline();
      return;
    }
    _timeline = timeline;
    if (clearHistory) {
      _triggerHistory.clear();
    }
    if (rebuildHistory) {
      _rebuildTriggerHistoryFromTimeline(timeline);
    }
    _syncTickerWithTimeline();
    unawaited(_persistCurrentRun());
    notifyListeners();
  }

  void _syncTickerWithTimeline() {
    _lastTickElapsedMicros = _timeline.elapsedMicrosAt(_nowMicros());
    if (_timeline.isRunning) {
      _startRunTicker();
      return;
    }
    _stopRunTicker();
  }

  void _rebuildTriggerHistoryFromTimeline(SessionRaceTimeline timeline) {
    _triggerHistory.clear();
    final startedAtEpochMs = timeline.startedAtEpochMs;
    if (startedAtEpochMs == null) {
      return;
    }
    final startedAtMicros = startedAtEpochMs * 1000;
    _addTriggerToHistory(
      MotionTriggerEvent(
        triggerMicros: startedAtMicros,
        score: 0,
        type: MotionTriggerType.start,
        splitIndex: 0,
      ),
    );
    for (int i = 0; i < timeline.splitMicros.length; i += 1) {
      _addTriggerToHistory(
        MotionTriggerEvent(
          triggerMicros: startedAtMicros + timeline.splitMicros[i],
          score: 0,
          type: MotionTriggerType.split,
          splitIndex: i + 1,
        ),
      );
    }
    final stoppedAt = timeline.stopElapsedMicros;
    if (stoppedAt == null) {
      return;
    }
    _addTriggerToHistory(
      MotionTriggerEvent(
        triggerMicros: startedAtMicros + stoppedAt,
        score: 0,
        type: MotionTriggerType.stop,
        splitIndex: 0,
      ),
    );
  }

  SessionRaceTimeline _timelineForTrigger(
    SessionRaceTimeline timeline,
    MotionTriggerEvent trigger,
  ) {
    if (trigger.type == MotionTriggerType.start) {
      return SessionRaceTimeline(
        startedAtEpochMs: trigger.triggerMicros ~/ 1000,
        splitMicros: const <int>[],
        revision: timeline.revision + 1,
      );
    }

    final startedAtEpochMs = timeline.startedAtEpochMs;
    if (!timeline.isRunning || startedAtEpochMs == null) {
      return timeline;
    }

    final elapsedMicros = math.max(
      0,
      trigger.triggerMicros - (startedAtEpochMs * 1000),
    );
    if (trigger.type == MotionTriggerType.split) {
      return timeline.copyWith(
        splitMicros: <int>[...timeline.splitMicros, elapsedMicros],
        revision: timeline.revision + 1,
      );
    }
    if (trigger.type == MotionTriggerType.stop) {
      return timeline.copyWith(
        stopElapsedMicros: elapsedMicros,
        revision: timeline.revision + 1,
      );
    }
    return timeline;
  }

  bool _isSameTimeline(SessionRaceTimeline left, SessionRaceTimeline right) {
    return left.startedAtEpochMs == right.startedAtEpochMs &&
        left.stopElapsedMicros == right.stopElapsedMicros &&
        left.revision == right.revision &&
        listEquals(left.splitMicros, right.splitMicros);
  }

  MotionRunSnapshot _snapshotFromTimeline(
    SessionRaceTimeline timeline, {
    required int nowMicros,
  }) {
    final startedAtEpochMs = timeline.startedAtEpochMs;
    if (startedAtEpochMs == null) {
      return MotionRunSnapshot.ready();
    }
    return MotionRunSnapshot(
      isActive: timeline.isRunning,
      startedAtMicros: startedAtEpochMs * 1000,
      elapsedMicros: timeline.elapsedMicrosAt(nowMicros),
      splitMicros: timeline.displaySplitMicros,
    );
  }

  int _nowMicros() => DateTime.now().microsecondsSinceEpoch;
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
