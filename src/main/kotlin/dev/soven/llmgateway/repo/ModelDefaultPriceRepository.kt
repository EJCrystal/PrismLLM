package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ModelDefaultPriceVersions
import dev.soven.llmgateway.db.ModelDefaultPrices
import dev.soven.llmgateway.domain.DefaultModelPriceRecord
import dev.soven.llmgateway.domain.DefaultModelPriceVersionRecord
import dev.soven.llmgateway.domain.ModelPriceTier
import dev.soven.llmgateway.domain.PricingMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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
class ModelDefaultPriceRepository(private val database: Database) {
    suspend fun list(): List<DefaultModelPriceRecord> = suspendTransaction(database) {
        ModelDefaultPrices.selectAll()
            .map(::toRecord)
            .sortedBy { it.modelId }
    }

    suspend fun find(modelId: String): DefaultModelPriceRecord? = suspendTransaction(database) {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return@suspendTransaction null
        ModelDefaultPrices.selectAll()
            .where { ModelDefaultPrices.modelId eq normalized }
            .singleOrNull()
            ?.let(::toRecord)
    }

    suspend fun findById(id: String): DefaultModelPriceRecord? = suspendTransaction(database) {
        ModelDefaultPrices.selectAll()
            .where { ModelDefaultPrices.id eq id }
            .singleOrNull()
            ?.let(::toRecord)
    }

