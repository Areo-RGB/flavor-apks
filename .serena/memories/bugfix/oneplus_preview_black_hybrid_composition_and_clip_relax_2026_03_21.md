Addressed OnePlus black preview issue by switching Flutter embedding path for native preview and relaxing clip constraints.

Context:
- OnePlus CPH2399 showed black preview while monitoring continued.
- Logcat showed repeated BLASTBufferQueue acquire-next-buffer saturation without native monitoring startup/analysis errors.

Changes:
- `MotionDetectionScreen._buildPreviewSurface` switched from `AndroidView` to `PlatformViewLink` + `AndroidViewSurface` using `PlatformViewsService.initSurfaceAndroidView`.
  - This uses a more robust hybrid composition path for Android platform views.
- Preview container clipping adjusted:
  - Replaced clipped `Card` wrapper with `Container` decoration (no hard clipping), while keeping preview visually small via `FractionallySizedBox(widthFactor: 0.34)`.
- Added imports needed for new embedding path (`flutter/gestures.dart`, `flutter/rendering.dart`).
- Updated test platform view mocks (`motion_detection_settings_widget_test.dart`, `race_session_screen_test.dart`) so `platform_views` `resize` method returns size metadata required by `PlatformViewLink` in test environment.

Validation:
- `flutter test test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart` passed.
- `flutter analyze lib/features/motion_detection/motion_detection_screen.dart test/motion_detection_settings_widget_test.dart test/race_session_screen_test.dart` passed.