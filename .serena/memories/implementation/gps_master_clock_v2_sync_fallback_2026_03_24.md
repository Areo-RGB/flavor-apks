Implemented GPS Master Clock v2 across native Android + Dart race session stack.

Key behaviors now in place:
- Native emits gpsUtcOffsetNanos + gpsFixElapsedRealtimeNanos in native_state and native_frame_stats.
- Session snapshot includes hostGpsUtcOffsetNanos and hostGpsFixAgeNanos (host age, not raw elapsed timestamp).
- Client lock selection is explicit: GPS lock (freshness-checked) preferred over NTP, with NTP fallback.
- NTP sync requests are suppressed while fresh GPS lock is available.
- Mapping uses GPS-derived hostMinusClientElapsed first, then NTP fallback.
- Host rebroadcasts snapshot on GPS field changes with 1s throttle.
- UI exposes monitoringSyncModeLabel (GPS/NTP/-) and monitoringLatencyMs only for NTP.

Freshness rule:
- GPS stale threshold constant is 10s (_gpsOffsetStaleAfterNanos).

Verification (2026-03-24):
- dart analyze on touched race/motion files and tests: PASS.
- flutter test test/race_session_controller_test.dart: PASS.
- flutter test test/race_session_screen_test.dart: PASS.
- Android unit test SensorNativeMathTest (including GPS UTC/sensor round-trip): PASS.