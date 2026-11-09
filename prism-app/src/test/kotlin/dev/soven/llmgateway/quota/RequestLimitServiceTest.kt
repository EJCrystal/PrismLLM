package dev.soven.llmgateway.quota

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.domain.RequestRateLimiter
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
        var quotaReads = 0
        val quotaService = quotaService { quotaReads++ }
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val key = key(
            id = "key-a",
            quotas = ClientKeyQuotaLimits(requestsPerMinute = 1),
        )

        val lease = service.acquire(key)
        assertEquals(1, quotaReads)
        val rejected = assertFailsWith<GatewayError> { service.acquire(key) }
        assertEquals("key_rate_limit_exceeded", rejected.code)
        // 限流拒绝发生在配额读取之前，不能因拒绝而多读或占用并发。
        assertEquals(1, quotaReads)
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

        // 模拟客户端断开：协程已进入执行后才取消，finally 里的 release 仍必须释放并发名额。
        // started 信号保证协程确已启动并挂起在 awaitCancellation，否则未启动即取消时
        // finally 不会执行，测试会退化为竞态（CI 曾因此失败：expected 0 but was 1）。
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        val job = scope.launch {
            try {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                service.release(lease)
            }
        }
        started.await()
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

private fun quotaService(onRead: () -> Unit = {}): ClientKeyQuotaService {
    val usage = mutableMapOf<String, dev.soven.llmgateway.repo.StoredQuotaUsage>()
    return ClientKeyQuotaService(
        usageReader = { id, _ ->
            onRead()
            usage[id] ?: dev.soven.llmgateway.repo.StoredQuotaUsage()
        },
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
