package dev.soven.prismllm.quota.infra

import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.shared.error.GatewayError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
        service.settle(lease, tokens = 7, usdCost = BigDecimal("0.25"))

        assertEquals(0, service.currentUsage(key.id).concurrentRequests)
        assertEquals(7, service.currentUsage(key.id).dailyTokens)
    }

    @Test
    fun `releases concurrency after failure without usage`() = runBlocking {
        val service = InMemoryUsageRepository().service()
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))

        val lease = service.acquire(key)
        service.settle(lease, tokens = 0, usdCost = BigDecimal.ZERO)

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
        val key = key(ClientKeyQuotaLimits(dailyTokens = 5, dailyUsdLimit = BigDecimal("1")))

        val lease = service.acquire(key)
        service.settle(lease, tokens = 5, usdCost = BigDecimal("1"))

        val error = assertFailsWith<GatewayError> { service.acquire(key) }
        assertEquals("daily_token_quota_exceeded", error.code)
        assertTrue(storage.usage[key.id]!!.dailyTokens == 5L)
    }

    @Test
    fun `in flight requests can settle beyond soft daily limits`() = runBlocking {
        val service = InMemoryUsageRepository().service()
        val key = key(ClientKeyQuotaLimits(dailyTokens = 5, dailyUsdLimit = BigDecimal("1")))
        val first = service.acquire(key)
        val second = service.acquire(key)

        service.settle(first, tokens = 4, usdCost = BigDecimal("0.75"))
        service.settle(second, tokens = 4, usdCost = BigDecimal("0.75"))

        val usage = service.currentUsage(key.id)
        assertEquals(8L, usage.dailyTokens)
        assertEquals(BigDecimal("1.50"), usage.dailyUsdCost)
        assertEquals(0, usage.concurrentRequests)
        assertEquals("daily_token_quota_exceeded", assertFailsWith<GatewayError> { service.acquire(key) }.code)
    }

    @Test
    fun `settlement after UTC midnight uses the acquisition lease period`() = runBlocking {
        val midnight = 86_400_000L * 20_011
        var current = midnight - 1
        val usage = mutableMapOf<Pair<String, Long>, ClientKeyQuotaUsage>()
        val service = ClientKeyQuotaService(
            usageReader = { id, period -> usage[id to period] ?: ClientKeyQuotaUsage() },
            usageWriter = { id, period, tokens, usdCost ->
                val previous = usage[id to period] ?: ClientKeyQuotaUsage()
                usage[id to period] = ClientKeyQuotaUsage(
                    dailyTokens = previous.dailyTokens + tokens,
                    dailyUsdCost = previous.dailyUsdCost + usdCost,
                )
            },
            now = { current },
        )
        val key = key(ClientKeyQuotaLimits(dailyTokens = 5, maxConcurrentRequests = 2))
        val beforeMidnight = service.acquire(key)
        assertEquals(20_010L, beforeMidnight.period)

        current = midnight
        val afterMidnight = service.acquire(key)
        assertEquals(20_011L, afterMidnight.period)
        service.settle(beforeMidnight, tokens = 7, usdCost = BigDecimal("0.25"))

        assertEquals(ClientKeyQuotaUsage(7, BigDecimal("0.25")), usage[key.id to beforeMidnight.period])
        assertEquals(ClientKeyQuotaUsage(concurrentRequests = 1), service.currentUsage(key.id))

        service.settle(afterMidnight, tokens = 3, usdCost = BigDecimal("0.50"))
        assertEquals(ClientKeyQuotaUsage(3, BigDecimal("0.50")), service.currentUsage(key.id))
        assertEquals(ClientKeyQuotaUsage(7, BigDecimal("0.25")), usage[key.id to beforeMidnight.period])
    }

    @Test
    fun `releases concurrency when usage write is cancelled`() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        var writes = 0
        val service = ClientKeyQuotaService(
            usageReader = { _, _ -> ClientKeyQuotaUsage() },
            usageWriter = { _, _, _, _ ->
                writes++
                writing.complete(Unit)
                awaitCancellation()
            },
            now = { 1L },
        )
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val lease = service.acquire(key)
        val job = launch { service.settle(lease, tokens = 10, usdCost = BigDecimal("0.25")) }
        writing.await()
        assertEquals(1, service.currentUsage(key.id).concurrentRequests)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, writes)
        assertEquals(ClientKeyQuotaUsage(), service.currentUsage(key.id))
        val next = service.acquire(key)
        service.release(next)
        assertEquals(0, service.currentUsage(key.id).concurrentRequests)
    }

    @Test
    fun `releases concurrency when usage write fails`() = runBlocking {
        var shouldFail = true
        val service = ClientKeyQuotaService(
            usageReader = { _, _ -> ClientKeyQuotaUsage() },
            usageWriter = { _, _, _, _ ->
                if (shouldFail) throw IllegalStateException("database unavailable")
            },
            now = { 1L },
        )
        val key = key(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val lease = service.acquire(key)

        assertFailsWith<IllegalStateException> {
            service.settle(lease, tokens = 10, usdCost = BigDecimal.ZERO)
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
        val usage = mutableMapOf<String, ClientKeyQuotaUsage>()

        fun service(now: () -> Long = { 1L }) = ClientKeyQuotaService(
            usageReader = { id, _ -> usage[id] ?: ClientKeyQuotaUsage() },
            usageWriter = { id, _, tokens, usdCost ->
                val previous = usage[id] ?: ClientKeyQuotaUsage()
                usage[id] = ClientKeyQuotaUsage(
                    dailyTokens = previous.dailyTokens + tokens,
                    dailyUsdCost = previous.dailyUsdCost + usdCost,
                )
            },
            now = now,
        )
    }
}
