# SprintSync WiFi Optimization - Implementation Prompt

## First Principles

**Current Problem:**
- Nearby Connections API requires discovery + handshake every session
- No saved connections - users must browse and select devices each time
- Discovery latency adds 5-15 seconds to race setup

**Core Insight:**
Fixed race setups with known devices don't need discovery. Hardcoded identities eliminate connection friction.

**Desired State:**
- Power on → devices auto-connect within 2 seconds
- No UI interaction required for connection
- Deterministic: same device = same role every time

---

## High-Level Architecture

### Option 1: Saved Endpoint Auto-Reconnect (Minimal Change)

**Concept:** Store last successful connection, skip discovery UI on restart

**Flow:**
1. First connection: User browses, selects host, connects
2. Save to local storage: `{lastEndpointId, lastEndpointName, lastConnectedAt}`
3. On app restart: Skip discovery, immediately requestConnection(savedEndpointId)
4. If fails (timeout 3s): Fall back to discovery UI

**Trade-offs:**
- ✅ Minimal code changes (~100 lines)
- ✅ Keeps Nearby Connections (reliable, handles complexity)
- ✅ Works with existing WiFi setup
- ❌ Still uses Nearby handshake (2-3 second delay)
- ❌ Endpoint IDs change if router resets

---

### Option 2: TCP Socket Transport (Direct Mode)

**Concept:** Replace Nearby with direct TCP sockets over local WiFi

**Components:**
- Host: TCP server on port 9000, accepts client connections
- Clients: TCP clients connect to hardcoded Host IP
- Discovery: Eliminated - IPs are static
- Protocol: Same JSON messages over TCP instead of Nearby

**Flow:**
1. Configure device role in app settings (Host/Start/Stop/Display/Split)
2. App reads role → knows target IP (Host=192.168.0.10)
3. Client connects TCP socket to 192.168.0.10:9000
4. Host accepts, maintains socket pool
5. Send/recv JSON messages over socket

**Trade-offs:**
- ✅ Instant connection (<200ms)
- ✅ No discovery overhead
- ✅ Deterministic - works every time
- ❌ Requires static IP setup (user must configure router)
- ❌ More code: connection management, reconnection logic, error handling
- ❌ No auto-fallback if network unavailable

---

### Option 3: Hybrid - Fast Path + Fallback

**Concept:** Try direct TCP first, fall back to Nearby if unavailable

**Flow:**
1. Check if static IP mode configured
2. Attempt TCP connection to known IP (500ms timeout)
3. If success: Use TCP mode
4. If fail: Use Nearby discovery mode

**Benefits:**
- Best of both worlds
- Works with or without static IP setup
- Progressive enhancement

---

## Recommended Approach: Option 1 (Saved Endpoint)

**Why:** Maximum value for minimum change. Eliminates UI friction while keeping proven Nearby reliability.

---

## Implementation Areas

### Area A: Data Persistence
- Extend LocalRepository to save connection history
- Data class: `SavedEndpoint(endpointId, endpointName, deviceRole, savedAt)`

### Area B: Connection Logic
- RaceSessionController: Check for saved endpoint on role set
- Skip discovery UI if valid saved endpoint exists
- Auto-requestConnection with 3-second timeout
- Clear saved endpoint on connection failure

### Area C: UI Flow
- SprintSyncApp: Conditional discovery screen
- If saved endpoint valid: Show "Connecting to [name]..." with cancel button
- If timeout/fail: Show discovery UI as before

### Area D: Settings
- Add "Clear Saved Connections" button in settings
- Optional: "Manual IP Mode" toggle for power users

---

## Success Criteria

1. **Setup time:** From app launch to connected < 5 seconds (currently 15-20s)
2. **Success rate:** Auto-connect succeeds 95%+ of time with saved endpoint
3. **Fallback:** Never trap user - always provide manual discovery escape hatch
4. **User control:** Clear way to forget/reset saved connections

---

## File Touches (High Level)

- `LocalRepository.kt`: Add saved endpoint storage
- `RaceSessionController.kt`: Auto-connect logic, saved endpoint check
- `SprintSyncApp.kt`: Conditional UI flow for discovery vs auto-connect
- `MainActivity.kt`: Settings integration

---

## Open Questions

1. Should saved endpoint expire after X days? (Stale data risk)
2. Multiple saved endpoints for different races? (Nice-to-have)
3. Export/import saved endpoints for device replacement? (Future)

---

## Context for Implementation

**Current Nearby Flow:**
1. User selects "Join Race" (CLIENT role)
2. startDiscovery() called
3. onEndpointFound() → Show device list UI
4. User taps device → requestConnection()
5. onConnectionResult() → Success

**Optimized Flow:**
1. User selects "Join Race"
2. Check: Have saved endpoint? + Is it recent? (<7 days)
3. If yes: requestConnection(savedId) immediately
4. If success (within 3s): Skip to step 6
5. If fail: startDiscovery() → Show list (fallback)
6. Save endpoint on successful connection

---

## Edge Cases to Handle

- Saved endpoint from different WiFi network (different subnet)
- Router reset → endpoint ID changed but name same
- Multiple clients with same saved endpoint (race condition)
- Device role mismatch (saved as Start, now trying to be Display)

---

**Goal:** Make race setup feel instantaneous. User opens app → 2 seconds later → ready to race.
