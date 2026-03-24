Implemented greenfield HS backend while keeping CameraX normal path intact.

Scope:
- Added NativeCameraFpsMode enum (NORMAL, HS120) and NativeMonitoringConfig.highSpeedEnabled (default false).
- Added RoiFrameDiffer.scorePrecroppedLuma and SensorNativeFpsMonitor in SensorNativeMath.
- Added GlLumaExtractor (new file): dedicated GL thread, SurfaceTexture camera input, transform matrix update per frame, headless frame consume callback, async readback request API, double-buffered byte handoff.
- Added Camera2HsSessionManager (new file): Camera2 constrained high-speed session lifecycle, dynamic HS size/fps selection, single-surface HS session output via GlLumaExtractor surface, error/disconnect cleanup + callback.
- Reworked SensorNativeController routing: CameraX for NORMAL, Camera2HsSessionManager for HS, backpressure gate before HS readback, telemetry includes cameraFpsMode/targetFpsUpper/observedFps, onHostResumed restart behavior.
- MainActivity now forwards onResume to SensorNativeController.onHostResumed.
- Race/motion Dart models/controllers now include highSpeedEnabled and propagate per-device setting through snapshots and local monitor config.
- Race session UI adds HS toggle/state chip in lobby row.

Testing:
- Kotlin: ./gradlew :app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest (pass)
- Kotlin compile: ./gradlew :app:compileDebugKotlin (pass)
- Flutter: flutter test test/motion_detection_controller_test.dart test/race_session_models_test.dart test/race_session_controller_test.dart test/race_session_screen_test.dart (pass)

Notable fix during verification:
- JVM unit tests cannot call android.util.Range hashCode/getLower in local unit environment. Added SensorNativeCameraPolicy.selectPreferredHsBounds (Pair-based) and switched unit test to validate bounds logic there; selectPreferredHsRange now wraps bounds helper for runtime Range conversion.
