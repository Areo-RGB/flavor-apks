import net from "node:net";
import { createServer as createHttpServer } from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import cors from "cors";
import express from "express";
import { WebSocketServer } from "ws";

const FRAME_KIND_MESSAGE = 1;
const FRAME_KIND_BINARY = 2;
const MAX_FRAME_BYTES = 1_048_576;

const CLOCK_SYNC_VERSION = 1;
const CLOCK_SYNC_TYPE_REQUEST = 1;
const CLOCK_SYNC_TYPE_RESPONSE = 2;
const CLOCK_SYNC_REQUEST_BYTES = 10;
const CLOCK_SYNC_RESPONSE_BYTES = 26;

const EVENT_LIMIT = 300;
const HISTORY_LIMIT = 1000;

const SESSION_STAGE_SETUP = "SETUP";
const SESSION_STAGE_LOBBY = "LOBBY";
const SESSION_STAGE_MONITORING = "MONITORING";

const ROLE_ORDER = [
  "Unassigned",
  "Start",
  "Split 1",
  "Split 2",
  "Split 3",
  "Split 4",
  "Stop",
];

const SPLIT_ROLE_OPTIONS = ["Split 1", "Split 2", "Split 3", "Split 4"];

const moduleFilePath = fileURLToPath(import.meta.url);
const moduleDirPath = path.dirname(moduleFilePath);
const backendRootPath = path.resolve(moduleDirPath, "..");

const config = {
  tcpHost: process.env.WINDOWS_TCP_HOST ?? "0.0.0.0",
  tcpPort: toPort(process.env.WINDOWS_TCP_PORT, 9000),
  httpHost: process.env.WINDOWS_HTTP_HOST ?? "0.0.0.0",
  httpPort: toPort(process.env.WINDOWS_HTTP_PORT, 8787),
  resultsDir: path.resolve(process.env.WINDOWS_RESULTS_DIR ?? path.join(backendRootPath, "saved-results")),
};

const startedAtMs = Date.now();

const clientsByEndpoint = new Map();
const socketsByEndpoint = new Map();
const latestLapByEndpoint = new Map();

const lapHistory = [];
const recentEvents = [];

const sessionState = {
  stage: SESSION_STAGE_LOBBY,
  monitoringActive: false,
  monitoringStartedAtMs: null,
  monitoringStartedIso: null,
  monitoringElapsedMs: 0,
  runId: null,
  hostStartSensorNanos: null,
  hostStopSensorNanos: null,
  hostSplitMarks: [],
  roleAssignments: {},
  deviceSensitivityAssignments: {},
  deviceCameraFacingAssignments: {},
  deviceDistanceAssignments: {},
  lastSavedResultsFilePath: null,
  lastSavedResultsAtIso: null,
};

const messageStats = {
  totalFrames: 0,
  messageFrames: 0,
  binaryFrames: 0,
  parseErrors: 0,
  knownTypes: {},
};

const clockDomainState = {
  implemented: true,
  source: "windows_monotonic_elapsed",
  samplesResponded: 0,
  ignoredFrames: 0,
  lastEndpointId: null,
  lastRequestAtIso: null,
  lastResponseAtIso: null,
  lastHostReceiveElapsedNanos: null,
  lastHostSendElapsedNanos: null,
};

let nextEventId = 1;
let nextLapId = 1;

let websocketServer = null;

const app = express();
app.use(cors());
app.use(express.json({ limit: "256kb" }));

app.get("/api/health", (_req, res) => {
  res.json({
    ok: true,
    timestampIso: new Date().toISOString(),
    uptimeMs: Date.now() - startedAtMs,
  });
});

app.get("/api/state", (_req, res) => {
  res.json(createSnapshot());
});

app.post("/api/control/reset-laps", (_req, res) => {
  resetRunData();
  pushEvent("info", "Operator reset lap results");
  publishState();
  res.json({ ok: true });
});

app.post("/api/control/start-lobby", (_req, res) => {
  sessionState.stage = SESSION_STAGE_LOBBY;
  sessionState.monitoringActive = false;
  sessionState.monitoringStartedAtMs = null;
  sessionState.monitoringStartedIso = null;
  sessionState.monitoringElapsedMs = 0;
  sessionState.runId = null;
  sessionState.hostStartSensorNanos = null;
  sessionState.hostStopSensorNanos = null;
  sessionState.hostSplitMarks = [];
  pushEvent("info", "Session moved to lobby");
  broadcastProtocolSnapshots();
  broadcastTimelineSnapshot();
  publishState();
  res.json({ ok: true });
});

app.post("/api/control/start-monitoring", (_req, res) => {
  if (sessionState.stage !== SESSION_STAGE_LOBBY) {
    sessionState.stage = SESSION_STAGE_LOBBY;
  }

  const connectedDevices = protocolDevicesWithRoles();
  const hasStartRole = connectedDevices.some((device) => device.roleLabel === "Start");
  const hasStopRole = connectedDevices.some((device) => device.roleLabel === "Stop");
  if (!hasStartRole || !hasStopRole) {
    res.status(409).json({ error: "assign start and stop roles before monitoring" });
    return;
  }

  const startedAtMs = Date.now();
  sessionState.stage = SESSION_STAGE_MONITORING;
  sessionState.monitoringActive = true;
  sessionState.monitoringStartedAtMs = startedAtMs;
  sessionState.monitoringStartedIso = new Date(startedAtMs).toISOString();
  sessionState.monitoringElapsedMs = 0;
  sessionState.runId = `run-${startedAtMs}`;
  sessionState.hostStartSensorNanos = null;
  sessionState.hostStopSensorNanos = null;
  sessionState.hostSplitMarks = [];
  resetRunData();
  pushEvent("info", "Monitoring started", { runId: sessionState.runId });
  broadcastProtocolSnapshots();
  broadcastTimelineSnapshot();
  publishState();
  res.json({ ok: true, runId: sessionState.runId });
});

app.post("/api/control/stop-monitoring", (_req, res) => {
  if (sessionState.monitoringActive && sessionState.monitoringStartedAtMs) {
    sessionState.monitoringElapsedMs = Date.now() - sessionState.monitoringStartedAtMs;
  }
  sessionState.monitoringActive = false;
  sessionState.monitoringStartedAtMs = null;
  sessionState.monitoringStartedIso = null;
  sessionState.stage = SESSION_STAGE_LOBBY;
  pushEvent("info", "Monitoring stopped", { runId: sessionState.runId });
  broadcastProtocolSnapshots();
  publishState();
  res.json({ ok: true });
});

app.post("/api/control/trigger", (req, res) => {
  if (!sessionState.monitoringActive || sessionState.stage !== SESSION_STAGE_MONITORING) {
    res.status(409).json({ error: "monitoring is not active" });
    return;
  }

  const triggerSpec = triggerSpecFromControlPayload(req.body);
  if (!triggerSpec) {
    res.status(400).json({ error: "invalid trigger payload" });
    return;
  }

  const rawTriggerSensorNanos = req.body?.triggerSensorNanos;
  let triggerSensorNanos = Number(rawTriggerSensorNanos);
  if (rawTriggerSensorNanos === null || rawTriggerSensorNanos === undefined || !Number.isFinite(triggerSensorNanos)) {
    triggerSensorNanos = nowHostSensorNanos();
  }
  triggerSensorNanos = Math.trunc(triggerSensorNanos);

  if (!applyTriggerToHostTimeline(triggerSpec, triggerSensorNanos)) {
    res.status(409).json({ error: "trigger rejected by timeline state" });
    return;
  }

  pushEvent("info", `Operator trigger fired: ${triggerLabelForSpec(triggerSpec)}`, {
    triggerType: triggerSpec.triggerType,
    splitIndex: triggerSpec.splitIndex,
    triggerSensorNanos,
  });
  broadcastProtocolTrigger(triggerSpec.triggerType, triggerSensorNanos, triggerSpec.splitIndex);
  broadcastTimelineSnapshot();
  broadcastProtocolSnapshots();
  publishState();
  res.json({
    ok: true,
    triggerType: triggerSpec.triggerType,
    splitIndex: triggerSpec.splitIndex,
    triggerSensorNanos,
  });
});

app.post("/api/control/reset-run", (_req, res) => {
  resetRunData();
  sessionState.monitoringElapsedMs = 0;
  sessionState.runId = sessionState.monitoringActive ? `run-${Date.now()}` : null;
  sessionState.hostStartSensorNanos = null;
  sessionState.hostStopSensorNanos = null;
  sessionState.hostSplitMarks = [];
  pushEvent("info", "Run reset");
  broadcastProtocolSnapshots();
  broadcastTimelineSnapshot();
  publishState();
  res.json({ ok: true });
});

app.post("/api/control/return-setup", (_req, res) => {
  sessionState.stage = SESSION_STAGE_SETUP;
  sessionState.monitoringActive = false;
  sessionState.monitoringStartedAtMs = null;
  sessionState.monitoringStartedIso = null;
  sessionState.monitoringElapsedMs = 0;
  sessionState.runId = null;
  sessionState.hostStartSensorNanos = null;
  sessionState.hostStopSensorNanos = null;
  sessionState.hostSplitMarks = [];
  pushEvent("info", "Session returned to setup");
  broadcastProtocolSnapshots();
  broadcastTimelineSnapshot();
  publishState();
  res.json({ ok: true });
});

