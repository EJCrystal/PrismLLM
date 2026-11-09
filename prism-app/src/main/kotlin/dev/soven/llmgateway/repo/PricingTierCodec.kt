package dev.soven.llmgateway.repo

import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Encodes tier rates as decimal strings so JSON never passes through binary floating point. */
internal object PricingTierCodec {
    @Serializable
    private data class StoredTier(
        val upToTokens: Long?,
        val inputPricePerMillion: String,
        val outputPricePerMillion: String,
        val cachedInputPricePerMillion: String? = null,
        val cachedInputWritePricePerMillion: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredTier.serializer())

    fun encode(tiers: List<ModelPriceTier>): String = json.encodeToString(
        serializer,
        tiers.map { tier ->
            StoredTier(
                upToTokens = tier.upToTokens,
                inputPricePerMillion = tier.inputPricePerMillion.toPlainString(),
                outputPricePerMillion = tier.outputPricePerMillion.toPlainString(),
                cachedInputPricePerMillion = tier.cachedInputPricePerMillion?.toPlainString(),
                cachedInputWritePricePerMillion = tier.cachedInputWritePricePerMillion?.toPlainString(),
            )
        },
    )

    fun decode(value: String): List<ModelPriceTier> = json.decodeFromString(serializer, value).map { tier ->
        ModelPriceTier(
            upToTokens = tier.upToTokens,
            inputPricePerMillion = tier.inputPricePerMillion.toBigDecimal(),
            outputPricePerMillion = tier.outputPricePerMillion.toBigDecimal(),
            cachedInputPricePerMillion = tier.cachedInputPricePerMillion?.toBigDecimal(),
            cachedInputWritePricePerMillion = tier.cachedInputWritePricePerMillion?.toBigDecimal(),
        )
    }
}
