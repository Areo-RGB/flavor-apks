package com.paul.sprintsync.features.race_session

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class SessionStage {
    SETUP,
    LOBBY,
    MONITORING,
}

enum class SessionNetworkRole {
    NONE,
    HOST,
    CLIENT,
}

enum class SessionDeviceRole {
    UNASSIGNED,
    START,
    STOP,
}

enum class SessionCameraFacing {
    REAR,
    FRONT,
}

data class SessionDevice(
    val id: String,
    val name: String,
    val role: SessionDeviceRole,
    val cameraFacing: SessionCameraFacing = SessionCameraFacing.REAR,
    val highSpeedEnabled: Boolean = false,
    val isLocal: Boolean,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("role", role.name.lowercase())
            .put("cameraFacing", cameraFacing.name.lowercase())
            .put("highSpeedEnabled", highSpeedEnabled)
            .put("isLocal", isLocal)
    }

    companion object {
        fun fromJsonObject(decoded: JSONObject): SessionDevice? {
            val id = decoded.optString("id", "").trim()
            val name = decoded.optString("name", "").trim()
            val role = sessionDeviceRoleFromName(decoded.readOptionalString("role"))
            val cameraFacing = sessionCameraFacingFromName(decoded.readOptionalString("cameraFacing"))
                ?: SessionCameraFacing.REAR
            if (id.isEmpty() || name.isEmpty() || role == null) {
                return null
            }
            return SessionDevice(
                id = id,
                name = name,
                role = role,
                cameraFacing = cameraFacing,
                highSpeedEnabled = decoded.optBoolean("highSpeedEnabled", false),
                isLocal = decoded.optBoolean("isLocal", false),
            )
        }
    }
}

data class SessionSnapshotMessage(
    val stage: SessionStage,
    val monitoringActive: Boolean,
    val devices: List<SessionDevice>,
    val hostStartSensorNanos: Long?,
    val hostStopSensorNanos: Long?,
    val runId: String?,
    val hostSensorMinusElapsedNanos: Long?,
    val hostGpsUtcOffsetNanos: Long?,
    val hostGpsFixAgeNanos: Long?,
    val selfDeviceId: String?,
) {
    fun toJsonString(): String {
        val devicesArray = JSONArray()
        devices.forEach { devicesArray.put(it.toJsonObject()) }
        val timeline = JSONObject()
            .put("hostStartSensorNanos", hostStartSensorNanos ?: JSONObject.NULL)
            .put("hostStopSensorNanos", hostStopSensorNanos ?: JSONObject.NULL)
        return JSONObject()
            .put("type", TYPE)
            .put("stage", stage.name.lowercase())
            .put("monitoringActive", monitoringActive)
            .put("devices", devicesArray)
            .put("timeline", timeline)
            .put("runId", runId ?: JSONObject.NULL)
            .put("hostSensorMinusElapsedNanos", hostSensorMinusElapsedNanos ?: JSONObject.NULL)
            .put("hostGpsUtcOffsetNanos", hostGpsUtcOffsetNanos ?: JSONObject.NULL)
            .put("hostGpsFixAgeNanos", hostGpsFixAgeNanos ?: JSONObject.NULL)
            .put("selfDeviceId", selfDeviceId ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        const val TYPE = "snapshot"

        fun tryParse(raw: String): SessionSnapshotMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val stage = sessionStageFromName(decoded.readOptionalString("stage")) ?: return null
            val devicesRaw = decoded.optJSONArray("devices") ?: return null
            val parsedDevices = mutableListOf<SessionDevice>()
            for (index in 0 until devicesRaw.length()) {
                val item = devicesRaw.optJSONObject(index) ?: continue
                val parsed = SessionDevice.fromJsonObject(item) ?: continue
                parsedDevices += parsed
            }
            if (parsedDevices.isEmpty()) {
                return null
            }
            val timeline = decoded.optJSONObject("timeline") ?: JSONObject()
            return SessionSnapshotMessage(
                stage = stage,
                monitoringActive = decoded.optBoolean("monitoringActive", false),
                devices = parsedDevices,
                hostStartSensorNanos = timeline.readOptionalLong("hostStartSensorNanos"),
                hostStopSensorNanos = timeline.readOptionalLong("hostStopSensorNanos"),
                runId = decoded.optString("runId", "").ifBlank { null },
                hostSensorMinusElapsedNanos = decoded.readOptionalLong("hostSensorMinusElapsedNanos"),
                hostGpsUtcOffsetNanos = decoded.readOptionalLong("hostGpsUtcOffsetNanos"),
                hostGpsFixAgeNanos = decoded.readOptionalLong("hostGpsFixAgeNanos"),
                selfDeviceId = decoded.optString("selfDeviceId", "").ifBlank { null },
            )
        }
    }
}

