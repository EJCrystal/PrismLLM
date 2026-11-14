package dev.soven.prismllm.domain

import dev.soven.prismllm.shared.domain.AUTH_TYPE_ANTHROPIC
import dev.soven.prismllm.shared.domain.AUTH_TYPE_BEARER
import dev.soven.prismllm.shared.domain.AUTH_TYPE_GOOGLE
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.effectiveAuthType

import dev.soven.prismllm.proxy.modelListProtocol
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("unit")
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

    @Test
    fun `ordinary protocol overrides do not replace legacy special model discovery`() {
        for ((authType, legacyProtocol) in listOf(
            AUTH_TYPE_GOOGLE to GatewayProtocol.GEMINI,
            AUTH_TYPE_ANTHROPIC to GatewayProtocol.MESSAGES,
        )) {
            for (ordinaryProtocol in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES)) {
                assertEquals(
                    legacyProtocol,
                    modelListProtocol(
                        listOf(ordinaryProtocol, legacyProtocol), authType,
                        mapOf(ordinaryProtocol.value to AUTH_TYPE_BEARER),
                    ),
                )
            }
        }
    }

    @Test
    fun `explicit native protocol overrides still precede legacy special model discovery`() {
        val protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI)
        assertEquals(
            GatewayProtocol.GEMINI,
            modelListProtocol(protocols, AUTH_TYPE_ANTHROPIC, mapOf(
                GatewayProtocol.GEMINI.value to AUTH_TYPE_BEARER,
                GatewayProtocol.MESSAGES.value to AUTH_TYPE_BEARER,
            )),
        )
        assertEquals(
            GatewayProtocol.MESSAGES,
            modelListProtocol(protocols, AUTH_TYPE_GOOGLE, mapOf(GatewayProtocol.MESSAGES.value to AUTH_TYPE_BEARER)),
        )
    }
}
