package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.db.ChannelModels
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.security.EncryptedCredential
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * 渠道持久化。上游凭据只以密文写入，读取时不自动解密。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class ChannelRepository(private val database: Database) {
    suspend fun list(): List<ChannelRecord> = suspendTransaction(database) {
        val rows = Channels.selectAll().orderBy(Channels.name).toList()
        val modelsByChannel = loadChannelModelMappingsInTransaction(rows.map { it[Channels.id] })
        rows.map { row ->
            toRecord(row).copy(modelMappings = modelsByChannel[row[Channels.id]].orEmpty())
        }
    }

    suspend fun find(id: String): ChannelRecord? = suspendTransaction(database) {
        Channels.selectAll().where { Channels.id eq id }.singleOrNull()?.let(::toRecordWithModels)
    }

    suspend fun findByName(name: String): ChannelRecord? = suspendTransaction(database) {
        Channels.selectAll().where { Channels.name eq name }.singleOrNull()?.let(::toRecordWithModels)
    }

    suspend fun create(
        name: String,
        baseUrl: String,
        credential: EncryptedCredential,
        authType: String,
        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        enabled: Boolean,
        modelMappings: List<ChannelModelRecord> = emptyList(),
        exposeUpstreamModels: Boolean = true,
    ): ChannelRecord = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        Channels.insert {
            it[Channels.id] = id
            it[Channels.name] = name
            it[Channels.baseUrl] = baseUrl
            it[credentialCiphertext] = credential.ciphertext
            it[credentialNonce] = credential.nonce
            it[credentialKeyVersion] = credential.keyVersion
            it[Channels.authType] = authType
            it[Channels.protocols] = protocols.joinToString(",") { protocol -> protocol.value }
            it[Channels.enabled] = enabled
            it[Channels.exposeUpstreamModels] = exposeUpstreamModels
            it[createdAt] = now
            it[updatedAt] = now
        }
        saveModels(id, modelMappings, now)
        Channels.selectAll().where { Channels.id eq id }.single().let(::toRecordWithModels)
    }

    suspend fun update(
        id: String,
        name: String,
        baseUrl: String,
        credential: EncryptedCredential?,
        authType: String,
        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        enabled: Boolean,
        modelMappings: List<ChannelModelRecord>? = null,
        exposeUpstreamModels: Boolean? = null,
    ): ChannelRecord? = suspendTransaction(database) {
        val updated = Channels.update({ Channels.id eq id }) {
            it[Channels.name] = name
            it[Channels.baseUrl] = baseUrl
            if (credential != null) {
                it[credentialCiphertext] = credential.ciphertext
                it[credentialNonce] = credential.nonce
                it[credentialKeyVersion] = credential.keyVersion
            }
            it[Channels.authType] = authType
            it[Channels.protocols] = protocols.joinToString(",") { protocol -> protocol.value }
            it[Channels.enabled] = enabled
            if (exposeUpstreamModels != null) it[Channels.exposeUpstreamModels] = exposeUpstreamModels
            it[updatedAt] = System.currentTimeMillis()
        }
        if (updated == 0) {
            null
        } else {
            if (modelMappings != null) saveModels(id, modelMappings, System.currentTimeMillis())
            Channels.selectAll().where { Channels.id eq id }.single().let(::toRecordWithModels)
        }
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        Channels.deleteWhere { Channels.id eq id } > 0
    }

    suspend fun modelsFor(channelId: String): Set<String> = suspendTransaction(database) {
        ChannelModels.selectAll()
            .where { ChannelModels.channelId eq channelId }
            .map { it[ChannelModels.upstreamModel] }
            .toSet()
    }

    private fun saveModels(channelId: String, modelMappings: List<ChannelModelRecord>, now: Long) {
        ChannelModels.deleteWhere { ChannelModels.channelId eq channelId }
        modelMappings.distinctBy { it.upstreamModel }.forEach { mapping ->
            ChannelModels.insert {
                it[ChannelModels.channelId] = channelId
                it[upstreamModel] = mapping.upstreamModel
                it[canonicalModelId] = mapping.canonicalModelId
                it[mappingSource] = mapping.mappingSource
                it[createdAt] = now
            }
        }
    }

    private fun toRecordWithModels(row: ResultRow): ChannelRecord =
        toRecord(row).copy(modelMappings = modelsForInTransaction(row[Channels.id]))

    private fun modelsForInTransaction(channelId: String): List<ChannelModelRecord> =
        loadChannelModelMappingsInTransaction(listOf(channelId))[channelId].orEmpty()

    private fun toRecord(row: ResultRow) = ChannelRecord(
        id = row[Channels.id],
        name = row[Channels.name],
        baseUrl = row[Channels.baseUrl],
        credentialCiphertext = row[Channels.credentialCiphertext],
        credentialNonce = row[Channels.credentialNonce],
        credentialKeyVersion = row[Channels.credentialKeyVersion],
        authType = row[Channels.authType],
        protocols = GatewayProtocol.parse(row[Channels.protocols]),
        enabled = row[Channels.enabled],
        exposeUpstreamModels = row[Channels.exposeUpstreamModels],
        createdAt = row[Channels.createdAt],
        updatedAt = row[Channels.updatedAt],
    )
}

/** Batch-loads channel model mappings inside the caller's transaction. */
internal fun loadChannelModelMappingsInTransaction(
    channelIds: Collection<String>,
): Map<String, List<ChannelModelRecord>> {
    if (channelIds.isEmpty()) return emptyMap()
    return ChannelModels.selectAll()
        .where { ChannelModels.channelId inList channelIds }
        .groupBy { it[ChannelModels.channelId] }
        .mapValues { (_, rows) ->
            rows.map {
                ChannelModelRecord(
                    upstreamModel = it[ChannelModels.upstreamModel],
                    canonicalModelId = it[ChannelModels.canonicalModelId],
                    mappingSource = it[ChannelModels.mappingSource],
                )
            }.sortedBy { it.upstreamModel }
        }
}
