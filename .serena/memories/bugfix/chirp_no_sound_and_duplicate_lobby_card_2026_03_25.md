User reported two issues: duplicate Audio Chirp card in Race Lobby and no audible chirp playback.

Fixes implemented:
1) UI duplication:
- Removed second _buildChirpSyncCard() from lobby list in race_session_screen.dart.

2) Chirp playback/integration:
- AcousticChirpSyncEngine.startCalibration no longer hard-fails when timestamp path probe is unavailable.
- Initiator now always emits a chirp tone during startCalibration.
- Responder emits chirp tones during calibration rounds.
- Added new emitCalibrationChirp helper using AudioTrack with USAGE_MEDIA/CONTENT_TYPE_MUSIC for reliable playback path (less affected by sonification routing).
- Added source fallback and timestamp retry loop previously in probe (UNPROCESSED then MIC + retry loop) remains.
- Adjusted fallback chirp to be audibly lower frequency (3.2k-4.8k) with higher amplitude and longer duration so users can hear it.
- RaceSessionController no longer blocks chirp start when capabilities.supported is false; it proceeds in degraded mode with status text 'Calibrating (degraded timestamp path)'.

Validation:
- dart format + dart analyze (controller/screen): clean.
- flutter tests: race_session_controller_test + race_session_screen_test passed.
- Android gradle chirp unit test + compileDebugKotlin: BUILD SUCCESSFUL.