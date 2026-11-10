package dev.soven.prismllm.pricing.domain

/**
 * 统一解析渠道覆盖价与模型默认价。数据面与管理面使用同一优先级：
 * `渠道 + 上游模型` > `对外模型默认价` > 未定价。
 *
 * 价格读取通过 [ModelPriceLookup] 与 [DefaultModelPriceLookup] 端口注入，
 * 解析规则不感知持久化实现。
 *
 * @author 文杰
 * @since 2026-10-27
 */
class ModelPricingService(
    private val channelPriceLookup: ModelPriceLookup,
    private val defaultPriceLookup: DefaultModelPriceLookup,
) {
    suspend fun resolve(
        channelId: String?,
        upstreamModel: String?,
        modelId: String?,
    ): ResolvedModelPrice? {
        if (channelId != null && upstreamModel != null) {
            channelPriceLookup.find(channelId, upstreamModel)?.let { price ->
                return ResolvedModelPrice(
                    source = ModelPriceSource.CHANNEL_OVERRIDE,
                    modelId = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: upstreamModel.trim(),
                    channelPriceId = price.id,
                    currency = price.currency,
                    inputPricePerMillion = price.inputPricePerMillion,
                    outputPricePerMillion = price.outputPricePerMillion,
                    cachedInputPricePerMillion = price.cachedInputPricePerMillion,
                    cachedInputWritePricePerMillion = price.cachedInputWritePricePerMillion,
                    pricingMode = price.pricingMode,
                    perRequestPrice = price.perRequestPrice,
                    priceTiers = price.priceTiers,
                )
            }
        }

        val defaultModelIds = listOfNotNull(
            modelId?.trim()?.takeIf { it.isNotEmpty() },
            upstreamModel?.trim()?.takeIf { it.isNotEmpty() },
        ).distinct()
        for (defaultModelId in defaultModelIds) {
            defaultPriceLookup.find(defaultModelId)?.let { price ->
                return ResolvedModelPrice(
                    source = ModelPriceSource.MODEL_DEFAULT,
                    modelId = price.modelId,
                    defaultPriceId = price.id,
                    currency = price.currency,
                    inputPricePerMillion = price.inputPricePerMillion,
                    outputPricePerMillion = price.outputPricePerMillion,
                    cachedInputPricePerMillion = price.cachedInputPricePerMillion,
                    cachedInputWritePricePerMillion = price.cachedInputWritePricePerMillion,
                    pricingMode = price.pricingMode,
                    perRequestPrice = price.perRequestPrice,
                    priceTiers = price.priceTiers,
                )
            }
        }
        return null
    }
}