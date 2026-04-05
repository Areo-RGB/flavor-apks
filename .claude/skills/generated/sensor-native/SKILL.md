---
name: sensor-native
description: "Skill for the Sensor_native area of photo-finish. 82 symbols across 9 files."
---

# Sensor_native

82 symbols | 9 files | Cohesion: 84%

## When to Use

- Working with code in `android/`
- Understanding how `schedules retry only when monitoring and both preview and provider are ready`, `becomes retry eligible once preview attaches after provider is available`, createPreviewView work
- Modifying sensor_native-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | attachPreviewSurface, detachPreviewSurface, startNormalBackend, rebindCameraUseCasesIfMonitoring, attemptPreviewRebind (+28) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt` | currentTargetFpsUpper, bindAndConfigure, bindCameraUseCases, stop, cancelPendingAeAwbLock (+11) |
| `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | `detection math emits split triggers with cooldown and rearm parity`, `selectPreferredNormalFrameRateBounds prefers upper 60 with highest lower`, `selectPreferredNormalFrameRateBounds falls back to highest when 60 unavailable`, `selectPreferredNormalFrameRateBounds returns null for null or empty`, `shouldLockAeAwb returns true only at and after warmup` (+6) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | updateConfig, process, scoreLumaPlane, resetRun, reset (+1) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeEvents.kt` | SensorNativeEvent, FrameStats, Trigger, State, Diagnostic (+1) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt` | defaults, fromMap, clampDouble, clampInt, nativeCameraFacingFromWire |
| `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeControllerPreviewTimingTest.kt` | `schedules retry only when monitoring and both preview and provider are ready`, `becomes retry eligible once preview attaches after provider is available` |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewViewFactory.kt` | createPreviewView, detachPreviewView |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewPlatformView.kt` | dispose |

## Entry Points

Start here when exploring this area:

- **``schedules retry only when monitoring and both preview and provider are ready``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeControllerPreviewTimingTest.kt:7`
- **``becomes retry eligible once preview attaches after provider is available``** (Function) — `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeControllerPreviewTimingTest.kt:39`
- **`createPreviewView`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewViewFactory.kt:8`
- **`detachPreviewView`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewViewFactory.kt:17`
- **`dispose`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewPlatformView.kt:22`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `FrameStats` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeEvents.kt` | 3 |
| `Trigger` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeEvents.kt` | 15 |
| `State` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeEvents.kt` | 19 |
| `Diagnostic` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeEvents.kt` | 27 |
| `Error` | Class | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeEvents.kt` | 31 |
| ``schedules retry only when monitoring and both preview and provider are ready`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeControllerPreviewTimingTest.kt` | 7 |
| ``becomes retry eligible once preview attaches after provider is available`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeControllerPreviewTimingTest.kt` | 39 |
| `createPreviewView` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewViewFactory.kt` | 8 |
| `detachPreviewView` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewViewFactory.kt` | 17 |
| `dispose` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewPlatformView.kt` | 22 |
| `attachPreviewSurface` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 159 |
| `detachPreviewSurface` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 170 |
| `updateConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | 21 |
| `onHostResumed` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 134 |
| `dispose` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 152 |
| `startNativeMonitoring` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 203 |
| `warmupGpsSync` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 237 |
| `updateNativeConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 245 |
| ``detection math emits split triggers with cooldown and rearm parity`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | 39 |
| `defaults` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt` | 23 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `DetachPreviewSurface → SelectHighestFrameRateBounds` | cross_community | 8 |
| `UpdateConfig → CancelPendingAeAwbLock` | cross_community | 8 |
| `OnHostResumed → CancelPendingAeAwbLock` | cross_community | 7 |
| `DetachPreviewSurface → CancelPendingAeAwbLock` | cross_community | 7 |
| `DetachPreviewSurface → IsCurrentBinding` | cross_community | 7 |
| `DetachPreviewSurface → ShouldLockAeAwb` | cross_community | 7 |
| `RestartMonitoringBackend → CancelPendingAeAwbLock` | cross_community | 7 |
| `Run → SelectHighestFrameRateBounds` | cross_community | 7 |
| `UpdateConfig → SelectCameraFacing` | cross_community | 7 |
| `UpdateConfig → HandleUnlockedPolicyFailure` | cross_community | 7 |

## How to Explore

1. `gitnexus_context({name: "`schedules retry only when monitoring and both preview and provider are ready`"})` — see callers and callees
2. `gitnexus_query({query: "sensor_native"})` — find related execution flows
3. Read key files listed above for implementation details
