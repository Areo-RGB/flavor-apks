import 'dart:math' as math;

enum MotionTriggerType { start, split }

class MotionRunSnapshot {
  const MotionRunSnapshot({
    required this.isActive,
    this.startedAtMicros,
    required this.elapsedMicros,
    required this.splitMicros,
  });

  final bool isActive;
  final int? startedAtMicros;
  final int elapsedMicros;
  final List<int> splitMicros;

  factory MotionRunSnapshot.ready() {
    return const MotionRunSnapshot(
      isActive: false,
      startedAtMicros: null,
      elapsedMicros: 0,
      splitMicros: <int>[],
    );
  }

  MotionRunSnapshot copyWith({
    bool? isActive,
    int? startedAtMicros,
    int? elapsedMicros,
    List<int>? splitMicros,
    bool clearStartedAt = false,
  }) {
    return MotionRunSnapshot(
      isActive: isActive ?? this.isActive,
      startedAtMicros: clearStartedAt
          ? null
          : (startedAtMicros ?? this.startedAtMicros),
      elapsedMicros: elapsedMicros ?? this.elapsedMicros,
      splitMicros: splitMicros ?? this.splitMicros,
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
      threshold: 0.08,
      roiCenterX: 0.5,
      roiWidth: 0.12,
      cooldownMs: 900,
      processEveryNFrames: 2,
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
      threshold: _clampDouble(parsedThreshold, 0.02, 0.30),
      roiCenterX: _clampDouble(parsedRoiCenter, 0.20, 0.80),
      roiWidth: _clampDouble(parsedRoiWidth, 0.05, 0.40),
      cooldownMs: parsedCooldown.clamp(300, 2000),
      processEveryNFrames: parsedFrameSkip.clamp(1, 5),
    );
  }
}

class MotionTriggerEvent {
  const MotionTriggerEvent({
    required this.triggerMicros,
    required this.score,
    required this.type,
    required this.splitIndex,
  });

  final int triggerMicros;
  final double score;
  final MotionTriggerType type;
  final int splitIndex;
}

class MotionFrameStats {
  const MotionFrameStats({
    required this.rawScore,
    required this.baseline,
    required this.effectiveScore,
    required this.timestampMicros,
    this.triggerEvent,
  });

  final double rawScore;
  final double baseline;
  final double effectiveScore;
  final int timestampMicros;
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
  int? _belowSinceMicros;
  int? _lastTriggerMicros;
  bool _raceStarted = false;
  int _splitIndex = 0;

  MotionDetectionConfig get config => _config;

  void updateConfig(MotionDetectionConfig config) {
    _config = config;
  }

  void resetRace() {
    _aboveCount = 0;
    _armed = true;
    _belowSinceMicros = null;
    _lastTriggerMicros = null;
    _raceStarted = false;
    _splitIndex = 0;
  }

  MotionFrameStats process({
    required double rawScore,
    required int timestampMicros,
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
        _belowSinceMicros ??= timestampMicros;
        final elapsed =
            timestampMicros - (_belowSinceMicros ?? timestampMicros);
        if (elapsed >= 200000) {
          _armed = true;
          _aboveCount = 0;
          _belowSinceMicros = null;
        }
      } else {
        _belowSinceMicros = null;
      }
    }

    if (effectiveScore > triggerThreshold) {
      _aboveCount += 1;
    } else {
      _aboveCount = 0;
    }

    MotionTriggerEvent? trigger;
    final cooldownMicros = _config.cooldownMs * 1000;
    final cooldownPassed =
        _lastTriggerMicros == null ||
        (timestampMicros - (_lastTriggerMicros ?? 0)) >= cooldownMicros;

    if (_armed && cooldownPassed && _aboveCount >= 3) {
      _lastTriggerMicros = timestampMicros;
      _aboveCount = 0;
      _armed = false;
      _belowSinceMicros = null;

      if (_raceStarted) {
        _splitIndex += 1;
        trigger = MotionTriggerEvent(
          triggerMicros: timestampMicros,
          score: effectiveScore,
          type: MotionTriggerType.split,
          splitIndex: _splitIndex,
        );
      } else {
        _raceStarted = true;
        trigger = MotionTriggerEvent(
          triggerMicros: timestampMicros,
          score: effectiveScore,
          type: MotionTriggerType.start,
          splitIndex: 0,
        );
      }
    }

    return MotionFrameStats(
      rawScore: rawScore,
      baseline: _baseline,
      effectiveScore: effectiveScore,
      timestampMicros: timestampMicros,
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

String formatDurationMicros(int micros) {
  final seconds = micros / Duration.microsecondsPerSecond;
  return '${seconds.toStringAsFixed(2)}s';
}

String formatSplitLabel(int splitIndex) {
  return 'Split $splitIndex';
}
