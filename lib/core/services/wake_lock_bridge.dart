import 'package:wakelock_plus/wakelock_plus.dart';

class WakeLockBridge {
  Future<void> enable() {
    return WakelockPlus.enable();
  }

  Future<void> disable() {
    return WakelockPlus.disable();
  }

  Future<void> toggle({required bool enable}) {
    return WakelockPlus.toggle(enable: enable);
  }
}
