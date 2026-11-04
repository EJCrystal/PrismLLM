package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ChannelKeyModels
import dev.soven.llmgateway.db.ChannelKeyHealth
import dev.soven.llmgateway.db.ChannelKeyRateLimits
import dev.soven.llmgateway.db.ChannelKeyQuotaSettings
import dev.soven.llmgateway.db.ChannelKeys
import dev.soven.llmgateway.db.ChannelModels
import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelQuotaSettings
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import kotlinx.serialization.json.Json
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.security.CredentialCipher
import dev.soven.llmgateway.security.EncryptedCredential
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** 新建渠道密钥的输入。凭据已加密。 */
data class NewChannelKey(
    val name: String,
    val credential: EncryptedCredential,
    val state: ChannelKeyState = ChannelKeyState.ACTIVE,
    val priority: Int = 1,
    val models: List<String> = emptyList(),
    val rateLimit: ChannelKeyRateLimitPolicy = ChannelKeyRateLimitPolicy(),
)

/** 更新渠道密钥的输入。credential 为空表示保持原凭据。 */
data class ChannelKeyUpdate(
    val id: String? = null,
    val name: String,
    val credential: EncryptedCredential? = null,
    val state: ChannelKeyState = ChannelKeyState.ACTIVE,
    val priority: Int = 1,
    val models: List<String> = emptyList(),
    /** `null` 表示保留该密钥已有的限流规则。 */
    val rateLimit: ChannelKeyRateLimitPolicy? = null,
    val stateVersion: Long? = null,
)

class ChannelKeyStateChangedException : RuntimeException("密钥状态已变化，请重新打开渠道编辑页面后再保存")

