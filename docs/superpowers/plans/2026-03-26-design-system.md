# Sprint Sync Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a rigorous Jetpack Compose Design System (Theme, Typography, Colors, Shapes, and Reusable Components) to enforce visual consistency across the Sprint Sync app. 

**Architecture:** Create a `theme` package with standard Compose foundation files (`Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`). Define specific branding colors for both Light and Dark mode. Extract standard UI elements into a `components` package with strict API contracts so the main UI code becomes fully declarative and completely decoupled from styling modifiers (like hardcoded paddings and colors). 

**Tech Stack:** Kotlin, Android Jetpack Compose, Material3

---

## 1. Design Tokens Specification

Before implementing code, the following tokens must be established to ensure the design is truly cohesive.

### Color Palette (M3 ColorScheme mapping)
**Primary:** `#005A8D` (Sprint Blue) — Used for primary actions (Start, Connect, Host)  
**Secondary:** `#D97706` (Amber/Warning) — Used for warnings (Clock sync issues)  
**Tertiary:** `#10B981` (Emerald Green) — Used for active monitoring and successful states  
**Error:** `#EF4444` (Red) — Used for critical failures and disconnection drops  
**Surface/Background (Dark):** `#121212`, Surface: `#1E1E1E`  
**Surface/Background (Light):** `#FAFAFA`, Surface: `#FFFFFF`  

### Typography Scale
We will customize the Material3 Typography object:
- **Display Large / Medium / Small:** Used for headers (e.g., "Sprint Sync", huge stopwatch timer).
- **Title Large / Medium:** Section headers (e.g., "Network & Connection").
- **Body Large / Medium:** Standard info text.
- **Label Medium:** Used for component badges (e.g., Role chips, FPS tags).
- **Monospace Override:** The `StopwatchCard` and `AdvancedDetectionCard` metrics MUST use `FontFamily.Monospace` with `fontFeatureSettings = "tnum"` (Tabular numbers) to prevent UI jittering.

### Shapes
- **Small:** `RoundedCornerShape(8.dp)` for Buttons, Chips, and Input Fields.
- **Medium:** `RoundedCornerShape(16.dp)` for standard Cards (`SprintSyncCard`).
- **Large:** `RoundedCornerShape(24.dp)` for modal bottom sheets and dialogs.

---

## 2. Component API Contracts

The following reusable components must be built with these exact signatures to restrict ad-hoc styling in `SprintSyncApp.kt`.

1. **`SprintSyncCard`**
   ```kotlin
   @Composable
   fun SprintSyncCard(
       modifier: Modifier = Modifier,
       highlightIntent: CardHighlightIntent = CardHighlightIntent.NONE,
       content: @Composable ColumnScope.() -> Unit
   )
   ```
   *Intent mappings:* `NONE` (Surface color), `ACTIVE` (Emerald tint), `WARNING` (Amber tint), `ERROR` (Red tint).

2. **`SectionHeader`**
   ```kotlin
   @Composable
   fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier)
   ```

3. **`PrimaryButton` & `SecondaryButton`**
   ```kotlin
   @Composable
   fun PrimaryButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true)
   ```

4. **`MetricDisplay`**
   ```kotlin
   @Composable
   fun MetricDisplay(label: String, value: String, unit: String? = null)
   ```
   *Usage:* Used replacing raw `Text("Threshold: 0.006")` in `AdvancedDetectionCard`.

---

## 3. Execution Tasks

### Task 1: Foundation (Tokens & Theme)

**Files:**
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/Color.kt`
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/Type.kt`
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/Shape.kt`
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/Theme.kt`

- [ ] **Step 1: Write Color, Type, Shape implementations**
Implement the exact Hex codes, Typography overrides with Tabular Numbers (`tnum`), and Shapes defined in Section 1.

- [ ] **Step 2: Write Theme implementation**
Implement `SprintSyncTheme` resolving Dark/Light modes cleanly.

- [ ] **Step 3: Run build to verify Compose compilation**
Run: `cd android && gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**
```bash
git add android/app/src/main/kotlin/com/paul/sprintsync/ui/theme/
git commit -m "feat(ui): implement base design system tokens (colors, typography, shapes, theme)"
```

### Task 2: Component Library Construction

**Files:**
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/components/SprintSyncCard.kt`
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/components/Buttons.kt`
- Create: `android/app/src/main/kotlin/com/paul/sprintsync/ui/components/TypographyOverrides.kt`

- [ ] **Step 1: Implement `SprintSyncCard` and `CardHighlightIntent`**
Ensure it uses a standard `12.dp` or `16.dp` inner padding by default so the parent doesn't need to specify it.

- [ ] **Step 2: Implement Buttons and `SectionHeader`**
Make sure all buttons use standard spacing (`Arrangement.spacedBy(8.dp)`) between text and optional icons.

- [ ] **Step 3: Run build to verify Compose compilation**
Run: `cd android && gradlew.bat :app:assembleDebug`

- [ ] **Step 4: Commit**
```bash
git add android/app/src/main/kotlin/com/paul/sprintsync/ui/components/
git commit -m "feat(ui): construct core reusable design system components"
```

### Task 3: App-Wide Refactoring & Integration

**Files:**
- Modify: `android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt`

- [ ] **Step 1: Wrap App Content in Theme**
Modify `MainActivity.kt` to wrap `SprintSyncApp()` inside `SprintSyncTheme { ... }`.

- [ ] **Step 2: Rewrite `SprintSyncApp.kt` using Component Library**
- Replace all `Card { Column(modifier = Modifier.padding(12.dp)) { ... } }` with `SprintSyncCard { ... }`.
- Insert `SectionHeader` above logically grouped cards.
- Replace all raw `Button` and `OutlinedButton` with `PrimaryButton` and `SecondaryButton`.
- Update `StopwatchCard` to use the new tabular typography mapping.
- Apply `CardHighlightIntent.ACTIVE` on `ConnectedDevicesListCard` when devices > 0.

- [ ] **Step 3: Run build to verify refactored code compilation**
Run: `cd android && gradlew.bat :app:assembleDebug`

- [ ] **Step 4: Commit**
```bash
git add android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt android/app/src/main/kotlin/com/paul/sprintsync/SprintSyncApp.kt
git commit -m "refactor(ui): convert SprintSyncApp to utilize standardized design system"
```
