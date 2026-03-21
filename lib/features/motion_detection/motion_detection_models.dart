import 'dart:math' as math;

enum MotionTriggerType { start, stop, split }

class MotionRunSnapshot {
  const MotionRunSnapshot({
    required this.isActive,
    this.startedSensorNanos,
    required this.elapsedNanos,
    required this.splitElapsedNanos,
  });

  final bool isActive;
  final int? startedSensorNanos;
  final int elapsedNanos;
  final List<int> splitElapsedNanos;

  factory MotionRunSnapshot.ready() {
    return const MotionRunSnapshot(
      isActive: false,
      startedSensorNanos: null,
      elapsedNanos: 0,
      splitElapsedNanos: <int>[],
    );
  }

  MotionRunSnapshot copyWith({
    bool? isActive,
    int? startedSensorNanos,
    int? elapsedNanos,
    List<int>? splitElapsedNanos,
    bool clearStartedSensorNanos = false,
  }) {
    return MotionRunSnapshot(
      isActive: isActive ?? this.isActive,
      startedSensorNanos: clearStartedSensorNanos
          ? null
          : (startedSensorNanos ?? this.startedSensorNanos),
      elapsedNanos: elapsedNanos ?? this.elapsedNanos,
      splitElapsedNanos: splitElapsedNanos ?? this.splitElapsedNanos,
    );
  }
}

class MotionDetectionConfig {
  const MotionDetectionConfig({
    required this.threshold,
    required this.roiCenterX,
    required this.roiWidth,
    required this.cooldownMs,
    required this.processEveryNFrames,
  });

  final double threshold;
  final double roiCenterX;
  final double roiWidth;
  final int cooldownMs;
  final int processEveryNFrames;

  factory MotionDetectionConfig.defaults() {
    return const MotionDetectionConfig(
      threshold: 0.006,
      roiCenterX: 0.5,
      roiWidth: 0.12,
      cooldownMs: 900,
      processEveryNFrames: 1,
    );
  }

  MotionDetectionConfig copyWith({
    double? threshold,
    double? roiCenterX,
    double? roiWidth,
    int? cooldownMs,
    int? processEveryNFrames,
  }) {
    return MotionDetectionConfig(
      threshold: threshold ?? this.threshold,
      roiCenterX: roiCenterX ?? this.roiCenterX,
      roiWidth: roiWidth ?? this.roiWidth,
      cooldownMs: cooldownMs ?? this.cooldownMs,
      processEveryNFrames: processEveryNFrames ?? this.processEveryNFrames,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'threshold': threshold,
      'roiCenterX': roiCenterX,
      'roiWidth': roiWidth,
      'cooldownMs': cooldownMs,
      'processEveryNFrames': processEveryNFrames,
    };
  }

  static MotionDetectionConfig fromJson(Map<String, dynamic> json) {
    final defaults = MotionDetectionConfig.defaults();
    final parsedThreshold =
        (json['threshold'] as num?)?.toDouble() ?? defaults.threshold;
    final parsedRoiCenter =
        (json['roiCenterX'] as num?)?.toDouble() ?? defaults.roiCenterX;
    final parsedRoiWidth =
        (json['roiWidth'] as num?)?.toDouble() ?? defaults.roiWidth;
    final parsedCooldown =
        (json['cooldownMs'] as num?)?.toInt() ?? defaults.cooldownMs;
    final parsedFrameSkip =
        (json['processEveryNFrames'] as num?)?.toInt() ??
        defaults.processEveryNFrames;

    return MotionDetectionConfig(
      threshold: _clampDouble(parsedThreshold, 0.001, 0.08),
      roiCenterX: _clampDouble(parsedRoiCenter, 0.20, 0.80),
      roiWidth: _clampDouble(parsedRoiWidth, 0.05, 0.40),
      cooldownMs: parsedCooldown.clamp(300, 2000),
      processEveryNFrames: parsedFrameSkip.clamp(1, 5),
    );
  }
}

