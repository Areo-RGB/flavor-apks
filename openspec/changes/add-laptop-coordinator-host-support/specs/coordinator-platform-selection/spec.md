## ADDED Requirements

### Requirement: Platform options are evaluated with explicit criteria
The project MUST evaluate implementation platform options using a documented scorecard that includes Kotlin Multiplatform and a Node.js coordinator backend with Vite React UI option.

#### Scenario: Evaluation report is produced
- **WHEN** platform discovery is completed for the coordinator change
- **THEN** a comparison document records scores for each option across agreed criteria including delivery speed, protocol compatibility effort, and operational complexity

### Requirement: Selected platform supports phase-1 Xiaomi flow parity
The chosen platform MUST demonstrate that Xiaomi host UI and app-flow parity can be delivered in phase 1 without introducing required flow deviations.

#### Scenario: Platform selected for parity-first delivery
- **WHEN** the platform decision is finalized
- **THEN** decision records include evidence that 1-to-1 Xiaomi flow parity is implementable on Windows in phase 1

### Requirement: Platform decision is recorded before feature implementation starts
The team SHALL publish an approved architecture decision record that selects one platform option and explains rejection reasons for alternatives.

#### Scenario: Decision gate is passed
- **WHEN** the platform decision meeting concludes
- **THEN** the selected option and rationale are committed in version-controlled project docs and referenced by implementation tasks

### Requirement: Decision can be reversed with a documented fallback
The team MUST define a fallback platform path if the selected option fails acceptance checks during implementation spikes.

#### Scenario: Selected platform fails acceptance checks
- **WHEN** implementation spike results fail predefined acceptance criteria
- **THEN** the fallback option and transition steps are activated according to the decision record
