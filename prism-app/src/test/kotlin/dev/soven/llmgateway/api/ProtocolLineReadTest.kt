package dev.soven.llmgateway.api

import dev.soven.llmgateway.shared.error.GatewayError

import dev.soven.llmgateway.protocol.MAX_PROTOCOL_EVENT_BYTES
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ProtocolLineReadTest {
    @Test
    fun boundedReadsClassifyOversizedLinesWithoutLeakingTheirContents() = runBlocking {
        val data = "data: private-upstream-payload-" + "x".repeat(MAX_PROTOCOL_EVENT_BYTES)
        val error = assertFailsWith<GatewayError> {
            readProtocolLine(ByteReadChannel(data + "\n"), MAX_PROTOCOL_EVENT_BYTES + 6)
        }
        assertEquals(HttpStatusCode.BadGateway, error.status)
        assertEquals("upstream_event_too_large", error.code)
        assertEquals("upstream_error", error.type)
        assertFalse(error.retryable)
        assertFalse(error.message.contains("private-upstream-payload"))
    }

    @Test
    fun unlimitedNativeReadsStillAcceptLinesLargerThanProtocolLimit() = runBlocking {
        val data = "data: " + "x".repeat(MAX_PROTOCOL_EVENT_BYTES + 32)
        val upstream = ByteReadChannel(data + "\n")
        assertEquals(data, readProtocolLine(upstream, Int.MAX_VALUE))
        assertNull(readProtocolLine(upstream, Int.MAX_VALUE))
    }
}
