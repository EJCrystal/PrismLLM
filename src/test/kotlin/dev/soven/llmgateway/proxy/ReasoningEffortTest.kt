package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 验证推理强度按协议读取对应字段，不跨协议取值。
 *
 * @author 文杰
 * @since 2026-10-22
 */
class ReasoningEffortTest {
    @Test
    fun `chat reads top level reasoning effort`() {
        val request = Json.parseToJsonElement(
            """{"reasoning_effort":"high","reasoning":{"effort":"low"}}""",
        ).jsonObject

        assertEquals("high", ReasoningEffort.fromRequest(request, GatewayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun `responses reads nested reasoning effort`() {
        val request = Json.parseToJsonElement(
            """{"reasoning_effort":"high","reasoning":{"effort":"low"}}""",
        ).jsonObject

        assertEquals("low", ReasoningEffort.fromRequest(request, GatewayProtocol.RESPONSES))
    }

    @Test
    fun `returns null when protocol field is absent`() {
        val request = Json.parseToJsonElement(
            """{"reasoning_effort":"high"}""",
        ).jsonObject

        assertNull(ReasoningEffort.fromRequest(request, GatewayProtocol.RESPONSES))
    }
}