data class SessionTriggerRequestMessage(
    val role: SessionDeviceRole,
    val triggerSensorNanos: Long,
    val mappedHostSensorNanos: Long?,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("role", role.name.lowercase())
            .put("triggerSensorNanos", triggerSensorNanos)
            .put("mappedHostSensorNanos", mappedHostSensorNanos ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        const val TYPE = "trigger_request"

        fun tryParse(raw: String): SessionTriggerRequestMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val role = sessionDeviceRoleFromName(decoded.readOptionalString("role")) ?: return null
            val triggerSensorNanos = decoded.optLong("triggerSensorNanos", Long.MIN_VALUE)
            if (triggerSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerRequestMessage(
                role = role,
                triggerSensorNanos = triggerSensorNanos,
                mappedHostSensorNanos = decoded.readOptionalLong("mappedHostSensorNanos"),
            )
        }
    }
}

data class SessionTriggerRefinementMessage(
    val runId: String,
    val role: SessionDeviceRole,
    val provisionalHostSensorNanos: Long,
    val refinedHostSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("runId", runId)
            .put("role", role.name.lowercase())
            .put("provisionalHostSensorNanos", provisionalHostSensorNanos)
            .put("refinedHostSensorNanos", refinedHostSensorNanos)
            .toString()
    }

    companion object {
        const val TYPE = "trigger_refinement"

        fun tryParse(raw: String): SessionTriggerRefinementMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val runId = decoded.optString("runId", "").trim()
            val role = sessionDeviceRoleFromName(decoded.readOptionalString("role"))
            val provisional = decoded.optLong("provisionalHostSensorNanos", Long.MIN_VALUE)
            val refined = decoded.optLong("refinedHostSensorNanos", Long.MIN_VALUE)
            if (runId.isEmpty() || role == null || provisional == Long.MIN_VALUE || refined == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerRefinementMessage(
                runId = runId,
                role = role,
                provisionalHostSensorNanos = provisional,
                refinedHostSensorNanos = refined,
            )
        }
    }
}

data class SessionClockSyncRequestMessage(
    val clientSendElapsedNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("clientSendElapsedNanos", clientSendElapsedNanos)
            .toString()
    }

    companion object {
        const val TYPE = "clock_sync_request"

        fun tryParse(raw: String): SessionClockSyncRequestMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            if (!decoded.has("clientSendElapsedNanos")) {
                return null
            }
            val clientSendElapsedNanos = decoded.optLong("clientSendElapsedNanos", Long.MIN_VALUE)
            if (clientSendElapsedNanos == Long.MIN_VALUE) {
                return null
            }
            return SessionClockSyncRequestMessage(clientSendElapsedNanos = clientSendElapsedNanos)
        }
    }
}

data class SessionClockSyncResponseMessage(
    val clientSendElapsedNanos: Long,
    val hostReceiveElapsedNanos: Long,
    val hostSendElapsedNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("clientSendElapsedNanos", clientSendElapsedNanos)
            .put("hostReceiveElapsedNanos", hostReceiveElapsedNanos)
            .put("hostSendElapsedNanos", hostSendElapsedNanos)
            .toString()
    }

    companion object {
        const val TYPE = "clock_sync_response"

        fun tryParse(raw: String): SessionClockSyncResponseMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val clientSend = decoded.optLong("clientSendElapsedNanos", Long.MIN_VALUE)
            val hostReceive = decoded.optLong("hostReceiveElapsedNanos", Long.MIN_VALUE)
            val hostSend = decoded.optLong("hostSendElapsedNanos", Long.MIN_VALUE)
            if (clientSend == Long.MIN_VALUE || hostReceive == Long.MIN_VALUE || hostSend == Long.MIN_VALUE) {
                return null
            }
            return SessionClockSyncResponseMessage(
                clientSendElapsedNanos = clientSend,
                hostReceiveElapsedNanos = hostReceive,
                hostSendElapsedNanos = hostSend,
            )
        }
    }
}

