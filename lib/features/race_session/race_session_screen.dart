import 'package:flutter/material.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_controller.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_models.dart';
import 'package:sprint_sync/features/motion_detection/motion_detection_screen.dart';
import 'package:sprint_sync/features/race_session/race_session_controller.dart';
import 'package:sprint_sync/features/race_session/race_session_models.dart';

class RaceSessionScreen extends StatelessWidget {
  const RaceSessionScreen({
    super.key,
    required this.controller,
    required this.motionController,
  });

  final RaceSessionController controller;
  final MotionDetectionController motionController;

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
        }
      },
    );
  }

  Widget _buildSetupScaffold(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Setup Session')),
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
                      if (!controller.permissionsGranted)
                        FilledButton.icon(
                          onPressed: controller.busy
                              ? null
                              : controller.requestPermissions,
                          icon: const Icon(Icons.security, size: 18),
                          label: const Text('Permissions'),
                        ),
                      FilledButton.icon(
                        onPressed: controller.busy
                            ? null
                            : controller.createLobby,
                        icon: const Icon(Icons.cell_wifi, size: 18),
                        label: const Text('Host'),
                      ),
                      FilledButton.icon(
                        onPressed: controller.busy
                            ? null
                            : controller.joinLobby,
                        icon: const Icon(Icons.search, size: 18),
                        label: const Text('Join'),
                      ),
                      if (controller.canGoToLobby)
                        FilledButton.icon(
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
      appBar: AppBar(title: const Text('Race Lobby')),
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
                        onPressed: controller.canStartMonitoring
                            ? controller.startMonitoring
                            : null,
                        icon: const Icon(Icons.videocam),
                        label: const Text('Start Monitoring'),
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Monitoring'),
        actions: [
          if (controller.isHost)
            TextButton(
              onPressed: controller.stopMonitoring,
              child: const Text('Stop'),
            ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
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
          ),
          Expanded(
            child: MotionDetectionScreen(
              controller: motionController,
              showPreview: true,
            ),
          ),
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

    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      title: Text(device.isLocal ? '${device.name} (Local)' : device.name),
      subtitle: Text(device.id),
      trailing: canEdit
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
          : Text(sessionDeviceRoleLabel(device.role)),
    );
  }

  Widget _buildTimelineCard() {
    final timeline = controller.timeline;
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
              Text(
                'Started: ${DateTime.fromMillisecondsSinceEpoch(timeline.startedAtEpochMs!).toLocal().toString().split('.').first}',
              ),
              if (timeline.splitMicros.isNotEmpty) ...[
                const SizedBox(height: 4),
                ...timeline.splitMicros.asMap().entries.map((entry) {
                  return Text(
                    'Split ${entry.key + 1}: ${formatDurationMicros(entry.value)}',
                  );
                }),
              ],
              if (timeline.hasStopped) ...[
                const SizedBox(height: 4),
                Text(
                  'Finished: ${formatDurationMicros(timeline.stopElapsedMicros!)}',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ],
            ],
          ],
        ),
      ),
    );
  }
}
