User reported split role failing while same device worked with start/stop roles.

Potential role-specific blocker fixed:
- Removed runtime split gating in `_applyRoleEvent`:
  - Deleted `if (role == SessionDeviceRole.split && !canShowSplitControls) return;`
- Rationale:
  - Split assignment is already constrained at assignment time.
  - During monitoring, `canShowSplitControls` depends on dynamic `totalDeviceCount`; transient count changes could silently drop split events while start/stop still process.

Regression test added:
- test/race_session_controller_test.dart
  - `split role still records after total device count drops during monitoring`
  - Covers scenario where split device remains and another device disconnects; split trigger must still record.

Verification:
- flutter test test/race_session_controller_test.dart (pass)
- flutter analyze lib/features/race_session/race_session_controller.dart test/race_session_controller_test.dart (pass)

Deployment:
- Built debug APK and installed/launched on Pixel + OnePlus via adb.
