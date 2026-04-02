## ADDED Requirements

### Requirement: Coordinator displays live ordered timing results
The system SHALL ingest timestamp events from connected devices and render a continuously updated ordered results board for the active run.

#### Scenario: New timestamp event updates board
- **WHEN** a valid timing event is received for an active run
- **THEN** the display updates the relevant athlete/device row and recalculates ordering

### Requirement: Coordinator UI can be deployed as a Vite React application
The system SHALL support a browser-based UI built with Vite React that reads result and session state from the coordinator backend.

#### Scenario: Vite React UI receives coordinator updates
- **WHEN** the coordinator backend publishes session or timing updates
- **THEN** the Vite React UI reflects those updates without requiring direct mobile-to-browser connections

### Requirement: Windows app flow mirrors Xiaomi host flow in phase 1
The system MUST implement Windows UI screens and navigation as a 1-to-1 copy of the Xiaomi host app flow for phase 1, including equivalent entry points, primary action order, and stage transitions.

#### Scenario: Operator follows Xiaomi workflow on Windows
- **WHEN** an operator executes the known Xiaomi host workflow on the Windows app
- **THEN** each step maps to an equivalent screen and action in the same flow order

### Requirement: Web UI styling uses Tailwind CSS
The system SHALL implement web UI layout and styling using Tailwind CSS utilities and shared design tokens for consistent operator-facing screens.

#### Scenario: Tailwind-styled result board is rendered
- **WHEN** the operator opens the web UI result board
- **THEN** the interface is styled through Tailwind CSS classes and remains readable across common laptop resolutions

### Requirement: Result ordering is deterministic under equal timestamps
The system MUST apply deterministic tie-breaking when two or more events resolve to equal normalized timestamps so ordering does not flicker across refreshes.

#### Scenario: Tie case is resolved consistently
- **WHEN** two devices submit events with equal normalized event time
- **THEN** the coordinator applies the configured tie-break rule and shows the same order on repeated refreshes

### Requirement: Result confidence and finalization are visible to operators
The system SHALL indicate whether a displayed result is provisional or final based on synchronization confidence and run completion state.

#### Scenario: Low clock confidence marks provisional result
- **WHEN** synchronization confidence falls below the configured threshold during result updates
- **THEN** the corresponding result entries are marked provisional until confidence recovers or operator finalizes manually
