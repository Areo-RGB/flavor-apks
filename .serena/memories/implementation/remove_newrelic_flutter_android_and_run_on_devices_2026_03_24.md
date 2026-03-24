Removed New Relic integration from app to unblock Android debug builds and device runs.

Changes made:
- Removed New Relic startup/config/navigation observer from lib/main.dart. App now always starts directly with SprintSyncApp.
- Removed New Relic bridge dependency path from lib/features/race_session/race_session_controller.dart:
  - deleted import of NewRelicBridge
  - removed constructor injection parameter and field
  - made _trackNearby a no-op to preserve existing call sites without telemetry side effects
- Deleted lib/core/services/newrelic_bridge.dart.
- Removed newrelic_mobile dependency from pubspec.yaml.
- Removed Android Gradle New Relic plugin declarations:
  - android/settings.gradle.kts pluginManagement entry
  - android/app/build.gradle.kts app plugin id
- Ran flutter pub get to update lock/dependency graph.

Build/run validation:
- Initial failure after New Relic removal was due to full disk, not code errors.
- Ran flutter clean to reclaim disk (about 25 GB free).
- Built debug APK successfully: build/app/outputs/flutter-apk/app-debug.apk
- Installed and launched on both connected Android devices:
  - Pixel 7 (31071FDH2008FK): install success, app launched, pid present
  - CPH2399 (DMIFHU7HUG9PKVVK): install success, app launched, pid present
