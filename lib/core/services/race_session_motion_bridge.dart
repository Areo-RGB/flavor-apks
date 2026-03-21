import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

void ingestStandalonePulse(
  MotionDetectionController controller,
  MotionTriggerEvent trigger,
) {
  final type = controller.isRunActive
      ? MotionTriggerType.split
      : MotionTriggerType.start;
  controller.ingestTrigger(
    MotionTriggerEvent(
      triggerSensorNanos: trigger.triggerSensorNanos,
      score: trigger.score,
      type: type,
      splitIndex: controller.currentSplitElapsedNanos.length + 1,
    ),
    forwardToSync: false,
  );
}

void syncMotionControllerFromTimeline(
  MotionDetectionController controller,
  SessionRaceTimeline timeline, {
  int? startedSensorNanosOverride,
}) {
  controller.resetRace();
  final timelineStartedSensorNanos = timeline.startedSensorNanos;
  if (timelineStartedSensorNanos == null) return;
  final startedSensorNanos =
      startedSensorNanosOverride ?? timelineStartedSensorNanos;

  controller.ingestTrigger(
    MotionTriggerEvent(
      triggerSensorNanos: startedSensorNanos,
      score: 0,
      type: MotionTriggerType.start,
      splitIndex: 0,
    ),
    forwardToSync: false,
  );

  for (int i = 0; i < timeline.splitElapsedNanos.length; i += 1) {
    controller.ingestTrigger(
      MotionTriggerEvent(
        triggerSensorNanos: startedSensorNanos + timeline.splitElapsedNanos[i],
        score: 0,
        type: MotionTriggerType.split,
        splitIndex: i + 1,
      ),
      forwardToSync: false,
    );
  }

  final stopElapsed = timeline.stopElapsedNanos;
  if (stopElapsed == null) return;
  controller.ingestTrigger(
    MotionTriggerEvent(
      triggerSensorNanos: startedSensorNanos + stopElapsed,
      score: 0,
      type: MotionTriggerType.stop,
      splitIndex: 0,
    ),
    forwardToSync: false,
  );
}
