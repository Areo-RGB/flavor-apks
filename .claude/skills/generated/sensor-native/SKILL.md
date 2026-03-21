---
name: sensor-native
description: "Skill for the Sensor_native area of photo-finish. 47 symbols across 7 files."
---

# Sensor_native

47 symbols | 7 files | Cohesion: 85%

## When to Use

- Working with code in `android/`
- Understanding how onPause, onDestroy, fromMap work
- Modifying sensor_native-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | onHostPaused, dispose, onMethodCall, startNativeMonitoring, stopNativeMonitoringInternal (+11) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeCameraSession.kt` | stop, scheduleAeAwbLock, applyCamera2Options, cancelPendingAeAwbLock, isCurrentBinding (+7) |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | updateConfig, resetRun, reset, process, scoreLumaPlane (+2) |
| `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | `detection math emits split triggers with cooldown and rearm parity`, `shouldLockAeAwb returns true only at and after warmup`, `selectHighestFrameRateBounds prefers highest upper then lower`, `selectHighestFrameRateBounds returns null for null or empty`, `sensor elapsed helpers and offset smoothing are stable` |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt` | fromMap, clampDouble, clampInt, defaults |
| `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | onPause, onDestroy |
| `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewPlatformView.kt` | dispose |

## Entry Points

Start here when exploring this area:

- **`onPause`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt:91`
- **`onDestroy`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt:98`
- **`fromMap`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt:23`
- **`updateConfig`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt:21`
- **`resetRun`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt:26`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `onPause` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 91 |
| `onDestroy` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 98 |
| `fromMap` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt` | 23 |
| `updateConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | 21 |
| `resetRun` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | 26 |
| `reset` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | 102 |
| `onHostPaused` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 58 |
| `dispose` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 63 |
| ``detection math emits split triggers with cooldown and rearm parity`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | 10 |
| `defaults` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeModels.kt` | 13 |
| `process` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | 36 |
| `scoreLumaPlane` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMath.kt` | 107 |
| `analyze` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 88 |
| ``shouldLockAeAwb returns true only at and after warmup`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | 83 |
| ``selectHighestFrameRateBounds prefers highest upper then lower`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | 61 |
| ``selectHighestFrameRateBounds returns null for null or empty`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | 77 |
| `dispose` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativePreviewPlatformView.kt` | 24 |
| `attachPreviewSurface` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 67 |
| `detachPreviewSurface` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/sensor_native/SensorNativeController.kt` | 73 |
| ``sensor elapsed helpers and offset smoothing are stable`` | Function | `android/app/src/test/kotlin/com/paul/sprintsync/sensor_native/SensorNativeMathTest.kt` | 46 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `DetachPreviewSurface → CancelPendingAeAwbLock` | cross_community | 7 |
| `AttachPreviewSurface → CancelPendingAeAwbLock` | cross_community | 7 |
| `DetachPreviewSurface → SelectHighestFrameRateBounds` | cross_community | 6 |
| `DetachPreviewSurface → IsCurrentBinding` | cross_community | 6 |
| `DetachPreviewSurface → ShouldLockAeAwb` | cross_community | 6 |
| `AttachPreviewSurface → SelectHighestFrameRateBounds` | cross_community | 6 |
| `AttachPreviewSurface → IsCurrentBinding` | cross_community | 6 |
| `AttachPreviewSurface → ShouldLockAeAwb` | cross_community | 6 |
| `OnPause → CancelPendingAeAwbLock` | cross_community | 5 |
| `OnPause → EmitEvent` | cross_community | 5 |

## How to Explore

1. `gitnexus_context({name: "onPause"})` — see callers and callees
2. `gitnexus_query({query: "sensor_native"})` — find related execution flows
3. Read key files listed above for implementation details
