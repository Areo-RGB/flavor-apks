Fixed monitoring connection info text truncation on narrow screens.

Changes:
- RaceSessionScreen monitoring header now renders connection info as two separate Text lines in a Column instead of a single one-line ellipsized Text.
- Kept key ValueKey('monitoring_connection_info') on the container Column for test stability.
- Updated race_session_screen_test expectation from one combined line to two lines:
  - 'Connection: Nearby (auto BT/Wi-Fi Direct)'
  - 'Sync: - · Latency: -'

Verification:
- flutter test test/race_session_screen_test.dart: PASS
- dart analyze lib/features/race_session/race_session_screen.dart test/race_session_screen_test.dart: PASS