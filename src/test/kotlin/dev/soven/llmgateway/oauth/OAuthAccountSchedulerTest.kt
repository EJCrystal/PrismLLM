package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthQuotaWindow
import dev.soven.llmgateway.domain.OAuthStoredWindowState
import dev.soven.llmgateway.domain.OAuthWindowSnapshot
import dev.soven.llmgateway.resilience.ChannelKeyState
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthAccountSchedulerTest {
    private var clock = 1_762_400_000_000L
    private val snapshots = mutableListOf<OAuthWindowSnapshot>()
    private val scheduler = OAuthAccountScheduler(
        defaultCooldownMs = 60_000,
        maxCooldownMs = 3_600_000,
        now = { clock },
        onSnapshot = { snapshots += it },
    )

    @Test
    fun `window admits the configured number of requests and reports the next free slot`() = runTest {
        val key = key(policy = OAuthAccountPolicy(windowLimit = 2, windowSeconds = 3_600, maxConcurrentRequests = 5))
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.WINDOW, denied.reason)
        assertEquals(3_600_000, denied.retryAfterMs)

        clock += 1_000
        val later = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(later)
        assertEquals(3_599_000, later.retryAfterMs)

        // 最早的一次命中滑出窗口后重新放行。
        clock += 3_599_000
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `unlimited window does not accumulate hits`() = runTest {
        val policy = OAuthAccountPolicy(windowLimit = null, windowSeconds = 3_600, maxConcurrentRequests = 100)
        val key = key(policy = policy)
        repeat(50) { assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key)) }

        // 不限制窗口时命中不参与准入判断，也不应占用内存。
        val state = assertNotNull(scheduler.runtimeState(key.id, policy))
        assertEquals(0, state.windowUsed)
        assertEquals(50, state.activeRequests)
        assertEquals(0, snapshots.last().hits.size)
    }

    @Test
    fun `switching to an unlimited window drops stale hits`() = runTest {
        val limited = OAuthAccountPolicy(windowLimit = 5, windowSeconds = 3_600, maxConcurrentRequests = 5)
        val key = key(policy = limited)
        repeat(3) { assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key)) }
        assertEquals(3, assertNotNull(scheduler.runtimeState(key.id, limited)).windowUsed)

        val unlimited = limited.copy(windowLimit = null)
        assertEquals(0, assertNotNull(scheduler.runtimeState(key.id, unlimited)).windowUsed)
    }

    @Test
    fun `cooldown deadlines saturate near the clock limit`() = runTest {
        clock = Long.MAX_VALUE - 10
        val key = key()
        scheduler.recordRateLimited(key, 60_000)
        assertEquals(Long.MAX_VALUE, snapshots.last().cooldownUntil)

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.COOLDOWN, denied.reason)
        assertEquals(10, denied.retryAfterMs)
    }

    @Test
    fun `window deadlines saturate near the clock limit`() = runTest {
        clock = Long.MAX_VALUE - 10
        val key = key(
            policy = OAuthAccountPolicy(windowLimit = 1, windowSeconds = 3_600, maxConcurrentRequests = 5),
        )
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.WINDOW, denied.reason)
        assertEquals(10, denied.retryAfterMs)
    }

    @Test
    fun `concurrency cap blocks a second in-flight request until release`() = runTest {
        val key = key(policy = OAuthAccountPolicy(windowLimit = 10, windowSeconds = 3_600, maxConcurrentRequests = 1))
        val first = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Allowed>(first)

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.CONCURRENCY, denied.reason)
        assertEquals(1_000, denied.retryAfterMs)

        scheduler.release((first as OAuthAccountDecision.Allowed).lease)
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `429 honors the upstream reset hint`() = runTest {
        val key = key()
        scheduler.recordRateLimited(key, 120_000)
        assertEquals(clock + 120_000, snapshots.last().cooldownUntil)

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.COOLDOWN, denied.reason)
        assertEquals(120_000, denied.retryAfterMs)

        clock += 120_000
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `repeated 429 without a hint backs off exponentially`() = runTest {
        val key = key()
        scheduler.recordRateLimited(key, null)
        assertEquals(clock + 60_000, snapshots.last().cooldownUntil)

        clock += 60_000
        scheduler.recordRateLimited(key, null)
        assertEquals(clock + 120_000, snapshots.last().cooldownUntil)

        clock += 120_000
        scheduler.recordRateLimited(key, null)
        assertEquals(clock + 240_000, snapshots.last().cooldownUntil)
    }

    @Test
    fun `success resets the consecutive 429 backoff`() = runTest {
        val key = key()
        scheduler.recordRateLimited(key, null)
        clock += 60_000
        scheduler.recordRateLimited(key, null)
        scheduler.recordSuccess(key.id)

        clock += 120_000
        scheduler.recordRateLimited(key, null)
        assertEquals(clock + 60_000, snapshots.last().cooldownUntil)
    }

    @Test
    fun `static keys are always allowed and never publish state`() = runTest {
        val key = key(policy = null)
        repeat(5) {
            val decision = scheduler.acquire(key)
            assertIs<OAuthAccountDecision.Allowed>(decision)
            assertNull((decision as OAuthAccountDecision.Allowed).lease)
        }
        assertNull(scheduler.check(key))
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `check reports a denial without consuming a window slot`() = runTest {
        val key = key(policy = OAuthAccountPolicy(windowLimit = 1, windowSeconds = 3_600, maxConcurrentRequests = 5))
        assertNull(scheduler.check(key))

        val allowed = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Allowed>(allowed)
        val denial = scheduler.check(key)
        assertNotNull(denial)
        assertEquals(OAuthAccountDenyReason.WINDOW, denial.reason)

        scheduler.release((allowed as OAuthAccountDecision.Allowed).lease)
        assertEquals(OAuthAccountDenyReason.WINDOW, scheduler.check(key)!!.reason)
    }

    @Test
    fun `restore prunes expired hits and keeps a live cooldown`() = runTest {
        val policy = OAuthAccountPolicy(windowLimit = 2, windowSeconds = 3_600, maxConcurrentRequests = 5)
        val key = key(policy = policy)
        scheduler.restore(
            listOf(
                OAuthStoredWindowState(
                    snapshot = OAuthWindowSnapshot(
                        keyId = key.id,
                        hits = listOf(clock - 7_200_000, clock - 1_800_000),
                        cooldownUntil = clock + 30_000,
                        consecutiveRateLimits = 3,
                        updatedAt = clock,
                    ),
                    policy = policy,
                ),
            ),
        )

        val cooling = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(cooling)
        assertEquals(OAuthAccountDenyReason.COOLDOWN, cooling.reason)
        assertEquals(30_000, cooling.retryAfterMs)

        clock += 30_000
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
        val windowDenied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(windowDenied)
        assertEquals(OAuthAccountDenyReason.WINDOW, windowDenied.reason)
    }

    @Test
    fun `runtime state reports window usage concurrency and cooldown`() = runTest {
        val policy = OAuthAccountPolicy(windowLimit = 5, windowSeconds = 3_600, maxConcurrentRequests = 2)
        val key = key(policy = policy)
        assertNull(scheduler.runtimeState(key.id, policy))

        val first = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Allowed>(first)
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
        scheduler.recordRateLimited(key, 90_000)
        scheduler.release(first.lease)

        val state = assertNotNull(scheduler.runtimeState(key.id, policy))
        assertEquals(2, state.windowUsed)
        assertEquals(1, state.activeRequests)
        assertEquals(clock + 90_000, state.cooldownUntil)
        assertEquals(1, state.consecutiveRateLimits)
    }

    @Test
    fun `clear cooldown releases the account and republishes a snapshot`() = runTest {
        val policy = OAuthAccountPolicy()
        val key = key(policy = policy)
        scheduler.recordRateLimited(key, null)
        scheduler.recordRateLimited(key, null)
        assertIs<OAuthAccountDecision.Denied>(scheduler.acquire(key))

        assertTrue(scheduler.clearCooldown(key.id))
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))

        val state = assertNotNull(scheduler.runtimeState(key.id, policy))
        assertNull(state.cooldownUntil)
        assertEquals(0, state.consecutiveRateLimits)
        assertNull(snapshots.last().cooldownUntil)
    }

    @Test
    fun `clear cooldown on an unused account is a no-op`() = runTest {
        assertFalse(scheduler.clearCooldown("missing"))
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `exhausted upstream quota blocks admission until the window resets`() = runTest {
        val key = key()
        scheduler.noteQuota(
            key.id,
            OAuthQuotaSnapshot(
                primary = OAuthQuotaWindow(usedPercent = 100.0, windowSeconds = 18_000, resetsAt = clock + 600_000),
                secondary = OAuthQuotaWindow(usedPercent = 42.5, windowSeconds = 604_800, resetsAt = clock + 86_400_000),
                checkedAt = clock,
            ),
        )

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.QUOTA, denied.reason)
        assertEquals(600_000, denied.retryAfterMs)

        // 用尽窗口已过重置时间后放行，即使读数本身还没过期。
        clock += 600_000
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `quota snapshot with credits or headroom does not block`() = runTest {
        val key = key(policy = OAuthAccountPolicy(windowLimit = null, maxConcurrentRequests = 5))
        scheduler.noteQuota(
            key.id,
            OAuthQuotaSnapshot(
                primary = OAuthQuotaWindow(usedPercent = 100.0, resetsAt = clock + 600_000),
                creditsBalance = 25.0,
                checkedAt = clock,
            ),
        )
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))

        scheduler.noteQuota(
            key.id,
            OAuthQuotaSnapshot(
                primary = OAuthQuotaWindow(usedPercent = 99.9, resetsAt = clock + 600_000),
                checkedAt = clock,
            ),
        )
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `exhausted quota without a reset time blocks only while the reading is fresh`() = runTest {
        val key = key()
        scheduler.noteQuota(key.id, OAuthQuotaSnapshot(primary = OAuthQuotaWindow(usedPercent = 100.0), checkedAt = clock))

        val denied = scheduler.acquire(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.QUOTA, denied.reason)
        assertEquals(900_000, denied.retryAfterMs)

        clock += 900_001
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `stale quota readings are displayed but never block`() = runTest {
        val key = key()
        scheduler.noteQuota(
            key.id,
            OAuthQuotaSnapshot(
                primary = OAuthQuotaWindow(usedPercent = 100.0, resetsAt = clock + 3_600_000),
                checkedAt = clock - 900_001,
            ),
        )
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
    }

    @Test
    fun `reply headers update the quota reading before the next sweep`() = runTest {
        val key = key(policy = OAuthAccountPolicy(maxConcurrentRequests = 5))
        scheduler.noteQuota(
            key.id,
            OAuthQuotaSnapshot(
                primary = OAuthQuotaWindow(usedPercent = 60.0, windowSeconds = 18_000, resetsAt = clock + 1_800_000),
                creditsBalance = 0.0,
                resetCredits = 1,
                checkedAt = clock,
            ),
        )
        scheduler.noteQuotaHeaders(
            key,
            OAuthQuotaHeaderReading(
                primary = OAuthQuotaWindow(usedPercent = 100.0, windowSeconds = 18_000, resetsAt = clock + 1_800_000),
            ),
            clock,
        )

        val denied = scheduler.check(key)
        assertIs<OAuthAccountDecision.Denied>(denied)
        assertEquals(OAuthAccountDenyReason.QUOTA, denied.reason)

        // 轮询才有的 credits 与重置次数仍在，窗口回落后立即恢复准入。
        assertEquals(0.0, scheduler.routingHints(listOf(key))[key.id]?.quota?.creditsBalance)
        scheduler.noteQuotaHeaders(
            key,
            OAuthQuotaHeaderReading(
                primary = OAuthQuotaWindow(usedPercent = 30.0, windowSeconds = 18_000),
            ),
            clock,
        )
        assertNull(scheduler.check(key))
    }

    @Test
    fun `routing hints expose window usage and fresh quota without consuming slots`() = runTest {
        val key = key(policy = OAuthAccountPolicy(windowLimit = 10, maxConcurrentRequests = 5))
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
        assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(key))
        scheduler.noteQuota(
            key.id,
            OAuthQuotaSnapshot(
                primary = OAuthQuotaWindow(usedPercent = 20.0, windowSeconds = 18_000, resetsAt = clock + 3_600_000),
                checkedAt = clock,
            ),
        )

        val hint = scheduler.routingHints(listOf(key)).getValue(key.id)
        assertEquals(2, hint.windowUsed)
        assertEquals(20.0, hint.quota?.primary?.usedPercent)

        // 过期读数只展示，不参与排序。
        val stale = key(policy = OAuthAccountPolicy()).copy(id = "key-2")
        scheduler.noteQuota(
            stale.id,
            OAuthQuotaSnapshot(primary = OAuthQuotaWindow(usedPercent = 90.0), checkedAt = clock - 900_001),
        )
        assertNotNull(scheduler.routingHints(listOf(stale))[stale.id])
        assertNull(scheduler.routingHints(listOf(stale))[stale.id]?.quota)

        // 从未使用过的账号不创建状态。
        assertTrue(scheduler.routingHints(listOf(key(policy = OAuthAccountPolicy()).copy(id = "key-3"))).isEmpty())
    }

    private fun key(policy: OAuthAccountPolicy? = OAuthAccountPolicy()): ChannelKeyRecord = ChannelKeyRecord(
        id = "key-1",
        channelId = "channel-1",
        name = "account",
        credentialCiphertext = "cipher",
        credentialNonce = "nonce",
        credentialKeyVersion = 1,
        state = ChannelKeyState.ACTIVE,
        priority = 0,
        createdAt = 0,
        updatedAt = 0,
        oauthPolicy = policy,
    )
}

