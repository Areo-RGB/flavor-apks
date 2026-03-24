import 'dart:convert';

enum SessionStage { setup, lobby, monitoring }

enum SessionNetworkRole { none, host, client }

enum SessionDeviceRole { unassigned, start, split, stop }

enum SessionCameraFacing { rear, front }

class SessionDevice {
  const SessionDevice({
    required this.id,
    required this.name,
    required this.role,
    this.cameraFacing = SessionCameraFacing.rear,
    this.highSpeedEnabled = false,
    required this.isLocal,
  });

  final String id;
  final String name;
  final SessionDeviceRole role;
  final SessionCameraFacing cameraFacing;
  final bool highSpeedEnabled;
  final bool isLocal;

  SessionDevice copyWith({
    String? id,
    String? name,
    SessionDeviceRole? role,
    SessionCameraFacing? cameraFacing,
    bool? highSpeedEnabled,
    bool? isLocal,
  }) {
    return SessionDevice(
      id: id ?? this.id,
      name: name ?? this.name,
      role: role ?? this.role,
      cameraFacing: cameraFacing ?? this.cameraFacing,
      highSpeedEnabled: highSpeedEnabled ?? this.highSpeedEnabled,
      isLocal: isLocal ?? this.isLocal,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'role': role.name,
      'cameraFacing': cameraFacing.name,
      'highSpeedEnabled': highSpeedEnabled,
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
    final cameraFacing =
        sessionCameraFacingFromName(source['cameraFacing']?.toString()) ??
        SessionCameraFacing.rear;
    final highSpeedEnabled = source['highSpeedEnabled'] == true;
    if (id == null || id.isEmpty || name == null || role == null) {
      return null;
    }
    return SessionDevice(
      id: id,
      name: name,
      role: role,
      cameraFacing: cameraFacing,
      highSpeedEnabled: highSpeedEnabled,
      isLocal: source['isLocal'] == true,
    );
  }
}

class SessionRaceTimeline {
  const SessionRaceTimeline({
    this.startedSensorNanos,
    required this.splitElapsedNanos,
    this.stopElapsedNanos,
  });

  final int? startedSensorNanos;
  final List<int> splitElapsedNanos;
  final int? stopElapsedNanos;

  factory SessionRaceTimeline.idle() {
    return const SessionRaceTimeline(splitElapsedNanos: <int>[]);
  }

  bool get hasStarted => startedSensorNanos != null;
  bool get hasStopped => stopElapsedNanos != null;
  bool get isRunning => hasStarted && !hasStopped;

  SessionRaceTimeline copyWith({
    int? startedSensorNanos,
    List<int>? splitElapsedNanos,
    int? stopElapsedNanos,
    bool clearStartedSensor = false,
    bool clearStopElapsedNanos = false,
  }) {
    return SessionRaceTimeline(
      startedSensorNanos: clearStartedSensor
          ? null
          : (startedSensorNanos ?? this.startedSensorNanos),
      splitElapsedNanos: splitElapsedNanos ?? this.splitElapsedNanos,
      stopElapsedNanos: clearStopElapsedNanos
          ? null
          : (stopElapsedNanos ?? this.stopElapsedNanos),
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'startedSensorNanos': startedSensorNanos,
      'splitElapsedNanos': splitElapsedNanos,
      'stopElapsedNanos': stopElapsedNanos,
    };
  }

  static SessionRaceTimeline fromJson(dynamic source) {
    if (source is! Map<String, dynamic>) {
      return SessionRaceTimeline.idle();
    }
    final splitRaw = source['splitElapsedNanos'];
    final splitElapsedNanos = <int>[];
    if (splitRaw is List) {
      for (final value in splitRaw) {
        if (value is int) {
          splitElapsedNanos.add(value);
        } else if (value is num) {
          splitElapsedNanos.add(value.toInt());
        }
      }
    }
    final startedRaw = source['startedSensorNanos'];
    final stopElapsedRaw = source['stopElapsedNanos'];
    final startedSensorNanos = startedRaw is num ? startedRaw.toInt() : null;
    final stopElapsedNanos = stopElapsedRaw is num
        ? stopElapsedRaw.toInt()
        : null;
    return SessionRaceTimeline(
      startedSensorNanos: startedSensorNanos,
      splitElapsedNanos: splitElapsedNanos,
      stopElapsedNanos: stopElapsedNanos,
    );
  }
}

class SessionSnapshotMessage {
  const SessionSnapshotMessage({
    required this.stage,
    required this.monitoringActive,
    required this.devices,
    required this.timeline,
    this.hostSensorMinusElapsedNanos,
    this.hostGpsUtcOffsetNanos,
    this.hostGpsFixAgeNanos,
    this.selfDeviceId,
  });

