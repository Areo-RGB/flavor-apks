Implemented CPH2399-only normal-path vendor-tag FPS experiment (no constrained high-speed session).

Files changed:
- android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt
- android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt
- lib/features/motion_detection/motion_detection_controller.dart
- lib/features/motion_detection/motion_detection_screen.dart
- android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt
- test/motion_detection_controller_test.dart
- test/motion_detection_settings_widget_test.dart

Behavior:
- Gate workaround by Build.MODEL==CPH2399 and rear-facing only.
- Attempt vendor-tag profiles in fixed order; first successful sustained FPS profile wins.
- One-shot/cooldown behavior prevents continuous thrashing.
- Emit native_diagnostic events for attempt start/result/selected/failure-all.
- Include vendor profile/fallback telemetry in native_frame_stats.
- Flutter parses diagnostics and appends compact profile/fallback status suffix.

Validation:
- flutter test (controller + settings widget) passed.
- gradlew :app:testDebugUnitTest (SensorNativeMathTest) passed.