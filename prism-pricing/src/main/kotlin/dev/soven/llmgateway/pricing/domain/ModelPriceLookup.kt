package dev.soven.llmgateway.pricing.domain

/**
 * 渠道覆盖价的读取端口：按渠道 + 上游模型唯一。
 *
 * @author 文杰
 * @since 2026-11-10
 */
interface ModelPriceLookup {
    suspend fun find(channelId: String, upstreamModel: String): ModelPriceRecord?
}

/**
 * 对外模型默认价的读取端口：按模型 ID 唯一。
 *
 * @author 文杰
 * @since 2026-11-10
 */
interface DefaultModelPriceLookup {
    suspend fun find(modelId: String): DefaultModelPriceRecord?
}