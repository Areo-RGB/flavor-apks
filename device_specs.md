# Device Specs

Connected devices detected via ADB on 2026-03-24 (Europe/Berlin):

| Device Name | Manufacturer | SENSOR_INFO_TIMESTAMP_SOURCE | Reported Value | Camera2 Hardware Level by Camera ID | Likely App Front Camera ID | Likely App Rear Camera ID |
| --- | --- | --- | --- | --- | --- | --- |
| Pixel 7 | Google | Yes | REALTIME | 0:FULL, 1:FULL | 1 | 0 |
| 2410CRP4CG | Xiaomi | Yes | REALTIME | 0:LEVEL_3, 1:LEVEL_3, 2:LEVEL_3, 3:LEVEL_3 | 1 | 0 |
| CPH2399 | OnePlus | Yes | REALTIME | 0:LEVEL_3, 1:FULL, 2:FULL, 3:LIMITED, 4:FULL, 5:FULL | 1 | 0 (default rear), with additional rear IDs 2,3,4,5 |
| EML-L29 | HUAWEI | Yes | REALTIME | 0:LIMITED, 1:LIMITED, 2:LIMITED, 3:LIMITED | 1 | 0 (default rear), with additional rear IDs 2,3 |

## Relevant Camera Abilities (Likely App Front/Rear)

Values below are extracted from Camera2 static metadata for the camera IDs most likely selected when the app requests front/rear by facing.

| Device | Rear Camera (ID) | Rear HW Level | Rear Max AE FPS (normal mode upper bound) | Rear High-Speed Max FPS (metadata) | Rear Timestamp Source | Rear High-Speed Capability Flag | Rear Video Stabilization | Rear OIS | Front Camera (ID) | Front HW Level | Front Max AE FPS (normal mode upper bound) | Front High-Speed Max FPS (metadata) | Front Timestamp Source | Front High-Speed Capability Flag | Front Video Stabilization | Front OIS |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pixel 7 | 0 | FULL | 30 | 120 | REALTIME | Yes | Yes | Yes | 1 | FULL | 24 | N/A | REALTIME | No | Yes | No |
| 2410CRP4CG | 0 | LEVEL_3 | 24 | N/A | REALTIME | No | Yes | No | 1 | LEVEL_3 | 15 | N/A | REALTIME | No | Yes | No |
| CPH2399 | 0 | LEVEL_3 | 15 | 120 | REALTIME | Yes | Yes | No | 1 | FULL | 15 | 120 (listed, but capability flag absent) | REALTIME | No | Yes | No |
| EML-L29 | 0 | LIMITED | 15 | 120 | REALTIME | Yes | Yes | No | 1 | LIMITED | 15 | N/A | REALTIME | No | Yes | No |

Note: `Max AE FPS` is derived from `android.control.aeAvailableTargetFpsRanges` and represents normal camera session target range upper bound, not guaranteed delivered frame rate.

Note: `High-Speed Max FPS` is derived from `android.control.availableHighSpeedVideoConfigurations` (for example `1920x1080 30-120`) and may require constrained high-speed session setup. On CPH2399 front camera, high-speed metadata is listed even though `CONSTRAINED_HIGH_SPEED_VIDEO` is not present in capability flags.
