package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceSource
import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
import dev.soven.llmgateway.pricing.domain.ResolvedModelPrice
import dev.soven.llmgateway.repo.ModelDefaultPriceRepository
import dev.soven.llmgateway.repo.ModelPriceRepository
import java.math.BigDecimal

/**
 * 统一解析渠道覆盖价与模型默认价。数据面与管理面使用同一优先级：
 * `渠道 + 上游模型` > `对外模型默认价` > 未定价。
 *
 * @author 文杰
 * @since 2026-10-27
 */
class ModelPricingService(
    private val channelPriceRepository: ModelPriceRepository,
    private val defaultPriceRepository: ModelDefaultPriceRepository,
) {
    suspend fun resolve(
        channelId: String?,
        upstreamModel: String?,
        modelId: String?,
    ): ResolvedModelPrice? {
        if (channelId != null && upstreamModel != null) {
            channelPriceRepository.find(channelId, upstreamModel)?.let { price ->
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
            defaultPriceRepository.find(defaultModelId)?.let { price ->
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

    suspend fun resolve(record: RequestLogRecord): ResolvedModelPrice? =
        resolve(record.channelId, record.upstreamModel, record.pricingModelId)
}
