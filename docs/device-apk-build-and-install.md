# Device APK Build + Install (TCP Static IP)

This branch builds three APKs with fixed roles and TCP transport.

## Profiles

- `hostXiaomi`: auto-starts TCP host server on launch (`192.168.0.103:9000`; Xiaomi Pad 7 DHCP reservation)
- `clientPixel`: auto-starts TCP client and connects to host
- `clientOneplus`: auto-starts TCP client and connects to host
- `clientHuawei`: auto-starts TCP client and connects to host
- `clientXiaomi`: auto-starts TCP client and connects to host

## Build Commands

Run from repository root:

```bash
cd android
./gradlew :app:assembleHostXiaomiDebug :app:assembleClientPixelDebug :app:assembleClientOneplusDebug :app:assembleClientHuaweiDebug :app:assembleClientXiaomiDebug
```

Windows PowerShell:

```powershell
cd android
.\gradlew.bat :app:assembleHostXiaomiDebug :app:assembleClientPixelDebug :app:assembleClientOneplusDebug :app:assembleClientHuaweiDebug :app:assembleClientXiaomiDebug
```

## Output APKs

- `android/app/build/outputs/apk/hostXiaomi/debug/app-hostXiaomi-debug.apk`
- `android/app/build/outputs/apk/clientPixel/debug/app-clientPixel-debug.apk`
- `android/app/build/outputs/apk/clientOneplus/debug/app-clientOneplus-debug.apk`
- `android/app/build/outputs/apk/clientHuawei/debug/app-clientHuawei-debug.apk`
- `android/app/build/outputs/apk/clientXiaomi/debug/app-clientXiaomi-debug.apk`

## Install (ADB examples)

```bash
adb -s <xiaomi-serial> install -r android/app/build/outputs/apk/hostXiaomi/debug/app-hostXiaomi-debug.apk
adb -s <pixel-serial> install -r android/app/build/outputs/apk/clientPixel/debug/app-clientPixel-debug.apk
adb -s <oneplus-serial> install -r android/app/build/outputs/apk/clientOneplus/debug/app-clientOneplus-debug.apk
adb -s <huawei-serial> install -r android/app/build/outputs/apk/clientHuawei/debug/app-clientHuawei-debug.apk
adb -s <xiaomi-client-serial> install -r android/app/build/outputs/apk/clientXiaomi/debug/app-clientXiaomi-debug.apk
```

## Router Requirements

- Xiaomi pad (host) must be reachable at `192.168.0.103` (DHCP reservation recommended).
- All devices must be on the same LAN subnet.
- AP/client isolation must be disabled.
