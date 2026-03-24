Added New Relic custom instrumentation for Nearby connection lifecycle.

Changes made:
- Added `lib/core/services/newrelic_bridge.dart`:
  - `NewRelicBridge.recordNearbyEvent(...)` wrapper around
    `NewrelicMobile.instance.recordCustomEvent(...)`
  - Emits custom event type: `NearbyConnection`
  - Swallows plugin/channel errors to avoid breaking tests and non-instrumented runs
- Updated `lib/features/race_session/race_session_controller.dart`:
  - Injected `NewRelicBridge` (optional constructor dependency with safe default)
  - Added `_trackNearby(...)` helper with standard attributes:
    - `action`, `networkRole`, `stage`, `endpointId`, `endpointName`,
      `serviceId`, `connected`, `statusCode`, `statusMessage`, `errorMessage`
  - Instrumented key lifecycle points:
    - permission status and permission failure
    - hosting/discovery start + failure
    - connection request + failure
    - endpoint found/lost/disconnected
    - connection result
    - Nearby error events

Verification:
- `dart format` on touched files completed.
- `flutter analyze lib/core/services/newrelic_bridge.dart lib/features/race_session/race_session_controller.dart` passed.

NRQL to verify after one app run:
- `SELECT count(*) FROM NearbyConnection SINCE 30 minutes ago`
- `SELECT count(*) FROM NearbyConnection FACET action SINCE 30 minutes ago`
