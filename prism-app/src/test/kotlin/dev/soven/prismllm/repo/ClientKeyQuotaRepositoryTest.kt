package dev.soven.prismllm.repo

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.integration.IntegrationPostgres
import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import dev.soven.prismllm.quota.persistence.ClientKeyQuotaRepository
import dev.soven.prismllm.quota.persistence.ClientKeyQuotaUsages
import dev.soven.prismllm.quota.persistence.ClientKeyRepository
import dev.soven.prismllm.quota.persistence.ClientKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientKeyQuotaRepositoryTest {
    private lateinit var factory: DatabaseFactory
    private lateinit var otherFactory: DatabaseFactory
    private lateinit var repositories: List<ClientKeyQuotaRepository>
    private val period = 20_011L

    @BeforeAll
    fun start() {
        val postgres = IntegrationPostgres.container
        val config = GatewayConfig.fromEnv(
            mapOf(
                "DATABASE_URL" to IntegrationPostgres.newDatabase(),
                "DATABASE_USER" to postgres.username,
                "DATABASE_PASSWORD" to postgres.password,
                "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                "ADMIN_PASSWORD" to "test-password",
            ),
        )
        // 模板库已经迁移；独立连接池模拟多个网关实例共用同一 PostgreSQL 账本。
        factory = DatabaseFactory(config)
        otherFactory = DatabaseFactory(config)
        repositories = listOf(factory.database, factory.database, otherFactory.database, otherFactory.database)
            .map(::ClientKeyQuotaRepository)
    }

    @BeforeEach
    fun reset() {
        runBlocking {
            suspendTransaction(factory.database) {
                ClientKeyQuotaUsages.deleteAll()
                ClientKeys.deleteAll()
            }
        }
    }

    @AfterAll
    fun stop() {
        if (::otherFactory.isInitialized) otherFactory.close()
        if (::factory.isInitialized) factory.close()
        // IntegrationPostgres 的共享容器由整个测试 JVM 持有，不在此处停止。
    }

    @Test
    fun `concurrent first settlements insert one exact daily total across repository instances`() = runBlocking {
        val clientKeyId = createKey()
        assertUsage(clientKeyId, period, ClientKeyQuotaUsage())
        val writes = (1..16).map { index ->
            UsageWrite(
                clientKeyId, period,
                tokens = index * 1_000_000_007L,
                usdCost = BigDecimal("0.12345678") * index.toBigDecimal(),
            )
        }

        settleConcurrently(writes)

        assertUsage(clientKeyId, period, total(writes))
        assertEquals(1L, rowCount())
    }

    @Test
    fun `concurrent settlements atomically increment an existing daily row`() = runBlocking {
        val clientKeyId = createKey()
        val initial = ClientKeyQuotaUsage(4_000_000_000L, BigDecimal("1234567890.12345678"))
        repositories.first().addUsage(clientKeyId, period, initial.dailyTokens, initial.dailyUsdCost)
        assertUsage(clientKeyId, period, initial)
        val writes = (1..16).map { index ->
            UsageWrite(
                clientKeyId, period,
                tokens = index * 1_000_000_007L,
                usdCost = BigDecimal("0.00000001") * index.toBigDecimal(),
            )
        }

        settleConcurrently(writes)

        assertUsage(clientKeyId, period, total(writes, initial))
        assertEquals(1L, rowCount())
    }

    @Test
    fun `concurrent settlements isolate client keys and UTC periods`() = runBlocking {
        val firstKey = createKey()
        val secondKey = createKey()
        val targets = listOf(firstKey to period, firstKey to period + 1, secondKey to period, secondKey to period + 1)
        val writes = targets.flatMapIndexed { targetIndex, (clientKeyId, day) ->
            (1..8).map { index ->
                val multiplier = (targetIndex + 1) * index
                UsageWrite(
                    clientKeyId, day,
                    tokens = multiplier * 1_000_000_007L,
                    usdCost = BigDecimal("0.01020304") * multiplier.toBigDecimal(),
                )
            }
        }

        settleConcurrently(writes)

        writes.groupBy { it.clientKeyId to it.period }.forEach { (target, dailyWrites) ->
            assertUsage(target.first, target.second, total(dailyWrites))
        }
        assertUsage(firstKey, period - 1, ClientKeyQuotaUsage())
        assertUsage(secondKey, period - 1, ClientKeyQuotaUsage())
        assertEquals(4L, rowCount())
    }

    @Test
    fun `negative token deltas are clamped on insert and update without losing USD precision`() = runBlocking {
        val clientKeyId = createKey()
        repositories[0].addUsage(clientKeyId, period, Long.MIN_VALUE, BigDecimal("0.00000001"))
        assertUsage(clientKeyId, period, ClientKeyQuotaUsage(0, BigDecimal("0.00000001")))

        repositories[1].addUsage(clientKeyId, period, 9, BigDecimal("0.12345678"))
        repositories[2].addUsage(clientKeyId, period, -4, BigDecimal("1.00000000"))

        assertUsage(clientKeyId, period, ClientKeyQuotaUsage(9, BigDecimal("1.12345679")))
        assertEquals(1L, rowCount())
    }

    private suspend fun createKey(): String = ClientKeyRepository(factory.database).create(
        name = "quota-test",
        keyHash = UUID.randomUUID().toString().replace("-", "").padEnd(64, '0'),
        keyPrefix = "sk-gw-test",
        enabled = true,
        expiresAt = null,
    ).id

    private suspend fun settleConcurrently(writes: List<UsageWrite>) = coroutineScope {
        val ready = List(writes.size) { CompletableDeferred<Unit>() }
        val barrier = CompletableDeferred<Unit>()
        val workers = writes.mapIndexed { index, write ->
            async(Dispatchers.IO) {
                ready[index].complete(Unit)
                barrier.await()
                repositories[index % repositories.size].addUsage(
                    write.clientKeyId, write.period, write.tokens, write.usdCost,
                )
            }
        }
        // 所有协程抵达 barrier 后才同时放行；等待发生在取数据库连接之前，避免耗尽连接池。
        ready.awaitAll()
        barrier.complete(Unit)
        workers.awaitAll()
    }

    private suspend fun assertUsage(clientKeyId: String, day: Long, expected: ClientKeyQuotaUsage) {
        repositories.forEachIndexed { index, repository ->
            assertEquals(expected, repository.usage(clientKeyId, day), "repository $index, key $clientKeyId, period $day")
        }
    }

    private suspend fun rowCount(): Long = suspendTransaction(factory.database) {
        ClientKeyQuotaUsages.selectAll().count()
    }

    private fun total(writes: List<UsageWrite>, initial: ClientKeyQuotaUsage = ClientKeyQuotaUsage()) = ClientKeyQuotaUsage(
        dailyTokens = initial.dailyTokens + writes.sumOf { it.tokens },
        dailyUsdCost = writes.fold(initial.dailyUsdCost) { cost, write -> cost + write.usdCost },
    )

    private data class UsageWrite(val clientKeyId: String, val period: Long, val tokens: Long, val usdCost: BigDecimal)
}
