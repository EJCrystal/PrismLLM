package dev.soven.llmgateway.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 上游凭据的 AES-256-GCM 加解密。每次加密使用独立 12 字节 nonce，密文与 nonce 分开存储。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class CredentialCipher(masterKey: ByteArray) {
    private val key = SecretKeySpec(masterKey, "AES")
    private val random = SecureRandom()

    fun encrypt(plaintext: String): EncryptedCredential {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedCredential(
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(nonce),
            keyVersion = CURRENT_KEY_VERSION,
        )
    }

    fun decrypt(ciphertext: String, nonce: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)),
        )
        return cipher.doFinal(Base64.getDecoder().decode(ciphertext)).toString(Charsets.UTF_8)
    }

    companion object {
        const val CURRENT_KEY_VERSION = 1
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/**
 * 加密后的上游凭据。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class EncryptedCredential(
    val ciphertext: String,
    val nonce: String,
    val keyVersion: Int,
)