app.post("/api/control/assign-role", (req, res) => {
  const targetId = String(req.body?.targetId ?? "").trim();
  const role = String(req.body?.role ?? "").trim();

  if (!targetId) {
    res.status(400).json({ error: "targetId is required" });
    return;
  }

  const availableRoles = computeRoleOptions();
  const currentlyAssigned = sessionState.roleAssignments[targetId] ?? "Unassigned";
  if (!availableRoles.includes(role) && role !== currentlyAssigned) {
    res.status(400).json({ error: "invalid role" });
    return;
  }

  if (role !== "Unassigned") {
    for (const [assignedTargetId, assignedRole] of Object.entries(sessionState.roleAssignments)) {
      if (assignedTargetId !== targetId && assignedRole === role) {
        delete sessionState.roleAssignments[assignedTargetId];
      }
    }
  }

  if (role === "Unassigned") {
    delete sessionState.roleAssignments[targetId];
  } else {
    sessionState.roleAssignments[targetId] = role;
  }

  pushEvent("info", `Role assigned: ${targetId} -> ${role}`);
  broadcastProtocolSnapshots();
  publishState();
  res.json({ ok: true });
});

app.post("/api/control/device-config", (req, res) => {
  const targetIdRaw = String(req.body?.targetId ?? "").trim();
  if (!targetIdRaw) {
    res.status(400).json({ error: "targetId is required" });
    return;
  }

  const targetId = canonicalTargetId(targetIdRaw);
  const hasSensitivity = Object.prototype.hasOwnProperty.call(req.body ?? {}, "sensitivity");
  const hasCameraFacing = Object.prototype.hasOwnProperty.call(req.body ?? {}, "cameraFacing");
  const hasDistanceMeters = Object.prototype.hasOwnProperty.call(req.body ?? {}, "distanceMeters");
  if (!hasSensitivity && !hasCameraFacing && !hasDistanceMeters) {
    res.status(400).json({ error: "at least one of sensitivity, cameraFacing, or distanceMeters is required" });
    return;
  }

  let nextSensitivity = null;
  if (hasSensitivity) {
    const parsedSensitivity = Number(req.body?.sensitivity);
    if (!Number.isInteger(parsedSensitivity) || parsedSensitivity < 1 || parsedSensitivity > 100) {
      res.status(400).json({ error: "sensitivity must be an integer in the range 1..100" });
      return;
    }
    nextSensitivity = parsedSensitivity;
    sessionState.deviceSensitivityAssignments[targetId] = parsedSensitivity;
  }

  let nextCameraFacing = null;
  if (hasCameraFacing) {
    nextCameraFacing = normalizeCameraFacing(req.body?.cameraFacing);
    if (!nextCameraFacing) {
      res.status(400).json({ error: "cameraFacing must be rear or front" });
      return;
    }
    sessionState.deviceCameraFacingAssignments[targetId] = nextCameraFacing;
  }

  let nextDistanceMeters = null;
  let nextDistanceMetersProvided = false;
  if (hasDistanceMeters) {
    const parsedDistanceMeters = normalizeDistanceMeters(req.body?.distanceMeters);
    if (parsedDistanceMeters === null) {
      res.status(400).json({ error: "distanceMeters must be a number in the range 0..100000" });
      return;
    }
    nextDistanceMeters = parsedDistanceMeters;
    nextDistanceMetersProvided = true;
    sessionState.deviceDistanceAssignments[targetId] = parsedDistanceMeters;
  }

  const endpointIds = resolveEndpointIdsForTargetId(targetId);
  for (const endpointId of endpointIds) {
    const client = clientsByEndpoint.get(endpointId);
    if (nextCameraFacing) {
      upsertClient(endpointId, { cameraFacing: nextCameraFacing });
    }
    if (nextSensitivity !== null) {
      const targetStableDeviceId = client?.stableDeviceId || endpointId;
      sendDeviceConfigUpdateToEndpoint(endpointId, targetStableDeviceId, nextSensitivity);
      upsertClient(endpointId, { telemetrySensitivity: nextSensitivity });
    }
    if (nextDistanceMetersProvided) {
      upsertClient(endpointId, { distanceMeters: nextDistanceMeters });
    }
  }

  pushEvent("info", `Device config updated: ${targetId}`, {
    targetId,
    sensitivity: nextSensitivity,
    cameraFacing: nextCameraFacing,
    distanceMeters: nextDistanceMetersProvided ? nextDistanceMeters : null,
    endpointCount: endpointIds.length,
  });
  broadcastProtocolSnapshots();
  publishState();
  res.json({
    ok: true,
    targetId,
    sensitivity: nextSensitivity,
    cameraFacing: nextCameraFacing,
    distanceMeters: nextDistanceMetersProvided ? nextDistanceMeters : null,
    endpointCount: endpointIds.length,
  });
});

