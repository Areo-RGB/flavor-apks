Reduced NTP RTT quantization artifact in RaceSessionController by interpolating elapsedRealtime-domain between frame updates.

Problem:
- _nowClockSyncElapsedNanos() relied on latest frame-derived elapsed value only.
- During monitoring, this caused RTT measurements to be quantized to frame cadence (e.g., ~100ms steps), making nearby-device RTT appear artificially high.

Change:
- Added cached frame-derived elapsed sample fields:
  - _lastSensorElapsedSampleNanos
  - _lastSensorElapsedSampleCapturedAtNanos (captured in controller monotonic nowElapsed domain)
- Updated _sensorDerivedElapsedNanos():
  - When fresh frame sample exists, update cache and return sampled elapsed.
  - When no new frame at query moment, project elapsed as:
    projected = lastSampledElapsed + (nowElapsed - sampleCapturedAt)
  - Projection limited by _sensorElapsedProjectionMaxAgeNanos = 3s; otherwise returns null.
- Cleared the cache in _resetSession().

Result:
- NTP sync timing no longer snaps to frame boundaries under normal operation.
- Should significantly reduce apparent RTT inflation caused by frame update granularity.

Verification:
- dart analyze lib/features/race_session/race_session_controller.dart test/race_session_controller_test.dart: PASS
- flutter test test/race_session_controller_test.dart: PASS
- flutter test test/race_session_screen_test.dart: PASS