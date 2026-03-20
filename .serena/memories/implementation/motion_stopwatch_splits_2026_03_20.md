Implemented local-first motion stopwatch and sprint split flow in Flutter app.

What changed:
- Motion models (`lib/features/motion_detection/motion_detection_models.dart`):
  - Added `MotionRunSnapshot` for active run state (active flag, startedAtMicros, elapsedMicros, splitMicros).
  - Added formatting helpers: `formatDurationMicros(int)` -> `X.XXs`, and `formatSplitLabel(int)`.
  - Kept detection engine trigger logic unchanged (start then unlimited splits).
- Motion controller (`lib/features/motion_detection/motion_detection_controller.dart`):
  - Added local stopwatch source-of-truth state (`_runSnapshot`, `_lastRun`) and public readonly getters for UI.
  - Added `ingestTrigger(MotionTriggerEvent)` to centralize trigger handling.
  - Start trigger initializes run + starts periodic ticker; split triggers append elapsed split times.
  - Persists `LastRunResult` locally on start/split via `LocalRepository`, independent of Nearby role.
  - Loads motion config + last run on startup.
  - `resetRace()` now clears active run/splits and trigger history, and stops ticker.
- Motion screen (`lib/features/motion_detection/motion_detection_screen.dart`):
  - Added stopwatch-first UI with large timer display, status, split count.
  - Added current run split list in seconds.
  - Added last saved run section with formatted splits.
  - Kept preview and detection controls.
  - Moved tuning sliders/live stats/trigger diagnostics into `Advanced Detection` expansion section.
- Tests:
  - Added controller tests in `test/motion_detection_controller_test.dart` covering start/ticker, split persistence, reset behavior, startup load of saved run.
  - Updated `test/motion_detection_settings_widget_test.dart` for new UI and added assertions for timer/status/current and saved split rendering.

Verification:
- `flutter test`: all tests passed.
- `flutter analyze`: no issues found.

Notes:
- Nearby/race sync protocol and wiring were left unchanged as requested.