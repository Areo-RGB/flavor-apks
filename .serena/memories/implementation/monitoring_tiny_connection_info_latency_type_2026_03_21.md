Implemented tiny Monitoring header connection info row with transport label and latency display.

Files changed:
- lib/features/race_session/race_session_controller.dart
  - Added getters:
    - hasConnectedPeers
    - monitoringConnectionTypeLabel (fixed: "Nearby (auto BT/Wi-Fi Direct)")
    - monitoringLatencyMs (derived from _hostClockRoundTripNanos, client-only, null when unavailable)
- lib/features/race_session/race_session_screen.dart
  - Monitoring header left section changed to Expanded Column.
  - Added small grey caption under Role:
    - "Connection: Nearby (auto BT/Wi-Fi Direct) · Latency: <value>"
  - Added key: monitoring_connection_info.
  - Latency formatting: "-" when null, otherwise "<ms> ms".
  - Added ellipsis/maxLines to avoid overflow.
- test/race_session_screen_test.dart
  - Added assertion for monitoring_connection_info key and host default text containing Latency: -.
- test/race_session_controller_test.dart
  - Added assertion in client sync flow that monitoringLatencyMs is non-null after a successful clock sync response.

Verification:
- flutter test test/race_session_screen_test.dart -> pass
- flutter test test/race_session_controller_test.dart -> pass
- flutter analyze -> pass
- gitnexus_detect_changes(scope: all) -> low risk, changed files match intended scope.