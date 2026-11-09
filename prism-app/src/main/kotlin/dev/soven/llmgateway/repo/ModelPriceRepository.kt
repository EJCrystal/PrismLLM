package dev.soven.llmgateway.repo

import dev.soven.llmgateway.channel.persistence.Channels
import dev.soven.llmgateway.db.ModelPrices
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.util.UUID

/**
 * 模型计费单价的持久化。价格按渠道 + 上游模型唯一。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class ModelPriceRepository(private val database: Database) {
    suspend fun list(): List<ModelPriceRecord> = suspendTransaction(database) {
        ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .map(::toRecord)
            .sortedWith(compareBy({ it.channelName }, { it.upstreamModel }))
    }

    suspend fun find(channelId: String, upstreamModel: String): ModelPriceRecord? =
        suspendTransaction(database) {
            val normalizedModel = upstreamModel.trim()
            if (normalizedModel.isEmpty()) return@suspendTransaction null
            ModelPrices
                .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
                .selectAll()
                .where { (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq normalizedModel) }
                .singleOrNull()
                ?.let(::toRecord)
        }

    suspend fun findById(id: String): ModelPriceRecord? = suspendTransaction(database) {
        load(id)
    }

    suspend fun create(
        channelId: String,
        upstreamModel: String,
        currency: String,
        inputPricePerMillion: BigDecimal,
        outputPricePerMillion: BigDecimal,
        cachedInputPricePerMillion: BigDecimal? = null,
        cachedInputWritePricePerMillion: BigDecimal? = null,
        pricingMode: PricingMode = PricingMode.TOKEN,
        perRequestPrice: BigDecimal? = null,
        priceTiers: List<ModelPriceTier> = emptyList(),
    ): ModelPriceRecord? = suspendTransaction(database) {
        val normalizedModel = upstreamModel.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val inserted = ModelPrices.insertIgnore {
            it[ModelPrices.id] = id
            it[ModelPrices.channelId] = channelId
            it[ModelPrices.upstreamModel] = normalizedModel
            it[ModelPrices.currency] = currency
            it[ModelPrices.inputPricePerMillion] = inputPricePerMillion
            it[ModelPrices.outputPricePerMillion] = outputPricePerMillion
            it[ModelPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelPrices.pricingMode] = pricingMode.value
            it[ModelPrices.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[createdAt] = now
            it[updatedAt] = now
        }.insertedCount > 0
        if (!inserted) return@suspendTransaction null
        load(id)
    }

    suspend fun update(
        id: String,
        channelId: String,
        upstreamModel: String,
        currency: String,
        inputPricePerMillion: BigDecimal,
        outputPricePerMillion: BigDecimal,
        cachedInputPricePerMillion: BigDecimal? = null,
        cachedInputWritePricePerMillion: BigDecimal? = null,
        pricingMode: PricingMode = PricingMode.TOKEN,
        perRequestPrice: BigDecimal? = null,
        priceTiers: List<ModelPriceTier> = emptyList(),
    ): ModelPriceRecord? = suspendTransaction(database) {
        val normalizedModel = upstreamModel.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null

        val existing = ModelPrices.selectAll()
            .where { ModelPrices.id eq id }
            .forUpdate()
            .singleOrNull() ?: return@suspendTransaction null

        if (existing[ModelPrices.channelId] != channelId || existing[ModelPrices.upstreamModel] != normalizedModel) {
            return@suspendTransaction null
        }

        val now = System.currentTimeMillis()
        val changed = ModelPrices.update({ ModelPrices.id eq id }) {
            it[ModelPrices.currency] = currency
            it[ModelPrices.inputPricePerMillion] = inputPricePerMillion
            it[ModelPrices.outputPricePerMillion] = outputPricePerMillion
            it[ModelPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelPrices.pricingMode] = pricingMode.value
            it[ModelPrices.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[updatedAt] = now
        }
        if (changed == 0) null else load(id)
    }

    /**
     * 在同一渠道内批量创建或更新多个上游模型的单价。所有写入在同一事务内完成。
     */
    suspend fun upsertBatch(
        channelId: String,
        upstreamModels: List<String>,
        currency: String,
        inputPricePerMillion: BigDecimal,
        outputPricePerMillion: BigDecimal,
        cachedInputPricePerMillion: BigDecimal?,
        cachedInputWritePricePerMillion: BigDecimal? = null,
        pricingMode: PricingMode = PricingMode.TOKEN,
        perRequestPrice: BigDecimal? = null,
        priceTiers: List<ModelPriceTier> = emptyList(),
    ): List<ModelPriceRecord> = suspendTransaction(database) {
        val normalizedModels = upstreamModels
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedModels.isEmpty()) return@suspendTransaction emptyList()

        val now = System.currentTimeMillis()
        for (upstreamModel in normalizedModels) {
            ModelPrices.upsert(
                ModelPrices.channelId,
                ModelPrices.upstreamModel,
                onUpdateExclude = listOf(ModelPrices.id, ModelPrices.createdAt),
            ) {
                it[id] = UUID.randomUUID().toString()
                it[ModelPrices.channelId] = channelId
                it[ModelPrices.upstreamModel] = upstreamModel
                it[ModelPrices.currency] = currency
                it[ModelPrices.inputPricePerMillion] = inputPricePerMillion
                it[ModelPrices.outputPricePerMillion] = outputPricePerMillion
                it[ModelPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
                it[ModelPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
                it[ModelPrices.pricingMode] = pricingMode.value
                it[ModelPrices.perRequestPrice] = perRequestPrice
                it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
                it[createdAt] = now
                it[updatedAt] = now
            }
        }

        ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .where {
                (ModelPrices.channelId eq channelId) and
                    (ModelPrices.upstreamModel inList normalizedModels)
            }
            .map(::toRecord)
            .sortedBy { it.upstreamModel }
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ModelPrices.deleteWhere { ModelPrices.id eq id } > 0
    }

    private fun load(id: String): ModelPriceRecord? =
        ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .where { ModelPrices.id eq id }
            .singleOrNull()
            ?.let(::toRecord)

    private fun toRecord(row: ResultRow) = ModelPriceRecord(
        id = row[ModelPrices.id],
        channelId = row[ModelPrices.channelId],
        channelName = row[Channels.name],
        upstreamModel = row[ModelPrices.upstreamModel],
        currency = row[ModelPrices.currency],
        inputPricePerMillion = row[ModelPrices.inputPricePerMillion],
        outputPricePerMillion = row[ModelPrices.outputPricePerMillion],
        cachedInputPricePerMillion = row[ModelPrices.cachedInputPricePerMillion],
        cachedInputWritePricePerMillion = row[ModelPrices.cachedInputWritePricePerMillion],
        pricingMode = PricingMode.fromValue(row[ModelPrices.pricingMode]),
        perRequestPrice = row[ModelPrices.perRequestPrice],
        priceTiers = PricingTierCodec.decode(row[ModelPrices.priceTiersJson]),
        createdAt = row[ModelPrices.createdAt],
        updatedAt = row[ModelPrices.updatedAt],
    )
}
