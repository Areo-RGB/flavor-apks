## ADDED Requirements

### Requirement: Laptop coordinator can host race TCP sessions
The system SHALL allow a laptop coordinator process to open the configured TCP host port, accept concurrent mobile client connections on the existing LAN, and expose host-session availability to clients without requiring Android host mode.

#### Scenario: Host starts and accepts clients
- **WHEN** an operator starts laptop host mode with a valid port configuration
- **THEN** the coordinator listens on the configured port and accepts supported client connections

### Requirement: Router setup remains unchanged
The system MUST work with the existing router setup and SHALL NOT require router configuration changes for normal race operation.

#### Scenario: Existing router is reused without reconfiguration
- **WHEN** the operator runs the coordinator with the same router topology currently used by mobile devices
- **THEN** mobile clients on that LAN can connect to the laptop host without changing router settings

### Requirement: Host behavior remains protocol compatible with existing mobile clients
The laptop coordinator MUST process and emit session messages using the existing wire-level contract expected by Android clients for connection lifecycle, synchronization, and timing events.

#### Scenario: Existing Android client joins laptop-hosted session
- **WHEN** an Android client discovers and connects to the laptop host
- **THEN** the client completes handshake and participates in session flow without protocol negotiation errors

### Requirement: Coordinator handles connection lifecycle deterministically
The system SHALL track endpoint join, disconnect, and reconnect events and keep session membership state consistent for race control and result computation.

#### Scenario: Client disconnects and reconnects mid-session
- **WHEN** a connected client drops and then reconnects
- **THEN** the coordinator updates endpoint state, preserves deterministic identity mapping, and continues the session without host restart

### Requirement: Smartphone-only operation remains supported
The system MUST keep the current smartphone-only operation mode available, and adding laptop coordinator support SHALL NOT make the PC a mandatory runtime dependency.

#### Scenario: Session starts without PC coordinator
- **WHEN** operators run a race session using only smartphones
- **THEN** hosting and timestamp flow continue to work using the existing mobile-host workflow
