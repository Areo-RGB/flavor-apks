Implemented follow-up fix for persistent OnePlus black monitoring preview:

1) Re-enabled compact preview UI in monitoring
- File: lib/features/motion_detection/motion_detection_screen.dart
- _buildPreviewCard now wraps preview card in Align + FractionallySizedBox(widthFactor: 0.34), keeping marker overlay behavior unchanged.

2) Removed native fallback that could intentionally drop preview
- File: android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt
- On Camera2 options failure, no longer rebinds with includePreview=false.
- New behavior: emit warning and keep preview + camera defaults.

3) Added bounded preview rebind retries to mitigate surface/lifecycle race
- File: android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt
- Added retry constants (200ms delay, 3 attempts), scheduled retries after attach/start when monitoring+previewView+provider are available.
- Added cancel logic on detach/stop/dispose.
- Added warning log line for failed retry attempts.

4) Test update (TDD)
- File: test/motion_detection_settings_widget_test.dart
- Added assertion that compact preview container uses widthFactor 0.34.

Verification:
- Failing test reproduced before UI change (`No element` for FractionallySizedBox) and passed after change.
- flutter test test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart -> passed.
- flutter analyze lib/features/motion_detection/motion_detection_screen.dart test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart -> no issues.
- android/gradlew.bat assembleDebug -> BUILD SUCCESSFUL.
- Installed debug APK on OnePlus: adb install -r build/app/outputs/flutter-apk/app-debug.apk -> Success.
- Fresh screenshot captured after install at C:/Users/paul/projects/photo-finish/tmp_oneplus_adb_screen_after_retry.png (currently on Setup Session screen, not Monitoring yet).