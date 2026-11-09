package dev.soven.llmgateway.pricing.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PricingCalculatorTest {
    @Test
    fun `charges cached tokens at the cache price`() {
        val cost = PricingCalculator.calculate(
            request(input = 100, output = 50, cached = 80),
            price(input = "1", output = "2", cached = "0.5"),
        )

        assertEquals(BigDecimal("0.00016000"), cost?.amount)
        assertEquals(BigDecimal("0.5"), cost?.cachedInputPricePerMillion)
    }

    @Test
    fun `falls back to the input price for missing cache rates`() {
        val cost = PricingCalculator.calculate(
            request(input = 100, output = 10, cached = 20, cachedWrite = 30),
            price(input = "1", output = "2", cached = null),
        )

        assertEquals(BigDecimal("0.00012000"), cost?.amount)
        assertEquals(BigDecimal("1"), cost?.cachedInputPricePerMillion)
        assertEquals(BigDecimal("1"), cost?.cachedInputWritePricePerMillion)
    }

    @Test
    fun `tiered prices only tokens inside each band`() {
        val cost = PricingCalculator.calculate(
            request(input = 350, output = 200, cached = 120, cachedWrite = 80),
            price(mode = PricingMode.TIERED, tiers = tiers()),
        )

        assertEquals(BigDecimal("0.00093400"), cost?.amount)
    }

    @Test
    fun `volume prices the full token count at the matching band`() {
        val cost = PricingCalculator.calculate(
            request(input = 320, output = 80, cached = 120, cachedWrite = 50),
            price(mode = PricingMode.VOLUME, tiers = tiers()),
        )

        assertEquals(BigDecimal("0.00062000"), cost?.amount)
    }

    @Test
    fun `per-request prices charge only successful requests`() {
        val perRequest = price(mode = PricingMode.PER_REQUEST, perRequestPrice = "0.05")
        val failed = PricingCalculator.calculate(
            request(input = null, output = null, known = false, succeeded = false),
            perRequest,
        )
        assertNull(failed)

        val succeeded = PricingCalculator.calculate(
            request(input = null, output = null, known = false),
            perRequest,
        )
        assertEquals(BigDecimal("0.05000000"), succeeded?.amount)
    }

    @Test
    fun `returns null when usage is incomplete`() {
        assertNull(PricingCalculator.calculate(request(input = null, output = 10), price()))
        assertNull(PricingCalculator.calculate(request(input = 10, output = null), price()))
        assertNull(PricingCalculator.calculate(request(input = 10, output = 10, known = false), price()))
    }

    private fun request(
        input: Long?,
        output: Long?,
        cached: Long? = null,
        cachedWrite: Long? = null,
        known: Boolean = true,
        succeeded: Boolean = true,
    ) = BillingRequest(
        usageKnown = known,
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
        cachedWriteTokens = cachedWrite,
        succeeded = succeeded,
    )

    private fun price(
        input: String = "1",
        output: String = "2",
        cached: String? = "0.5",
        mode: PricingMode = PricingMode.TOKEN,
        tiers: List<ModelPriceTier> = emptyList(),
        perRequestPrice: String? = null,
    ) = ResolvedModelPrice(
        source = ModelPriceSource.MODEL_DEFAULT,
        modelId = "model",
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = cached?.let(::BigDecimal),
        pricingMode = mode,
        perRequestPrice = perRequestPrice?.let(::BigDecimal),
        priceTiers = tiers,
    )

    private fun tiers() = listOf(
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
    )
}