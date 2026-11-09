package dev.soven.llmgateway.shared.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证管理员密码哈希不可逆且能正确校验。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class PasswordHasherTest {
    @Test
    fun `verifies correct password and rejects wrong one`() {
        val encoded = PasswordHasher.hash("correct horse battery staple")

        assertTrue(PasswordHasher.verify("correct horse battery staple", encoded))
        assertFalse(PasswordHasher.verify("wrong password", encoded))
    }

    @Test
    fun `uses a fresh salt for every hash`() {
        val first = PasswordHasher.hash("same-password")
        val second = PasswordHasher.hash("same-password")

        assertNotEquals(first, second)
        assertTrue(PasswordHasher.verify("same-password", first))
        assertTrue(PasswordHasher.verify("same-password", second))
    }
}
