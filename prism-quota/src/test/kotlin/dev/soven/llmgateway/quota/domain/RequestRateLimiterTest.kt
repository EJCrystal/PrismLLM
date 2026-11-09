package dev.soven.llmgateway.quota.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RequestRateLimiterTest {
    @Test
    fun `key request limit resets on the next UTC minute`(): Unit = runBlocking {
        var now = 120_000L
        val limiter = RequestRateLimiter(now = { now })

        assertIs<RequestRateLimitDecision.Allowed>(limiter.acquire("key-a", requestsPerMinute = 2))
        assertIs<RequestRateLimitDecision.Allowed>(limiter.acquire("key-a", requestsPerMinute = 2))
        assertEquals(2, limiter.currentKeyUsage("key-a"))
        assertEquals(RequestRateLimitDecision.Denied, limiter.acquire("key-a", requestsPerMinute = 2))

        now = 180_000L
        assertEquals(0, limiter.currentKeyUsage("key-a"))
        assertIs<RequestRateLimitDecision.Allowed>(limiter.acquire("key-a", requestsPerMinute = 2))
    }

    @Test
    fun `key request limit is independent between keys`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })

        val firstLease = limiter.acquire("key-a", requestsPerMinute = 1)
        assertIs<RequestRateLimitDecision.Allowed>(firstLease)
        assertEquals(RequestRateLimitDecision.Denied, limiter.acquire("key-a", requestsPerMinute = 1))
        assertIs<RequestRateLimitDecision.Allowed>(limiter.acquire("key-b", requestsPerMinute = 1))
        assertEquals("key-a", firstLease.lease.clientKeyId)
        assertEquals(1, limiter.currentKeyUsage("key-b"))
    }

    @Test
    fun `zero request limit blocks all requests`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })

        assertEquals(RequestRateLimitDecision.Denied, limiter.acquire("key-a", requestsPerMinute = 0))
        assertEquals(0, limiter.currentKeyUsage("key-a"))
    }

    @Test
    fun `missing limit admits every request`() = runBlocking {
        val limiter = RequestRateLimiter(now = { 0L })

        repeat(5) {
            assertIs<RequestRateLimitDecision.Allowed>(limiter.acquire("key-a", requestsPerMinute = null))
        }
        assertEquals(5, limiter.currentKeyUsage("key-a"))
    }
}