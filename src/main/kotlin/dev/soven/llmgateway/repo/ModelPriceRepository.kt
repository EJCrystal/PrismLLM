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
        val now = System.currentTimeMillis()
        ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .map { row -> toRecord(row, resolveCurrentVersion(row[ModelPrices.channelId], row[ModelPrices.upstreamModel], now)) }
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
        val row = ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .where { ModelPrices.id eq id }
            .singleOrNull()
            ?: return@suspendTransaction null
        toRecord(
            row,
            resolveCurrentVersion(
                row[ModelPrices.channelId],
                row[ModelPrices.upstreamModel],
                System.currentTimeMillis(),
            ),
        )
    }

    private fun resolveCurrentVersion(
        channelId: String,
        upstreamModel: String,
        asOfTime: Long,
    ): ModelPriceVersionRecord? = ModelPriceVersions.selectAll()
        .where {
            (ModelPriceVersions.channelId eq channelId) and
                (ModelPriceVersions.upstreamModel eq upstreamModel) and
                (ModelPriceVersions.effectiveFrom lessEq asOfTime) and
                (ModelPriceVersions.status neq "cancelled")
        }
        .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.DESC, ModelPriceVersions.version to SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.let(::toVersionRecord)

    private fun findPendingVersion(
        channelId: String,
        upstreamModel: String,
        asOfTime: Long,
    ): ModelPriceVersionRecord? = ModelPriceVersions.selectAll()
        .where {
            (ModelPriceVersions.channelId eq channelId) and
                (ModelPriceVersions.upstreamModel eq upstreamModel) and
                (ModelPriceVersions.effectiveFrom greater asOfTime) and
                (ModelPriceVersions.status eq "scheduled")
        }
        .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.ASC)
        .limit(1)
        .singleOrNull()
        ?.let(::toVersionRecord)

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
            val now = System.currentTimeMillis()
            val remainingActive = ModelPriceVersions.selectAll()
                .where {
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.effectiveFrom lessEq now) and
                        (ModelPriceVersions.status neq "cancelled")
                }
                .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.DESC, ModelPriceVersions.version to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
            if (remainingActive != null) {
                val activeVersion = remainingActive[ModelPriceVersions.version]
                val activeVersionId = remainingActive[ModelPriceVersions.id]
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.version eq activeVersion)
                }) {
                    it[status] = "active"
                }
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.id neq activeVersionId) and
                        (ModelPriceVersions.effectiveFrom lessEq now) and
                        (ModelPriceVersions.status eq "active")
                }) {
                    it[status] = "archived"
                }
                ModelPrices.update({ (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq upstreamModel) }) {
                    it[currency] = remainingActive[ModelPriceVersions.currency]
                    it[inputPricePerMillion] = remainingActive[ModelPriceVersions.inputPricePerMillion]
                    it[outputPricePerMillion] = remainingActive[ModelPriceVersions.outputPricePerMillion]
                    it[cachedInputPricePerMillion] = remainingActive[ModelPriceVersions.cachedInputPricePerMillion]
                    it[cachedInputWritePricePerMillion] = remainingActive[ModelPriceVersions.cachedInputWritePricePerMillion]
                    it[pricingMode] = remainingActive[ModelPriceVersions.pricingMode]
                    it[perRequestPrice] = remainingActive[ModelPriceVersions.perRequestPrice]
                    it[priceTiersJson] = remainingActive[ModelPriceVersions.priceTiersJson]
                    it[currentVersion] = activeVersion
                    it[effectiveFrom] = remainingActive[ModelPriceVersions.effectiveFrom]
                    it[updatedAt] = now
                }
            } else {
                val anyRemaining = ModelPriceVersions.selectAll()
                    .where {
                        (ModelPriceVersions.channelId eq channelId) and
                            (ModelPriceVersions.upstreamModel eq upstreamModel) and
                            (ModelPriceVersions.effectiveFrom greater now) and
                            (ModelPriceVersions.status neq "cancelled")
                    }
                    .orderBy(ModelPriceVersions.effectiveFrom to SortOrder.ASC)
                    .limit(1)
                    .singleOrNull()
                if (anyRemaining == null) {
                    ModelPrices.deleteWhere { (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq upstreamModel) }
                } else {
                    ModelPriceVersions.update({
                        (ModelPriceVersions.channelId eq channelId) and
                            (ModelPriceVersions.upstreamModel eq upstreamModel) and
                            (ModelPriceVersions.version eq anyRemaining[ModelPriceVersions.version])
                    }) {
                        it[status] = "active"
                    }
                    ModelPrices.update({ (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq upstreamModel) }) {
                        it[currency] = anyRemaining[ModelPriceVersions.currency]
                        it[inputPricePerMillion] = anyRemaining[ModelPriceVersions.inputPricePerMillion]
                        it[outputPricePerMillion] = anyRemaining[ModelPriceVersions.outputPricePerMillion]
                        it[cachedInputPricePerMillion] = anyRemaining[ModelPriceVersions.cachedInputPricePerMillion]
                        it[cachedInputWritePricePerMillion] = anyRemaining[ModelPriceVersions.cachedInputWritePricePerMillion]
                        it[pricingMode] = anyRemaining[ModelPriceVersions.pricingMode]
                        it[perRequestPrice] = anyRemaining[ModelPriceVersions.perRequestPrice]
                        it[priceTiersJson] = anyRemaining[ModelPriceVersions.priceTiersJson]
                        it[currentVersion] = anyRemaining[ModelPriceVersions.version]
                        it[effectiveFrom] = anyRemaining[ModelPriceVersions.effectiveFrom]
                        it[updatedAt] = now
                    }
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
    ): ModelPriceRecord? = suspendTransaction(database) {
        val normalizedModel = upstreamModel.trim()
        if (normalizedModel.isEmpty()) return@suspendTransaction null

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now

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
            it[currentVersion] = 1
            it[ModelPrices.effectiveFrom] = effectiveFrom
            it[createdAt] = now
            it[updatedAt] = now
        }.insertedCount > 0
        if (!inserted) return@suspendTransaction null
        insertVersion(
            channelId, normalizedModel, 1, effectiveFrom,
            if (isScheduled) "scheduled" else "active", now, currency,
            inputPricePerMillion, outputPricePerMillion, cachedInputPricePerMillion,
            cachedInputWritePricePerMillion, pricingMode, perRequestPrice, priceTiers,
        )
        checkNotNull(load(id))
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
        preserveSchedule: Boolean = false,
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
        val effectiveFrom = effectiveAt?.takeIf { it > 0 } ?: now
        val isScheduled = effectiveFrom > now

        if (preserveSchedule) {
            val current = resolveCurrentVersion(channelId, normalizedModel, now)
            val pending = findPendingVersion(channelId, normalizedModel, now)
            if (current == null || pending == null) return@suspendTransaction null
            return@suspendTransaction load(id)
        }

        if (!isScheduled) {
            val nowUpdate = System.currentTimeMillis()
            ModelPriceVersions.update({
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.effectiveFrom lessEq nowUpdate) and
                    (ModelPriceVersions.status neq "cancelled")
            }) {
                it[status] = "archived"
            }
            ModelPriceVersions.update({
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.effectiveFrom greater nowUpdate) and
                    (ModelPriceVersions.status neq "cancelled")
            }) {
                it[status] = "cancelled"
            }
            val nextVersion = nextVersion(channelId, normalizedModel)
            insertVersion(
                channelId, normalizedModel, nextVersion, effectiveFrom, "active", now, currency,
                inputPricePerMillion, outputPricePerMillion, cachedInputPricePerMillion,
                cachedInputWritePricePerMillion, pricingMode, perRequestPrice, priceTiers,
            )
            val changed = ModelPrices.update({ ModelPrices.id eq id }) {
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
            val nowUpdate = System.currentTimeMillis()
            ModelPriceVersions.update({
                (ModelPriceVersions.channelId eq channelId) and
                    (ModelPriceVersions.upstreamModel eq normalizedModel) and
                    (ModelPriceVersions.effectiveFrom greater nowUpdate) and
                    (ModelPriceVersions.status neq "cancelled")
            }) {
                it[status] = "cancelled"
            }
            val nextVersion = nextVersion(channelId, normalizedModel)
            insertVersion(
                channelId, normalizedModel, nextVersion, effectiveFrom, "scheduled", now, currency,
                inputPricePerMillion, outputPricePerMillion, cachedInputPricePerMillion,
                cachedInputWritePricePerMillion, pricingMode, perRequestPrice, priceTiers,
            )
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
            if (!isScheduled) {
                val nowUpdate = System.currentTimeMillis()
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.effectiveFrom lessEq nowUpdate) and
                        (ModelPriceVersions.status neq "cancelled")
                }) {
                    it[status] = "archived"
                }
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.effectiveFrom greater nowUpdate) and
                        (ModelPriceVersions.status neq "cancelled")
                }) {
                    it[status] = "cancelled"
                }
                val nextVersion = nextVersion(channelId, upstreamModel)
                insertVersion(
                    channelId, upstreamModel, nextVersion, effectiveFrom, "active", now, currency,
                    inputPricePerMillion, outputPricePerMillion, cachedInputPricePerMillion,
                    cachedInputWritePricePerMillion, pricingMode, perRequestPrice, priceTiers,
                )

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
            } else {
                val nowUpdate = System.currentTimeMillis()
                ModelPriceVersions.update({
                    (ModelPriceVersions.channelId eq channelId) and
                        (ModelPriceVersions.upstreamModel eq upstreamModel) and
                        (ModelPriceVersions.effectiveFrom greater nowUpdate) and
                        (ModelPriceVersions.status neq "cancelled")
                }) {
                    it[status] = "cancelled"
                }
                val nextVersion = nextVersion(channelId, upstreamModel)
                insertVersion(
                    channelId, upstreamModel, nextVersion, effectiveFrom, "scheduled", now, currency,
                    inputPricePerMillion, outputPricePerMillion, cachedInputPricePerMillion,
                    cachedInputWritePricePerMillion, pricingMode, perRequestPrice, priceTiers,
                )
                val hasCurrentPrice = ModelPrices.selectAll()
                    .where { (ModelPrices.channelId eq channelId) and (ModelPrices.upstreamModel eq upstreamModel) }
                    .count() > 0
                if (!hasCurrentPrice) {
                    ModelPrices.insert {
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
        }

        ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .where {
                (ModelPrices.channelId eq channelId) and
                    (ModelPrices.upstreamModel inList normalizedModels)
            }
            .map { row -> toRecord(row, resolveCurrentVersion(channelId, row[ModelPrices.upstreamModel], now)) }
            .sortedBy { it.upstreamModel }
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        val target = ModelPrices.selectAll().where { ModelPrices.id eq id }.singleOrNull()
            ?: return@suspendTransaction false
        val channelId = target[ModelPrices.channelId]
        val upstreamModel = target[ModelPrices.upstreamModel]
        ModelPrices.deleteWhere { ModelPrices.id eq id } > 0
    }

    private fun nextVersion(channelId: String, upstreamModel: String): Int =
        (ModelPriceVersions.selectAll()
            .where { (ModelPriceVersions.channelId eq channelId) and (ModelPriceVersions.upstreamModel eq upstreamModel) }
            .maxOfOrNull { it[ModelPriceVersions.version] } ?: 0) + 1

    private fun insertVersion(
        channelId: String,
        upstreamModel: String,
        version: Int,
        effectiveFrom: Long,
        status: String,
        createdAt: Long,
        currency: String,
        inputPricePerMillion: BigDecimal,
        outputPricePerMillion: BigDecimal,
        cachedInputPricePerMillion: BigDecimal?,
        cachedInputWritePricePerMillion: BigDecimal?,
        pricingMode: PricingMode,
        perRequestPrice: BigDecimal?,
        priceTiers: List<ModelPriceTier>,
    ) {
        ModelPriceVersions.insert {
            it[ModelPriceVersions.id] = UUID.randomUUID().toString()
            it[ModelPriceVersions.channelId] = channelId
            it[ModelPriceVersions.upstreamModel] = upstreamModel
            it[ModelPriceVersions.version] = version
            it[ModelPriceVersions.currency] = currency
            it[ModelPriceVersions.inputPricePerMillion] = inputPricePerMillion
            it[ModelPriceVersions.outputPricePerMillion] = outputPricePerMillion
            it[ModelPriceVersions.cachedInputPricePerMillion] = cachedInputPricePerMillion
            it[ModelPriceVersions.cachedInputWritePricePerMillion] = cachedInputWritePricePerMillion
            it[ModelPriceVersions.pricingMode] = pricingMode.value
            it[ModelPriceVersions.perRequestPrice] = perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(priceTiers)
            it[ModelPriceVersions.effectiveFrom] = effectiveFrom
            it[ModelPriceVersions.status] = status
            it[ModelPriceVersions.createdAt] = createdAt
        }
    }

    private fun load(id: String, asOfTime: Long = System.currentTimeMillis()): ModelPriceRecord? =
        ModelPrices
            .join(Channels, JoinType.INNER, ModelPrices.channelId, Channels.id)
            .selectAll()
            .where { ModelPrices.id eq id }
            .singleOrNull()
            ?.let { row ->
                toRecord(
                    row,
                    resolveCurrentVersion(row[ModelPrices.channelId], row[ModelPrices.upstreamModel], asOfTime),
                )
            }

    private fun toRecord(
        row: ResultRow,
        currentVersion: ModelPriceVersionRecord? = null,
    ) = ModelPriceRecord(
        id = row[ModelPrices.id],
        channelId = row[ModelPrices.channelId],
        channelName = row[Channels.name],
        upstreamModel = row[ModelPrices.upstreamModel],
        currency = currentVersion?.currency ?: row[ModelPrices.currency],
        inputPricePerMillion = currentVersion?.inputPricePerMillion ?: row[ModelPrices.inputPricePerMillion],
        outputPricePerMillion = currentVersion?.outputPricePerMillion ?: row[ModelPrices.outputPricePerMillion],
        cachedInputPricePerMillion = currentVersion?.cachedInputPricePerMillion ?: row[ModelPrices.cachedInputPricePerMillion],
        cachedInputWritePricePerMillion = currentVersion?.cachedInputWritePricePerMillion ?: row[ModelPrices.cachedInputWritePricePerMillion],
        pricingMode = currentVersion?.pricingMode ?: PricingMode.fromValue(row[ModelPrices.pricingMode]),
        perRequestPrice = currentVersion?.perRequestPrice ?: row[ModelPrices.perRequestPrice],
        priceTiers = currentVersion?.priceTiers ?: PricingTierCodec.decode(row[ModelPrices.priceTiersJson]),
        currentVersion = currentVersion?.version ?: row[ModelPrices.currentVersion],
        effectiveFrom = currentVersion?.effectiveFrom ?: row[ModelPrices.effectiveFrom],
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

}
