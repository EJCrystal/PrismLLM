package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ClientKeys
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
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
    suspend fun findByHash(hash: String): ClientKeyRecord? = suspendTransaction(database) {
        ClientKeys.selectAll().where { ClientKeys.keyHash eq hash }.singleOrNull()?.let(::toRecord)
    }

    suspend fun list(): List<ClientKeyRecord> = suspendTransaction(database) {
        ClientKeys.selectAll().orderBy(ClientKeys.createdAt).map(::toRecord)
    }

    suspend fun find(id: String): ClientKeyRecord? = suspendTransaction(database) {
        ClientKeys.selectAll().where { ClientKeys.id eq id }.singleOrNull()?.let(::toRecord)
    }

    suspend fun create(
        name: String,
        team: String? = null,
        keyHash: String,
        keyPrefix: String,
        enabled: Boolean,
        expiresAt: Long?,
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): ClientKeyRecord = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        ClientKeys.insert {
            it[ClientKeys.id] = id
            it[ClientKeys.name] = name
            it[ClientKeys.team] = team
            it[ClientKeys.keyHash] = keyHash
            it[ClientKeys.keyPrefix] = keyPrefix
            it[ClientKeys.enabled] = enabled
            it[ClientKeys.expiresAt] = expiresAt
            it[ClientKeys.dailyTokenLimit] = quotas.dailyTokens
            it[ClientKeys.dailyUsdLimit] = quotas.dailyCostLimit("USD")
            it[ClientKeys.dailyCnyLimit] = quotas.dailyCostLimit("CNY")
            it[ClientKeys.dailyEurLimit] = quotas.dailyCostLimit("EUR")
            it[ClientKeys.maxConcurrentRequests] = quotas.maxConcurrentRequests
            it[ClientKeys.requestsPerMinute] = quotas.requestsPerMinute
            it[createdAt] = now
        }
        ClientKeys.selectAll().where { ClientKeys.id eq id }.single().let(::toRecord)
    }

    suspend fun update(
        id: String,
        name: String,
        team: String? = null,
        enabled: Boolean,
        expiresAt: Long?,
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): ClientKeyRecord? =
        suspendTransaction(database) {
            val updated = ClientKeys.update({
                (ClientKeys.id eq id) and ClientKeys.revokedAt.isNull()
            }) {
                it[ClientKeys.name] = name
                it[ClientKeys.team] = team
                it[ClientKeys.enabled] = enabled
                it[ClientKeys.expiresAt] = expiresAt
                it[ClientKeys.dailyTokenLimit] = quotas.dailyTokens
                it[ClientKeys.dailyUsdLimit] = quotas.dailyCostLimit("USD")
                it[ClientKeys.dailyCnyLimit] = quotas.dailyCostLimit("CNY")
                it[ClientKeys.dailyEurLimit] = quotas.dailyCostLimit("EUR")
                it[ClientKeys.maxConcurrentRequests] = quotas.maxConcurrentRequests
                it[ClientKeys.requestsPerMinute] = quotas.requestsPerMinute
            }
            if (updated == 0) null else ClientKeys.selectAll().where { ClientKeys.id eq id }.single().let(::toRecord)
        }

    /**
     * 原子替换密钥哈希和展示前缀。撤销后的密钥不能轮换。
     */
    suspend fun rotate(id: String, keyHash: String, keyPrefix: String): ClientKeyRecord? =
        suspendTransaction(database) {
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
                ClientKeys.selectAll().where { ClientKeys.id eq id }.single().let(::toRecord)
            }
        }

    /**
     * 永久撤销密钥。重复撤销保持原有撤销时间，便于幂等调用。
     */
    suspend fun revoke(id: String): ClientKeyRecord? = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        ClientKeys.update({
            (ClientKeys.id eq id) and ClientKeys.revokedAt.isNull()
        }) {
            it[ClientKeys.enabled] = false
            it[ClientKeys.revokedAt] = now
        }
        ClientKeys.selectAll().where { ClientKeys.id eq id }.singleOrNull()?.let(::toRecord)
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ClientKeys.deleteWhere { ClientKeys.id eq id } > 0
    }

    private fun toRecord(row: ResultRow) = ClientKeyRecord(
        id = row[ClientKeys.id],
        name = row[ClientKeys.name],
        team = row[ClientKeys.team],
        keyHash = row[ClientKeys.keyHash],
        keyPrefix = row[ClientKeys.keyPrefix],
        enabled = row[ClientKeys.enabled],
        expiresAt = row[ClientKeys.expiresAt],
        quotas = ClientKeyQuotaLimits(
            dailyTokens = row[ClientKeys.dailyTokenLimit],
            dailyCosts = buildMap {
                row[ClientKeys.dailyUsdLimit]?.let { put("USD", it) }
                row[ClientKeys.dailyCnyLimit]?.let { put("CNY", it) }
                row[ClientKeys.dailyEurLimit]?.let { put("EUR", it) }
            },
            maxConcurrentRequests = row[ClientKeys.maxConcurrentRequests],
            requestsPerMinute = row[ClientKeys.requestsPerMinute],
        ),
        createdAt = row[ClientKeys.createdAt],
        rotatedAt = row[ClientKeys.rotatedAt],
        revokedAt = row[ClientKeys.revokedAt],
    )
}
