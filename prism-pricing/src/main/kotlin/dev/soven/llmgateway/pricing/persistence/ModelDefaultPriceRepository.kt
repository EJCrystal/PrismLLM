package dev.soven.llmgateway.pricing.persistence

import dev.soven.llmgateway.pricing.domain.DefaultModelPriceLookup
import dev.soven.llmgateway.pricing.domain.DefaultModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.UUID

/**
 * 模型默认单价的持久化。默认价按对外模型 ID 唯一，渠道价优先于默认价。
 *
 * @author 文杰
 * @since 2026-10-27
 */
class ModelDefaultPriceRepository(private val database: Database) : DefaultModelPriceLookup {
    suspend fun list(): List<DefaultModelPriceRecord> = suspendTransaction(database) {
        ModelDefaultPrices.selectAll()
            .map(::toRecord)
            .sortedBy { it.modelId }
    }

    override suspend fun find(modelId: String): DefaultModelPriceRecord? = suspendTransaction(database) {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return@suspendTransaction null
        ModelDefaultPrices.selectAll()
            .where { ModelDefaultPrices.modelId eq normalized }
            .singleOrNull()
            ?.let(::toRecord)
    }

    suspend fun findById(id: String): DefaultModelPriceRecord? = suspendTransaction(database) {
        load(id)
    }

    suspend fun create(
        modelId: String,
        currency: String,
        inputPricePerMillion: BigDecimal,
        outputPricePerMillion: BigDecimal,
        cachedInputPricePerMillion: BigDecimal? = null,
        cachedInputWritePricePerMillion: BigDecimal? = null,
        pricingMode: PricingMode = PricingMode.TOKEN,
        perRequestPrice: BigDecimal? = null,
        priceTiers: List<ModelPriceTier> = emptyList(),
    ): DefaultModelPriceRecord? = suspendTransaction(database) {
        val normalizedModel = modelId.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val inserted = ModelDefaultPrices.insertIgnore {
            it[ModelDefaultPrices.id] = id
            it[ModelDefaultPrices.modelId] = normalizedModel
            it[ModelDefaultPrices.currency] = currency
            it[ModelDefaultPrices.inputPricePerMillion] = inputPricePerMillion
            it[ModelDefaultPrices.outputPricePerMillion] = outputPricePerMillion
            it[ModelDefaultPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelDefaultPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelDefaultPrices.pricingMode] = pricingMode.value
            it[ModelDefaultPrices.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[createdAt] = now
            it[updatedAt] = now
        }.insertedCount > 0
        if (!inserted) return@suspendTransaction null
        load(id)
    }

    suspend fun update(
        id: String,
        modelId: String,
        currency: String,
        inputPricePerMillion: BigDecimal,
        outputPricePerMillion: BigDecimal,
        cachedInputPricePerMillion: BigDecimal? = null,
        cachedInputWritePricePerMillion: BigDecimal? = null,
        pricingMode: PricingMode = PricingMode.TOKEN,
        perRequestPrice: BigDecimal? = null,
        priceTiers: List<ModelPriceTier> = emptyList(),
    ): DefaultModelPriceRecord? = suspendTransaction(database) {
        val normalizedModel = modelId.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null

        val existing = ModelDefaultPrices.selectAll()
            .where { ModelDefaultPrices.id eq id }
            .forUpdate()
            .singleOrNull() ?: return@suspendTransaction null

        if (existing[ModelDefaultPrices.modelId] != normalizedModel) {
            return@suspendTransaction null
        }

        val now = System.currentTimeMillis()
        val changed = ModelDefaultPrices.update({ ModelDefaultPrices.id eq id }) {
            it[ModelDefaultPrices.currency] = currency
            it[ModelDefaultPrices.inputPricePerMillion] = inputPricePerMillion
            it[ModelDefaultPrices.outputPricePerMillion] = outputPricePerMillion
            it[ModelDefaultPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelDefaultPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelDefaultPrices.pricingMode] = pricingMode.value
            it[ModelDefaultPrices.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[updatedAt] = now
        }
        if (changed == 0) null else load(id)
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ModelDefaultPrices.deleteWhere { ModelDefaultPrices.id eq id } > 0
    }

    private fun load(id: String): DefaultModelPriceRecord? =
        ModelDefaultPrices.selectAll()
            .where { ModelDefaultPrices.id eq id }
            .singleOrNull()
            ?.let(::toRecord)

    private fun toRecord(row: ResultRow) = DefaultModelPriceRecord(
        id = row[ModelDefaultPrices.id],
        modelId = row[ModelDefaultPrices.modelId],
        currency = row[ModelDefaultPrices.currency],
        inputPricePerMillion = row[ModelDefaultPrices.inputPricePerMillion],
        outputPricePerMillion = row[ModelDefaultPrices.outputPricePerMillion],
        cachedInputPricePerMillion = row[ModelDefaultPrices.cachedInputPricePerMillion],
        cachedInputWritePricePerMillion = row[ModelDefaultPrices.cachedInputWritePricePerMillion],
        pricingMode = PricingMode.fromValue(row[ModelDefaultPrices.pricingMode]),
        perRequestPrice = row[ModelDefaultPrices.perRequestPrice],
        priceTiers = PricingTierCodec.decode(row[ModelDefaultPrices.priceTiersJson]),
        createdAt = row[ModelDefaultPrices.createdAt],
        updatedAt = row[ModelDefaultPrices.updatedAt],
    )
}
