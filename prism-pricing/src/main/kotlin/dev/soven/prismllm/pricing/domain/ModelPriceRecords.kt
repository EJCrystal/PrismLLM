package dev.soven.prismllm.pricing.domain

import java.math.BigDecimal

/**
 * 渠道内上游模型的计费单价。单价按每百万 token 计算。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class ModelPriceRecord(
    val id: String,
    val channelId: String,
    val channelName: String,
    val upstreamModel: String,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val createdAt: Long,
    val updatedAt: Long,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
)

/** 按对外模型 ID 配置的默认单价。 */
data class DefaultModelPriceRecord(
    val id: String,
    val modelId: String,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val createdAt: Long,
    val updatedAt: Long,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
)