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
    SPLIT,
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
    val hostSplitSensorNanos: List<Long>,
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
        val splitArray = JSONArray()
        hostSplitSensorNanos.forEach { splitArray.put(it) }
        val timeline = JSONObject()
            .put("hostStartSensorNanos", hostStartSensorNanos)
            .put("hostSplitSensorNanos", splitArray)
            .put("hostStopSensorNanos", hostStopSensorNanos)
        return JSONObject()
            .put("type", TYPE)
            .put("stage", stage.name.lowercase())
            .put("monitoringActive", monitoringActive)
            .put("devices", devicesArray)
            .put("timeline", timeline)
            .put("runId", runId)
            .put("hostSensorMinusElapsedNanos", hostSensorMinusElapsedNanos)
            .put("hostGpsUtcOffsetNanos", hostGpsUtcOffsetNanos)
            .put("hostGpsFixAgeNanos", hostGpsFixAgeNanos)
            .put("selfDeviceId", selfDeviceId)
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
            val splitRaw = timeline.optJSONArray("hostSplitSensorNanos") ?: JSONArray()
            val splits = mutableListOf<Long>()
            for (index in 0 until splitRaw.length()) {
                val value = splitRaw.optLong(index, Long.MIN_VALUE)
                if (value != Long.MIN_VALUE) {
                    splits += value
                }
            }
            return SessionSnapshotMessage(
                stage = stage,
                monitoringActive = decoded.optBoolean("monitoringActive", false),
                devices = parsedDevices,
                hostStartSensorNanos = timeline.readOptionalLong("hostStartSensorNanos"),
                hostSplitSensorNanos = splits,
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
            .put("mappedHostSensorNanos", mappedHostSensorNanos)
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
    val splitIndex: Int,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("runId", runId)
            .put("role", role.name.lowercase())
            .put("provisionalHostSensorNanos", provisionalHostSensorNanos)
            .put("refinedHostSensorNanos", refinedHostSensorNanos)
            .put("splitIndex", splitIndex)
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
            val splitIndex = decoded.optInt("splitIndex", -1)
            if (runId.isEmpty() || role == null || provisional == Long.MIN_VALUE || refined == Long.MIN_VALUE || splitIndex < 0) {
                return null
            }
            return SessionTriggerRefinementMessage(
                runId = runId,
                role = role,
                provisionalHostSensorNanos = provisional,
                refinedHostSensorNanos = refined,
                splitIndex = splitIndex,
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
    val hostSplitSensorNanos: List<Long>,
    val hostStopSensorNanos: Long?,
    val sentElapsedNanos: Long,
) {
    fun toJsonString(): String {
        val splits = JSONArray()
        hostSplitSensorNanos.forEach { splits.put(it) }
        return JSONObject()
            .put("type", TYPE)
            .put("hostStartSensorNanos", hostStartSensorNanos)
            .put("hostSplitSensorNanos", splits)
            .put("hostStopSensorNanos", hostStopSensorNanos)
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
            val splits = decoded.optJSONArray("hostSplitSensorNanos") ?: JSONArray()
            val parsedSplits = mutableListOf<Long>()
            for (index in 0 until splits.length()) {
                val value = splits.optLong(index, Long.MIN_VALUE)
                if (value != Long.MIN_VALUE) {
                    parsedSplits += value
                }
            }
            return SessionTimelineSnapshotMessage(
                hostStartSensorNanos = hostStartSensorNanos,
                hostSplitSensorNanos = parsedSplits,
                hostStopSensorNanos = hostStopSensorNanos,
                sentElapsedNanos = sentElapsedNanos,
            )
        }
    }
}

data class SessionTriggerMessage(
    val triggerType: String,
    val splitIndex: Int,
    val triggerSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("triggerType", triggerType)
            .put("splitIndex", splitIndex)
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
            val splitIndex = decoded.optInt("splitIndex", -1)
            val triggerSensorNanos = decoded.optLong("triggerSensorNanos", Long.MIN_VALUE)
            if (triggerType.isEmpty() || splitIndex < 0 || triggerSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return SessionTriggerMessage(
                triggerType = triggerType,
                splitIndex = splitIndex,
                triggerSensorNanos = triggerSensorNanos,
            )
        }
    }
}

