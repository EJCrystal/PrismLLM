package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.shared.crypto.CredentialCipher
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

object AntigravityAgySettings : Table("antigravity_agy_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val proxy = text("proxy")
    val proxyUsername = text("proxy_username").nullable()
    val proxyPasswordCiphertext = text("proxy_password_ciphertext").default("")
    val proxyPasswordNonce = text("proxy_password_nonce").default("")
    val proxyPasswordKeyVersion = integer("proxy_password_key_version").default(CredentialCipher.CURRENT_KEY_VERSION)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

class AntigravityAgySettingsRepository(
    private val database: Database,
    private val credentialCipher: CredentialCipher,
) {
    suspend fun load(): AntigravityAgySettingsRecord? = suspendTransaction(database) {
        AntigravityAgySettings.selectAll().where { AntigravityAgySettings.id eq 1 }.singleOrNull()?.let {
            val ciphertext = it[AntigravityAgySettings.proxyPasswordCiphertext]
            var unreadable = false
            val password = ciphertext.takeIf(String::isNotEmpty)?.let { encrypted ->
                runCatching {
                    credentialCipher.decrypt(encrypted, it[AntigravityAgySettings.proxyPasswordNonce], PROXY_PASSWORD_AAD)
                }.onFailure { unreadable = true }.getOrNull()
            }
            AntigravityAgySettingsRecord(
                enabled = it[AntigravityAgySettings.enabled],
                proxy = it[AntigravityAgySettings.proxy],
                proxyUsername = it[AntigravityAgySettings.proxyUsername],
                proxyPassword = password,
                updatedAt = it[AntigravityAgySettings.updatedAt],
                proxyCredentialsUnreadable = unreadable,
            )
        }
    }

    suspend fun disable(): Long = suspendTransaction(database) {
        val now = System.currentTimeMillis()
        AntigravityAgySettings.update({ AntigravityAgySettings.id eq 1 }) {
            it[enabled] = false
            it[updatedAt] = now
        }
        now
    }

    suspend fun save(settings: AntigravityAgySettingsRecord): AntigravityAgySettingsRecord = suspendTransaction(database) {
        val record = settings.copy(updatedAt = System.currentTimeMillis())
        val encrypted = record.proxyPassword?.let { credentialCipher.encrypt(it, PROXY_PASSWORD_AAD) }
        AntigravityAgySettings.upsert(AntigravityAgySettings.id) {
            it[id] = 1
            it[enabled] = record.enabled
            it[proxy] = record.proxy
            it[proxyUsername] = record.proxyUsername
            it[proxyPasswordCiphertext] = encrypted?.ciphertext.orEmpty()
            it[proxyPasswordNonce] = encrypted?.nonce.orEmpty()
            it[proxyPasswordKeyVersion] = encrypted?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
            it[updatedAt] = record.updatedAt
        }
        record
    }

    private companion object {
        val PROXY_PASSWORD_AAD = "antigravity-agy-proxy-password".toByteArray(Charsets.UTF_8)
    }
}
