package dev.soven.llmgateway.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证固定协议端点路径唯一且格式稳定，并明确 Gemini 使用动态 URL。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class GatewayProtocolTest {
    @Test
    fun `fixed endpoint paths are absolute and unique while Gemini uses a dynamic path`() {
        val fixedEndpoints = GatewayProtocol.entries.mapNotNull { protocol ->
            protocol.endpointPath?.let { path -> protocol to path }
        }
        fixedEndpoints.forEach { (_, path) ->
            assertTrue(path.startsWith("/"), path)
            assertFalse(path.endsWith("/"), path)
        }
        assertEquals(
            fixedEndpoints.size,
            fixedEndpoints.map { it.second }.distinct().size,
        )
        assertNull(GatewayProtocol.GEMINI.endpointPath)
    }

    @Test
    fun `anthropic messages protocol uses native messages endpoint`() {
        assertEquals("messages", GatewayProtocol.MESSAGES.value)
        assertEquals("/messages", assertNotNull(GatewayProtocol.MESSAGES.endpointPath))
    }

    @Test
    fun `protocol parse preserves configured conversion order`() {
        assertEquals(
            listOf(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
            GatewayProtocol.parse("messages,chat_completions,responses,messages"),
        )
    }
}
