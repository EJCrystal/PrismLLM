package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.OAuthAccountPolicy
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
