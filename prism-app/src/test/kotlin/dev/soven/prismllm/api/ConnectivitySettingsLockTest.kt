package dev.soven.prismllm.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.soven.prismllm.plugin.host.PluginHostException
import dev.soven.prismllm.testsupport.db.SharedPostgres
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("component")
@Timeout(60)
class ConnectivitySettingsLockTest {
    @Test
    fun `advisory contention returns retryable busy without running the body and permits a later request`() = runBlocking {
        withDatabase { database, pool ->
            val lock = ConnectivitySettingsLock(database)
            for (initialTimeout in listOf("0", "100ms")) {
                val callbacks = AtomicInteger()
                pool.connection.use { connection ->
                    connection.executeSql("SET lock_timeout = '$initialTimeout'")
                    connection.executeSql("SET statement_timeout = '${if (initialTimeout == "0") "5s" else "500ms"}'")
                }
                DriverManager.getConnection(pool.jdbcUrl, pool.username, pool.password).use { holder ->
                    holder.autoCommit = false
                    holder.executeSql(LOCK_SQL)
                    try {
                        val failure = assertFailsWith<PluginHostException> {
                            lock.withLock { callbacks.incrementAndGet() }
                        }

                        assertEquals("PLUGIN_BUSY", failure.code)
                        assertEquals(503, failure.statusCode)
                        assertEquals("55P03", (failure.cause as? SQLException)?.sqlState)
                        assertEquals(0, callbacks.get(), "a failed acquisition must never run the protected operation")
                        pool.connection.use { assertEquals(initialTimeout, it.scalarText("SELECT current_setting('lock_timeout')")) }
                    } finally {
                        holder.rollback()
                    }

                    assertEquals(1, lock.withLock { callbacks.incrementAndGet() })
                    assertEquals(1, callbacks.get(), "only the explicit later request may run the operation")
                    assertTrue(holder.tryConnectivityLock(), "the successful request must release its transaction lock")
                    holder.rollback()
                }
            }
        }
    }

    @Test
    fun `successful scope holds the existing advisory lock and restores the previous timeout before the body`() = runBlocking {
        withDatabase { database, pool ->
            pool.connection.use { it.executeSql("SET lock_timeout = '7s'") }
            DriverManager.getConnection(pool.jdbcUrl, pool.username, pool.password).use { observer ->
                val result = ConnectivitySettingsLock(database).withLock {
                    val timeout = TransactionManager.current().exec("SELECT current_setting('lock_timeout')") {
                        check(it.next())
                        it.getString(1)
                    }
                    assertEquals("7s", timeout, "the wait bound must not change the protected operation's lock policy")
                    assertFalse(observer.tryConnectivityLock(), "the original lock must cover the whole operation")
                    "saved"
                }

                assertEquals("saved", result)
                assertTrue(observer.tryConnectivityLock())
                pool.connection.use { assertEquals("7s", it.scalarText("SELECT current_setting('lock_timeout')")) }
            }
        }
    }

    @Test
    fun `protected operation failure and cancellation retain their identity and release the advisory lock`() = runBlocking {
        withDatabase { database, pool ->
            pool.connection.use { it.executeSql("SET lock_timeout = '7s'") }
            DriverManager.getConnection(pool.jdbcUrl, pool.username, pool.password).use { observer ->
                val lock = ConnectivitySettingsLock(database)
                val failures = listOf(
                    IllegalStateException("protected operation failed"),
                    PluginHostException("SYNTHETIC_FAILURE", "protected plugin operation failed", 503),
                    CancellationException("protected operation canceled"),
                )
                for (failure in failures) {
                    val thrown = runCatching {
                        lock.withLock {
                            assertFalse(observer.tryConnectivityLock())
                            throw failure
                        }
                    }.exceptionOrNull()

                    assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
                    assertTrue(observer.tryConnectivityLock())
                    pool.connection.use { assertEquals("7s", it.scalarText("SELECT current_setting('lock_timeout')")) }
                }
            }
        }
    }

    @Test
    fun `statement cancellation during advisory wait is not reclassified as lock contention`() = runBlocking {
        withDatabase { database, pool ->
            pool.connection.use { it.executeSql("SET statement_timeout = '100ms'") }
            val callbacks = AtomicInteger()
            DriverManager.getConnection(pool.jdbcUrl, pool.username, pool.password).use { holder ->
                holder.autoCommit = false
                holder.executeSql(LOCK_SQL)
                try {
                    val failure = assertFailsWith<SQLException> {
                        ConnectivitySettingsLock(database).withLock { callbacks.incrementAndGet() }
                    }

                    assertEquals("57014", failure.sqlState)
                    assertEquals(0, callbacks.get())
                    pool.connection.use { assertEquals("0", it.scalarText("SELECT current_setting('lock_timeout')")) }
                } finally {
                    holder.rollback()
                }
            }
        }
    }

    private suspend fun withDatabase(block: suspend (Database, HikariDataSource) -> Unit) {
        val postgres = SharedPostgres.container
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = SharedPostgres.newDatabase() + "?connectTimeout=5&socketTimeout=10"
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
            minimumIdle = 0
            connectionTimeout = 5_000
            connectionInitSql = "SET statement_timeout = '5s'"
        }).use { pool ->
            val database = Database.connect(pool)
            try {
                block(database, pool)
            } finally {
                TransactionManager.closeAndUnregister(database)
            }
        }
    }

    private fun Connection.executeSql(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.scalarText(sql: String): String = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getString(1)
        }
    }

    private fun Connection.tryConnectivityLock(): Boolean = createStatement().use { statement ->
        statement.executeQuery("SELECT pg_try_advisory_xact_lock(1347570253, 1129270862)").use { rows ->
            check(rows.next())
            rows.getBoolean(1)
        }
    }

    private companion object {
        const val LOCK_SQL = "SELECT pg_advisory_xact_lock(1347570253, 1129270862)"
    }
}
