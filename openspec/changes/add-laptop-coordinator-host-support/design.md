## Context

The current race workflow uses Android devices for both sensor capture and host coordination. In this repository, hosting is handled through TCP in the Android app (for example, host/client role transitions and socket hosting in MainActivity plus TcpConnectionsManager). This works for phone/tablet-only sessions, but race operations need a laptop station that is easier to monitor and control.

Key constraints:
- Existing Android clients already speak a TCP message contract and rely on host-driven coordination.
- Laptop host must accept mobile connections within the current router setup; router configuration stays unchanged.
- Smartphone-only sessions MUST remain fully supported without requiring the laptop coordinator.
- Result display must be near real time and stable during active sessions.
- The project is currently Kotlin Android; introducing a laptop coordinator adds a new runtime target.

Stakeholders are race operators (need clear timing board), mobile-device operators (need compatibility), and maintainers (need low operational complexity).

## Goals / Non-Goals

**Goals:**
- Enable a laptop runtime to host TCP sessions for mobile clients on the existing LAN.
- Receive timestamp events and show ordered results live and after run completion.
- Choose an implementation platform with explicit criteria, including Kotlin Multiplatform and alternatives.
- Keep compatibility with existing Android client behavior and message semantics.
- Keep smartphone-only race operation as a first-class supported mode.
- Deliver Windows UI and navigation flow as a 1-to-1 copy of Xiaomi host flow for phase 1.

**Non-Goals:**
- Rewriting Android sensor-capture logic.
- Introducing cloud synchronization or internet dependency.
- Implementing multi-race analytics/reporting beyond immediate session results.
- Replacing current Android-host path in the first release.
- Changing router topology, router rules, or access-point setup.
- Removing smartphone-only operation or making it depend on a PC service.
- Introducing new UX patterns or altered screen flow in phase 1.

## Decisions

### 1) Preserve protocol compatibility first
Decision: The laptop coordinator SHALL implement the existing TCP frame/message contract used by Android host/client flows.

Rationale: The irreducible constraint is that already-deployed mobile clients must keep working. Protocol drift would force simultaneous client upgrades and increase race-day risk.

Alternatives considered:
- New protocol version immediately: cleaner long-term schema, but high breakage risk.
- Adapter proxy translating between protocols: adds latency and operational complexity.

### 1.1) Additive rollout only
Decision: The laptop coordinator SHALL be introduced as an optional mode in addition to the current smartphone-only mode.

Rationale: Operational continuity is a hard constraint. Race-day workflows must continue to work even when no PC is present.

Alternatives considered:
- Mandatory coordinator cutover: simpler long-term architecture, unacceptable migration risk.
- Deprecating smartphone host path in first release: reduces maintenance but breaks existing field setup.

### 2) Keep existing router topology unchanged
Decision: The coordinator SHALL run as a LAN host in the current router subnet and require zero router reconfiguration.

Rationale: The immutable operational constraint is that race-day network setup cannot change. Any solution that needs router updates is operationally invalid regardless of code quality.

Alternatives considered:
- Dedicated hotspot or separate router per event: adds setup burden and violates the fixed-router requirement.
- Router-level custom rules (for example forwarding or static remapping): brittle across venues and devices.

### 3) Split coordinator into three internal components
Decision: Use a clear separation of concerns:
- Connection runtime (socket host, endpoint lifecycle, retries)
- Session domain (clock alignment, timestamp ingestion, ranking)
- Presentation layer (live board and final result view)

Rationale: Network failures, timing logic, and UI refresh have different failure modes and test boundaries.

Alternatives considered:
- Single monolithic coordinator module: faster initial coding, harder to test and evolve.

### 3.1) Phase-1 UI/flow parity with Xiaomi
Decision: Windows operator application SHALL mirror Xiaomi host UI structure and app flow one-to-one in phase 1, including screen order, primary actions, and state transitions.

Rationale: Immediate operator familiarity reduces rollout risk and training overhead while backend hosting logic is introduced.

Alternatives considered:
- New Windows-first UX in phase 1: potentially better desktop ergonomics, higher validation and behavior-drift risk.

### 4) Platform decision gate: Kotlin Multiplatform vs alternatives
Decision: Use a formal evaluation scorecard before implementation commit. Candidate options:
- Kotlin Multiplatform shared core + desktop target
- Kotlin/JVM desktop app (Compose Desktop or equivalent) with shared pure Kotlin module
- Node.js coordinator server + Vite React UI (browser-based operator console)
- Another non-Kotlin option (for example Go + UI toolkit)

Rationale: The fundamental trade-off is delivery speed versus long-term code sharing. Since only Android + laptop are required now, full multiplatform abstraction may be unnecessary overhead; however, Kotlin Multiplatform can reduce future fragmentation if additional targets are likely.

Decision criteria (weighted):
- Protocol compatibility effort
- Time-to-first-usable coordinator
- Reuse of existing Kotlin timing/network knowledge
- Packaging/ops simplicity on Windows laptops
- Testability of deterministic timestamp ordering logic
- Ability to deliver Xiaomi-equivalent UI/flow parity without behavior drift

Implementation note for Node.js + Vite React candidate:
- Node.js process hosts TCP session handling for mobile clients on LAN.
- Vite React app provides operator UI and communicates with backend via HTTP/WebSocket.
- In development, Vite proxy can route API/socket traffic to the Node process; in production, static frontend assets can be served alongside the backend.

Frontend styling note:
- Tailwind CSS is the default styling layer for the Vite React UI to keep operator screens consistent, fast to iterate, and easy to maintain.

Output of this gate is an ADR-style decision recorded in the change implementation branch.

### 5) Deterministic result ordering model
Decision: Coordinator ranking SHALL be computed from normalized event timestamps with deterministic tie breaking (device stable ID then event sequence).

Rationale: Humans can tolerate small clock skew, but they cannot tolerate inconsistent re-ordering between screen refreshes.

Alternatives considered:
- Arrival-order ranking: simplest, but incorrect under variable network latency.

## Risks / Trade-offs

- [Risk] Protocol edge-case mismatch between Android and laptop runtime -> Mitigation: add cross-runtime golden-frame tests and compatibility fixtures.
- [Risk] Laptop host cannot receive inbound mobile connections due to local firewall policy -> Mitigation: startup preflight checks plus one-time guided firewall rule creation.
- [Risk] Clock-offset instability affects close finishes -> Mitigation: expose offset confidence and fallback markers in UI when lock quality is low.
- [Risk] KMP adoption introduces build complexity before value is proven -> Mitigation: keep a gated decision with rollback to Kotlin/JVM-only implementation.
- [Risk] Windows packaging friction slows race-day setup -> Mitigation: produce a single documented launcher artifact and preflight checklist.

## Migration Plan

1. Define and lock laptop coordinator requirements and protocol compatibility tests.
2. Complete platform decision gate and capture chosen stack.
3. Implement coordinator host runtime and timestamp/result projection with unchanged-router LAN assumptions.
4. Validate with existing Android clients in mixed-device dry runs and smartphone-only regression runs.
5. Roll out as optional host path; keep Android host path enabled by default.

Rollback strategy:
- If coordinator path is unstable, operators revert to the current Android-host workflow with no client protocol change required.

## Open Questions

- Should first release include only a local desktop UI, or also a lightweight remote view endpoint?
- What is the minimum acceptable clock-lock confidence before displaying a result as final?
- Do we need persistent storage of session results in v1, or only export/copy for race-day handoff?
