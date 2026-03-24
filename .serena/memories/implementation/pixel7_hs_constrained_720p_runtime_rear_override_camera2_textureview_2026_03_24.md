Implemented Pixel 7 HS upgrade by migrating native monitoring camera stack from CameraX Preview+ImageAnalysis to Camera2 TextureView + ImageReader backend.

Key Android native changes:
- Replaced SensorNativeCameraSession with Camera2 session manager that supports:
  - NORMAL mode: 640x480 session.
  - HS mode: constrained high-speed session at 1280x720.
- HS policy:
  - Select camera by requested facing first.
  - If HS requested and selected facing lacks HS but rear supports it, runtime force rear only for this native run (no model/session assignment mutation).
  - HS FPS range preference: 120-120, then 30-120.
  - On constrained-HS create/request failures, fallback automatically to NORMAL mode with diagnostic.
- Added diagnostics:
  - hs_forced_rear_runtime
  - hs_constrained_started
  - hs_fallback_normal
- Image ingestion now uses ImageReader callbacks and feeds existing ROI/frame-diff detection path.
- Existing event payload continuity preserved: native_frame_stats still includes observedFps, cameraFpsMode, targetFpsUpper.

Controller/view migration:
- Updated SensorNativeController to remove CameraX/ProcessCameraProvider/ImageAnalysis usage.
- Controller now binds/rebinds Camera2 session directly and processes android.media.Image frames.
- Updated preview surface type from PreviewView to TextureView.
- Updated SensorNativePreviewPlatformView to host TextureView and notify controller on surface lifecycle.

Policy/test support:
- Added SensorNativeCameraPolicy.shouldLockAeAwb() helper (for existing tests).
- SensorNativeMathTest now passes for:
  - HS 30-120 preference fallback path.
  - Runtime HS facing override policy.

Verification:
- Android Kotlin compile: success.
- Kotlin unit test suite target (SensorNativeMathTest): success.
- Flutter regression tests:
  - race_session_models_test
  - motion_detection_controller_test
  - race_session_screen_test
  - race_session_controller_test
  all passed.

Notes:
- Camera2 deprecated warnings remain for createCaptureSession/createConstrainedHighSpeedCaptureSession legacy overloads used in current min-API compatible implementation.