app.post("/api/control/save-results", async (req, res) => {
  try {
    const snapshot = createSnapshot();
    const exportTimestampIso = new Date().toISOString();
    const athleteName = normalizeAthleteNameForResult(req.body?.athleteName);
    const notes = String(req.body?.notes ?? "").trim().slice(0, 240);
    const requestedName = String(req.body?.name ?? "").trim();
    const athleteDateName =
      athleteName !== null ? `${athleteName}_${formatDateForResultName(exportTimestampIso)}` : "";
    const runSegment = sanitizeFileNameSegment(
      requestedName || athleteDateName || snapshot.session.runId || `run_${Date.now()}`,
    );
    const timestampSegment = exportTimestampIso.replace(/[:.]/g, "-");
    const fileName = `${runSegment}_${timestampSegment}.json`;
    const filePath = path.join(config.resultsDir, fileName);

    const exportPayload = {
      type: "windows_results_export",
      resultName: runSegment,
      athleteName,
      notes: notes || null,
      namingFormat: "athlete_dd_MM_yyyy",
      exportedAtIso: exportTimestampIso,
      exportedAtMs: Date.now(),
      runId: snapshot.session.runId,
      session: snapshot.session,
      clients: snapshot.clients,
      latestLapResults: snapshot.latestLapResults,
      lapHistory: snapshot.lapHistory,
      recentEvents: snapshot.recentEvents,
    };

    await fs.mkdir(config.resultsDir, { recursive: true });
    await fs.writeFile(filePath, `${JSON.stringify(exportPayload, null, 2)}\n`, "utf8");

    sessionState.lastSavedResultsFilePath = filePath;
    sessionState.lastSavedResultsAtIso = exportTimestampIso;
    pushEvent("info", `Results saved to ${filePath}`);
    publishState();

    res.json({
      ok: true,
      filePath,
      fileName,
      resultName: runSegment,
      athleteName,
      notes: notes || null,
      savedAtIso: exportTimestampIso,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown save error";
    pushEvent("error", `Failed to save results: ${message}`);
    publishState();
    res.status(500).json({ error: message });
  }
});

app.get("/api/results", async (_req, res) => {
  try {
    const items = await listSavedResultItems();
    res.json({ ok: true, items });
  } catch (error) {
    const message = error instanceof Error ? error.message : "failed to list results";
    res.status(500).json({ error: message });
  }
});

app.get("/api/results/:fileName", async (req, res) => {
  const fileName = String(req.params?.fileName ?? "").trim();
  if (!isSafeSavedResultsFileName(fileName)) {
    res.status(400).json({ error: "invalid file name" });
    return;
  }

  try {
    const loaded = await loadSavedResultsFile(fileName);
    if (!loaded) {
      res.status(404).json({ error: "saved result not found" });
      return;
    }
    res.json({ ok: true, ...loaded });
  } catch (error) {
    const message = error instanceof Error ? error.message : "failed to load saved result";
    res.status(500).json({ error: message });
  }
});

app.post("/api/control/clear-events", (_req, res) => {
  recentEvents.length = 0;
  pushEvent("info", "Operator cleared event log");
  publishState();
  res.json({ ok: true });
});

app.use((_req, res) => {
  res.status(404).json({ error: "Not found" });
});

const httpServer = createHttpServer(app);
websocketServer = new WebSocketServer({ server: httpServer, path: "/ws" });

websocketServer.on("connection", (socket) => {
  sendSocketMessage(socket, {
    type: "snapshot",
    payload: createSnapshot(),
  });
});

const monitoringTicker = setInterval(() => {
  if (sessionState.monitoringActive) {
    publishState();
  }
}, 200);

const tcpServer = net.createServer((socket) => {
  const endpointId = `${socket.remoteAddress ?? "unknown"}:${socket.remotePort ?? 0}`;

  socketsByEndpoint.set(endpointId, {
    endpointId,
    socket,
    buffer: Buffer.alloc(0),
  });

  upsertClient(endpointId, {
    endpointId,
    remoteAddress: socket.remoteAddress ?? "unknown",
    remotePort: socket.remotePort ?? 0,
    connectedAtIso: new Date().toISOString(),
    lastSeenAtIso: new Date().toISOString(),
    stableDeviceId: null,
    deviceName: null,
  });

  pushEvent("info", `TCP client connected: ${endpointId}`, { endpointId });
  sendProtocolSnapshotToEndpoint(endpointId);
  publishState();

  socket.on("data", (chunk) => {
    handleSocketData(endpointId, chunk);
  });

  socket.on("error", (error) => {
    pushEvent("warn", `Socket error from ${endpointId}: ${error.message}`, { endpointId });
    publishState();
  });

  socket.on("close", () => {
    const client = clientsByEndpoint.get(endpointId);
    const stableId = client?.stableDeviceId;
    socketsByEndpoint.delete(endpointId);
    clientsByEndpoint.delete(endpointId);
    if (!stableId) {
      delete sessionState.roleAssignments[endpointId];
      delete sessionState.deviceSensitivityAssignments[endpointId];
      delete sessionState.deviceCameraFacingAssignments[endpointId];
      delete sessionState.deviceDistanceAssignments[endpointId];
    }
    pushEvent("info", `TCP client disconnected: ${endpointId}`, { endpointId });
    broadcastProtocolSnapshots();
    publishState();
  });
});

tcpServer.on("error", (error) => {
  pushEvent("error", `TCP server error: ${error.message}`);
  publishState();
});

httpServer.on("error", (error) => {
  pushEvent("error", `HTTP server error: ${error.message}`);
  publishState();
});

tcpServer.listen(config.tcpPort, config.tcpHost, () => {
  pushEvent("info", `TCP server listening on ${config.tcpHost}:${config.tcpPort}`);
  publishState();
});

httpServer.listen(config.httpPort, config.httpHost, () => {
  pushEvent("info", `HTTP server listening on ${config.httpHost}:${config.httpPort}`);
  publishState();
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    shutdown(signal);
  });
}

function toPort(value, fallback) {
  const parsed = Number(value ?? fallback);
  if (!Number.isInteger(parsed) || parsed <= 0 || parsed > 65535) {
    return fallback;
  }
  return parsed;
}

function appendBounded(array, value, maxSize) {
  array.push(value);
  if (array.length > maxSize) {
    array.splice(0, array.length - maxSize);
  }
}

function safeParseJson(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function upsertClient(endpointId, patch) {
  const existing = clientsByEndpoint.get(endpointId) ?? {
    endpointId,
    remoteAddress: "unknown",
    remotePort: 0,
    connectedAtIso: new Date().toISOString(),
    lastSeenAtIso: new Date().toISOString(),
    stableDeviceId: null,
    deviceName: null,
    cameraFacing: "rear",
    distanceMeters: null,
    telemetrySensitivity: 100,
    telemetryLatencyMs: null,
    telemetryClockSynced: false,
    telemetryAnalysisWidth: null,
    telemetryAnalysisHeight: null,
    telemetryTimestampMillis: null,
  };
  clientsByEndpoint.set(endpointId, { ...existing, ...patch });
}

function incrementMessageType(typeName) {
  const current = messageStats.knownTypes[typeName] ?? 0;
  messageStats.knownTypes[typeName] = current + 1;
}

function handleSocketData(endpointId, chunk) {
  const context = socketsByEndpoint.get(endpointId);
  if (!context) {
    return;
  }

  context.buffer = Buffer.concat([context.buffer, chunk]);
  upsertClient(endpointId, { lastSeenAtIso: new Date().toISOString() });

  while (context.buffer.length >= 5) {
    const frameKind = context.buffer.readUInt8(0);
    const frameLength = context.buffer.readInt32BE(1);
    if (frameLength <= 0 || frameLength > MAX_FRAME_BYTES) {
      messageStats.parseErrors += 1;
      pushEvent("warn", `Dropping client ${endpointId}: invalid frame length ${frameLength}`, {
        endpointId,
      });
      context.socket.destroy();
      publishState();
      return;
    }

    const frameTotalSize = 5 + frameLength;
    if (context.buffer.length < frameTotalSize) {
      break;
    }

    const payload = Buffer.from(context.buffer.subarray(5, frameTotalSize));
    context.buffer = Buffer.from(context.buffer.subarray(frameTotalSize));

    messageStats.totalFrames += 1;

    if (frameKind === FRAME_KIND_MESSAGE) {
      messageStats.messageFrames += 1;
      handleMessageFrame(endpointId, payload);
      continue;
    }

    if (frameKind === FRAME_KIND_BINARY) {
      messageStats.binaryFrames += 1;
      handleBinaryFrame(endpointId, payload);
      continue;
    }

    messageStats.parseErrors += 1;
    pushEvent("warn", `Unsupported frame kind ${frameKind} from ${endpointId}`, { endpointId });
  }

  publishState();
}

function handleMessageFrame(endpointId, payload) {
  const rawMessage = payload.toString("utf8");
  const decoded = safeParseJson(rawMessage);
  if (!decoded || typeof decoded !== "object") {
    messageStats.parseErrors += 1;
    pushEvent("warn", `Non-JSON message from ${endpointId}`, {
      endpointId,
      preview: rawMessage.slice(0, 120),
    });
    return;
  }

  const messageType = typeof decoded.type === "string" ? decoded.type : "unknown";
  incrementMessageType(messageType);

  if (messageType === "device_identity") {
    handleDeviceIdentity(endpointId, decoded);
    return;
  }

  if (messageType === "lap_result") {
    handleLapResult(endpointId, decoded);
    return;
  }

  if (messageType === "device_telemetry") {
    handleDeviceTelemetry(endpointId, decoded);
    return;
  }

  if (messageType === "trigger_request") {
    handleTriggerRequest(endpointId, decoded);
    return;
  }

  if (messageType === "session_trigger") {
    handleSessionTrigger(endpointId, decoded);
    return;
  }

  if (messageType === "trigger_refinement") {
    handleTriggerRefinement(endpointId, decoded);
    return;
  }
}

function handleBinaryFrame(endpointId, payload) {
  if (payload.length < 2) {
    clockDomainState.ignoredFrames += 1;
    messageStats.parseErrors += 1;
    return;
  }

  const version = payload.readUInt8(0);
  if (version !== CLOCK_SYNC_VERSION) {
    clockDomainState.ignoredFrames += 1;
    return;
  }

  const payloadType = payload.readUInt8(1);
  if (payloadType === CLOCK_SYNC_TYPE_REQUEST) {
    handleClockSyncRequest(endpointId, payload);
    return;
  }

  // RESPONSE frames are expected from host to client, not the other way around.
  clockDomainState.ignoredFrames += 1;
}

function handleClockSyncRequest(endpointId, payload) {
  if (payload.length !== CLOCK_SYNC_REQUEST_BYTES) {
    clockDomainState.ignoredFrames += 1;
    messageStats.parseErrors += 1;
    return;
  }

  let clientSendElapsedNanos;
  try {
    clientSendElapsedNanos = payload.readBigInt64BE(2);
  } catch {
    clockDomainState.ignoredFrames += 1;
    messageStats.parseErrors += 1;
    return;
  }

  const hostReceiveElapsedNanos = nowHostElapsedNanos();
  const hostSendElapsedNanos = nowHostElapsedNanos();

  const responsePayload = Buffer.alloc(CLOCK_SYNC_RESPONSE_BYTES);
  responsePayload.writeUInt8(CLOCK_SYNC_VERSION, 0);
  responsePayload.writeUInt8(CLOCK_SYNC_TYPE_RESPONSE, 1);
  responsePayload.writeBigInt64BE(clientSendElapsedNanos, 2);
  responsePayload.writeBigInt64BE(hostReceiveElapsedNanos, 10);
  responsePayload.writeBigInt64BE(hostSendElapsedNanos, 18);

  if (!sendTcpFrame(endpointId, FRAME_KIND_BINARY, responsePayload)) {
    clockDomainState.ignoredFrames += 1;
    return;
  }

  const timestampIso = new Date().toISOString();
  clockDomainState.samplesResponded += 1;
  clockDomainState.lastEndpointId = endpointId;
  clockDomainState.lastRequestAtIso = timestampIso;
  clockDomainState.lastResponseAtIso = timestampIso;
  clockDomainState.lastHostReceiveElapsedNanos = hostReceiveElapsedNanos.toString();
  clockDomainState.lastHostSendElapsedNanos = hostSendElapsedNanos.toString();
}

function nowHostElapsedNanos() {
  return process.hrtime.bigint();
}

function handleDeviceIdentity(endpointId, decoded) {
  const stableDeviceId = String(decoded.stableDeviceId ?? "").trim();
  const deviceName = String(decoded.deviceName ?? "").trim();
  if (!stableDeviceId || !deviceName) {
    messageStats.parseErrors += 1;
    pushEvent("warn", `Invalid device_identity payload from ${endpointId}`, { endpointId });
    return;
  }

  const existing = clientsByEndpoint.get(endpointId);
  if (existing?.stableDeviceId && existing.stableDeviceId !== stableDeviceId) {
    const previousRole = sessionState.roleAssignments[existing.stableDeviceId];
    if (previousRole) {
      sessionState.roleAssignments[stableDeviceId] = previousRole;
      delete sessionState.roleAssignments[existing.stableDeviceId];
    }
    migrateAssignmentKey(sessionState.deviceSensitivityAssignments, existing.stableDeviceId, stableDeviceId);
    migrateAssignmentKey(sessionState.deviceCameraFacingAssignments, existing.stableDeviceId, stableDeviceId);
    migrateAssignmentKey(sessionState.deviceDistanceAssignments, existing.stableDeviceId, stableDeviceId);
  }

  const endpointRole = sessionState.roleAssignments[endpointId];
  if (endpointRole && !sessionState.roleAssignments[stableDeviceId]) {
    sessionState.roleAssignments[stableDeviceId] = endpointRole;
    delete sessionState.roleAssignments[endpointId];
  }
  migrateAssignmentKey(sessionState.deviceSensitivityAssignments, endpointId, stableDeviceId);
  migrateAssignmentKey(sessionState.deviceCameraFacingAssignments, endpointId, stableDeviceId);
  migrateAssignmentKey(sessionState.deviceDistanceAssignments, endpointId, stableDeviceId);

  upsertClient(endpointId, {
    stableDeviceId,
    deviceName,
    lastSeenAtIso: new Date().toISOString(),
  });

  const configuredSensitivity = sessionState.deviceSensitivityAssignments[stableDeviceId];
  if (Number.isInteger(configuredSensitivity) && configuredSensitivity >= 1 && configuredSensitivity <= 100) {
    sendDeviceConfigUpdateToEndpoint(endpointId, stableDeviceId, configuredSensitivity);
    upsertClient(endpointId, { telemetrySensitivity: configuredSensitivity });
  }

  pushEvent("info", `Identity update ${deviceName} (${stableDeviceId})`, {
    endpointId,
    stableDeviceId,
    deviceName,
  });
  broadcastProtocolSnapshots();
}

function handleDeviceTelemetry(endpointId, decoded) {
  const stableDeviceId = String(decoded.stableDeviceId ?? "").trim();
  const sensitivity = Number(decoded.sensitivity);
  const timestampMillis = Number(decoded.timestampMillis);
  if (!stableDeviceId || !Number.isInteger(sensitivity) || sensitivity < 1 || sensitivity > 100) {
    messageStats.parseErrors += 1;
    return;
  }
  if (!Number.isFinite(timestampMillis) || timestampMillis <= 0) {
    messageStats.parseErrors += 1;
    return;
  }

  let latencyMs = null;
  if (decoded.latencyMs !== null && decoded.latencyMs !== undefined) {
    const parsedLatency = Number(decoded.latencyMs);
    if (!Number.isInteger(parsedLatency) || parsedLatency < 0) {
      messageStats.parseErrors += 1;
      return;
    }
    latencyMs = parsedLatency;
  }

  let analysisWidth = null;
  let analysisHeight = null;
  const hasAnalysisWidth = decoded.analysisWidth !== null && decoded.analysisWidth !== undefined;
  const hasAnalysisHeight = decoded.analysisHeight !== null && decoded.analysisHeight !== undefined;
  if (hasAnalysisWidth || hasAnalysisHeight) {
    const parsedWidth = Number(decoded.analysisWidth);
    const parsedHeight = Number(decoded.analysisHeight);
    if (
      !Number.isInteger(parsedWidth) ||
      !Number.isInteger(parsedHeight) ||
      parsedWidth <= 0 ||
      parsedHeight <= 0
    ) {
      messageStats.parseErrors += 1;
      return;
    }
    analysisWidth = parsedWidth;
    analysisHeight = parsedHeight;
  }

  const existing = clientsByEndpoint.get(endpointId);
  const roleTarget = existing?.stableDeviceId || stableDeviceId;
  const configuredSensitivity =
    sessionState.deviceSensitivityAssignments[roleTarget] ??
    sessionState.deviceSensitivityAssignments[endpointId] ??
    sensitivity;

  upsertClient(endpointId, {
    stableDeviceId,
    telemetrySensitivity: Number.isInteger(configuredSensitivity) ? configuredSensitivity : sensitivity,
    telemetryLatencyMs: latencyMs,
    telemetryClockSynced: Boolean(decoded.clockSynced),
    telemetryAnalysisWidth: analysisWidth,
    telemetryAnalysisHeight: analysisHeight,
    telemetryTimestampMillis: Math.trunc(timestampMillis),
  });
}

function handleLapResult(endpointId, decoded) {
  const senderDeviceName = String(decoded.senderDeviceName ?? "").trim();
  const startedSensorNanos = Number(decoded.startedSensorNanos);
  const stoppedSensorNanos = Number(decoded.stoppedSensorNanos);

  if (
    !senderDeviceName ||
    !Number.isFinite(startedSensorNanos) ||
    !Number.isFinite(stoppedSensorNanos) ||
    stoppedSensorNanos <= startedSensorNanos
  ) {
    messageStats.parseErrors += 1;
    pushEvent("warn", `Invalid lap_result payload from ${endpointId}`, { endpointId });
    return;
  }

  const elapsedNanos = Math.trunc(stoppedSensorNanos - startedSensorNanos);
  const lapResult = {
    id: `lap-${nextLapId}`,
    endpointId,
    senderDeviceName,
    startedSensorNanos: Math.trunc(startedSensorNanos),
    stoppedSensorNanos: Math.trunc(stoppedSensorNanos),
    elapsedNanos,
    elapsedMillis: Math.round(elapsedNanos / 1_000_000),
    receivedAtIso: new Date().toISOString(),
  };
  nextLapId += 1;

  latestLapByEndpoint.set(endpointId, lapResult);
  appendBounded(lapHistory, lapResult, HISTORY_LIMIT);

  const existing = clientsByEndpoint.get(endpointId);
  if (!existing?.deviceName) {
    upsertClient(endpointId, { deviceName: senderDeviceName });
  }

  pushEvent("info", `Lap result from ${senderDeviceName}: ${lapResult.elapsedMillis} ms`, {
    endpointId,
    senderDeviceName,
    elapsedMillis: lapResult.elapsedMillis,
  });
}

function assignedRoleForEndpoint(endpointId) {
  const client = clientsByEndpoint.get(endpointId);
  const roleTarget = client?.stableDeviceId || endpointId;
  return (
    sessionState.roleAssignments[roleTarget] ??
    sessionState.roleAssignments[endpointId] ??
    "Unassigned"
  );
}

function rejectTriggerRequest(endpointId, reason, details = {}) {
  pushEvent("warn", `Trigger request rejected from ${endpointId}: ${reason}`, {
    endpointId,
    ...details,
  });
}

function triggerSpecMatchesRole(roleLabel, triggerSpec) {
  const expected = triggerSpecForRole(roleLabel);
  if (!expected) {
    return false;
  }
  return (
    expected.triggerType === triggerSpec.triggerType &&
    Number(expected.splitIndex ?? 0) === Number(triggerSpec.splitIndex ?? 0)
  );
}

function handleSessionTrigger(endpointId, decoded) {
  if (!sessionState.monitoringActive || sessionState.stage !== SESSION_STAGE_MONITORING) {
    return;
  }

  const assignedRole = assignedRoleForEndpoint(endpointId);
  if (assignedRole === "Unassigned") {
    rejectTriggerRequest(endpointId, "unassigned role", { sourceType: "session_trigger" });
    return;
  }

  const triggerSpec = triggerSpecForType(decoded.triggerType, decoded.splitIndex);
  if (!triggerSpec) {
    rejectTriggerRequest(endpointId, "invalid trigger payload", { sourceType: "session_trigger" });
    return;
  }
  if (!triggerSpecMatchesRole(assignedRole, triggerSpec)) {
    rejectTriggerRequest(endpointId, "role mismatch", {
      sourceType: "session_trigger",
      assignedRole,
      triggerType: triggerSpec.triggerType,
      splitIndex: triggerSpec.splitIndex,
    });
    return;
  }

  // Compatibility path for older clients: use host receive time as canonical trigger timestamp.
  const triggerSensorNanos = nowHostSensorNanos();
  if (!applyTriggerToHostTimeline(triggerSpec, triggerSensorNanos)) {
    rejectTriggerRequest(endpointId, "timeline state rejected", {
      sourceType: "session_trigger",
      triggerType: triggerSpec.triggerType,
      splitIndex: triggerSpec.splitIndex,
    });
    return;
  }

  pushEvent("info", `Trigger accepted from ${endpointId}: ${triggerSpec.triggerType}`, {
    endpointId,
    sourceType: "session_trigger",
    triggerType: triggerSpec.triggerType,
    splitIndex: triggerSpec.splitIndex,
    triggerSensorNanos,
  });
  broadcastProtocolTrigger(triggerSpec.triggerType, triggerSensorNanos, triggerSpec.splitIndex);
  broadcastTimelineSnapshot();
  broadcastProtocolSnapshots();
  publishState();
}

function handleTriggerRequest(endpointId, decoded) {
  if (!sessionState.monitoringActive || sessionState.stage !== SESSION_STAGE_MONITORING) {
    rejectTriggerRequest(endpointId, "monitoring inactive", { sourceType: "trigger_request" });
    return;
  }

  const requestedRoleLabel = wireRoleToRoleLabel(decoded.role);
  if (!requestedRoleLabel) {
    rejectTriggerRequest(endpointId, "invalid role", {
      sourceType: "trigger_request",
      role: decoded.role,
    });
    return;
  }

  const assignedRole = assignedRoleForEndpoint(endpointId);
  if (assignedRole !== requestedRoleLabel) {
    rejectTriggerRequest(endpointId, "role mismatch", {
      sourceType: "trigger_request",
      role: decoded.role,
      assignedRole,
    });
    return;
  }

  const triggerSpec = triggerSpecForRole(assignedRole);
  if (!triggerSpec) {
    rejectTriggerRequest(endpointId, "role has no trigger mapping", {
      sourceType: "trigger_request",
      assignedRole,
    });
    return;
  }

  const rawMappedHostSensorNanos = decoded.mappedHostSensorNanos;
  if (rawMappedHostSensorNanos === null || rawMappedHostSensorNanos === undefined) {
    rejectTriggerRequest(endpointId, "missing host mapping", {
      sourceType: "trigger_request",
      assignedRole,
    });
    return;
  }

  const mappedHostSensorNanos = Number(rawMappedHostSensorNanos);
  if (!Number.isFinite(mappedHostSensorNanos)) {
    rejectTriggerRequest(endpointId, "missing host mapping", {
      sourceType: "trigger_request",
      assignedRole,
    });
    return;
  }

  const triggerSensorNanos = Math.trunc(mappedHostSensorNanos);
  if (!applyTriggerToHostTimeline(triggerSpec, triggerSensorNanos)) {
    rejectTriggerRequest(endpointId, "timeline state rejected", {
      sourceType: "trigger_request",
      triggerType: triggerSpec.triggerType,
      splitIndex: triggerSpec.splitIndex,
    });
    return;
  }

  pushEvent("info", `Trigger accepted from ${endpointId}: ${triggerSpec.triggerType}`, {
    endpointId,
    triggerType: triggerSpec.triggerType,
    splitIndex: triggerSpec.splitIndex,
  });
  broadcastProtocolTrigger(triggerSpec.triggerType, triggerSensorNanos, triggerSpec.splitIndex);
  broadcastTimelineSnapshot();
  broadcastProtocolSnapshots();
  publishState();
}

function handleTriggerRefinement(endpointId, decoded) {
  const runId = String(decoded.runId ?? "").trim();
  if (!runId || runId !== sessionState.runId) {
    return;
  }

  const requestedRoleLabel = wireRoleToRoleLabel(decoded.role);
  if (!requestedRoleLabel || requestedRoleLabel === "Unassigned") {
    return;
  }

  const client = clientsByEndpoint.get(endpointId);
  const roleTarget = client?.stableDeviceId || endpointId;
  const assignedRole =
    sessionState.roleAssignments[roleTarget] ??
    sessionState.roleAssignments[endpointId] ??
    "Unassigned";
  if (assignedRole !== requestedRoleLabel) {
    return;
  }

  const rawProvisionalHostSensorNanos = decoded.provisionalHostSensorNanos;
  const rawRefinedHostSensorNanos = decoded.refinedHostSensorNanos;
  if (
    rawProvisionalHostSensorNanos === null ||
    rawProvisionalHostSensorNanos === undefined ||
    rawRefinedHostSensorNanos === null ||
    rawRefinedHostSensorNanos === undefined
  ) {
    return;
  }

  const provisionalHostSensorNanos = Number(rawProvisionalHostSensorNanos);
  const refinedHostSensorNanos = Number(rawRefinedHostSensorNanos);
  if (!Number.isFinite(provisionalHostSensorNanos) || !Number.isFinite(refinedHostSensorNanos)) {
    return;
  }

  const provisional = Math.trunc(provisionalHostSensorNanos);
  const refined = Math.trunc(refinedHostSensorNanos);
  if (!applyTriggerRefinementToHostTimeline(requestedRoleLabel, provisional, refined)) {
    return;
  }

  pushEvent("info", `Trigger refinement accepted from ${endpointId}: ${requestedRoleLabel}`, {
    endpointId,
    roleLabel: requestedRoleLabel,
    provisionalHostSensorNanos: provisional,
    refinedHostSensorNanos: refined,
  });
  broadcastProtocolTriggerRefinement(requestedRoleLabel, provisional, refined);
  broadcastTimelineSnapshot();
  broadcastProtocolSnapshots();
  publishState();
}

function pushEvent(level, message, details = {}) {
  const event = {
    id: `event-${nextEventId}`,
    timestampIso: new Date().toISOString(),
    level,
    message,
    ...details,
  };
  nextEventId += 1;
  appendBounded(recentEvents, event, EVENT_LIMIT);
  broadcast({ type: "server:event", payload: event });
}

function resetRunData() {
  latestLapByEndpoint.clear();
  lapHistory.length = 0;
}

function normalizeCameraFacing(rawCameraFacing) {
  const normalized = String(rawCameraFacing ?? "").trim().toLowerCase();
  if (normalized === "rear") {
    return "rear";
  }
  if (normalized === "front") {
    return "front";
  }
  return null;
}

function normalizeDistanceMeters(rawDistanceMeters) {
  const parsed = Number(rawDistanceMeters);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100000) {
    return null;
  }
  return Math.round(parsed * 1000) / 1000;
}

function canonicalTargetId(targetId) {
  const directClient = clientsByEndpoint.get(targetId);
  if (directClient?.stableDeviceId) {
    return directClient.stableDeviceId;
  }
  for (const client of clientsByEndpoint.values()) {
    if (client.stableDeviceId === targetId) {
      return targetId;
    }
  }
  return targetId;
}

function resolveEndpointIdsForTargetId(targetId) {
  const resolved = [];
  for (const client of clientsByEndpoint.values()) {
    if (client.endpointId === targetId || client.stableDeviceId === targetId) {
      resolved.push(client.endpointId);
    }
  }
  return resolved;
}

function resolveCameraFacingForRoleTarget(roleTarget, fallbackFacing = "rear") {
  const configured = sessionState.deviceCameraFacingAssignments[roleTarget];
  return normalizeCameraFacing(configured) ?? normalizeCameraFacing(fallbackFacing) ?? "rear";
}

function resolveSensitivityForRoleTarget(roleTarget, fallbackSensitivity = 100) {
  const configured = Number(sessionState.deviceSensitivityAssignments[roleTarget]);
  if (Number.isInteger(configured) && configured >= 1 && configured <= 100) {
    return configured;
  }
  const fallback = Number(fallbackSensitivity);
  if (Number.isInteger(fallback) && fallback >= 1 && fallback <= 100) {
    return fallback;
  }
  return 100;
}

function resolveDistanceForRoleTarget(roleTarget, fallbackDistanceMeters = null) {
  const configured = normalizeDistanceMeters(sessionState.deviceDistanceAssignments[roleTarget]);
  if (configured !== null) {
    return configured;
  }
  return normalizeDistanceMeters(fallbackDistanceMeters);
}

function migrateAssignmentKey(targetMap, oldKey, newKey) {
  if (!oldKey || !newKey || oldKey === newKey) {
    return;
  }
  if (!(oldKey in targetMap)) {
    return;
  }
  if (!(newKey in targetMap)) {
    targetMap[newKey] = targetMap[oldKey];
  }
  delete targetMap[oldKey];
}

function sendDeviceConfigUpdateToEndpoint(endpointId, targetStableDeviceId, sensitivity) {
  const normalizedSensitivity = Number(sensitivity);
  if (!Number.isInteger(normalizedSensitivity) || normalizedSensitivity < 1 || normalizedSensitivity > 100) {
    return false;
  }
  return sendTcpJsonMessage(endpointId, {
    type: "device_config_update",
    targetStableDeviceId,
    sensitivity: normalizedSensitivity,
  });
}

function sanitizeFileNameSegment(rawName) {
  const normalized = String(rawName ?? "").trim();
  const stripped = normalized.replace(/[^a-zA-Z0-9._-]+/g, "_").replace(/^_+|_+$/g, "");
  if (!stripped) {
    return "results";
  }
  return stripped.slice(0, 80);
}

function normalizeAthleteNameForResult(rawAthleteName) {
  const normalized = String(rawAthleteName ?? "").trim().toLowerCase();
  if (!normalized) {
    return null;
  }
  const compacted = normalized.replace(/\s+/g, "_").replace(/[^a-z0-9_-]+/g, "").replace(/^_+|_+$/g, "");
  if (!compacted) {
    return null;
  }
  return compacted.slice(0, 40);
}

function formatDateForResultName(rawDate) {
  const date = new Date(rawDate);
  const day = String(date.getDate()).padStart(2, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const year = String(date.getFullYear());
  return `${day}_${month}_${year}`;
}

function isSafeSavedResultsFileName(fileName) {
  return /^[a-zA-Z0-9._-]+\.json$/u.test(fileName);
}

async function loadSavedResultsFile(fileName) {
  if (!isSafeSavedResultsFileName(fileName)) {
    return null;
  }
  const filePath = path.join(config.resultsDir, fileName);
  try {
    const rawContent = await fs.readFile(filePath, "utf8");
    const payload = JSON.parse(rawContent);
    return {
      fileName,
      filePath,
      payload,
    };
  } catch (error) {
    if (error && typeof error === "object" && "code" in error && error.code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

function summarizeSavedResults(fileName, filePath, payload, stat) {
  const latestLapResults = Array.isArray(payload?.latestLapResults) ? payload.latestLapResults : [];
  const bestLap = latestLapResults.find((lap) => Number.isFinite(Number(lap?.elapsedNanos)));

  return {
    fileName,
    filePath,
    resultName: String(payload?.resultName ?? fileName.replace(/\.json$/iu, "")),
    athleteName: typeof payload?.athleteName === "string" ? payload.athleteName : null,
    notes: typeof payload?.notes === "string" ? payload.notes : null,
    runId: typeof payload?.runId === "string" ? payload.runId : null,
    savedAtIso:
      typeof payload?.exportedAtIso === "string" && payload.exportedAtIso.length > 0
        ? payload.exportedAtIso
        : new Date(stat.mtimeMs).toISOString(),
    resultCount: latestLapResults.length,
    bestElapsedNanos: bestLap ? Number(bestLap.elapsedNanos) : null,
  };
}

async function listSavedResultItems() {
  await fs.mkdir(config.resultsDir, { recursive: true });
  const dirEntries = await fs.readdir(config.resultsDir, { withFileTypes: true });
  const savedFiles = dirEntries.filter((entry) => entry.isFile() && isSafeSavedResultsFileName(entry.name));

  const items = [];
  for (const entry of savedFiles) {
    const filePath = path.join(config.resultsDir, entry.name);
    try {
      const [rawContent, stat] = await Promise.all([fs.readFile(filePath, "utf8"), fs.stat(filePath)]);
      const payload = JSON.parse(rawContent);
      items.push(summarizeSavedResults(entry.name, filePath, payload, stat));
    } catch {
      // Ignore unreadable or malformed files to keep listing resilient.
    }
  }

  return items.sort((left, right) => String(right.savedAtIso).localeCompare(String(left.savedAtIso)));
}

function roleOrderIndex(role) {
  const index = ROLE_ORDER.indexOf(role);
  return index === -1 ? ROLE_ORDER.length : index;
}

function computeRoleOptions() {
  const assignedRoles = new Set(Object.values(sessionState.roleAssignments));

  let unlockedSplitCount = 1;
  while (
    unlockedSplitCount < SPLIT_ROLE_OPTIONS.length &&
    assignedRoles.has(SPLIT_ROLE_OPTIONS[unlockedSplitCount - 1])
  ) {
    unlockedSplitCount += 1;
  }

  const options = [
    "Unassigned",
    "Start",
    ...SPLIT_ROLE_OPTIONS.slice(0, unlockedSplitCount),
    "Stop",
  ];

  for (const assignedRole of assignedRoles) {
    if (SPLIT_ROLE_OPTIONS.includes(assignedRole) && !options.includes(assignedRole)) {
      options.push(assignedRole);
    }
  }

  return options.sort((left, right) => roleOrderIndex(left) - roleOrderIndex(right));
}

function roleLabelToWireRole(roleLabel) {
  const normalized = String(roleLabel ?? "").trim().toLowerCase().replace(/\s+/g, "");
  if (normalized === "start") {
    return "start";
  }
  if (normalized === "split1" || normalized === "split") {
    return "split1";
  }
  if (normalized === "split2") {
    return "split2";
  }
  if (normalized === "split3") {
    return "split3";
  }
  if (normalized === "split4") {
    return "split4";
  }
  if (normalized === "stop") {
    return "stop";
  }
  return "unassigned";
}

function wireRoleToRoleLabel(rawRole) {
  const normalized = String(rawRole ?? "").trim().toLowerCase();
  if (normalized === "start") {
    return "Start";
  }
  if (normalized === "split" || normalized === "split1") {
    return "Split 1";
  }
  if (normalized === "split2") {
    return "Split 2";
  }
  if (normalized === "split3") {
    return "Split 3";
  }
  if (normalized === "split4") {
    return "Split 4";
  }
  if (normalized === "stop") {
    return "Stop";
  }
  if (normalized === "unassigned") {
    return "Unassigned";
  }
  return null;
}

function triggerSpecForRole(roleLabel) {
  if (roleLabel === "Start") {
    return { triggerType: "start", splitIndex: 0 };
  }
  if (roleLabel === "Split 1") {
    return { triggerType: "split", splitIndex: 1 };
  }
  if (roleLabel === "Split 2") {
    return { triggerType: "split", splitIndex: 2 };
  }
  if (roleLabel === "Split 3") {
    return { triggerType: "split", splitIndex: 3 };
  }
  if (roleLabel === "Split 4") {
    return { triggerType: "split", splitIndex: 4 };
  }
  if (roleLabel === "Stop") {
    return { triggerType: "stop", splitIndex: 0 };
  }
  return null;
}

function triggerSpecForType(triggerType, splitIndex = 0) {
  const normalizedType = String(triggerType ?? "").trim().toLowerCase();
  if (normalizedType === "start") {
    return { triggerType: "start", splitIndex: 0 };
  }
  if (normalizedType === "stop") {
    return { triggerType: "stop", splitIndex: 0 };
  }
  if (normalizedType === "split") {
    const numericSplitIndex = Number(splitIndex);
    if (!Number.isInteger(numericSplitIndex) || numericSplitIndex < 1 || numericSplitIndex > 4) {
      return null;
    }
    return { triggerType: "split", splitIndex: numericSplitIndex };
  }
  return null;
}

function triggerSpecFromControlPayload(payload) {
  const explicitRole = String(payload?.role ?? "").trim();
  if (explicitRole.length > 0) {
    const directRole = triggerSpecForRole(explicitRole);
    if (directRole) {
      return directRole;
    }
    const parsedWireRole = wireRoleToRoleLabel(explicitRole);
    if (parsedWireRole) {
      return triggerSpecForRole(parsedWireRole);
    }
  }

  return triggerSpecForType(payload?.triggerType, payload?.splitIndex);
}

function triggerLabelForSpec(triggerSpec) {
  if (triggerSpec.triggerType === "start") {
    return "Start";
  }
  if (triggerSpec.triggerType === "stop") {
    return "Stop";
  }
  if (triggerSpec.triggerType === "split") {
    return `Split ${triggerSpec.splitIndex}`;
  }
  return triggerSpec.triggerType;
}

function applyTriggerToHostTimeline(triggerSpec, triggerSensorNanos) {
  if (!Number.isFinite(triggerSensorNanos)) {
    return false;
  }
  const normalizedSensorNanos = Math.trunc(triggerSensorNanos);

  if (triggerSpec.triggerType === "start") {
    if (Number.isFinite(sessionState.hostStartSensorNanos)) {
      return false;
    }
    sessionState.hostStartSensorNanos = normalizedSensorNanos;
    return true;
  }

  if (triggerSpec.triggerType === "stop") {
    if (!Number.isFinite(sessionState.hostStartSensorNanos) || Number.isFinite(sessionState.hostStopSensorNanos)) {
      return false;
    }
    sessionState.hostStopSensorNanos = normalizedSensorNanos;
    return true;
  }

  if (triggerSpec.triggerType === "split") {
    if (!Number.isFinite(sessionState.hostStartSensorNanos) || Number.isFinite(sessionState.hostStopSensorNanos)) {
      return false;
    }

    const splitIndex = Number(triggerSpec.splitIndex);
    if (!Number.isInteger(splitIndex) || splitIndex < 1 || splitIndex > 4) {
      return false;
    }

    const roleLabel = `Split ${splitIndex}`;
    if (sessionState.hostSplitMarks.some((splitMark) => splitMark.roleLabel === roleLabel)) {
      return false;
    }

    const lastMarker =
      sessionState.hostSplitMarks.length > 0
        ? sessionState.hostSplitMarks[sessionState.hostSplitMarks.length - 1].hostSensorNanos
        : sessionState.hostStartSensorNanos;
    if (!Number.isFinite(lastMarker) || normalizedSensorNanos <= lastMarker) {
      return false;
    }

    sessionState.hostSplitMarks = [
      ...sessionState.hostSplitMarks,
      { roleLabel, hostSensorNanos: normalizedSensorNanos },
    ];
    return true;
  }

  return false;
}

function applyTriggerRefinementToHostTimeline(roleLabel, provisionalHostSensorNanos, refinedHostSensorNanos) {
  if (
    !Number.isFinite(provisionalHostSensorNanos) ||
    !Number.isFinite(refinedHostSensorNanos)
  ) {
    return false;
  }

  if (roleLabel === "Start") {
    if (!Number.isFinite(sessionState.hostStartSensorNanos)) {
      return false;
    }
    if (sessionState.hostStartSensorNanos !== provisionalHostSensorNanos) {
      return false;
    }

    const earliestFutureMarker = sessionState.hostSplitMarks.length
      ? sessionState.hostSplitMarks[0].hostSensorNanos
      : sessionState.hostStopSensorNanos;
    if (Number.isFinite(earliestFutureMarker) && refinedHostSensorNanos >= earliestFutureMarker) {
      return false;
    }

    sessionState.hostStartSensorNanos = refinedHostSensorNanos;
    return true;
  }

  if (roleLabel === "Stop") {
    if (!Number.isFinite(sessionState.hostStopSensorNanos)) {
      return false;
    }
    if (sessionState.hostStopSensorNanos !== provisionalHostSensorNanos) {
      return false;
    }

    const previousMarker =
      sessionState.hostSplitMarks.length > 0
        ? sessionState.hostSplitMarks[sessionState.hostSplitMarks.length - 1].hostSensorNanos
        : sessionState.hostStartSensorNanos;
    if (!Number.isFinite(previousMarker) || refinedHostSensorNanos <= previousMarker) {
      return false;
    }

    sessionState.hostStopSensorNanos = refinedHostSensorNanos;
    return true;
  }

  if (roleLabel.startsWith("Split ")) {
    const splitMarkIndex = sessionState.hostSplitMarks.findIndex((mark) => mark.roleLabel === roleLabel);
    if (splitMarkIndex === -1) {
      return false;
    }

    const currentMark = sessionState.hostSplitMarks[splitMarkIndex];
    if (currentMark.hostSensorNanos !== provisionalHostSensorNanos) {
      return false;
    }

    const previousMarker =
      splitMarkIndex > 0
        ? sessionState.hostSplitMarks[splitMarkIndex - 1].hostSensorNanos
        : sessionState.hostStartSensorNanos;
    const nextMarker =
      splitMarkIndex < sessionState.hostSplitMarks.length - 1
        ? sessionState.hostSplitMarks[splitMarkIndex + 1].hostSensorNanos
        : sessionState.hostStopSensorNanos;

    if (!Number.isFinite(previousMarker) || refinedHostSensorNanos <= previousMarker) {
      return false;
    }
    if (Number.isFinite(nextMarker) && refinedHostSensorNanos >= nextMarker) {
      return false;
    }

    sessionState.hostSplitMarks = sessionState.hostSplitMarks.map((mark, index) =>
      index === splitMarkIndex
        ? { ...mark, hostSensorNanos: refinedHostSensorNanos }
        : mark,
    );
    return true;
  }

  return false;
}

function roleTargetForRole(roleLabel) {
  for (const [targetId, assignedRole] of Object.entries(sessionState.roleAssignments)) {
    if (assignedRole === roleLabel) {
      return targetId;
    }
  }
  return null;
}

function clientForRoleTarget(roleTarget) {
  if (!roleTarget) {
    return null;
  }
  for (const client of clientsByEndpoint.values()) {
    if (client.stableDeviceId === roleTarget || client.endpointId === roleTarget) {
      return client;
    }
  }
  return null;
}

function computeSpeedMps(distanceMeters, elapsedNanos) {
  const normalizedDistance = normalizeDistanceMeters(distanceMeters);
  const elapsed = Number(elapsedNanos);
  if (normalizedDistance === null || !Number.isFinite(elapsed) || elapsed <= 0) {
    return null;
  }
  const metersPerSecond = normalizedDistance / (elapsed / 1_000_000_000);
  if (!Number.isFinite(metersPerSecond) || metersPerSecond < 0) {
    return null;
  }
  return Math.round(metersPerSecond * 1000) / 1000;
}

function createTimelineLapResults() {
  const rawStartSensorNanos = Number(sessionState.hostStartSensorNanos);
  if (!Number.isFinite(rawStartSensorNanos)) {
    return [];
  }
  const startSensorNanos = Math.trunc(rawStartSensorNanos);

  const timelineMarkers = [];
  for (const splitMark of sessionState.hostSplitMarks) {
    const roleLabel = String(splitMark?.roleLabel ?? "").trim();
    const markerSensorNanos = Number(splitMark?.hostSensorNanos);
    if (!roleLabel || !Number.isFinite(markerSensorNanos)) {
      continue;
    }
    timelineMarkers.push({ roleLabel, hostSensorNanos: Math.trunc(markerSensorNanos) });
  }

  const rawStopSensorNanos = Number(sessionState.hostStopSensorNanos);
  if (Number.isFinite(rawStopSensorNanos)) {
    timelineMarkers.push({ roleLabel: "Stop", hostSensorNanos: Math.trunc(rawStopSensorNanos) });
  }

  if (timelineMarkers.length === 0) {
    return [];
  }

  timelineMarkers.sort((left, right) => left.hostSensorNanos - right.hostSensorNanos);

  const startRoleTarget = roleTargetForRole("Start");
  let previousHostSensorNanos = startSensorNanos;
  let previousDistanceMeters = resolveDistanceForRoleTarget(startRoleTarget, 0) ?? 0;

  const results = [];
  for (const marker of timelineMarkers) {
    const elapsedNanos = marker.hostSensorNanos - startSensorNanos;
    const lapElapsedNanos = marker.hostSensorNanos - previousHostSensorNanos;
    if (elapsedNanos <= 0 || lapElapsedNanos <= 0) {
      continue;
    }

    const roleTarget = roleTargetForRole(marker.roleLabel);
    const client = clientForRoleTarget(roleTarget);
    const distanceMeters = resolveDistanceForRoleTarget(roleTarget, client?.distanceMeters ?? null);

    let lapDistanceMeters = null;
    if (distanceMeters !== null && Number.isFinite(previousDistanceMeters)) {
      const segmentDistance = normalizeDistanceMeters(distanceMeters - previousDistanceMeters);
      if (segmentDistance !== null) {
        lapDistanceMeters = segmentDistance;
      }
    }

    const averageSpeedMps = computeSpeedMps(distanceMeters, elapsedNanos);
    const lapSpeedMps = computeSpeedMps(lapDistanceMeters, lapElapsedNanos);

    const roleSegment = marker.roleLabel.toLowerCase().replace(/\s+/g, "-");
    results.push({
      id: `timeline-${sessionState.runId ?? "run"}-${roleSegment}`,
      source: "timeline",
      endpointId: client?.endpointId ?? roleTarget ?? `role:${roleSegment}`,
      senderDeviceName: client?.deviceName ?? roleTarget ?? marker.roleLabel,
      roleLabel: marker.roleLabel,
      startedSensorNanos: startSensorNanos,
      stoppedSensorNanos: marker.hostSensorNanos,
      elapsedNanos,
      elapsedMillis: Math.round(elapsedNanos / 1_000_000),
      lapElapsedNanos,
      lapElapsedMillis: Math.round(lapElapsedNanos / 1_000_000),
      distanceMeters,
      lapDistanceMeters,
      averageSpeedMps,
      lapSpeedMps,
      receivedAtIso: new Date().toISOString(),
    });

    previousHostSensorNanos = marker.hostSensorNanos;
    if (distanceMeters !== null) {
      previousDistanceMeters = distanceMeters;
    }
  }

  return results.sort((left, right) => left.elapsedNanos - right.elapsedNanos);
}

function stageToWireStage(stage) {
  return String(stage ?? SESSION_STAGE_LOBBY).toLowerCase();
}

function nowHostSensorNanos() {
  const value = nowHostElapsedNanos();
  const maxSafe = BigInt(Number.MAX_SAFE_INTEGER);
  if (value > maxSafe) {
    return Number(maxSafe);
  }
  return Number(value);
}

function protocolDevicesWithRoles() {
  return Array.from(clientsByEndpoint.values())
    .sort((left, right) => left.connectedAtIso.localeCompare(right.connectedAtIso))
    .map((client) => {
      const deviceId = client.stableDeviceId || client.endpointId;
      const fallbackRole = sessionState.roleAssignments[client.endpointId] ?? "Unassigned";
      const assignedRole = sessionState.roleAssignments[deviceId] ?? fallbackRole;
      const cameraFacing = resolveCameraFacingForRoleTarget(deviceId, client.cameraFacing);
      return {
        endpointId: client.endpointId,
        id: deviceId,
        name: client.deviceName || client.endpointId,
        roleLabel: assignedRole,
        cameraFacing,
      };
    });
}

function createProtocolSnapshotForEndpoint(endpointId) {
  const protocolDevices = protocolDevicesWithRoles();
  if (protocolDevices.length === 0) {
    return null;
  }

  const selfDevice = protocolDevices.find((device) => device.endpointId === endpointId);
  const anchorDevice = protocolDevices.find((device) => roleLabelToWireRole(device.roleLabel) === "start");

  return {
    type: "snapshot",
    stage: stageToWireStage(sessionState.stage),
    monitoringActive: sessionState.monitoringActive,
    devices: protocolDevices.map((device) => ({
      id: device.id,
      name: device.name,
      role: roleLabelToWireRole(device.roleLabel),
      cameraFacing: device.cameraFacing,
      isLocal: false,
    })),
    timeline: {
      hostStartSensorNanos:
        Number.isFinite(sessionState.hostStartSensorNanos) ? sessionState.hostStartSensorNanos : null,
      hostStopSensorNanos:
        Number.isFinite(sessionState.hostStopSensorNanos) ? sessionState.hostStopSensorNanos : null,
      hostSplitMarks: sessionState.hostSplitMarks.map((split) => ({
        role: roleLabelToWireRole(split.roleLabel),
        hostSensorNanos: split.hostSensorNanos,
      })),
      hostSplitSensorNanos: sessionState.hostSplitMarks.map((split) => split.hostSensorNanos),
    },
    runId: sessionState.runId,
    hostSensorMinusElapsedNanos: 0,
    hostGpsUtcOffsetNanos: null,
    hostGpsFixAgeNanos: null,
    selfDeviceId: selfDevice?.id ?? null,
    anchorDeviceId: anchorDevice?.id ?? null,
    anchorState: sessionState.monitoringActive ? "active" : "ready",
  };
}

function sendTcpJsonMessage(endpointId, payloadObject) {
  const payloadBuffer = Buffer.from(JSON.stringify(payloadObject), "utf8");
  return sendTcpFrame(endpointId, FRAME_KIND_MESSAGE, payloadBuffer);
}

function sendProtocolSnapshotToEndpoint(endpointId) {
  const payload = createProtocolSnapshotForEndpoint(endpointId);
  if (!payload) {
    return false;
  }
  return sendTcpJsonMessage(endpointId, payload);
}

function broadcastProtocolSnapshots() {
  for (const endpointId of socketsByEndpoint.keys()) {
    sendProtocolSnapshotToEndpoint(endpointId);
  }
}

function broadcastProtocolTrigger(triggerType, triggerSensorNanos, splitIndex = null) {
  const payload = {
    type: "session_trigger",
    triggerType,
    splitIndex,
    triggerSensorNanos,
  };

  for (const endpointId of socketsByEndpoint.keys()) {
    sendTcpJsonMessage(endpointId, payload);
  }
}

function broadcastProtocolTriggerRefinement(
  roleLabel,
  provisionalHostSensorNanos,
  refinedHostSensorNanos,
) {
  const role = roleLabelToWireRole(roleLabel);
  if (role === "unassigned") {
    return;
  }
  if (!sessionState.runId) {
    return;
  }

  const payload = {
    type: "trigger_refinement",
    runId: sessionState.runId,
    role,
    provisionalHostSensorNanos,
    refinedHostSensorNanos,
  };

  for (const endpointId of socketsByEndpoint.keys()) {
    sendTcpJsonMessage(endpointId, payload);
  }
}

function createTimelineSnapshotPayload() {
  return {
    type: "timeline_snapshot",
    hostStartSensorNanos:
      Number.isFinite(sessionState.hostStartSensorNanos) ? sessionState.hostStartSensorNanos : null,
    hostStopSensorNanos:
      Number.isFinite(sessionState.hostStopSensorNanos) ? sessionState.hostStopSensorNanos : null,
    hostSplitMarks: sessionState.hostSplitMarks.map((split) => ({
      role: roleLabelToWireRole(split.roleLabel),
      hostSensorNanos: split.hostSensorNanos,
    })),
    hostSplitSensorNanos: sessionState.hostSplitMarks.map((split) => split.hostSensorNanos),
    sentElapsedNanos: nowHostSensorNanos(),
  };
}

function broadcastTimelineSnapshot() {
  const payload = createTimelineSnapshotPayload();
  for (const endpointId of socketsByEndpoint.keys()) {
    sendTcpJsonMessage(endpointId, payload);
  }
}

function sessionElapsedMsNow() {
  if (sessionState.monitoringActive && sessionState.monitoringStartedAtMs) {
    return Math.max(0, Date.now() - sessionState.monitoringStartedAtMs);
  }
  return Math.max(0, sessionState.monitoringElapsedMs);
}

function createSnapshot() {
  const timelineLapResults = createTimelineLapResults();
  const latestLapResults =
    timelineLapResults.length > 0
      ? timelineLapResults
      : Array.from(latestLapByEndpoint.values()).sort((a, b) => a.elapsedNanos - b.elapsedNanos);
  const clients = Array.from(clientsByEndpoint.values()).sort((a, b) =>
    a.connectedAtIso.localeCompare(b.connectedAtIso),
  );

  const clientsWithRoles = clients.map((client) => {
    const roleTarget = client.stableDeviceId || client.endpointId;
    const role = sessionState.roleAssignments[roleTarget] ?? "Unassigned";
    const sensitivity = resolveSensitivityForRoleTarget(roleTarget, client.telemetrySensitivity);
    const cameraFacing = resolveCameraFacingForRoleTarget(roleTarget, client.cameraFacing);
    const distanceMeters = resolveDistanceForRoleTarget(roleTarget, client.distanceMeters);
    return {
      ...client,
      assignedRole: role,
      roleTarget,
      sensitivity,
      cameraFacing,
      distanceMeters,
    };
  });

  const monitoringElapsedMs = sessionElapsedMsNow();

  return {
    server: {
      name: "Sprint Sync Windows Backend",
      timestampIso: new Date().toISOString(),
      startedAtIso: new Date(startedAtMs).toISOString(),
      uptimeMs: Date.now() - startedAtMs,
      tcp: {
        host: config.tcpHost,
        port: config.tcpPort,
      },
      http: {
        host: config.httpHost,
        port: config.httpPort,
      },
    },
    stats: {
      connectedClients: clientsWithRoles.length,
      totalFrames: messageStats.totalFrames,
      messageFrames: messageStats.messageFrames,
      binaryFrames: messageStats.binaryFrames,
      parseErrors: messageStats.parseErrors,
      totalLapResults: lapHistory.length,
      knownTypes: messageStats.knownTypes,
    },
    session: {
      stage: sessionState.stage,
      monitoringActive: sessionState.monitoringActive,
      monitoringStartedIso: sessionState.monitoringStartedIso,
      monitoringElapsedMs,
      runId: sessionState.runId,
      hostStartSensorNanos:
        Number.isFinite(sessionState.hostStartSensorNanos) ? sessionState.hostStartSensorNanos : null,
      hostStopSensorNanos:
        Number.isFinite(sessionState.hostStopSensorNanos) ? sessionState.hostStopSensorNanos : null,
      hostSplitMarks: sessionState.hostSplitMarks,
      roleOptions: computeRoleOptions(),
      roleAssignments: sessionState.roleAssignments,
      distanceAssignments: sessionState.deviceDistanceAssignments,
    },
    resultsExport: {
      directory: config.resultsDir,
      lastSavedFilePath: sessionState.lastSavedResultsFilePath,
      lastSavedAtIso: sessionState.lastSavedResultsAtIso,
    },
    clockDomainMapping: {
      implemented: clockDomainState.implemented,
      source: clockDomainState.source,
      samplesResponded: clockDomainState.samplesResponded,
      ignoredFrames: clockDomainState.ignoredFrames,
      lastEndpointId: clockDomainState.lastEndpointId,
      lastRequestAtIso: clockDomainState.lastRequestAtIso,
      lastResponseAtIso: clockDomainState.lastResponseAtIso,
      lastHostReceiveElapsedNanos: clockDomainState.lastHostReceiveElapsedNanos,
      lastHostSendElapsedNanos: clockDomainState.lastHostSendElapsedNanos,
      description:
        "Windows host now responds to binary clock-sync requests and acts as the active host elapsed time-domain source for connected clients.",
    },
    clients: clientsWithRoles,
    latestLapResults,
    lapHistory: [...lapHistory].reverse(),
    recentEvents: [...recentEvents].reverse(),
  };
}

function sendSocketMessage(socket, payload) {
  if (socket.readyState === 1) {
    socket.send(JSON.stringify(payload));
  }
}

function sendTcpFrame(endpointId, frameKind, payloadBuffer) {
  const context = socketsByEndpoint.get(endpointId);
  if (!context || !context.socket || context.socket.destroyed) {
    return false;
  }

  const frame = Buffer.alloc(5 + payloadBuffer.length);
  frame.writeUInt8(frameKind, 0);
  frame.writeInt32BE(payloadBuffer.length, 1);
  payloadBuffer.copy(frame, 5);

  try {
    context.socket.write(frame);
    return true;
  } catch (error) {
    pushEvent("warn", `Failed to send TCP frame to ${endpointId}: ${error.message}`, { endpointId });
    return false;
  }
}

function broadcast(payload) {
  if (!websocketServer) {
    return;
  }
  const encoded = JSON.stringify(payload);
  for (const client of websocketServer.clients) {
    if (client.readyState === 1) {
      client.send(encoded);
    }
  }
}

function publishState() {
  broadcast({ type: "state:update", payload: createSnapshot() });
}

function shutdown(signal) {
  pushEvent("info", `Shutting down after ${signal}`);
  publishState();

  for (const context of socketsByEndpoint.values()) {
    context.socket.destroy();
  }
  socketsByEndpoint.clear();
  clearInterval(monitoringTicker);

  tcpServer.close(() => {
    httpServer.close(() => {
      process.exit(0);
    });
  });

  setTimeout(() => process.exit(1), 2_000).unref();
}
