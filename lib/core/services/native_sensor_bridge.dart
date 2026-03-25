import 'package:flutter/services.dart';

class NativeSensorBridge {
  static const String previewViewType =
      'com.paul.sprintsync/sensor_native_preview';
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

  Future<void> startNativeMonitoring({required Map<String, dynamic> config}) {
    return _methodChannel.invokeMethod<void>('startNativeMonitoring', {
      'config': config,
    });
  }

  Future<void> stopNativeMonitoring() {
    return _methodChannel.invokeMethod<void>('stopNativeMonitoring');
  }

  Future<void> updateNativeConfig({required Map<String, dynamic> config}) {
    return _methodChannel.invokeMethod<void>('updateNativeConfig', {
      'config': config,
    });
  }

  Future<void> resetNativeRun() {
    return _methodChannel.invokeMethod<void>('resetNativeRun');
  }

  Future<void> warmupGpsSync() async {
    try {
      await _methodChannel.invokeMethod<void>('warmupGpsSync');
    } on MissingPluginException {
      // Non-Android platforms or tests may not expose native sensor methods.
    } on PlatformException catch (error) {
      if (error.code == 'unimplemented') {
        return;
      }
      rethrow;
    }
  }

  Future<Map<String, dynamic>> refineHsTriggers({
    required List<Map<String, dynamic>> requests,
  }) async {
    try {
      final response = await _methodChannel.invokeMethod<dynamic>(
        'refineHsTriggers',
        {'requests': requests},
      );
      if (response is Map) {
        return Map<String, dynamic>.from(response);
      }
      return <String, dynamic>{'results': <dynamic>[], 'recordedFrameCount': 0};
    } on MissingPluginException {
      return <String, dynamic>{'results': <dynamic>[], 'recordedFrameCount': 0};
    } on PlatformException catch (error) {
      if (error.code == 'unimplemented') {
        return <String, dynamic>{
          'results': <dynamic>[],
          'recordedFrameCount': 0,
        };
      }
      rethrow;
    }
  }

  Future<Map<String, dynamic>> startHighSpeedRecording({
    required String runId,
    required String cameraFacing,
  }) async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      'startHighSpeedRecording',
      <String, dynamic>{
        'runId': runId,
        'cameraFacing': cameraFacing,
      },
    );
    if (response is Map) {
      return Map<String, dynamic>.from(response);
    }
    return <String, dynamic>{'started': false};
  }

  Future<Map<String, dynamic>> stopHighSpeedRecording() async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      'stopHighSpeedRecording',
    );
    if (response is Map) {
      return Map<String, dynamic>.from(response);
    }
    return <String, dynamic>{'stopped': false};
  }

  Future<Map<String, dynamic>> analyzeHighSpeedRecording({
    required String runId,
    required String triggerType,
    required int splitIndex,
    required String scanDirection,
  }) async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      'analyzeHighSpeedRecording',
      <String, dynamic>{
        'runId': runId,
        'triggerType': triggerType,
        'splitIndex': splitIndex,
        'scanDirection': scanDirection,
      },
    );
    if (response is Map) {
      return Map<String, dynamic>.from(response);
    }
    return <String, dynamic>{
      'runId': runId,
      'triggerType': triggerType,
      'splitIndex': splitIndex,
      'scanDirection': scanDirection,
      'resolved': false,
      'diagnostics': 'No native response.',
    };
  }
}
