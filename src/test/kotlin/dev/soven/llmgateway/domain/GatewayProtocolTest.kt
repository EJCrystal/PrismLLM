package dev.soven.llmgateway.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证协议路径是唯一且以斜杠开头的相对路径。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class GatewayProtocolTest {
    @Test
    fun `protocol paths are absolute and unique`() {
        GatewayProtocol.entries.forEach { protocol ->
            assertTrue(protocol.path.startsWith("/"), protocol.path)
            assertFalse(protocol.path.endsWith("/"), protocol.path)
        }
        assertEquals(
            GatewayProtocol.entries.size,
            GatewayProtocol.entries.map { it.path }.distinct().size,
        )
    }
}
