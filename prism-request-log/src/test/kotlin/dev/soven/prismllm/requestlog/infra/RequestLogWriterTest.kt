package dev.soven.prismllm.requestlog.infra

import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RequestLogWriterTest {
    @Test
    fun `persists the supplied price snapshot without recalculation`() = runBlocking {
        val records = mutableListOf<RequestLogRecord>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = RequestLogWriter(scope = scope, insertRecord = { records += it })
            val priced = requestRecord("priced").copy(
                costAmount = BigDecimal("0.00000200"),
                costCurrency = "USD",
                inputPricePerMillion = BigDecimal.ONE,
                outputPricePerMillion = BigDecimal.ONE,
            )
            writer.submit(priced)
            writer.closeAndDrain(5_000)

            assertSame(priced, records.single())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `preserves an unknown price without substituting zero`() = runBlocking {
        val records = mutableListOf<RequestLogRecord>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = RequestLogWriter(scope = scope, insertRecord = { records += it })
            val unpriced = requestRecord("unknown")
            writer.submit(unpriced)
            writer.closeAndDrain(5_000)

            assertSame(unpriced, records.single())
            assertEquals(null, records.single().costAmount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `counts failed writes and continues with the next record`() = runBlocking {
        val failures = AtomicInteger()
        val records = mutableListOf<RequestLogRecord>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = RequestLogWriter(
                scope = scope,
                insertRecord = {
                    if (it.requestId == "first") error("database unavailable")
                    records += it
                },
                onWriteFailure = { failures.incrementAndGet() },
            )
            writer.submit(requestRecord("first"))
            writer.submit(requestRecord("second"))
            writer.closeAndDrain(5_000)

            assertEquals(1, failures.get())
            assertEquals("second", records.single().requestId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `counts records dropped by a full queue and after close`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dropped = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer = RequestLogWriter(
                scope = scope,
                insertRecord = {
                    entered.complete(Unit)
                    release.await()
                },
                onDropped = { dropped.incrementAndGet() },
            )
            writer.submit(requestRecord("in-flight"))
            entered.await()
            repeat(1024) { writer.submit(requestRecord("queued-$it")) }
            writer.submit(requestRecord("overflow"))
            assertEquals(1, dropped.get())

            release.complete(Unit)
            writer.closeAndDrain(5_000)
            writer.submit(requestRecord("closed"))
            assertEquals(2, dropped.get())
        } finally {
            scope.cancel()
        }
    }

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
            )
            writer.submit(requestRecord("first"))
            writer.submit(requestRecord("second"))
            writer.closeAndDrain(5_000)

            assertEquals(1, insertCalls.get())
        } finally {
            scope.cancel()
        }
    }

    private fun requestRecord(requestId: String) = RequestLogRecord(
        requestId = requestId,
        clientKeyId = null,
        clientKeyName = null,
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
