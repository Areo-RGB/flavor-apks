Implemented cross-device stopwatch start/split mirroring so client motion timers start when host starts.

Changes:
- Motion controller (`lib/features/motion_detection/motion_detection_controller.dart`):
  - Extended `ingestTrigger` with optional named arg `forwardToSync` (default true).
  - Remote-injected triggers can now update local stopwatch state without re-broadcasting to race sync (`forwardToSync: false`).
- Race sync controller (`lib/features/race_sync/race_sync_controller.dart`):
  - Added optional constructor callback `onRemoteTrigger`.
  - On client role, when `payload_received` decodes `race_started`/`race_split`, emits synthesized `MotionTriggerEvent` via callback.
  - Trigger timestamps are reconstructed from payload/session data so stopwatch timing matches host events.
- App wiring (`lib/main.dart`):
  - Wired `RaceSyncController(onRemoteTrigger: ...)` to feed remote triggers into `MotionDetectionController.ingestTrigger(..., forwardToSync: false)`.
  - This ensures connected clients start and update stopwatch immediately from host payloads.
- Tests:
  - Added race sync test in `test/race_sync_controller_test.dart`:
    - `client forwards host payloads as motion triggers`
    - verifies remote start + split payloads produce expected motion trigger events/microsecond timestamps.

Verification:
- `flutter test`: all tests passed.
- `flutter analyze`: no issues found.