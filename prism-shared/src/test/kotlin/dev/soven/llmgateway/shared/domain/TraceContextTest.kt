package dev.soven.llmgateway.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraceContextTest {
    private val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
    private val parentId = "00f067aa0ba902b7"

    @Test
    fun `preserves incoming trace id and flags with a new span id`() {
        val context = TraceContextFactory.fromHeaderOrCreate("00-$traceId-$parentId-01")

        assertEquals(traceId, context.traceId)
        assertEquals("01", context.traceFlags)
        assertNotEquals(parentId, context.spanId)
        assertTrue(context.traceparent.matches(Regex("00-$traceId-[0-9a-f]{16}-01")))
    }

    @Test
    fun `creates a complete traceparent when header is absent`() {
        val context = TraceContextFactory.fromHeaderOrCreate(null)
        assertTrue(context.traceparent.matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
    }

    @Test
    fun `rejects malformed and all-zero identifiers`() {
        listOf(
            "bad",
            "00-${"0".repeat(32)}-$parentId-01",
            "00-$traceId-${"0".repeat(16)}-01",
            "01-$traceId-$parentId-01",
        ).forEach { invalid ->
            val context = TraceContextFactory.fromHeaderOrCreate(invalid)
            assertNotEquals(invalid, context.traceparent)
            assertNotEquals("0".repeat(32), context.traceId)
        }
    }
}
