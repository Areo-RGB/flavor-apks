Reviewed and merged branch cursor/monitoring-motion-detection-timer-2760 into master via fast-forward (commit range 59c38db..abd0a03).

Diff summary:
- motion_detection_controller.dart: avoid duplicate trigger processing by early-return if onTrigger callback already updated runSnapshot.
- motion_detection_models.dart: resetRace() now clears baseline.
- Added tests covering duplicate split prevention and baseline reset behavior.
- pubspec.lock transitive dependency updates.

Validation performed:
- flutter test test/motion_detection_controller_test.dart test/motion_detection_engine_test.dart => pass.
- flutter test test/motion_detection_engine_test.dart => pass.
- flutter test test/local_repository_test.dart fails on both feature branch and master with expected 0.04 vs actual 0.006 in legacy v1 config test; pre-existing unrelated failure.

Merged with: git merge --ff-only origin/cursor/monitoring-motion-detection-timer-2760
Pushed: origin/master updated to abd0a03.