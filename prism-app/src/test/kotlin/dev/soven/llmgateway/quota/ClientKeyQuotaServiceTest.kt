package dev.soven.llmgateway.quota

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaLimits
import dev.soven.llmgateway.repo.StoredQuotaUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClientKeyQuotaServiceTest {
    @Test
    fun `releases concurrency after success`() = runBlocking {
        val service = InMemoryUsageRepository().service()
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))

        val lease = service.acquire(key)
        service.settle(lease, tokens = 7, costs = mapOf("USD" to BigDecimal("0.25")))

        assertEquals(0, service.currentUsage(key.id).concurrentRequests)
        assertEquals(7, service.currentUsage(key.id).dailyTokens)
    }

    @Test
    fun `releases concurrency after failure without usage`() = runBlocking {
        val service = InMemoryUsageRepository().service()
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))

        val lease = service.acquire(key)
        service.settle(lease, tokens = 0, costs = emptyMap())

        service.acquire(key)
        assertEquals(1, service.currentUsage(key.id).concurrentRequests)
    }

    @Test
    fun `rejects second request while first request is active`() = runBlocking {
        val service = InMemoryUsageRepository().service()
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val first = service.acquire(key)

        val error = assertFailsWith<GatewayError> { service.acquire(key) }

        assertEquals(429, error.status.value)
        assertEquals("concurrency_limit_exceeded", error.code)
        service.release(first)
    }

    @Test
    fun `allows concurrent acquisitions up to limit`() = runBlocking {
        val service = InMemoryUsageRepository().service()
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 2))
        val gate = CompletableDeferred<Unit>()

        val leases = listOf(
            async { gate.await(); service.acquire(key) },
            async { gate.await(); service.acquire(key) },
        )
        gate.complete(Unit)
        leases.awaitAll().forEach(service::release)

        assertEquals(0, service.currentUsage(key.id).concurrentRequests)
    }

    @Test
    fun `checks persisted tokens and costs on next acquisition`() = runBlocking {
        val storage = InMemoryUsageRepository()
        val service = storage.service()
        val key = key(ClientKeyQuotaLimits(dailyTokens = 5, dailyCosts = mapOf("USD" to BigDecimal("1"))))

        val lease = service.acquire(key)
        service.settle(lease, tokens = 5, costs = mapOf("USD" to BigDecimal("1")))

        val error = assertFailsWith<GatewayError> { service.acquire(key) }
        assertEquals("daily_token_quota_exceeded", error.code)
        assertTrue(storage.usage[key.id]!!.tokens == 5L)
    }

    @Test
    fun `releases concurrency when usage write fails`() = runBlocking {
        var shouldFail = true
        val service = ClientKeyQuotaService(
            usageReader = { _, _ -> StoredQuotaUsage() },
            usageWriter = { _, _, _, _ ->
                if (shouldFail) throw IllegalStateException("database unavailable")
            },
            now = { 1L },
        )
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val lease = service.acquire(key)

        assertFailsWith<IllegalStateException> {
            service.settle(lease, tokens = 10, costs = emptyMap())
        }

        shouldFail = false
        val next = service.acquire(key)
        service.release(next)
        assertEquals(0, service.currentUsage(key.id).concurrentRequests)
    }

    private fun key(quotas: ClientKeyQuotaLimits) = ClientKeyRecord(
        id = "key-a",
        name = "test",
        keyHash = "hash",
        keyPrefix = "sk-gw-test",
        enabled = true,
        expiresAt = null,
        quotas = quotas,
        createdAt = 0,
    )

    private class InMemoryUsageRepository : InMemoryQuotaStore()

    private open class InMemoryQuotaStore {
        val usage = mutableMapOf<String, StoredQuotaUsage>()

        fun service(now: () -> Long = { 1L }) = ClientKeyQuotaService(
            usageReader = { id, _ -> usage[id] ?: StoredQuotaUsage() },
            usageWriter = { id, _, tokens, costs ->
                val previous = usage[id] ?: StoredQuotaUsage()
                usage[id] = StoredQuotaUsage(
                    tokens = previous.tokens + tokens,
                    costs = previous.costs.mapValues { (currency, amount) ->
                        amount + (costs[currency] ?: BigDecimal.ZERO)
                    },
                )
            },
            now = now,
        )
    }
}
