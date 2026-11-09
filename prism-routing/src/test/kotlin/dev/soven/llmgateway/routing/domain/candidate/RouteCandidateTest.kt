package dev.soven.llmgateway.routing.domain.candidate

import dev.soven.llmgateway.channel.domain.ChannelModelRecord
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.shared.domain.UpstreamCredential
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteCandidateTest {
    private class Credential(override val id: String = "key") : UpstreamCredential {
        override val name: String = id
        override val priority: Int = 1
        override val models: List<String> = listOf("upstream-model")
        override val enabled: Boolean = true
        override val isOAuth: Boolean = false

        override fun supports(upstreamModel: String): Boolean = upstreamModel in models

        override fun isRoutable(now: Long): Boolean = enabled
    }

    private fun candidate(
        upstreamModel: String,
        mappings: List<ChannelModelRecord>,
    ) = RouteCandidate(
        channel = ChannelRecord(
            id = "channel-1",
            name = "channel",
            baseUrl = "https://example.invalid",
            authType = "bearer",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            modelMappings = mappings,
        ),
        credential = Credential(),
        upstreamModel = upstreamModel,
        position = 0,
    )

    @Test
    fun `pricing model prefers the trimmed canonical id from the channel mapping`() {
        val candidate = candidate(
            upstreamModel = " upstream-model ",
            mappings = listOf(ChannelModelRecord("upstream-model", canonicalModelId = " canonical-model ")),
        )

        assertEquals("canonical-model", candidate.pricingModelId)
    }

    @Test
    fun `pricing model falls back to the trimmed upstream name`() {
        val candidate = candidate(
            upstreamModel = " upstream-model ",
            mappings = listOf(ChannelModelRecord("upstream-model", canonicalModelId = " ")),
        )

        assertEquals("upstream-model", candidate.pricingModelId)
    }
}