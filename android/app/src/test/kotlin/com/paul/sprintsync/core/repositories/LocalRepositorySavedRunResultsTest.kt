package com.paul.sprintsync.core.repositories

import android.content.Context
import com.paul.sprintsync.core.models.SavedRunResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalRepositorySavedRunResultsTest {
    @Test
    fun `add and delete saved run results persist through repository`() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val repository = LocalRepository(context)
        repository.saveSavedRunResults(emptyList())

        val first = SavedRunResult(id = "1", name = "Alice", durationNanos = 1_000L, savedAtMillis = 10L)
        val second = SavedRunResult(id = "2", name = "Bob", durationNanos = 2_000L, savedAtMillis = 20L)
        repository.addSavedRunResult(first)
        repository.addSavedRunResult(second)

        val afterAdd = repository.loadSavedRunResults()
        assertEquals(listOf(first, second), afterAdd)

        repository.deleteSavedRunResult("1")
        val afterDelete = repository.loadSavedRunResults()
        assertEquals(listOf(second), afterDelete)

        repository.saveSavedRunResults(emptyList())
    }
}
