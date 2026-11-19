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
import java.lang.reflect.Proxy
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

    @Test
    fun `caller times out during uninterruptible commit and lease stays owned through cleanup`() = runBlocking {
        val jdbc = BlockingJdbc()
        val waiter = async(Dispatchers.Default) {
            DiagnosticPreparationDeadline.withinSynchronous(1_000) {
                DiagnosticPreparationDeadline.diagnosticConnection(jdbc.connection) { jdbc.discards.incrementAndGet() }.use {
                    it.commit()
                }
                true
            }
        }
        try {
            assertSignalled(jdbc.commitEntered, "commit should enter the fake driver")
            assertNull(withTimeout(3_000) { waiter.await() }, "caller must stop waiting while commit is blocked")
            assertEquals(0, jdbc.returns.get(), "commit still owns the lease after the caller times out")
            assertEquals(1L, jdbc.closeEntered.count)
            assertEquals(0, jdbc.aborts.get(), "caller must never abort the owner's connection")

            jdbc.allowCommit.countDown()
            assertSignalled(jdbc.closeEntered, "owner must reach close after commit returns")
            assertEquals(0, jdbc.returns.get(), "blocked close must retain the lease")
            assertEquals(jdbc.originalTimeout, jdbc.networkTimeout.get(), "restore timeout before pool return")
            jdbc.allowClose.countDown()
            assertSignalled(jdbc.returned, "lease should return once real cleanup finishes")
            assertEquals(1, jdbc.returns.get())
            assertEquals(0, jdbc.aborts.get())
            assertEquals(0, jdbc.discards.get())
            assertTrue(jdbc.timeoutWrites.get() >= 2)

            // Reusing an ordinary connection cannot be touched by any late diagnostic cancellation.
            val ordinary = BlockingJdbc().apply { releaseAll() }
            assertSame(ordinary.connection, DiagnosticPreparationDeadline.diagnosticConnection(ordinary.connection) {
                ordinary.discards.incrementAndGet()
            })
            ordinary.connection.use { it.commit() }
            assertEquals(0, ordinary.timeoutWrites.get(), "ordinary transactions must keep their timeout settings")
            assertEquals(0, ordinary.aborts.get())
        } finally {
            jdbc.releaseAll()
            withTimeout(3_000) { waiter.join() }
            assertSignalled(jdbc.returned, "test must release its owned lease")
        }
    }

    @Test
    fun `external cancellation returns during rollback and drain waits for actual close`() = runBlocking {
        val jdbc = BlockingJdbc()
        val enteredOperation = CountDownLatch(1)
        val waiter = async(Dispatchers.Default) {
            DiagnosticPreparationDeadline.withinSynchronous(20_000) {
                DiagnosticPreparationDeadline.diagnosticConnection(jdbc.connection) { jdbc.discards.incrementAndGet() }.use {
                    try {
                        enteredOperation.countDown()
                        delay(Long.MAX_VALUE)
                    } finally {
                        it.rollback()
                    }
                }
                true
            }
        }
        try {
            assertSignalled(enteredOperation, "operation should own its lease")
            waiter.cancel()
            withTimeout(3_000) { waiter.join() }
            assertTrue(waiter.isCancelled)
            assertSignalled(jdbc.rollbackEntered, "cancellation must still execute rollback")
            assertEquals(0, jdbc.returns.get())
            assertEquals(0, jdbc.aborts.get())
            assertTrue(jdbc.networkTimeout.get() in 1..1_000, "rollback needs a positive cleanup timeout")
            jdbc.allowRollback.countDown()
            assertSignalled(jdbc.closeEntered, "close follows rollback")
            assertEquals(0, jdbc.returns.get())
            jdbc.allowClose.countDown()
            assertSignalled(jdbc.returned, "lease returns only when cleanup really finishes")
        } finally {
            jdbc.releaseAll()
            withTimeout(3_000) { waiter.join() }
        }

        val drainingJdbc = BlockingJdbc()
        val drain = async(Dispatchers.Default) {
            DiagnosticPreparationDeadline.withinSynchronousAndDrain(1_000) {
                DiagnosticPreparationDeadline.diagnosticConnection(drainingJdbc.connection) {
                    drainingJdbc.discards.incrementAndGet()
                }.use { it.commit() }
                true
            }
        }
        try {
            assertSignalled(drainingJdbc.commitEntered, "draining operation should enter commit")
            // An independent bounded waiter observes expiry without cancelling the draining caller.
            assertNull(kotlinx.coroutines.withTimeoutOrNull(1_500) { drain.await() })
            assertTrue(!drain.isCompleted, "drain must wait for the real worker, not its cancellation flag")
            drainingJdbc.allowCommit.countDown()
            assertSignalled(drainingJdbc.closeEntered, "drain must also cover blocked close")
            assertTrue(!drain.isCompleted)
            drainingJdbc.allowClose.countDown()
            assertNull(withTimeout(3_000) { drain.await() })
            assertSignalled(drainingJdbc.returned, "drain returns after the lease is released")
        } finally {
            drainingJdbc.releaseAll()
            withTimeout(3_000) { drain.join() }
        }
    }

    private class BlockingJdbc {
        val originalTimeout = 7_000
        val networkTimeout = AtomicInteger(originalTimeout)
        val timeoutWrites = AtomicInteger()
        val aborts = AtomicInteger()
        val discards = AtomicInteger()
        val returns = AtomicInteger()
        val commitEntered = CountDownLatch(1)
        val rollbackEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val allowRollback = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getNetworkTimeout" -> networkTimeout.get()
                "setNetworkTimeout" -> {
                    timeoutWrites.incrementAndGet()
                    networkTimeout.set(args!![1] as Int)
                    null
                }
                "commit" -> { block(commitEntered, allowCommit); null }
                "rollback" -> { block(rollbackEntered, allowRollback); null }
                "close" -> {
                    block(closeEntered, allowClose)
                    returns.incrementAndGet()
                    returned.countDown()
                    null
                }
                "abort" -> { aborts.incrementAndGet(); null }
                "isClosed" -> returns.get() != 0
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BlockingJdbc"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Connection

        fun releaseAll() {
            allowCommit.countDown()
            allowRollback.countDown()
            allowClose.countDown()
        }

        private fun block(entered: CountDownLatch, release: CountDownLatch) {
            entered.countDown()
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (release.count != 0L) {
                check(System.nanoTime() < limit) { "test did not release ${Thread.currentThread().name}" }
                try {
                    release.await(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Deliberately model a JDBC driver that ignores thread interruption.
                }
            }
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
