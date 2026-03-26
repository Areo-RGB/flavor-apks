package com.paul.sprintsync.core.models

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class LastRunResult(
    val startedSensorNanos: Long,
    val splitElapsedNanos: List<Long>,
) {
    fun toJsonString(): String {
        val splits = JSONArray()
        splitElapsedNanos.forEach { split ->
            splits.put(split)
        }
        return JSONObject()
            .put("startedSensorNanos", startedSensorNanos)
            .put("splitElapsedNanos", splits)
            .toString()
    }

    companion object {
        fun fromJsonString(raw: String): LastRunResult? {
            val decoded = try {
                JSONObject(raw)
            } catch (_: JSONException) {
                return null
            }
            if (!decoded.has("startedSensorNanos") || !decoded.has("splitElapsedNanos")) {
                return null
            }
            val startedSensorNanos = decoded.optLong("startedSensorNanos", Long.MIN_VALUE)
            if (startedSensorNanos == Long.MIN_VALUE) {
                return null
            }
            val splitsRaw = decoded.optJSONArray("splitElapsedNanos") ?: JSONArray()
            val splitElapsedNanos = mutableListOf<Long>()
            for (index in 0 until splitsRaw.length()) {
                val value = splitsRaw.optLong(index, Long.MIN_VALUE)
                if (value != Long.MIN_VALUE) {
                    splitElapsedNanos.add(value)
                }
            }
            return LastRunResult(
                startedSensorNanos = startedSensorNanos,
                splitElapsedNanos = splitElapsedNanos,
            )
        }
    }
}
