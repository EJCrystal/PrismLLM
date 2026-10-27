package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.DefaultModelPriceRecord
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.ModelPriceTier
import dev.soven.llmgateway.domain.PricingMode
import dev.soven.llmgateway.domain.RequestLogRecord
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 验证计费口径：缓存单独计价、推理不重复计费、缺失用量不估算。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class BillingCalculatorTest {
    @Test
    fun `charges cached tokens at cache price`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 50, cached = 80),
            price(input = "1", output = "2", cached = "0.5"),
        )

        assertEquals(BigDecimal("0.00016000"), cost?.amount)
        assertEquals("USD", cost?.currency)
        assertEquals(BigDecimal("0.5"), cost?.cachedInputPricePerMillion)
    }

    @Test
    fun `falls back to input price when cached price is missing`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 0, cached = 80),
            price(input = "1", output = "2", cached = null),
        )

        assertEquals(BigDecimal("0.00010000"), cost?.amount)
        assertEquals(BigDecimal("1"), cost?.cachedInputPricePerMillion)
    }

    @Test
    fun `clamps cached tokens to input tokens`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 0, cached = 200),
            price(input = "1", output = "2", cached = "0.5"),
        )

        assertEquals(BigDecimal("0.00005000"), cost?.amount)
    }

    @Test
    fun `returns null when usage is incomplete`() {
        assertNull(BillingCalculator.calculate(record(input = null, output = 10), price()))
        assertNull(BillingCalculator.calculate(record(input = 10, output = null), price()))
        assertNull(BillingCalculator.calculate(record(input = 10, output = 10, known = false), price()))
    }

    @Test
    fun `charges with model default price definition`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 50, cached = 20),
            DefaultModelPriceRecord(
                id = "default-price",
                modelId = "model",
                currency = "CNY",
                inputPricePerMillion = BigDecimal("3"),
                outputPricePerMillion = BigDecimal("4"),
                cachedInputPricePerMillion = BigDecimal("1"),
                createdAt = 0,
                updatedAt = 0,
            ),
        )

        assertEquals(BigDecimal("0.00046000"), cost?.amount)
        assertEquals("CNY", cost?.currency)
        assertEquals(BigDecimal("1"), cost?.cachedInputPricePerMillion)
    }

    @Test
    fun `charges with resolved model price and records source`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 50),
            ResolvedModelPrice(
                source = ModelPriceSource.MODEL_DEFAULT,
                modelId = "model",
                defaultPriceId = "default-price",
                priceVersion = 7,
                priceVersionId = "version-7",
                currency = "USD",
                inputPricePerMillion = BigDecimal("1"),
                outputPricePerMillion = BigDecimal("2"),
                cachedInputPricePerMillion = null,
            ),
        )

        assertEquals(BigDecimal("0.00020000"), cost?.amount)
        assertEquals("USD", cost?.currency)
        assertEquals(7, cost?.priceVersion)
        assertEquals("version-7", cost?.priceVersionId)
    }

    @Test
    fun `charges cached write tokens at cache write price`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 10, cached = 20, cachedWrite = 30),
            price(input = "1", output = "2", cached = "0.5", cachedWrite = "1.25"),
        )

        assertEquals(BigDecimal("0.00011750"), cost?.amount)
        assertEquals(BigDecimal("0.5"), cost?.cachedInputPricePerMillion)
        assertEquals(BigDecimal("1.25"), cost?.cachedInputWritePricePerMillion)
    }

    @Test
    fun `falls back to input price when cached write price is missing`() {
        val cost = BillingCalculator.calculate(
            record(input = 100, output = 0, cached = 20, cachedWrite = 30),
            price(input = "1", output = "2", cached = "0.5", cachedWrite = null),
        )

        assertEquals(BigDecimal("0.00009000"), cost?.amount)
        assertEquals(BigDecimal("1"), cost?.cachedInputWritePricePerMillion)
    }

    @Test
    fun `anthropic cache read and write usage is billed exactly once`() {
        val usage = UsageParser.fromAnthropicJson(
            Json.parseToJsonElement(
                """{"type":"message","usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":4}}""",
            ).jsonObject,
        )!!

        val cost = BillingCalculator.calculate(
            record(
                input = usage.input,
                output = usage.output,
                cached = usage.cached,
                cachedWrite = usage.cacheWrite,
            ),
            price(input = "2", output = "0", cached = "0.5", cachedWrite = "1.25"),
        )

        // 10 uncached + 3 cache-read + 2 cache-write tokens: 20 + 1.5 + 2.5 = 24 / million.
        assertEquals(15L, usage.input)
        assertEquals(BigDecimal("0.00002400"), cost?.amount)
    }

    @Test
    fun `openai compatible cache fields partition inclusive input without double billing`() {
        val usage = UsageParser.fromJson(
            Json.parseToJsonElement(
                """{"usage":{"prompt_tokens":100,"completion_tokens":0,"total_tokens":100,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":30}}}""",
            ).jsonObject,
        )!!

        val cost = BillingCalculator.calculate(
            record(
                input = usage.input,
                output = usage.output,
                cached = usage.cached,
                cachedWrite = usage.cacheWrite,
            ),
            price(input = "1", output = "0", cached = "0.5", cachedWrite = "1.25"),
        )

        // 100 prompt tokens split into 50 uncached, 20 cache-read, and 30 cache-write tokens.
        assertEquals(100L, usage.input)
        assertEquals(BigDecimal("0.00009750"), cost?.amount)
    }

    @Test
    fun `charges fixed amount for per_request pricing mode on successful request`() {
        val perReqPrice = ModelPriceRecord(
            id = "p-req",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.05"),
            createdAt = 0,
            updatedAt = 0,
        )
        val log = record(input = null, output = null, known = false)
        val cost = BillingCalculator.calculate(log, perReqPrice, requestSucceeded = true)

        assertEquals(BigDecimal("0.05000000"), cost?.amount)
        assertEquals("USD", cost?.currency)
        assertEquals(PricingMode.PER_REQUEST, cost?.pricingMode)
        assertEquals(BigDecimal("0.05"), cost?.perRequestPrice)
    }

    @Test
    fun `does not charge per_request pricing when request failed`() {
        val perReqPrice = ModelPriceRecord(
            id = "p-req",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.05"),
            createdAt = 0,
            updatedAt = 0,
        )
        val log = record(input = null, output = null, known = false)
        val cost = BillingCalculator.calculate(log, perReqPrice, requestSucceeded = false)

        assertNull(cost)
    }

    @Test
    fun `charges tiered tokens progressively across bands`() {
        val tieredPrice = ModelPriceRecord(
            id = "p-tiered",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.TIERED,
            priceTiers = listOf(
                ModelPriceTier(
                    upToTokens = 100,
                    inputPricePerMillion = BigDecimal("1.0"),
                    outputPricePerMillion = BigDecimal("2.0"),
                    cachedInputPricePerMillion = BigDecimal("0.5"),
                    cachedInputWritePricePerMillion = BigDecimal("0.8"),
                ),
                ModelPriceTier(
                    upToTokens = null,
                    inputPricePerMillion = BigDecimal("2.0"),
                    outputPricePerMillion = BigDecimal("4.0"),
                    cachedInputPricePerMillion = BigDecimal("1.0"),
                    cachedInputWritePricePerMillion = BigDecimal("1.6"),
                ),
            ),
            createdAt = 0,
            updatedAt = 0,
        )
        // input = 150 uncached + 120 cached + 80 cachedWrite = 350
        val log = record(input = 350, output = 200, cached = 120, cachedWrite = 80)
        val cost = BillingCalculator.calculate(log, tieredPrice)

        assertEquals(BigDecimal("0.00093400"), cost?.amount)
        assertEquals(PricingMode.TIERED, cost?.pricingMode)
        assertEquals(2, cost?.priceTiers?.size)
    }

    @Test
    fun `tiered billing ignores non increasing extreme bounds without overflowing`() {
        val tieredPrice = ModelPriceRecord(
            id = "p-tiered-extreme",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.TIERED,
            priceTiers = listOf(
                ModelPriceTier(
                    upToTokens = Long.MAX_VALUE - 10,
                    inputPricePerMillion = BigDecimal.ONE,
                    outputPricePerMillion = BigDecimal.ZERO,
                ),
                ModelPriceTier(
                    upToTokens = Long.MIN_VALUE,
                    inputPricePerMillion = BigDecimal("100"),
                    outputPricePerMillion = BigDecimal.ZERO,
                ),
                ModelPriceTier(
                    upToTokens = null,
                    inputPricePerMillion = BigDecimal("2"),
                    outputPricePerMillion = BigDecimal.ZERO,
                ),
            ),
            createdAt = 0,
            updatedAt = 0,
        )

        val cost = BillingCalculator.calculate(
            record(input = Long.MAX_VALUE, output = 0),
            tieredPrice,
        )

        assertEquals(BigDecimal("9223372036854.77581700"), cost?.amount)
    }

    @Test
    fun `tiered billing handles Long MAX upper bound after a previous band`() {
        val tieredPrice = ModelPriceRecord(
            id = "p-tiered-max-bound",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.TIERED,
            priceTiers = listOf(
                ModelPriceTier(
                    upToTokens = 10,
                    inputPricePerMillion = BigDecimal.ONE,
                    outputPricePerMillion = BigDecimal.ZERO,
                ),
                ModelPriceTier(
                    upToTokens = Long.MAX_VALUE,
                    inputPricePerMillion = BigDecimal("2"),
                    outputPricePerMillion = BigDecimal.ZERO,
                ),
            ),
            createdAt = 0,
            updatedAt = 0,
        )

        val cost = BillingCalculator.calculate(
            record(input = Long.MAX_VALUE, output = 0),
            tieredPrice,
        )

        assertEquals(BigDecimal("18446744073709.55160400"), cost?.amount)
    }

    @Test
    fun `charges volume tokens at the matching band for entire usage`() {
        val volumePrice = ModelPriceRecord(
            id = "p-vol",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.VOLUME,
            priceTiers = listOf(
                ModelPriceTier(
                    upToTokens = 100,
                    inputPricePerMillion = BigDecimal("1.0"),
                    outputPricePerMillion = BigDecimal("2.0"),
                    cachedInputPricePerMillion = BigDecimal("0.5"),
                    cachedInputWritePricePerMillion = BigDecimal("0.8"),
                ),
                ModelPriceTier(
                    upToTokens = null,
                    inputPricePerMillion = BigDecimal("2.0"),
                    outputPricePerMillion = BigDecimal("4.0"),
                    cachedInputPricePerMillion = BigDecimal("1.0"),
                    cachedInputWritePricePerMillion = BigDecimal("1.6"),
                ),
            ),
            createdAt = 0,
            updatedAt = 0,
        )
        // input = 150 uncached (> 100 -> tier 2), output = 80 (<= 100 -> tier 1), cached = 120 (> 100 -> tier 2), cachedWrite = 50 (<= 100 -> tier 1)
        val log = record(input = 320, output = 80, cached = 120, cachedWrite = 50)
        val cost = BillingCalculator.calculate(log, volumePrice)

        assertEquals(BigDecimal("0.00062000"), cost?.amount)
        assertEquals(PricingMode.VOLUME, cost?.pricingMode)
    }

    @Test
    fun `tiered rate falls back to tier input price when cache rate is null`() {
        val tieredPrice = ModelPriceRecord(
            id = "p-tiered-fallback",
            channelId = "c",
            channelName = "c",
            upstreamModel = "m",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = PricingMode.TIERED,
            priceTiers = listOf(
                ModelPriceTier(
                    upToTokens = null,
                    inputPricePerMillion = BigDecimal("2.0"),
                    outputPricePerMillion = BigDecimal("3.0"),
                    cachedInputPricePerMillion = null,
                    cachedInputWritePricePerMillion = null,
                ),
            ),
            createdAt = 0,
            updatedAt = 0,
        )
        val log = record(input = 30, output = 10, cached = 10, cachedWrite = 10)
        val cost = BillingCalculator.calculate(log, tieredPrice)

        // uncached: 10 * 2.0 = 20, cachedRead: 10 * 2.0 = 20, cachedWrite: 10 * 2.0 = 20, output: 10 * 3.0 = 30. Total = 90 / 1M = 0.00009000
        assertEquals(BigDecimal("0.00009000"), cost?.amount)
    }

    private fun record(
        input: Long?,
        output: Long?,
        cached: Long? = null,
        cachedWrite: Long? = null,
        known: Boolean = true,
    ) = RequestLogRecord(
        requestId = "req",
        clientKeyId = null,
        clientKeyName = null,
        alias = "alias",
        channelId = "channel",
        channelName = "channel",
        upstreamModel = "model",
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 100,
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
        cachedWriteTokens = cachedWrite,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = null,
        usageKnown = known,
        costAmount = null,
        costCurrency = null,
        inputPricePerMillion = null,
        outputPricePerMillion = null,
        cachedInputPricePerMillion = null,
        errorCode = null,
        failureStage = null,
        errorMessage = null,
        upstreamErrorCode = null,
        createdAt = 0,
    )

    private fun price(
        input: String = "1",
        output: String = "2",
        cached: String? = null,
        cachedWrite: String? = null,
    ) = ModelPriceRecord(
        id = "price",
        channelId = "channel",
        channelName = "channel",
        upstreamModel = "model",
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = cached?.let(::BigDecimal),
        cachedInputWritePricePerMillion = cachedWrite?.let(::BigDecimal),
        createdAt = 0,
        updatedAt = 0,
    )
}
