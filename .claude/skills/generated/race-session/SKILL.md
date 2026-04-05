---
name: race-session
description: "Skill for the Race_session area of photo-finish. 97 symbols across 11 files."
---

# Race_session

97 symbols | 11 files | Cohesion: 69%

## When to Use

- Working with code in `android/`
- Understanding how `timeline snapshot maps host sensor into local sensor in client mode`, `auto ticker does not start NTP burst when fresh GPS lock exists`, `auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale` work
- Modifying race_session-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | setLocalDeviceIdentity, setSessionStage, setNetworkRole, setDeviceRole, onNearbyEvent (+44) |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionModels.kt` | toJsonObject, toJsonString, tryParse, sessionStageFromName, readOptionalLong (+4) |
| `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt` | `timeline snapshot maps host sensor into local sensor in client mode`, `auto ticker does not start NTP burst when fresh GPS lock exists`, `auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale`, `in-progress NTP burst is not cancelled when GPS becomes fresh`, `clock sync burst selects minimum RTT sample and breaks ties by earliest accepted` (+3) |
| `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionModelsTest.kt` | `clock sync binary request and response round-trip`, `clock sync binary codec rejects wrong version type and length`, `snapshot round-trips host GPS fields`, `timeline snapshot round-trips with optional fields`, `trigger message parse rejects invalid payload` (+2) |
| `android/app/src/main/kotlin/com/paul/sprintsync/core/services/NearbyConnectionsManager.kt` | setEventListener, configureNativeClockSyncHost, onPayloadReceived, tryHandleClockSyncPayload, tryRespondToClockSyncRequest (+1) |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | updateThreshold, updateRoiCenter, updateRoiWidth, updateCooldown, stopMonitoring |
| `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | onCreate, onDestroy, localDeviceId, shouldRunLocalMonitoring |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/SessionClockSyncBinaryCodec.kt` | encodeRequest, encodeResponse, decodeRequest, decodeResponse |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | setEventListener, currentClockSyncElapsedNanos, stopNativeMonitoring |
| `android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/Theme.kt` | SprintSyncTheme |

## Entry Points

Start here when exploring this area:

- **``timeline snapshot maps host sensor into local sensor in client mode``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt:228`
- **``auto ticker does not start NTP burst when fresh GPS lock exists``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt:312`
- **``auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt:352`
- **``in-progress NTP burst is not cancelled when GPS becomes fresh``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt:386`
- **`setLocalDeviceIdentity`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt:178`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| ``timeline snapshot maps host sensor into local sensor in client mode`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt` | 228 |
| ``auto ticker does not start NTP burst when fresh GPS lock exists`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt` | 312 |
| ``auto ticker starts NTP burst when GPS lock is unavailable and clock lock is stale`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt` | 352 |
| ``in-progress NTP burst is not cancelled when GPS becomes fresh`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/features/race_session/RaceSessionControllerTest.kt` | 386 |
| `setLocalDeviceIdentity` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 178 |
| `setSessionStage` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 203 |
| `setNetworkRole` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 210 |
| `setDeviceRole` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 328 |
| `onNearbyEvent` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 332 |
| `assignRole` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 398 |
| `localDeviceRole` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 557 |
| `localCameraFacing` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 561 |
| `updateClockState` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 632 |
| `onCreate` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 64 |
| `onDestroy` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 367 |
| `setEventListener` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 123 |
| `currentClockSyncElapsedNanos` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 184 |
| `stopNativeMonitoring` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 241 |
| `SprintSyncTheme` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/Theme.kt` | 35 |
| `stopDisplayHostMode` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 317 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnCreate → LocalDeviceFromState` | cross_community | 5 |
| `OnSensorEvent → LocalDeviceFromState` | cross_community | 5 |
| `OnResume → LocalDeviceFromState` | cross_community | 5 |
| `StopHostingAndReturnToSetup → LocalDeviceFromState` | cross_community | 5 |
| `OnCreate → EnsureLocalDevice` | cross_community | 4 |
| `OnCreate → PruneOrphanedNonLocalDevices` | cross_community | 4 |
| `OnNearbyEvent → ClearIdentityMappingForEndpoint` | cross_community | 4 |
| `OnNearbyEvent → EnsureLocalDevice` | cross_community | 4 |
| `OnNearbyEvent → LocalDeviceFromState` | cross_community | 4 |
| `OnNearbyEvent → PruneOrphanedNonLocalDevices` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Sprintsync | 11 calls |
| Services | 7 calls |
| Sensor_native | 2 calls |
| Clock | 2 calls |

## How to Explore

1. `gitnexus_context({name: "`timeline snapshot maps host sensor into local sensor in client mode`"})` — see callers and callees
2. `gitnexus_query({query: "race_session"})` — find related execution flows
3. Read key files listed above for implementation details
