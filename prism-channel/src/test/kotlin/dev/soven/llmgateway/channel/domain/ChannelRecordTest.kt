package dev.soven.llmgateway.channel.domain

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelRecordTest {
    private class Credential(
        override val id: String,
        override val name: String = id,
        override val priority: Int = 1,
        override val models: List<String> = emptyList(),
        override val enabled: Boolean = true,
    ) : UpstreamCredential {
        override val isOAuth: Boolean get() = false

        override fun supports(upstreamModel: String): Boolean = upstreamModel in models

        override fun isRoutable(now: Long): Boolean = enabled
    }

    private fun channel(credentials: List<UpstreamCredential>) = ChannelRecord(
        id = "channel-1",
        name = "channel",
        baseUrl = "https://example.invalid",
        authType = "bearer",
        protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
        enabled = true,
        createdAt = 0,
        updatedAt = 0,
        modelMappings = listOf(ChannelModelRecord("m1"), ChannelModelRecord("m2")),
        oauthAccounts = credentials,
    )

    @Test
    fun `credentialsFor filters by model and orders by priority name id`() {
        val channel = channel(
            listOf(
                Credential("b", priority = 1, models = listOf("m1")),
                Credential("a", priority = 1, models = listOf("m1")),
                Credential("c", priority = 0, models = listOf("m1")),
                Credential("d", priority = 1, models = listOf("m2")),
                Credential("e", priority = 1, models = listOf("m1"), enabled = false),
            ),
        )

        assertEquals(listOf("c", "a", "b"), channel.credentialsFor("m1", now = 0).map { it.id })
    }

    @Test
    fun `routableCredentialCount ignores disabled and unauthorized credentials`() {
        val channel = channel(
            listOf(
                Credential("authorized", models = listOf("m1")),
                Credential("outside-catalog", models = listOf("m3")),
                Credential("disabled", models = listOf("m1"), enabled = false),
            ),
        )

        assertEquals(1, channel.routableCredentialCount(now = 0))
    }
}