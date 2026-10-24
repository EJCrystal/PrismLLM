package dev.soven.llmgateway.quota

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.repo.StoredQuotaUsage
import kotlinx.coroutines.CompletableDeferred
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
            teamLimitReader = { null },
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
    fun `team slot is released when key quota rejects`() = runBlocking {
        val quotaService = quotaService()
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            teamLimitReader = {
                TeamLimitPolicy(team = "search", maxConcurrentRequests = 2)
            },
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val key = key(
            id = "key-a",
            team = "search",
            quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 1),
        )

        val first = service.acquire(key)
        val rejected = assertFailsWith<GatewayException> { service.acquire(key) }
        assertEquals("concurrency_limit_exceeded", rejected.code)
        assertEquals(1, service.teamUsage("search").activeConcurrentRequests)

        service.release(first)
        assertEquals(0, service.teamUsage("search").activeConcurrentRequests)
    }

    @Test
    fun `settling releases both key and team concurrency`() = runBlocking {
        val quotaService = quotaService()
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            teamLimitReader = {
                TeamLimitPolicy(team = "search", maxConcurrentRequests = 1)
            },
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val key = key(
            id = "key-a",
            team = "search",
            quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 1),
        )

        val lease = service.acquire(key)
        service.settle(lease, tokens = 10, costs = mapOf("USD" to BigDecimal("0.1")))

        assertEquals(0, quotaService.currentUsage(key.id).concurrentRequests)
        assertEquals(0, service.teamUsage("search").activeConcurrentRequests)
    }

    @Test
    fun `usage write failure releases key and team concurrency`() = runBlocking {
        val quotaService = ClientKeyQuotaService(
            usageReader = { _, _ -> StoredQuotaUsage() },
            usageWriter = { _, _, _, _ -> throw IllegalStateException("database unavailable") },
            now = { 0L },
        )
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            teamLimitReader = {
                TeamLimitPolicy(team = "search", maxConcurrentRequests = 1)
            },
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val key = key(
            id = "key-a",
            team = "search",
            quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 1),
        )
        val lease = service.acquire(key)

        assertFailsWith<IllegalStateException> {
            service.settle(lease, tokens = 10, costs = emptyMap())
        }

        assertEquals(0, quotaService.currentUsage(key.id).concurrentRequests)
        assertEquals(0, service.teamUsage("search").activeConcurrentRequests)
    }

    @Test
    fun `cancelling while key quota waits preserves other team slots`() = runBlocking {
        val readerEntered = CompletableDeferred<Unit>()
        val unblockReader = CompletableDeferred<Unit>()
        var blockSecondKey = true
        val quotaService = ClientKeyQuotaService(
            usageReader = { id, _ ->
                if (id == "key-b" && blockSecondKey) {
                    readerEntered.complete(Unit)
                    unblockReader.await()
                }
                StoredQuotaUsage()
            },
            usageWriter = { _, _, _, _ -> },
            now = { 0L },
        )
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            teamLimitReader = {
                TeamLimitPolicy(team = "search", maxConcurrentRequests = 2)
            },
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val first = service.acquire(key("key-a", team = "search"))
        val waiting = launch { service.acquire(key("key-b", team = "search")) }

        readerEntered.await()
        waiting.cancelAndJoin()
        unblockReader.complete(Unit)
        assertEquals(1, service.teamUsage("search").activeConcurrentRequests)

        blockSecondKey = false
        val retry = service.acquire(key("key-b", team = "search"))
        assertEquals(2, service.teamUsage("search").activeConcurrentRequests)
        service.release(retry)
        service.release(first)
        assertEquals(0, service.teamUsage("search").activeConcurrentRequests)
    }

    @Test
    fun `cancelling usage settlement releases key and team slots`() = runBlocking {
        val writerEntered = CompletableDeferred<Unit>()
        val unblockWriter = CompletableDeferred<Unit>()
        var blockWriter = true
        val quotaService = ClientKeyQuotaService(
            usageReader = { _, _ -> StoredQuotaUsage() },
            usageWriter = { _, _, _, _ ->
                if (blockWriter) {
                    writerEntered.complete(Unit)
                    unblockWriter.await()
                }
            },
            now = { 0L },
        )
        val service = RequestLimitService(
            clientKeyQuotaService = quotaService,
            teamLimitReader = {
                TeamLimitPolicy(team = "search", maxConcurrentRequests = 1)
            },
            rateLimiter = RequestRateLimiter(now = { 0L }),
        )
        val lease = service.acquire(key("key-a", team = "search"))
        val settling = launch {
            service.settle(lease, tokens = 10, costs = emptyMap())
        }

        writerEntered.await()
        settling.cancelAndJoin()
        unblockWriter.complete(Unit)
        assertEquals(0, quotaService.currentUsage("key-a").concurrentRequests)
        assertEquals(0, service.teamUsage("search").activeConcurrentRequests)

        blockWriter = false
        val retry = service.acquire(key("key-a", team = "search"))
        service.release(retry)
        assertEquals(0, service.teamUsage("search").activeConcurrentRequests)
    }

    private fun quotaService(): ClientKeyQuotaService {
        val usage = mutableMapOf<String, StoredQuotaUsage>()
        return ClientKeyQuotaService(
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
            now = { 0L },
        )
    }

    private fun key(
        id: String,
        team: String? = null,
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
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
