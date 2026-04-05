---
name: sprintsync
description: "Skill for the Sprintsync area of photo-finish. 43 symbols across 8 files."
---

# Sprintsync

43 symbols | 8 files | Cohesion: 76%

## When to Use

- Working with code in `android/`
- Understanding how `timer refresh runs only during active in-progress resumed monitoring`, onPause, onResume work
- Modifying sprintsync-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | onPause, onResume, onNearbyEvent, firstConnectedEndpointId, syncControllerSummaries (+16) |
| `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | `timer refresh runs only during active in-progress resumed monitoring`, `starts local capture when monitoring active resumed assigned and local capture is idle`, `stops local capture when app pauses during monitoring`, `stops local capture when local role becomes unassigned during monitoring`, `keeps local capture unchanged when monitoring state is already satisfied` (+4) |
| `android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt` | SprintSyncApp, StatusCard, PermissionWarningCard, SetupActionsCard, LobbyActionsCard |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | startMonitoring, canStartMonitoring, hasFreshAnyClockLock |
| `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | currentRole, connectedEndpoints |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | onHostPaused |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | handleSensorEvent |
| `android/app/src/main/kotlin/com/paul/sprintsync/ui/components/SprintSyncCard.kt` | SprintSyncCard |

## Entry Points

Start here when exploring this area:

- **``timer refresh runs only during active in-progress resumed monitoring``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt:60`
- **`onPause`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt:351`
- **`onResume`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt:359`
- **`onHostPaused`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt:127`
- **`startMonitoring`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt:440`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| ``timer refresh runs only during active in-progress resumed monitoring`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 60 |
| `onPause` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 351 |
| `onResume` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 359 |
| `onHostPaused` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 127 |
| `startMonitoring` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 440 |
| `canStartMonitoring` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 549 |
| `hasFreshAnyClockLock` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 743 |
| `currentRole` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 67 |
| `connectedEndpoints` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | 71 |
| ``starts local capture when monitoring active resumed assigned and local capture is idle`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 8 |
| ``stops local capture when app pauses during monitoring`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 21 |
| ``stops local capture when local role becomes unassigned during monitoring`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 34 |
| ``keeps local capture unchanged when monitoring state is already satisfied`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 47 |
| ``does not start capture again while start is pending`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 85 |
| ``does not start local capture when user monitoring toggle is off`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 98 |
| ``stops local capture when user monitoring toggle is turned off during monitoring`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 111 |
| ``re-enabling user monitoring toggle allows local capture start when guards are met`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/MainActivityMonitoringLogicTest.kt` | 124 |
| `onRequestPermissionsResult` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 387 |
| `handleSensorEvent` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | 103 |
| `SprintSyncCard` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/ui/components/SprintSyncCard.kt` | 17 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnResume → CancelPendingAeAwbLock` | cross_community | 7 |
| `OnResume → SelectCameraFacing` | cross_community | 7 |
| `OnResume → HandleUnlockedPolicyFailure` | cross_community | 7 |
| `OnSensorEvent → LocalDeviceFromState` | cross_community | 5 |
| `OnPause → CancelPendingAeAwbLock` | cross_community | 5 |
| `OnPause → Reset` | cross_community | 5 |
| `OnPause → ResetRun` | cross_community | 5 |
| `OnResume → CurrentTargetFpsUpper` | cross_community | 5 |
| `OnResume → LogRuntimeDiagnostic` | cross_community | 5 |
| `OnResume → LocalDeviceFromState` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Race_session | 8 calls |
| Sensor_native | 3 calls |
| Clock | 2 calls |
| Services | 1 calls |
| Motion_detection | 1 calls |

## How to Explore

1. `gitnexus_context({name: "`timer refresh runs only during active in-progress resumed monitoring`"})` — see callers and callees
2. `gitnexus_query({query: "sprintsync"})` — find related execution flows
3. Read key files listed above for implementation details
