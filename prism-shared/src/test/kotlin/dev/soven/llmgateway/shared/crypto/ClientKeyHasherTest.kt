package dev.soven.llmgateway.shared.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证调用方密钥具备可识别前缀、稳定哈希和足够随机性。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class ClientKeyHasherTest {
    @Test
    fun `generates prefixed key and stable hash`() {
        val generated = ClientKeyHasher.generate()

        assertTrue(generated.raw.startsWith(ClientKeyHasher.PREFIX))
        assertEquals(64, generated.hash.length)
        assertEquals(generated.hash, ClientKeyHasher.hash(generated.raw))
        assertTrue(generated.raw.startsWith(generated.prefix))
    }

    @Test
    fun `generates distinct keys`() {
        assertNotEquals(ClientKeyHasher.generate().raw, ClientKeyHasher.generate().raw)
    }
}
