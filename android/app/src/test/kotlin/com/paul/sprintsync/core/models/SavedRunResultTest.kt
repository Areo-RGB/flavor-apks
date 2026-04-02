package com.paul.sprintsync.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedRunResultTest {
    @Test
    fun `saved run result list round-trips`() {
        val original = listOf(
            SavedRunResult(id = "1", name = "Alice", durationNanos = 1_200_000_000L, savedAtMillis = 100L),
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
}
