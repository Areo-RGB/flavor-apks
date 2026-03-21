import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:sprint_sync/core/models/app_models.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';
import 'package:sprint_sync/core/services/native_sensor_bridge.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

class MotionDetectionController extends ChangeNotifier {
  MotionDetectionController({
    required LocalRepository repository,
    NativeSensorBridge? nativeSensorBridge,
    void Function(MotionTriggerEvent event)? onTrigger,
  }) : _repository = repository,
       _nativeSensorBridge = nativeSensorBridge ?? NativeSensorBridge(),
       _onTrigger = onTrigger {
    _nativeEventsSubscription = _nativeSensorBridge.events.listen(
      _onNativeEvent,
    );
    unawaited(_loadInitialState());
  }

  final LocalRepository _repository;
  final NativeSensorBridge _nativeSensorBridge;
  final void Function(MotionTriggerEvent event)? _onTrigger;

  MotionDetectionConfig _config = MotionDetectionConfig.defaults();
  MotionFrameStats? _latestStats;
  final List<MotionTriggerEvent> _triggerHistory = <MotionTriggerEvent>[];
  MotionRunSnapshot _runSnapshot = MotionRunSnapshot.ready();
  LastRunResult? _lastRun;
  StreamSubscription<Map<String, dynamic>>? _nativeEventsSubscription;

  bool _isLoading = false;
  bool _isStreaming = false;
  int _streamFrameCount = 0;
  int _processedFrameCount = 0;
  int? _sensorMinusElapsedNanos;
  String? _errorText;
  bool _isDisposed = false;

  MotionDetectionConfig get config => _config;
  MotionFrameStats? get latestStats => _latestStats;
  List<MotionTriggerEvent> get triggerHistory =>
      List.unmodifiable(_triggerHistory);
  MotionRunSnapshot get runSnapshot => _runSnapshot;
  LastRunResult? get lastRun => _lastRun;
  bool get isRunActive => _runSnapshot.isActive;
  String get elapsedDisplay => formatDurationNanos(_runSnapshot.elapsedNanos);
  List<int> get currentSplitElapsedNanos =>
      List.unmodifiable(_runSnapshot.splitElapsedNanos);
  int? get sensorMinusElapsedNanos => _sensorMinusElapsedNanos;

