package dev.soven.llmgateway.pricing.domain

import java.math.BigDecimal

/** 单次请求实际采用的计价来源。 */
enum class ModelPriceSource(val value: String) {
    CHANNEL_OVERRIDE("channel_override"),
    MODEL_DEFAULT("model_default"),
}

/**
 * 解析后的有效单价。渠道覆盖价优先，其次是对外模型默认价。
 */
data class ResolvedModelPrice(
    val source: ModelPriceSource,
    val modelId: String,
    val channelPriceId: String? = null,
    val defaultPriceId: String? = null,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
)