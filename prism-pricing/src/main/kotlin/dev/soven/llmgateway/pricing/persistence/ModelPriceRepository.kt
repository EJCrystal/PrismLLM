package dev.soven.llmgateway.pricing.persistence

import dev.soven.llmgateway.pricing.domain.ChannelNameLookup
import dev.soven.llmgateway.pricing.domain.ModelPriceLookup
import dev.soven.llmgateway.pricing.domain.ModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
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
class ModelPriceRepository(
    private val database: Database,
    private val channelNames: ChannelNameLookup,
) : ModelPriceLookup {
    suspend fun list(): List<ModelPriceRecord> = suspendTransaction(database) {
        val rows = ModelPrices.selectAll().toList()
        val names = channelNames.names(rows.map { it[ModelPrices.channelId] }.toSet())
        rows.map { toRecord(it, names) }
            .sortedWith(compareBy({ it.channelName }, { it.upstreamModel }))
    }

    override suspend fun find(channelId: String, upstreamModel: String): ModelPriceRecord? =
        suspendTransaction(database) {
            val normalizedModel = upstreamModel.trim()
            if (normalizedModel.isEmpty()) return@suspendTransaction null
            ModelPrices.selectAll()
                .where { (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq normalizedModel) }
                .singleOrNull()
                ?.let { row -> toRecord(row, channelNames.names(setOf(row[ModelPrices.channelId]))) }
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

        val rows = ModelPrices.selectAll()
            .where {
                (ModelPrices.channelId eq channelId) and
                    (ModelPrices.upstreamModel inList normalizedModels)
            }
            .toList()
        val names = channelNames.names(rows.map { it[ModelPrices.channelId] }.toSet())
        rows.map { toRecord(it, names) }
            .sortedBy { it.upstreamModel }
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ModelPrices.deleteWhere { ModelPrices.id eq id } > 0
    }

    private fun load(id: String): ModelPriceRecord? =
        ModelPrices.selectAll()
            .where { ModelPrices.id eq id }
            .singleOrNull()
            ?.let { row -> toRecord(row, channelNames.names(setOf(row[ModelPrices.channelId]))) }

    private fun toRecord(row: ResultRow, names: Map<String, String>) = ModelPriceRecord(
        id = row[ModelPrices.id],
        channelId = row[ModelPrices.channelId],
        channelName = names[row[ModelPrices.channelId]] ?: row[ModelPrices.channelId],
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
