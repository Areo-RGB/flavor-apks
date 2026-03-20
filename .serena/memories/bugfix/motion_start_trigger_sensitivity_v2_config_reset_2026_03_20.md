Implemented fix for timer not starting due to missing motion START triggers.

Root cause:
- Motion detector defaults and trigger gate were too strict for short real-world crossing events, so MotionDetectionEngine often emitted no START, leaving stopwatch/timer untouched.

Changes made:
- lib/features/motion_detection/motion_detection_models.dart
  - MotionDetectionConfig.defaults threshold: 0.08 -> 0.04
  - MotionDetectionConfig.defaults processEveryNFrames: 2 -> 1
  - Trigger gate in MotionDetectionEngine.process: _aboveCount >= 3 -> >= 2
- lib/core/repositories/local_repository.dart
  - Motion config key bumped: motion_detection_config_v1 -> motion_detection_config_v2
  - This forces fresh defaults and intentionally ignores legacy v1 config for all existing installs.
- test/motion_detection_engine_test.dart
  - Updated START and SPLIT tests to validate 2-frame trigger gate.
  - Added cooldown/re-arm regression test: rapid post-rearm trigger inside cooldown is blocked.
- test/local_repository_test.dart (new)
  - Added migration/reset test proving v1-only stored config is ignored and v2 defaults load.

Verification:
- flutter test test/local_repository_test.dart test/motion_detection_engine_test.dart test/motion_detection_controller_test.dart test/motion_detection_settings_widget_test.dart -> all passed.
- flutter analyze -> no issues.

Notes:
- Timer and race-sync logic were intentionally left unchanged.
- There is an unrelated existing modification in package.json in the worktree, left untouched per user instruction.