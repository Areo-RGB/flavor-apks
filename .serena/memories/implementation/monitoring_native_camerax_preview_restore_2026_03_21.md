Restored live camera preview feed in monitoring using native CameraX + Flutter Android platform view.

Changes made:
- Native CameraX pipeline now supports optional preview + analysis simultaneously in `SensorNativeController`.
  - Added `Preview` use case integration.
  - Added preview attach/detach hooks from platform view lifecycle.
  - Rebinds camera use cases when preview surface appears/disappears during active monitoring.
  - Added shared platform view type constant `com.paul.sprintsync/sensor_native_preview`.
- Added Android platform view wrappers under `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native`:
  - `SensorNativePreviewViewFactory.kt`
  - `SensorNativePreviewPlatformView.kt` (hosts `PreviewView`, attaches/detaches to controller)
- Registered native preview view factory in `MainActivity.configureFlutterEngine`.
- Added `androidx.camera:camera-view:1.5.3` dependency to app build config.
- Dart bridge now exposes shared preview type constant via `NativeSensorBridge.previewViewType`.
- Monitoring UI now re-enables preview:
  - `RaceSessionScreen` passes `showPreview: true` in monitoring stage.
  - `MotionDetectionScreen` renders Android `AndroidView` preview surface + minimal vertical marker overlay that tracks ROI center (`roiCenterX`).
  - Non-Android fallback message added.
  - `initializeCamera` call moved to post-frame callback to avoid notify-during-build in widget tests.

Tests updated:
- `motion_detection_settings_widget_test.dart`
  - Replaced old disabled-preview assertion.
  - Added marker-present + ROI tracking assertion.
  - Added hidden-preview assertion for `showPreview: false`.
  - Added platform_views channel mock handler for AndroidView in tests.
- `race_session_screen_test.dart`
  - Added monitoring-stage test that verifies preview marker appears after host assigns required roles and starts monitoring.
  - Added platform_views channel mock handler.

Verification:
- `flutter analyze lib test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart` passes.
- Focused widget tests pass:
  - `flutter test test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart`
- Android build passes:
  - `android/gradlew.bat assembleDebug`

Repository-wide pre-existing failures remain unrelated to this change:
- `flutter analyze` fails due missing legacy `race_sync` test imports/symbols.
- `flutter test` fails due those same race_sync tests and an existing `local_repository_test` expectation mismatch (threshold default assertion).