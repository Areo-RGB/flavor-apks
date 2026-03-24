User reported Pixel NORMAL mode FPS display became jumpy after HS additions.

Root cause:
- Flutter now prefers native `observedFps` from `native_frame_stats`.
- In NORMAL mode, direct assignment of native samples can produce visible UI swings from frame pacing noise/outliers.

Fix implemented:
- File: lib/features/motion_detection/motion_detection_controller.dart
- Added NORMAL-mode display smoothing for native observed FPS:
  - EMA alpha 0.10
  - Per-frame max step clamp ±2.5 fps
- HS mode behavior unchanged: still uses native observed FPS directly (no extra smoothing), preserving HS diagnostics/fallback responsiveness.

Tests:
- Added test in test/motion_detection_controller_test.dart:
  - `native frame stats smooths large normal-mode observed FPS swings`
- Verified:
  - flutter test test/motion_detection_controller_test.dart
  - flutter test test/motion_detection_settings_widget_test.dart
  - flutter analyze lib/features/motion_detection/motion_detection_controller.dart test/motion_detection_controller_test.dart

Deployment:
- Built debug APK and installed/launched on connected devices:
  - 31071FDH2008FK
  - 4c637b9e
  - DMIFHU7HUG9PKVVK
