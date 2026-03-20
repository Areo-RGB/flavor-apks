import 'dart:convert';

enum SessionStage { setup, lobby, monitoring }

enum SessionNetworkRole { none, host, client }

enum SessionDeviceRole { unassigned, start, split, stop }

class SessionDevice {
  const SessionDevice({
    required this.id,
    required this.name,
    required this.role,
    required this.isLocal,
  });

  final String id;
  final String name;
  final SessionDeviceRole role;
  final bool isLocal;

  SessionDevice copyWith({
    String? id,
    String? name,
    SessionDeviceRole? role,
    bool? isLocal,
  }) {
    return SessionDevice(
      id: id ?? this.id,
      name: name ?? this.name,
      role: role ?? this.role,
      isLocal: isLocal ?? this.isLocal,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'role': role.name,
      'isLocal': isLocal,
    };
  }

  static SessionDevice? fromJson(dynamic source) {
    if (source is! Map<String, dynamic>) {
      return null;
    }
    final id = source['id']?.toString();
    final name = source['name']?.toString();
    final role = sessionDeviceRoleFromName(source['role']?.toString());
    if (id == null || id.isEmpty || name == null || role == null) {
      return null;
    }
    return SessionDevice(
      id: id,
      name: name,
      role: role,
      isLocal: source['isLocal'] == true,
    );
  }
}

class SessionRaceTimeline {
  const SessionRaceTimeline({
    this.startedAtEpochMs,
    required this.splitMicros,
    this.stopElapsedMicros,
    required this.revision,
  });

  final int? startedAtEpochMs;
  final List<int> splitMicros;
  final int? stopElapsedMicros;
  final int revision;

  factory SessionRaceTimeline.idle({int revision = 0}) {
    return SessionRaceTimeline(splitMicros: const <int>[], revision: revision);
  }

  bool get hasStarted => startedAtEpochMs != null;
  bool get hasStopped => stopElapsedMicros != null;
  bool get isRunning => hasStarted && !hasStopped;

  int elapsedMicrosAt(int nowMicros) {
    final startedAt = startedAtEpochMs;
    if (startedAt == null) {
      return 0;
    }
    final stoppedAt = stopElapsedMicros;
    if (stoppedAt != null) {
      return stoppedAt;
    }
    final elapsed = nowMicros - (startedAt * 1000);
    return elapsed < 0 ? 0 : elapsed;
  }

  List<int> get displaySplitMicros {
    final stoppedAt = stopElapsedMicros;
    if (stoppedAt == null) {
      return List<int>.unmodifiable(splitMicros);
    }
    return List<int>.unmodifiable(<int>[...splitMicros, stoppedAt]);
  }

  SessionRaceTimeline copyWith({
    int? startedAtEpochMs,
    List<int>? splitMicros,
    int? stopElapsedMicros,
    int? revision,
    bool clearStartedAt = false,
    bool clearStopElapsed = false,
  }) {
    return SessionRaceTimeline(
      startedAtEpochMs: clearStartedAt
          ? null
          : (startedAtEpochMs ?? this.startedAtEpochMs),
      splitMicros: splitMicros ?? this.splitMicros,
      stopElapsedMicros: clearStopElapsed
          ? null
          : (stopElapsedMicros ?? this.stopElapsedMicros),
      revision: revision ?? this.revision,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'startedAtEpochMs': startedAtEpochMs,
      'splitMicros': splitMicros,
      'stopElapsedMicros': stopElapsedMicros,
      'revision': revision,
    };
  }

  static SessionRaceTimeline fromJson(dynamic source) {
    if (source is! Map<String, dynamic>) {
      return SessionRaceTimeline.idle();
    }
    final splitRaw = source['splitMicros'];
    final splitMicros = <int>[];
    if (splitRaw is List) {
      for (final value in splitRaw) {
        if (value is int) {
          splitMicros.add(value);
        } else if (value is num) {
          splitMicros.add(value.toInt());
        }
      }
    }
    final startedAtRaw = source['startedAtEpochMs'];
    final stopElapsedRaw = source['stopElapsedMicros'];
    final startedAtEpochMs = startedAtRaw is num ? startedAtRaw.toInt() : null;
    final stopElapsedMicros = stopElapsedRaw is num
        ? stopElapsedRaw.toInt()
        : null;
    final revisionRaw = source['revision'];
    final revision = revisionRaw is num ? revisionRaw.toInt() : 0;
    return SessionRaceTimeline(
      startedAtEpochMs: startedAtEpochMs,
      splitMicros: splitMicros,
      stopElapsedMicros: stopElapsedMicros,
      revision: revision,
    );
  }
}

class SessionSnapshotMessage {
  const SessionSnapshotMessage({
    required this.stage,
    required this.monitoringActive,
    required this.devices,
    required this.timeline,
    this.selfDeviceId,
  });

  final SessionStage stage;
  final bool monitoringActive;
  final List<SessionDevice> devices;
  final SessionRaceTimeline timeline;
  final String? selfDeviceId;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'snapshot',
      'stage': stage.name,
      'monitoringActive': monitoringActive,
      'devices': devices.map((device) => device.toJson()).toList(),
      'timeline': timeline.toJson(),
      'selfDeviceId': selfDeviceId,
    };
  }

  String toJsonString() => jsonEncode(toJson());

  static SessionSnapshotMessage? tryParse(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic> || decoded['type'] != 'snapshot') {
        return null;
      }
      final stage = sessionStageFromName(decoded['stage']?.toString());
      if (stage == null) {
        return null;
      }
      final monitoringActive = decoded['monitoringActive'] == true;
      final devicesRaw = decoded['devices'];
      if (devicesRaw is! List) {
        return null;
      }
      final devices = <SessionDevice>[];
      for (final item in devicesRaw) {
        final parsed = SessionDevice.fromJson(item);
        if (parsed != null) {
          devices.add(parsed);
        }
      }
      if (devices.isEmpty) {
        return null;
      }
      return SessionSnapshotMessage(
        stage: stage,
        monitoringActive: monitoringActive,
        devices: devices,
        timeline: SessionRaceTimeline.fromJson(decoded['timeline']),
        selfDeviceId: decoded['selfDeviceId']?.toString(),
      );
    } catch (_) {
      return null;
    }
  }
}

