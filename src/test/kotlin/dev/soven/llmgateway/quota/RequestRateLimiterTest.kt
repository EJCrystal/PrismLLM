package dev.soven.llmgateway.quota

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ClientKeyRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestRateLimiterTest {
    @Test
    fun `key request limit resets on the next UTC minute`() = runBlocking {
        var now = 120_000L
        val limiter = RequestRateLimiter(now = { now })
        val key = key("key-a", ClientKeyQuotaLimits(requestsPerMinute = 2))

        val first = limiter.acquire(key, null)
        val second = limiter.acquire(key, null)
        assertEquals(2, limiter.currentKeyUsage(key.id))
        val rejected = assertFailsWith<GatewayException> { limiter.acquire(key, null) }
        assertEquals("key_rate_limit_exceeded", rejected.code)

        now = 180_000L
        assertEquals(0, limiter.currentKeyUsage(key.id))
        val nextMinute = limiter.acquire(key, null)
        limiter.release(first)
        limiter.release(second)
        limiter.release(nextMinute)
    }

    @Test
    fun `team request limit is shared across keys`() = runBlocking {
        var now = 0L
        val limiter = RequestRateLimiter(now = { now })
        val policy = TeamLimitPolicy(team = "search", requestsPerMinute = 2)
        val firstKey = key("key-a", team = "search")
        val secondKey = key("key-b", team = "search")

        val first = limiter.acquire(firstKey, policy)
        val second = limiter.acquire(secondKey, policy)
        val rejected = assertFailsWith<GatewayException> { limiter.acquire(secondKey, policy) }
        assertEquals("team_rate_limit_exceeded", rejected.code)

        now = 60_000L
        val nextMinute = limiter.acquire(secondKey, policy)
        listOf(first, second, nextMinute).forEach { limiter.release(it) }
    }

    @Test
    fun `team concurrency is shared and released across keys`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })
        val policy = TeamLimitPolicy(team = "search", maxConcurrentRequests = 1)
        val firstKey = key("key-a", team = "search")
        val secondKey = key("key-b", team = "search")
        val first = limiter.acquire(firstKey, policy)

        val rejected = assertFailsWith<GatewayException> { limiter.acquire(secondKey, policy) }
        assertEquals("team_concurrency_limit_exceeded", rejected.code)

        limiter.release(first)
        val next = limiter.acquire(secondKey, policy)
        val usage = limiter.currentTeamUsage("search")
        assertEquals(1, usage.activeConcurrentRequests)
        assertEquals(2, usage.currentMinuteRequests)
        limiter.release(next)
        assertEquals(0, limiter.currentTeamUsage("search").activeConcurrentRequests)
    }

    @Test
    fun `zero request limit blocks all requests`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })
        val key = key("key-a", ClientKeyQuotaLimits(requestsPerMinute = 0))

        val error = assertFailsWith<GatewayException> { limiter.acquire(key, null) }
        assertEquals("key_rate_limit_exceeded", error.code)
    }

    private fun key(
        id: String,
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
        team: String? = null,
    ) = ClientKeyRecord(
        id = id,
        name = id,
        team = team,
        keyHash = "hash-$id",
        keyPrefix = "sk-gw-test",
        enabled = true,
        expiresAt = null,
        quotas = quotas,
        createdAt = 0,
    )
}