    suspend fun findEffective(
        modelId: String,
        asOfTime: Long = System.currentTimeMillis(),
    ): DefaultModelPriceVersionRecord? = suspendTransaction(database) {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return@suspendTransaction null
        ModelDefaultPriceVersions.selectAll()
            .where {
                (ModelDefaultPriceVersions.modelId eq normalized) and
                    (ModelDefaultPriceVersions.effectiveFrom lessEq asOfTime) and
                    (ModelDefaultPriceVersions.status neq "cancelled")
            }
            .orderBy(ModelDefaultPriceVersions.effectiveFrom to SortOrder.DESC, ModelDefaultPriceVersions.version to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let(::toVersionRecord)
            ?: find(normalized)?.takeIf { it.effectiveFrom <= asOfTime }?.toBaselineVersion()
    }

    suspend fun findScheduled(
        modelId: String,
        asOfTime: Long = System.currentTimeMillis(),
    ): DefaultModelPriceVersionRecord? = suspendTransaction(database) {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return@suspendTransaction null
        ModelDefaultPriceVersions.selectAll()
            .where {
                (ModelDefaultPriceVersions.modelId eq normalized) and
                    (ModelDefaultPriceVersions.effectiveFrom greater asOfTime) and
                    (ModelDefaultPriceVersions.status eq "scheduled")
            }
            .orderBy(ModelDefaultPriceVersions.effectiveFrom to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let(::toVersionRecord)
    }

    suspend fun listVersions(modelId: String): List<DefaultModelPriceVersionRecord> = suspendTransaction(database) {
        val normalized = modelId.trim()
        if (normalized.isEmpty()) return@suspendTransaction emptyList()
        ModelDefaultPriceVersions.selectAll()
            .where {
                (ModelDefaultPriceVersions.modelId eq normalized) and
                    (ModelDefaultPriceVersions.status neq "cancelled")
            }
            .orderBy(ModelDefaultPriceVersions.version to SortOrder.DESC)
            .map(::toVersionRecord)
    }

    suspend fun deleteVersion(versionId: String): Boolean = suspendTransaction(database) {
        val target = ModelDefaultPriceVersions.selectAll().where { ModelDefaultPriceVersions.id eq versionId }.singleOrNull()
            ?: return@suspendTransaction false
        val modelId = target[ModelDefaultPriceVersions.modelId]
        val deleted = ModelDefaultPriceVersions.deleteWhere { ModelDefaultPriceVersions.id eq versionId } > 0
        if (deleted) {
            val now = System.currentTimeMillis()
            val active = ModelDefaultPriceVersions.selectAll()
                .where {
                    (ModelDefaultPriceVersions.modelId eq modelId) and
                        (ModelDefaultPriceVersions.effectiveFrom lessEq now) and
                        (ModelDefaultPriceVersions.status neq "cancelled")
                }
                .orderBy(ModelDefaultPriceVersions.effectiveFrom to SortOrder.DESC, ModelDefaultPriceVersions.version to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            if (active != null) {
                val activeVersion = active[ModelDefaultPriceVersions.version]
                ModelDefaultPriceVersions.update({
                    (ModelDefaultPriceVersions.modelId eq modelId) and
                        (ModelDefaultPriceVersions.version eq activeVersion)
                }) {
                    it[status] = "active"
                }
                ModelDefaultPrices.update({ ModelDefaultPrices.modelId eq modelId }) {
                    it[currency] = active[ModelDefaultPriceVersions.currency]
                    it[inputPricePerMillion] = active[ModelDefaultPriceVersions.inputPricePerMillion]
                    it[outputPricePerMillion] = active[ModelDefaultPriceVersions.outputPricePerMillion]
                    it[cachedInputPricePerMillion] = active[ModelDefaultPriceVersions.cachedInputPricePerMillion]
                    it[cachedInputWritePricePerMillion] = active[ModelDefaultPriceVersions.cachedInputWritePricePerMillion]
                    it[pricingMode] = active[ModelDefaultPriceVersions.pricingMode]
                    it[perRequestPrice] = active[ModelDefaultPriceVersions.perRequestPrice]
                    it[priceTiersJson] = active[ModelDefaultPriceVersions.priceTiersJson]
                    it[currentVersion] = activeVersion
                    it[effectiveFrom] = active[ModelDefaultPriceVersions.effectiveFrom]
                    it[updatedAt] = now
                }
            } else {
                val anyRemaining = ModelDefaultPriceVersions.selectAll()
                    .where { ModelDefaultPriceVersions.modelId eq modelId }
                    .orderBy(ModelDefaultPriceVersions.effectiveFrom to SortOrder.ASC)
                    .limit(1)
                    .singleOrNull()
                if (anyRemaining == null) {
                    ModelDefaultPrices.deleteWhere { ModelDefaultPrices.modelId eq modelId }
                } else {
                    ModelDefaultPrices.update({ ModelDefaultPrices.modelId eq modelId }) {
                        it[currentVersion] = anyRemaining[ModelDefaultPriceVersions.version]
                        it[effectiveFrom] = anyRemaining[ModelDefaultPriceVersions.effectiveFrom]
                        it[updatedAt] = now
                    }
                }
            }
        }
        deleted
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
        effectiveAt: Long? = null,
    ): DefaultModelPriceRecord? = suspendTransaction(database) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now
        val normalizedModel = modelId.trim()

        val maxVersion = ModelDefaultPriceVersions.selectAll()
            .where { ModelDefaultPriceVersions.modelId eq normalizedModel }
            .maxOfOrNull { it[ModelDefaultPriceVersions.version] } ?: 0
        val nextVersion = maxVersion + 1

        val versionId = UUID.randomUUID().toString()
        ModelDefaultPriceVersions.insert {
            it[ModelDefaultPriceVersions.id] = versionId
            it[ModelDefaultPriceVersions.modelId] = normalizedModel
            it[version] = nextVersion
            it[ModelDefaultPriceVersions.currency] = currency
            it[ModelDefaultPriceVersions.inputPricePerMillion] = inputPricePerMillion
            it[ModelDefaultPriceVersions.outputPricePerMillion] = outputPricePerMillion
            it[ModelDefaultPriceVersions.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelDefaultPriceVersions.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelDefaultPriceVersions.pricingMode] = pricingMode.value
            it[ModelDefaultPriceVersions.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[ModelDefaultPriceVersions.effectiveFrom] = effectiveFrom
            it[status] = if (isScheduled) "scheduled" else "active"
            it[createdAt] = now
        }

        val existing = ModelDefaultPrices.selectAll()
            .where { ModelDefaultPrices.modelId eq normalizedModel }
            .singleOrNull()

        if (existing != null && !isScheduled) {
            return@suspendTransaction null
        }

        val priceId = if (existing == null) {
            ModelDefaultPrices.insertIgnore {
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
                it[currentVersion] = nextVersion
                it[ModelDefaultPrices.effectiveFrom] = effectiveFrom
                it[createdAt] = now
                it[updatedAt] = now
            }
            id
        } else {
            val existingId = existing[ModelDefaultPrices.id]
            if (!isScheduled) {
                ModelDefaultPriceVersions.update({
                    (ModelDefaultPriceVersions.modelId eq normalizedModel) and
                        (ModelDefaultPriceVersions.version neq nextVersion) and
                        (ModelDefaultPriceVersions.status eq "active")
                }) {
                    it[status] = "archived"
                }
                ModelDefaultPrices.update({ ModelDefaultPrices.id eq existingId }) {
                    it[ModelDefaultPrices.currency] = currency
                    it[ModelDefaultPrices.inputPricePerMillion] = inputPricePerMillion
                    it[ModelDefaultPrices.outputPricePerMillion] = outputPricePerMillion
                    it[ModelDefaultPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
                    it[ModelDefaultPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
                    it[ModelDefaultPrices.pricingMode] = pricingMode.value
                    it[ModelDefaultPrices.perRequestPrice] = perRequestPrice
                    it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
                    it[currentVersion] = nextVersion
                    it[ModelDefaultPrices.effectiveFrom] = effectiveFrom
                    it[updatedAt] = now
                }
            }
            existingId
        }
        load(priceId)
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
        effectiveAt: Long? = null,
    ): DefaultModelPriceRecord? = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now
        val normalizedModel = modelId.trim()

        val maxVersion = ModelDefaultPriceVersions.selectAll()
            .where { ModelDefaultPriceVersions.modelId eq normalizedModel }
            .maxOfOrNull { it[ModelDefaultPriceVersions.version] } ?: 0
        val nextVersion = maxVersion + 1

        val versionId = UUID.randomUUID().toString()
        ModelDefaultPriceVersions.insert {
            it[ModelDefaultPriceVersions.id] = versionId
            it[ModelDefaultPriceVersions.modelId] = normalizedModel
            it[version] = nextVersion
            it[ModelDefaultPriceVersions.currency] = currency
            it[ModelDefaultPriceVersions.inputPricePerMillion] = inputPricePerMillion
            it[ModelDefaultPriceVersions.outputPricePerMillion] = outputPricePerMillion
            it[ModelDefaultPriceVersions.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelDefaultPriceVersions.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelDefaultPriceVersions.pricingMode] = pricingMode.value
            it[ModelDefaultPriceVersions.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[ModelDefaultPriceVersions.effectiveFrom] = effectiveFrom
            it[status] = if (isScheduled) "scheduled" else "active"
            it[createdAt] = now
        }

        if (!isScheduled) {
            ModelDefaultPriceVersions.update({
                (ModelDefaultPriceVersions.modelId eq normalizedModel) and
                    (ModelDefaultPriceVersions.version neq nextVersion) and
                    (ModelDefaultPriceVersions.status eq "active")
            }) {
                it[status] = "archived"
            }
            val changed = ModelDefaultPrices.update({ ModelDefaultPrices.id eq id }) {
                it[ModelDefaultPrices.modelId] = normalizedModel
                it[ModelDefaultPrices.currency] = currency
                it[ModelDefaultPrices.inputPricePerMillion] = inputPricePerMillion
                it[ModelDefaultPrices.outputPricePerMillion] = outputPricePerMillion
                it[ModelDefaultPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
                it[ModelDefaultPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
                it[ModelDefaultPrices.pricingMode] = pricingMode.value
                it[ModelDefaultPrices.perRequestPrice] = perRequestPrice
                it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
                it[currentVersion] = nextVersion
                it[ModelDefaultPrices.effectiveFrom] = effectiveFrom
                it[updatedAt] = now
            }
            if (changed == 0) null else load(id)
        } else {
            load(id)
        }
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
        currentVersion = row[ModelDefaultPrices.currentVersion],
        effectiveFrom = row[ModelDefaultPrices.effectiveFrom],
        createdAt = row[ModelDefaultPrices.createdAt],
        updatedAt = row[ModelDefaultPrices.updatedAt],
    )

    private fun toVersionRecord(row: ResultRow) = DefaultModelPriceVersionRecord(
        id = row[ModelDefaultPriceVersions.id],
        modelId = row[ModelDefaultPriceVersions.modelId],
        version = row[ModelDefaultPriceVersions.version],
        currency = row[ModelDefaultPriceVersions.currency],
        inputPricePerMillion = row[ModelDefaultPriceVersions.inputPricePerMillion],
        outputPricePerMillion = row[ModelDefaultPriceVersions.outputPricePerMillion],
        cachedInputPricePerMillion = row[ModelDefaultPriceVersions.cachedInputPricePerMillion],
        cachedInputWritePricePerMillion = row[ModelDefaultPriceVersions.cachedInputWritePricePerMillion],
        pricingMode = PricingMode.fromValue(row[ModelDefaultPriceVersions.pricingMode]),
        perRequestPrice = row[ModelDefaultPriceVersions.perRequestPrice],
        priceTiers = PricingTierCodec.decode(row[ModelDefaultPriceVersions.priceTiersJson]),
        effectiveFrom = row[ModelDefaultPriceVersions.effectiveFrom],
        status = row[ModelDefaultPriceVersions.status],
        createdAt = row[ModelDefaultPriceVersions.createdAt],
    )

    private fun DefaultModelPriceRecord.toBaselineVersion() = DefaultModelPriceVersionRecord(
        id = id,
        modelId = modelId,
        version = currentVersion,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        cachedInputPricePerMillion = cachedInputPricePerMillion,
        cachedInputWritePricePerMillion = cachedInputWritePricePerMillion,
        pricingMode = pricingMode,
        perRequestPrice = perRequestPrice,
        priceTiers = priceTiers,
        effectiveFrom = effectiveFrom,
        status = "active",
        createdAt = createdAt,
    )
}
