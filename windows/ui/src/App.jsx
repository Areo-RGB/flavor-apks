import { useEffect, useMemo, useRef, useState } from "react";

const ROLE_ORDER = ["Unassigned", "Start", "Split 1", "Split 2", "Split 3", "Split 4", "Stop"];

function formatDurationNanos(nanos) {
  if (!Number.isFinite(nanos) || nanos <= 0) return "-";
  const centiseconds = Math.round(nanos / 10_000_000);
  const minutes = Math.floor(centiseconds / 6_000);
  const seconds = Math.floor((centiseconds % 6_000) / 100);
  const cs = centiseconds % 100;
  if (minutes > 0) {
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${String(cs).padStart(2, "0")}`;
  }
  return `${String(seconds).padStart(2, "0")}.${String(cs).padStart(2, "0")}`;
}

function formatRaceClockMs(ms) {
  if (!Number.isFinite(ms) || ms < 0) return "00.00";
  const centiseconds = Math.floor(ms / 10);
  const minutes = Math.floor(centiseconds / 6_000);
  const seconds = Math.floor((centiseconds % 6_000) / 100);
  const cs = centiseconds % 100;
  if (minutes > 0) {
    return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${String(cs).padStart(2, "0")}`;
  }
  return `${String(seconds).padStart(2, "0")}.${String(cs).padStart(2, "0")}`;
}

function formatMeters(distanceMeters) {
  if (!Number.isFinite(distanceMeters) || distanceMeters < 0) return "-";
  return `${distanceMeters.toFixed(2)} m`;
}

function formatSpeed(speedMps) {
  if (!Number.isFinite(speedMps) || speedMps < 0) return "-";
  return `${speedMps.toFixed(2)} m/s`;
}

function formatIsoTime(isoValue) {
  if (typeof isoValue !== "string" || isoValue.length === 0) return "";
  const parsed = new Date(isoValue);
  if (Number.isNaN(parsed.getTime())) return isoValue;
  return parsed.toLocaleString();
}

function stageLabel(stage) {
  if (stage === "MONITORING") return "Monitoring";
  if (stage === "SETUP") return "Setup";
  return "Lobby";
}

function roleOrderIndex(roleLabel) {
  const index = ROLE_ORDER.indexOf(roleLabel);
  return index === -1 ? ROLE_ORDER.length : index;
}

function normalizeRoleOptions(roleOptions) {
  const unique = Array.from(new Set((roleOptions ?? []).filter((role) => typeof role === "string" && role.length > 0)));
  if (unique.length === 0) {
    return ["Unassigned", "Start", "Split 1", "Stop"];
  }
  return unique.sort((left, right) => roleOrderIndex(left) - roleOrderIndex(right));
}

function computeProgressiveRoleOptions(clients) {
  const assignedRoles = new Set((clients ?? []).map((client) => client.assignedRole));
  let unlockedSplitCount = 1;
  while (unlockedSplitCount < 4 && assignedRoles.has(`Split ${unlockedSplitCount}`)) {
    unlockedSplitCount += 1;
  }

  const options = [
    "Unassigned",
    "Start",
    ...["Split 1", "Split 2", "Split 3", "Split 4"].slice(0, unlockedSplitCount),
    "Stop",
  ];

  for (const assignedRole of assignedRoles) {
    if (typeof assignedRole === "string" && assignedRole.startsWith("Split") && !options.includes(assignedRole)) {
      options.push(assignedRole);
    }
  }

  return options.sort((left, right) => roleOrderIndex(left) - roleOrderIndex(right));
}

function ActionButton({
  label,
  onClick,
  busy,
  disabled = false,
  variant = "primary",
  active = false,
}) {
  let className = "rounded-md px-3 py-2 text-sm font-semibold disabled:opacity-50";
  if (variant === "secondary") {
    className += active
      ? " border border-slate-700 bg-slate-700 text-white"
      : " border border-slate-300 bg-white text-slate-700";
  } else if (variant === "start") {
    className += active
      ? " border border-emerald-700 bg-emerald-600 text-white ring-2 ring-emerald-300"
      : " border border-emerald-300 bg-emerald-50 text-emerald-700";
  } else if (variant === "stop") {
    className += active
      ? " border border-rose-700 bg-rose-600 text-white ring-2 ring-rose-300"
      : " border border-slate-300 bg-white text-slate-600";
  } else {
    className += " bg-slate-900 text-white";
  }

  return (
    <button type="button" onClick={onClick} disabled={disabled || busy} className={className}>
      {busy ? "Working..." : label}
    </button>
  );
}

