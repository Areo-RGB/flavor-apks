# Sprint Sync Android (Kotlin)

This repository contains the Android-native Kotlin/Compose app.

Cross-platform app code lives in a separate companion repository in this workspace.

## Build and Install (Windows)

1. `npm run build:debug:apk`
2. `npm run install:debug:devices`
3. `npm run run:debug:devices`

## Native Test Command

`cd android && gradlew.bat :app:testDebugUnitTest`

## Android Quality Commands

From `android/`:

- `./gradlew detekt`
- `./gradlew ktlintCheck`
- `./gradlew ktlintFormat`
- `./gradlew jacocoTestReport`
- `./gradlew dependencyUpdates`

## Notes

- This repository is Android-only.
- Device details are tracked in `device_specs.md`.
