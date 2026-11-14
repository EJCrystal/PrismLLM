package dev.soven.prismllm.proxy

import dev.soven.prismllm.pricing.domain.ModelPriceSource
import dev.soven.prismllm.pricing.domain.ModelPriceTier
import dev.soven.prismllm.pricing.domain.PricingMode
import dev.soven.prismllm.pricing.domain.ResolvedModelPrice
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.domain.RequestRateLease
import dev.soven.prismllm.quota.infra.ClientKeyQuotaService
import dev.soven.prismllm.quota.infra.QuotaLease
import dev.soven.prismllm.quota.infra.RequestLimitLease
import dev.soven.prismllm.quota.infra.RequestLimitService
import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import dev.soven.prismllm.shared.domain.FailureStage
import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RequestFinalizerTest {
    @Test
    fun `resolves price once and shares its snapshot with quota and logs`() = runBlocking {
        var currentPrice = price("1")
        var lookups = 0
        var charged: BigDecimal? = null
        val settled = CompletableDeferred<Unit>()
        val continueSettlement = CompletableDeferred<Unit>()
        val logs = mutableListOf<RequestLogRecord>()
        val finalizer = RequestFinalizer(
            resolvePrice = { lookups++; currentPrice },
            settleUsage = { _, _, cost ->
                charged = cost
                settled.complete(Unit)
                continueSettlement.await()
            },
            recordMetrics = {},
            submitRecord = logs::add,
            onFailure = { _, _, cause -> throw cause },
        )
        val finishing = async { finalizer.finish(record(), lease()) }
        settled.await()
        currentPrice = price("99")
        continueSettlement.complete(Unit)
        finishing.await()

        assertEquals(1, lookups)
        assertEquals(BigDecimal("2.00000000"), charged)
        assertEquals(charged, logs.single().costAmount)
        assertEquals(BigDecimal("1"), logs.single().inputPricePerMillion)
        assertEquals("USD", logs.single().costCurrency)
    }

    @Test
    fun `keeps absent pricing unknown without a second lookup`() = runBlocking {
        var lookups = 0
        var charged: BigDecimal? = null
        val logs = mutableListOf<RequestLogRecord>()
        RequestFinalizer(
            resolvePrice = { lookups++; null },
            settleUsage = { _, _, cost -> charged = cost },
            recordMetrics = {},
            submitRecord = logs::add,
            onFailure = { _, _, cause -> throw cause },
        ).finish(record(), lease())

        assertEquals(1, lookups)
        assertEquals(BigDecimal.ZERO, charged)
        assertNull(logs.single().costAmount)
        assertNull(logs.single().costCurrency)
    }

    @ParameterizedTest(name = "pricingMode={0}")
    @EnumSource(PricingMode::class)
    fun `non USD price cannot enter quota settlement in any pricing mode`(mode: PricingMode) = runBlocking {
        val unsupportedPrice = price("1").copy(
            currency = "EUR",
            pricingMode = mode,
            perRequestPrice = BigDecimal.ONE,
            priceTiers = listOf(ModelPriceTier(null, BigDecimal.ONE, BigDecimal.ONE)),
        )
        var lookups = 0
        var settlements = 0
        var tokens = 0L
        var charged: BigDecimal? = null
        val failures = mutableListOf<String>()
        val logs = mutableListOf<RequestLogRecord>()
        val measured = mutableListOf<RequestLogRecord>()
        RequestFinalizer(
            resolvePrice = { lookups++; unsupportedPrice },
            settleUsage = { _, value, cost -> settlements++; tokens = value; charged = cost },
            recordMetrics = measured::add,
            submitRecord = logs::add,
            onFailure = { _, stage, _ -> failures += stage },
        ).finish(record(), lease())

        assertEquals(1, lookups)
        assertEquals(1, settlements)
        assertEquals(2_000_000L, tokens)
        assertEquals(BigDecimal.ZERO, charged)
        assertEquals(emptyList(), failures)
        assertEquals(200, logs.single().statusCode)
        assertNull(logs.single().errorCode)
        assertNull(logs.single().costAmount)
        assertNull(logs.single().costCurrency)
        assertNull(logs.single().inputPricePerMillion)
        assertNull(logs.single().perRequestPrice)
        assertEquals(emptyList(), logs.single().priceTiers)
        assertEquals(measured.single(), logs.single())
    }

    @Test
    fun `pricing failure still settles tokens and preserves the original request error`() = runBlocking {
        val failure = IllegalStateException("price lookup failed")
        val failures = mutableListOf<Pair<String, Exception>>()
        val logs = mutableListOf<RequestLogRecord>()
        val measured = mutableListOf<RequestLogRecord>()
        var tokens = 0L
        val failedRecord = record().copy(statusCode = 502, errorCode = "upstream_error", failureStage = FailureStage.UPSTREAM)
        RequestFinalizer(
            resolvePrice = { throw failure },
            settleUsage = { _, value, _ -> tokens = value },
            recordMetrics = measured::add,
            submitRecord = logs::add,
            onFailure = { _, stage, cause -> failures += stage to cause },
        ).finish(failedRecord, lease())

        assertEquals(2_000_000L, tokens)
        assertEquals("pricing", failures.single().first)
        assertSame(failure, failures.single().second)
        assertEquals("upstream_error", logs.single().errorCode)
        assertEquals(measured.single(), logs.single())
    }

    @Test
    fun `quota failure still records metrics and the calculated log`() = runBlocking {
        val failures = mutableListOf<String>()
        val logs = mutableListOf<RequestLogRecord>()
        val measured = mutableListOf<RequestLogRecord>()
        RequestFinalizer(
            resolvePrice = { price("1") },
            settleUsage = { _, _, _ -> error("database unavailable") },
            recordMetrics = measured::add,
            submitRecord = logs::add,
            onFailure = { _, stage, _ -> failures += stage },
        ).finish(record(), lease(), elapsedMs = { 42 })

        assertEquals(listOf("quota"), failures)
        assertEquals(200, logs.single().statusCode)
        assertNull(logs.single().errorCode)
        assertEquals(BigDecimal("2.00000000"), logs.single().costAmount)
        assertEquals(42L, logs.single().totalMs)
        assertEquals(measured.single(), logs.single())
    }

    @Test
    fun `failed settlement releases only its own concurrency lease`() = runBlocking {
        val quota = ClientKeyQuotaService(
            usageReader = { _, _ -> ClientKeyQuotaUsage() },
            usageWriter = { _, _, _, _ -> error("database unavailable") },
        )
        val limits = RequestLimitService(quota)
        val key = ClientKeyRecord(
            id = "client", name = "client", keyHash = "hash", keyPrefix = "prefix",
            enabled = true, expiresAt = null, createdAt = 0,
            quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 2),
        )
        val first = limits.acquire(key)
        val second = limits.acquire(key)
        RequestFinalizer(
            resolvePrice = { price("1") },
            settleUsage = limits::settle,
            recordMetrics = {},
            submitRecord = {},
            onFailure = { _, _, _ -> },
        ).finish(record(), first)

        assertEquals(1, quota.currentUsage(key.id).concurrentRequests)
        limits.release(second)
        assertEquals(0, quota.currentUsage(key.id).concurrentRequests)
    }

    @Test
    fun `cancelled caller still settles and records the request once`() = runBlocking {
        var settled = 0
        var measured = 0
        var submitted = 0
        val finalizer = RequestFinalizer(
            resolvePrice = { price("1") },
            settleUsage = { _, _, _ -> settled++ },
            recordMetrics = { measured++ },
            submitRecord = { submitted++ },
            onFailure = { _, _, cause -> throw cause },
        )
        launch {
            currentCoroutineContext().cancel()
            finalizer.finish(record(), lease())
        }.join()

        assertEquals(1, settled)
        assertEquals(1, measured)
        assertEquals(1, submitted)
    }

    @Test
    fun `metric failure cannot prevent log submission`() = runBlocking {
        val failures = mutableListOf<String>()
        val logs = mutableListOf<RequestLogRecord>()
        RequestFinalizer(
            resolvePrice = { null },
            settleUsage = { _, _, _ -> },
            recordMetrics = { error("metrics unavailable") },
            submitRecord = logs::add,
            onFailure = { _, stage, _ -> failures += stage },
        ).finish(record(), lease())

        assertEquals(listOf("metrics"), failures)
        assertEquals(1, logs.size)
    }

    @Test
    fun `request without a lease still receives a price snapshot`() = runBlocking {
        var settled = 0
        val logs = mutableListOf<RequestLogRecord>()
        RequestFinalizer(
            resolvePrice = { price("1") },
            settleUsage = { _, _, _ -> settled++ },
            recordMetrics = {},
            submitRecord = logs::add,
            onFailure = { _, _, cause -> throw cause },
        ).finish(record(), null)

        assertEquals(0, settled)
        assertEquals(BigDecimal("2.00000000"), logs.single().costAmount)
    }

    @Test
    fun `failing diagnostics cannot skip settlement or replace the original request error`() = runBlocking {
        val stages = mutableListOf<String>()
        val logs = mutableListOf<RequestLogRecord>()
        var settlements = 0
        var tokens = 0L
        val failedRecord = record().copy(statusCode = 502, errorCode = "upstream_error", failureStage = FailureStage.UPSTREAM)
        RequestFinalizer(
            resolvePrice = { error("pricing unavailable") },
            settleUsage = { _, value, _ -> settlements++; tokens = value; error("quota unavailable") },
            recordMetrics = { error("metrics unavailable") },
            submitRecord = logs::add,
            onFailure = { _, stage, _ -> stages += stage; error("diagnostics unavailable") },
        ).finish(failedRecord, lease())

        assertEquals(1, settlements)
        assertEquals(2_000_000L, tokens)
        assertEquals(listOf("pricing", "quota", "metrics"), stages)
        assertEquals("upstream_error", logs.single().errorCode)
        assertEquals(502, logs.single().statusCode)
    }

    @Test
    fun `log submission and diagnostics failures do not escape finalization`() = runBlocking {
        val stages = mutableListOf<String>()
        RequestFinalizer(
            resolvePrice = { null },
            settleUsage = { _, _, _ -> },
            recordMetrics = {},
            submitRecord = { error("log queue unavailable") },
            onFailure = { _, stage, _ -> stages += stage; error("diagnostics unavailable") },
        ).finish(record(), lease())

        assertEquals(listOf("request_log"), stages)
    }

    private fun price(rate: String) = ResolvedModelPrice(
        source = ModelPriceSource.MODEL_DEFAULT,
        modelId = "model",
        currency = "USD",
        inputPricePerMillion = BigDecimal(rate),
        outputPricePerMillion = BigDecimal(rate),
        cachedInputPricePerMillion = null,
    )

    private fun lease() = RequestLimitLease(
        rateLease = RequestRateLease("client", 0),
        quotaLease = QuotaLease("client", 0),
    )

    private fun record() = RequestLogRecord(
        requestId = "request",
        clientKeyId = "client",
        clientKeyName = "client",
        alias = "model",
        channelId = "channel",
        channelName = "channel",
        upstreamModel = "upstream-model",
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 1,
        inputTokens = 1_000_000,
        outputTokens = 1_000_000,
        cachedTokens = null,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = 2_000_000,
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