data class SessionChirpCalibrationStartMessage(
    val calibrationId: String,
    val role: String,
    val profile: String,
    val sampleCount: Int,
    val remoteSendElapsedNanos: Long?,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("calibrationId", calibrationId)
            .put("role", role)
            .put("profile", profile)
            .put("sampleCount", sampleCount)
            .put("remoteSendElapsedNanos", remoteSendElapsedNanos)
            .toString()
    }

    companion object {
        const val TYPE = "chirp_calibration_start"

        fun tryParse(raw: String): SessionChirpCalibrationStartMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val calibrationId = decoded.optString("calibrationId", "").trim()
            val role = decoded.optString("role", "").trim()
            val profile = decoded.optString("profile", "").trim()
            val sampleCount = decoded.optInt("sampleCount", 3)
            if (calibrationId.isEmpty() || role.isEmpty() || profile.isEmpty()) {
                return null
            }
            val remoteSendElapsedNanos = decoded.readOptionalLong("remoteSendElapsedNanos")
            return SessionChirpCalibrationStartMessage(
                calibrationId = calibrationId,
                role = role,
                profile = profile,
                sampleCount = sampleCount.coerceAtLeast(3),
                remoteSendElapsedNanos = remoteSendElapsedNanos,
            )
        }
    }
}

data class SessionChirpCalibrationResultMessage(
    val calibrationId: String,
    val accepted: Boolean,
    val hostMinusClientElapsedNanos: Long?,
    val jitterNanos: Long?,
    val reason: String?,
    val completedAtElapsedNanos: Long?,
    val profile: String,
    val sampleCount: Int,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("calibrationId", calibrationId)
            .put("accepted", accepted)
            .put("hostMinusClientElapsedNanos", hostMinusClientElapsedNanos)
            .put("jitterNanos", jitterNanos)
            .put("reason", reason)
            .put("completedAtElapsedNanos", completedAtElapsedNanos)
            .put("profile", profile)
            .put("sampleCount", sampleCount)
            .toString()
    }

    companion object {
        const val TYPE = "chirp_calibration_result"

        fun tryParse(raw: String): SessionChirpCalibrationResultMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val calibrationId = decoded.optString("calibrationId", "").trim()
            val accepted = decoded.optBoolean("accepted", false)
            val profile = decoded.optString("profile", "").trim()
            val sampleCount = decoded.optInt("sampleCount", 0)
            if (calibrationId.isEmpty() || profile.isEmpty() || sampleCount <= 0) {
                return null
            }
            val hostMinusClientElapsedNanos = decoded.readOptionalLong("hostMinusClientElapsedNanos")
            val jitterNanos = decoded.readOptionalLong("jitterNanos")
            val completedAtElapsedNanos = decoded.readOptionalLong("completedAtElapsedNanos")
            val reason = decoded.optString("reason", "").ifBlank { null }
            return SessionChirpCalibrationResultMessage(
                calibrationId = calibrationId,
                accepted = accepted,
                hostMinusClientElapsedNanos = hostMinusClientElapsedNanos,
                jitterNanos = jitterNanos,
                reason = reason,
                completedAtElapsedNanos = completedAtElapsedNanos,
                profile = profile,
                sampleCount = sampleCount,
            )
        }
    }
}

data class SessionChirpClearMessage(
    val calibrationId: String?,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("type", TYPE)
            .put("calibrationId", calibrationId)
            .toString()
    }

    companion object {
        const val TYPE = "chirp_clear"

        fun tryParse(raw: String): SessionChirpClearMessage? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (decoded.optString("type") != TYPE) {
                return null
            }
            val calibrationId = decoded.optString("calibrationId", "").ifBlank { null }
            return SessionChirpClearMessage(calibrationId = calibrationId)
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
        SessionDeviceRole.SPLIT -> "Split"
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
