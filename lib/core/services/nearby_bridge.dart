import 'package:flutter/services.dart';

class NearbyConnectionResultEvent {
  const NearbyConnectionResultEvent({
    required this.endpointId,
    required this.connected,
    this.endpointName,
    this.statusCode,
    this.statusMessage,
  });

  final String endpointId;
  final bool connected;
  final String? endpointName;
  final int? statusCode;
  final String? statusMessage;

  static NearbyConnectionResultEvent? tryParse(Map<String, dynamic> event) {
    if (event['type'] != 'connection_result') {
      return null;
    }
    final endpointId = event['endpointId']?.toString();
    if (endpointId == null || endpointId.isEmpty) {
      return null;
    }
    final connected = event['connected'];
    if (connected is! bool) {
      return null;
    }
    return NearbyConnectionResultEvent(
      endpointId: endpointId,
      connected: connected,
      endpointName: event['endpointName']?.toString(),
      statusCode: _readInt(event['statusCode']),
      statusMessage: event['statusMessage']?.toString(),
    );
  }
}

class NearbyEndpoint {
  const NearbyEndpoint({
    required this.id,
    required this.name,
    required this.serviceId,
  });

  final String id;
  final String name;
  final String serviceId;
}

class NearbyBridge {
  static const _methodChannel = MethodChannel(
    'com.paul.sprintsync/nearby_methods',
  );
  static const _eventChannel = EventChannel(
    'com.paul.sprintsync/nearby_events',
  );

  Stream<Map<String, dynamic>> get events {
    return _eventChannel.receiveBroadcastStream().map((dynamic event) {
      if (event is Map) {
        return Map<String, dynamic>.from(event);
      }
      return <String, dynamic>{'type': 'error', 'message': 'Malformed event'};
    });
  }

  Future<Map<String, dynamic>> requestPermissions() async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      'requestPermissions',
    );
    if (response is Map) {
      return Map<String, dynamic>.from(response);
    }
    return <String, dynamic>{'granted': false, 'denied': <String>[]};
  }

  Future<Map<String, dynamic>> getPermissionStatus() async {
    try {
      final response = await _methodChannel.invokeMethod<dynamic>(
        'getPermissionStatus',
      );
      if (response is Map) {
        return Map<String, dynamic>.from(response);
      }
    } on MissingPluginException {
      // Test and non-Android environments may not expose the platform method.
    } on PlatformException catch (error) {
      if (error.code != 'unimplemented') {
        rethrow;
      }
    }
    return <String, dynamic>{'granted': false, 'denied': <String>[]};
  }

  Future<void> startHosting({
    required String serviceId,
    required String endpointName,
  }) {
    return _methodChannel.invokeMethod<void>('startHosting', {
      'serviceId': serviceId,
      'endpointName': endpointName,
    });
  }

  Future<void> stopHosting() {
    return _methodChannel.invokeMethod<void>('stopHosting');
  }

  Future<void> startDiscovery({
    required String serviceId,
    required String endpointName,
  }) {
    return _methodChannel.invokeMethod<void>('startDiscovery', {
      'serviceId': serviceId,
      'endpointName': endpointName,
    });
  }

  Future<void> stopDiscovery() {
    return _methodChannel.invokeMethod<void>('stopDiscovery');
  }

  Future<void> requestConnection({
    required String endpointId,
    required String endpointName,
  }) {
    return _methodChannel.invokeMethod<void>('requestConnection', {
      'endpointId': endpointId,
      'endpointName': endpointName,
    });
  }

  Future<void> sendBytes({
    required String endpointId,
    required String messageJson,
  }) {
    return _methodChannel.invokeMethod<void>('sendBytes', {
      'endpointId': endpointId,
      'messageJson': messageJson,
    });
  }

  Future<void> disconnect({required String endpointId}) {
    return _methodChannel.invokeMethod<void>('disconnect', {
      'endpointId': endpointId,
    });
  }

  Future<void> stopAll() {
    return _methodChannel.invokeMethod<void>('stopAll');
  }
}

int? _readInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  if (value is String) {
    return int.tryParse(value);
  }
  return null;
}
