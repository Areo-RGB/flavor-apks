Implemented separate HS recording mode (no live HS analysis) and aligned app/tests.

Key behavior:
- Live monitoring always forces highSpeedEnabled=false and remains normal mode.
- Added dedicated recording workflow: host starts/stops high-speed recording; clients follow snapshot recordingActive transitions.
- Offline analysis per role after recording stop via native analyzeHighSpeedRecording bridge.
- Role scan direction:
  - start/split => forward
  - stop => backward
- Host aggregates SessionRecordingAnalysisResultMessage payloads and rebuilds timeline from host sensor timestamps.
- Added recording status lifecycle text and recording stage UI with Start Recording button in lobby.

Native:
- Added Camera2HsRecordingManager (single-surface constrained HS + MediaCodec H.264 + MediaMuxer MP4).
- Added HsRecordedVideoAnalyzer with ROI-luma decode path and directional threshold selection.
- Updated analyzer to compute EMA/effective score in requested traversal order, so backward stop scan uses reverse traversal baseline.

UI correction display fix:
- Post-Race Analysis now shows rows only for changed impacts; when no deltas exist, displays "No correction deltas recorded yet."

Tests:
- Updated Flutter tests to match separate recording architecture.
- Added/updated controller tests for backward stop scan direction usage and recording-analysis aggregation path.
- Added Kotlin tests for backward threshold selector latest-crossing behavior and unresolved-no-crossing case.

Verification run:
- android/gradlew.bat :app:compileDebugKotlin ✅
- android/gradlew.bat :app:testDebugUnitTest --tests com.paul.sprintsync.sensor_native.SensorNativeMathTest ✅
- flutter test test/motion_detection_controller_test.dart test/race_session_models_test.dart test/race_session_controller_test.dart test/race_session_screen_test.dart ✅