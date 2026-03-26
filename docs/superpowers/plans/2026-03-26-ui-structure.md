# UI Structure & UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a structured UI with a TopAppBar header, bring visual hierarchy using Jetpack Compose, and enhance typography, padding, and state visual cues.

**Architecture:** 
1. **Header & Navigation:** Use Material3 `CenterAlignedTopAppBar` inside the `Scaffold`'s `topBar` slot in `SprintSyncApp.kt` to act as a header giving context on the current stage (`uiState.stage`). 
2. **Sections (`LazyColumn`):** Refactor the `LazyColumn` content by grouping related cards with Text headers (e.g., "Network & Connection", "Configuration", "Live Preview") to make the interface self-explanatory.
3. **Card UX:** Improve visual hierarchy within cards by adding Material Icons suitable for the context, accentuating primary buttons (e.g., `Button` vs `OutlinedButton`), and tweaking Card elevation/colors depending on states (e.g., green tint for "Monitoring").
4. **Legibility & Typography:** Ensure the stopwatch and timing metrics use a monospace or tabular number format so values don't jitter horizontally as they tick.

**Tech Stack:** Kotlin, Android Jetpack Compose, Material3

---

### Task 1: Add CenterAlignedTopAppBar and Section Headers

**Files:**
- Modify: `android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt`

- [ ] **Step 1: Write header and section implementation**

Modify `SprintSyncApp.kt` to:
1. Add `@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)` to the `SprintSyncApp` composable if not already present.
2. In the `Scaffold(topBar = { ... })` section, insert a `CenterAlignedTopAppBar` displaying the app's title ("Sprint Sync") and the current Stage label as a subtitle or leading icon.
3. Add `item { SectionHeader("Networking") }` composables in the LazyColumn to group features cleanly.
   - SETUP: "Networking", "Local Network Devices"
   - LOBBY: "Role Configuration", "Synchronization"
   - MONITORING: "Live Telemetry", "Detection", "Logs"

- [ ] **Step 2: Run build to verify Compose compilation**

Run: `cd android && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt
git commit -m "feat: setup app header and top-level visual section grouping"
```

---

### Task 2: Enhance Card UI with Icons and State Colors

**Files:**
- Modify: `android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt`

- [ ] **Step 1: Write Card UX improvements**

Modify `SprintSyncApp.kt` to:
1. Introduce relevant `Icon`s into Buttons and Headers. e.g., `Icons.Default.PlayArrow` for Start Monitoring, `Icons.Default.Stop` for Stop Monitoring, `Icons.Default.Settings` for the advanced setup card.
2. For cards that have active successful states (like `ConnectedDevicesListCard` when devices exist, or `StopwatchCard` when running), set a subtle surface color (`MaterialTheme.colorScheme.primaryContainer` or `secondaryContainer`).
3. For Warnings (like the `ClockWarningCard`), use `MaterialTheme.colorScheme.errorContainer`.

- [ ] **Step 2: Run build to verify Compose compilation**

Run: `cd android && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt
git commit -m "style: enrich cards with icons and contextual state colors"
```

---

### Task 3: Improve Legibility & Typography (Stopwatch Tabular Nums)

**Files:**
- Modify: `android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt`

- [ ] **Step 1: Write typography improvements**

Modify `SprintSyncApp.kt` to:
1. Find the Text composable rendering the `elapsedDisplay` inside `StopwatchCard` (e.g., "00:00.000").
2. Apply `fontFamily = FontFamily.Monospace` or `style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum")`.
3. Separate the minutes/seconds from milliseconds slightly using varying font sizes (Seconds huge, millis slightly smaller) to make it look like a physical stopwatch.

- [ ] **Step 2: Run build to verify Compose compilation**

Run: `cd android && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt
git commit -m "style: implement monospace tabular numbers for stopwatch legibility"
```
