Implemented one-time split trigger gating per run in race session flow.

Changes:
- Updated `RaceSessionController._applyRoleEvent` (`lib/features/race_session/race_session_controller.dart`).
- In split-role branch, added guard `if (_timeline.splitElapsedNanos.isNotEmpty) return;` after existing running/start checks.
- Behavior now: first split in a run is accepted; subsequent split triggers in the same run are silently ignored.
- Reset/new run behavior unchanged: `resetRun()`/new start clears timeline so split allowance resets per run.

Tests added in `test/race_session_controller_test.dart`:
- `split role only records first split per run`
- `host ignores duplicate split trigger requests from client`
- `split allowance resets on new run after reset`

Verification:
- `flutter test test/race_session_controller_test.dart` passed.
- `flutter test test/motion_detection_controller_test.dart` passed.

Notes:
- GitNexus symbol-level impact lookup for `_applyRoleEvent` did not resolve in current index; file-level impact for `race_session_controller.dart` returned LOW risk.
- Worktree had unrelated pre-existing changes in multiple files before/while implementing; this change was scoped to race-session split gating and related tests.