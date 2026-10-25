package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.AliasCandidates
import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.db.ModelAliases
import dev.soven.llmgateway.domain.AliasRecord
import dev.soven.llmgateway.domain.CandidateRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RouteCandidate
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * 模型别名与候选的持久化。数据面只读取启用别名下的启用渠道。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class AliasRepository(private val database: Database) {
    suspend fun findEnabledCandidates(alias: String): List<RouteCandidate> = suspendTransaction(database) {
        val aliasRow = ModelAliases.selectAll()
            .where { (ModelAliases.alias eq alias) and (ModelAliases.enabled eq true) }
            .singleOrNull() ?: return@suspendTransaction emptyList()

        val rows = AliasCandidates
            .join(Channels, JoinType.INNER, AliasCandidates.channelId, Channels.id)
            .selectAll()
            .where { (AliasCandidates.aliasId eq aliasRow[ModelAliases.id]) and (Channels.enabled eq true) }
            .orderBy(AliasCandidates.position to SortOrder.ASC, AliasCandidates.channelId to SortOrder.ASC)
            .toList()
        val modelsByChannel = loadChannelModelMappingsInTransaction(rows.map { it[Channels.id] })
        rows.map { row ->
            val channelId = row[Channels.id]
            RouteCandidate(
                channel = row.toChannel(modelsByChannel[channelId].orEmpty()),
                upstreamModel = row[AliasCandidates.upstreamModel],
                position = row[AliasCandidates.position],
            )
        }.filter { candidate -> candidate.upstreamModel in candidate.channel.models }
    }

    suspend fun listAvailableAliases(): List<String> = suspendTransaction(database) {
        AliasCandidates
            .join(Channels, JoinType.INNER, AliasCandidates.channelId, Channels.id)
            .join(ModelAliases, JoinType.INNER, AliasCandidates.aliasId, ModelAliases.id)
            .selectAll()
            .where { (ModelAliases.enabled eq true) and (Channels.enabled eq true) }
            .map { it[ModelAliases.alias] }
            .distinct()
            .sorted()
    }

    suspend fun listAllAliasNames(): Set<String> = suspendTransaction(database) {
        ModelAliases.selectAll().map { it[ModelAliases.alias] }.toSet()
    }

    suspend fun list(): List<AliasWithCandidates> = suspendTransaction(database) {
        ModelAliases.selectAll().orderBy(ModelAliases.alias).map { row ->
            AliasWithCandidates(
                alias = row.toAlias(),
                candidates = candidatesOf(row[ModelAliases.id]),
            )
        }
    }

    suspend fun find(id: String): AliasWithCandidates? = suspendTransaction(database) {
        val row = ModelAliases.selectAll().where { ModelAliases.id eq id }.singleOrNull() ?: return@suspendTransaction null
        AliasWithCandidates(alias = row.toAlias(), candidates = candidatesOf(id))
    }

    suspend fun findByAlias(alias: String): AliasRecord? = suspendTransaction(database) {
        ModelAliases.selectAll().where { ModelAliases.alias eq alias }.singleOrNull()?.let { it.toAlias() }
    }

    suspend fun isChannelInUse(channelId: String): Boolean = suspendTransaction(database) {
        AliasCandidates.selectAll().where { AliasCandidates.channelId eq channelId }.limit(1).any()
    }

    suspend fun findAliasNamesUsingModel(channelId: String, upstreamModel: String): List<String> =
        suspendTransaction(database) {
            AliasCandidates
                .join(ModelAliases, JoinType.INNER, AliasCandidates.aliasId, ModelAliases.id)
                .selectAll()
                .where {
                    (AliasCandidates.channelId eq channelId) and
                        (AliasCandidates.upstreamModel eq upstreamModel)
                }
                .map { it[ModelAliases.alias] }
                .distinct()
                .sorted()
        }

    suspend fun create(alias: String, enabled: Boolean, candidates: List<NewCandidate>): AliasWithCandidates =
        suspendTransaction(database) {
            val now = System.currentTimeMillis()
            val aliasId = UUID.randomUUID().toString()
            ModelAliases.insert {
                it[id] = aliasId
                it[ModelAliases.alias] = alias
                it[ModelAliases.enabled] = enabled
                it[createdAt] = now
                it[updatedAt] = now
            }
            insertCandidates(aliasId, candidates)
            AliasWithCandidates(
                alias = AliasRecord(aliasId, alias, enabled, now, now),
                candidates = candidatesOf(aliasId),
            )
        }

    suspend fun update(id: String, alias: String, enabled: Boolean, candidates: List<NewCandidate>): AliasWithCandidates? =
        suspendTransaction(database) {
            val updated = ModelAliases.update({ ModelAliases.id eq id }) {
                it[ModelAliases.alias] = alias
                it[ModelAliases.enabled] = enabled
                it[updatedAt] = System.currentTimeMillis()
            }
            if (updated == 0) return@suspendTransaction null
            AliasCandidates.deleteWhere { AliasCandidates.aliasId eq id }
            insertCandidates(id, candidates)
            val row = ModelAliases.selectAll().where { ModelAliases.id eq id }.single()
            AliasWithCandidates(row.toAlias(), candidatesOf(id))
        }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ModelAliases.deleteWhere { ModelAliases.id eq id } > 0
    }

    private fun insertCandidates(aliasId: String, candidates: List<NewCandidate>) {
        candidates.forEachIndexed { index, candidate ->
            AliasCandidates.insert {
                it[id] = UUID.randomUUID().toString()
                it[AliasCandidates.aliasId] = aliasId
                it[channelId] = candidate.channelId
                it[upstreamModel] = candidate.upstreamModel
                it[position] = candidate.position ?: index
            }
        }
    }

    private fun candidatesOf(aliasId: String): List<CandidateWithChannel> =
        AliasCandidates
            .join(Channels, JoinType.INNER, AliasCandidates.channelId, Channels.id)
            .selectAll()
            .where { AliasCandidates.aliasId eq aliasId }
            .orderBy(AliasCandidates.position to SortOrder.ASC, AliasCandidates.channelId to SortOrder.ASC)
            .map { row ->
                CandidateWithChannel(
                    candidate = CandidateRecord(
                        id = row[AliasCandidates.id],
                        aliasId = row[AliasCandidates.aliasId],
                        channelId = row[AliasCandidates.channelId],
                        upstreamModel = row[AliasCandidates.upstreamModel],
                        position = row[AliasCandidates.position],
                    ),
                    channelName = row[Channels.name],
                )
            }

    private fun ResultRow.toAlias() = AliasRecord(
        id = this[ModelAliases.id],
        alias = this[ModelAliases.alias],
        enabled = this[ModelAliases.enabled],
        createdAt = this[ModelAliases.createdAt],
        updatedAt = this[ModelAliases.updatedAt],
    )

    private fun ResultRow.toChannel(modelMappings: List<ChannelModelRecord>) = ChannelRecord(
        id = this[Channels.id],
        name = this[Channels.name],
        baseUrl = this[Channels.baseUrl],
        credentialCiphertext = this[Channels.credentialCiphertext],
        credentialNonce = this[Channels.credentialNonce],
        credentialKeyVersion = this[Channels.credentialKeyVersion],
        authType = this[Channels.authType],
        protocols = GatewayProtocol.parse(this[Channels.protocols]),
        enabled = this[Channels.enabled],
        exposeUpstreamModels = this[Channels.exposeUpstreamModels],
        priority = this[Channels.priority],
        createdAt = this[Channels.createdAt],
        updatedAt = this[Channels.updatedAt],
        modelMappings = modelMappings,
    )
}

/**
 * 别名及其候选。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class AliasWithCandidates(
    val alias: AliasRecord,
    val candidates: List<CandidateWithChannel>,
)

/**
 * 候选及其渠道展示名。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class CandidateWithChannel(
    val candidate: CandidateRecord,
    val channelName: String,
)

/**
 * 新建候选的输入。position 是优先级档位，null 表示按数组顺序。
 */
data class NewCandidate(
    val channelId: String,
    val upstreamModel: String,
    val position: Int? = null,
)
