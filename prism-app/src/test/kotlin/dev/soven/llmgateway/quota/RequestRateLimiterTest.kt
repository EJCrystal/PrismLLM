package dev.soven.llmgateway.quota

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaLimits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestRateLimiterTest {
    @Test
    fun `key request limit resets on the next UTC minute`(): Unit = runBlocking {
        var now = 120_000L
        val limiter = RequestRateLimiter(now = { now })
        val key = key("key-a", ClientKeyQuotaLimits(requestsPerMinute = 2))

        limiter.acquire(key)
        limiter.acquire(key)
        assertEquals(2, limiter.currentKeyUsage(key.id))
        val rejected = assertFailsWith<GatewayError> { limiter.acquire(key) }
        assertEquals("key_rate_limit_exceeded", rejected.code)

        now = 180_000L
        assertEquals(0, limiter.currentKeyUsage(key.id))
        limiter.acquire(key)
    }

    @Test
    fun `key request limit is independent between keys`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })
        val first = key("key-a", ClientKeyQuotaLimits(requestsPerMinute = 1))
        val second = key("key-b", ClientKeyQuotaLimits(requestsPerMinute = 1))

        val firstLease = limiter.acquire(first)
        assertFailsWith<GatewayError> { limiter.acquire(first) }
        limiter.acquire(second)
        assertEquals(firstLease.clientKeyId, first.id)
        assertEquals(1, limiter.currentKeyUsage(second.id))
    }

    @Test
    fun `zero request limit blocks all requests`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })
        val key = key("key-a", ClientKeyQuotaLimits(requestsPerMinute = 0))

        val rejected = assertFailsWith<GatewayError> { limiter.acquire(key) }
        assertEquals("key_rate_limit_exceeded", rejected.code)
    }
}

private fun key(id: String, quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits()) = ClientKeyRecord(
    id = id,
    name = id,
    keyHash = "hash-$id",
    keyPrefix = "sk-gw-test",
    enabled = true,
    expiresAt = null,
    quotas = quotas,
    createdAt = 0,
    rotatedAt = null,
    revokedAt = null,
)
