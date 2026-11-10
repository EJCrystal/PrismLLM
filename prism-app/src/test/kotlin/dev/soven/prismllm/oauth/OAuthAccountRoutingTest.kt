package dev.soven.prismllm.oauth

import dev.soven.prismllm.shared.domain.OAuthProvider

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.oauth.domain.OAuthAccountPolicy
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.domain.OAuthQuotaSnapshot
import dev.soven.prismllm.oauth.domain.OAuthQuotaWindow
import dev.soven.prismllm.oauth.domain.OAuthRoutingStrategy
import dev.soven.prismllm.oauth.infra.OAuthRoutingHint
import dev.soven.prismllm.routing.domain.candidate.RouteCandidate
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthAccountRoutingTest {
    private val now = 1_762_400_000_000L

    @Test
    fun `drain keeps the current account until it leaves the pool`() = runTest {
        val routing = routing(OAuthRoutingStrategy.DRAIN)
        val a = key("a")
        val b = key("b")
        val c = key("c")

        assertEquals(ids("a", "b", "c"), order(routing, a, b, c))
        // a 因额度用尽或冷却被准入过滤后，指针顺延到 b。
        assertEquals(ids("b", "c"), order(routing, b, c))
        // a 恢复后不会回抢，仍在 b 之后。
        assertEquals(ids("b", "a", "c"), order(routing, a, b, c))
    }

    @Test
    fun `drain is scoped by channel and key priority`() = runTest {
        val routing = routing(OAuthRoutingStrategy.DRAIN)
        val a = key("a", priority = 1)
        val b = key("b", priority = 1)
        val c = key("c", priority = 2)
        val d = key("d", priority = 2)

        assertEquals(ids("a", "b", "c", "d"), order(routing, a, b, c, d))
        assertEquals(ids("b", "d"), order(routing, b, d))
        assertEquals(ids("b", "a", "d", "c"), order(routing, a, b, c, d))
    }

    @Test
    fun `least used ranks known accounts and keeps unknown after them`() = runTest {
        val routing = routing(
            OAuthRoutingStrategy.LEAST_USED,
            mapOf("a" to OAuthRoutingHint(quota = quota(primary = 80.0)), "b" to OAuthRoutingHint(quota = quota(primary = 10.0))),
        )

        assertEquals(ids("b", "a", "c"), order(routing, key("a"), key("b"), key("c")))
    }

    @Test
    fun `least used falls back to the local window without a quota reading`() = runTest {
        val routing = routing(
            OAuthRoutingStrategy.LEAST_USED,
            mapOf("a" to OAuthRoutingHint(windowUsed = 8), "b" to OAuthRoutingHint(windowUsed = 2)),
        )
        val keys = arrayOf(key("a", windowLimit = 10), key("b", windowLimit = 10), key("c", windowLimit = 10))

        // 从未使用过的 c 按 0 计，排在已用 20% 与 80% 的账号之前。
        assertEquals(ids("c", "b", "a"), order(routing, *keys))
    }

    @Test
    fun `pace prefers the account that loses the most quota per hour`() = runTest {
        val routing = routing(
            OAuthRoutingStrategy.PACE,
            mapOf(
                "a" to OAuthRoutingHint(quota = quotaWithRemaining(remaining = 10.0, hours = 1.0)),
                "b" to OAuthRoutingHint(quota = quotaWithRemaining(remaining = 60.0, hours = 10.0)),
                "c" to OAuthRoutingHint(quota = quotaWithRemaining(remaining = 90.0, hours = 100.0)),
            ),
        )

        assertEquals(ids("a", "b", "c", "d"), order(routing, key("a"), key("b"), key("c"), key("d")))
    }

    @Test
    fun `pace clamps a corrupt reset time instead of overflowing`() = runTest {
        val routing = routing(
            OAuthRoutingStrategy.PACE,
            mapOf(
                "a" to OAuthRoutingHint(
                    quota = OAuthQuotaSnapshot(
                        primary = OAuthQuotaWindow(
                            usedPercent = 50.0,
                            windowSeconds = 18_000,
                            resetsAt = Long.MIN_VALUE,
                        ),
                        checkedAt = now,
                    ),
                ),
                "b" to OAuthRoutingHint(quota = quotaWithRemaining(remaining = 50.0, hours = 1.0)),
            ),
        )

        // 损坏的重置时间按“即将重置”计，而不是溢出成极长剩余时间。
        assertEquals(ids("a", "b"), order(routing, key("a"), key("b")))
    }

    @Test
    fun `pace clamps a corrupt local window deadline`() = runTest {
        val routing = routing(
            OAuthRoutingStrategy.PACE,
            mapOf(
                "a" to OAuthRoutingHint(windowUsed = 5, windowOldestAt = Long.MAX_VALUE),
                "b" to OAuthRoutingHint(windowUsed = 5, windowOldestAt = now),
            ),
        )

        // 饱和后的截止时间远在未来，a 的余量摊薄，排在 b 之后。
        assertEquals(ids("b", "a"), order(routing, key("a", windowLimit = 10), key("b", windowLimit = 10)))
    }

    @Test
    fun `static keys keep their slots and affinity wins over the strategy`() = runTest {
        val routing = routing(
            OAuthRoutingStrategy.LEAST_USED,
            mapOf("a" to OAuthRoutingHint(quota = quota(primary = 90.0)), "b" to OAuthRoutingHint(quota = quota(primary = 10.0))),
        )
        val a = key("a")
        val b = key("b")
        val static = ChannelKeyRecord(
            id = "s",
            channelId = "ch",
            name = "s",
            credentialCiphertext = "c",
            credentialNonce = "n",
            credentialKeyVersion = 1,
            state = ChannelKeyState.ACTIVE,
            priority = 1,
            models = listOf("m"),
            createdAt = 0,
            updatedAt = 0,
        )
        val candidates = listOf(candidate(a), staticCandidate(static), candidate(b))

        assertEquals(
            ids("b", "s", "a"),
            routing.order(candidates, affinityApplied = false).map { it.credential.id },
        )
        assertEquals(
            ids("a", "s", "b"),
            routing.order(candidates, affinityApplied = true).map { it.credential.id },
        )
    }

    @Test
    fun `round robin keeps the incoming order`() = runTest {
        val routing = routing(OAuthRoutingStrategy.ROUND_ROBIN)
        assertEquals(ids("c", "a", "b"), order(routing, key("c"), key("a"), key("b")))
    }

    private fun routing(
        strategy: OAuthRoutingStrategy,
        readings: Map<String, OAuthRoutingHint> = emptyMap(),
    ) = OAuthAccountRouting(
        strategy = strategy,
        hints = { keys, _ -> keys.mapNotNull { key -> readings[key.id]?.let { key.id to it } }.toMap() },
        now = { now },
    )

    private suspend fun order(routing: OAuthAccountRouting, vararg keys: OAuthAccountRecord): List<String> =
        routing.order(keys.map(::candidate), affinityApplied = false).map { it.credential.id }

    private fun ids(vararg ids: String): List<String> = ids.toList()

    private fun key(id: String, priority: Int = 1, windowLimit: Int? = null): OAuthAccountRecord = OAuthAccountRecord(
        id = id,
        name = id,
        provider = dev.soven.prismllm.shared.domain.OAuthProvider.CHATGPT,
        refreshTokenCiphertext = "c",
        refreshTokenNonce = "n",
        refreshTokenKeyVersion = 1,
        priority = priority,
        models = listOf("m"),
        createdAt = 0,
        updatedAt = 0,
        policy = OAuthAccountPolicy(windowLimit = windowLimit, maxConcurrentRequests = 5),
    )

    private fun candidate(account: OAuthAccountRecord): RouteCandidate = RouteCandidate(
        channel = channel(),
        credential = account,
        upstreamModel = "m",
        position = 0,
    )

    private fun channel() = ChannelRecord(
        id = "ch",
        name = "ch",
        baseUrl = "https://example.test",
        authType = "bearer",
        protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
        enabled = true,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun staticCandidate(key: ChannelKeyRecord): RouteCandidate = RouteCandidate(
        channel = ChannelRecord(
            id = key.channelId,
            name = key.channelId,
            baseUrl = "https://example.test",
            authType = "bearer",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            enabled = true,
            createdAt = 0,
            updatedAt = 0,
        ),
        credential = key,
        upstreamModel = "m",
        position = 0,
    )

    private fun quota(primary: Double, secondary: Double? = null): OAuthQuotaSnapshot = OAuthQuotaSnapshot(
        primary = OAuthQuotaWindow(primary, windowSeconds = 18_000, resetsAt = now + 3_600_000),
        secondary = secondary?.let {
            OAuthQuotaWindow(it, windowSeconds = 604_800, resetsAt = now + 86_400_000)
        },
        allowed = true,
        checkedAt = now,
    )

    private fun quotaWithRemaining(remaining: Double, hours: Double): OAuthQuotaSnapshot = OAuthQuotaSnapshot(
        primary = OAuthQuotaWindow(
            usedPercent = (100.0 - remaining).coerceAtLeast(0.0),
            windowSeconds = 604_800,
            resetsAt = now + (hours * 3_600_000).toLong(),
        ),
        allowed = true,
        checkedAt = now,
    )
}
