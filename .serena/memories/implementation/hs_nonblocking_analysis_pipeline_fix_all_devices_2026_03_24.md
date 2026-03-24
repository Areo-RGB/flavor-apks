Implemented a cross-device HS stability fix in native controller to avoid false HS fallback caused by callback-thread bottlenecks.

Problem identified:
- In Camera2 pipeline, onImageAvailable processing was synchronous on the camera callback thread.
- Heavy ROI/diff work there can throttle frame delivery (especially in HS mode), under-report observed FPS, and trigger fallback to normal even when HS session is valid.

Changes made (SensorNativeController.kt):
- Added dedicated analysis executor:
  - `private val analyzerExecutor = Executors.newSingleThreadExecutor()`
- Added non-blocking latest-frame gating:
  - `private val analysisInFlight = AtomicBoolean(false)`
  - If analysis is busy, incoming Image is dropped immediately (closed), preventing camera-thread blockage and queue buildup.
- Refactored frame handling into two stages:
  1) `updateStreamTelemetry(frameSensorNanos)` runs immediately on callback path for every frame:
     - increments streamFrameCount,
     - updates offset smoother,
     - updates fps monitor and observedFps,
     - triggers HS downgrade decision when criteria met.
  2) `processFrameForDetection(...)` runs asynchronously on analyzer executor only for selected frames.
- Preserved existing `processEveryNFrames` behavior, but now applied before enqueue to analysis thread.
- Ensured cleanup/reset safety:
  - `analysisInFlight.set(false)` on stop/reset path,
  - `analyzerExecutor.shutdownNow()` in dispose,
  - robust image closing in all paths.

Why this is cross-device and not Pixel-specific:
- The fix addresses architecture-level backpressure and callback-thread contention independent of vendor HAL.
- It improves HS viability on any device where analysis work previously throttled frame callback cadence.

Verification:
- `:app:compileDebugKotlin` passed.
- `:app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest` passed.
- Flutter tests passed:
  - motion_detection_controller_test.dart
  - race_session_controller_test.dart

Remaining note:
- Some HAL/device combos may still cap effective analysis-output FPS due hardware stream constraints; this fix removes a major app-side bottleneck so HS decisions are based on stream telemetry from all callback frames rather than analysis throughput.