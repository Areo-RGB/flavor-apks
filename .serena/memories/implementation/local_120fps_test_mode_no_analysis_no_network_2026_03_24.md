Implemented standalone local FPS Test Mode from setup flow.

Scope:
- Added `Test FPS` button in setup and local screen-state branch (`_fpsTestActive`) in `lib/features/race_session/race_session_screen.dart`.
- Entering test mode auto-starts local native camera stream via `MotionDetectionController.startFpsTest()`.
- Exiting test mode stops native stream and returns to setup UI.
- Added monitoring-like test UI with:
  - local status copy (no network required)
  - local preview on/off toggle
  - Android preview surface
  - persistent camera status text: `Camera: {fps} fps · {mode}[ · target {n}]`
- Added `startFpsTest()` / `stopFpsTest()` in `lib/features/motion_detection/motion_detection_controller.dart`.
- Added native config payload helper so all native config updates include backward-compatible `analysisEnabled`.
- `startFpsTest()` forces `highSpeedEnabled=true` and `analysisEnabled=false`.

Native changes:
- Extended `NativeMonitoringConfig` in `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt` with `analysisEnabled` (default true, map parse fallback true).
- In `SensorNativeController`:
  - frame telemetry/FPS is always updated,
  - when `analysisEnabled=false`, analysis/detection path is skipped,
  - emits `native_frame_stats` with neutral scores and current timing/FPS fields,
  - HS low-FPS auto-downgrade is disabled when `analysisEnabled=false` (test mode remains errors-only fallback).

Tests:
- Added controller test to assert `startFpsTest()` sends `highSpeedEnabled=true` and `analysisEnabled=false`.
- Added race session tests for:
  - Test FPS button visibility,
  - entering test mode auto-starts stream,
  - preview toggle local behavior with camera status remaining visible,
  - close exits test mode and stops stream.
- Added native unit tests for `analysisEnabled` defaults/parsing in `SensorNativeMathTest`.
- Verified with:
  - `flutter test test/motion_detection_controller_test.dart test/race_session_screen_test.dart test/motion_detection_settings_widget_test.dart`
  - `android/gradlew.bat :app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest`

Notes:
- Existing monitoring flow (`startDetection`/`stopDetection`) remains unchanged except config payload now consistently includes `analysisEnabled=true`.
- No method-channel breaking changes; only backward-compatible config key addition.