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
    val checkpointResults: List<SavedRunCheckpointResult> = emptyList(),
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("durationNanos", durationNanos)
            .put("savedAtMillis", savedAtMillis)
            .put("checkpointResults", checkpointResults.toJsonArray())
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
                checkpointResults = raw.readCheckpointResults("checkpointResults"),
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

data class SavedRunCheckpointResult(
    val checkpointLabel: String,
    val distanceMeters: Double,
    val totalTimeSec: Double,
    val splitTimeSec: Double,
    val avgSpeedKmh: Double,
    val accelerationMs2: Double,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("checkpointLabel", checkpointLabel)
            .put("distanceMeters", distanceMeters)
            .put("totalTimeSec", totalTimeSec)
            .put("splitTimeSec", splitTimeSec)
            .put("avgSpeedKmh", avgSpeedKmh)
            .put("accelerationMs2", accelerationMs2)
    }

    companion object {
        fun fromJsonObject(raw: JSONObject): SavedRunCheckpointResult? {
            val checkpointLabel = raw.optString("checkpointLabel", "").trim()
            val distanceMeters = raw.optDouble("distanceMeters", Double.NaN)
            val totalTimeSec = raw.optDouble("totalTimeSec", Double.NaN)
            val splitTimeSec = raw.optDouble("splitTimeSec", Double.NaN)
            val avgSpeedKmh = raw.optDouble("avgSpeedKmh", Double.NaN)
            val accelerationMs2 = raw.optDouble("accelerationMs2", Double.NaN)
            if (
                checkpointLabel.isEmpty() ||
                !distanceMeters.isFinite() ||
                !totalTimeSec.isFinite() ||
                !splitTimeSec.isFinite() ||
                !avgSpeedKmh.isFinite() ||
                !accelerationMs2.isFinite()
            ) {
                return null
            }
            return SavedRunCheckpointResult(
                checkpointLabel = checkpointLabel,
                distanceMeters = distanceMeters,
                totalTimeSec = totalTimeSec,
                splitTimeSec = splitTimeSec,
                avgSpeedKmh = avgSpeedKmh,
                accelerationMs2 = accelerationMs2,
            )
        }
    }
}

private fun List<SavedRunCheckpointResult>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { array.put(it.toJsonObject()) }
    return array
}

private fun JSONObject.readCheckpointResults(key: String): List<SavedRunCheckpointResult> {
    if (!has(key) || isNull(key)) {
        return emptyList()
    }
    val array = optJSONArray(key) ?: return emptyList()
    val results = mutableListOf<SavedRunCheckpointResult>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        SavedRunCheckpointResult.fromJsonObject(item)?.let { results += it }
    }
    return results
}
