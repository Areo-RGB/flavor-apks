## 1. Platform Decision Gate

- [ ] 1.1 Define weighted platform scorecard criteria (delivery speed, protocol compatibility effort, ops complexity, testability).
- [ ] 1.2 Evaluate Kotlin Multiplatform approach and Node.js + Vite React approach against the same scorecard.
- [ ] 1.3 Publish and approve an ADR that selects the coordinator implementation platform and fallback path.

## 2. Protocol Compatibility Baseline

- [ ] 2.1 Extract current TCP message/handshake contract into reusable test fixtures.
- [ ] 2.2 Build compatibility tests that validate laptop coordinator message handling against Android client expectations.
- [ ] 2.3 Add regression checks for endpoint lifecycle events (join, disconnect, reconnect) using deterministic identity mapping.
- [ ] 2.4 Add regression checks that verify smartphone-only host/client flow works without any PC coordinator process.

## 3. Laptop Host Runtime

- [ ] 3.1 Implement coordinator host startup configuration (port, session strategy, operator start/stop control).
- [ ] 3.2 Implement concurrent client acceptance and connection-state tracking for active sessions.
- [ ] 3.3 Implement host-side relay for sync and timing messages required by existing client flows.
- [ ] 3.4 Add LAN connectivity preflight checks (host reachability and required local firewall allowances for inbound mobile connections).

## 4. Web UI (Vite React)

- [ ] 4.1 Scaffold Vite React operator UI for live session and result board views.
- [ ] 4.2 Add Tailwind CSS setup and shared design tokens for operator-facing layouts.
- [ ] 4.3 Implement backend-to-UI data channel (HTTP/WebSocket) for session state and timing updates.
- [ ] 4.4 Implement deterministic result rendering and provisional/final indicators in the UI.

## 5. Results Ingestion and Display

- [ ] 5.1 Implement timestamp ingestion pipeline from incoming client events.
- [ ] 5.2 Implement deterministic ranking and tie-break behavior for equal normalized timestamps.
- [ ] 5.3 Validate parity between backend ranking output and rendered UI ordering.
- [ ] 5.4 Validate Tailwind-styled UI readability and layout stability on target laptop resolutions.

## 6. Validation and Rollout

- [ ] 6.1 Run mixed-device dry-run tests with existing Android clients connected to laptop host.
- [ ] 6.2 Verify successful connection flow while keeping router setup unchanged.
- [ ] 6.3 Run dedicated smartphone-only dry-run tests and verify parity with current workflow.
- [ ] 6.4 Document race-day setup steps for both modes and fallback procedure to Android-host mode.
- [ ] 6.5 Package coordinator runtime for Windows laptop execution and verify preflight checklist.
