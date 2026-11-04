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
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertEquals(CandidateCooldown.REASON_TRANSIENT, cooldown.recordFailure(permit, FailureKind.TRANSPORT)?.reason)

        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        // 渠道键连坐：同渠道其他模型也被阻塞
        assertNull(cooldown.acquire("ch1", "k1", "m2"))
        // 渠道键连坐：同渠道其他密钥也被阻塞
        assertNull(cooldown.acquire("ch1", "k2", "m1"))
        // 其他渠道不受影响
        assertNotNull(cooldown.acquire("ch2", "k1", "m1"))
    }

    @Test
    fun `rate limit cools only the candidate key and honors Retry-After with cap`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertEquals(
            CandidateCooldown.REASON_RATE_LIMITED,
            cooldown.recordFailure(permit, FailureKind.RATE_LIMITED, retryAfterMs = 5_000)?.reason,
        )

        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        // 候选键不连坐同渠道其他模型
        assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
        // 候选键不连坐同渠道其他密钥
        assertNotNull(cooldown.acquire("ch1", "k2", "m1"))

        // Retry-After（5s）到期后可探测
        now = 5_000L
        val expired = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        // Retry-After 超过封顶时按封顶（60s）算
        val trigger = cooldown.recordFailure(expired, FailureKind.RATE_LIMITED, retryAfterMs = 120_000)
        assertEquals(CandidateCooldown.REASON_RATE_LIMITED, trigger?.reason)
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        now = 65_000L
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
    }

    @Test
    fun `auth failure takes long cooldown and model not found does not cool`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertEquals(CandidateCooldown.REASON_AUTH, cooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)?.reason)
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        // 密钥键不连坐同渠道其他密钥
        assertNotNull(cooldown.acquire("ch1", "k2", "m1"))

        now = 5_000L
        assertNull(cooldown.acquire("ch1", "k1", "m1"), "auth 冷却应远未到期")

        val notFound = assertNotNull(cooldown.acquire("ch2", "k1", "m1"))
        assertNull(cooldown.recordFailure(notFound, FailureKind.MODEL_NOT_FOUND))
        assertNotNull(cooldown.acquire("ch2", "k1", "m1"))
    }

    @Test
    fun `cooldown expiry releases a single probe and success fully recovers`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertTrue(probe.refs.any { it.probing })
        // 探测中：其余请求（含其他模型，渠道键在探测）被阻塞
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNull(cooldown.acquire("ch1", "k1", "m2"))
        cooldown.recordSuccess(probe)
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
    }

    @Test
    fun `failed probe doubles the cooldown`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(transientBaseMs = 1_000, transientMaxMs = 60_000), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertEquals(CandidateCooldown.REASON_TRANSIENT, cooldown.recordFailure(probe, FailureKind.TRANSPORT)?.reason)

        // 翻倍后冷却到 2s 起步：1s..2s 之间仍阻塞
        now = 2_500L
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        now = 3_000L
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
    }

    @Test
    fun `force acquire permits attempts regardless of cooldown`() {
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { 0L })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)
        assertNull(cooldown.acquire("ch1", "k1", "m1"))

        val forced = cooldown.forceAcquire("ch1", "k1", "m1")
        assertFalse(forced.refs.any { it.probing })
        // fail-open 成功后完全恢复
        cooldown.recordSuccess(forced)
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
    }

    @Test
    fun `release returns probe slot without reward or penalty`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.release(probe)
        // 归还后下一个请求可再次探测
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
    }

    @Test
    fun `late results from an older generation are ignored`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val stale = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        val fresh = assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
        cooldown.recordFailure(fresh, FailureKind.TRANSPORT)

        // 迟到的成功不能凭旧代际恢复渠道键
        cooldown.recordSuccess(stale)
        assertNull(cooldown.acquire("ch1", "k1", "m3"))

        // 迟到的失败也不能凭旧代际延长或二次触发
        assertNull(cooldown.recordFailure(stale, FailureKind.TRANSPORT))
    }

    @Test
    fun `http response failure resets other keys while cooling the target`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        // 先让渠道键进入冷却
        val warmup = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(warmup, FailureKind.TRANSPORT)

        // 冷却到期，m2 的请求拿到渠道键探测资格
        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
        // 这次请求失败但拿到了 429（HTTP 有响应）：渠道键恢复，候选键 m2 冷却
        assertEquals(
            CandidateCooldown.REASON_RATE_LIMITED,
            cooldown.recordFailure(probe, FailureKind.RATE_LIMITED)?.reason,
        )
        // 渠道键已恢复：m1 可用；候选键还在冷却：m2 不可用
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNull(cooldown.acquire("ch1", "k1", "m2"))
    }

    @Test
    fun `ttft slow streak triggers candidate cooldown and fast token resets streak`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(ttftSlowThresholdMs = 500, ttftSlowStreak = 2), nowMs = { now })

        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertFalse(cooldown.recordFirstToken(permit, firstTokenMs = 600))
        // 快 token 清零连慢计数
        assertFalse(cooldown.recordFirstToken(permit, firstTokenMs = 100))
        assertFalse(cooldown.recordFirstToken(permit, firstTokenMs = 600))
        assertTrue(cooldown.recordFirstToken(permit, firstTokenMs = 700))

        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
        assertNotNull(cooldown.acquire("ch1", "k2", "m1"))
    }

    @Test
    fun `healthy success does not invalidate concurrent failure recording`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val first = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        val second = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))

        // 健康态的成功不推进代际：并发的另一请求随后失败仍要记入冷却
        cooldown.recordSuccess(first)
        assertEquals(CandidateCooldown.REASON_TRANSIENT, cooldown.recordFailure(second, FailureKind.TRANSPORT)?.reason)
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
    }

    @Test
    fun `isBlocked detects candidate-key-only cooldown`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.RATE_LIMITED)

        // 渠道键无状态、仅候选键在冷却时，isBlocked 不能提前返回
        assertTrue(cooldown.isBlocked("ch1", "k1", "m1"))
        assertFalse(cooldown.isBlocked("ch1", "k1", "m2"))
    }

    @Test
    fun `isBlocked peeks cooldown without consuming probe slot`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        assertFalse(cooldown.isBlocked("ch1", "k1", "m1"))

        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)
        assertTrue(cooldown.isBlocked("ch1", "k1", "m1"))

        // 到期后探测就绪：peek 返回未阻塞，且不占用探测票
        now = 1_000L
        assertFalse(cooldown.isBlocked("ch1", "k1", "m1"))
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertTrue(probe.refs.any { it.probing })
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
        assertNull(cooldown.acquire("ch1", "k1", "m1"))
        // 已过期的行进入探测就绪
        assertNotNull(cooldown.acquire("ch2", "k1", "m1"))
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
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)
        assertEquals(1, changes.size)
        val updated = changes.single() as CooldownChange.Updated
        assertEquals("ch:ch1", updated.stateKey)
        assertEquals(1_000L, updated.cooldownUntil)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordSuccess(probe)
        val removed = changes.lastOrNull() as? CooldownChange.Removed
        assertEquals("ch:ch1", removed?.stateKey)
    }

    @Test
    fun `stale permit releases still-current probe slots`() {
        var now = 0L
        val cooldown = CandidateCooldown(
            policy = policy(ttftSlowThresholdMs = 100, ttftSlowStreak = 1),
            nowMs = { now },
        )
        val warmup = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(warmup, FailureKind.TRANSPORT)
        now = 10_000L

        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNull(cooldown.acquire("ch1", "k1", "m2"))

        assertTrue(cooldown.recordFirstToken(permit, 200))
        cooldown.recordSuccess(permit)

        assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
    }

    @Test
    fun `key reset clears all key model cooldowns and ignores late permits`() {
        val changes = mutableListOf<CooldownChange>()
        val cooldown = CandidateCooldown(policy(), nowMs = { 0L }, onChange = changes::add)
        val lateAuth = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(lateAuth, FailureKind.AUTH_REJECTED)
        val lateRateLimit = cooldown.forceAcquire("ch1", "k1", "m2")
        cooldown.recordFailure(lateRateLimit, FailureKind.RATE_LIMITED)
        val lateTransport = assertNotNull(cooldown.forceAcquire("ch1", "k1", "m1"))
        val other = assertNotNull(cooldown.acquire("ch1", "k2", "m1"))
        cooldown.recordFailure(other, FailureKind.RATE_LIMITED)
        val ttftPermit = assertNotNull(cooldown.acquire("ch1", "k1", "m3"))
        cooldown.recordFirstToken(ttftPermit, 1000)

        cooldown.clearKey("ch1", "k1")
        assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
        assertNull(cooldown.acquire("ch1", "k2", "m1"))
        assertNull(cooldown.recordFailure(lateAuth, FailureKind.AUTH_REJECTED))
        assertNull(cooldown.recordFailure(lateRateLimit, FailureKind.RATE_LIMITED))
        assertNull(cooldown.recordFailure(lateTransport, FailureKind.TRANSPORT))
        assertFalse(cooldown.recordFirstToken(ttftPermit, 1000))
        assertTrue(changes.any { it == CooldownChange.Removed("key:ch1:k1") })
        assertTrue(changes.any { it == CooldownChange.Removed("kc:ch1:k1:m2") })
        assertNull(cooldown.cooldownState("ch1", "k1", "m1"))
    }

    @Test
    fun `cooldown state exposes the trigger reason`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })

        val transient = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(transient, FailureKind.TRANSPORT)
        assertEquals(CandidateCooldown.REASON_TRANSIENT, cooldown.cooldownState("ch1", "k1", "m1")?.reason)
        assertEquals("channel", cooldown.cooldownState("ch1", "k1", "m1")?.scope)
        assertEquals(1_000L, cooldown.cooldownState("ch1", "k1", "m1")?.until)

        now = 1_000L
        val rateLimited = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(rateLimited, FailureKind.RATE_LIMITED, retryAfterMs = 5_000)
        assertEquals(CandidateCooldown.REASON_RATE_LIMITED, cooldown.cooldownState("ch1", "k1", "m1")?.reason)
        assertEquals("candidate", cooldown.cooldownState("ch1", "k1", "m1")?.scope)
        assertEquals(6_000L, cooldown.cooldownState("ch1", "k1", "m1")?.until)

        val auth = assertNotNull(cooldown.acquire("ch2", "k1", "m1"))
        cooldown.recordFailure(auth, FailureKind.AUTH_REJECTED)
        assertEquals(CandidateCooldown.REASON_AUTH, cooldown.cooldownState("ch2", "k1", "m1")?.reason)
        assertEquals("key", cooldown.cooldownState("ch2", "k1", "m1")?.scope)

        val ttftCooldown = CandidateCooldown(
            policy = policy(ttftSlowThresholdMs = 100, ttftSlowStreak = 1),
            nowMs = { now },
        )
        val ttft = assertNotNull(ttftCooldown.acquire("ch3", "k1", "m1"))
        assertTrue(ttftCooldown.recordFirstToken(ttft, firstTokenMs = 200))
        assertEquals(CandidateCooldown.REASON_TTFT_SLOW, ttftCooldown.cooldownState("ch3", "k1", "m1")?.reason)
        assertEquals("candidate", ttftCooldown.cooldownState("ch3", "k1", "m1")?.scope)
    }

    @Test
    fun `successful probe clears cooldown reason`() {
        var now = 0L
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { now })
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.TRANSPORT)

        now = 1_000L
        val probe = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordSuccess(probe)
        assertNull(cooldown.cooldownState("ch1", "k1", "m1"))
    }

    @Test
    fun `restore preserves cooldown reason`() {
        val cooldown = CandidateCooldown(policy = policy(), nowMs = { 0L })
        cooldown.restore(listOf(CooldownStateSnapshot("ch:ch1", 1, 60_000L, "server_error")))

        assertEquals("server_error", cooldown.cooldownState("ch1", "k1", "m1")?.reason)
    }

    @Test
    fun `clear candidate removes only the candidate cooldown`() {
        val changes = mutableListOf<CooldownChange>()
        val cooldown = CandidateCooldown(policy(), nowMs = { 0L }, onChange = changes::add)
        val first = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(first, FailureKind.RATE_LIMITED)
        val second = assertNotNull(cooldown.acquire("ch1", "k1", "m2"))
        cooldown.recordFailure(second, FailureKind.RATE_LIMITED)

        cooldown.clearCandidate("ch1", "k1", "m1")

        assertFalse(cooldown.isBlocked("ch1", "k1", "m1"))
        assertTrue(cooldown.isBlocked("ch1", "k1", "m2"))
        assertTrue(changes.any { it == CooldownChange.Removed("kc:ch1:k1:m1") })
        assertFalse(changes.any { it == CooldownChange.Removed("kc:ch1:k1:m2") })
    }

    @Test
    fun `clear channel removes every cooldown under that channel`() {
        val changes = mutableListOf<CooldownChange>()
        val cooldown = CandidateCooldown(policy(), nowMs = { 0L }, onChange = changes::add)

        val auth = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(auth, FailureKind.AUTH_REJECTED)
        val rate = assertNotNull(cooldown.acquire("ch1", "k2", "m1"))
        cooldown.recordFailure(rate, FailureKind.RATE_LIMITED)
        val transport = assertNotNull(cooldown.acquire("ch1", "k3", "m1"))
        cooldown.recordFailure(transport, FailureKind.TRANSPORT)
        val other = assertNotNull(cooldown.acquire("ch2", "k1", "m1"))
        cooldown.recordFailure(other, FailureKind.RATE_LIMITED)

        cooldown.clearChannel("ch1")

        assertFalse(cooldown.isBlocked("ch1", "k1", "m1"))
        assertFalse(cooldown.isBlocked("ch1", "k2", "m1"))
        assertFalse(cooldown.isBlocked("ch1", "k3", "m1"))
        assertTrue(cooldown.isBlocked("ch2", "k1", "m1"))
        assertTrue(changes.any { it == CooldownChange.Removed("ch:ch1") })
        assertTrue(changes.any { it == CooldownChange.Removed("key:ch1:k1") })
        assertTrue(changes.any { it == CooldownChange.Removed("kc:ch1:k2:m1") })
    }

    @Test
    fun `clear methods only emit removed events and return keys for non-healthy states`() {
        val changes = mutableListOf<CooldownChange>()
        val cooldown = CandidateCooldown(policy(), nowMs = { 0L }, onChange = changes::add)

        // 仅 acquire 建立健康状态（consecutiveFailures=0, cooldownUntil=0）
        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        assertTrue(changes.isEmpty())

        // 清除健康候选，不应提交 Removed，也不返回该状态键
        val clearedCandidate = cooldown.clearCandidate("ch1", "k1", "m1")
        assertTrue(clearedCandidate.isEmpty())
        assertTrue(changes.isEmpty())
        // 但代际已递增，旧许可应当作废
        assertNull(cooldown.recordFailure(permit, FailureKind.RATE_LIMITED))

        // 清除健康密钥
        val permit2 = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        val clearedKey = cooldown.clearKey("ch1", "k1")
        assertTrue(clearedKey.isEmpty())
        assertTrue(changes.isEmpty())
        assertNull(cooldown.recordFailure(permit2, FailureKind.RATE_LIMITED))

        // 清除健康渠道
        val permit3 = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        val clearedChannel = cooldown.clearChannel("ch1")
        assertTrue(clearedChannel.isEmpty())
        assertTrue(changes.isEmpty())
        assertNull(cooldown.recordFailure(permit3, FailureKind.RATE_LIMITED))

        // 若确实处于失败或冷却，则应返回并提交 Removed
        val permit4 = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit4, FailureKind.RATE_LIMITED)
        val clearedAfterFailure = cooldown.clearCandidate("ch1", "k1", "m1")
        assertEquals(listOf("kc:ch1:k1:m1"), clearedAfterFailure)
        assertTrue(changes.any { it == CooldownChange.Removed("kc:ch1:k1:m1") })
    }

    @Test
    fun `unconfirmed deletions are returned on retry until confirmDeletions is called`() {
        val changes = mutableListOf<CooldownChange>()
        val cooldown = CandidateCooldown(policy(), nowMs = { 0L }, onChange = changes::add)

        val permit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(permit, FailureKind.RATE_LIMITED)
        changes.clear()

        // 第一次清除：返回候选键，但模拟持久化失败未调用 confirmDeletions
        val firstClear = cooldown.clearCandidate("ch1", "k1", "m1")
        assertEquals(listOf("kc:ch1:k1:m1"), firstClear)
        assertEquals(1, changes.size)
        changes.clear()

        // 内存中虽已清零，但重试清除候选仍能返回该键以供持久化重试
        val retryCandidate = cooldown.clearCandidate("ch1", "k1", "m1")
        assertEquals(listOf("kc:ch1:k1:m1"), retryCandidate)
        assertEquals(1, changes.size)
        changes.clear()

        // 重试清除同渠道也能并入该未确认键
        val retryChannel = cooldown.clearChannel("ch1")
        assertEquals(listOf("kc:ch1:k1:m1"), retryChannel)
        assertEquals(1, changes.size)
        changes.clear()

        // 持久化成功并确认后，再次清除则因状态已健康且已确认，返回空
        cooldown.confirmDeletions(retryChannel)
        val clearedAfterConfirm = cooldown.clearChannel("ch1")
        assertTrue(clearedAfterConfirm.isEmpty())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `successful request does not clear deleteUnconfirmed flag before confirmDeletions`() {
        val changes = mutableListOf<CooldownChange>()
        val cooldown = CandidateCooldown(policy(), nowMs = { 0L }, onChange = changes::add)

        val failPermit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordFailure(failPermit, FailureKind.RATE_LIMITED)
        changes.clear()

        // 第一次清除：标记 deleteUnconfirmed，未确认
        val cleared = cooldown.clearCandidate("ch1", "k1", "m1")
        assertEquals(listOf("kc:ch1:k1:m1"), cleared)
        changes.clear()

        // 此时新请求进入并在新代际下成功，触发 recordSuccess -> reset
        val successPermit = assertNotNull(cooldown.acquire("ch1", "k1", "m1"))
        cooldown.recordSuccess(successPermit)

        // 常规 reset 不应清除 deleteUnconfirmed，重试清除依然能够捕获该键
        val retry = cooldown.clearCandidate("ch1", "k1", "m1")
        assertEquals(listOf("kc:ch1:k1:m1"), retry)
        assertEquals(1, changes.size)
        changes.clear()

        // 持久化确认后才清除标记
        cooldown.confirmDeletions(retry)
        assertTrue(cooldown.clearCandidate("ch1", "k1", "m1").isEmpty())
    }
}