/**
 * 渠道持久化。渠道密钥只以密文写入，读取时不自动解密。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class ChannelRepository(private val database: Database, private val credentialCipher: CredentialCipher) {
    /** Health overview needs only channel identities and enabled state, never credentials or relations. */
    suspend fun listHealthChannels(): Map<String, Boolean> = suspendTransaction(database) {
        Channels.select(Channels.id, Channels.enabled).toList()
            .associate { row -> row[Channels.id] to row[Channels.enabled] }
    }

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
        protocols: List<GatewayProtocol> = GatewayProtocol.default,
        enabled: Boolean,
        modelMappings: List<ChannelModelRecord> = emptyList(),
        priority: Int = 1,
        requestOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
        quota: ChannelQuotaSettings = ChannelQuotaSettings(),
        quotaCredential: EncryptedCredential? = null,
        protocolAuthTypes: Map<String, String> = emptyMap(),
    ): ChannelRecord = suspendTransaction(database) {
        require(keys.isNotEmpty()) { "channel requires at least one key" }
        val storedProtocols = protocols.distinct()
        require(storedProtocols.isNotEmpty()) { "channel requires at least one protocol" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        Channels.insert {
            it[Channels.id] = id
            it[Channels.name] = name
            it[Channels.baseUrl] = baseUrl
            it[Channels.authType] = authType
            it[Channels.protocolAuthTypes] = Json.encodeToString(protocolAuthTypes)
            it[Channels.protocols] = storedProtocols.joinToString(",") { protocol -> protocol.value }
            it[Channels.enabled] = enabled
            it[Channels.priority] = priority
            it[Channels.requestOverrides] = Json.encodeToString(requestOverrides)
            it[Channels.quotaSettings] = Json.encodeToString(quota)
            it.setQuotaCredential(quotaCredential, clear = false)
            it[createdAt] = now
            it[updatedAt] = now
        }
        saveModels(id, modelMappings, now)
        saveKeys(id, keys.map { key ->
            ChannelKeyUpdate(
                name = key.name,
                credential = key.credential,
                state = key.state,
                priority = key.priority,
                models = key.models,
                rateLimit = key.rateLimit,
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
        protocols: List<GatewayProtocol>? = null,
        enabled: Boolean,
        modelMappings: List<ChannelModelRecord>? = null,
        priority: Int? = null,
        requestOverrides: UpstreamRequestOverrides? = null,
        quota: ChannelQuotaSettings? = null,
        quotaCredential: EncryptedCredential? = null,
        clearQuotaCredential: Boolean = false,
        protocolAuthTypes: Map<String, String>? = null,
    ): ChannelRecord? = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        Channels.selectAll()
            .where { Channels.id eq id }
            .forUpdate()
            .singleOrNull() ?: return@suspendTransaction null
        val updated = Channels.update({ Channels.id eq id }) {
            it[Channels.name] = name
            it[Channels.baseUrl] = baseUrl
            if (authType != null) it[Channels.authType] = authType
            protocolAuthTypes?.let { value -> it[Channels.protocolAuthTypes] = Json.encodeToString(value) }
            protocols?.let { values ->
                val storedProtocols = values.distinct()
                require(storedProtocols.isNotEmpty()) { "channel requires at least one protocol" }
                it[Channels.protocols] = storedProtocols.joinToString(",") { protocol -> protocol.value }
            }
            it[Channels.enabled] = enabled
            if (priority != null) it[Channels.priority] = priority
            if (requestOverrides != null) it[Channels.requestOverrides] = Json.encodeToString(requestOverrides)
            if (quota != null) it[Channels.quotaSettings] = Json.encodeToString(quota)
            it.setQuotaCredential(quotaCredential, clearQuotaCredential)
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

    suspend fun updateQuota(
        id: String,
        settings: ChannelQuotaSettings,
        credential: EncryptedCredential? = null,
        clearCredential: Boolean = false,
    ): ChannelRecord? = suspendTransaction(database) {
        val changed = Channels.update({ Channels.id eq id }) {
            it[quotaSettings] = Json.encodeToString(settings)
            it.setQuotaCredential(credential, clearCredential)
            it[updatedAt] = System.currentTimeMillis()
        }
        if (changed == 0) null else Channels.selectAll().where { Channels.id eq id }.single().let(::toRecordWithRelations)
    }

    suspend fun modelsFor(channelId: String): Set<String> = suspendTransaction(database) {
        ChannelModels.selectAll()
            .where { ChannelModels.channelId eq channelId }
            .map { it[ChannelModels.upstreamModel] }
            .toSet()
    }

    suspend fun updateKeyQuota(
        channelId: String,
        keyId: String,
        settings: ChannelQuotaSettings,
        credential: EncryptedCredential? = null,
        clearCredential: Boolean = false,
    ): ChannelRecord? = suspendTransaction(database) {
        require(credential == null || !clearCredential)
        // 与整渠道编辑共用父行锁，避免密钥删除/替换时留下悬空配置或覆盖其他账户。
        val channel = Channels.selectAll().where { Channels.id eq channelId }.forUpdate().singleOrNull()
            ?: return@suspendTransaction null
        ChannelKeys.selectAll().where { (ChannelKeys.id eq keyId) and (ChannelKeys.channelId eq channelId) }
            .forUpdate().singleOrNull() ?: return@suspendTransaction null
        val previous = ChannelKeyQuotaSettings.selectAll().where { ChannelKeyQuotaSettings.keyId eq keyId }.singleOrNull()
        val now = System.currentTimeMillis()
        fun UpdateBuilder<*>.setValues() {
            this[ChannelKeyQuotaSettings.settings] = Json.encodeToString(settings)
            this[ChannelKeyQuotaSettings.updatedAt] = now
            if (credential != null || clearCredential) {
                this[ChannelKeyQuotaSettings.credentialCiphertext] = credential?.ciphertext.orEmpty()
                this[ChannelKeyQuotaSettings.credentialNonce] = credential?.nonce.orEmpty()
                this[ChannelKeyQuotaSettings.credentialKeyVersion] = credential?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
            }
        }
        if (previous == null) ChannelKeyQuotaSettings.insert {
            it[ChannelKeyQuotaSettings.keyId] = keyId
            it.setValues()
        } else ChannelKeyQuotaSettings.update({ ChannelKeyQuotaSettings.keyId eq keyId }) { it.setValues() }
        Channels.update({ Channels.id eq channelId }) { it[updatedAt] = now }
        toRecordWithRelations(channel).copy(updatedAt = now)
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
            .orderBy(ChannelKeys.id).forUpdate()
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
                val previous = existing.getValue(keyId)
                if (key.stateVersion != null && key.stateVersion != previous[ChannelKeys.stateVersion]) {
                    throw ChannelKeyStateChangedException()
                }
                val previousState = ChannelKeyState.fromValue(previous[ChannelKeys.state])
                    ?: throw IllegalStateException("未知的渠道密钥状态: ${previous[ChannelKeys.state]}")
                // 自动停用状态只能由系统写入；保留 AUTO_PERMANENT 来源，人工恢复直接提交 ACTIVE。
                val targetState = if (previousState == ChannelKeyState.AUTO_PERMANENT &&
                    key.state == ChannelKeyState.MANUAL_DISABLED
                ) {
                    ChannelKeyState.AUTO_PERMANENT
                } else {
                    key.state
                }
                val resetHealth = key.credential != null || targetState.value != previous[ChannelKeys.state]
                ChannelKeys.update({ ChannelKeys.id eq keyId }) {
                    it[name] = key.name
                    key.credential?.let { credential ->
                        it[credentialCiphertext] = credential.ciphertext
                        it[credentialNonce] = credential.nonce
                        it[credentialKeyVersion] = credential.keyVersion
                    }
                    it[state] = targetState.value
                    it[priority] = key.priority
                    it[updatedAt] = now
                    if (resetHealth) {
                        it[stateVersion] = previous[ChannelKeys.stateVersion] + 1
                        // 人工启停与换凭据都会清空自动停用元数据；临时停用到期时间同样作废。
                        it[autoDisabledAt] = null
                        it[autoDisableReason] = null
                        it[autoDisableUntil] = null
                        it[probeUntil] = null
                    }
                }
                if (resetHealth) ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq keyId }
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
                    it[state] = key.state.value
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
            key.rateLimit?.let { saveRateLimit(keyId, it, now) }
        }
        (existing.keys - keptIds).forEach { staleId ->
            ChannelKeys.deleteWhere { ChannelKeys.id eq staleId }
        }
    }

    private fun saveRateLimit(keyId: String, policy: ChannelKeyRateLimitPolicy, now: Long) {
        ChannelKeyRateLimits.deleteWhere { ChannelKeyRateLimits.keyId eq keyId }
        if (!policy.enabled && policy.requestsPerMinute == null && policy.maxConcurrentRequests == null) return
        ChannelKeyRateLimits.insert {
            it[ChannelKeyRateLimits.keyId] = keyId
            it[enabled] = policy.enabled
            it[requestsPerMinute] = policy.requestsPerMinute
            it[maxConcurrentRequests] = policy.maxConcurrentRequests
            it[updatedAt] = now
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
        protocolAuthTypes = Json.decodeFromString<Map<String, String>>(row[Channels.protocolAuthTypes]),
        protocols = GatewayProtocol.parse(row[Channels.protocols]),
        enabled = row[Channels.enabled],
        priority = row[Channels.priority],
        createdAt = row[Channels.createdAt],
        updatedAt = row[Channels.updatedAt],
        requestOverrides = Json.decodeFromString<UpstreamRequestOverrides>(row[Channels.requestOverrides]),
        quota = Json.decodeFromString<ChannelQuotaSettings>(row[Channels.quotaSettings]),
        quotaCredential = row[Channels.quotaCredentialCiphertext].takeIf { it.isNotEmpty() }?.let {
            EncryptedCredential(it, row[Channels.quotaCredentialNonce], row[Channels.quotaCredentialKeyVersion])
        },
    )

    private fun UpdateBuilder<*>.setQuotaCredential(credential: EncryptedCredential?, clear: Boolean) {
        require(credential == null || !clear) { "cannot replace and clear quota credential together" }
        if (credential != null || clear) {
            this[Channels.quotaCredentialCiphertext] = credential?.ciphertext.orEmpty()
            this[Channels.quotaCredentialNonce] = credential?.nonce.orEmpty()
            this[Channels.quotaCredentialKeyVersion] = credential?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
        }
    }
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
    val rateLimitsByKey = ChannelKeyRateLimits.selectAll()
        .where { ChannelKeyRateLimits.keyId inList keyRows.map { it[ChannelKeys.id] } }
        .associate { row ->
            row[ChannelKeyRateLimits.keyId] to ChannelKeyRateLimitPolicy(
                enabled = row[ChannelKeyRateLimits.enabled],
                requestsPerMinute = row[ChannelKeyRateLimits.requestsPerMinute],
                maxConcurrentRequests = row[ChannelKeyRateLimits.maxConcurrentRequests],
            )
        }
    val quotasByKey = ChannelKeyQuotaSettings.selectAll()
        .where { ChannelKeyQuotaSettings.keyId inList keyRows.map { it[ChannelKeys.id] } }
        .associateBy { it[ChannelKeyQuotaSettings.keyId] }
    return keyRows.groupBy { it[ChannelKeys.channelId] }.mapValues { (_, rows) ->
        rows.map { row ->
            val quotaRow = quotasByKey[row[ChannelKeys.id]]
            ChannelKeyRecord(
                id = row[ChannelKeys.id],
                channelId = row[ChannelKeys.channelId],
                name = row[ChannelKeys.name],
                credentialCiphertext = row[ChannelKeys.credentialCiphertext],
                credentialNonce = row[ChannelKeys.credentialNonce],
                credentialKeyVersion = row[ChannelKeys.credentialKeyVersion],
                state = ChannelKeyState.fromValue(row[ChannelKeys.state])
                    ?: throw IllegalStateException("未知的渠道密钥状态: ${row[ChannelKeys.state]}"),
                priority = row[ChannelKeys.priority],
                models = modelsByKey[row[ChannelKeys.id]].orEmpty(),
                rateLimit = rateLimitsByKey[row[ChannelKeys.id]] ?: ChannelKeyRateLimitPolicy(),
                createdAt = row[ChannelKeys.createdAt],
                updatedAt = row[ChannelKeys.updatedAt],
                stateVersion = row[ChannelKeys.stateVersion],
                autoDisabledAt = row[ChannelKeys.autoDisabledAt],
                autoDisableReason = row[ChannelKeys.autoDisableReason],
                autoDisableUntil = row[ChannelKeys.autoDisableUntil],
                probeUntil = row[ChannelKeys.probeUntil],
                quota = quotaRow?.let { Json.decodeFromString<ChannelQuotaSettings>(it[ChannelKeyQuotaSettings.settings]) },
                quotaCredential = quotaRow?.let { entry ->
                    entry[ChannelKeyQuotaSettings.credentialCiphertext].takeIf { it.isNotEmpty() }?.let { ciphertext ->
                        EncryptedCredential(ciphertext, entry[ChannelKeyQuotaSettings.credentialNonce], entry[ChannelKeyQuotaSettings.credentialKeyVersion])
                    }
                },
            )
        }
    }
}
