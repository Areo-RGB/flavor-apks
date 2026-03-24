Implemented NTP sync policy update in RaceSessionController:

Behavior changes:
- Reduced max accepted RTT from 400ms to 100ms (`_maxClockSyncRttNanos = 100_000_000`).
- Added target RTT threshold for additional optimization burst (`_targetClockSyncRttNanos = 20_000_000`).
- Added adaptive second burst: after first burst, if best RTT is >20ms, run another 10-request burst and keep best sample.
- Added burst-state guards to avoid overlapping sync attempts (`_clockSyncInProgress`).
- Added per-burst counters for response/high-RTT reject accounting.
- Added lock warning detail passthrough: when `_errorText` starts with `Clock sync failed:`, warning banner includes the failure reason.

Implementation details:
- Refactored sync into `_runClockSyncBurst(...)` helper returning best RTT + counters.
- Burst wait timeout now uses real elapsed wall-time via `Stopwatch`, not injected clock, so tests and runtime are robust even when test clock is static.
- High-RTT rejects increment `_activeClockSyncBurstHighRttRejectCount`.
- Clock sync state cleanup now also resets in-flight/counter state.

Tests updated:
- Updated rejection test name/semantics to 100ms cap.
- Added test: `client runs second sync burst when first best RTT is above 20ms target`.
- Existing race session and screen tests pass.

Verification:
- `dart analyze lib/features/race_session/race_session_controller.dart test/race_session_controller_test.dart` PASS
- `flutter test test/race_session_controller_test.dart test/race_session_screen_test.dart` PASS