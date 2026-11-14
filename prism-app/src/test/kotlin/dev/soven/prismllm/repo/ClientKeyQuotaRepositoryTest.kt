package dev.soven.prismllm.repo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class ClientKeyQuotaRepositoryTest {
    private lateinit var factory: DatabaseFactory
    private lateinit var otherFactory: DatabaseFactory
    private lateinit var databaseUrl: String
    private lateinit var repositories: List<ClientKeyQuotaRepository>
    private val period = 20_011L

    @BeforeAll
    fun start() {
        val postgres = IntegrationPostgres.container
        databaseUrl = IntegrationPostgres.newDatabase()
        val config = GatewayConfig.fromEnv(
            mapOf(
                "DATABASE_URL" to databaseUrl,
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

    @Test
    fun `locked settlement times out once without increments and restores the pooled timeout after rollback`() = runBlocking {
        val clientKeyId = createKey()
        val initial = ClientKeyQuotaUsage(7, BigDecimal("0.00100000"))
        repositories.first().addUsage(clientKeyId, period, initial.dailyTokens, initial.dailyUsdCost)

        singleConnectionPool().use { pool ->
            val repository = ClientKeyQuotaRepository(Database.connect(pool))
            val originalSettings = sessionSettings(pool, initializeTimeout = true)
            withCountedQuotaWrites {
                testConnection().use { locker ->
                    locker.autoCommit = false
                    coroutineScope {
                        try {
                            locker.prepareStatement(
                                "select client_key_id from client_key_quota_usage where client_key_id = ? and period = ? for update",
                            ).use { statement ->
                                statement.queryTimeout = 2
                                statement.setString(1, clientKeyId)
                                statement.setLong(2, period)
                                statement.executeQuery().use { result ->
                                    assertTrue(result.next(), "the existing ledger row must be locked before settlement starts")
                                }
                            }
                            val pending = async(Dispatchers.IO) {
                                runCatching { repository.addUsage(clientKeyId, period, 13, BigDecimal("0.00001600")) }
                            }
                            // 只限时等待 worker；回归失败时先解除 JDBC 锁，再让作用域等待 worker 退出。
                            val result = withTimeout(10_000) { pending.await() }
                            val failure = assertNotNull(result.exceptionOrNull(), "settlement must fail while the row remains locked")
                            val timeout = assertNotNull(
                                generateSequence(failure) { it.cause }
                                    .filterIsInstance<SQLException>()
                                    .firstOrNull { it.sqlState == "57014" },
                                "settlement must fail with PostgreSQL query cancellation, not a coroutine watchdog: $failure",
                            )
                            assertTrue(timeout.message.orEmpty().contains("statement timeout"), timeout.toString())
                            assertEquals(1, quotaWriteAttempts(), "a timed out non-idempotent settlement must not be retried")
                            assertUsage(clientKeyId, period, initial)
                            assertEquals(1L, rowCount())
                            assertEquals(originalSettings, sessionSettings(pool), "rollback must restore the same pooled session's timeout")
                        } finally {
                            locker.rollback()
                        }
                    }
                }
            }
        }
        assertUsage(clientKeyId, period, initial)
    }

    @Test
    fun `successful settlement restores the statement timeout in the same single connection pool`() = runBlocking {
        val clientKeyId = createKey()
        singleConnectionPool().use { pool ->
            val repository = ClientKeyQuotaRepository(Database.connect(pool))
            val originalSettings = sessionSettings(pool, initializeTimeout = true)

            repository.addUsage(clientKeyId, period, 13, BigDecimal("0.00001600"))

            assertEquals(originalSettings, sessionSettings(pool), "commit must restore the same pooled session's timeout")
        }
        assertUsage(clientKeyId, period, ClientKeyQuotaUsage(13, BigDecimal("0.00001600")))
        assertEquals(1L, rowCount())
    }

    private fun singleConnectionPool(): HikariDataSource {
        val postgres = IntegrationPostgres.container
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = databaseUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
            minimumIdle = 1
            connectionTimeout = 2_000
            validationTimeout = 1_000
        })
    }

    private fun sessionSettings(pool: HikariDataSource, initializeTimeout: Boolean = false): SessionSettings =
        pool.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.queryTimeout = 2
                if (initializeTimeout) statement.execute("set statement_timeout = '17s'")
                val backendPid = statement.executeQuery("select pg_backend_pid()").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
                val statementTimeout = statement.executeQuery("show statement_timeout").use { result ->
                    check(result.next())
                    result.getString(1)
                }
                connection.commit()
                SessionSettings(backendPid, statementTimeout)
            }
        }

    private suspend fun withCountedQuotaWrites(block: suspend () -> Unit) {
        try {
            executeTestSql(
                "create sequence quota_settlement_write_attempts",
                """
                    create function quota_count_write_attempt() returns trigger language plpgsql as ${'$'}counter${'$'}
                    begin
                        perform nextval('quota_settlement_write_attempts');
                        return null;
                    end;
                    ${'$'}counter${'$'}
                """.trimIndent(),
                // INSERT 语句级触发器先于 UPSERT 的冲突行锁等待执行；UPDATE 触发器执行时已取得锁。
                "create trigger quota_count_write_attempt before insert on client_key_quota_usage for each statement execute function quota_count_write_attempt()",
            )
            block()
        } finally {
            executeTestSql(
                "drop trigger if exists quota_count_write_attempt on client_key_quota_usage",
                "drop function if exists quota_count_write_attempt()",
                "drop sequence if exists quota_settlement_write_attempts",
            )
        }
    }

    private fun quotaWriteAttempts(): Int = testConnection().use { connection ->
        connection.createStatement().use { statement ->
            statement.queryTimeout = 2
            statement.executeQuery("select case when is_called then last_value else 0 end from quota_settlement_write_attempts").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }

    private fun executeTestSql(vararg statements: String) {
        testConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 2
                statements.forEach { statement.execute(it) }
            }
        }
    }

    private fun testConnection(): Connection {
        val databaseName = databaseUrl.substringBefore('?').substringAfterLast('/')
        require(databaseName.matches(Regex("prism_test_[0-9]+"))) { "quota fault injection requires this class's PostgreSQL clone" }
        val postgres = IntegrationPostgres.container
        return DriverManager.getConnection(databaseUrl, postgres.username, postgres.password)
    }

    private data class SessionSettings(val backendPid: Int, val statementTimeout: String)

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
