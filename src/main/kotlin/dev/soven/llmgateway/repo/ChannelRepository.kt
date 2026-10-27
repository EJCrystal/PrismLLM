package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ChannelKeyModels
import dev.soven.llmgateway.db.ChannelKeys
import dev.soven.llmgateway.db.ChannelModels
import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.security.EncryptedCredential
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** 新建渠道密钥的输入。凭据已加密。 */
data class NewChannelKey(
    val name: String,
    val credential: EncryptedCredential,
    val enabled: Boolean = true,
    val priority: Int = 1,
    val models: List<String> = emptyList(),
)

/** 更新渠道密钥的输入。credential 为空表示保持原凭据。 */
data class ChannelKeyUpdate(
    val id: String? = null,
    val name: String,
    val credential: EncryptedCredential? = null,
    val enabled: Boolean = true,
    val priority: Int = 1,
    val models: List<String> = emptyList(),
)

/**
 * 渠道持久化。渠道密钥只以密文写入，读取时不自动解密。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class ChannelRepository(private val database: Database) {
    suspend fun list(): List<ChannelRecord> = suspendTransaction(database) {
        val rows = Channels.selectAll().orderBy(Channels.name).toList()
        val ids = rows.map { it[Channels.id] }
        val modelsByChannel = loadChannelModelMappingsInTransaction(ids)
        val keysByChannel = loadChannelKeysInTransaction(ids)
        rows.map { row ->
            toRecord(row).copy(
                modelMappings = modelsByChannel[row[Channels.id]].orEmpty(),
                keys = keysByChannel[row[Channels.id]].orEmpty(),
            )
        }
    }

    suspend fun find(id: String): ChannelRecord? = suspendTransaction(database) {
        Channels.selectAll().where { Channels.id eq id }.singleOrNull()?.let(::toRecordWithRelations)
    }

    suspend fun findByName(name: String): ChannelRecord? = suspendTransaction(database) {
        Channels.selectAll().where { Channels.name eq name }.singleOrNull()?.let(::toRecordWithRelations)
    }

    suspend fun create(
        name: String,
        baseUrl: String,
        keys: List<NewChannelKey>,
        authType: String,
        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        enabled: Boolean,
        modelMappings: List<ChannelModelRecord> = emptyList(),
        priority: Int = 1,
    ): ChannelRecord = suspendTransaction(database) {
        require(keys.isNotEmpty()) { "channel requires at least one key" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        Channels.insert {
            it[Channels.id] = id
            it[Channels.name] = name
            it[Channels.baseUrl] = baseUrl
            it[Channels.authType] = authType
            it[Channels.protocols] = protocols.joinToString(",") { protocol -> protocol.value }
            it[Channels.enabled] = enabled
            it[Channels.priority] = priority
            it[createdAt] = now
            it[updatedAt] = now
        }
        saveModels(id, modelMappings, now)
        saveKeys(id, keys.map { key ->
            ChannelKeyUpdate(
                name = key.name,
                credential = key.credential,
                enabled = key.enabled,
                priority = key.priority,
                models = key.models,
            )
        }, now)
        Channels.selectAll().where { Channels.id eq id }.single().let(::toRecordWithRelations)
    }

    suspend fun update(
        id: String,
        name: String,
        baseUrl: String,
        keys: List<ChannelKeyUpdate>? = null,
        authType: String?,
        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        enabled: Boolean,
        modelMappings: List<ChannelModelRecord>? = null,
        priority: Int? = null,
    ): ChannelRecord? = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        val updated = Channels.update({ Channels.id eq id }) {
            it[Channels.name] = name
            it[Channels.baseUrl] = baseUrl
            if (authType != null) it[Channels.authType] = authType
            it[Channels.protocols] = protocols.joinToString(",") { protocol -> protocol.value }
            it[Channels.enabled] = enabled
            if (priority != null) it[Channels.priority] = priority
            it[updatedAt] = now
        }
        if (updated == 0) {
            null
        } else {
            if (modelMappings != null) saveModels(id, modelMappings, now)
            if (keys != null) saveKeys(id, keys, now)
            Channels.selectAll().where { Channels.id eq id }.single().let(::toRecordWithRelations)
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
        pruneKeyModels(channelId, modelMappings.map { it.upstreamModel }.toSet())
    }

    /** 渠道目录收缩时同步清理密钥上已不存在的模型引用。 */
    private fun pruneKeyModels(channelId: String, allowedModels: Set<String>) {
        val keyIds = ChannelKeys.selectAll()
            .where { ChannelKeys.channelId eq channelId }
            .map { it[ChannelKeys.id] }
        keyIds.forEach { keyId ->
            if (allowedModels.isEmpty()) {
                ChannelKeyModels.deleteWhere { ChannelKeyModels.keyId eq keyId }
            } else {
                ChannelKeyModels.deleteWhere {
                    (ChannelKeyModels.keyId eq keyId) and (ChannelKeyModels.upstreamModel notInList allowedModels)
                }
            }
        }
    }

    /** 按 id 覆盖密钥集合；未出现在列表中的密钥会被删除。 */
    private fun saveKeys(channelId: String, keys: List<ChannelKeyUpdate>, now: Long) {
        val existing = ChannelKeys.selectAll()
            .where { ChannelKeys.channelId eq channelId }
            .associateBy { it[ChannelKeys.id] }
        val allowedModels = ChannelModels.selectAll()
            .where { ChannelModels.channelId eq channelId }
            .map { it[ChannelModels.upstreamModel] }
            .toSet()
        val keptIds = mutableSetOf<String>()
        keys.forEach { key ->
            val keyId = when {
                key.id == null -> UUID.randomUUID().toString()
                key.id in existing -> key.id
                else -> throw IllegalArgumentException("channel key not found: ${key.id}")
            }
            keptIds += keyId
            if (keyId in existing) {
                ChannelKeys.update({ ChannelKeys.id eq keyId }) {
                    it[name] = key.name
                    key.credential?.let { credential ->
                        it[credentialCiphertext] = credential.ciphertext
                        it[credentialNonce] = credential.nonce
                        it[credentialKeyVersion] = credential.keyVersion
                    }
                    it[enabled] = key.enabled
                    it[priority] = key.priority
                    it[updatedAt] = now
                }
            } else {
                val credential = key.credential
                    ?: throw IllegalArgumentException("new channel key requires credential")
                ChannelKeys.insert {
                    it[id] = keyId
                    it[ChannelKeys.channelId] = channelId
                    it[name] = key.name
                    it[credentialCiphertext] = credential.ciphertext
                    it[credentialNonce] = credential.nonce
                    it[credentialKeyVersion] = credential.keyVersion
                    it[enabled] = key.enabled
                    it[priority] = key.priority
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            ChannelKeyModels.deleteWhere { ChannelKeyModels.keyId eq keyId }
            key.models.distinct().forEach { model ->
                require(model in allowedModels) { "channel key references unknown model: $model" }
                ChannelKeyModels.insert {
                    it[ChannelKeyModels.keyId] = keyId
                    it[upstreamModel] = model
                    it[createdAt] = now
                }
            }
        }
        (existing.keys - keptIds).forEach { staleId ->
            ChannelKeys.deleteWhere { ChannelKeys.id eq staleId }
        }
    }

    private fun toRecordWithRelations(row: ResultRow): ChannelRecord =
        toRecord(row).copy(
            modelMappings = modelsForInTransaction(row[Channels.id]),
            keys = keysForInTransaction(row[Channels.id]),
        )

    private fun modelsForInTransaction(channelId: String): List<ChannelModelRecord> =
        loadChannelModelMappingsInTransaction(listOf(channelId))[channelId].orEmpty()

    private fun keysForInTransaction(channelId: String): List<ChannelKeyRecord> =
        loadChannelKeysInTransaction(listOf(channelId))[channelId].orEmpty()

    private fun toRecord(row: ResultRow) = ChannelRecord(
        id = row[Channels.id],
        name = row[Channels.name],
        baseUrl = row[Channels.baseUrl],
        authType = row[Channels.authType],
        protocols = GatewayProtocol.parse(row[Channels.protocols]),
        enabled = row[Channels.enabled],
        priority = row[Channels.priority],
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

/** Batch-loads channel keys and their model sets inside the caller's transaction. */
internal fun loadChannelKeysInTransaction(
    channelIds: Collection<String>,
): Map<String, List<ChannelKeyRecord>> {
    if (channelIds.isEmpty()) return emptyMap()
    val keyRows = ChannelKeys.selectAll()
        .where { ChannelKeys.channelId inList channelIds }
        .orderBy(ChannelKeys.priority to SortOrder.ASC, ChannelKeys.name to SortOrder.ASC)
        .toList()
    if (keyRows.isEmpty()) return emptyMap()
    val modelsByKey = ChannelKeyModels.selectAll()
        .where { ChannelKeyModels.keyId inList keyRows.map { it[ChannelKeys.id] } }
        .groupBy { it[ChannelKeyModels.keyId] }
        .mapValues { (_, rows) -> rows.map { it[ChannelKeyModels.upstreamModel] }.sorted() }
    return keyRows.groupBy { it[ChannelKeys.channelId] }.mapValues { (_, rows) ->
        rows.map { row ->
            ChannelKeyRecord(
                id = row[ChannelKeys.id],
                channelId = row[ChannelKeys.channelId],
                name = row[ChannelKeys.name],
                credentialCiphertext = row[ChannelKeys.credentialCiphertext],
                credentialNonce = row[ChannelKeys.credentialNonce],
                credentialKeyVersion = row[ChannelKeys.credentialKeyVersion],
                enabled = row[ChannelKeys.enabled],
                priority = row[ChannelKeys.priority],
                models = modelsByKey[row[ChannelKeys.id]].orEmpty(),
                createdAt = row[ChannelKeys.createdAt],
                updatedAt = row[ChannelKeys.updatedAt],
            )
        }
    }
}
