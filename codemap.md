## SprintSync App Flow
Complete SprintSync app flow covering initialization, device discovery, motion detection, race timing, and clock synchronization. Key flows include app startup [1a-1e], nearby connections setup [2a-2e], camera motion detection [3a-3e], cross-device trigger coordination [4a-4e], and precision time synchronization [5a-5e].
### 1. App Initialization & Controller Setup
Main app startup and dependency injection for race session and motion detection controllers
### 1a. App Entry Point (`main.dart:9`)
Flutter app initialization and widget tree startup
```text
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SprintSyncApp());
```
### 1b. Dependency Creation (`main.dart:28`)
Instantiate core service dependencies for data, networking, and native sensors
```text
final repository = LocalRepository();
final nearbyBridge = NearbyBridge();
final nativeSensorBridge = NativeSensorBridge();
```
### 1c. Motion Controller Setup (`main.dart:32`)
Create motion detection controller with callback to race session
```text
_motionDetectionController = MotionDetectionController(
  repository: repository,
  nativeSensorBridge: nativeSensorBridge,
  onTrigger: (event) {
    sessionController?.onLocalMotionPulse(event);
  },
);
```
### 1d. Race Session Controller (`main.dart:39`)
Create race session controller with networking and motion dependencies
```text
_raceSessionController = RaceSessionController(
  nearbyBridge: nearbyBridge,
  motionController: _motionDetectionController,
);
```
### 1e. Main Screen Setup (`main.dart:61`)
Set primary screen with both controllers injected
```text
home: RaceSessionScreen(
  controller: _raceSessionController,
  motionController: _motionDetectionController,
),
```
### 2. Session Setup & Device Discovery
Network connection setup and device discovery flow for host/client roles
### 2a. Host Lobby Creation (`race_session_controller.dart:119`)
Start advertising as host for nearby device discovery
```text
await _nearbyBridge.startHosting(
  serviceId: _serviceId,
  endpointName: 'SprintSyncHost',
);
```
### 2b. Client Discovery (`race_session_controller.dart:139`)
Start scanning for available host endpoints
```text
await _nearbyBridge.startDiscovery(
  serviceId: _serviceId,
  endpointName: 'SprintSyncClient',
);
```
### 2c. Connection Request (`race_session_controller.dart:153`)
Client initiates connection to discovered host
```text
await _nearbyBridge.requestConnection(
  endpointId: endpointId,
  endpointName: 'SprintSyncClient',
);
```
### 2d. Native Advertising (`MainActivity.kt:241`)
Android Nearby Connections API starts host advertising
```text
connectionsClient
  .startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
```
### 2e. Native Discovery (`MainActivity.kt:266`)
Android Nearby Connections API starts device discovery
```text
connectionsClient
  .startDiscovery(serviceId, endpointDiscoveryCallback, options)
```
### 3. Motion Detection & Camera Monitoring
Camera-based motion detection pipeline with native sensor integration
### 3a. Start Native Monitoring (`motion_detection_controller.dart:95`)
Initiate camera motion detection through native bridge
```text
await _nativeSensorBridge.startNativeMonitoring(config: _config.toJson());
```
### 3b. Native Method Call (`native_sensor_bridge.dart:26`)
Flutter method channel invokes Android native monitoring
```text
return _methodChannel.invokeMethod<void>('startNativeMonitoring', {
  'config': config,
});
```
### 3c. Trigger Event Processing (`motion_detection_controller.dart:218`)
Parse and ingest motion trigger events from native layer
```text
final trigger = _parseTriggerEvent(event);
if (trigger != null) {
  ingestTrigger(trigger);
}
```
### 3d. Forward to Race Session (`motion_detection_controller.dart:256`)
Forward motion triggers to race session controller for timing
```text
_onTrigger(trigger);
return;
```
### 3e. Camera Preview Display (`motion_detection_screen.dart:217`)
Display live camera preview with motion detection overlay
```text
AndroidView(
  key: const ValueKey<String>('native_preview_view'),
  viewType: NativeSensorBridge.previewViewType,
```
### 4. Race Timing & Trigger Synchronization
Cross-device race timing with clock synchronization and trigger coordination
### 4a. Host Trigger Processing (`race_session_controller.dart:255`)
Host applies motion trigger directly to race timeline
```text
await _applyRoleEvent(
  role: localRole,
  triggerSensorNanos: trigger.triggerSensorNanos,
);
```
### 4b. Client Trigger Forwarding (`race_session_controller.dart:273`)
Client maps sensor time to host time and sends trigger request
```text
await _nearbyBridge.sendBytes(
  endpointId: _connectedEndpointIds.first,
  messageJson: SessionTriggerRequestMessage(
    role: localRole,
    triggerSensorNanos: trigger.triggerSensorNanos,
    mappedHostSensorNanos: mappedHostSensorNanos,
  ).toJsonString(),
);
```
### 4c. Host Trigger Application (`race_session_controller.dart:547`)
Host applies received client trigger to timeline
```text
await _applyRoleEvent(
  role: role,
  triggerSensorNanos: mappedHostSensorNanos,
);
```
### 4d. Timeline State Update (`race_session_controller.dart:308`)
Update race timeline with new timing event
```text
_timeline = _timeline.copyWith(
  startedSensorNanos: triggerSensorNanos,
  splitElapsedNanos: <int>[],
  clearStopElapsedNanos: true,
);
```
### 4e. State Synchronization (`race_session_controller.dart:352`)
Broadcast updated timeline to all connected devices
```text
await _broadcastSnapshot();
```
### 5. Clock Synchronization Protocol
Precision time synchronization between host and client devices
### 5a. Sync Initiation (`race_session_controller.dart:395`)
Client initiates clock sync after connection
```text
unawaited(_requestClockSync());
```
### 5b. Sync Request Burst (`race_session_controller.dart:595`)
Send burst of sync requests for improved accuracy
```text
await _nearbyBridge.sendBytes(
  endpointId: endpointId,
  messageJson: SessionClockSyncRequestMessage(
    clientSendElapsedNanos: uniqueClientSendElapsedNanos,
  ).toJsonString(),
);
```
### 5c. Host Timestamp Capture (`race_session_controller.dart:486`)
Host records precise timestamp of sync request receipt
```text
final hostReceiveElapsedNanos = _nowClockSyncElapsedNanos(
  requireSensorDomainIfMonitoring: true,
);
```
### 5d. Offset Calculation (`race_session_controller.dart:525`)
Client calculates clock offset from round-trip timing
```text
_updateHostClockOffset(
  clientSendElapsedNanos: clockSyncResponse.clientSendElapsedNanos,
  hostReceiveElapsedNanos: clockSyncResponse.hostReceiveElapsedNanos,
  clientReceiveElapsedNanos: clientReceiveElapsedNanos,
);
```
### 5e. Sync State Storage (`race_session_controller.dart:637`)
Store calculated offset and RTT for time mapping
```text
_hostMinusClientElapsedNanos = sampleOffsetNanos;
_hostClockRoundTripNanos = roundTripNanos;
_lastClockSyncElapsedNanos = clientReceiveElapsedNanos;
```