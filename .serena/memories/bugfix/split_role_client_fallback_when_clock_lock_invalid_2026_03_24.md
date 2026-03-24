User reported split role triggers not producing split times while start/stop still worked.

Implemented split-role fallback path to improve reliability when client clock lock is invalid:

Code changes:
- lib/features/race_session/race_session_controller.dart
  - In `onLocalMotionPulse(...)` for client mode:
    - If `mappedHostSensorNanos` is null and local role is `split`, send `SessionTriggerRequestMessage` with `mappedHostSensorNanos: null` instead of dropping the trigger.
    - Keep strict rejection behavior for start/stop roles when clock lock is invalid.
  - In host trigger payload handling (`_onPayload` + `SessionTriggerRequestMessage`):
    - If role is `split` and incoming `mappedHostSensorNanos` is null, use `_estimateLocalSensorNanosNow()` as host fallback timestamp.
    - Existing rejection remains for missing mapped timestamp on non-split roles.

Tests added/updated:
- test/race_session_controller_test.dart
  - Added: `host applies split fallback timestamp when mapped host sensor is missing`.
  - Added: `client split trigger is sent when clock lock is invalid`.

Verification:
- flutter test test/race_session_controller_test.dart (pass)
- flutter analyze lib/features/race_session/race_session_controller.dart test/race_session_controller_test.dart (pass)

Deployment:
- Built debug APK and installed/launched on connected devices via scripts/install-debug-apk.mjs.
