package dev.soven.prismllm.pricing.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** 计算计费所需的请求用量与结果；由调用方从请求日志或即时响应中提取。 */
data class BillingRequest(
    val usageKnown: Boolean,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long? = null,
    val cachedWriteTokens: Long? = null,
    val succeeded: Boolean,
)

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
object PricingCalculator {
    private val MILLION = BigDecimal("1000000")
    private val ZERO = BigDecimal.ZERO

    fun calculate(request: BillingRequest, price: ResolvedModelPrice): CalculatedCost? {
        // 结算防御：非 USD 价格若绕过配置校验（如手工改库）混入，拒绝计费而不是把原币金额当 USD 记账。
        if (price.currency != BILLING_CURRENCY) return null
        if (price.pricingMode == PricingMode.PER_REQUEST) {
            if (!request.succeeded) return null
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

        if (!request.usageKnown) return null
        val input = request.inputTokens ?: return null
        val output = request.outputTokens ?: return null
        val cachedRead = (request.cachedTokens ?: 0L).coerceIn(0L, input)
        val cachedWrite = (request.cachedWriteTokens ?: 0L).coerceIn(0L, input - cachedRead)
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
}