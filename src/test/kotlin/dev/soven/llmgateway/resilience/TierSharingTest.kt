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

    private fun channelCandidates(
        position: Int,
        name: String,
        keys: List<Pair<String, Int>>,
        upstreamModel: String = "m",
    ): List<dev.soven.llmgateway.domain.RouteCandidate> {
        val channelKeys = keys.map { (keyName, keyPriority) ->
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
            )
        }
        val channel = ChannelRecord(
            id = "id-$name",
            name = name,
            baseUrl = "https://$name.example",
            authType = "bearer",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
            modelMappings = emptyList(),
            keys = channelKeys,
        )
        return channelKeys.map { key ->
            dev.soven.llmgateway.domain.RouteCandidate(
                channel = channel,
                key = key,
                upstreamModel = upstreamModel,
                position = position,
            )
        }
    }

    @Test
    fun `key priority is scoped to one channel and never outranks channel priority`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(1, "primary-channel", keyName = "low-priority-key", keyPriority = 999),
            candidate(20, "backup-channel", keyName = "high-priority-key", keyPriority = 1),
        )

        repeat(4) {
            assertEquals(
                "low-priority-key",
                sharing.order("shared-model", candidates).first().key.name,
                "跨渠道时渠道档位优先，低档渠道的密钥优先级 1 不应越过高档渠道的密钥优先级 999",
            )
        }
    }

    @Test
    fun `same tier channels rotate before their same priority keys rotate`() {
        val sharing = TierSharing()
        val candidates = channelCandidates(1, "c1", listOf("k1a" to 1, "k1b" to 1)) +
            channelCandidates(1, "c2", listOf("k2a" to 1, "k2b" to 1)) +
            channelCandidates(20, "c5", listOf("k5a" to 1))

        val heads = (1..4).map {
            sharing.order("shared-model", candidates).first().let { "${it.channel.name}/${it.key.name}" }
        }

        assertEquals(listOf("c1/k1a", "c2/k2a", "c1/k1b", "c2/k2b"), heads)
    }

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
    fun `higher priority key wins even when its upstream model name sorts later`() {
        val sharing = TierSharing()
        val primary = candidate(0, "a", keyName = "primary", keyPriority = 10, upstreamModel = "deepseek-v4.1-flash")
        val fallback = candidate(0, "a", keyName = "fallback", keyPriority = 100, upstreamModel = "deepseek-flash")
        val candidates = listOf(fallback, primary)

        repeat(3) {
            val ordered = sharing.order("deepseek-flash", candidates)
            assertEquals(
                listOf("primary" to "deepseek-v4.1-flash", "fallback" to "deepseek-flash"),
                ordered.map { it.key.name to it.upstreamModel },
            )
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

    @Test
    fun `same affinity key consistently routes to the same candidate without advancing round-robin counter`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(0, "c1", keyName = "k1"),
            candidate(0, "c2", keyName = "k2"),
            candidate(0, "c3", keyName = "k3"),
        )

        val fixedKey = "prompt_cache_session_42"
        val firstWithAffinity = sharing.order("shared-model", candidates, fixedKey).map { "${it.channel.name}/${it.key.name}" }

        repeat(5) {
            val repeatWithAffinity = sharing.order("shared-model", candidates, fixedKey).map { "${it.channel.name}/${it.key.name}" }
            assertEquals(firstWithAffinity, repeatWithAffinity, "相同 affinityKey 必须产生完全一致的候选排序")
        }

        // 无 affinityKey 的请求不受影响，首次轮询从初始位置开始
        val normalFirst = sharing.order("shared-model", candidates).map { it.channel.name }
        assertEquals(listOf("c1", "c2", "c3"), normalFirst, "带有 affinityKey 的调用不应推进普通轮询计数器")
    }

    @Test
    fun `different affinity keys distribute across same tier channels`() {
        val sharing = TierSharing()
        val candidates = (1..5).map { candidate(0, "channel-$it") }

        val pickedChannels = (1..50).map { i ->
            sharing.order("shared-model", candidates, "cache_key_$i").first().channel.name
        }.toSet()

        // 50 个不同 key 应能覆盖多个同档渠道（哈希分布均匀）
        assertEquals(5, pickedChannels.size, "不同 affinityKey 应能散列到全部同档渠道")
    }

    @Test
    fun `affinity preserves tier priority and only routes within same tier`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(1, "backup-1"),
            candidate(0, "primary-1"),
            candidate(0, "primary-2"),
            candidate(2, "backup-2"),
        )

        repeat(20) { i ->
            val ordered = sharing.order("shared-model", candidates, "key-$i")
            assertEquals(0, ordered.first().position, "高档位候选优先级始终高于低档位")
            assertEquals(listOf(0, 0, 1, 2), ordered.map { it.position })
        }
    }

    @Test
    fun `affinity key routes consistently to same key within channel`() {
        val sharing = TierSharing()
        val candidates = listOf(
            candidate(0, "c1", keyName = "k1"),
            candidate(0, "c1", keyName = "k2"),
            candidate(0, "c1", keyName = "k3"),
        )

        val fixedKey = "key_affinity_prompt_123"
        val expected = sharing.order("shared-model", candidates, fixedKey).map { it.key.name }

        repeat(5) {
            val actual = sharing.order("shared-model", candidates, fixedKey).map { it.key.name }
            assertEquals(expected, actual, "同渠道内相同 keyPriority 的候选也应保持亲和稳定")
        }
    }

    @Test
    fun `removing an unrelated cooled-down channel preserves affinity for remaining channels`() {
        val sharing = TierSharing()
        val c1 = candidate(0, "c1")
        val c2 = candidate(0, "c2")
        val c3 = candidate(0, "c3")
        val all = listOf(c1, c2, c3)

        // 找一个初始首选不是 c1 的 affinityKey（例如首选为 c2 或 c3）
        val affinityKey = (1..100).map { "prompt_key_$it" }.first { key ->
            sharing.order("scope", all, key).first().channel.name != "c1"
        }
        val originalFirst = sharing.order("scope", all, affinityKey).first().channel.name

        // 当不相关的渠道 c1 进入冷却被剔除时，剩余可用渠道集为 [c2, c3]
        val withoutC1 = listOf(c2, c3)
        val newFirst = sharing.order("scope", withoutC1, affinityKey).first().channel.name

        assertEquals(originalFirst, newFirst, "非首选渠道进入冷却被移除后，原首选渠道亲和性必须保持不变")
    }
}
