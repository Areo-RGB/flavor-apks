import 'dart:convert';

enum RaceRole { none, host, client }

enum RaceEventType { raceStarted, raceSplit, latencyProbe, latencyPong }

enum ConnectionQuality { offline, good, warning, bad }

class SessionState {
  const SessionState({
    required this.raceStarted,
    this.startedAtEpochMs,
    required this.splitMicros,
  });

  final bool raceStarted;
  final int? startedAtEpochMs;
  final List<int> splitMicros;

  factory SessionState.initial() {
    return const SessionState(raceStarted: false, splitMicros: <int>[]);
  }

  SessionState copyWith({
    bool? raceStarted,
    int? startedAtEpochMs,
    List<int>? splitMicros,
    bool clearStartedAt = false,
  }) {
    return SessionState(
      raceStarted: raceStarted ?? this.raceStarted,
      startedAtEpochMs: clearStartedAt
          ? null
          : (startedAtEpochMs ?? this.startedAtEpochMs),
      splitMicros: splitMicros ?? this.splitMicros,
    );
  }
}

class RaceEventMessage {
  const RaceEventMessage({
    required this.type,
    required this.sessionId,
    this.startedAtEpochMs,
    this.splitIndex,
    this.elapsedMicros,
    this.probeId,
  });

  final RaceEventType type;
  final String sessionId;
  final int? startedAtEpochMs;
  final int? splitIndex;
  final int? elapsedMicros;
  final String? probeId;

  Map<String, dynamic> toJsonMap() {
    return {
      'type': _typeToWire(type),
      'sessionId': sessionId,
      'startedAtEpochMs': startedAtEpochMs,
      'splitIndex': splitIndex,
      'elapsedMicros': elapsedMicros,
      'probeId': probeId,
    };
  }

  String toJsonString() => jsonEncode(toJsonMap());

  static RaceEventMessage? tryParse(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) {
        return null;
      }
      final type = _typeFromWire(decoded['type']);
      final sessionId = decoded['sessionId'];
      if (type == null || sessionId is! String) {
        return null;
      }
      return RaceEventMessage(
        type: type,
        sessionId: sessionId,
        startedAtEpochMs: (decoded['startedAtEpochMs'] as num?)?.toInt(),
        splitIndex: (decoded['splitIndex'] as num?)?.toInt(),
        elapsedMicros: (decoded['elapsedMicros'] as num?)?.toInt(),
        probeId: decoded['probeId']?.toString(),
      );
    } catch (_) {
      return null;
    }
  }

  static String _typeToWire(RaceEventType type) {
    switch (type) {
      case RaceEventType.raceStarted:
        return 'race_started';
      case RaceEventType.raceSplit:
        return 'race_split';
      case RaceEventType.latencyProbe:
        return 'latency_probe';
      case RaceEventType.latencyPong:
        return 'latency_pong';
    }
  }

  static RaceEventType? _typeFromWire(dynamic source) {
    if (source == 'race_started') {
      return RaceEventType.raceStarted;
    }
    if (source == 'race_split') {
      return RaceEventType.raceSplit;
    }
    if (source == 'latency_probe') {
      return RaceEventType.latencyProbe;
    }
    if (source == 'latency_pong') {
      return RaceEventType.latencyPong;
    }
    return null;
  }
}
