import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/repositories/local_repository.dart';

void main() {
  test('ignores legacy v1 motion config and falls back to v2 defaults', () async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      'motion_detection_config_v1': jsonEncode(<String, Object>{
        'threshold': 0.2,
        'roiCenterX': 0.6,
        'roiWidth': 0.2,
        'cooldownMs': 1200,
        'processEveryNFrames': 5,
      }),
    });

    final repository = LocalRepository();
    final config = await repository.loadMotionConfig();

    expect(config.threshold, 0.04);
    expect(config.roiCenterX, 0.5);
    expect(config.roiWidth, 0.12);
    expect(config.cooldownMs, 900);
    expect(config.processEveryNFrames, 1);
  });
}
