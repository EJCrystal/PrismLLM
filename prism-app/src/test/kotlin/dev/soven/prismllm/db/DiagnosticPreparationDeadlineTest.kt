package dev.soven.prismllm.db

import com.zaxxer.hikari.HikariDataSource
import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.testsupport.db.SharedPostgres
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Tag("component")
class DiagnosticPreparationDeadlineTest {
    @Test
    fun `timed out acquisition closes its late connection without running consumer action`() = runBlocking {
        val executor = acquisitionExecutor()
        val acquisitionStarted = CountDownLatch(1)
        val allowAcquisitionToReturn = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val acquisitionCalls = AtomicInteger()
        val consumerActions = AtomicInteger()
        val closeCalls = AtomicInteger()
        val lateConnection = TestConnection(connectionClosed, closeCalls)
        val clock = AtomicLong()
        val budget = DiagnosticPreparationDeadline.start(20_000, clock::get)

        try {
            val waiter = async(Dispatchers.Default) {
                DiagnosticPreparationDeadline.within(budget) {
                    val connection = DiagnosticPreparationDeadline.acquireConnection(executor) {
                        acquisitionCalls.incrementAndGet()
                        acquisitionStarted.countDown()
                        check(allowAcquisitionToReturn.await(3, TimeUnit.SECONDS)) {
                            "test did not release the blocked acquisition"
                        }
                        lateConnection
                    }
                    connection.use { consumerActions.incrementAndGet() }
                    true
                }
            }

            assertSignalled(acquisitionStarted, "connection acquisition should have started")
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20_001))
            val result = withTimeout(3_000) { waiter.await() }
            assertNull(result, "the active preparation deadline should expire while acquisition is blocked")
            assertEquals(1, acquisitionCalls.get())
            assertEquals(0, consumerActions.get(), "a connection that arrives after timeout must not reach its consumer")

            allowAcquisitionToReturn.countDown()
            assertSignalled(connectionClosed, "the late connection should be closed by its acquiring worker")
            assertEquals(1, closeCalls.get())
            assertEquals(0, consumerActions.get(), "no consumer SQL/action should run after the deadline")
        } finally {
            allowAcquisitionToReturn.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "acquisition executor did not terminate")
        }
    }

    @Test
    fun `exhausted Hikari pool bounds diagnostic acquisition and recovers after release`() = runBlocking {
        val postgres = SharedPostgres.container
        DatabaseFactory(
            GatewayConfig.fromEnv(
                mapOf(
                    "DATABASE_URL" to SharedPostgres.newDatabase(),
                    "DATABASE_USER" to postgres.username,
                    "DATABASE_PASSWORD" to postgres.password,
                    "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                    "ADMIN_PASSWORD" to "test-password",
                ),
            ),
        ).use { factory ->
            val dataSource = DatabaseFactory::class.java.getDeclaredField("dataSource").let { field ->
                field.isAccessible = true
                field.get(factory) as HikariDataSource
            }
            val connectionTimeout = dataSource.connectionTimeout
            val maxPoolSize = dataSource.maximumPoolSize
            val pool = checkNotNull(dataSource.hikariPoolMXBean)
            val borrowed = mutableListOf<Connection>()

            suspend fun selectOne(): Int = suspendTransaction(factory.database) {
                checkNotNull(exec("SELECT 1") { rows ->
                    check(rows.next())
                    rows.getInt(1)
                })
            }

            try {
                repeat(maxPoolSize) { borrowed += dataSource.connection }
                assertEquals(maxPoolSize, pool.activeConnections)

                val diagnostic = async(Dispatchers.IO) {
                    DiagnosticPreparationDeadline.within(2_000) { selectOne() }
                }
                // Keep every lease checked out until the diagnostic returns, before Hikari's own timeout.
                withTimeout(connectionTimeout - 1_000) {
                    while (pool.threadsAwaitingConnection == 0) delay(10)
                    assertEquals(connectionTimeout, dataSource.connectionTimeout)
                    assertEquals(maxPoolSize, dataSource.maximumPoolSize)
                    assertNull(diagnostic.await(), "diagnostic should time out before a pool lease is returned")
                }
            } finally {
                borrowed.forEach { it.close() }
            }

            withTimeout(5_000) {
                while (pool.threadsAwaitingConnection != 0 || pool.activeConnections != 0) delay(10)
            }
            assertEquals(1, selectOne())
            assertEquals(connectionTimeout, dataSource.connectionTimeout)
            assertEquals(maxPoolSize, dataSource.maximumPoolSize)
        }
    }

    @Test
    fun `ordinary acquisition stays on caller thread without changing pool settings`() {
        val executor = acquisitionExecutor()
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "unused executor did not terminate")

        val caller = Thread.currentThread()
        val acquisitionThread = AtomicReference<Thread>()
        val connectionClosed = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val connection = TestConnection(connectionClosed, closeCalls)

        DiagnosticPreparationDeadline.acquireConnection(executor) {
            acquisitionThread.set(Thread.currentThread())
            connection
        }.use { }

        assertSame(caller, acquisitionThread.get(), "outside a diagnostic scope acquisition should remain inline")
        assertEquals(1, closeCalls.get())
        assertTrue(connectionClosed.count == 0L)
        // The acquisition bridge receives only the supplier and executor; it has no per-request
        // Hikari timeout or pool-size mutation path. A shut-down executor also proves it was unused.
        assertTrue(executor.isShutdown)
    }

    @Test
    fun `external coroutine cancellation propagates while acquisition is blocked`() = runBlocking {
        val executor = acquisitionExecutor()
        val acquisitionStarted = CountDownLatch(1)
        val allowAcquisitionToReturn = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val cancellationObserved = AtomicInteger()
        val lateConnection = TestConnection(connectionClosed, closeCalls)

        try {
            val waiter = async(Dispatchers.Default) {
                try {
                    DiagnosticPreparationDeadline.within(5_000) {
                        val connection = DiagnosticPreparationDeadline.acquireConnection(executor) {
                            acquisitionStarted.countDown()
                            check(allowAcquisitionToReturn.await(3, TimeUnit.SECONDS)) {
                                "test did not release the blocked acquisition"
                            }
                            lateConnection
                        }
                        connection.use { true }
                    }
                } catch (cause: CancellationException) {
                    cancellationObserved.incrementAndGet()
                    throw cause
                }
            }

            assertSignalled(acquisitionStarted, "connection acquisition should have started")
            waiter.cancel()
            withTimeout(3_000) { waiter.join() }

            assertTrue(waiter.isCancelled)
            assertEquals(1, cancellationObserved.get(), "external cancellation must not be converted to a timeout result")

            allowAcquisitionToReturn.countDown()
            assertSignalled(connectionClosed, "the connection acquired after cancellation should be closed")
            assertEquals(1, closeCalls.get())
        } finally {
            allowAcquisitionToReturn.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "acquisition executor did not terminate")
        }
    }

    private class TestConnection(
        private val closed: CountDownLatch,
        private val closeCalls: AtomicInteger,
    ) : AutoCloseable {
        override fun close() {
            closeCalls.incrementAndGet()
            closed.countDown()
        }
    }

    private fun acquisitionExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        2,
        2,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4),
        ThreadFactory { task -> Thread(task, "diagnostic-deadline-test").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun assertSignalled(latch: CountDownLatch, message: String) {
        assertTrue(latch.await(3, TimeUnit.SECONDS), message)
    }
}
