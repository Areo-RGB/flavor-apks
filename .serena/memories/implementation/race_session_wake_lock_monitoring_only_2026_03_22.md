Implemented wake lock integration for race sessions so screen stays awake only during SessionStage.monitoring on both host and clients.

Changes made:
- Added dependency: wakelock_plus ^1.5.1 in pubspec.yaml (pubspec.lock updated via flutter pub add).
- Added core bridge: lib/core/services/wake_lock_bridge.dart with WakeLockBridge.enable/disable/toggle wrappers around WakelockPlus.
- Updated RaceSessionController:
  - Added optional constructor injection: WakeLockBridge? wakeLockBridge.
  - Added internal wake-lock state (_wakeLockEnabled) and idempotent helper _setWakeLockEnabled(enabled, force:false) with best-effort error handling.
  - Enabled wake lock when monitoring starts on host (startMonitoring) and when client snapshot transitions into monitoring (!wasMonitoring && _monitoringActive in _onPayload).
  - Disabled wake lock when monitoring stops on host (stopMonitoring) and when client snapshot transitions out of monitoring (wasMonitoring && !_monitoringActive in _onPayload).
  - Converted _resetSession to async and force-releases wake lock there; updated createLobby/joinLobby to await _resetSession.
  - Added dispose safety release via unawaited(_setWakeLockEnabled(false, force:true)).
- Extended tests in test/race_session_controller_test.dart:
  - host startMonitoring enables wake lock once
  - host stopMonitoring disables wake lock
  - client snapshot transition to monitoring enables wake lock
  - client snapshot transition out of monitoring disables wake lock
  - dispose releases wake lock when active
  - reset session path releases wake lock from active monitoring
  - Added _FakeWakeLockBridge and fixture injection.

Validation:
- Ran dart format on changed Dart files.
- Ran flutter test test/race_session_controller_test.dart: all tests passed.

Note:
- Attempted to run `gitnexus wiki` per AGENTS/project instructions, but command failed due missing OPENAI_API_KEY or GITNEXUS_API_KEY in environment.