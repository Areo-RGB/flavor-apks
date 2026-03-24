Addressed client-side persistent 'Clock sync lock is invalid' condition by relaxing NTP hard RTT acceptance threshold in RaceSessionController.

Changes:
- lib/features/race_session/race_session_controller.dart
  - `_maxClockSyncRttNanos` changed from 100_000_000 (100ms) to 250_000_000 (250ms).
  - This keeps low-RTT preference behavior (`_targetClockSyncRttNanos` remains 20ms, second burst still used when first best >20ms) while reducing complete-lockout cases on real Nearby links.

Tests updated:
- test/race_session_controller_test.dart
  - Renamed test: "client trigger is rejected when clock sync RTT exceeds 250ms".
  - Increased synthetic rejected RTT sample from 130ms to 280ms so rejection coverage still matches the new hard cap.

Verification:
- flutter analyze lib/features/race_session/race_session_controller.dart test/race_session_controller_test.dart
- flutter test test/race_session_controller_test.dart test/race_session_screen_test.dart
All passed.

Device deployment:
- Built debug APK and installed/launched via scripts/install-debug-apk.mjs on connected devices:
  - 31071FDH2008FK
  - 4c637b9e
  - DMIFHU7HUG9PKVVK
