package dev.soven.prismllm.pricing.persistence

import org.jetbrains.exposed.v1.core.Table

/**
 * 按对外模型 ID 配置的默认计费单价。渠道内同上游模型的价格优先于默认价。
 *
 * @author 文杰
 * @since 2026-10-27
 */
object ModelDefaultPrices : Table("model_default_prices") {
    val id = varchar("id", 36)
    val modelId = varchar("model_id", 200).uniqueIndex()
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
}