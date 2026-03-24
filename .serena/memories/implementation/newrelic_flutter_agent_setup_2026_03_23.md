Added New Relic Flutter monitoring to Sprint Sync.

Changes made:
- Added `newrelic_mobile: ^1.2.0` to `pubspec.yaml` and refreshed `pubspec.lock`.
- Wired `lib/main.dart` to:
  - read platform-specific tokens from `NEW_RELIC_ANDROID_TOKEN` / `NEW_RELIC_IOS_TOKEN`
  - start New Relic with `NewrelicMobile.instance.start(...)`
  - attach `NewRelicNavigationObserver` when monitoring is enabled
- Applied the Android agent plugin in:
  - `android/settings.gradle.kts`
  - `android/app/build.gradle.kts`
- Documented the `dart-define` tokens in `README.md`.

Verification:
- `flutter pub get` passed.
- `flutter analyze` passed after importing `package:newrelic_mobile/config.dart`.
- `flutter test` still has an unrelated existing failure in
  `test/race_session_screen_test.dart` (`monitoring stage shows preview marker overlay`).
- Android build could not be fully validated in this environment because Gradle
  hits `java.io.IOException: Unable to establish loopback connection`.
- `npx gitnexus analyze` succeeded, but `npx gitnexus wiki --force` failed because
  no LLM API key is configured in this environment.
