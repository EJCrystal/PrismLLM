package dev.soven.prismllm.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("unit")
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

    @Test
    fun `concurrent begin and end keep counts consistent and release empty slots`() = runBlocking {
        val occupancy = UpstreamOccupancy()
        val slots = (1..500).map {
            async(Dispatchers.Default) { occupancy.begin("channel-a", "key-${it % 5}") }
        }.awaitAll()

        assertEquals(500, occupancy.snapshot().values.sum())

        slots.forEach { occupancy.end(it) }
        assertTrue(occupancy.snapshot().isEmpty())
    }

    @Test
    fun `snapshot tolerates slots being added and removed concurrently`() = runBlocking {
        val occupancy = UpstreamOccupancy()
        // 反复让映射在 0/1 个槽位之间切换，覆盖 toMap() 单元素分支的竞态窗口。
        val churn = launch(Dispatchers.Default) {
            repeat(20_000) {
                val slot = occupancy.begin("channel-a", "key-1")
                occupancy.end(slot)
            }
        }
        repeat(20_000) {
            occupancy.snapshot()
        }
        churn.join()
        assertTrue(occupancy.snapshot().isEmpty())
    }
}
