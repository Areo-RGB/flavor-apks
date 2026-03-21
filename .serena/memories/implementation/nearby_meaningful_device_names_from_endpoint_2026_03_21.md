Implemented meaningful device naming for race session device roles by propagating Nearby endpoint names and defaulting local endpoint names to Android model.

Changes:
- android/app/src/main/kotlin/com/paul/sprintsync/MainActivity.kt
  - Added endpointNamesById map to retain endpoint names across lifecycle callbacks.
  - Added localEndpointName() helper returning Build.MODEL fallback Build.DEVICE/"Android Device".
  - startHosting/requestConnection now use effectiveEndpointName derived from localEndpointName.
  - onConnectionInitiated stores endpointName from ConnectionInfo.
  - connection_result events now include endpointName for reject/accept-fail/success paths.
  - clearTransientState/clearEndpointState now clear endpointNamesById entries.
- lib/core/services/nearby_bridge.dart
  - NearbyConnectionResultEvent now includes optional endpointName parsed from event payload.
- lib/features/race_session/race_session_controller.dart
  - Host-side connection handling now prefers connection.endpointName, then discovered name, then Device <endpointId> fallback.
- test/race_session_controller_test.dart
  - Added test: host uses endpointName from connection_result for device label.

Verification:
- dart format on touched Dart files.
- flutter test test/race_session_controller_test.dart -> pass.
- flutter test test/race_session_screen_test.dart -> pass.
- android/.\gradlew.bat :app:compileDebugKotlin -> BUILD SUCCESSFUL.