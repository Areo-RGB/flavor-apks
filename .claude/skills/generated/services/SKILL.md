---
name: services
description: "Skill for the Services area of photo-finish. 28 symbols across 2 files."
---

# Services

28 symbols | 2 files | Cohesion: 81%

## When to Use

- Working with code in `android/`
- Understanding how disconnect, startHosting, stopHosting work
- Modifying services-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | disconnect, isPointToPointHostBusy, clearEndpointState, onEndpointFound, onEndpointLost (+15) |
| `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | NearbyEvent, EndpointFound, EndpointLost, ConnectionResult, EndpointDisconnected (+3) |

## Entry Points

Start here when exploring this area:

- **`disconnect`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt:206`
- **`startHosting`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt:78`
- **`stopHosting`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt:98`
- **`startDiscovery`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt:106`
- **`stopDiscovery`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt:125`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `EndpointFound` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 5 |
| `EndpointLost` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 11 |
| `ConnectionResult` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 15 |
| `EndpointDisconnected` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 23 |
| `PayloadReceived` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 27 |
| `ClockSyncSampleReceived` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 32 |
| `Error` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 37 |
| `disconnect` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 206 |
| `startHosting` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 78 |
| `stopHosting` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 98 |
| `startDiscovery` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 106 |
| `stopDiscovery` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 125 |
| `stopAll` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 212 |
| `requestConnection` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 133 |
| `sendMessage` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 168 |
| `sendClockSyncPayload` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 187 |
| `NearbyEvent` | Interface | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyEvents.kt` | 4 |
| `isPointToPointHostBusy` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 230 |
| `clearEndpointState` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 252 |
| `onEndpointFound` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 264 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnNearbyEvent → ClearTransientState` | cross_community | 4 |
| `OnDestroy → ClearTransientState` | cross_community | 4 |
| `StartHosting → ClearTransientState` | intra_community | 4 |
| `StartDiscovery → ClearTransientState` | intra_community | 4 |

## How to Explore

1. `gitnexus_context({name: "disconnect"})` — see callers and callees
2. `gitnexus_query({query: "services"})` — find related execution flows
3. Read key files listed above for implementation details
