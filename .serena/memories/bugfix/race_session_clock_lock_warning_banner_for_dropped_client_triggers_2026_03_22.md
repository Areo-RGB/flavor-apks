Added explicit monitoring-stage clock-lock warning surfaced to clients when local triggers are being dropped due to invalid clock sync.

Changes:
- lib/features/race_session/race_session_controller.dart
  - Added `isClockLockWarningVisible` getter.
  - Added `clockLockWarningText` getter returning: "Clock sync lock is invalid. Triggers from this device are being dropped until sync recovers."
  - Warning visibility is true only for client + monitoring active + connected peer + assigned role + invalid clock lock.
- lib/features/race_session/race_session_screen.dart
  - In monitoring header, added warning banner keyed `clock_lock_warning_banner` when `controller.clockLockWarningText != null`.
  - Banner uses amber styling + warning icon and explains trigger drops.
- test/race_session_controller_test.dart
  - Extended rejection tests to assert warning visibility/text for invalid/no-sync, stale sync, and high RTT scenarios.
- test/race_session_screen_test.dart
  - Added widget test: "monitoring shows warning banner when client clock lock is invalid".

Verification:
- Ran: `flutter test test/race_session_controller_test.dart test/race_session_screen_test.dart`
- Result: all tests passed.

Wiki update:
- Attempted `gitnexus wiki` per AGENTS.md.
- Failed due to missing `OPENAI_API_KEY`/`GITNEXUS_API_KEY` in environment.