Bugfix: prevent camera startup for unassigned race-session roles.

Changes:
- In RaceSessionController, introduced `_localMonitoringCaptureActive` and two helpers:
  - `_startLocalMonitoringCaptureIfAssigned()` starts local monitoring action/native camera only when `localRole != SessionDeviceRole.unassigned`.
  - `_stopLocalMonitoringCaptureIfRunning()` only stops local monitoring if it was started locally.
- Replaced direct start/stop camera calls in both host flow (`startMonitoring`/`stopMonitoring`) and client snapshot transitions (`_onPayload`) with the new helpers.
- Reset `_localMonitoringCaptureActive` during `_resetSession`.

Test updates:
- Strengthened existing host start test to assert start action is called for assigned role.
- Added host test ensuring unassigned local role does not invoke start/stop local monitoring actions.
- Added client snapshot test ensuring unassigned local role does not invoke local monitoring action.

Verification:
- `flutter test test/race_session_controller_test.dart test/race_session_screen_test.dart` passed.