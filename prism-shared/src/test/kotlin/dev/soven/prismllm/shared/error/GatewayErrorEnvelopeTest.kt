package dev.soven.prismllm.shared.error

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Anthropic 兼容错误信封没有 upstream_code 字段，必须把上游错误码带进 message，
 * 否则 Claude Code / Cindy 只能看到“上游返回错误 400”。
 *
 * @author 文杰
 * @since 2026-11-27
 */
class GatewayErrorEnvelopeTest {
    @Test
    fun `anthropic envelope appends upstream code to message`() {
        val error = GatewayError(
            status = HttpStatusCode.BadRequest,
            code = "upstream_error",
            message = "上游返回错误 400",
            upstreamCode = "session_turn_limit_exceeded",
        )

        val envelope = error.toAnthropicEnvelope()

        assertEquals("invalid_request_error", envelope.error.type)
        assertTrue(envelope.error.message.contains("session_turn_limit_exceeded"))
        assertFalse(envelope.error.message == error.message)
    }

    @Test
    fun `anthropic envelope leaves message unchanged without upstream code`() {
        val error = GatewayError(
            status = HttpStatusCode.BadRequest,
            code = "upstream_error",
            message = "上游返回错误 400",
        )

        assertEquals("上游返回错误 400", error.toAnthropicEnvelope().error.message)
    }

    @Test
    fun `anthropic envelope does not duplicate an upstream code already in message`() {
        val message = "上游返回错误 400 (upstream_code: session_turn_limit_exceeded)"
        val error = GatewayError(
            status = HttpStatusCode.BadRequest,
            code = "upstream_error",
            message = message,
            upstreamCode = "session_turn_limit_exceeded",
        )

        assertEquals(message, error.toAnthropicEnvelope().error.message)
    }

    @Test
    fun `openai envelope keeps upstream code in its own field`() {
        val error = GatewayError(
            status = HttpStatusCode.BadRequest,
            code = "upstream_error",
            message = "上游返回错误 400",
            upstreamCode = "session_turn_limit_exceeded",
        )

        assertEquals("session_turn_limit_exceeded", error.toEnvelope().error.upstream_code)
        assertEquals("上游返回错误 400", error.toEnvelope().error.message)
    }
}
