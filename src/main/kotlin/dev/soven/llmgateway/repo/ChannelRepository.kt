package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.security.EncryptedCredential
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
 * 渠道持久化。上游凭据只以密文写入，读取时不自动解密。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class ChannelRepository(private val database: Database) {
    suspend fun list(): List<ChannelRecord> = suspendTransaction(database) {
        Channels.selectAll().orderBy(Channels.name).map(::toRecord)
    }

    suspend fun find(id: String): ChannelRecord? = suspendTransaction(database) {
        Channels.selectAll().where { Channels.id eq id }.singleOrNull()?.let(::toRecord)
    }

    suspend fun findByName(name: String): ChannelRecord? = suspendTransaction(database) {
        Channels.selectAll().where { Channels.name eq name }.singleOrNull()?.let(::toRecord)
    }

    suspend fun create(
        name: String,
        baseUrl: String,
        credential: EncryptedCredential,
        authType: String,
        enabled: Boolean,
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
            it[Channels.enabled] = enabled
            it[createdAt] = now
            it[updatedAt] = now
        }
        Channels.selectAll().where { Channels.id eq id }.single().let(::toRecord)
    }

    suspend fun update(
        id: String,
        name: String,
        baseUrl: String,
        credential: EncryptedCredential?,
        authType: String,
        enabled: Boolean,
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
            it[Channels.enabled] = enabled
            it[updatedAt] = System.currentTimeMillis()
        }
        if (updated == 0) null else Channels.selectAll().where { Channels.id eq id }.single().let(::toRecord)
    }

    suspend fun delete(id: String): Boolean = suspendTransaction(database) {
        Channels.deleteWhere { Channels.id eq id } > 0
    }

    private fun toRecord(row: ResultRow) = ChannelRecord(
        id = row[Channels.id],
        name = row[Channels.name],
        baseUrl = row[Channels.baseUrl],
        credentialCiphertext = row[Channels.credentialCiphertext],
        credentialNonce = row[Channels.credentialNonce],
        credentialKeyVersion = row[Channels.credentialKeyVersion],
        authType = row[Channels.authType],
        enabled = row[Channels.enabled],
        createdAt = row[Channels.createdAt],
        updatedAt = row[Channels.updatedAt],
    )
}
