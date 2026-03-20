Fixed startDiscovery error 8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION by aligning permissions with flutter/sprint implementation.

Root cause:
- Sprint Sync had ACCESS_COARSE_LOCATION/ACCESS_FINE_LOCATION declared with maxSdkVersion=30 and runtime requiredPermissions only requested coarse/fine on pre-S.
- On newer Android, requestPermissions returned granted=true without coarse in required list, then Nearby startDiscovery failed with 8034.

Changes made:
- AndroidManifest.xml:
  - ACCESS_COARSE_LOCATION: removed maxSdkVersion restriction (declared for all API levels)
  - ACCESS_FINE_LOCATION: removed maxSdkVersion restriction (declared for all API levels)
- MainActivity.kt requiredPermissions():
  - now always includes ACCESS_COARSE_LOCATION and ACCESS_FINE_LOCATION
  - still includes S+ bluetooth advertise/connect/scan and T+ nearby wifi devices

Validation:
- flutter build apk --debug succeeded
- Installed to both connected devices (31071FDH2008FK, 4c637b9e)
- Granted runtime permissions via adb pm grant for location + bt + nearby wifi
- dumpsys package confirms permissions declared and granted=true on device
- flutter analyze: no issues

Expected result:
- startDiscovery should no longer fail with 8034 missing coarse location.