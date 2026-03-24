Investigated HS FPS volatility report. Root cause found in Flutter MotionDetectionController: FPS UI value was being recomputed from successive native_frame_stats frameSensorNanos deltas (processed-frame cadence), which can skip frames in HS/backpressure and produce large swings. Native side already emits smoothed observedFps from SensorNativeFpsMonitor based on stream timestamps.

Fix:
- Updated lib/features/motion_detection/motion_detection_controller.dart native_frame_stats handling to prefer event['observedFps'] when present/valid.
- Kept previous timestamp-based fallback only when native observedFps is absent.

Test coverage:
- Added test in test/motion_detection_controller_test.dart: 'native frame stats prefers native observed FPS when provided'.
- Ran flutter test test/motion_detection_controller_test.dart (pass).

Device/build:
- Built debug APK successfully.
- Installed/launched on Pixel 7 and CPH2399 successfully after patch.