class SessionTriggerRequestMessage {
  const SessionTriggerRequestMessage({
    required this.role,
    required this.deviceTriggerMicros,
    this.hostTriggerMicros,
  });

  final SessionDeviceRole role;
  final int deviceTriggerMicros;
  final int? hostTriggerMicros;

  Map<String, dynamic> toJson() {
    final payload = <String, dynamic>{
      'type': 'trigger_request',
      'role': role.name,
      'deviceTriggerMicros': deviceTriggerMicros,
    };
    if (hostTriggerMicros != null) {
      payload['hostTriggerMicros'] = hostTriggerMicros;
    }
    return payload;
  }

  String toJsonString() => jsonEncode(toJson());

  static SessionTriggerRequestMessage? tryParse(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic> ||
          decoded['type'] != 'trigger_request') {
        return null;
      }
      final role = sessionDeviceRoleFromName(decoded['role']?.toString());
      final deviceTriggerMicrosRaw =
          decoded['deviceTriggerMicros'] ?? decoded['triggerMicros'];
      if (role == null || deviceTriggerMicrosRaw is! num) {
        return null;
      }
      final hostTriggerMicrosRaw = decoded['hostTriggerMicros'];
      return SessionTriggerRequestMessage(
        role: role,
        deviceTriggerMicros: deviceTriggerMicrosRaw.toInt(),
        hostTriggerMicros: hostTriggerMicrosRaw is num
            ? hostTriggerMicrosRaw.toInt()
            : null,
      );
    } catch (_) {
      return null;
    }
  }
}

class SessionTimelineUpdateMessage {
  const SessionTimelineUpdateMessage({required this.timeline});

  final SessionRaceTimeline timeline;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'timeline_update',
      'timeline': timeline.toJson(),
    };
  }

  String toJsonString() => jsonEncode(toJson());

  static SessionTimelineUpdateMessage? tryParse(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic> ||
          decoded['type'] != 'timeline_update') {
        return null;
      }
      return SessionTimelineUpdateMessage(
        timeline: SessionRaceTimeline.fromJson(decoded['timeline']),
      );
    } catch (_) {
      return null;
    }
  }
}

class SessionClockSyncRequestMessage {
  const SessionClockSyncRequestMessage({required this.clientSentAtMicros});

  final int clientSentAtMicros;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'clock_sync_request',
      'clientSentAtMicros': clientSentAtMicros,
    };
  }

  String toJsonString() => jsonEncode(toJson());

  static SessionClockSyncRequestMessage? tryParse(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic> ||
          decoded['type'] != 'clock_sync_request') {
        return null;
      }
      final clientSentAtMicros = decoded['clientSentAtMicros'];
      if (clientSentAtMicros is! num) {
        return null;
      }
      return SessionClockSyncRequestMessage(
        clientSentAtMicros: clientSentAtMicros.toInt(),
      );
    } catch (_) {
      return null;
    }
  }
}

class SessionClockSyncResponseMessage {
  const SessionClockSyncResponseMessage({
    required this.clientSentAtMicros,
    required this.hostReceivedAtMicros,
    required this.hostSentAtMicros,
  });

  final int clientSentAtMicros;
  final int hostReceivedAtMicros;
  final int hostSentAtMicros;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'clock_sync_response',
      'clientSentAtMicros': clientSentAtMicros,
      'hostReceivedAtMicros': hostReceivedAtMicros,
      'hostSentAtMicros': hostSentAtMicros,
    };
  }

  String toJsonString() => jsonEncode(toJson());

  static SessionClockSyncResponseMessage? tryParse(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic> ||
          decoded['type'] != 'clock_sync_response') {
        return null;
      }
      final clientSentAtMicros = decoded['clientSentAtMicros'];
      final hostReceivedAtMicros = decoded['hostReceivedAtMicros'];
      final hostSentAtMicros = decoded['hostSentAtMicros'];
      if (clientSentAtMicros is! num ||
          hostReceivedAtMicros is! num ||
          hostSentAtMicros is! num) {
        return null;
      }
      return SessionClockSyncResponseMessage(
        clientSentAtMicros: clientSentAtMicros.toInt(),
        hostReceivedAtMicros: hostReceivedAtMicros.toInt(),
        hostSentAtMicros: hostSentAtMicros.toInt(),
      );
    } catch (_) {
      return null;
    }
  }
}

SessionStage? sessionStageFromName(String? name) {
  if (name == null) {
    return null;
  }
  for (final value in SessionStage.values) {
    if (value.name == name) {
      return value;
    }
  }
  return null;
}

SessionDeviceRole? sessionDeviceRoleFromName(String? name) {
  if (name == null) {
    return null;
  }
  for (final value in SessionDeviceRole.values) {
    if (value.name == name) {
      return value;
    }
  }
  return null;
}

String sessionDeviceRoleLabel(SessionDeviceRole role) {
  switch (role) {
    case SessionDeviceRole.unassigned:
      return 'Unassigned';
    case SessionDeviceRole.start:
      return 'Start';
    case SessionDeviceRole.split:
      return 'Split';
    case SessionDeviceRole.stop:
      return 'Stop';
  }
}
