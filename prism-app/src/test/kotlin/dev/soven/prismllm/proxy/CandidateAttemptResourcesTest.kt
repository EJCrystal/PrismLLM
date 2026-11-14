package dev.soven.prismllm.proxy

import dev.soven.prismllm.channel.domain.ChannelKeyRateLimitPolicy
import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitDecision
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitLease
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimiter
import dev.soven.prismllm.oauth.domain.OAuthAccountPolicy
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.infra.OAuthAccountDecision
import dev.soven.prismllm.oauth.infra.OAuthAccountDenyReason
import dev.soven.prismllm.oauth.infra.OAuthAccountLease
import dev.soven.prismllm.oauth.infra.OAuthAccountScheduler
import dev.soven.prismllm.resilience.CandidateCooldown
import dev.soven.prismllm.resilience.CooldownPolicy
import dev.soven.prismllm.resilience.FailureKind
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CandidateAttemptResourcesTest {
    @Test
    fun `cancel during channel lease delivery registers then releases without entering attempt`() = runTest {
        var clock = 1L
        val cooldown = CandidateCooldown(CooldownPolicy(transientBaseMs = 1), nowMs = { clock })
        val seed = assertNotNull(cooldown.acquire("channel", "key", "model"))
        cooldown.recordFailure(seed, FailureKind.TRANSPORT)
        clock = 2
        val limiter = ChannelKeyRateLimiter()
        val key = channelKey()
        val acquired = CompletableDeferred<Unit>()
        val deliver = CompletableDeferred<Unit>()
        var enteredAttempt = false
        val job = launch {
            CandidateAttemptResources(
                releaseCooldown = cooldown::release,
                releaseRateLimit = limiter::release,
                releaseOAuth = {},
                releaseProbe = {},
                onReleaseFailure = { _, cause -> throw AssertionError("unexpected release failure", cause) },
            ).use { resources ->
                assertNotNull(resources.acquireCooldown { cooldown.acquire("channel", "key", "model") })
                resources.acquireRateLimit {
                    limiter.acquire(key).also {
                        acquired.complete(Unit)
                        deliver.await()
                    }
                }
                enteredAttempt = true
            }
        }
        acquired.await()
        job.cancel()
        deliver.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(false, enteredAttempt)
        val next = assertIs<ChannelKeyRateLimitDecision.Allowed>(limiter.acquire(key))
        limiter.release(next.lease)
        cooldown.release(assertNotNull(cooldown.acquire("channel", "key", "model")))
    }

    @Test
    fun `cancel during oauth lease delivery keeps window hit but returns concurrency`() = runTest {
        val scheduler = OAuthAccountScheduler()
        val policy = OAuthAccountPolicy(windowLimit = 2, maxConcurrentRequests = 1)
        val account = oauthAccount(policy)
        val acquired = CompletableDeferred<Unit>()
        val deliver = CompletableDeferred<Unit>()
        var enteredAttempt = false
        var cooldownReleases = 0
        val cooldown = CandidateCooldown(CooldownPolicy())
        val job = launch {
            CandidateAttemptResources(
                releaseCooldown = { cooldown.release(it); cooldownReleases += 1 },
                releaseRateLimit = {},
                releaseOAuth = scheduler::release,
                releaseProbe = {},
                onReleaseFailure = { _, cause -> throw AssertionError("unexpected release failure", cause) },
            ).use { resources ->
                resources.acquireCooldown { cooldown.acquire("channel", "key", "model") }
                resources.acquireOAuth {
                    scheduler.acquire(account).also {
                        acquired.complete(Unit)
                        deliver.await()
                    }
                }
                enteredAttempt = true
            }
        }
        acquired.await()
        job.cancel()
        deliver.complete(Unit)
        job.join()
        assertEquals(false, enteredAttempt)
        assertEquals(1, cooldownReleases)
        val afterCancel = assertNotNull(scheduler.runtimeState(account.id, policy))
        assertEquals(0, afterCancel.activeRequests)
        assertEquals(1, afterCancel.windowUsed)
        val next = assertIs<OAuthAccountDecision.Allowed>(scheduler.acquire(account))
        scheduler.release(next.lease)
        assertEquals(OAuthAccountDenyReason.WINDOW, assertIs<OAuthAccountDecision.Denied>(scheduler.acquire(account)).reason)
    }

    @Test
    fun `admission exception and failing diagnostics preserve cause and release all held resources once`() = runTest {
        val cause = IllegalStateException("oauth admission failed")
        val releases = mutableListOf<String>()
        val cooldown = CandidateCooldown(CooldownPolicy())
        val resources = CandidateAttemptResources(
            releaseCooldown = { cooldown.release(it); releases += "cooldown" },
            releaseRateLimit = { releases += "channel"; error("release diagnostic failure") },
            releaseOAuth = { releases += "oauth" },
            releaseProbe = {},
            onReleaseFailure = { _, _ -> error("alarm failure") },
        )
        val observed = assertFailsWith<IllegalStateException> {
            resources.use {
                it.acquireCooldown { cooldown.acquire("channel", "key", "model") }
                it.acquireRateLimit {
                    ChannelKeyRateLimitDecision.Allowed(ChannelKeyRateLimitLease("channel", "key", true))
                }
                it.acquireOAuth { throw cause }
            }
        }
        assertEquals(cause.message, observed.message)
        // Coroutine stacktrace recovery may copy the exception but retains its original cause.
        assertSame(cause, observed.cause ?: observed)
        assertEquals(listOf("oauth", "channel", "cooldown"), releases)
    }

    @Test
    fun `upstream attempt remains cancellable and settled cooldown is not released twice`() = runTest {
        val cooldown = CandidateCooldown(CooldownPolicy())
        var cooldownReleases = 0
        var channelReleases = 0
        var oauthReleases = 0
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            CandidateAttemptResources(
                releaseCooldown = { cooldownReleases += 1 },
                releaseRateLimit = { channelReleases += 1 },
                releaseOAuth = { oauthReleases += 1 },
                releaseProbe = {},
                onReleaseFailure = { _, _ -> },
            ).use { resources ->
                resources.acquireCooldown { cooldown.acquire("channel", "key", "model") }
                resources.acquireRateLimit {
                    ChannelKeyRateLimitDecision.Allowed(ChannelKeyRateLimitLease("channel", "key", true))
                }
                resources.acquireOAuth { OAuthAccountDecision.Allowed(OAuthAccountLease("account")) }
                assertTrue(currentCoroutineContext()[Job]?.isActive == true)
                resources.recordCooldown { cooldown.recordSuccess(it) }
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancel()
        job.join()
        assertEquals(0, cooldownReleases)
        assertEquals(1, channelReleases)
        assertEquals(1, oauthReleases)
    }

    private fun channelKey() = ChannelKeyRecord(
        id = "key", channelId = "channel", name = "key",
        credentialCiphertext = "ciphertext", credentialNonce = "nonce", credentialKeyVersion = 1,
        state = ChannelKeyState.ACTIVE, priority = 0,
        rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
        createdAt = 0, updatedAt = 0,
    )

    private fun oauthAccount(policy: OAuthAccountPolicy) = OAuthAccountRecord(
        id = "account", name = "account", provider = OAuthProvider.CHATGPT,
        refreshTokenCiphertext = "ciphertext", refreshTokenNonce = "nonce", refreshTokenKeyVersion = 1,
        priority = 0, createdAt = 0, updatedAt = 0, policy = policy,
    )
}
