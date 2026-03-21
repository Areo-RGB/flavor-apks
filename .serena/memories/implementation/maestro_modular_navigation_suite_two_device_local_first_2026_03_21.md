Implemented modular Maestro navigation suite (two-device, local-first) for Setup -> Lobby -> Monitoring -> Lobby.

Code changes:
- Updated lib/features/race_session/race_session_screen.dart with stable ValueKeys for navigation-critical UI:
  - setup_stage_title, lobby_stage_title, monitoring_stage_title
  - permissions_button, host_button, join_button, next_button
  - start_monitoring_button, stop_monitoring_button
- Updated package.json scripts:
  - test:maestro:navigation
  - test:maestro:navigation:continuous

Maestro flow structure added under new-workspace/.maestro/flows:
- navigation/navigation_core.yaml (tagged entrypoint: navigation, core)
- shared/setup_host_join.yaml
- shared/go_to_lobby.yaml
- shared/start_monitoring.yaml
- shared/stop_monitoring.yaml

Flow design details:
- Uses runFlow modular composition and env passing for titles.
- Includes guard assertion that Start Monitoring is disabled before role assignment, then enabled after assigning Start/Stop roles.
- Waits for Next button availability before transition to lobby.
- Verifies monitoring entry and host stop returning to lobby.

Compatibility update:
- new-workspace/test.yaml now delegates to .maestro/flows/navigation/navigation_core.yaml.

Validation:
- flutter analyze lib/features/race_session/race_session_screen.dart: clean
- maestro --version: 2.3.0