  final SessionStage stage;
  final bool monitoringActive;
  final List<SessionDevice> devices;
  final SessionRaceTimeline timeline;
  final int? hostSensorMinusElapsedNanos;
  final int? hostGpsUtcOffsetNanos;
  final int? hostGpsFixAgeNanos;
  final String? selfDeviceId;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'snapshot',
      'stage': stage.name,
      'monitoringActive': monitoringActive,
      'devices': devices.map((device) => device.toJson()).toList(),
      'timeline': timeline.toJson(),
      'hostSensorMinusElapsedNanos': hostSensorMinusElapsedNanos,
      'hostGpsUtcOffsetNanos': hostGpsUtcOffsetNanos,
      'hostGpsFixAgeNanos': hostGpsFixAgeNanos,
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
        hostSensorMinusElapsedNanos:
            (decoded['hostSensorMinusElapsedNanos'] as num?)?.toInt(),
        hostGpsUtcOffsetNanos: (decoded['hostGpsUtcOffsetNanos'] as num?)
            ?.toInt(),
        hostGpsFixAgeNanos: (decoded['hostGpsFixAgeNanos'] as num?)?.toInt(),
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
    required this.triggerSensorNanos,
    this.mappedHostSensorNanos,
  });

  final SessionDeviceRole role;
  final int triggerSensorNanos;
  final int? mappedHostSensorNanos;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'trigger_request',
      'role': role.name,
      'triggerSensorNanos': triggerSensorNanos,
      'mappedHostSensorNanos': mappedHostSensorNanos,
    };
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
      final triggerSensorNanosRaw = decoded['triggerSensorNanos'];
      if (role == null || triggerSensorNanosRaw is! num) {
        return null;
      }
      final mappedHostSensorNanosRaw = decoded['mappedHostSensorNanos'];
      return SessionTriggerRequestMessage(
        role: role,
        triggerSensorNanos: triggerSensorNanosRaw.toInt(),
        mappedHostSensorNanos: mappedHostSensorNanosRaw is num
            ? mappedHostSensorNanosRaw.toInt()
            : null,
      );
    } catch (_) {
      return null;
    }
  }
}

class SessionClockSyncRequestMessage {
  const SessionClockSyncRequestMessage({required this.clientSendElapsedNanos});

  final int clientSendElapsedNanos;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'clock_sync_request',
      'clientSendElapsedNanos': clientSendElapsedNanos,
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
      final clientSendElapsedNanosRaw = decoded['clientSendElapsedNanos'];
      if (clientSendElapsedNanosRaw is! num) {
        return null;
      }
      return SessionClockSyncRequestMessage(
        clientSendElapsedNanos: clientSendElapsedNanosRaw.toInt(),
      );
    } catch (_) {
      return null;
    }
  }
}

class SessionClockSyncResponseMessage {
  const SessionClockSyncResponseMessage({
    required this.clientSendElapsedNanos,
    required this.hostReceiveElapsedNanos,
    required this.hostSendElapsedNanos,
  });

  final int clientSendElapsedNanos;
  final int hostReceiveElapsedNanos;
  final int hostSendElapsedNanos;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'type': 'clock_sync_response',
      'clientSendElapsedNanos': clientSendElapsedNanos,
      'hostReceiveElapsedNanos': hostReceiveElapsedNanos,
      'hostSendElapsedNanos': hostSendElapsedNanos,
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
      final clientSendElapsedNanosRaw = decoded['clientSendElapsedNanos'];
      final hostReceiveElapsedNanosRaw = decoded['hostReceiveElapsedNanos'];
      final hostSendElapsedNanosRaw = decoded['hostSendElapsedNanos'];
      if (clientSendElapsedNanosRaw is! num ||
          hostReceiveElapsedNanosRaw is! num ||
          hostSendElapsedNanosRaw is! num) {
        return null;
      }
      return SessionClockSyncResponseMessage(
        clientSendElapsedNanos: clientSendElapsedNanosRaw.toInt(),
        hostReceiveElapsedNanos: hostReceiveElapsedNanosRaw.toInt(),
        hostSendElapsedNanos: hostSendElapsedNanosRaw.toInt(),
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

SessionCameraFacing? sessionCameraFacingFromName(String? name) {
  if (name == null) {
    return null;
  }
  for (final value in SessionCameraFacing.values) {
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

String sessionCameraFacingLabel(SessionCameraFacing facing) {
  switch (facing) {
    case SessionCameraFacing.rear:
      return 'Rear';
    case SessionCameraFacing.front:
      return 'Front';
  }
}
