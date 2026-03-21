Fixed client stopwatch exploding to very large elapsed values (e.g., ~391k seconds) when host/client device uptimes differ.

Root cause:
- RaceSessionController mixed clock domains:
  - sensor mapping used host/client `sensorMinusElapsedNanos` derived from Android `SystemClock.elapsedRealtimeNanos` domain.
  - clock sync offset (`_hostMinusClientElapsedNanos`) used `_nowElapsedNanos` based on app-local `Stopwatch` domain.
- With different device uptimes, mapping host sensor start into client sensor domain produced invalid local start values and huge elapsed display.

Code changes:
- `lib/features/race_session/race_session_controller.dart`
  - Added `_sensorDerivedElapsedNanos()` from motion controller latest frame + sensor offset.
  - Added `_nowClockSyncElapsedNanos(requireSensorDomainIfMonitoring: ...)` to prefer sensor-derived elapsed domain during monitoring.
  - Updated clock sync request/response handling to use sensor-domain elapsed when monitoring; returns early if required sensor-domain reference is unavailable.
  - Added `_clearClockSyncLock()` helper and used it when monitoring starts on client, on disconnect, and on session reset.
  - Added opportunistic re-sync trigger when monitoring active but lock invalid.
  - Hardened sync update: reject responses where receive timestamp is earlier than send timestamp.
  - Updated clock-lock age validation to use clock-sync elapsed domain and reject negative ages.

Tests:
- `test/race_session_controller_test.dart`
  - Added regression: `client keeps stopwatch elapsed sane when host/client uptimes differ`.
  - Updated existing sync tests to perform sensor-domain clock sync handshake (capture `clock_sync_request`, reply with matching `clientSendElapsedNanos`).

Verification run:
- `flutter test test/race_session_controller_test.dart` ✅
- `flutter test test/race_session_models_test.dart test/race_session_controller_test.dart` ✅
- `flutter analyze lib/features/race_session/race_session_controller.dart test/race_session_controller_test.dart` ✅