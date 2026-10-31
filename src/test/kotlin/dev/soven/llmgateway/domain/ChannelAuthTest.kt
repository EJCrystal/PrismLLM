package dev.soven.llmgateway.domain

import dev.soven.llmgateway.proxy.modelListProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelAuthTest {
    @Test
    fun `protocol override wins and missing entries inherit legacy channel auth`() {
        val overrides = mapOf(
            GatewayProtocol.RESPONSES.value to AUTH_TYPE_ANTHROPIC,
            GatewayProtocol.MESSAGES.value to AUTH_TYPE_GOOGLE,
        )

        assertEquals(AUTH_TYPE_ANTHROPIC, effectiveAuthType(AUTH_TYPE_BEARER, overrides, GatewayProtocol.RESPONSES))
        assertEquals(AUTH_TYPE_GOOGLE, effectiveAuthType(AUTH_TYPE_BEARER, overrides, GatewayProtocol.MESSAGES))
        assertEquals(AUTH_TYPE_BEARER, effectiveAuthType(AUTH_TYPE_BEARER, overrides, GatewayProtocol.CHAT_COMPLETIONS))
        assertEquals(AUTH_TYPE_BEARER, effectiveAuthType(AUTH_TYPE_BEARER, overrides))
    }

    @Test
    fun `model discovery selects a native special protocol override before legacy auth fallback`() {
        val protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES)

        assertEquals(
            GatewayProtocol.MESSAGES,
            modelListProtocol(protocols, AUTH_TYPE_BEARER, mapOf(GatewayProtocol.MESSAGES.value to AUTH_TYPE_ANTHROPIC)),
        )
        assertEquals(
            GatewayProtocol.RESPONSES,
            modelListProtocol(protocols, AUTH_TYPE_BEARER, mapOf(GatewayProtocol.RESPONSES.value to AUTH_TYPE_GOOGLE)),
        )
        assertEquals(
            GatewayProtocol.MESSAGES,
            modelListProtocol(protocols, AUTH_TYPE_ANTHROPIC),
        )
        assertEquals(
            GatewayProtocol.GEMINI,
            modelListProtocol(listOf(GatewayProtocol.GEMINI), AUTH_TYPE_BEARER),
        )
    }
}
