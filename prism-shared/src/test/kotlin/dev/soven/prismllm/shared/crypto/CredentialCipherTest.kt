package dev.soven.prismllm.shared.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * 验证上游凭据加密后不可直接读取，且错误密钥不能解密。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class CredentialCipherTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `round trips credential without storing plaintext`() {
        val cipher = CredentialCipher(key)

        val encrypted = cipher.encrypt("sk-upstream-secret")

        assertNotEquals("sk-upstream-secret", encrypted.ciphertext)
        assertEquals("sk-upstream-secret", cipher.decrypt(encrypted.ciphertext, encrypted.nonce))
    }

    @Test
    fun `uses a fresh nonce for every encryption`() {
        val cipher = CredentialCipher(key)

        val first = cipher.encrypt("same-secret")
        val second = cipher.encrypt("same-secret")

        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.ciphertext, second.ciphertext)
    }

    @Test
    fun `rejects decryption with a different master key`() {
        val encrypted = CredentialCipher(key).encrypt("secret")
        val other = CredentialCipher(ByteArray(32) { (it + 1).toByte() })

        assertFailsWith<Exception> { other.decrypt(encrypted.ciphertext, encrypted.nonce) }
    }
}
