Implemented HS120 in-memory ROI-luma pipeline end-to-end.

Native:
- Added HsRecordingPolicy constants (10s @ 120fps, stride=4, window=250ms), HsRoiRecordingBuffer ring buffer, and refinement request/result models.
- SensorNativeController now records every HS readback frame in-memory, analyzes only every 4th HS frame for live detection, exposes method channel action refineHsTriggers, and clears HS recorder on run reset/stop/restart.
- Added HsPostRaceRefiner (windowed scan around provisional timestamps using frame-to-frame ROI diff, EMA baseline, effective score, first-threshold-crossing fallback).
- Added constrained HS surface guard in Camera2HsSessionManager for >1 surface attempts.

Flutter:
- NativeSensorBridge now supports refineHsTriggers().
- MotionDetectionController/models now include HS refinement lifecycle (idle/running/done/error), refinement request/result parsing, and provisional trigger timestamp tracking.
- Race session models include snapshot runId and SessionTriggerRefinementMessage (type trigger_refinement).
- RaceSessionController now tracks runId, keeps internal absolute host trigger timestamps, derives timeline from absolutes, runs local host refinement before stop, runs client refinement on host monitoring-off snapshot before stopping local capture, maps refined local timestamps to host domain using stored provisional deltas, sends trigger_refinement payloads, and applies host-side refinements only when runId and provisional role/index match.
- RaceSessionScreen shows compact refinement status in lobby + monitoring.

Tests added/updated for:
- Kotlin HS buffer/stride/refiner behavior.
- Motion controller refinement lifecycle + parsing.
- Race model runId/refinement payload serialization.
- Race controller host/client refinement flows including stale runId rejection and host-domain mapping.
- Race screen refinement status + timeline auto-update rendering.