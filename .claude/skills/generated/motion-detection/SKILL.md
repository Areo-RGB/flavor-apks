---
name: motion-detection
description: "Skill for the Motion_detection area of photo-finish. 9 symbols across 3 files."
---

# Motion_detection

9 symbols | 3 files | Cohesion: 89%

## When to Use

- Working with code in `android/`
- Understanding how toJsonString, updateConfig, startMonitoring work
- Modifying motion_detection-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt` | toJsonString, fromWireName, defaults, fromJsonString |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | updateConfig, startMonitoring, toNativeConfig |
| `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt` | saveMotionConfig, loadMotionConfig |

## Entry Points

Start here when exploring this area:

- **`toJsonString`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt:28`
- **`updateConfig`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt:56`
- **`startMonitoring`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt:87`
- **`saveMotionConfig`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt:27`
- **`fromWireName`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt:11`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `toJsonString` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt` | 28 |
| `updateConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | 56 |
| `startMonitoring` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | 87 |
| `saveMotionConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt` | 27 |
| `fromWireName` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt` | 11 |
| `defaults` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt` | 40 |
| `fromJsonString` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionModels.kt` | 51 |
| `loadMotionConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt` | 21 |
| `toNativeConfig` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/motion_detection/MotionDetectionController.kt` | 141 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `UpdateConfig → CancelPendingAeAwbLock` | cross_community | 8 |
| `UpdateConfig → SelectCameraFacing` | cross_community | 7 |
| `UpdateConfig → HandleUnlockedPolicyFailure` | cross_community | 7 |
| `UpdateConfig → EmitEvent` | cross_community | 6 |
| `UpdateConfig → CurrentTargetFpsUpper` | cross_community | 5 |
| `UpdateConfig → ShouldSchedulePreviewRebindRetry` | cross_community | 5 |
| `UpdateConfig → LogRuntimeDiagnostic` | cross_community | 5 |
| `UpdateConfig → CancelPreviewRebindRetries` | cross_community | 5 |
| `UpdateConfig → UpdateConfig` | cross_community | 3 |
| `UpdateConfig → ToJsonString` | intra_community | 3 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Sensor_native | 1 calls |

## How to Explore

1. `gitnexus_context({name: "toJsonString"})` — see callers and callees
2. `gitnexus_query({query: "motion_detection"})` — find related execution flows
3. Read key files listed above for implementation details
