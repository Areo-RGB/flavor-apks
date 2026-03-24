Implemented HS-mode preview UX lock in race monitoring screen.

Changes:
- lib/features/race_session/race_session_controller.dart
  - Added getter `localHighSpeedEnabled` based on local session device assignment.

- lib/features/race_session/race_session_screen.dart
  - Monitoring scaffold now derives:
    - `previewAvailable = !controller.localHighSpeedEnabled`
    - `effectiveShowPreview = previewAvailable && _showPreview`
  - Preview switch behavior:
    - Value forced off in HS (`effectiveShowPreview`)
    - `onChanged` set to null in HS, so switch is greyed/disabled
    - Added helper label `Disabled in HS` with key `monitoring_preview_disabled_text`
  - MotionDetectionScreen now receives `showPreview: effectiveShowPreview`, so preview card/line are hidden in HS.

Tests:
- test/race_session_screen_test.dart
  - Added widget test: `monitoring stage disables preview toggle and hides preview in HS mode`
  - Asserts switch is present, off, disabled, helper label visible, and preview card/tripwire absent.

Verification:
- flutter test test/race_session_screen_test.dart
- flutter test test/race_session_controller_test.dart
- flutter analyze lib/features/race_session/race_session_screen.dart lib/features/race_session/race_session_controller.dart test/race_session_screen_test.dart
All passed.

Device deployment:
- Built and installed debug APK via scripts/install-debug-apk.mjs on connected devices:
  - 31071FDH2008FK
  - 4c637b9e
  - DMIFHU7HUG9PKVVK
