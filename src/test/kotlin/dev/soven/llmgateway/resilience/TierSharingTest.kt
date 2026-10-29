package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class TierSharingTest {
    private fun candidate(
        position: Int,
        name: String,
        keyName: String = "default",
        keyPriority: Int = 1,
        upstreamModel: String = "m",
    ) = dev.soven.llmgateway.domain.RouteCandidate(
        channel = ChannelRecord(
            id = "id-$name",
            name = name,
            baseUrl = "https://$name.example",
            authType = "bearer",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            modelMappings = emptyList(),
            keys = listOf(
                ChannelKeyRecord(
                    id = "key-$name-$keyName",
                    channelId = "id-$name",
                    name = keyName,
                    credentialCiphertext = "c",
                    credentialNonce = "n",
                    credentialKeyVersion = 1,
                    state = ChannelKeyState.ACTIVE,
                    priority = keyPriority,
                    models = listOf(upstreamModel),
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ),
        ),
        key = ChannelKeyRecord(
            id = "key-$name-$keyName",
            channelId = "id-$name",
            name = keyName,
            credentialCiphertext = "c",
            credentialNonce = "n",
            credentialKeyVersion = 1,
            state = ChannelKeyState.ACTIVE,
            priority = keyPriority,
            models = listOf(upstreamModel),
            createdAt = 0,
            updatedAt = 0,
        ),
        upstreamModel = upstreamModel,
        position = position,
    )

    @Test
    fun `same tier candidates rotate across requests`() {
        val sharing = TierSharing()
        val candidates = listOf(candidate(0, "a"), candidate(0, "b"), candidate(0, "c"))

        val first = sharing.order("alias", candidates).map { it.channel.name }
        val second = sharing.order("alias", candidates).map { it.channel.name }
        val third = sharing.order("alias", candidates).map { it.channel.name }

        assertEquals(listOf("a", "b", "c"), first)
        assertEquals(listOf("b", "c", "a"), second)
        assertEquals(listOf("c", "a", "b"), third)
    }

    @Test
    fun `tier order is preserved and lower tiers only serve as fallback`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(1, "backup"),
            candidate(0, "primary-a"),
            candidate(0, "primary-b"),
        )

        val ordered = sharing.order("alias", candidates).map { "${it.position}:${it.channel.name}" }
        assertEquals(listOf("0:primary-a", "0:primary-b", "1:backup"), ordered)

        // 轮转只发生在档内，档间顺序不变
        repeat(5) {
            val again = sharing.order("alias", candidates)
            assertEquals(0, again.first().position)
            assertEquals(listOf(1, 0, 0), again.map { it.position }.sortedDescending())
        }
    }

    @Test
    fun `single candidate is returned unchanged`() {
        val sharing = TierSharing()
        val only = candidate(0, "a")
        assertEquals(listOf(only), sharing.order("alias", listOf(only)))
    }

    @Test
    fun `upstream aliases in one channel stay together as one routing unit`() {
        val sharing = TierSharing()
        val primary = candidate(0, "a")
        val secondary = candidate(0, "b")
        val aliases = listOf(
            primary.copy(upstreamModel = "alias-1"),
            primary.copy(upstreamModel = "alias-2"),
            secondary,
        )

        val first = sharing.order("shared-model", aliases)
        val second = sharing.order("shared-model", aliases)

        assertEquals(listOf("alias-1", "alias-2", "m"), first.map { it.upstreamModel })
        assertEquals(listOf("m", "alias-1", "alias-2"), second.map { it.upstreamModel })
    }

    @Test
    fun `same channel keys rotate within the same key priority`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(0, "a", keyName = "k1"),
            candidate(0, "a", keyName = "k2"),
            candidate(0, "b", keyName = "k1"),
        )

        val first = sharing.order("shared-model", candidates).map { "${it.channel.name}/${it.key.name}" }
        val second = sharing.order("shared-model", candidates).map { "${it.channel.name}/${it.key.name}" }

        assertEquals(listOf("a/k1", "a/k2", "b/k1"), first)
        assertEquals(listOf("b/k1", "a/k2", "a/k1"), second)
    }

    @Test
    fun `lower priority keys stay behind higher priority keys in one channel`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(0, "a", keyName = "primary", keyPriority = 1),
            candidate(0, "a", keyName = "backup", keyPriority = 2),
        )

        repeat(4) {
            val ordered = sharing.order("shared-model", candidates).map { it.key.name }
            assertEquals(listOf("primary", "backup"), ordered)
        }
    }

    @Test
    fun `channel and key rotations do not stay correlated`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(0, "a", keyName = "k1"),
            candidate(0, "a", keyName = "k2"),
            candidate(0, "b", keyName = "k1"),
            candidate(0, "b", keyName = "k2"),
        )

        val firstChoices = (1..4).map {
            sharing.order("shared-model", candidates).first().let { "${it.channel.name}/${it.key.name}" }
        }

        assertEquals(setOf("a/k1", "a/k2", "b/k1", "b/k2"), firstChoices.toSet())
    }
}
