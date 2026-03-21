import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:sprint_sync/core/models/app_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

class LocalRepository {
  static const _motionConfigKey = 'motion_detection_config_v2';
  static const _lastRunKey = 'last_run_result_v2_nanos';

  Future<MotionDetectionConfig> loadMotionConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final encoded = prefs.getString(_motionConfigKey);
    if (encoded == null) {
      return MotionDetectionConfig.defaults();
    }
    try {
      final data = jsonDecode(encoded);
      if (data is Map<String, dynamic>) {
        return MotionDetectionConfig.fromJson(data);
      }
    } catch (_) {
      return MotionDetectionConfig.defaults();
    }
    return MotionDetectionConfig.defaults();
  }

  Future<void> saveMotionConfig(MotionDetectionConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_motionConfigKey, jsonEncode(config.toJson()));
  }

  Future<LastRunResult?> loadLastRun() async {
    final prefs = await SharedPreferences.getInstance();
    final encoded = prefs.getString(_lastRunKey);
    if (encoded == null) {
      return null;
    }
    try {
      final data = jsonDecode(encoded);
      if (data is Map<String, dynamic>) {
        return LastRunResult.fromJson(data);
      }
    } catch (_) {
      return null;
    }
    return null;
  }

  Future<void> saveLastRun(LastRunResult run) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_lastRunKey, jsonEncode(run.toJson()));
  }
}
