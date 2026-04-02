package com.paul.sprintsync.core.models

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

data class LastRunResult(
    val startedSensorNanos: Long,
    val stoppedSensorNanos: Long,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("startedSensorNanos", startedSensorNanos)
            .put("stoppedSensorNanos", stoppedSensorNanos)
            .toString()
    }

    companion object {
        fun fromJsonString(raw: String): LastRunResult? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (!decoded.has("startedSensorNanos") || !decoded.has("stoppedSensorNanos")) {
                return null
            }
            val startedSensorNanos = decoded.optLong("startedSensorNanos", Long.MIN_VALUE)
            val stoppedSensorNanos = decoded.optLong("stoppedSensorNanos", Long.MIN_VALUE)
            if (startedSensorNanos == Long.MIN_VALUE || stoppedSensorNanos == Long.MIN_VALUE) {
                return null
            }
            return LastRunResult(
                startedSensorNanos = startedSensorNanos,
                stoppedSensorNanos = stoppedSensorNanos,
            )
        }
    }
}

data class SavedRunResult(
    val id: String,
    val name: String,
    val durationNanos: Long,
    val savedAtMillis: Long,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("durationNanos", durationNanos)
            .put("savedAtMillis", savedAtMillis)
    }

    companion object {
        fun fromJsonObject(raw: JSONObject): SavedRunResult? {
            val id = raw.optString("id", "").trim()
            val name = raw.optString("name", "").trim()
            val durationNanos = raw.optLong("durationNanos", Long.MIN_VALUE)
            val savedAtMillis = raw.optLong("savedAtMillis", Long.MIN_VALUE)
            if (
                id.isEmpty() ||
                name.isEmpty() ||
                durationNanos == Long.MIN_VALUE ||
                savedAtMillis == Long.MIN_VALUE ||
                durationNanos <= 0L
            ) {
                return null
            }
            return SavedRunResult(
                id = id,
                name = name,
                durationNanos = durationNanos,
                savedAtMillis = savedAtMillis,
            )
        }

        fun listToJsonString(results: List<SavedRunResult>): String {
            val encoded = JSONArray()
            results.forEach { result -> encoded.put(result.toJsonObject()) }
            return encoded.toString()
        }

        fun listFromJsonString(raw: String): List<SavedRunResult> {
            val decoded = try {
                JSONArray(raw)
            } catch (_: JSONException) {
                return emptyList()
            }
            val items = mutableListOf<SavedRunResult>()
            for (index in 0 until decoded.length()) {
                val entry = decoded.optJSONObject(index) ?: continue
                val parsed = fromJsonObject(entry) ?: continue
                items += parsed
            }
            return items
        }
    }
}
