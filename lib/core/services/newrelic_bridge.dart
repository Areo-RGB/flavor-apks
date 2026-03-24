import 'dart:async';

import 'package:newrelic_mobile/newrelic_mobile.dart';

class NewRelicBridge {
  const NewRelicBridge();

  Future<void> recordNearbyEvent({
    required String action,
    required String networkRole,
    required String stage,
    String? endpointId,
    String? endpointName,
    String? serviceId,
    bool? connected,
    int? statusCode,
    String? statusMessage,
    String? errorMessage,
  }) async {
    final attributes = <String, dynamic>{
      'action': action,
      'networkRole': networkRole,
      'stage': stage,
      'endpointId': endpointId,
      'endpointName': endpointName,
      'serviceId': serviceId,
      'connected': connected,
      'statusCode': statusCode,
      'statusMessage': statusMessage,
      'errorMessage': errorMessage,
    };
    attributes.removeWhere((_, value) => value == null);
    try {
      await NewrelicMobile.instance.recordCustomEvent(
        'NearbyConnection',
        eventName: action,
        eventAttributes: attributes,
      );
    } catch (_) {
      // Keep Nearby flow resilient when plugin channels are unavailable (e.g. tests).
    }
  }
}
