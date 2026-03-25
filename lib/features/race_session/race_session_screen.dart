import 'package:flutter/material.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

class RaceSessionScreen extends StatefulWidget {
  const RaceSessionScreen({
    super.key,
    required this.controller,
    required this.motionController,
  });

  final RaceSessionController controller;
  final MotionDetectionController motionController;

  @override
  State<RaceSessionScreen> createState() => _RaceSessionScreenState();
}

class _RaceSessionScreenState extends State<RaceSessionScreen> {
  bool _showPreview = true;

  RaceSessionController get controller => widget.controller;
  MotionDetectionController get motionController => widget.motionController;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge(<Listenable>[controller, motionController]),
      builder: (context, child) {
        switch (controller.stage) {
          case SessionStage.setup:
            return _buildSetupScaffold(context);
          case SessionStage.lobby:
            return _buildLobbyScaffold(context);
          case SessionStage.monitoring:
            return _buildMonitoringScaffold(context);
          case SessionStage.recording:
            return _buildRecordingScaffold(context);
        }
      },
    );
  }

  Widget _buildSetupScaffold(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Setup Session',
          key: ValueKey<String>('setup_stage_title'),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Network Connection',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  if (controller.errorText != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      controller.errorText!,
                      style: const TextStyle(color: Colors.red),
                    ),
                  ],
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      if (controller.shouldShowPermissionsButton)
                        FilledButton.icon(
                          key: const ValueKey<String>('permissions_button'),
                          onPressed: controller.busy
                              ? null
                              : controller.requestPermissions,
                          icon: const Icon(Icons.security, size: 18),
                          label: const Text('Permissions'),
                        ),
                      FilledButton.icon(
                        key: const ValueKey<String>('host_button'),
                        onPressed: controller.busy
                            ? null
                            : controller.createLobby,
                        icon: const Icon(Icons.cell_wifi, size: 18),
                        label: const Text('Host'),
                      ),
                      FilledButton.icon(
                        key: const ValueKey<String>('join_button'),
                        onPressed: controller.busy
                            ? null
                            : controller.joinLobby,
                        icon: const Icon(Icons.search, size: 18),
                        label: const Text('Join'),
                      ),
                      if (controller.canGoToLobby)
                        FilledButton.icon(
                          key: const ValueKey<String>('next_button'),
                          onPressed: controller.goToLobby,
                          icon: const Icon(Icons.arrow_forward, size: 18),
                          label: const Text('Next'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          if (controller.discoveredEndpoints.isNotEmpty) ...[
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Discovered Devices',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 16,
                      ),
                    ),
                    const SizedBox(height: 8),
                    ...controller.discoveredEndpoints.map((endpoint) {
                      return ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(endpoint.name),
                        subtitle: Text(endpoint.id),
                        trailing: TextButton(
                          onPressed: () => controller.connect(endpoint.id),
                          child: const Text('Connect'),
                        ),
                      );
                    }),
                  ],
                ),
              ),
            ),
          ],
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Connected Devices',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  const SizedBox(height: 8),
                  ...controller.devices.map((device) {
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        device.isLocal ? '${device.name} (Local)' : device.name,
                      ),
                      subtitle: Text(device.id),
                    );
                  }),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLobbyScaffold(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Race Lobby',
          key: ValueKey<String>('lobby_stage_title'),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Device Roles',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  if (controller.isHost) ...[
                    const SizedBox(height: 4),
                    const Text(
                      'Assign roles to connected devices.',
                      style: TextStyle(color: Colors.grey, fontSize: 13),
                    ),
                  ],
                  const SizedBox(height: 8),
                  ...controller.devices.map((device) {
                    return _buildRoleRow(device);
                  }),
                  if (controller.refinementStatusText != null ||
                      controller.recordingStatusText != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      controller.recordingStatusText != null
                          ? 'Recording: ${controller.recordingStatusText}'
                          : 'Refinement: ${controller.refinementStatusText}',
                      key: const ValueKey<String>(
                        'lobby_refinement_status_text',
                      ),
                      style: const TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Session Actions',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        key: const ValueKey<String>('start_monitoring_button'),
                        onPressed: controller.canStartMonitoring
                            ? controller.startMonitoring
                            : null,
                        icon: const Icon(Icons.videocam),
                        label: const Text('Start Monitoring'),
                      ),
                      FilledButton.icon(
                        key: const ValueKey<String>('start_recording_button'),
                        onPressed: controller.canStartRecording
                            ? controller.startRecording
                            : null,
                        icon: const Icon(Icons.fiber_manual_record),
                        label: const Text('Start Recording'),
                      ),
                      if (controller.isHost && controller.timeline.hasStarted)
                        FilledButton.icon(
                          onPressed: controller.resetRun,
                          icon: const Icon(Icons.refresh),
                          label: const Text('Reset Run'),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          _buildTimelineCard(),
        ],
      ),
    );
  }

  Widget _buildMonitoringScaffold(BuildContext context) {
    final latencyMs = controller.monitoringLatencyMs;
    final syncModeLabel = controller.monitoringSyncModeLabel;
    final previewAvailable = !controller.localHighSpeedEnabled;
    final effectiveShowPreview = previewAvailable && _showPreview;
    final latencyLabel = switch (syncModeLabel) {
      'NTP' => latencyMs == null ? '-' : '$latencyMs ms',
      'GPS' => 'GPS',
      _ => '-',
    };
    final clockLockWarningText = controller.clockLockWarningText;
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Monitoring',
          key: ValueKey<String>('monitoring_stage_title'),
        ),
        actions: [
          if (controller.isHost)
            TextButton(
              key: const ValueKey<String>('stop_monitoring_button'),
              onPressed: controller.stopMonitoring,
              child: const Text('Stop'),
            ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Role: ${sessionDeviceRoleLabel(controller.localRole)}',
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    if (!controller.isHost)
                      const Text(
                        'Waiting for host...',
                        style: TextStyle(
                          color: Colors.grey,
                          fontStyle: FontStyle.italic,
                        ),
                      )
                    else
                      FilledButton.icon(
                        onPressed: controller.resetRun,
                        icon: const Icon(Icons.refresh, size: 16),
                        label: const Text('Reset Run'),
                        style: FilledButton.styleFrom(
                          visualDensity: VisualDensity.compact,
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 2),
                Column(
                  key: const ValueKey<String>('monitoring_connection_info'),
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Connection: ${controller.monitoringConnectionTypeLabel}',
                      style: const TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                    Text(
                      'Sync: $syncModeLabel · Latency: $latencyLabel',
                      style: const TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                    if (controller.refinementStatusText != null)
                      Text(
                        'Refinement: ${controller.refinementStatusText}',
                        key: const ValueKey<String>(
                          'monitoring_refinement_status_text',
                        ),
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.grey,
                        ),
                      ),
                  ],
                ),
                if (clockLockWarningText != null) ...[
                  const SizedBox(height: 8),
                  Container(
                    key: const ValueKey<String>('clock_lock_warning_banner'),
                    width: double.infinity,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 8,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.orange.shade50,
                      border: Border.all(color: Colors.orange.shade200),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Padding(
                          padding: EdgeInsets.only(top: 1),
                          child: Icon(
                            Icons.warning_amber_rounded,
                            size: 16,
                            color: Colors.orange,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            clockLockWarningText,
                            style: const TextStyle(
                              color: Colors.black87,
                              fontSize: 12,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Row(
              children: [
                const Text('Preview'),
                const SizedBox(width: 8),
                Switch(
                  key: const ValueKey<String>('monitoring_preview_toggle'),
                  value: effectiveShowPreview,
                  onChanged: previewAvailable
                      ? (value) {
                          setState(() {
                            _showPreview = value;
                          });
                        }
                      : null,
                ),
                if (!previewAvailable) ...[
                  const SizedBox(width: 8),
                  const Text(
                    'Disabled in HS',
                    key: ValueKey<String>('monitoring_preview_disabled_text'),
                    style: TextStyle(color: Colors.grey, fontSize: 12),
                  ),
                ],
              ],
            ),
          ),
          Expanded(
            child: MotionDetectionScreen(
              controller: motionController,
              showPreview: effectiveShowPreview,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRecordingScaffold(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'HS Recording',
          key: ValueKey<String>('recording_stage_title'),
        ),
        actions: [
          if (controller.isHost)
            TextButton(
              key: const ValueKey<String>('stop_recording_button'),
              onPressed: controller.recordingActive
                  ? controller.stopRecording
                  : null,
              child: const Text('Stop'),
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Recording Status',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    controller.recordingStatusText ??
                        (controller.recordingActive
                            ? 'Recording in progress...'
                            : 'Waiting for analysis.'),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Role: ${sessionDeviceRoleLabel(controller.localRole)}',
                    style: const TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          _buildTimelineCard(),
        ],
      ),
    );
  }

  Widget _buildRoleRow(SessionDevice device) {
    final canEdit = controller.isHost && !controller.monitoringActive;
    final allRoles = <SessionDeviceRole>[
      SessionDeviceRole.unassigned,
      SessionDeviceRole.start,
      if (controller.canShowSplitControls) SessionDeviceRole.split,
      SessionDeviceRole.stop,
    ];
    final roleControl = canEdit
        ? PopupMenuButton<SessionDeviceRole>(
            onSelected: (value) => controller.assignRole(device.id, value),
            itemBuilder: (context) => allRoles.map((role) {
              return PopupMenuItem<SessionDeviceRole>(
                value: role,
                child: Text(sessionDeviceRoleLabel(role)),
              );
            }).toList(),
            child: Chip(label: Text(sessionDeviceRoleLabel(device.role))),
          )
        : Text(sessionDeviceRoleLabel(device.role));
    final cameraFacingControl = canEdit
        ? SegmentedButton<SessionCameraFacing>(
            key: ValueKey<String>('camera_facing_toggle_${device.id}'),
            showSelectedIcon: false,
            style: const ButtonStyle(
              visualDensity: VisualDensity.compact,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            segments: <ButtonSegment<SessionCameraFacing>>[
              ButtonSegment<SessionCameraFacing>(
                value: SessionCameraFacing.rear,
                label: Text(
                  'Rear',
                  key: ValueKey<String>('camera_facing_rear_${device.id}'),
                ),
              ),
              ButtonSegment<SessionCameraFacing>(
                value: SessionCameraFacing.front,
                label: Text(
                  'Front',
                  key: ValueKey<String>('camera_facing_front_${device.id}'),
                ),
              ),
            ],
            selected: <SessionCameraFacing>{device.cameraFacing},
            onSelectionChanged: (selection) {
              if (selection.isEmpty) return;
              controller.assignCameraFacing(device.id, selection.first);
            },
          )
        : Text(sessionCameraFacingLabel(device.cameraFacing));
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      title: Text(device.isLocal ? '${device.name} (Local)' : device.name),
      subtitle: Text(device.id),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [cameraFacingControl, const SizedBox(width: 8), roleControl],
      ),
    );
  }

  Widget _buildTimelineCard() {
    final timeline = controller.timeline;
    final refinementImpacts = controller.refinementImpacts;
    final changedRefinementImpacts = refinementImpacts
        .where((impact) => impact.changed)
        .toList();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Race Timeline',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const SizedBox(height: 8),
            if (!timeline.hasStarted)
              const Text(
                'Ready to start.',
                style: TextStyle(color: Colors.grey),
              )
            else ...[
              Text('Started Sensor Nanos: ${timeline.startedSensorNanos}'),
              if (timeline.splitElapsedNanos.isNotEmpty) ...[
                const SizedBox(height: 4),
                ...timeline.splitElapsedNanos.asMap().entries.map((entry) {
                  return Text(
                    'Split ${entry.key + 1}: ${formatDurationNanos(entry.value)}',
                  );
                }),
              ],
              if (timeline.hasStopped) ...[
                const SizedBox(height: 4),
                Text(
                  'Finished: ${formatDurationNanos(timeline.stopElapsedNanos!)}',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ],
            ],
            if (controller.refinementStatusText != null ||
                refinementImpacts.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Divider(height: 1),
              const SizedBox(height: 8),
              const Text(
                'Post-Race Analysis',
                key: ValueKey<String>('post_race_analysis_title'),
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              if (controller.refinementStatusText != null) ...[
                const SizedBox(height: 4),
                Text('Status: ${controller.refinementStatusText}'),
              ],
              if (changedRefinementImpacts.isNotEmpty) ...[
                const SizedBox(height: 6),
                ...changedRefinementImpacts.map((impact) {
                  final liveValue = impact.label == 'Start'
                      ? '${impact.liveSensorNanos}'
                      : formatDurationNanos(impact.liveElapsedNanos);
                  final correctedValue = impact.label == 'Start'
                      ? '${impact.correctedSensorNanos}'
                      : formatDurationNanos(impact.correctedElapsedNanos);
                  return Text(
                    '${impact.label}: live $liveValue -> corrected $correctedValue (${_formatDeltaNanos(impact.deltaElapsedNanos)})',
                    key: ValueKey<String>(
                      'post_race_analysis_row_${impact.label}',
                    ),
                  );
                }),
              ] else ...[
                const SizedBox(height: 6),
                const Text(
                  'No correction deltas recorded yet.',
                  style: TextStyle(color: Colors.grey),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }

  String _formatDeltaNanos(int deltaNanos) {
    final deltaMs = deltaNanos / 1000000.0;
    final sign = deltaMs >= 0 ? '+' : '';
    return 'Δ ${sign}${deltaMs.toStringAsFixed(2)}ms';
  }
}