  String get runStatusLabel {
    if (_runSnapshot.isActive) {
      return 'running';
    }
    if (_runSnapshot.startedSensorNanos != null) {
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
    if (!_isDisposed) {
      notifyListeners();
    }
  }

  Future<void> initializeCamera() async {
    _errorText = null;
    notifyListeners();
  }

  Future<void> disposeCamera() async {
    await stopDetection();
  }

  Future<void> startDetection() async {
    _isLoading = true;
    _errorText = null;
    notifyListeners();
    try {
      await _nativeSensorBridge.startNativeMonitoring(config: _config.toJson());
      _isStreaming = true;
    } catch (error) {
      _isStreaming = false;
      _errorText = 'Start native monitoring failed: $error';
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> stopDetection() async {
    try {
      await _nativeSensorBridge.stopNativeMonitoring();
    } catch (error) {
      _errorText = 'Stop native monitoring failed: $error';
    }
    _isStreaming = false;
    notifyListeners();
  }

  void resetRace() {
    _triggerHistory.clear();
    _runSnapshot = MotionRunSnapshot.ready();
    unawaited(_nativeSensorBridge.resetNativeRun());
    notifyListeners();
  }

  Future<void> updateThreshold(double value) async {
    _config = _config.copyWith(threshold: value.clamp(0.001, 0.08));
    await _repository.saveMotionConfig(_config);
    await _pushNativeConfig();
    notifyListeners();
  }

  Future<void> updateRoiCenter(double value) async {
    _config = _config.copyWith(roiCenterX: value.clamp(0.20, 0.80));
    await _repository.saveMotionConfig(_config);
    await _pushNativeConfig();
    notifyListeners();
  }

  Future<void> updateRoiWidth(double value) async {
    _config = _config.copyWith(roiWidth: value.clamp(0.05, 0.40));
    await _repository.saveMotionConfig(_config);
    await _pushNativeConfig();
    notifyListeners();
  }

  Future<void> updateCooldown(int value) async {
    _config = _config.copyWith(cooldownMs: value.clamp(300, 2000));
    await _repository.saveMotionConfig(_config);
    await _pushNativeConfig();
    notifyListeners();
  }

  Future<void> _pushNativeConfig() async {
    if (!_isStreaming) {
      return;
    }
    await _nativeSensorBridge.updateNativeConfig(config: _config.toJson());
  }

  void _onNativeEvent(Map<String, dynamic> event) {
    final type = event['type']?.toString();
    if (type == null) {
      return;
    }
    if (type == 'native_error') {
      _errorText = event['message']?.toString() ?? 'Native sensor error';
      notifyListeners();
      return;
    }
    if (type == 'native_state') {
      _sensorMinusElapsedNanos = _readInt(event['hostSensorMinusElapsedNanos']);
      notifyListeners();
      return;
    }
    if (type == 'native_frame_stats') {
      final frameSensorNanos = _readInt(event['frameSensorNanos']);
      final rawScore = _readDouble(event['rawScore']);
      final baseline = _readDouble(event['baseline']);
      final effective = _readDouble(event['effectiveScore']);
      if (frameSensorNanos == null ||
          rawScore == null ||
          baseline == null ||
          effective == null) {
        return;
      }
      final streamFrameCount = _readInt(event['streamFrameCount']);
      final processedFrameCount = _readInt(event['processedFrameCount']);
      _streamFrameCount = streamFrameCount ?? (_streamFrameCount + 1);
      _processedFrameCount = processedFrameCount ?? (_processedFrameCount + 1);
      _sensorMinusElapsedNanos =
          _readInt(event['hostSensorMinusElapsedNanos']) ??
          _sensorMinusElapsedNanos;
      _latestStats = MotionFrameStats(
        rawScore: rawScore,
        baseline: baseline,
        effectiveScore: effective,
        frameSensorNanos: frameSensorNanos,
      );
      final started = _runSnapshot.startedSensorNanos;
      if (_runSnapshot.isActive && started != null) {
        final elapsedNanos = math.max(0, frameSensorNanos - started);
        if (elapsedNanos != _runSnapshot.elapsedNanos) {
          _runSnapshot = _runSnapshot.copyWith(elapsedNanos: elapsedNanos);
        }
      }
      notifyListeners();
      return;
    }
    if (type == 'native_trigger') {
      final trigger = _parseTriggerEvent(event);
      if (trigger != null) {
        ingestTrigger(trigger);
      }
    }
  }

  MotionTriggerEvent? _parseTriggerEvent(Map<String, dynamic> event) {
    final triggerSensorNanos = _readInt(event['triggerSensorNanos']);
    final score = _readDouble(event['score']);
    final typeName = event['triggerType']?.toString();
    final splitIndex = _readInt(event['splitIndex']);
    if (triggerSensorNanos == null ||
        score == null ||
        typeName == null ||
        splitIndex == null) {
      return null;
    }
    final type = switch (typeName) {
      'start' => MotionTriggerType.start,
      'stop' => MotionTriggerType.stop,
      'split' => MotionTriggerType.split,
      _ => null,
    };
    if (type == null) {
      return null;
    }
    return MotionTriggerEvent(
      triggerSensorNanos: triggerSensorNanos,
      score: score,
      type: type,
      splitIndex: splitIndex,
    );
  }

  void ingestTrigger(MotionTriggerEvent trigger, {bool forwardToSync = true}) {
    final onTrigger = _onTrigger;
    if (forwardToSync && onTrigger != null) {
      onTrigger(trigger);
      return;
    }
    _addTriggerToHistory(trigger);

    if (trigger.type == MotionTriggerType.start) {
      _runSnapshot = MotionRunSnapshot(
        isActive: true,
        startedSensorNanos: trigger.triggerSensorNanos,
        elapsedNanos: 0,
        splitElapsedNanos: const <int>[],
      );
      unawaited(_persistCurrentRun());
      notifyListeners();
      return;
    }

    if (!_runSnapshot.isActive || _runSnapshot.startedSensorNanos == null) {
      return;
    }

    final elapsedNanos = math.max(
      0,
      trigger.triggerSensorNanos - _runSnapshot.startedSensorNanos!,
    );

    if (trigger.type == MotionTriggerType.split) {
      _runSnapshot = _runSnapshot.copyWith(
        elapsedNanos: elapsedNanos,
        splitElapsedNanos: <int>[
          ..._runSnapshot.splitElapsedNanos,
          elapsedNanos,
        ],
      );
      unawaited(_persistCurrentRun());
      notifyListeners();
      return;
    }

    if (trigger.type == MotionTriggerType.stop) {
      _runSnapshot = _runSnapshot.copyWith(
        isActive: false,
        elapsedNanos: elapsedNanos,
        splitElapsedNanos: <int>[
          ..._runSnapshot.splitElapsedNanos,
          elapsedNanos,
        ],
      );
      unawaited(_persistCurrentRun());
      notifyListeners();
    }
  }

  void _addTriggerToHistory(MotionTriggerEvent trigger) {
    _triggerHistory.insert(0, trigger);
    if (_triggerHistory.length > 20) {
      _triggerHistory.removeLast();
    }
  }

  Future<void> _persistCurrentRun() async {
    final startedSensorNanos = _runSnapshot.startedSensorNanos;
    if (startedSensorNanos == null) {
      return;
    }
    final run = LastRunResult(
      startedSensorNanos: startedSensorNanos,
      splitElapsedNanos: List<int>.from(_runSnapshot.splitElapsedNanos),
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
    _nativeEventsSubscription?.cancel();
    super.dispose();
  }
}

int? _readInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return null;
}

double? _readDouble(dynamic value) {
  if (value is double) {
    return value;
  }
  if (value is num) {
    return value.toDouble();
  }
  return null;
}
