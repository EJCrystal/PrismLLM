package dev.soven.prismllm.pricing.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 渠道内上游模型的计费单价。单价按每百万 token 计算。
 *
 * 渠道外键由数据库迁移维护；计价模块只保存渠道标识，不反向依赖渠道表。
 *
 * @author 文杰
 * @since 2026-10-22
 */
object ModelPrices : Table("model_prices") {
    val id = varchar("id", 36)
    val channelId = varchar("channel_id", 36)
    val upstreamModel = varchar("upstream_model", 200)
    val currency = varchar("currency", 8)
    val inputPricePerMillion = decimal("input_price_per_million", 18, 8)
    val outputPricePerMillion = decimal("output_price_per_million", 18, 8)
    val cachedInputPricePerMillion = decimal("cached_input_price_per_million", 18, 8).nullable()
    val cachedInputWritePricePerMillion = decimal("cached_input_write_price_per_million", 18, 8).nullable()
    val pricingMode = varchar("pricing_mode", 20).default("token")
    val perRequestPrice = decimal("per_request_price", 18, 8).nullable()
    val priceTiersJson = text("price_tiers_json").default("[]")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(channelId, upstreamModel)
    }
}
