User requested full removal of CPH2399 vendor-hack experiment from normal CameraX analysis path.

Action taken:
- Restored these files to HEAD baseline (pre-vendor-hack):
  - android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt
  - android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt
  - android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt
  - lib/features/motion_detection/motion_detection_controller.dart
  - lib/features/motion_detection/motion_detection_screen.dart
  - test/motion_detection_controller_test.dart
  - test/motion_detection_settings_widget_test.dart
- Removed temporary local dump artifact created during diagnostics.

Validation:
- flutter test test/motion_detection_controller_test.dart test/motion_detection_settings_widget_test.dart => PASS
- android/gradlew.bat :app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest => PASS