function Card({ title, subtitle, children }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-700">{title}</h2>
      {subtitle ? <p className="mb-3 text-xs text-slate-500">{subtitle}</p> : null}
      {children}
    </section>
  );
}

export default function App() {
  const [snapshot, setSnapshot] = useState(null);
  const [wsConnected, setWsConnected] = useState(false);
  const [busyAction, setBusyAction] = useState("");
  const [lastError, setLastError] = useState("");
  const [refreshing, setRefreshing] = useState(false);
  const [sensitivityDraftByTarget, setSensitivityDraftByTarget] = useState({});
  const [distanceDraftByTarget, setDistanceDraftByTarget] = useState({});
  const raceClockBaseMsRef = useRef(null);

  async function fetchState() {
    setRefreshing(true);
    try {
      const response = await fetch("/api/state");
      if (!response.ok) throw new Error(`State request failed (${response.status})`);
      setSnapshot(await response.json());
      setLastError("");
    } catch (error) {
      setLastError(error instanceof Error ? error.message : "State fetch failed");
    } finally {
      setRefreshing(false);
    }
  }

  async function postControl(path, body = null, actionKey = path) {
    setBusyAction(actionKey);
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: body ? JSON.stringify(body) : undefined,
      });

      if (!response.ok) {
        let message = `Request failed (${response.status})`;
        try {
          const payload = await response.json();
          if (payload?.error) message = payload.error;
        } catch {
          // Keep generic request error message.
        }
        throw new Error(message);
      }

      await fetchState();
      setLastError("");
    } catch (error) {
      setLastError(error instanceof Error ? error.message : "Control request failed");
    } finally {
      setBusyAction("");
    }
  }

  function assignRole(targetId, role) {
    postControl("/api/control/assign-role", { targetId, role }, `assign-role:${targetId}`);
  }

  function fireTrigger(role) {
    postControl("/api/control/trigger", { role }, `trigger:${role}`);
  }

  function updateDeviceConfig(targetId, patch, actionKey) {
    postControl("/api/control/device-config", { targetId, ...patch }, actionKey);
  }

  function setCameraFacing(targetId, cameraFacing) {
    updateDeviceConfig(targetId, { cameraFacing }, `device-config-camera:${targetId}`);
  }

  function updateSensitivityDraft(targetId, rawValue) {
    setSensitivityDraftByTarget((previous) => ({
      ...previous,
      [targetId]: rawValue,
    }));
  }

  function applySensitivity(targetId, fallbackSensitivity) {
    const rawValue = sensitivityDraftByTarget[targetId] ?? String(fallbackSensitivity ?? 100);
    const parsedValue = Number(rawValue);
    if (!Number.isInteger(parsedValue) || parsedValue < 1 || parsedValue > 100) {
      setLastError("Sensitivity must be an integer in the range 1 to 100.");
      return;
    }
    updateDeviceConfig(targetId, { sensitivity: parsedValue }, `device-config-sensitivity:${targetId}`);
    setSensitivityDraftByTarget((previous) => ({
      ...previous,
      [targetId]: String(parsedValue),
    }));
  }

  function updateDistanceDraft(targetId, rawValue) {
    setDistanceDraftByTarget((previous) => ({
      ...previous,
      [targetId]: rawValue,
    }));
  }

  function applyDistance(targetId, fallbackDistanceMeters) {
    const rawValue = distanceDraftByTarget[targetId] ?? String(fallbackDistanceMeters ?? 0);
    const parsedValue = Number(rawValue);
    if (!Number.isFinite(parsedValue) || parsedValue < 0 || parsedValue > 100000) {
      setLastError("Distance must be a number in the range 0 to 100000 meters.");
      return;
    }
    const normalizedDistance = Math.round(parsedValue * 1000) / 1000;
    updateDeviceConfig(targetId, { distanceMeters: normalizedDistance }, `device-config-distance:${targetId}`);
    setDistanceDraftByTarget((previous) => ({
      ...previous,
      [targetId]: String(normalizedDistance),
    }));
  }

  function saveResultsJson() {
    postControl("/api/control/save-results", null, "/api/control/save-results");
  }

  useEffect(() => {
    let socket;
    let disposed = false;
    let reconnectHandle;

    function connect() {
      const protocol = window.location.protocol === "https:" ? "wss" : "ws";
      socket = new WebSocket(`${protocol}://${window.location.host}/ws`);

      socket.onopen = () => {
        if (disposed) return;
        setWsConnected(true);
      };

      socket.onmessage = (event) => {
        if (disposed) return;
        try {
          const payload = JSON.parse(event.data);
          if (payload.type === "snapshot" || payload.type === "state:update") {
            setSnapshot(payload.payload);
          }
        } catch {
          setLastError("Malformed WebSocket payload");
        }
      };

      socket.onclose = () => {
        if (disposed) return;
        setWsConnected(false);
        reconnectHandle = window.setTimeout(connect, 1500);
      };

      socket.onerror = () => {
        if (disposed) return;
        setLastError("WebSocket error");
      };
    }

    fetchState();
    connect();

    return () => {
      disposed = true;
      if (reconnectHandle) window.clearTimeout(reconnectHandle);
      if (socket) socket.close();
    };
  }, []);

  const session = snapshot?.session ?? {
    stage: "LOBBY",
    monitoringActive: false,
    monitoringElapsedMs: 0,
    hostStartSensorNanos: null,
    hostStopSensorNanos: null,
    hostSplitMarks: [],
    roleOptions: [],
  };
  const stage = session.stage ?? "LOBBY";
  const monitoringActive = stage === "MONITORING" || Boolean(session.monitoringActive);
  const hostStartSensorNanos = Number.isFinite(session.hostStartSensorNanos)
    ? session.hostStartSensorNanos
    : null;
  const hostStopSensorNanos = Number.isFinite(session.hostStopSensorNanos)
    ? session.hostStopSensorNanos
    : null;
  const hostSplitMarks = Array.isArray(session.hostSplitMarks) ? session.hostSplitMarks : [];
  const firedSplitRoles = new Set(
    hostSplitMarks
      .map((splitMark) => splitMark?.roleLabel)
      .filter((roleLabel) => typeof roleLabel === "string"),
  );
  const clients = snapshot?.clients ?? [];
  const latestLapResults = snapshot?.latestLapResults ?? [];
  const recentEvents = snapshot?.recentEvents ?? [];
  const resultsExport = snapshot?.resultsExport ?? {};
  const lastSavedFilePath =
    typeof resultsExport.lastSavedFilePath === "string" ? resultsExport.lastSavedFilePath : "";
  const lastSavedAtIso = typeof resultsExport.lastSavedAtIso === "string" ? resultsExport.lastSavedAtIso : "";
  const canSaveResults =
    latestLapResults.length > 0 ||
    (hostStartSensorNanos !== null && hostStopSensorNanos !== null && hostStopSensorNanos > hostStartSensorNanos);
  const runCompleted = hostStartSensorNanos !== null && hostStopSensorNanos !== null && hostStopSensorNanos > hostStartSensorNanos;
  const timerStateLabel = monitoringActive ? "Monitoring" : runCompleted ? "Run Complete" : "Ready";
  const bestResult = latestLapResults.length > 0 ? latestLapResults[0] : null;

  const knownTypes = useMemo(() => {
    const values = snapshot?.stats?.knownTypes ?? {};
    return Object.entries(values).sort(([a], [b]) => a.localeCompare(b));
  }, [snapshot]);

  const fallbackRoleOptions = useMemo(() => computeProgressiveRoleOptions(clients), [clients]);
  const serverRoleOptions = useMemo(() => normalizeRoleOptions(session.roleOptions), [session.roleOptions]);
  const roleOptions = serverRoleOptions.length > 0 ? serverRoleOptions : fallbackRoleOptions;
  const triggerRoles = ["Start", "Split 1", "Split 2", "Split 3", "Split 4", "Stop"];
  const hasStartAssignment = clients.some((client) => client.assignedRole === "Start");
  const hasStopAssignment = clients.some((client) => client.assignedRole === "Stop");
  const canStartMonitoring = clients.length > 0 && hasStartAssignment && hasStopAssignment && !monitoringActive;

  useEffect(() => {
    if (hostStartSensorNanos === null) {
      raceClockBaseMsRef.current = null;
      return;
    }
    if (raceClockBaseMsRef.current === null && Number.isFinite(session.monitoringElapsedMs)) {
      raceClockBaseMsRef.current = session.monitoringElapsedMs;
    }
  }, [hostStartSensorNanos, session.monitoringElapsedMs]);

  const raceClockDisplay = useMemo(() => {
    if (hostStartSensorNanos === null) {
      return "00.00";
    }
    if (hostStopSensorNanos !== null && hostStopSensorNanos > hostStartSensorNanos) {
      return formatDurationNanos(hostStopSensorNanos - hostStartSensorNanos);
    }
    if (!monitoringActive) {
      return "00.00";
    }

    const monitoringElapsedMs = Number.isFinite(session.monitoringElapsedMs) ? session.monitoringElapsedMs : 0;
    const baseMs = Number.isFinite(raceClockBaseMsRef.current) ? raceClockBaseMsRef.current : monitoringElapsedMs;
    return formatRaceClockMs(Math.max(0, monitoringElapsedMs - baseMs));
  }, [hostStartSensorNanos, hostStopSensorNanos, monitoringActive, session.monitoringElapsedMs]);

  function triggerDisabled(roleLabel) {
    if (!monitoringActive) {
      return true;
    }

    if (roleLabel === "Start") {
      return hostStartSensorNanos !== null;
    }

    if (roleLabel === "Stop") {
      return hostStartSensorNanos === null || hostStopSensorNanos !== null;
    }

    const splitMatch = /^Split\s+(\d)$/i.exec(roleLabel);
    if (!splitMatch) {
      return false;
    }

    const splitIndex = Number(splitMatch[1]);
    if (hostStartSensorNanos === null || hostStopSensorNanos !== null) {
      return true;
    }
    if (firedSplitRoles.has(roleLabel)) {
      return true;
    }
    if (splitIndex > 1 && !firedSplitRoles.has(`Split ${splitIndex - 1}`)) {
      return true;
    }
    return false;
  }

  function triggerActive(roleLabel) {
    if (roleLabel === "Start") {
      return hostStartSensorNanos !== null && hostStopSensorNanos === null;
    }
    if (roleLabel === "Stop") {
      return hostStopSensorNanos !== null;
    }
    return firedSplitRoles.has(roleLabel);
  }

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900">
      <main className="mx-auto flex w-full max-w-7xl flex-col gap-4 p-4 md:p-6">
        <header className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h1 className="text-xl font-bold">Sprint Sync Windows Coordinator</h1>
              <p className="text-sm text-slate-600">
                Windows auto-hosts session. Connected devices receive lobby and monitoring state from this coordinator.
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700">
                {stageLabel(stage)}
              </span>
              <span
                className={`rounded-full px-3 py-1 text-xs font-semibold ${
                  wsConnected ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"
                }`}
              >
                {wsConnected ? "Live" : "Reconnecting"}
              </span>
            </div>
          </div>
          {lastError ? (
            <p className="mt-3 rounded-md bg-rose-100 px-3 py-2 text-sm text-rose-700">{lastError}</p>
          ) : null}
        </header>

        <section className="rounded-2xl border border-slate-900 bg-slate-900 p-6 text-white shadow-lg">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.25em] text-slate-400">Race Timer</p>
              <p className="mt-2 font-mono text-5xl font-bold leading-none md:text-7xl">{raceClockDisplay}</p>
              <p className="mt-3 text-sm text-slate-300">
                {timerStateLabel} · Start {hostStartSensorNanos !== null ? "set" : "pending"} · Splits {hostSplitMarks.length}/4
                {

        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="mb-3 flex flex-wrap gap-2">
            <ActionButton
              label={refreshing ? "Refreshing..." : "Refresh"}
              onClick={fetchState}
              busy={refreshing}
              variant="secondary"
            />
            <ActionButton
              label="Start Monitoring"
              onClick={() => postControl("/api/control/start-monitoring")}
              busy={busyAction === "/api/control/start-monitoring"}
              disabled={!canStartMonitoring}
              variant="start"
              active={monitoringActive}
            />
            <ActionButton
              label="Stop Monitoring"
              onClick={() => postControl("/api/control/stop-monitoring")}
              busy={busyAction === "/api/control/stop-monitoring"}
              disabled={!monitoringActive}
              variant="stop"
              active={monitoringActive}
            />
            <ActionButton
              label="Reset Run"
              onClick={() => postControl("/api/control/reset-run")}
              busy={busyAction === "/api/control/reset-run"}
              variant="secondary"
            />
            <ActionButton
              label="Save Results JSON"
              onClick={saveResultsJson}
              busy={busyAction === "/api/control/save-results"}
              disabled={!canSaveResults}
              variant="secondary"
            />
          </div>
          <div className="mb-3 flex flex-wrap gap-2">
            {triggerRoles.map((roleLabel) => (
              <ActionButton
                key={roleLabel}
                label={roleLabel}
                onClick={() => fireTrigger(roleLabel)}
                busy={busyAction === `trigger:${roleLabel}`}
                disabled={triggerDisabled(roleLabel)}
                variant={roleLabel === "Start" ? "start" : roleLabel === "Stop" ? "stop" : "secondary"}
                active={triggerActive(roleLabel)}
              />
            ))}
          </div>
          <p className="text-xs text-slate-500">
            Monitoring controls switch stage only. Trigger buttons emit Start, progressive Splits, and Stop packets while monitoring is active.
          </p>
          {!monitoringActive && (!hasStartAssignment || !hasStopAssignment) ? (
            <p className="mt-2 text-xs text-amber-700">
              Assign one device to Start and one device to Stop before starting monitoring.
            </p>
          ) : null}
          {lastSavedFilePath ? (
            <p className="mt-2 break-all text-xs text-slate-500">
              Last saved: {lastSavedFilePath}
              {lastSavedAtIso ? ` (${lastSavedAtIso})` : ""}
            </p>
          ) : null}
        </div>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          <Card
            title={monitoringActive ? "Monitoring Devices" : "Connected Devices"}
            subtitle={
              monitoringActive
                ? "Roles are locked in monitoring view; sensitivity and camera settings can still be tuned."
                : "Role assignment uses progressive split options to avoid dropdown crowding."
            }
          >
            {clients.length === 0 ? (
              <p className="text-sm text-slate-500">No peers connected yet.</p>
            ) : (
              <div className="space-y-2">
                {clients.map((client) => {
                  const targetId = client.roleTarget;
                  const actionKey = `assign-role:${targetId}`;
                  const cameraActionKey = `device-config-camera:${targetId}`;
                  const sensitivityActionKey = `device-config-sensitivity:${targetId}`;
                  const assignedRole = client.assignedRole ?? "Unassigned";
                  const effectiveSensitivity =
                    Number.isInteger(client.sensitivity) && client.sensitivity >= 1 && client.sensitivity <= 100
                      ? client.sensitivity
                      : 100;
                  const sensitivityDraft = sensitivityDraftByTarget[targetId] ?? String(effectiveSensitivity);
                  const cameraFacing = client.cameraFacing === "front" ? "front" : "rear";
                  const latencyLabel =
                    Number.isInteger(client.telemetryLatencyMs) && client.telemetryLatencyMs >= 0
                      ? `${client.telemetryLatencyMs} ms`
                      : "-";
                  const syncLabel = client.telemetryClockSynced ? "Synced" : "Unsynced";
                  const clientRoleOptions = roleOptions.includes(assignedRole)
                    ? roleOptions
                    : [...roleOptions, assignedRole].sort(
                        (left, right) => roleOrderIndex(left) - roleOrderIndex(right),
                      );

                  return (
                    <div key={client.endpointId} className="rounded-md border border-slate-200 bg-slate-50 p-3">
                      <div className="text-sm font-semibold">{client.deviceName ?? "Unknown device"}</div>
                      <div className="mb-2 font-mono text-xs text-slate-500">{targetId}</div>
                      {monitoringActive ? (
                        <p className="rounded border border-slate-200 bg-white px-2 py-2 text-sm text-slate-700">
                          Role: {assignedRole}
                        </p>
                      ) : (
                        <select
                          className="w-full rounded-md border border-slate-300 bg-white px-2 py-2 text-sm"
                          value={assignedRole}
                          disabled={busyAction === actionKey}
                          onChange={(event) => assignRole(targetId, event.target.value)}
                        >
                          {clientRoleOptions.map((role) => (
                            <option key={role} value={role}>
                              {role}
                            </option>
                          ))}
                        </select>
                      )}

                      <div className="mt-2 grid gap-2 sm:grid-cols-2">
                        <label className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                          Camera
                          <select
                            className="mt-1 w-full rounded-md border border-slate-300 bg-white px-2 py-2 text-sm text-slate-700"
                            value={cameraFacing}
                            disabled={busyAction === cameraActionKey}
                            onChange={(event) => setCameraFacing(targetId, event.target.value)}
                          >
                            <option value="rear">Rear</option>
                            <option value="front">Front</option>
                          </select>
                        </label>

                        <label className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                          Sensitivity
                          <div className="mt-1 flex items-center gap-2">
                            <input
                              type="number"
                              min={1}
                              max={100}
                              step={1}
                              className="w-full rounded-md border border-slate-300 bg-white px-2 py-2 text-sm text-slate-700"
                              value={sensitivityDraft}
                              disabled={busyAction === sensitivityActionKey}
                              onChange={(event) => updateSensitivityDraft(targetId, event.target.value)}
                            />
                            <ActionButton
                              label="Apply"
                              onClick={() => applySensitivity(targetId, effectiveSensitivity)}
                              busy={busyAction === sensitivityActionKey}
                              variant="secondary"
                            />
                          </div>
                        </label>
                      </div>

                      <p className="mt-2 text-xs text-slate-500">
                        Latency: {latencyLabel} · Clock: {syncLabel}
                      </p>
                    </div>
                  );
                })}
              </div>
            )}
          </Card>

          <Card
            title={monitoringActive ? "Monitoring Results" : "Latest Lap Results"}
            subtitle="Lowest elapsed time ranks first"
          >
            {latestLapResults.length === 0 ? (
              <p className="text-sm text-slate-500">No lap results received yet.</p>
            ) : (
              <div className="overflow-auto">
                <table className="min-w-full text-left text-sm">
                  <thead className="text-xs uppercase tracking-wide text-slate-500">
                    <tr>
                      <th className="pb-2 pr-3">Rank</th>
                      <th className="pb-2 pr-3">Device</th>
                      <th className="pb-2 pr-3">Elapsed</th>
                      <th className="pb-2">Role</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {latestLapResults.map((lap, index) => {
                      const matchingClient = clients.find((client) => client.endpointId === lap.endpointId);
                      return (
                        <tr key={lap.id}>
                          <td className="py-2 pr-3 font-semibold text-slate-700">{index + 1}</td>
                          <td className="py-2 pr-3 text-slate-900">{lap.senderDeviceName}</td>
                          <td className="py-2 pr-3 font-mono text-slate-900">
                            {formatDurationNanos(lap.elapsedNanos)}
                          </td>
                          <td className="py-2 text-slate-700">{matchingClient?.assignedRole ?? "Unassigned"}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          <Card title="Protocol Message Types" subtitle="Observed input traffic">
            {knownTypes.length === 0 ? (
              <p className="text-sm text-slate-500">No message types observed yet.</p>
            ) : (
              <ul className="grid grid-cols-1 gap-1 text-sm sm:grid-cols-2">
                {knownTypes.map(([name, count]) => (
                  <li
                    key={name}
                    className="flex items-center justify-between rounded border border-slate-200 bg-slate-50 px-2 py-1"
                  >
                    <span className="font-mono text-xs">{name}</span>
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-semibold">{count}</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card title="Recent Events" subtitle="Newest first">
            {recentEvents.length === 0 ? (
              <p className="text-sm text-slate-500">No events logged yet.</p>
            ) : (
              <ul className="max-h-80 space-y-2 overflow-auto text-sm">
                {recentEvents.map((event) => (
                  <li key={event.id} className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-semibold text-slate-800">{event.message}</span>
                      <span className="text-xs uppercase tracking-wide text-slate-500">{event.level}</span>
                    </div>
                    <div className="mt-1 text-xs text-slate-500">{event.timestampIso}</div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </main>
    </div>
  );
}