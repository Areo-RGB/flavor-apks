Implemented CameraX stable-luma + max-FPS policy for native monitoring.

What changed:
- Added native camera session helper: `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt`.
- `SensorNativeController` now delegates camera binding/control to `SensorNativeCameraSession` and remains under 400 lines.
- Camera session now:
  - Selects highest FPS range by highest upper bound then highest lower bound.
  - Applies Camera2 options via `Camera2CameraControl`:
    - Unlocked warm-up: `CONTROL_AE_LOCK=false`, `CONTROL_AWB_LOCK=false`, with target FPS range set.
    - Delayed lock after 400ms: `CONTROL_AE_LOCK=true`, `CONTROL_AWB_LOCK=true`.
  - Uses bind-generation + active-camera identity check to prevent stale delayed lock application after rebind.
  - Cancels pending AE/AWB lock runnable on stop/rebind.
  - Uses max-FPS-first fallback policy:
    - First try preview+analysis when preview exists.
    - If options application fails, rebind analysis-only once and retry.
    - If retry fails, continue with defaults and emit native error events.
- Added policy object `SensorNativeCameraPolicy` (same file) exposing pure helpers for FPS bound selection and lock timing.

Tests:
- Extended tracked test file `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` with policy tests:
  - FPS bounds selection preference
  - null/empty handling
  - AE/AWB warm-up boundary behavior
- New standalone policy test file was removed because project `.gitignore` ignores newly created `android/app/src/test/...` paths; tests were moved into tracked file.

Verification evidence:
- `android/gradlew.bat :app:testDebugUnitTest` passes.
- `android/gradlew.bat :app:assembleDebug` passes.
