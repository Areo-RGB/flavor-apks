Reverted Monitoring preview UI path to the pre-size-reduction implementation after black-screen regression report on OnePlus.

What was reverted in `lib/features/motion_detection/motion_detection_screen.dart`:
- Restored `_buildPreviewCard` from reduced `FractionallySizedBox(widthFactor: 0.34)` container back to full `Card` with `clipBehavior: Clip.antiAlias` and same 9:16 aspect stack.
- Restored `_buildPreviewSurface` from `PlatformViewLink + AndroidViewSurface` back to direct `AndroidView` embedding with `creationParams` and `StandardMessageCodec`.
- Removed temporary imports added for hybrid composition (`flutter/gestures.dart`, `flutter/rendering.dart`).

Validation:
- `flutter test test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart` passed.
- Reverted temporary test mock adjustments too, tests still pass.

Net effect:
- Monitoring preview code is now back to the known working state prior to preview-size reduction/hybrid-composition experiments.