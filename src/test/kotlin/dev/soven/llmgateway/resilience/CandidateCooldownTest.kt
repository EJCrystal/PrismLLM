package dev.soven.llmgateway.resilience

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandidateCooldownTest {
    private fun policy(
        transientBaseMs: Long = 1_000,
        transientMaxMs: Long = 4_000,
        rateLimitMaxMs: Long = 60_000,
        authFailureMs: Long = 10_000,
        ttftSlowThresholdMs: Long = 500,
        ttftSlowStreak: Int = 2,
    ) = CooldownPolicy(
        transientBaseMs = transientBaseMs,
        transientMaxMs = transientMaxMs,
        rateLimitDefaultMs = 1_000,
        rateLimitMaxMs = rateLimitMaxMs,
        authFailureMs = authFailureMs,
        ttftSlowThresholdMs = ttftSlowThresholdMs,
        ttftSlowStreak = ttftSlowStreak,
    )

    @Test
    fun `transport failure cools channel key and blocks all models on the channel`() {
        var now = 10_000L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        assertEquals(CandidateCooldown.REASON_TRANSIENT, cooldown.recordFailure(permit, FailureKind.TRANSPORT)?.reason)

        assertNull(cooldown.acquire("ch1", "m1"))
        // 渠道键连坐：同渠道其他模型也被阻塞
        assertNull(cooldown.acquire("ch1", "m2"))
        // 其他渠道不受影响
        assertNotNull(cooldown.acquire("ch2", "m1"))
    }

    @Test
    fun `rate limit cools only the candidate key and honors Retry-After with cap`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        assertEquals(
            CandidateCooldown.REASON_RATE_LIMITED,
            cooldown.recordFailure(permit, FailureKind.RATE_LIMITED, retryAfterMs = 5_000)?.reason,
        )

        assertNull(cooldown.acquire("ch1", "m1"))
        // 候选键不连坐同渠道其他模型
        assertNotNull(cooldown.acquire("ch1", "m2"))

        // Retry-After（5s）到期后可探测
        now = 5_000L
        val expired = assertNotNull(cooldown.acquire("ch1", "m1"))
        // Retry-After 超过封顶时按封顶（60s）算
        val trigger = cooldown.recordFailure(expired, FailureKind.RATE_LIMITED, retryAfterMs = 120_000)
        assertEquals(CandidateCooldown.REASON_RATE_LIMITED, trigger?.reason)
        assertNull(cooldown.acquire("ch1", "m1"))
        now = 65_000L
        assertNotNull(cooldown.acquire("ch1", "m1"))
    }

    @Test
    fun `auth failure takes long cooldown and model not found does not cool`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        assertEquals(CandidateCooldown.REASON_AUTH, cooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)?.reason)
        assertNull(cooldown.acquire("ch1", "m1"))

        now = 5_000L
        assertNull(cooldown.acquire("ch1", "m1"), "auth 冷却应远未到期")

        val notFound = assertNotNull(cooldown.acquire("ch2", "m1"))
        assertNull(cooldown.recordFailure(notFound, FailureKind.MODEL_NOT_FOUND))
        assertNotNull(cooldown.acquire("ch2", "m1"))
    }

    @Test
    fun `cooldown expiry releases a single probe and success fully recovers`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "m1"))
        assertTrue(probe.refs.any { it.probing })
        // 探测中：其余请求（含其他模型，渠道键在探测）被阻塞
        assertNull(cooldown.acquire("ch1", "m1"))
        assertNull(cooldown.acquire("ch1", "m2"))
        cooldown.recordSuccess(probe)
        assertNotNull(cooldown.acquire("ch1", "m1"))
        assertNotNull(cooldown.acquire("ch1", "m2"))
    }

    @Test
    fun `failed probe doubles the cooldown`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(transientBaseMs = 1_000, transientMaxMs = 60_000), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "m1"))
        assertEquals(CandidateCooldown.REASON_TRANSIENT, cooldown.recordFailure(probe, FailureKind.TRANSPORT)?.reason)

        // 翻倍后冷却到 2s 起步：1s..2s 之间仍阻塞
        now = 2_500L
        assertNull(cooldown.acquire("ch1", "m1"))
        now = 3_000L
        assertNotNull(cooldown.acquire("ch1", "m1"))
    }

    @Test
    fun `force acquire permits attempts regardless of cooldown`() {
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { 0L })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)
        assertNull(cooldown.acquire("ch1", "m1"))

        val forced = cooldown.forceAcquire("ch1", "m1")
        assertFalse(forced.refs.any { it.probing })
        // fail-open 成功后完全恢复
        cooldown.recordSuccess(forced)
        assertNotNull(cooldown.acquire("ch1", "m1"))
    }

    @Test
    fun `release returns probe slot without reward or penalty`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "m1"))
        assertNull(cooldown.acquire("ch1", "m1"))
        cooldown.release(probe)
        // 归还后下一个请求可再次探测
        assertNotNull(cooldown.acquire("ch1", "m1"))
    }

    @Test
    fun `late results from an older generation are ignored`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val stale = assertNotNull(cooldown.acquire("ch1", "m1"))
        val fresh = assertNotNull(cooldown.acquire("ch1", "m2"))
        cooldown.recordFailure(fresh, FailureKind.TRANSPORT)

        // 迟到的成功不能凭旧代际恢复渠道键
        cooldown.recordSuccess(stale)
        assertNull(cooldown.acquire("ch1", "m3"))

        // 迟到的失败也不能凭旧代际延长或二次触发
        assertNull(cooldown.recordFailure(stale, FailureKind.TRANSPORT))
    }

    @Test
    fun `http response failure resets other keys while cooling the target`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        // 先让渠道键进入冷却
        val warmup = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordFailure(warmup, FailureKind.TRANSPORT)

        // 冷却到期，m2 的请求拿到渠道键探测资格
        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "m2"))
        // 这次请求失败但拿到了 429（HTTP 有响应）：渠道键恢复，候选键 m2 冷却
        assertEquals(
            CandidateCooldown.REASON_RATE_LIMITED,
            cooldown.recordFailure(probe, FailureKind.RATE_LIMITED)?.reason,
        )
        // 渠道键已恢复：m1 可用；候选键还在冷却：m2 不可用
        assertNotNull(cooldown.acquire("ch1", "m1"))
        assertNull(cooldown.acquire("ch1", "m2"))
    }

    @Test
    fun `ttft slow streak triggers candidate cooldown and fast token resets streak`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(ttftSlowThresholdMs = 500, ttftSlowStreak = 2), nowMs = { now })

        assertFalse(cooldown.recordFirstToken("ch1", "m1", firstTokenMs = 600))
        // 快 token 清零连慢计数
        assertFalse(cooldown.recordFirstToken("ch1", "m1", firstTokenMs = 100))
        assertFalse(cooldown.recordFirstToken("ch1", "m1", firstTokenMs = 600))
        assertTrue(cooldown.recordFirstToken("ch1", "m1", firstTokenMs = 700))

        assertNull(cooldown.acquire("ch1", "m1"))
        assertNotNull(cooldown.acquire("ch1", "m2"))
    }

    @Test
    fun `restore reactivates persisted cooldowns`() {
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { 5_000L })
        cooldown.restore(
            listOf(
                CooldownStateSnapshot("ch:ch1", consecutiveFailures = 1, cooldownUntil = 60_000L),
                CooldownStateSnapshot("ch:ch2", consecutiveFailures = 1, cooldownUntil = 1_000L),
            ),
        )
        assertNull(cooldown.acquire("ch1", "m1"))
        // 已过期的行进入探测就绪
        assertNotNull(cooldown.acquire("ch2", "m1"))
    }

    @Test
    fun `state changes are reported for write-through`() {
        val changes = mutableListOf<CooldownChange>()
        var now = 0L
        val cooldown = CandidateCooldown(
            policy = policy(),
            nowMs = { now },
            onChange = { changes.add(it) },
        )
        val permit = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)
        assertEquals(1, changes.size)
        val updated = changes.single() as CooldownChange.Updated
        assertEquals("ch:ch1", updated.stateKey)
        assertEquals(1_000L, updated.cooldownUntil)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "m1"))
        cooldown.recordSuccess(probe)
        val removed = changes.lastOrNull() as? CooldownChange.Removed
        assertEquals("ch:ch1", removed?.stateKey)
    }
}
