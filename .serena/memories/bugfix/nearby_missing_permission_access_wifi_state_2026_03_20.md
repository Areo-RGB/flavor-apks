Fixed runtime Nearby hosting failure 'startHosting failed: 8032: MISSING_PERMISSION_ACCESS_WIFI_STATE' by updating Android manifest permissions.

Root cause:
- Sprint Sync manifest lacked android.permission.ACCESS_WIFI_STATE (and related network/wifi state permissions), while reference sprint app includes them.
- Nearby Connections host path on affected device required ACCESS_WIFI_STATE.

Change made:
- android/app/src/main/AndroidManifest.xml:
  - added android.permission.ACCESS_NETWORK_STATE
  - added android.permission.ACCESS_WIFI_STATE
  - added android.permission.CHANGE_WIFI_STATE

Validation performed:
- Built debug APK successfully: flutter build apk --debug
- Installed on both connected devices (31071FDH2008FK, 4c637b9e) via adb install -r
- Verified on device via dumpsys:
  - ACCESS_WIFI_STATE present and granted=true
  - ACCESS_NETWORK_STATE present and granted=true
  - CHANGE_WIFI_STATE present and granted=true

User should retry startHosting after this install.