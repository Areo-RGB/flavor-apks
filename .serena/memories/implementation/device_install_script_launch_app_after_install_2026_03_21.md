Updated scripts/install-debug-apk.mjs to launch the app after successful install on each ready adb device.

Changes:
- Added appId constant: com.paul.sprintsync.
- Added launch tracking counter: failedLaunches.
- After install success per device, run:
  adb -s <deviceId> shell monkey -p com.paul.sprintsync -c android.intent.category.LAUNCHER 1
- Launch success criteria: exit status 0 and output does not contain "No activities found" or "Error".
- Script now exits non-zero if any launch fails (after install pass).
- Final success message changed to "Installed and launched debug APK on <n> device(s)."

Verification:
- node --check scripts/install-debug-apk.mjs (pass).