package com.paul.sprintsync.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
class SavedRunResultTest {
    @Test
    fun `saved run result list round-trips`() {
        val original = listOf(
            SavedRunResult(
                id = "1",
                name = "Alice",
                durationNanos = 1_200_000_000L,
                savedAtMillis = 100L,
                checkpointResults = listOf(
                    SavedRunCheckpointResult(
                        checkpointLabel = "Split 1",
                        distanceMeters = 5.0,
                        totalTimeSec = 1.12,
                        splitTimeSec = 1.12,
                        avgSpeedKmh = 16.0,
                        accelerationMs2 = 4.0,
                    ),
                ),
            ),
            SavedRunResult(id = "2", name = "Bob", durationNanos = 1_150_000_000L, savedAtMillis = 200L),
        )

        val parsed = SavedRunResult.listFromJsonString(SavedRunResult.listToJsonString(original))

        assertEquals(original, parsed)
    }

    @Test
    fun `saved run result list parser returns empty for malformed json`() {
        val parsed = SavedRunResult.listFromJsonString("{bad-json")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `saved run result parser remains compatible with legacy entries without checkpoints`() {
        val legacy = JSONArray()
            .put(
                org.json.JSONObject()
                    .put("id", "1")
                    .put("name", "Legacy")
                    .put("durationNanos", 1_000L)
                    .put("savedAtMillis", 123L),
            )
            .toString()

        val parsed = SavedRunResult.listFromJsonString(legacy)

        assertEquals(1, parsed.size)
        assertEquals("Legacy", parsed.first().name)
        assertTrue(parsed.first().checkpointResults.isEmpty())
    }
}
