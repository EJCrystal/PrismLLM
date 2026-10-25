package dev.soven.llmgateway.quota

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ClientKeyRecord
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestLimitServiceTest {
    @Test
    fun `key request limit rejects before quota reservation`() = runBlocking {
        val quotaService = quotaService()
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val key = key(
            id = "key-a",
            quotas = ClientKeyQuotaLimits(requestsPerMinute = 1),
        )

        val lease = service.acquire(key)
        val rejected = assertFailsWith<GatewayException> { service.acquire(key) }
        assertEquals("key_rate_limit_exceeded", rejected.code)
        service.release(lease)
        assertEquals(0, quotaService.currentUsage(key.id).concurrentRequests)
    }

    @Test
    fun `release frees concurrency from a cancelled coroutine context`() = runBlocking {
        val quotaService = quotaService()
        val service = RequestLimitService(clientKeyQuotaService = quotaService)
        val key = key(id = "key-a", quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 1))

        val lease = service.acquire(key)
        assertEquals(1, quotaService.currentUsage(key.id).concurrentRequests)

        // 模拟客户端断开：协程取消后，finally 里的 release 仍必须释放并发名额
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        val job = scope.launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                service.release(lease)
            }
        }
        job.cancelAndJoin()
        assertEquals(0, quotaService.currentUsage(key.id).concurrentRequests)
    }

    @Test
    fun `settle and release free the key concurrency`() = runBlocking {
        val quotaService = quotaService()
        val service = RequestLimitService(clientKeyQuotaService = quotaService)
        val key = key(id = "key-a")

        val lease = service.acquire(key)
        assertEquals(1, quotaService.currentUsage(key.id).concurrentRequests)
        service.settle(lease, tokens = 10L, costs = mapOf("USD" to BigDecimal("0.01")))
        assertEquals(0, quotaService.currentUsage(key.id).concurrentRequests)

        val second = service.acquire(key)
        assertEquals(1, quotaService.currentUsage(key.id).concurrentRequests)
        service.release(second)
        assertEquals(0, quotaService.currentUsage(key.id).concurrentRequests)
    }
}

private fun quotaService(): ClientKeyQuotaService {
    val usage = mutableMapOf<String, dev.soven.llmgateway.repo.StoredQuotaUsage>()
    return ClientKeyQuotaService(
        usageReader = { id, _ -> usage[id] ?: dev.soven.llmgateway.repo.StoredQuotaUsage() },
        usageWriter = { id, _, tokens, costs ->
            val previous = usage[id] ?: dev.soven.llmgateway.repo.StoredQuotaUsage()
            usage[id] = dev.soven.llmgateway.repo.StoredQuotaUsage(
                tokens = previous.tokens + tokens,
                costs = previous.costs.mapValues { (currency, amount) -> amount + (costs[currency] ?: java.math.BigDecimal.ZERO) },
            )
        },
        now = { 0L },
    )
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
