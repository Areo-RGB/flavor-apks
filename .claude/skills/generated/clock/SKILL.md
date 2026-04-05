---
name: clock
description: "Skill for the Clock area of photo-finish. 6 symbols across 3 files."
---

# Clock

6 symbols | 3 files | Cohesion: 71%

## When to Use

- Working with code in `android/`
- Understanding how computeGpsFixAgeNanos, estimateLocalSensorNanosNow, nowElapsedNanos work
- Modifying clock-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt` | nowElapsedNanos, elapsedToSensorNanos, computeGpsFixAgeNanos |
| `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | computeGpsFixAgeNanos, estimateLocalSensorNanosNow |
| `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | formatElapsedDisplay |

## Entry Points

Start here when exploring this area:

- **`computeGpsFixAgeNanos`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt:705`
- **`estimateLocalSensorNanosNow`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt:709`
- **`nowElapsedNanos`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt:5`
- **`elapsedToSensorNanos`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt:14`
- **`computeGpsFixAgeNanos`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt:21`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `computeGpsFixAgeNanos` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 705 |
| `estimateLocalSensorNanosNow` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/features/race_session/RaceSessionController.kt` | 709 |
| `nowElapsedNanos` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt` | 5 |
| `elapsedToSensorNanos` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt` | 14 |
| `computeGpsFixAgeNanos` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/clock/ClockDomain.kt` | 21 |
| `formatElapsedDisplay` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt` | 899 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `OnSensorEvent → NowElapsedNanos` | cross_community | 4 |

## How to Explore

1. `gitnexus_context({name: "computeGpsFixAgeNanos"})` — see callers and callees
2. `gitnexus_query({query: "clock"})` — find related execution flows
3. Read key files listed above for implementation details
