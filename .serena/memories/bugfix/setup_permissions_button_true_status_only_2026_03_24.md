Fixed setup-screen Permissions button visibility by initializing and parsing permission status correctly.

Issue:
- `_permissionsGranted` started as false and only updated after explicit permission request/event, so button could appear despite already-granted permissions.

Changes:
1) MainActivity.kt:
- Added nearby method-channel handler `getPermissionStatus` that returns:
  - granted: deniedPermissions().isEmpty()
  - denied: deniedPermissions()

2) nearby_bridge.dart:
- Added `getPermissionStatus()` method for passive status query.
- Includes safe fallbacks for MissingPlugin/unimplemented.

3) race_session_controller.dart:
- Constructor now triggers passive startup sync: `unawaited(_refreshPermissionStatusFromPlatform())`.
- Added `_refreshPermissionStatusFromPlatform()` to update `_permissionsGranted` and notify listeners.
- Added robust `_isPermissionsGranted(Map)` parser:
  - uses explicit `granted` bool if present
  - falls back to `denied.isEmpty` when available.
- Updated `requestPermissions()` and `permission_status` event handling to use the parser.

Result:
- Permissions button now appears only when required permissions are actually missing.

Verification:
- dart analyze on touched files/tests: PASS.
- flutter test race session screen/controller tests: PASS.
- Android unit task compile/test pass (including MainActivity changes in build).