data class SessionTimelineSnapshotMessage(
    val hostStartSensorNanos: Long?,
    val hostStopSensorNanos: Long?,
    val sentElapsedNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("hostStartSensorNanos", hostStartSensorNanos ?: JSONObject.NULL)
            .put("hostStopSensorNanos", hostStopSensorNanos ?: JSONObject.NULL)
            .put("sentElapsedNanos", sentElapsedNanos)
            .toString()
    }

    companion object {
        const val TYPE = "timeline_snapshot"

        fun tryParse(raw: String): SessionTimelineSnapshotMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val sentElapsedNanos = decoded.optLong("sentElapsedNanos", Long.MIN_VALUE)
            if (sentElapsedNanos == Long.MIN_VALUE) {
                return null
            }
            val hostStartSensorNanos = decoded.readOptionalLong("hostStartSensorNanos")
            val hostStopSensorNanos = decoded.readOptionalLong("hostStopSensorNanos")
            return SessionTimelineSnapshotMessage(
                hostStartSensorNanos = hostStartSensorNanos,
                hostStopSensorNanos = hostStopSensorNanos,
                sentElapsedNanos = sentElapsedNanos,
            )
        }
    }
}

data class SessionTriggerMessage(
    val triggerType: String,
    val triggerSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("triggerType", triggerType)
            .put("triggerSensorNanos", triggerSensorNanos)
            .toString()
    }

    companion object {
        const val TYPE = "session_trigger"

        fun tryParse(raw: String): SessionTriggerMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val triggerType = decoded.optString("triggerType", "").trim()
            val triggerSensorNanos = decoded.optLong("triggerSensorNanos", Long.MIN_VALUE)
            if (triggerType.isEmpty() || triggerSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerMessage(
                triggerType = triggerType,
                triggerSensorNanos = triggerSensorNanos,
            )
        }
    }
}

data class SessionDeviceIdentityMessage(
    val stableDeviceId: String,
    val deviceName: String,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("stableDeviceId", stableDeviceId)
            .put("deviceName", deviceName)
            .toString()
    }

    companion object {
        const val TYPE = "device_identity"

        fun tryParse(raw: String): SessionDeviceIdentityMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val stableDeviceId = decoded.optString("stableDeviceId", "").trim()
            val deviceName = decoded.optString("deviceName", "").trim()
            if (stableDeviceId.isEmpty() || deviceName.isEmpty()) {
                return null
            }
            return SessionDeviceIdentityMessage(
                stableDeviceId = stableDeviceId,
                deviceName = deviceName,
            )
        }
    }
}

fun sessionStageFromName(name: String?): SessionStage? {
    if (name == null) {
        return null
    }
    return SessionStage.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionDeviceRoleFromName(name: String?): SessionDeviceRole? {
    if (name == null) {
        return null
    }
    return SessionDeviceRole.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionCameraFacingFromName(name: String?): SessionCameraFacing? {
    if (name == null) {
        return null
    }
    return SessionCameraFacing.values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}

fun sessionDeviceRoleLabel(role: SessionDeviceRole): String {
    return when (role) {
        SessionDeviceRole.UNASSIGNED -> "Unassigned"
        SessionDeviceRole.START -> "Start"
        SessionDeviceRole.STOP -> "Stop"
    }
}

fun sessionCameraFacingLabel(facing: SessionCameraFacing): String {
    return when (facing) {
        SessionCameraFacing.REAR -> "Rear"
        SessionCameraFacing.FRONT -> "Front"
    }
}

private fun JSONObject.readOptionalLong(key: String): Long? {
    if (!has(key) || isNull(key)) {
        return null
    }
    val value = optLong(key, Long.MIN_VALUE)
    return value.takeIf { it != Long.MIN_VALUE }
}

private fun JSONObject.readOptionalString(key: String): String? {
    if (!has(key) || isNull(key)) {
        return null
    }
    return optString(key, "").ifBlank { null }
}
