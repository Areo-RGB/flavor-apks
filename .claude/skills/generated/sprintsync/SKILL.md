---
name: sprintsync
description: "Skill for the Sprintsync area of photo-finish. 27 symbols across 2 files."
---

# Sprintsync

27 symbols | 2 files | Cohesion: 69%

## When to Use

- Working with code in `android/`
- Understanding how onRequestPermissionsResult, configureFlutterEngine, configure work
- Modifying sprintsync-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | disconnect, clearEndpointState, onEndpointFound, onEndpointLost, onConnectionInitiated (+21) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | configure |

## Entry Points

Start here when exploring this area:

- **`onRequestPermissionsResult`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt:199`
- **`configureFlutterEngine`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt:57`
- **`configure`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt:54`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `onRequestPermissionsResult` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 199 |
| `configureFlutterEngine` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 57 |
| `configure` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 54 |
| `disconnect` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 345 |
| `clearEndpointState` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 402 |
| `onEndpointFound` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 413 |
| `onEndpointLost` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 427 |
| `onConnectionInitiated` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 439 |
| `onConnectionResult` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 483 |
| `onDisconnected` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 510 |
| `onPayloadReceived` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 522 |
| `emitEvent` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 548 |
| `handleMethodCall` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 105 |
| `stringArg` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 156 |
| `stopHosting` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 249 |
| `stopDiscovery` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 274 |
| `requestConnection` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 281 |
| `sendBytes` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 324 |
| `stopAll` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 356 |
| `startHosting` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 230 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `HandleMethodCall → ClearTransientState` | cross_community | 5 |
| `HandleMethodCall → RequiredPermissions` | cross_community | 4 |
| `HandleMethodCall → EmitEvent` | cross_community | 4 |
| `StartDiscovery → ClearTransientState` | cross_community | 4 |
| `OnRequestPermissionsResult → RequiredPermissions` | intra_community | 3 |

## How to Explore

1. `gitnexus_context({name: "onRequestPermissionsResult"})` — see callers and callees
2. `gitnexus_query({query: "sprintsync"})` — find related execution flows
3. Read key files listed above for implementation details