class MotionTriggerEvent {
  const MotionTriggerEvent({
    required this.triggerSensorNanos,
    required this.score,
    required this.type,
    required this.splitIndex,
  });

  final int triggerSensorNanos;
  final double score;
  final MotionTriggerType type;
  final int splitIndex;
}

class MotionFrameStats {
  const MotionFrameStats({
    required this.rawScore,
    required this.baseline,
    required this.effectiveScore,
    required this.frameSensorNanos,
    this.triggerEvent,
  });

  final double rawScore;
  final double baseline;
  final double effectiveScore;
  final int frameSensorNanos;
  final MotionTriggerEvent? triggerEvent;
}

class MotionDetectionEngine {
  MotionDetectionEngine({
    required MotionDetectionConfig config,
    this.emaAlpha = 0.08,
  }) : _config = config;

  final double emaAlpha;
  MotionDetectionConfig _config;

  double _baseline = 0;
  int _aboveCount = 0;
  bool _armed = true;
  int? _belowSinceNanos;
  int? _lastTriggerNanos;
  int _pulseCounter = 0;

  MotionDetectionConfig get config => _config;

  void updateConfig(MotionDetectionConfig config) {
    _config = config;
  }

  void resetRace() {
    _baseline = 0;
    _aboveCount = 0;
    _armed = true;
    _belowSinceNanos = null;
    _lastTriggerNanos = null;
    _pulseCounter = 0;
  }

  MotionFrameStats process({
    required double rawScore,
    required int frameSensorNanos,
  }) {
    if (_baseline == 0) {
      _baseline = rawScore;
    } else {
      _baseline = (rawScore * emaAlpha) + (_baseline * (1 - emaAlpha));
    }

    final effectiveScore = math.max(0.0, rawScore - _baseline);
    final triggerThreshold = _config.threshold;
    final rearmsBelow = triggerThreshold * 0.6;

    if (!_armed) {
      if (effectiveScore < rearmsBelow) {
        _belowSinceNanos ??= frameSensorNanos;
        final elapsed =
            frameSensorNanos - (_belowSinceNanos ?? frameSensorNanos);
        if (elapsed >= 200000000) {
          _armed = true;
          _aboveCount = 0;
          _belowSinceNanos = null;
        }
      } else {
        _belowSinceNanos = null;
      }
    }

    if (effectiveScore > triggerThreshold) {
      _aboveCount += 1;
    } else {
      _aboveCount = 0;
    }

    MotionTriggerEvent? trigger;
    final cooldownNanos = _config.cooldownMs * 1000000;
    final cooldownPassed =
        _lastTriggerNanos == null ||
        (frameSensorNanos - (_lastTriggerNanos ?? 0)) >= cooldownNanos;

    if (_armed && cooldownPassed && _aboveCount >= 1) {
      _lastTriggerNanos = frameSensorNanos;
      _aboveCount = 0;
      _armed = false;
      _belowSinceNanos = null;
      _pulseCounter += 1;
      trigger = MotionTriggerEvent(
        triggerSensorNanos: frameSensorNanos,
        score: effectiveScore,
        type: MotionTriggerType.split,
        splitIndex: _pulseCounter,
      );
    }

    return MotionFrameStats(
      rawScore: rawScore,
      baseline: _baseline,
      effectiveScore: effectiveScore,
      frameSensorNanos: frameSensorNanos,
      triggerEvent: trigger,
    );
  }
}

double _clampDouble(double value, double min, double max) {
  if (value < min) {
    return min;
  }
  if (value > max) {
    return max;
  }
  return value;
}

String formatDurationNanos(int nanos) {
  const nanosPerSecond = 1000000000;
  final seconds = nanos / nanosPerSecond;
  return '${seconds.toStringAsFixed(2)}s';
}

String formatSplitLabel(int splitIndex) {
  return 'Split $splitIndex';
}
