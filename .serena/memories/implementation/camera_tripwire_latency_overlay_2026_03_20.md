Implemented latency-aware camera tripwire overlay with Nearby probe/pong RTT tracking.

Changes:
- Extended race sync wire model:
  - RaceEventType now includes latencyProbe and latencyPong.
  - RaceEventMessage now carries optional probeId and supports latency probe/pong wire values.
  - Added ConnectionQuality enum (offline/good/warning/bad).
- RaceSyncController latency tracking:
  - Added periodic latency probing (default 1s interval, configurable via constructor for tests).
  - Sends latency_probe payloads to connected peers and handles latency_pong responses.
  - Computes worstPeerLatencyMs across connected peers.
  - Exposes hasConnectedPeers, worstPeerLatencyMs, and derived connectionQuality.
  - Handles latency probe/pong before race event processing to avoid malformed payload logs.
  - Cleans probe/latency state on endpoint lost/disconnect/failure, role switch, stopAll, and dispose.
- MotionDetectionScreen overlay:
  - Added optional raceSyncController dependency and merged listenables so camera tab reacts to race sync changes.
  - Camera preview now renders overlay stack with:
    - status border color
    - vertical tripwire line
  - Tripwire position follows ROI center slider (roiCenterX).
  - Border/line colors map from connection quality:
    - offline -> gray
    - good (<100ms) -> green
    - warning (>=100ms and <=250ms, plus connected-without-sample) -> orange
    - bad (>250ms) -> red
  - Overlay also renders in fallback preview state for consistency.
- App wiring:
  - main.dart now passes RaceSyncController into MotionDetectionScreen.
- Tests:
  - race_sync_models_test: added latency probe/pong roundtrip tests.
  - race_sync_controller_test: added probe->pong handling test and threshold/offline quality transitions test.
  - motion_detection_settings_widget_test: added preview overlay test for tripwire alignment and all color states via stub race sync controller.

Verification:
- flutter analyze: no issues.
- flutter test: all tests passed (23 total).