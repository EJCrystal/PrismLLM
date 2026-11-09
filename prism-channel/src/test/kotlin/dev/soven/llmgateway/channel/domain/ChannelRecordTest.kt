package dev.soven.llmgateway.channel.domain

import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelRecordTest {
    /**
     * @param routable 模拟订阅账号的授权状态：OAuth token 失效时为 false，
     * 此时账号本身仍启用，但不参与路由。
     */
    private class Credential(
        override val id: String,
        override val name: String = id,
        override val priority: Int = 1,
        override val models: List<String> = emptyList(),
        override val enabled: Boolean = true,
        override val isOAuth: Boolean = false,
        private val routable: Boolean = true,
    ) : UpstreamCredential {
        override fun supports(upstreamModel: String): Boolean = upstreamModel in models

        override fun isRoutable(now: Long): Boolean = enabled && routable
    }

    private fun channel(
        credentials: List<UpstreamCredential> = emptyList(),
        keys: List<ChannelKeyRecord> = emptyList(),
    ) = ChannelRecord(
        id = "channel-1",
        name = "channel",
        baseUrl = "https://example.invalid",
        authType = "bearer",
        protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
        enabled = true,
        createdAt = 0,
        updatedAt = 0,
        keys = keys,
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
    fun `credentialsFor merges static keys and oauth accounts in one stable order`() {
        val channel = channel(
            credentials = listOf(Credential("oauth-b", priority = 0, models = listOf("m1"))),
            keys = listOf(
                ChannelKeyRecord(
                    id = "key-a",
                    channelId = "channel-1",
                    name = "key-a",
                    credentialCiphertext = "cipher",
                    credentialNonce = "nonce",
                    credentialKeyVersion = 1,
                    state = ChannelKeyState.ACTIVE,
                    priority = 1,
                    models = listOf("m1"),
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ),
        )

        assertEquals(listOf("oauth-b", "key-a"), channel.credentialsFor("m1", now = 0).map { it.id })
    }

    @Test
    fun `routableCredentialCount ignores disabled unauthorized and out-of-catalog credentials`() {
        val channel = channel(
            listOf(
                Credential("authorized", models = listOf("m1")),
                Credential("outside-catalog", models = listOf("m3")),
                Credential("disabled", models = listOf("m1"), enabled = false),
                Credential("unauthorized", models = listOf("m1"), isOAuth = true, routable = false),
            ),
        )

        assertEquals(1, channel.routableCredentialCount(now = 0))
    }
}