After HS rollback, re-added local monitoring preview toggle and moved live FPS status to non-video UI.

Changes:
- RaceSessionScreen converted to StatefulWidget with local `_showPreview` state and `monitoring_preview_toggle` switch in monitoring header.
- MotionDetectionScreen now renders camera status in Stopwatch card with key `camera_status_text`, format `Camera: {fps} fps · {mode}[ · target N]`; placeholder is `Camera: --.- fps · INIT`.
- MotionDetectionController now tracks observed FPS from frame timestamp deltas (EMA), optional camera mode/target from event fields, and resets FPS state on start/stop.

Tests updated:
- race_session_screen_test verifies preview toggle exists and hides preview while camera status remains visible.
- motion_detection_settings_widget_test checks INIT placeholder and runtime camera status updates with preview hidden.
- motion_detection_controller_test checks observed FPS/mode/target parsing from native_frame_stats.

Verification:
- `flutter test test/race_session_screen_test.dart test/motion_detection_settings_widget_test.dart test/motion_detection_controller_test.dart` passed.