package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.ModelPriceTier
import dev.soven.llmgateway.domain.PricingMode
import dev.soven.llmgateway.repo.ModelDefaultPriceRepository
import dev.soven.llmgateway.repo.ModelPriceRepository
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
    val priceVersion: Int? = null,
    val priceVersionId: String? = null,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
)

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
        asOfTime: Long = System.currentTimeMillis(),
    ): ResolvedModelPrice? {
        if (channelId != null && upstreamModel != null) {
            val primaryPrice = channelPriceRepository.find(channelId, upstreamModel)
            channelPriceRepository.findEffective(channelId, upstreamModel, asOfTime)?.let { version ->
                return ResolvedModelPrice(
                    source = ModelPriceSource.CHANNEL_OVERRIDE,
                    modelId = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: upstreamModel.trim(),
                    channelPriceId = primaryPrice?.id,
                    priceVersion = version.version,
                    priceVersionId = version.id,
                    currency = version.currency,
                    inputPricePerMillion = version.inputPricePerMillion,
                    outputPricePerMillion = version.outputPricePerMillion,
                    cachedInputPricePerMillion = version.cachedInputPricePerMillion,
                    cachedInputWritePricePerMillion = version.cachedInputWritePricePerMillion,
                    pricingMode = version.pricingMode,
                    perRequestPrice = version.perRequestPrice,
                    priceTiers = version.priceTiers,
                )
            }
        }

        val defaultModelIds = listOfNotNull(
            modelId?.trim()?.takeIf { it.isNotEmpty() },
            upstreamModel?.trim()?.takeIf { it.isNotEmpty() },
        ).distinct()
        for (defaultModelId in defaultModelIds) {
            val primaryPrice = defaultPriceRepository.find(defaultModelId)
            defaultPriceRepository.findEffective(defaultModelId, asOfTime)?.let { version ->
                return ResolvedModelPrice(
                    source = ModelPriceSource.MODEL_DEFAULT,
                    modelId = version.modelId,
                    defaultPriceId = primaryPrice?.id,
                    priceVersion = version.version,
                    priceVersionId = version.id,
                    currency = version.currency,
                    inputPricePerMillion = version.inputPricePerMillion,
                    outputPricePerMillion = version.outputPricePerMillion,
                    cachedInputPricePerMillion = version.cachedInputPricePerMillion,
                    cachedInputWritePricePerMillion = version.cachedInputWritePricePerMillion,
                    pricingMode = version.pricingMode,
                    perRequestPrice = version.perRequestPrice,
                    priceTiers = version.priceTiers,
                )
            }
        }
        return null
    }

    suspend fun resolve(record: RequestLogRecord): ResolvedModelPrice? =
        resolve(record.channelId, record.upstreamModel, record.pricingModelId, record.createdAt)
}
