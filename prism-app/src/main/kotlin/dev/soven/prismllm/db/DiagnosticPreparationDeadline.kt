package dev.soven.prismllm.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcPreparedStatementImpl
import org.jetbrains.exposed.v1.jdbc.transactions.JdbcTransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.postgresql.jdbc.PgStatement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Active wall-clock budget shared by both phases of one diagnostic preparation. */
internal class DiagnosticPreparationDeadline private constructor(
    private val deadlineNanos: Long,
    private val job: Job,
    private val nanoTime: () -> Long,
) : StatementInterceptor {
    /** Pause only outside [within]; nested scopes share the same absolute active deadline. */
    class Budget internal constructor(timeoutMs: Long, internal val nanoTime: () -> Long) {
        private var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        private var activeDeadlineNanos: Long? = nanoTime() + remainingNanos

        fun pause() = synchronized(this) {
            activeDeadlineNanos?.let { remainingNanos = (it - nanoTime()).coerceAtLeast(0) }
            activeDeadlineNanos = null
        }

        fun resume() = synchronized(this) {
            if (activeDeadlineNanos == null) activeDeadlineNanos = nanoTime() + remainingNanos
        }

        internal fun deadline(job: Job): Long {
            job.ensureActive()
            return synchronized(this) {
                checkNotNull(activeDeadlineNanos) { "diagnostic preparation budget is paused" }
                    .also { if (it - nanoTime() <= 0L) throw Expired() }
            }
        }

        internal fun remainingMillis(job: Job): Long = millisUntil(deadline(job), nanoTime)
    }

    private fun remainingMillis(): Long {
        job.ensureActive()
        return millisUntil(deadlineNanos, nanoTime)
    }

    override fun afterStatementPrepared(transaction: Transaction, preparedStatement: PreparedStatementApi) {
        // JDBC's seconds-based timeout would overrun a sub-second preparation budget.
        val statement = (preparedStatement as JdbcPreparedStatementImpl).statement.unwrap(PgStatement::class.java)
        statement.setQueryTimeoutMs(remainingMillis())
    }

    override fun beforeCommit(transaction: Transaction) {
        remainingMillis()
    }

    // Rollback must remain possible after expiry/cancellation. Its timeout belongs to the proxy.
    private class Expired : RuntimeException()

    private class ConnectionOwnership<T : AutoCloseable> {
        private enum class State { WAITING, ACQUIRING, READY, TRANSFERRED, ABANDONED, FAILED }
        private var state = State.WAITING
        private var value: T? = null
        private var failure: Throwable? = null

        fun beginAcquire(): Boolean = synchronized(this) {
            if (state != State.WAITING) return@synchronized false
            state = State.ACQUIRING
            true
        }

        fun provide(connection: T) {
            val callerOwns = synchronized(this) {
                if (state != State.ACQUIRING) false else {
                    value = connection
                    state = State.READY
                    (this as java.lang.Object).notifyAll()
                    true
                }
            }
            if (!callerOwns) runCatching { connection.close() }
        }

        fun fail(cause: Throwable) = synchronized(this) {
            if (state == State.ACQUIRING) {
                failure = cause
                state = State.FAILED
                (this as java.lang.Object).notifyAll()
            }
        }

        fun await(deadline: DiagnosticPreparationDeadline): T {
            synchronized(this) {
                while (state == State.WAITING || state == State.ACQUIRING) {
                    val remaining = TimeUnit.MILLISECONDS.toNanos(deadline.remainingMillis())
                    TimeUnit.NANOSECONDS.timedWait(this, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)))
                }
                return when (state) {
                    State.READY -> value!!.also {
                        deadline.remainingMillis()
                        state = State.TRANSFERRED
                    }
                    State.FAILED -> throw requireNotNull(failure)
                    else -> throw Expired()
                }
            }
        }

        fun abandon() {
            val late = synchronized(this) {
                val owned = if (state == State.READY) value else null
                if (state == State.WAITING || state == State.ACQUIRING || state == State.READY) state = State.ABANDONED
                value = null
                (this as java.lang.Object).notifyAll()
                owned
            }
            late?.let { runCatching { it.close() } }
        }
    }

    companion object {
        private const val CLEANUP_TIMEOUT_MS = 1_000L
        private val active = ThreadLocal<DiagnosticPreparationDeadline>()
        private val synchronousOwner = ThreadLocal<Boolean>()
        private val operations = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val operationSlots = Semaphore(16)
        private val directExecutor = Executor { command -> command.run() }

        private fun millisUntil(deadlineNanos: Long, nanoTime: () -> Long): Long {
            val remaining = deadlineNanos - nanoTime()
            if (remaining <= 0L) throw Expired()
            return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
        }

        fun start(timeoutMs: Long, nanoTime: () -> Long = System::nanoTime): Budget {
            require(timeoutMs > 0) { "diagnostic preparation timeout must be positive" }
            return Budget(timeoutMs, nanoTime)
        }

        /**
         * Wrap on the diagnostic owner after acquisition. [discard] must evict this checked-out
         * lease before returning (HikariDataSource.evictConnection). Ordinary connections are unchanged.
         * Cancellation never aborts a connection from another thread. The owner restores the original
         * timeout before pool return and retains its slot through a potentially blocking close.
         */
        fun diagnosticConnection(connection: Connection, discard: (Connection) -> Unit): Connection {
            val deadline = active.get() ?: return connection
            val terminal = AtomicBoolean()
            fun poison(cause: Throwable) {
                if (!terminal.compareAndSet(false, true)) return
                // Abort while still checked out. If abort blocks, this owner keeps its slot/lease.
                val aborted = runCatching { connection.abort(directExecutor) }
                aborted.exceptionOrNull()?.let { if (it !== cause) cause.addSuppressed(it) }
                val evicted = runCatching { discard(connection) }
                evicted.exceptionOrNull()?.let { if (it !== cause) cause.addSuppressed(it) }
                // If neither operation succeeded, quarantine the lease rather than recycle it.
                if (aborted.isSuccess || evicted.isSuccess) {
                    runCatching { connection.close() }.exceptionOrNull()?.let {
                        if (it !== cause) cause.addSuppressed(it)
                    }
                }
            }
            val originalNetworkTimeout = try {
                connection.networkTimeout
            } catch (cause: Throwable) {
                poison(cause)
                throw cause
            }
            fun setTimeout(timeout: Int) {
                try {
                    connection.setNetworkTimeout(directExecutor, timeout)
                } catch (cause: Throwable) {
                    poison(cause)
                    throw cause
                }
            }
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { proxy, method, args ->
                when {
                    method.declaringClass == Any::class.java -> when (method.name) {
                        "equals" -> proxy === args?.get(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "DiagnosticConnection($connection)"
                        else -> invoke(connection, method, args)
                    }
                    method.name == "isClosed" && terminal.get() -> true
                    method.name == "close" -> {
                        if (!terminal.get()) {
                            // A Hikari lease is recycled inside close(), so restore before entering
                            // it. Close itself is isolated by the worker, not by a late abort timer.
                            setTimeout(originalNetworkTimeout)
                            if (terminal.compareAndSet(false, true)) invoke(connection, method, args) else null
                        } else null
                    }
                    terminal.get() -> throw SQLException("Diagnostic connection is closed")
                    method.name == "commit" -> {
                        setTimeout(deadline.remainingMillis().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        invoke(connection, method, args)
                    }
                    method.name == "rollback" -> {
                        val remaining = (deadline.deadlineNanos - deadline.nanoTime()).coerceAtLeast(1L)
                        setTimeout(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, CLEANUP_TIMEOUT_MS).toInt())
                        invoke(connection, method, args)
                    }
                    method.name == "setNetworkTimeout" -> {
                        try {
                            invoke(connection, method, args)
                        } catch (cause: Throwable) {
                            poison(cause)
                            throw cause
                        }
                    }
                    else -> invoke(connection, method, args)
                }
            } as Connection
        }

        private fun invoke(connection: Connection, method: Method, args: Array<out Any?>?): Any? =
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (cause: InvocationTargetException) {
                throw cause.targetException
            }

        /** Ordinary acquisitions stay inline; late diagnostic acquisitions retain their own owner. */
        fun <T : AutoCloseable> acquireConnection(executor: ThreadPoolExecutor, acquire: () -> T): T {
            val deadline = active.get() ?: return acquire()
            val ownership = ConnectionOwnership<T>()
            val task = Runnable {
                if (!ownership.beginAcquire()) return@Runnable
                try {
                    ownership.provide(acquire())
                } catch (cause: Throwable) {
                    ownership.fail(cause)
                }
            }
            try {
                executor.execute(task)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                throw Expired()
            }
            return try {
                ownership.await(deadline)
            } catch (cause: Throwable) {
                ownership.abandon()
                executor.remove(task)
                throw cause
            }
        }

        fun transactionManager(database: Database): JdbcTransactionManager {
            val delegate = TransactionManager(database)
            return object : JdbcTransactionManager by delegate {
                override fun newTransaction(isolation: Int, readOnly: Boolean, outerTransaction: JdbcTransaction?): JdbcTransaction =
                    delegate.newTransaction(isolation, readOnly, outerTransaction).also { transaction ->
                        if (outerTransaction !== transaction) active.get()?.let { deadline ->
                            transaction.maxAttempts = 1
                            transaction.registerInterceptor(deadline)
                        }
                    }
            }
        }

        /** Caller wait is bounded; the supervised worker owns the lease through synchronous cleanup. */
        suspend fun <T : Any> withinSynchronous(timeoutMs: Long, operation: suspend () -> T): T? =
            runSynchronous(start(timeoutMs), drain = false, operation)

        suspend fun <T : Any> withinSynchronous(budget: Budget, operation: suspend () -> T): T? =
            runSynchronous(budget, drain = false, operation)

        /** Service-owned retries must wait for this worker's actual cleanup before starting again. */
        suspend fun <T : Any> withinSynchronousAndDrain(timeoutMs: Long, operation: suspend () -> T): T? =
            runSynchronous(start(timeoutMs), drain = true, operation)

        suspend fun <T : Any> withinSynchronousAndDrain(budget: Budget, operation: suspend () -> T): T? =
            runSynchronous(budget, drain = true, operation)

        private suspend fun <T : Any> runSynchronous(
            budget: Budget,
            drain: Boolean,
            operation: suspend () -> T,
        ): T? {
            // A nested JDBC operation already has an owner/slot; do not deadlock on admission.
            if (synchronousOwner.get() == true) return withinOwned(budget, operation)
            val caller = currentCoroutineContext()
            var worker: Deferred<T?>? = null
            return try {
                val deadlineNanos = budget.deadline(caller.job)
                val task = operations.async {
                    withTimeoutOrNull(budget.remainingMillis(currentCoroutineContext().job)) {
                        operationSlots.withPermit {
                            withContext(synchronousOwner.asContextElement(true)) {
                                withinOwned(budget, operation)
                            }
                        }
                    }
                }
                worker = task
                withTimeoutOrNull(millisUntil(deadlineNanos, budget.nanoTime)) { task.await() }
            } catch (cause: Exception) {
                if (!isTimeout(cause)) throw cause
                caller.ensureActive()
                null
            } finally {
                worker?.let { task ->
                    // async is independent of the caller Job. Cancellation does not complete it
                    // until its body, all children and synchronous finally/close have exited.
                    task.cancel()
                    if (drain) withContext(NonCancellable) { task.join() }
                }
            }
        }

        fun deadlineExceeded(): RuntimeException = Expired()

        /** Recognize PostgreSQL query cancellation without exposing SQL or driver messages. */
        fun isTimeout(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.any {
            it is Expired || (it is SQLException && it.sqlState == "57014")
        }

        suspend fun <T : Any> within(timeoutMs: Long, prepare: suspend () -> T): T? =
            withinOwned(start(timeoutMs), prepare)

        suspend fun <T : Any> within(budget: Budget, prepare: suspend () -> T): T? =
            withinOwned(budget, prepare)

        private suspend fun <T : Any> withinOwned(budget: Budget, prepare: suspend () -> T): T? {
            val caller = currentCoroutineContext()
            return try {
                val deadlineNanos = budget.deadline(caller.job)
                withTimeoutOrNull(millisUntil(deadlineNanos, budget.nanoTime)) {
                    val deadline = DiagnosticPreparationDeadline(deadlineNanos, currentCoroutineContext().job, budget.nanoTime)
                    withContext(active.asContextElement(deadline)) {
                        prepare().also { deadline.remainingMillis() }
                    }
                }
            } catch (cause: Exception) {
                if (!isTimeout(cause)) throw cause
                caller.ensureActive()
                null
            }
        }
    }
}
