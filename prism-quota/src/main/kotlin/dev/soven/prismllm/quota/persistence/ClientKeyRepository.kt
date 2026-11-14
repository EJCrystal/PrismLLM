package dev.soven.prismllm.quota.persistence

import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * 调用方密钥持久化。数据面按哈希查找，管理面不回显明文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class ClientKeyRepository(private val database: Database) {
    /** 根据凭据哈希读取密钥与访问范围的一致快照。 */
    suspend fun findByHash(hash: String): ClientKeyRecord? = suspendTransaction(database) {
        exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        toRecords(ClientKeys.selectAll().where { ClientKeys.keyHash eq hash }.toList()).singleOrNull()
    }

    /** 批量读取管理列表的访问关联，避免逐密钥查询。 */
    suspend fun list(): List<ClientKeyRecord> = suspendTransaction(database) {
        exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        toRecords(ClientKeys.selectAll().orderBy(ClientKeys.createdAt).toList())
    }

    /** 按 ID 读取密钥与访问范围的一致快照。 */
    suspend fun find(id: String): ClientKeyRecord? = suspendTransaction(database) {
        exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        toRecords(ClientKeys.selectAll().where { ClientKeys.id eq id }.toList()).singleOrNull()
    }

    /** 原子创建密钥及其完整访问范围，省略策略时保持不限制模式。 */
    suspend fun create(
        name: String,
        keyHash: String,
        keyPrefix: String,
        enabled: Boolean,
        expiresAt: Long?,
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
        accessPolicy: ClientKeyAccessPolicy = ClientKeyAccessPolicy(),
    ): ClientKeyRecord = suspendTransaction(database) {
        exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        val policy = accessPolicy.normalized()
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        ClientKeys.insert {
            it[ClientKeys.id] = id
            it[ClientKeys.name] = name
            it[ClientKeys.keyHash] = keyHash
            it[ClientKeys.keyPrefix] = keyPrefix
            it[ClientKeys.enabled] = enabled
            it[ClientKeys.expiresAt] = expiresAt
            it[ClientKeys.dailyTokenLimit] = quotas.dailyTokens
            it[ClientKeys.dailyUsdLimit] = quotas.dailyUsdLimit
            it[ClientKeys.maxConcurrentRequests] = quotas.maxConcurrentRequests
            it[ClientKeys.requestsPerMinute] = quotas.requestsPerMinute
            it[createdAt] = now
            setAccessModes(it, policy)
        }
        saveAccessPolicy(id, policy)
        toRecords(ClientKeys.selectAll().where { ClientKeys.id eq id }.toList()).single()
    }

    /** 更新可编辑密钥；省略或传入 `null` 策略参数时保留已有访问范围。 */
    suspend fun update(
        id: String,
        name: String,
        team: String? = null,
        enabled: Boolean,
        expiresAt: Long?,
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
        accessPolicy: ClientKeyAccessPolicy? = null,
    ): ClientKeyRecord? =
        suspendTransaction(database) {
            exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            val policy = accessPolicy?.normalized()
            val updated = ClientKeys.update({
                (ClientKeys.id eq id) and ClientKeys.revokedAt.isNull()
            }) {
                it[ClientKeys.name] = name
                it[ClientKeys.enabled] = enabled
                it[ClientKeys.expiresAt] = expiresAt
                it[ClientKeys.dailyTokenLimit] = quotas.dailyTokens
                it[ClientKeys.dailyUsdLimit] = quotas.dailyUsdLimit
                it[ClientKeys.maxConcurrentRequests] = quotas.maxConcurrentRequests
                it[ClientKeys.requestsPerMinute] = quotas.requestsPerMinute
                if (policy != null) setAccessModes(it, policy)
            }
            if (updated == 0) {
                null
            } else {
                if (policy != null) saveAccessPolicy(id, policy)
                toRecords(ClientKeys.selectAll().where { ClientKeys.id eq id }.toList()).single()
            }
        }

    /**
     * 原子替换密钥哈希和展示前缀。撤销后的密钥不能轮换。
     */
    suspend fun rotate(id: String, keyHash: String, keyPrefix: String): ClientKeyRecord? =
        suspendTransaction(database) {
            exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            val now = System.currentTimeMillis()
            val updated = ClientKeys.update({
                (ClientKeys.id eq id) and ClientKeys.revokedAt.isNull()
            }) {
                it[ClientKeys.keyHash] = keyHash
                it[ClientKeys.keyPrefix] = keyPrefix
                it[ClientKeys.rotatedAt] = now
            }
            if (updated == 0) {
                null
            } else {
                toRecords(ClientKeys.selectAll().where { ClientKeys.id eq id }.toList()).single()
            }
        }

    /**
     * 永久撤销密钥。重复撤销保持原有撤销时间，便于幂等调用。
     */
    suspend fun revoke(id: String): ClientKeyRecord? = suspendTransaction(database) {
        exec("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        val now = System.currentTimeMillis()
        ClientKeys.update({
            (ClientKeys.id eq id) and ClientKeys.revokedAt.isNull()
        }) {
            it[ClientKeys.enabled] = false
            it[ClientKeys.revokedAt] = now
        }
        toRecords(ClientKeys.selectAll().where { ClientKeys.id eq id }.toList()).singleOrNull()
    }

    /** 删除调用方密钥，由数据库级联清理其访问范围与额度关联。 */
    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ClientKeys.deleteWhere { ClientKeys.id eq id } > 0
    }

    /** 设置限制模式；此操作与授权关联替换始终在同一个事务内完成。 */
    private fun setAccessModes(statement: UpdateBuilder<*>, policy: ClientKeyAccessPolicy) {
        statement[ClientKeys.accessChannelsRestricted] = policy.allowedChannelIds != null
        statement[ClientKeys.accessChannelKeysRestricted] = policy.allowedChannelKeys != null
        statement[ClientKeys.accessModelsRestricted] = policy.allowedModelIds != null
    }

    /** 在当前事务中完整替换三项白名单，不限制模式不保存授权关联。 */
    private fun saveAccessPolicy(id: String, policy: ClientKeyAccessPolicy) {
        ClientKeyAllowedChannels.deleteWhere { clientKeyId eq id }
        ClientKeyAllowedChannelKeys.deleteWhere { clientKeyId eq id }
        ClientKeyAllowedModels.deleteWhere { clientKeyId eq id }
        policy.allowedChannelIds.orEmpty().forEach { channel ->
            ClientKeyAllowedChannels.insert {
                it[clientKeyId] = id
                it[channelId] = channel
            }
        }
        policy.allowedChannelKeys.orEmpty().forEach { key ->
            ClientKeyAllowedChannelKeys.insert {
                it[clientKeyId] = id
                it[channelId] = key.channelId
                it[keyId] = key.keyId
            }
        }
        policy.allowedModelIds.orEmpty().forEach { model ->
            ClientKeyAllowedModels.insert {
                it[clientKeyId] = id
                it[modelId] = model
            }
        }
    }

    /** 从当前事务快照批量读取受限维度，仅查存在限制标记的密钥。 */
    private fun toRecords(rows: List<ResultRow>): List<ClientKeyRecord> {
        val channelIds = rows.filter { it[ClientKeys.accessChannelsRestricted] }.map { it[ClientKeys.id] }
        val keyIds = rows.filter { it[ClientKeys.accessChannelKeysRestricted] }.map { it[ClientKeys.id] }
        val modelIds = rows.filter { it[ClientKeys.accessModelsRestricted] }.map { it[ClientKeys.id] }
        val channels = if (channelIds.isEmpty()) emptyMap() else ClientKeyAllowedChannels.selectAll()
            .where { ClientKeyAllowedChannels.clientKeyId inList channelIds }
            .groupBy({ it[ClientKeyAllowedChannels.clientKeyId] }, { it[ClientKeyAllowedChannels.channelId] })
        val keys = if (keyIds.isEmpty()) emptyMap() else ClientKeyAllowedChannelKeys.selectAll()
            .where { ClientKeyAllowedChannelKeys.clientKeyId inList keyIds }
            .groupBy({ it[ClientKeyAllowedChannelKeys.clientKeyId] }, {
                ClientKeyAllowedChannelKey(it[ClientKeyAllowedChannelKeys.channelId], it[ClientKeyAllowedChannelKeys.keyId])
            })
        val models = if (modelIds.isEmpty()) emptyMap() else ClientKeyAllowedModels.selectAll()
            .where { ClientKeyAllowedModels.clientKeyId inList modelIds }
            .groupBy({ it[ClientKeyAllowedModels.clientKeyId] }, { it[ClientKeyAllowedModels.modelId] })
        return rows.map { row ->
            val id = row[ClientKeys.id]
            toRecord(row, ClientKeyAccessPolicy(
                allowedChannelIds = if (row[ClientKeys.accessChannelsRestricted]) channels[id].orEmpty().toSet() else null,
                allowedChannelKeys = if (row[ClientKeys.accessChannelKeysRestricted]) keys[id].orEmpty().toSet() else null,
                allowedModelIds = if (row[ClientKeys.accessModelsRestricted]) models[id].orEmpty().toSet() else null,
            ))
        }
    }

    /** 将基础字段与已读取的访问范围组合为领域记录。 */
    private fun toRecord(row: ResultRow, policy: ClientKeyAccessPolicy) = ClientKeyRecord(
        id = row[ClientKeys.id],
        name = row[ClientKeys.name],
        keyHash = row[ClientKeys.keyHash],
        keyPrefix = row[ClientKeys.keyPrefix],
        enabled = row[ClientKeys.enabled],
        expiresAt = row[ClientKeys.expiresAt],
        quotas = ClientKeyQuotaLimits(
            dailyTokens = row[ClientKeys.dailyTokenLimit],
            dailyUsdLimit = row[ClientKeys.dailyUsdLimit],
            maxConcurrentRequests = row[ClientKeys.maxConcurrentRequests],
            requestsPerMinute = row[ClientKeys.requestsPerMinute],
        ),
        createdAt = row[ClientKeys.createdAt],
        rotatedAt = row[ClientKeys.rotatedAt],
        revokedAt = row[ClientKeys.revokedAt],
        accessPolicy = policy,
    )
}
