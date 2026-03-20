import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';

class MotionDetectionScreen extends StatefulWidget {
  const MotionDetectionScreen({
    super.key,
    required this.controller,
    this.showPreview = true,
  });

  final MotionDetectionController controller;
  final bool showPreview;

  @override
  State<MotionDetectionScreen> createState() => _MotionDetectionScreenState();
}

class _MotionDetectionScreenState extends State<MotionDetectionScreen>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (widget.showPreview) {
      widget.controller.initializeCamera();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!widget.showPreview) {
      return;
    }
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused) {
      widget.controller.stopDetection();
    }
    if (state == AppLifecycleState.resumed) {
      widget.controller.initializeCamera();
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, child) {
        final config = widget.controller.config;
        final stats = widget.controller.latestStats;

        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (widget.showPreview)
              _buildPreviewCard(
                widget.controller.cameraController,
                roiCenterX: config.roiCenterX,
              ),
            if (widget.showPreview) const SizedBox(height: 12),
            _buildStopwatchCard(),
            const SizedBox(height: 12),
            _buildCurrentSplitsCard(),
            const SizedBox(height: 12),
            _buildLastRunCard(),
            const SizedBox(height: 12),
            Card(
              child: ExpansionTile(
                title: const Text('Advanced Detection'),
                childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                children: [
                  Text('Threshold: ${config.threshold.toStringAsFixed(3)}'),
                  Slider(
                    key: const ValueKey<String>('threshold_slider'),
                    value: config.threshold,
                    min: 0.001,
                    max: 0.08,
                    onChanged: (value) =>
                        widget.controller.updateThreshold(value),
                  ),
                  Text('ROI center: ${config.roiCenterX.toStringAsFixed(2)}'),
                  Slider(
                    key: const ValueKey<String>('roi_center_slider'),
                    value: config.roiCenterX,
                    min: 0.20,
                    max: 0.80,
                    onChanged: (value) =>
                        widget.controller.updateRoiCenter(value),
                  ),
                  Text('ROI width: ${config.roiWidth.toStringAsFixed(2)}'),
                  Slider(
                    key: const ValueKey<String>('roi_width_slider'),
                    value: config.roiWidth,
                    min: 0.05,
                    max: 0.40,
                    onChanged: (value) =>
                        widget.controller.updateRoiWidth(value),
                  ),
                  Text('Cooldown: ${config.cooldownMs} ms'),
                  Slider(
                    key: const ValueKey<String>('cooldown_slider'),
                    value: config.cooldownMs.toDouble(),
                    min: 300,
                    max: 2000,
                    onChanged: (value) =>
                        widget.controller.updateCooldown(value.round()),
                  ),
                  const Divider(),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Live Stats',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Raw score: ${stats?.rawScore.toStringAsFixed(4) ?? '-'}',
                  ),
                  Text(
                    'Baseline: ${stats?.baseline.toStringAsFixed(4) ?? '-'}',
                  ),
                  Text(
                    'Effective: ${stats?.effectiveScore.toStringAsFixed(4) ?? '-'}',
                  ),
                  Text('Timestamp: ${stats?.timestampMicros ?? '-'}'),
                  Text(
                    'Frames: ${widget.controller.processedFrameCount}'
                    '/${widget.controller.streamFrameCount}',
                  ),
                  const Divider(),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Recent Triggers',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                  const SizedBox(height: 6),
                  if (widget.controller.triggerHistory.isEmpty)
                    const Align(
                      alignment: Alignment.centerLeft,
                      child: Text('No trigger events yet.'),
                    )
                  else
                    ...widget.controller.triggerHistory.map((event) {
                      final label = switch (event.type) {
                        MotionTriggerType.start => 'START',
                        MotionTriggerType.stop => 'STOP',
                        MotionTriggerType.split => formatSplitLabel(
                          event.splitIndex,
                        ),
                      };
                      return Align(
                        alignment: Alignment.centerLeft,
                        child: Text(
                          '$label at ${event.triggerMicros}us '
                          '(score ${event.score.toStringAsFixed(4)})',
                        ),
                      );
                    }),
                ],
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildStopwatchCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Sprint Stopwatch',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Status: ${widget.controller.runStatusLabel}',
              key: const ValueKey<String>('run_status_text'),
            ),
            Text('Marks: ${widget.controller.currentSplitMicros.length}'),
            const SizedBox(height: 12),
            const Text('Timer'),
            Text(
              widget.controller.elapsedDisplay,
              key: const ValueKey<String>('stopwatch_timer_text'),
              style: const TextStyle(
                fontSize: 56,
                fontWeight: FontWeight.w500,
                height: 1,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildCurrentSplitsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Current Run Marks',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            if (widget.controller.currentSplitMicros.isEmpty)
              const Text('No finish mark yet.')
            else
              ...widget.controller.currentSplitMicros.asMap().entries.map((
                entry,
              ) {
                final splitIndex = entry.key + 1;
                final isFinish =
                    !widget.controller.isRunActive &&
                    splitIndex == widget.controller.currentSplitMicros.length;
                final label = isFinish
                    ? 'Finish'
                    : formatSplitLabel(splitIndex);
                return Text(
                  '$label: ${formatDurationMicros(entry.value)}',
                  key: ValueKey<String>('current_split_$splitIndex'),
                );
              }),
          ],
        ),
      ),
    );
  }

  Widget _buildLastRunCard() {
    final lastRun = widget.controller.lastRun;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Last Saved Run',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            if (lastRun == null || lastRun.splitMicros.isEmpty)
              const Text('No saved run yet.')
            else
              ...lastRun.splitMicros.asMap().entries.map((entry) {
                final splitIndex = entry.key + 1;
                final isFinish = splitIndex == lastRun.splitMicros.length;
                final label = isFinish
                    ? 'Finish'
                    : formatSplitLabel(splitIndex);
                return Text(
                  '$label: ${formatDurationMicros(entry.value)}',
                  key: ValueKey<String>('saved_split_$splitIndex'),
                );
              }),
          ],
        ),
      ),
    );
  }

  Widget _buildPreviewCard(
    CameraController? controller, {
    required double roiCenterX,
  }) {
    final statusColor = const Color(0xFF005A8D);
    final tripwireAlignmentX = _tripwireAlignmentForRoiCenter(roiCenterX);

    late final Widget previewChild;
    late final double previewAspectRatio;

    if (controller != null && controller.value.isInitialized) {
      previewChild = CameraPreview(controller);
      previewAspectRatio = controller.value.aspectRatio;
    } else {
      previewChild = const Center(child: Text('Camera preview unavailable'));
      previewAspectRatio = 9 / 16;
    }

    return Card(
      clipBehavior: Clip.antiAlias,
      child: AspectRatio(
        aspectRatio: previewAspectRatio,
        child: Stack(
          fit: StackFit.expand,
          children: [
            ColoredBox(color: Colors.black12, child: previewChild),
            Align(
              key: const ValueKey<String>('preview_tripwire_alignment'),
              alignment: Alignment(tripwireAlignmentX, 0),
              child: IgnorePointer(
                child: Container(
                  key: const ValueKey<String>('preview_tripwire_line'),
                  width: 2,
                  height: double.infinity,
                  color: statusColor,
                ),
              ),
            ),
            IgnorePointer(
              child: DecoratedBox(
                key: const ValueKey<String>('preview_status_border'),
                decoration: BoxDecoration(
                  border: Border.all(color: statusColor, width: 3),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  double _tripwireAlignmentForRoiCenter(double roiCenterX) {
    final clamped = roiCenterX.clamp(0.0, 1.0);
    return (clamped * 2.0) - 1.0;
  }
}
