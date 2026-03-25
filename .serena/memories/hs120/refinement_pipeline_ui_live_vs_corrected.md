Added post-race impact visibility for live vs corrected results.

RaceSessionController:
- Keeps both live (pre-refine) and corrected host trigger timestamps:
  - _hostLiveStartSensorNanos, _hostLiveStopSensorNanos, _hostLiveSplitSensorNanosByIndex
  - existing corrected host fields remain _hostStartSensorNanos/_hostStopSensorNanos/_hostSplitSensorNanosByIndex.
- Exposes refinementImpacts and hasRefinementImpact getters via SessionRefinementImpact model with:
  - label
  - liveSensorNanos, correctedSensorNanos
  - liveElapsedNanos, correctedElapsedNanos
  - changed and deltaElapsedNanos helpers.
- _applyRoleEvent now records both live and corrected values at trigger time.
- _applyHostTriggerRefinement now updates corrected values only and preserves provisional baseline mapping; duplicate no-op refinements are ignored.

RaceSessionScreen:
- Timeline card now includes a "Post-Race Analysis" section that shows per-event live -> corrected values and delta (ms), plus refinement status.
- Added keys: post_race_analysis_title and post_race_analysis_row_<label>.

Tests:
- race_session_controller_test: added refinement impacts test validating live/corrected/delta behavior for start+finish.
- race_session_screen_test: added assertions for Post-Race Analysis section and start live->corrected row.
