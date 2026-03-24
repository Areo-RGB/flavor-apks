Root cause and fix for Pixel 7 FPS Test mode showing NORMAL 60 instead of HS 120.

Root cause observed:
- Pixel camera metadata supports constrained HS at 1280x720 with 30-120 / 120-120.
- Runtime logs showed constrained-HS attempts but fallback to normal session with vendor messages indicating unsupported stream combinations involving YUV/basic capture path.
- Test mode still used ImageReader YUV stream even with analysis disabled, which interfered with constrained HS path.

Implemented fix:
1) SensorNativeCameraSession now accepts `analysisEnabled` in `bindAndConfigure` and all internal rebind/fallback paths.
2) For `analysisEnabled=false` (FPS test mode):
   - does NOT create YUV ImageReader/output surface,
   - uses preview surface only for camera outputs.
3) Added headless preview fallback surface (`SurfaceTexture(0)`) when no TextureView is attached, so local FPS test can run without visible preview and still provide required camera output surface.
4) Added Camera2 capture timestamp callback path:
   - `SensorNativeCameraSession` emits `onCaptureTimestamp` from `TotalCaptureResult.SENSOR_TIMESTAMP` on repeating requests/bursts when analysis is disabled.
   - `SensorNativeController` handles these timestamps in `onCaptureTimestamp(...)` to update FPS telemetry and emit `native_frame_stats` without analysis.
5) Kept detection path unchanged when `analysisEnabled=true` (still uses YUV + analyzer).
6) Kept low-FPS HS auto-downgrade disabled in analysis-disabled test mode (errors-only fallback behavior unchanged).

Validation:
- Android compile: `:app:compileDebugKotlin` successful.
- Dart tests: `flutter test test/motion_detection_controller_test.dart test/race_session_screen_test.dart` successful.
- Kotlin unit tests: `:app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest` successful.