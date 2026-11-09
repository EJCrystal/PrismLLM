package dev.soven.llmgateway.domain

import java.math.BigDecimal

/** Billing strategy persisted with a model price. */
enum class PricingMode(val value: String) {
    TOKEN("token"),
    PER_REQUEST("per_request"),
    TIERED("tiered"),
    VOLUME("volume");

    companion object {
        fun fromValue(value: String?): PricingMode =
            entries.firstOrNull { it.value == value } ?: TOKEN
    }
}

/**
 * Rates for one inclusive token-volume band. A null upper bound means there is no upper limit.
 * Cache rates are optional and fall back to the input rate for the same tier.
 */
data class ModelPriceTier(
    val upToTokens: Long?,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal? = null,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
)
