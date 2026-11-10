package dev.soven.prismllm.routing.domain.ordering

import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.UpstreamCredential
import dev.soven.prismllm.routing.domain.candidate.RouteCandidate
import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderingRulesTest {
    private class Credential(
        override val id: String,
        override val name: String = id,
        override val priority: Int = 1,
    ) : UpstreamCredential {
        override val models: List<String> = listOf("m")
        override val enabled: Boolean = true
        override val isOAuth: Boolean = false

        override fun supports(upstreamModel: String): Boolean = upstreamModel in models

        override fun isRoutable(now: Long): Boolean = enabled
    }

    private fun candidate(
        channelId: String,
        channelName: String = channelId,
        position: Int,
        priority: Int,
        credentialName: String = "key",
        upstreamModel: String = "m",
        protocols: List<GatewayProtocol> = listOf(GatewayProtocol.CHAT_COMPLETIONS),
    ) = RouteCandidate(
        channel = ChannelRecord(
            id = channelId,
            name = channelName,
            baseUrl = "https://example.invalid",
            authType = "bearer",
            protocols = protocols,
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
        ),
        credential = Credential(
            id = "$channelId-$credentialName",
            name = credentialName,
            priority = priority,
        ),
        upstreamModel = upstreamModel,
        position = position,
    )

    @Test
    fun `initial order follows position then channel credential and upstream model`() {
        val candidates = listOf(
            candidate("b", position = 1, priority = 0),
            candidate("a", position = 0, priority = 2, credentialName = "z"),
            candidate("a", position = 0, priority = 1, credentialName = "b", upstreamModel = "z"),
            candidate("a", position = 0, priority = 1, credentialName = "a", upstreamModel = "z"),
            candidate("a", position = 0, priority = 1, credentialName = "a", upstreamModel = "a"),
        )

        assertEquals(
            listOf("a/a/a", "a/a/z", "a/b/z", "a/z/m", "b/key/m"),
            OrderingRules.initialOrder(candidates).map { "${it.channel.name}/${it.credential.name}/${it.upstreamModel}" },
        )
    }

    @Test
    fun `native protocol candidates are moved ahead without reordering either group`() {
        val candidates = listOf(
            candidate("conversion-a", position = 0, priority = 0, protocols = listOf(GatewayProtocol.RESPONSES)),
            candidate("native-a", position = 1, priority = 0),
            candidate("conversion-b", position = 0, priority = 0, protocols = listOf(GatewayProtocol.RESPONSES)),
            candidate("native-b", position = 2, priority = 0),
        )

        assertEquals(
            listOf("native-a", "native-b", "conversion-a", "conversion-b"),
            OrderingRules.nativeProtocolFirst(candidates, GatewayProtocol.CHAT_COMPLETIONS).map { it.channel.id },
        )
    }
}