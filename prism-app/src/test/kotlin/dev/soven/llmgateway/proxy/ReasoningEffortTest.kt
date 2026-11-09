package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.shared.domain.GatewayProtocol
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
        assertNull(ReasoningEffort.fromRequest(request, GatewayProtocol.MESSAGES))
    }

    @Test
    fun `messages reads output_config effort`() {
        val request = Json.parseToJsonElement(
            """{"output_config":{"effort":"high"}}""",
        ).jsonObject

        assertEquals("high", ReasoningEffort.fromRequest(request, GatewayProtocol.MESSAGES))
    }

    @Test
    fun `messages reads thinking effort`() {
        val request = Json.parseToJsonElement(
            """{"thinking":{"type":"enabled","effort":"xhigh"}}""",
        ).jsonObject

        assertEquals("xhigh", ReasoningEffort.fromRequest(request, GatewayProtocol.MESSAGES))
    }

    @Test
    fun `messages maps budget_tokens to reasoning effort tiers`() {
        val cases = listOf(
            1024L to "low",
            4096L to "medium",
            8192L to "high",
            16384L to "xhigh",
            32768L to "max",
        )
        cases.forEach { (budget, expected) ->
            val request = Json.parseToJsonElement(
                """{"thinking":{"type":"enabled","budget_tokens":$budget}}""",
            ).jsonObject
            assertEquals(expected, ReasoningEffort.fromRequest(request, GatewayProtocol.MESSAGES))
        }
    }

    @Test
    fun `messages returns none when thinking is disabled`() {
        val request = Json.parseToJsonElement(
            """{"thinking":{"type":"disabled"}}""",
        ).jsonObject

        assertEquals("none", ReasoningEffort.fromRequest(request, GatewayProtocol.MESSAGES))
    }

    @Test
    fun `messages prefers output_config effort over thinking budget`() {
        val request = Json.parseToJsonElement(
            """{"output_config":{"effort":"high"},"thinking":{"type":"enabled","budget_tokens":1024}}""",
        ).jsonObject

        assertEquals("high", ReasoningEffort.fromRequest(request, GatewayProtocol.MESSAGES))
    }
}
