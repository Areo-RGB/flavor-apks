Reduced monitoring live preview size by at least 66%.

Change:
- Updated `MotionDetectionScreen._buildPreviewCard` to wrap preview card in:
  - `Align(alignment: Alignment.centerLeft)`
  - `FractionallySizedBox(widthFactor: 0.34)`
- This reduces preview width to ~34% of available width (66%+ reduction) while preserving:
  - Android native preview rendering
  - Tripwire marker overlay behavior and ROI tracking

Validation:
- `flutter test test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart` passed.