class OAuthRateLimitHintsTest {
    private val now = 1_762_400_000_000L

    @Test
    fun `reads resets_in_seconds from the top level and nested error`() {
        assertEquals(30_000, parseRateLimitResetMs("""{"resets_in_seconds":30}""", now))
        assertEquals(45_000, parseRateLimitResetMs("""{"error":{"resets_in_seconds":45}}""", now))
        assertEquals(15_000, parseRateLimitResetMs("""{"error":{"retry_after":15}}""", now))
        assertEquals(2_500, parseRateLimitResetMs("""{"retry_after_ms":2500}""", now))
    }

    @Test
    fun `reads epoch seconds millis and ISO reset times`() {
        val epochSeconds = now / 1_000 + 60
        assertEquals(60_000, parseRateLimitResetMs("""{"resets_at":$epochSeconds}""", now))
        assertEquals(60_000, parseRateLimitResetMs("""{"resets_at":${now + 60_000}}""", now))
        assertEquals(
            60_000,
            parseRateLimitResetMs("""{"resets_at":"${Instant.ofEpochMilli(now + 60_000)}"}""", now),
        )
    }

    @Test
    fun `reads google rpc retry delay`() {
        val body =
            """{"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"45s"}]}}"""
        assertEquals(45_000, parseRateLimitResetMs(body, now))
    }

