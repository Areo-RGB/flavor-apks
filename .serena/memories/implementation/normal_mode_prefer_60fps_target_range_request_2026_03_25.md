Updated native normal camera policy to prefer requesting an FPS range with upper=60 when available.

Files changed:
- android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt
- android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt

Implementation details:
- In SensorNativeCameraSession.applyUnlockedPolicy, replaced selectHighestFrameRateRange(...) with selectPreferredNormalFrameRateRange(...).
- Added SensorNativeCameraPolicy.NORMAL_TARGET_FPS_UPPER = 60.
- Added selectPreferredNormalFrameRateRange(ranges) and selectPreferredNormalFrameRateBounds(bounds):
  - Prefer ranges where upper == 60; among those choose highest lower bound.
  - Fallback to selectHighestFrameRateBounds when no upper=60 range exists.

Tests added/updated:
- selectPreferredNormalFrameRateBounds prefers upper 60 with highest lower.
- selectPreferredNormalFrameRateBounds falls back to highest when 60 unavailable.
- selectPreferredNormalFrameRateBounds returns null for null or empty.

Formatting/verification:
- Ran `dart format` on changed Dart files in worktree (0 changed).
- Attempted targeted Android unit test run for SensorNativeMathTest, but Gradle wrapper is unavailable in this checkout (`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`).