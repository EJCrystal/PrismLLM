package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class RequestLogWriterTest {
    @Test
    fun `propagates cancellation from request log insert`() = runBlocking {
        val insertCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = RequestLogWriter(
                scope = scope,
                insertRecord = {
                    if (insertCalls.incrementAndGet() == 1) throw CancellationException("stop writer")
                },
                findPrice = { _, _ -> null },
            )
            writer.submit(requestRecord("first"))
            writer.submit(requestRecord("second"))
            writer.closeAndDrain(5_000)

            assertEquals(1, insertCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `propagates cancellation from price lookup`() = runBlocking {
        val lookupCalls = AtomicInteger()
        val insertCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = RequestLogWriter(
                scope = scope,
                insertRecord = { insertCalls.incrementAndGet() },
                findPrice = { _, _ ->
                    lookupCalls.incrementAndGet()
                    throw CancellationException("stop writer")
                },
            )
            writer.submit(requestRecord("first"))
            writer.submit(requestRecord("second"))
            writer.closeAndDrain(5_000)

            assertEquals(1, lookupCalls.get())
            assertEquals(0, insertCalls.get())
        } finally {
            scope.cancel()
        }
    }

    private fun requestRecord(requestId: String) = RequestLogRecord(
        requestId = requestId,
        clientKeyId = null,
        clientKeyName = null,
        team = null,
        alias = "alias",
        channelId = "channel",
        channelName = "channel",
        upstreamModel = "model",
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 1,
        inputTokens = 1,
        outputTokens = 1,
        cachedTokens = null,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = 2,
        usageKnown = true,
        costAmount = null,
        costCurrency = null,
        inputPricePerMillion = null,
        outputPricePerMillion = null,
        cachedInputPricePerMillion = null,
        errorCode = null,
        failureStage = null,
        errorMessage = null,
        upstreamErrorCode = null,
        createdAt = 1,
    )
}
