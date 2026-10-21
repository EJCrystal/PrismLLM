package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ClientKeys
import dev.soven.llmgateway.domain.ClientKeyRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
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
        keyHash: String,
        keyPrefix: String,
        enabled: Boolean,
        expiresAt: Long?,
    ): ClientKeyRecord = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        ClientKeys.insert {
            it[ClientKeys.id] = id
            it[ClientKeys.name] = name
            it[ClientKeys.keyHash] = keyHash
            it[ClientKeys.keyPrefix] = keyPrefix
            it[ClientKeys.enabled] = enabled
            it[ClientKeys.expiresAt] = expiresAt
            it[createdAt] = now
        }
        ClientKeys.selectAll().where { ClientKeys.id eq id }.single().let(::toRecord)
    }

    suspend fun update(id: String, name: String, enabled: Boolean, expiresAt: Long?): ClientKeyRecord? =
        suspendTransaction(database) {
            val updated = ClientKeys.update({ ClientKeys.id eq id }) {
                it[ClientKeys.name] = name
                it[ClientKeys.enabled] = enabled
                it[ClientKeys.expiresAt] = expiresAt
            }
            if (updated == 0) null else ClientKeys.selectAll().where { ClientKeys.id eq id }.single().let(::toRecord)
        }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        ClientKeys.deleteWhere { ClientKeys.id eq id } > 0
    }

    private fun toRecord(row: ResultRow) = ClientKeyRecord(
        id = row[ClientKeys.id],
        name = row[ClientKeys.name],
        keyHash = row[ClientKeys.keyHash],
        keyPrefix = row[ClientKeys.keyPrefix],
        enabled = row[ClientKeys.enabled],
        expiresAt = row[ClientKeys.expiresAt],
        createdAt = row[ClientKeys.createdAt],
    )
}
