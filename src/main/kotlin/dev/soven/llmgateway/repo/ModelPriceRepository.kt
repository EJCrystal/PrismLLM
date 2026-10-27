package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.db.ModelPriceVersions
import dev.soven.llmgateway.db.ModelPrices
import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.ModelPriceTier
import dev.soven.llmgateway.domain.ModelPriceVersionRecord
import dev.soven.llmgateway.domain.PricingMode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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

    suspend fun findById(id: String): ModelPriceRecord? = suspendTransaction(database) { load(id) }

    suspend fun findEffective(
        channelId: String,
        upstreamModel: String,
        asOfTime: Long = System.currentTimeMillis(),
    ): ModelPriceVersionRecord? = suspendTransaction(database) {
        val normalizedModel = upstreamModel.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null
        ModelPriceVersions.selectAll()
            .where {
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.effectiveFrom lessEq asOfTime) and
                    (ModelPriceVersions.status neq "cancelled")
            }
            .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.DESC, ModelPriceVersions.version to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let(::toVersionRecord)
            ?: find(channelId, normalizedModel)?.toBaselineVersion()
    }

    suspend fun findScheduled(
        channelId: String,
        upstreamModel: String,
        asOfTime: Long = System.currentTimeMillis(),
    ): ModelPriceVersionRecord? = suspendTransaction(database) {
        val normalizedModel = upstreamModel.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null
        ModelPriceVersions.selectAll()
            .where {
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.effectiveFrom greater asOfTime) and
                    (ModelPriceVersions.status eq "scheduled")
            }
            .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let(::toVersionRecord)
    }

    suspend fun listVersions(
        channelId: String,
        upstreamModel: String,
    ): List<ModelPriceVersionRecord> = suspendTransaction(database) {
        val normalizedModel = upstreamModel.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction emptyList()
        ModelPriceVersions.selectAll()
            .where {
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.status neq "cancelled")
            }
            .orderBy(ModelPriceVersions.version to SortOrder.DESC)
            .map(::toVersionRecord)
    }

    suspend fun deleteVersion(versionId: String): Boolean = suspendTransaction(database) {
        val target = ModelPriceVersions.selectAll().where { ModelPriceVersions.id eq versionId }.singleOrNull()
            ?: return@suspendTransaction false
        val channelId = target[ModelPriceVersions.channelId]
        val upstreamModel = target[ModelPriceVersions.upstreamModel]
        val deleted = ModelPriceVersions.deleteWhere { ModelPriceVersions.id eq versionId } > 0
        if (deleted) {
            val active = ModelPriceVersions.selectAll()
                .where {
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.effectiveFrom lessEq System.currentTimeMillis()) and
                        (ModelPriceVersions.status neq "cancelled")
                }
                .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.DESC, ModelPriceVersions.version to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            if (active != null) {
                ModelPrices.update({ (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq upstreamModel) }) {
                    it[currency] = active[ModelPriceVersions.currency]
                    it[inputPricePerMillion] = active[ModelPriceVersions.inputPricePerMillion]
                    it[outputPricePerMillion] = active[ModelPriceVersions.outputPricePerMillion]
                    it[cachedInputPricePerMillion] = active[ModelPriceVersions.cachedInputPricePerMillion]
                    it[cachedInputWritePricePerMillion] = active[ModelPriceVersions.cachedInputWritePricePerMillion]
                    it[pricingMode] = active[ModelPriceVersions.pricingMode]
                    it[perRequestPrice] = active[ModelPriceVersions.perRequestPrice]
                    it[priceTiersJson] = active[ModelPriceVersions.priceTiersJson]
                    it[currentVersion] = active[ModelPriceVersions.version]
                    it[effectiveFrom] = active[ModelPriceVersions.effectiveFrom]
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
        }
        deleted
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
        effectiveAt: Long? = null,
    ): ModelPriceRecord = suspendTransaction(database) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now
        val normalizedModel = upstreamModel.trim()

        val maxVersion = ModelPriceVersions.selectAll()
            .where { (ModelPriceVersions.channelId eq channelId) and (ModelPriceVersions.upstreamModel eq normalizedModel) }
            .maxOfOrNull { it[ModelPriceVersions.version] } ?: 0
        val nextVersion = maxVersion + 1

        val versionId = UUID.randomUUID().toString()
        ModelPriceVersions.insert {
            it[ModelPriceVersions.id] = versionId
            it[ModelPriceVersions.channelId] = channelId
            it[ModelPriceVersions.upstreamModel] = normalizedModel
            it[version] = nextVersion
            it[ModelPriceVersions.currency] = currency
            it[ModelPriceVersions.inputPricePerMillion] = inputPricePerMillion
            it[ModelPriceVersions.outputPricePerMillion] = outputPricePerMillion
            it[ModelPriceVersions.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelPriceVersions.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelPriceVersions.pricingMode] = pricingMode.value
            it[ModelPriceVersions.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[ModelPriceVersions.effectiveFrom] = effectiveFrom
            it[status] = if (isScheduled) "scheduled" else "active"
            it[createdAt] = now
        }

        val existing = ModelPrices.selectAll()
            .where { (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq normalizedModel) }
            .singleOrNull()

        val priceId = if (existing == null) {
            ModelPrices.insert {
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
                it[currentVersion] = nextVersion
                it[ModelPrices.effectiveFrom] = effectiveFrom
                it[createdAt] = now
                it[updatedAt] = now
            }
            id
        } else {
            val existingId = existing[ModelPrices.id]
            if (!isScheduled) {
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq normalizedModel) and
                        (ModelPriceVersions.version neq nextVersion) and
                        (ModelPriceVersions.status eq "active")
                }) {
                    it[status] = "archived"
                }
                ModelPrices.update({ ModelPrices.id eq existingId }) {
                    it[ModelPrices.currency] = currency
                    it[ModelPrices.inputPricePerMillion] = inputPricePerMillion
                    it[ModelPrices.outputPricePerMillion] = outputPricePerMillion
                    it[ModelPrices.cachedInputPricePerMillion] = cachedInputPricePerMillion
                    it[ModelPrices.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
                    it[ModelPrices.pricingMode] = pricingMode.value
                    it[ModelPrices.perRequestPrice] = perRequestPrice
                    it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
                    it[currentVersion] = nextVersion
                    it[ModelPrices.effectiveFrom] = effectiveFrom
                    it[updatedAt] = now
                }
            }
            existingId
        }
        checkNotNull(load(priceId))
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
        effectiveAt: Long? = null,
    ): ModelPriceRecord? = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now
        val normalizedModel = upstreamModel.trim()

        val maxVersion = ModelPriceVersions.selectAll()
            .where { (ModelPriceVersions.channelId eq channelId) and (ModelPriceVersions.upstreamModel eq normalizedModel) }
            .maxOfOrNull { it[ModelPriceVersions.version] } ?: 0
        val nextVersion = maxVersion + 1

        val versionId = UUID.randomUUID().toString()
        ModelPriceVersions.insert {
            it[ModelPriceVersions.id] = versionId
            it[ModelPriceVersions.channelId] = channelId
            it[ModelPriceVersions.upstreamModel] = normalizedModel
            it[version] = nextVersion
            it[ModelPriceVersions.currency] = currency
            it[ModelPriceVersions.inputPricePerMillion] = inputPricePerMillion
            it[ModelPriceVersions.outputPricePerMillion] = outputPricePerMillion
            it[ModelPriceVersions.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelPriceVersions.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelPriceVersions.pricingMode] = pricingMode.value
            it[ModelPriceVersions.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[ModelPriceVersions.effectiveFrom] = effectiveFrom
            it[status] = if (isScheduled) "scheduled" else "active"
            it[createdAt] = now
        }

        if (!isScheduled) {
            ModelPriceVersions.update({
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.version neq nextVersion) and
                    (ModelPriceVersions.status eq "active")
            }) {
                it[status] = "archived"
            }
            val changed = ModelPrices.update({ ModelPrices.id eq id }) {
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
                it[currentVersion] = nextVersion
                it[ModelPrices.effectiveFrom] = effectiveFrom
                it[updatedAt] = now
            }
            if (changed == 0) null else load(id)
        } else {
            load(id)
        }
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
        effectiveAt: Long? = null,
    ): List<ModelPriceRecord> = suspendTransaction(database) {
        val normalizedModels = upstreamModels
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedModels.isEmpty()) return@suspendTransaction emptyList()

        val now = System.currentTimeMillis()
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now

        for (upstreamModel in normalizedModels) {
            val maxVersion = ModelPriceVersions.selectAll()
                .where { (ModelPriceVersions.channelId eq channelId) and (ModelPriceVersions.upstreamModel eq upstreamModel) }
                .maxOfOrNull { it[ModelPriceVersions.version] } ?: 0
            val nextVersion = maxVersion + 1

            ModelPriceVersions.insert {
                it[id] = UUID.randomUUID().toString()
                it[ModelPriceVersions.channelId] = channelId
                it[ModelPriceVersions.upstreamModel] = upstreamModel
                it[version] = nextVersion
                it[ModelPriceVersions.currency] = currency
                it[ModelPriceVersions.inputPricePerMillion] = inputPricePerMillion
                it[ModelPriceVersions.outputPricePerMillion] = outputPricePerMillion
                it[ModelPriceVersions.cachedInputPricePerMillion] = cachedInputPricePerMillion
                it[ModelPriceVersions.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
                it[ModelPriceVersions.pricingMode] = pricingMode.value
                it[ModelPriceVersions.perRequestPrice] = perRequestPrice
                it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
                it[ModelPriceVersions.effectiveFrom] = effectiveFrom
                it[status] = if (isScheduled) "scheduled" else "active"
                it[createdAt] = now
            }

            if (!isScheduled) {
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.version neq nextVersion) and
                        (ModelPriceVersions.status eq "active")
                }) {
                    it[status] = "archived"
                }

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
                    it[currentVersion] = nextVersion
                    it[ModelPrices.effectiveFrom] = effectiveFrom
                    it[createdAt] = now
                    it[updatedAt] = now
                }
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
        currentVersion = row[ModelPrices.currentVersion],
        effectiveFrom = row[ModelPrices.effectiveFrom],
        createdAt = row[ModelPrices.createdAt],
        updatedAt = row[ModelPrices.updatedAt],
    )

    private fun toVersionRecord(row: ResultRow) = ModelPriceVersionRecord(
        id = row[ModelPriceVersions.id],
        channelId = row[ModelPriceVersions.channelId],
        upstreamModel = row[ModelPriceVersions.upstreamModel],
        version = row[ModelPriceVersions.version],
        currency = row[ModelPriceVersions.currency],
        inputPricePerMillion = row[ModelPriceVersions.inputPricePerMillion],
        outputPricePerMillion = row[ModelPriceVersions.outputPricePerMillion],
        cachedInputPricePerMillion = row[ModelPriceVersions.cachedInputPricePerMillion],
        cachedInputWritePricePerMillion = row[ModelPriceVersions.cachedInputWritePricePerMillion],
        pricingMode = PricingMode.fromValue(row[ModelPriceVersions.pricingMode]),
        perRequestPrice = row[ModelPriceVersions.perRequestPrice],
        priceTiers = PricingTierCodec.decode(row[ModelPriceVersions.priceTiersJson]),
        effectiveFrom = row[ModelPriceVersions.effectiveFrom],
        status = row[ModelPriceVersions.status],
        createdAt = row[ModelPriceVersions.createdAt],
    )

    private fun ModelPriceRecord.toBaselineVersion() = ModelPriceVersionRecord(
        id = id,
        channelId = channelId,
        upstreamModel = upstreamModel,
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
