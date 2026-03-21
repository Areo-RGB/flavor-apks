Hardened RaceSessionController clock sync against asymmetric latency.

What changed:
- Reduced accepted clock-sync RTT threshold from 400ms to 20ms.
- Changed client clock sync from single ping to a 10-request burst per sync attempt.
- Added in-flight request tracking keyed by clientSendElapsedNanos and a burst timeout guard.
- Client now accepts only responses matching pending burst requests.
- Clock offset update now keeps the lowest RTT sample in the active burst and ignores worse samples.
- Removed weighted averaging across samples; the best sample directly sets host-client offset.
- High RTT and backwards-time samples are ignored (do not clobber a previously good lock).
- Clearing clock lock also clears burst/pending sync state.
- Updated user-facing lock error text to reflect dynamic RTT threshold in ms.

Tests updated:
- Existing race_session_controller tests now assert 10 sync requests in burst at monitoring sync points.
- RTT rejection test updated to 20ms expectation.
- Added test: lowest RTT sample in burst is used for mapped host sensor trigger conversion.

Verification:
- flutter test test/race_session_controller_test.dart passed.