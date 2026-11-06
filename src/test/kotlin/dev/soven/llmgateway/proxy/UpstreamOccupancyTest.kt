package dev.soven.llmgateway.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpstreamOccupancyTest {
    @Test
    fun `snapshot aggregates in-flight attempts by channel and key`() = runBlocking {
        val occupancy = UpstreamOccupancy()
        val first = occupancy.begin("channel-a", "key-1")
        val second = occupancy.begin("channel-a", "key-1")
        val other = occupancy.begin("channel-b", "key-2")

        val snapshot = occupancy.snapshot()
        assertEquals(2, snapshot[UpstreamOccupancy.Slot("channel-a", "key-1")])
        assertEquals(1, snapshot[UpstreamOccupancy.Slot("channel-b", "key-2")])

        occupancy.end(first)
        occupancy.end(second)
        occupancy.end(other)
        assertTrue(occupancy.snapshot().isEmpty())
    }

    @Test
    fun `track releases occupancy after success failure and cancellation`() = runBlocking {
        val occupancy = UpstreamOccupancy()

        assertEquals("ok", occupancy.track("channel-a", "key-1") { "ok" })
        assertTrue(occupancy.snapshot().isEmpty())

        runCatching {
            occupancy.track<Unit>("channel-a", "key-1") { error("boom") }
        }
        assertTrue(occupancy.snapshot().isEmpty())

        val started = CompletableDeferred<Unit>()
        val job = launch {
            occupancy.track<Unit>("channel-a", "key-1") {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertEquals(1, occupancy.snapshot()[UpstreamOccupancy.Slot("channel-a", "key-1")])
        job.cancelAndJoin()
        assertTrue(occupancy.snapshot().isEmpty())
    }
}
