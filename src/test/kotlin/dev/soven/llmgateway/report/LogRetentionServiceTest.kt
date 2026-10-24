package dev.soven.llmgateway.report

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class LogRetentionServiceTest {
    @Test
    fun `cleans in batches until a short page`() = runBlocking {
        val cutoff = 1_000L
        val pages = ArrayDeque(listOf(
            CleanupResult(100, cutoff),
            CleanupResult(100, cutoff),
            CleanupResult(25, cutoff),
        ))
        val calls = AtomicInteger()
        val service = LogRetentionService(
            deleteBatch = { requestedCutoff, size ->
                assertEquals(cutoff, requestedCutoff)
                assertEquals(100, size)
                calls.incrementAndGet()
                pages.removeFirst()
            },
            policy = RetentionPolicy(batchSize = 100),
            now = { cutoff + 30 * 86_400_000L },
        )

        val result = service.cleanExpired()

        assertEquals(225, result.deleted)
        assertEquals(3, calls.get())
    }

    @Test
    fun `stops immediately when nothing is expired`() = runBlocking {
        val calls = AtomicInteger()
        val service = LogRetentionService(
            deleteBatch = { _, _ ->
                calls.incrementAndGet()
                CleanupResult(0, 1)
            },
            policy = RetentionPolicy(batchSize = 100),
            now = { 1L },
        )

        assertEquals(0, service.cleanExpired().deleted)
        assertEquals(1, calls.get())
    }
}