    @Test
    fun `ignores malformed or missing hints`() {
        assertNull(parseRateLimitResetMs(null, now))
        assertNull(parseRateLimitResetMs("not json", now))
        assertNull(parseRateLimitResetMs("""{"error":{"message":"slow down"}}""", now))
        assertNull(parseRateLimitResetMs("""{"resets_in_seconds":0}""", now))
        assertNull(parseRateLimitResetMs("""{"resets_at":${now - 1_000}}""", now))
    }

    @Test
    fun `ignores hints that would overflow or exceed the cap`() {
        assertNull(parseRateLimitResetMs("""{"resets_in_seconds":9223372036854775807}""", now))
        assertNull(parseRateLimitResetMs("""{"retry_after":9223372036854775807}""", now))
        assertNull(parseRateLimitResetMs("""{"retry_after_ms":9223372036854775807}""", now))
        assertNull(parseRateLimitResetMs("""{"resets_at":9223372036854775807}""", now))
        assertNull(parseRateLimitResetMs("""{"resets_at":-9223372036854775808}""", now))
        assertNull(parseRateLimitResetMs("""{"resets_in_seconds":604801}""", now))
        assertNull(
            parseRateLimitResetMs(
                """{"error":{"details":[{"retryDelay":"999999999999999999999h"}]}}""",
                now,
            ),
        )
        assertEquals(604_800_000, parseRateLimitResetMs("""{"resets_in_seconds":604800}""", now))
    }
}
