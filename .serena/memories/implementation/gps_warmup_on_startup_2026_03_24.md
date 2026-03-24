Added startup GPS warmup so app tries to sync GPS before monitoring starts.

Changes:
- NativeSensorBridge: added warmupGpsSync() method channel call with MissingPluginException/unimplemented-safe fallback.
- MotionDetectionController: calls warmupGpsSync() in constructor (unawaited), and exposes warmupGpsSync() helper with best-effort error swallowing.
- RaceSessionController.requestPermissions(): after granted==true, triggers motionController.warmupGpsSync() so startup attempt is retried once runtime permission is granted.
- SensorNativeController (Kotlin): added method call handler case "warmupGpsSync"; it starts GPS updates via existing startGpsUpdatesIfAvailable() and emits native_state. Also emits native_state on GPS location/provider updates so GPS offset propagates while idle (without camera monitoring).

Verification:
- dart analyze on changed dart files: PASS.
- flutter test test/motion_detection_controller_test.dart: PASS.
- flutter test test/race_session_controller_test.dart: PASS.
- android gradle unit task (:app:testDebugUnitTest --tests SensorNativeMathTest) compilation/tests: PASS.