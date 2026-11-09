package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.DefaultModelPriceRecord
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceSource
import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
import dev.soven.llmgateway.pricing.domain.ResolvedModelPrice
import dev.soven.llmgateway.domain.RequestLogRecord
import java.math.BigDecimal
import java.math.RoundingMode

/** One request charge and the price snapshot used to calculate it. */
data class CalculatedCost(
    val amount: BigDecimal,
    val currency: String,
    val pricingMode: PricingMode,
    val inputPricePerMillion: BigDecimal?,
    val outputPricePerMillion: BigDecimal?,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal?,
    val perRequestPrice: BigDecimal?,
    val priceTiers: List<ModelPriceTier>,
)

/**
 * Cache read/write prices default to the tier's input rate. Tier thresholds apply independently
 * to ordinary input, cached read, cache write and output token counts. TIERED prices only tokens
 * within each band at that band's rate; VOLUME prices all tokens of that category at the rate for
 * the category's full request volume.
 */
object BillingCalculator {
    private val MILLION = BigDecimal("1000000")
    private val ZERO = BigDecimal.ZERO

    fun calculate(
        record: RequestLogRecord,
        price: ModelPriceRecord,
        requestSucceeded: Boolean = record.statusCode in 200..299 && record.errorCode == null && record.failureStage == null,
    ): CalculatedCost? = calculate(record, price.toResolved(), requestSucceeded)

    fun calculate(
        record: RequestLogRecord,
        price: DefaultModelPriceRecord,
        requestSucceeded: Boolean = record.statusCode in 200..299 && record.errorCode == null && record.failureStage == null,
    ): CalculatedCost? = calculate(record, price.toResolved(), requestSucceeded)

    fun calculate(
        record: RequestLogRecord,
        price: ResolvedModelPrice,
        requestSucceeded: Boolean = record.statusCode in 200..299 && record.errorCode == null && record.failureStage == null,
    ): CalculatedCost? {
        if (price.pricingMode == PricingMode.PER_REQUEST) {
            if (!requestSucceeded) return null
            val requestPrice = price.perRequestPrice ?: return null
            return CalculatedCost(
                amount = requestPrice.setScale(8, RoundingMode.HALF_UP),
                currency = price.currency,
                pricingMode = price.pricingMode,
                inputPricePerMillion = null,
                outputPricePerMillion = null,
                cachedInputPricePerMillion = null,
                cachedInputWritePricePerMillion = null,
                perRequestPrice = requestPrice,
                priceTiers = emptyList(),
            )
        }

        if (!record.usageKnown) return null
        val input = record.inputTokens ?: return null
        val output = record.outputTokens ?: return null
        val cachedRead = (record.cachedTokens ?: 0L).coerceIn(0L, input)
        val cachedWrite = (record.cachedWriteTokens ?: 0L).coerceIn(0L, input - cachedRead)
        val uncachedInput = input - cachedRead - cachedWrite

        val amount = when (price.pricingMode) {
            PricingMode.TOKEN -> {
                val readRate = price.cachedInputPricePerMillion ?: price.inputPricePerMillion
                val writeRate = price.cachedInputWritePricePerMillion ?: price.inputPricePerMillion
                BigDecimal.valueOf(uncachedInput).multiply(price.inputPricePerMillion)
                    .add(BigDecimal.valueOf(cachedRead).multiply(readRate))
                    .add(BigDecimal.valueOf(cachedWrite).multiply(writeRate))
                    .add(BigDecimal.valueOf(output).multiply(price.outputPricePerMillion))
            }
            PricingMode.TIERED, PricingMode.VOLUME -> {
                if (price.priceTiers.isEmpty()) return null
                val tiered = price.pricingMode == PricingMode.TIERED
                tierAmount(uncachedInput, price.priceTiers, tiered) { it.inputPricePerMillion }
                    .add(tierAmount(cachedRead, price.priceTiers, tiered) {
                        it.cachedInputPricePerMillion ?: it.inputPricePerMillion
                    })
                    .add(tierAmount(cachedWrite, price.priceTiers, tiered) {
                        it.cachedInputWritePricePerMillion ?: it.inputPricePerMillion
                    })
                    .add(tierAmount(output, price.priceTiers, tiered) { it.outputPricePerMillion })
            }
            PricingMode.PER_REQUEST -> error("Per-request prices were handled above")
        }.divide(MILLION, 8, RoundingMode.HALF_UP)

        return CalculatedCost(
            amount = amount,
            currency = price.currency,
            pricingMode = price.pricingMode,
            inputPricePerMillion = price.inputPricePerMillion,
            outputPricePerMillion = price.outputPricePerMillion,
            cachedInputPricePerMillion = if (price.pricingMode == PricingMode.TOKEN) {
                price.cachedInputPricePerMillion ?: price.inputPricePerMillion
            } else null,
            cachedInputWritePricePerMillion = if (price.pricingMode == PricingMode.TOKEN) {
                price.cachedInputWritePricePerMillion ?: price.inputPricePerMillion
            } else null,
            perRequestPrice = null,
            priceTiers = price.priceTiers,
        )
    }

    private fun tierAmount(
        tokens: Long,
        tiers: List<ModelPriceTier>,
        progressive: Boolean,
        rate: (ModelPriceTier) -> BigDecimal,
    ): BigDecimal {
        if (tokens <= 0) return ZERO
        if (!progressive) {
            val tier = tiers.firstOrNull { tier -> tier.upToTokens?.let { tokens <= it } ?: true } ?: tiers.last()
            return BigDecimal.valueOf(tokens).multiply(rate(tier))
        }

        var lowerBound = 0L
        var remaining = tokens
        var amount = ZERO
        for (tier in tiers) {
            if (remaining == 0L) break
            val upperBound = tier.upToTokens
            val capacity = upperBound?.let {
                if (it <= lowerBound) 0L else it - lowerBound
            } ?: remaining
            val inBand = minOf(remaining, capacity)
            if (inBand > 0) amount = amount.add(BigDecimal.valueOf(inBand).multiply(rate(tier)))
            remaining -= inBand
            upperBound?.takeIf { it > lowerBound }?.let { lowerBound = it }
        }
        return amount
    }

    private fun ModelPriceRecord.toResolved() = ResolvedModelPrice(
        source = ModelPriceSource.CHANNEL_OVERRIDE,
        modelId = upstreamModel,
        channelPriceId = id,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        cachedInputPricePerMillion = cachedInputPricePerMillion,
        cachedInputWritePricePerMillion = cachedInputWritePricePerMillion,
        pricingMode = pricingMode,
        perRequestPrice = perRequestPrice,
        priceTiers = priceTiers,
    )

    private fun DefaultModelPriceRecord.toResolved() = ResolvedModelPrice(
        source = ModelPriceSource.MODEL_DEFAULT,
        modelId = modelId,
        defaultPriceId = id,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        cachedInputPricePerMillion = cachedInputPricePerMillion,
        cachedInputWritePricePerMillion = cachedInputWritePricePerMillion,
        pricingMode = pricingMode,
        perRequestPrice = perRequestPrice,
        priceTiers = priceTiers,
    )
}
