Implemented temporary two-trigger race mode with synchronized stop events across connected devices.

Behavior change:
- Motion detection now emits START on first trigger and STOP on second trigger.
- Additional detections are ignored until manual reset.
- Timer states now follow: ready -> running -> stopped.
- STOP freezes elapsed time and persists final time in existing LastRunResult format using splitMicros[0].

Motion layer changes:
- MotionTriggerType extended with stop (legacy split still present for compatibility normalization).
- MotionDetectionEngine now tracks runStarted/runStopped and emits start then stop only.
- MotionDetectionController ingests stop events by freezing timer, stopping ticker, persisting final elapsed, and ignoring start events after stop until reset.
- Legacy split triggers are normalized to stop for compatibility.
- Motion screen updated labels to START/STOP in trigger history and Finish for first persisted mark.

Race sync protocol changes:
- RaceEventType added:
  - raceStartRequest (client -> host)
  - raceStopRequest (client -> host)
  - raceStopped (host -> all)
- Wire mappings added for new event strings:
  - race_start_request, race_stop_request, race_stopped
- Host now promotes client requests into canonical race_started/race_stopped broadcasts.
- Client local detections send start/stop requests when connected, and apply local state immediately (existing main wiring still applies local triggers immediately).
- Host replay for late join now sends race_started and race_stopped when race already ended.
- Legacy race_split payloads are interpreted as stop-compatible in client ingest path.

Tests updated/added:
- motion_detection_engine_test:
  - second trigger emits STOP
  - post-stop detections ignored until reset
- motion_detection_controller_test:
  - stop freezes timer and persists final elapsed
  - post-stop detections ignored until reset
- motion_detection_settings_widget_test:
  - finish row label assertions updated
- race_sync_models_test:
  - raceStopped serialization/parsing
  - raceStopRequest serialization/parsing
- race_sync_controller_test:
  - host replay start+stop
  - client mirrors host start+stop
  - host promotion of client start/stop requests to canonical broadcasts

Verification:
- flutter test (full suite): passed.
- flutter analyze: no issues.

Note:
- Existing unrelated worktree changes (including package.json and prior config-tuning edits) were left untouched.