package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class TierSharingTest {
    private fun candidate(position: Int, name: String) = dev.soven.llmgateway.domain.RouteCandidate(
        channel = ChannelRecord(
            id = "id-$name",
            name = name,
            baseUrl = "https://$name.example",
            credentialCiphertext = "c",
            credentialNonce = "n",
            credentialKeyVersion = 1,
            authType = "bearer",
            protocols = setOf(GatewayProtocol.CHAT_COMPLETIONS),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            modelMappings = emptyList(),
        ),
        upstreamModel = "m",
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
}
