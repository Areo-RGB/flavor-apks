## Why

The current workflow depends on an Android host device for both coordination and display, which limits operator ergonomics during races and makes it harder to run a stable control station. We need a laptop-based coordinator that can host the TCP session, accept mobile connections on the existing router/LAN, and present timing results in real time while staying compatible with existing mobile clients. This PC path is additive only; smartphone-only operation must remain available.

## What Changes

- Add support for a laptop coordinator runtime that can accept mobile device connections on the existing router/LAN and operate as the TCP host endpoint.
- Add a coordinator result view that consumes incoming timestamps and shows ranked finish output with per-device timing.
- Allow the coordinator UI to be implemented as a Vite React app connected to the laptop host server.
- Use Tailwind CSS for the web UI design system and layout implementation.
- For phase 1, copy Xiaomi host UI and app flow to Windows as a 1-to-1 parity implementation.
- Preserve the existing smartphone-only workflow as a supported operation mode with no required PC dependency.
- Define protocol compatibility requirements so existing Android client devices can join without app-side behavior regressions.
- Define a no-network-topology-change constraint: router setup stays the same and coordinator operation must not require router reconfiguration.
- Add a platform selection decision gate to evaluate Kotlin Multiplatform and Node.js + Vite React alternatives for implementation, with explicit acceptance criteria and chosen direction.

## Capabilities

### New Capabilities
- `laptop-coordinator-host`: Laptop process can host the race TCP server, manage client sessions, and broadcast coordinator state.
- `laptop-results-display`: Laptop UI can ingest timestamp events and render live and final race results.
- `coordinator-platform-selection`: Team can evaluate and select implementation platform (including Kotlin Multiplatform) using explicit criteria.

### Modified Capabilities
- None.

## Impact

- New coordinator code path and packaging workflow for a laptop target (desktop runtime).
- Shared message-contract/protocol validation between Android app and coordinator runtime.
- Network operations constrained to existing local router setup with mobile-to-laptop LAN connectivity.
- Optional web UI runtime and frontend build pipeline (Vite React) integrated with coordinator backend.
- Phase-1 UI scope is parity-driven (Xiaomi flow mirror) rather than new UX design.
- No forced migration: operators can continue smartphone-only sessions without the PC coordinator.
- Potential extraction or reuse of timing/protocol logic into a shared module depending on platform decision.
- Documentation and operator workflow updates for host deployment on laptop versus Android host device.