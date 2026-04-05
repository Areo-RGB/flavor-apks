---
name: repositories
description: "Skill for the Repositories area of photo-finish. 4 symbols across 2 files."
---

# Repositories

4 symbols | 2 files | Cohesion: 100%

## When to Use

- Working with code in `android/`
- Understanding how loadLastRun, fromJsonString, saveLastRun work
- Modifying repositories-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt` | loadLastRun, saveLastRun |
| `android/app/src/main/kotlin/com/paul/sprintsync/core/models/AppModels.kt` | fromJsonString, toJsonString |

## Entry Points

Start here when exploring this area:

- **`loadLastRun`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt:33`
- **`fromJsonString`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/models/AppModels.kt:17`
- **`saveLastRun`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt:39`
- **`toJsonString`** (Function) — `android/app/src/main/kotlin/com/paul/sprintsync/core/models/AppModels.kt:9`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `loadLastRun` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt` | 33 |
| `fromJsonString` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/models/AppModels.kt` | 17 |
| `saveLastRun` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/repositories/LocalRepository.kt` | 39 |
| `toJsonString` | Function | `android/app/src/main/kotlin/com/paul/sprintsync/core/models/AppModels.kt` | 9 |

## How to Explore

1. `gitnexus_context({name: "loadLastRun"})` — see callers and callees
2. `gitnexus_query({query: "repositories"})` — find related execution flows
3. Read key files listed above for implementation details
