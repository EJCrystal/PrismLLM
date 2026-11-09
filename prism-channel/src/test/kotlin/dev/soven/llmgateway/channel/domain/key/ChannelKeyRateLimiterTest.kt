package dev.soven.llmgateway.channel.domain.key

import dev.soven.llmgateway.channel.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelKeyRateLimiterTest {
    @Test
    fun `unconfigured and disabled policies do not limit requests`() = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val unlimited = key("unlimited")
        val disabled = key(
            "disabled",
            ChannelKeyRateLimitPolicy(enabled = false, requestsPerMinute = 1, maxConcurrentRequests = 1),
        )

        repeat(5) {
            val decision = limiter.acquire(unlimited)
            assertIs<ChannelKeyRateLimitDecision.Allowed>(decision)
            assertNull(decision.lease)
        }
        repeat(5) {
            val decision = limiter.acquire(disabled)
            assertIs<ChannelKeyRateLimitDecision.Allowed>(decision)
            assertNull(decision.lease)
        }
    }

    @Test
    fun `RPM budget is shared across all models on one channel key`(): Unit = runBlocking {
        var now = 0L
        val limiter = ChannelKeyRateLimiter(nowNanos = { now })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 2)
        val firstModel = key("shared", policy, models = listOf("m1"))
        val secondModel = key("shared", policy, models = listOf("m2"))

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(firstModel))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(secondModel))
        val denied = assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(firstModel))
        assertEquals(ChannelKeyRateLimitReason.REQUESTS_PER_MINUTE, denied.reason)
        assertTrue(denied.retryAfterMs > 0)

        now = 30_000_000_000L
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(secondModel))
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(firstModel))
    }

    @Test
    fun `RPM counters are independent by channel and key id`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1)
        val first = key("key-a", policy, channelId = "channel-a")
        val sameKeyOtherChannel = key("key-a", policy, channelId = "channel-b")
        val otherKeySameChannel = key("key-b", policy, channelId = "channel-a")

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(first))
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(first))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(sameKeyOtherChannel))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(otherKeySameChannel))
    }

    @Test
    fun `state keys cannot collide when ids contain delimiters`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1)
        val first = key("c", policy, channelId = "a:b")
        val second = key("b:c", policy, channelId = "a")

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(first))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(second))
    }

    @Test
    fun `concurrency lease blocks then releases capacity`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val limited = key(
            "concurrent",
            ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
        )

        val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited)).lease
        assertTrue(lease?.holdsConcurrency == true)
        val denied = assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))
        assertEquals(ChannelKeyRateLimitReason.CONCURRENCY, denied.reason)

        limiter.release(lease)
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited))
    }

    @Test
    fun `release frees concurrency after coroutine cancellation`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val limited = key(
            "cancelled",
            ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
        )
        val started = CompletableDeferred<Unit>()
        val job = launch {
            val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited)).lease
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                limiter.release(lease)
            }
        }

        started.await()
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))
        job.cancelAndJoin()
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited))
    }

    @Test
    fun `policy update resets RPM while preserving active concurrency`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val original = key(
            "updated",
            ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1, maxConcurrentRequests = 2),
        )
        val originalLease = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original)).lease
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(original))

        val updatedPolicy = ChannelKeyRateLimitPolicy(
            enabled = true,
            requestsPerMinute = 2,
            maxConcurrentRequests = 1,
        )
        limiter.updatePolicy(original.channelId, original.id, updatedPolicy)

        val updated = original.copy(rateLimit = updatedPolicy)
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(updated))
        limiter.release(originalLease)
        val updatedLease = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(updated)).lease
        limiter.release(updatedLease)
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(updated))
    }

    @Test
    fun `stale request snapshots cannot overwrite the authoritative policy`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val original = key(
            "stale-policy",
            ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1),
        )
        val updatedPolicy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 2)

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original))
        limiter.updatePolicy(original.channelId, original.id, updatedPolicy)

        // 在途请求仍携带旧快照，但已应用的新规则不能被它回滚。
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original.copy(rateLimit = updatedPolicy)))
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(original))
    }

    @Test
    fun `stale request snapshot cannot re-enable a disabled policy`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val original = key(
            "disabled-stale-policy",
            ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1),
        )

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original))
        limiter.updatePolicy(original.channelId, original.id, original.rateLimit.copy(enabled = false))

        // 旧请求仍携带启用状态的快照，不能覆盖管理端刚保存的禁用策略。
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(original))
    }

    @Test
    fun `reapplying the same policy does not refill RPM tokens`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1)
        val limited = key("idempotent-update", policy)

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited))
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))

        limiter.updatePolicy(limited.channelId, limited.id, policy)
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))
    }

    @Test
    fun `disabling and re-enabling a policy preserves an in-flight concurrency lease`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1)
        val limited = key("disable-with-active-lease", policy)
        val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited)).lease

        limiter.updatePolicy(limited.channelId, limited.id, policy.copy(enabled = false))
        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited))

        limiter.updatePolicy(limited.channelId, limited.id, policy)
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))

        limiter.release(lease)
        val reacquired = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited)).lease
        limiter.release(reacquired)
    }

    @Test
    fun `re-enabling an RPM policy starts with a fresh token bucket`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1)
        val limited = key("rpm-disable-reenable", policy)

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited))
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))
        limiter.updatePolicy(limited.channelId, limited.id, policy.copy(enabled = false))
        limiter.updatePolicy(limited.channelId, limited.id, policy)

        assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited))
    }

    @Test
    fun `clear key preserves active concurrency leases until they are released`(): Unit = runBlocking {
        val limiter = ChannelKeyRateLimiter(nowNanos = { 0L })
        val policy = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1)
        val limited = key("deleted-with-active-lease", policy)
        val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited)).lease

        limiter.clearKey(limited.channelId, limited.id)
        limiter.updatePolicy(limited.channelId, limited.id, policy)
        assertIs<ChannelKeyRateLimitDecision.Denied>(limiter.acquire(limited))

        limiter.release(lease)
        val reacquired = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(limited)).lease
        limiter.release(reacquired)
    }
}

private fun key(
    id: String,
    rateLimit: ChannelKeyRateLimitPolicy = ChannelKeyRateLimitPolicy(),
    channelId: String = "channel-a",
    models: List<String> = listOf("m1"),
) = ChannelKeyRecord(
    id = id,
    channelId = channelId,
    name = id,
    credentialCiphertext = "ciphertext",
    credentialNonce = "nonce",
    credentialKeyVersion = 1,
    state = ChannelKeyState.ACTIVE,
    priority = 1,
    models = models,
    rateLimit = rateLimit,
    createdAt = 0,
    updatedAt = 0,
)
