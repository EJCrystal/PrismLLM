package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationCredentialFields
import dev.soven.prismllm.admin.domain.NotificationStoredCredentials
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.admin.persistence.NotificationRepository
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.crypto.EncryptedCredential
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** Reports only whether notification credentials are configured, never their values. */
@Serializable
data class NotificationCredentialStatus(
    val urlConfigured: Boolean,
    val bearerTokenConfigured: Boolean,
    val signingSecretConfigured: Boolean,
    val urlUnreadable: Boolean = false,
    val bearerTokenUnreadable: Boolean = false,
    val signingSecretUnreadable: Boolean = false,
)

/** Stored credentials cannot be decrypted (master key mismatch or corrupted row). */
class NotificationCredentialUnreadable : RuntimeException("notification_credential_unreadable")

/**
 * Reads the target's AES-256-GCM encrypted credentials and decrypts them on demand.
 * Plaintext values never leave this class, the transport, or the admin write path.
 *
 * @author 文杰
 * @since 2026-11-16
 */
class NotificationCredentials(
    private val loadStored: suspend (String) -> NotificationStoredCredentials?,
    private val cipher: CredentialCipher,
) {
    constructor(repository: NotificationRepository, cipher: CredentialCipher) : this(
        repository::loadCredentials,
        cipher,
    )

    private val logger = LoggerFactory.getLogger(NotificationCredentials::class.java)

    /** Never throws; unreadable fields are reported as not configured with a dedicated flag. */
    suspend fun status(target: NotificationTarget): NotificationCredentialStatus {
        val stored = loadStored(target.id) ?: return NotificationCredentialStatus(false, false, false)
        val url = decryptOrNull(target.id, NotificationCredentialFields.URL, stored.url)
        val bearerToken = decryptOrNull(target.id, NotificationCredentialFields.BEARER, stored.bearerToken)
        val signingSecret = decryptOrNull(target.id, NotificationCredentialFields.SIGNATURE, stored.signingSecret)
        return NotificationCredentialStatus(
            urlConfigured = url.value != null,
            bearerTokenConfigured = bearerToken.value != null,
            signingSecretConfigured = signingSecret.value != null,
            urlUnreadable = url.unreadable,
            bearerTokenUnreadable = bearerToken.unreadable,
            signingSecretUnreadable = signingSecret.unreadable,
        )
    }

    /** @throws NotificationCredentialUnreadable when a stored field cannot be decrypted. */
    internal suspend fun resolve(target: NotificationTarget): NotificationEnvironment {
        val stored = loadStored(target.id) ?: return NotificationEnvironment(null, null, null)
        return NotificationEnvironment(
            url = decryptRequired(target.id, NotificationCredentialFields.URL, stored.url),
            bearerToken = decryptRequired(target.id, NotificationCredentialFields.BEARER, stored.bearerToken),
            signingSecret = decryptRequired(target.id, NotificationCredentialFields.SIGNATURE, stored.signingSecret),
        )
    }

    private fun decryptRequired(targetId: String, field: String, encrypted: EncryptedCredential?): String? {
        if (encrypted == null) return null
        return try {
            cipher.decrypt(encrypted.ciphertext, encrypted.nonce, NotificationCredentialFields.aad(targetId, field))
        } catch (failure: Exception) {
            // 主密钥轮换或密文损坏时只记录异常类型，避免把密文或明文写入日志。
            logger.warn(
                "notification credential {} of target {} cannot be decrypted; re-enter it in the admin UI ({})",
                field, targetId, failure::class.simpleName,
            )
            throw NotificationCredentialUnreadable()
        }
    }

    private fun decryptOrNull(targetId: String, field: String, encrypted: EncryptedCredential?): DecryptedField {
        if (encrypted == null) return DecryptedField(null, false)
        return try {
            DecryptedField(
                cipher.decrypt(encrypted.ciphertext, encrypted.nonce, NotificationCredentialFields.aad(targetId, field)),
                false,
            )
        } catch (failure: Exception) {
            logger.warn(
                "notification credential {} of target {} cannot be decrypted; re-enter it in the admin UI ({})",
                field, targetId, failure::class.simpleName,
            )
            DecryptedField(null, true)
        }
    }

    private class DecryptedField(val value: String?, val unreadable: Boolean)
}

/** Decrypted credentials used for one delivery attempt. Never expose through API or logs. */
internal class NotificationEnvironment(
    val url: String?,
    val bearerToken: String?,
    val signingSecret: String?,
) {
    override fun toString(): String = "NotificationEnvironment([redacted])"
}
