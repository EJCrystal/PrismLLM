package dev.soven.prismllm.proxy

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@Tag("unit")
class ReasoningCarryCacheTest {
    @Test
    fun `payloads are keyed by a sixteen character hash and expire when unused`() {
        var now = 0L
        val cache = ReasoningCarryCache(keepMillis = 1_000, clock = { now })

        val key = cache.keep("opaque")
        assertEquals(16, key.length)
        assertEquals("opaque", cache.recall(key))

        now = 1_001
        assertNull(cache.recall(key))
    }

    @Test
    fun `overflow evicts the least recently seen entry`() {
        var now = 0L
        val cache = ReasoningCarryCache(maxEntries = 2, keepMillis = 1_000_000, clock = { now })

        val first = cache.keep("a")
        now = 1_000
        val second = cache.keep("b")
        now = 2_000
        assertEquals("a", cache.recall(first))
        now = 61_001
        cache.keep("c")

        assertNull(cache.recall(second))
        assertEquals("a", cache.recall(first))
    }

    @Test
    fun `capacity is enforced even within the pruning interval`() {
        var now = 0L
        val cache = ReasoningCarryCache(maxEntries = 2, keepMillis = 1_000_000, clock = { now })

        val first = cache.keep("a")
        now = 1_000
        val second = cache.keep("b")
        now = 2_000
        val third = cache.keep("c")

        assertNull(cache.recall(first))
        assertEquals("b", cache.recall(second))
        assertEquals("c", cache.recall(third))
    }

    @Test
    fun `scoped views only recall what their own channel kept`() {
        val cache = ReasoningCarryCache()
        val first = cache.scoped("channel-a")
        val second = cache.scoped("channel-b")

        val key = first.keep("opaque")!!
        assertEquals("opaque", first.recall(key))
        assertNull(second.recall(key))
        assertNull(cache.recall(key))

        val other = second.keep("opaque")!!
        assertNotEquals(key, other)
        assertEquals("opaque", second.recall(other))
    }
}
