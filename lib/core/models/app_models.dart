class LastRunResult {
  const LastRunResult({
    required this.startedSensorNanos,
    required this.splitElapsedNanos,
  });

  final int startedSensorNanos;
  final List<int> splitElapsedNanos;

  Map<String, dynamic> toJson() {
    return {
      'startedSensorNanos': startedSensorNanos,
      'splitElapsedNanos': splitElapsedNanos,
    };
  }

  static LastRunResult? fromJson(dynamic source) {
    if (source is! Map<String, dynamic>) {
      return null;
    }
    final startedAt = source['startedSensorNanos'];
    final splits = source['splitElapsedNanos'];
    if (startedAt is! int || splits is! List) {
      return null;
    }
    final parsedSplits = <int>[];
    for (final value in splits) {
      if (value is int) {
        parsedSplits.add(value);
      }
    }
    return LastRunResult(
      startedSensorNanos: startedAt,
      splitElapsedNanos: parsedSplits,
    );
  }
}
