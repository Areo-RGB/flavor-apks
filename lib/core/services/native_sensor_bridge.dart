import 'package:flutter/services.dart';

class NativeSensorBridge {
  static const _methodChannel = MethodChannel(
    'com.paul.sprintsync/sensor_native_methods',
  );
  static const _eventChannel = EventChannel(
    'com.paul.sprintsync/sensor_native_events',
  );

  Stream<Map<String, dynamic>> get events {
    return _eventChannel.receiveBroadcastStream().map((dynamic event) {
      if (event is Map) {
        return Map<String, dynamic>.from(event);
      }
      return <String, dynamic>{
        'type': 'native_error',
        'message': 'Malformed native sensor event',
      };
    });
  }

  Future<void> startNativeMonitoring({
    required Map<String, dynamic> config,
  }) {
    return _methodChannel.invokeMethod<void>('startNativeMonitoring', {
      'config': config,
    });
  }

  Future<void> stopNativeMonitoring() {
    return _methodChannel.invokeMethod<void>('stopNativeMonitoring');
  }

  Future<void> updateNativeConfig({
    required Map<String, dynamic> config,
  }) {
    return _methodChannel.invokeMethod<void>('updateNativeConfig', {
      'config': config,
    });
  }

  Future<void> resetNativeRun() {
    return _methodChannel.invokeMethod<void>('resetNativeRun');
  }
}
