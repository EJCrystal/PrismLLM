package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.RequestLogRecord
import java.math.BigDecimal
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

    private fun record(
        input: Long?,
        output: Long?,
        cached: Long? = null,
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
    ) = ModelPriceRecord(
        id = "price",
        channelId = "channel",
        channelName = "channel",
        upstreamModel = "model",
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = cached?.let(::BigDecimal),
        createdAt = 0,
        updatedAt = 0,
    )
}
