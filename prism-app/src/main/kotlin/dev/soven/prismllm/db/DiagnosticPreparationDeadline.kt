package dev.soven.prismllm.db

import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
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
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/** Active wall-clock budget shared by both phases of one diagnostic preparation. */
internal class DiagnosticPreparationDeadline private constructor(
    private val deadlineNanos: Long,
    private val job: Job,
    private val nanoTime: () -> Long,
) : StatementInterceptor {
    /**
     * Starts active. Pause only after the initial account read, outside [within], then resume after
     * plugin planning and runtime admission. Nested scopes share one absolute active deadline;
     * entering or leaving a scope never debits the budget a second time.
     */
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
        // The JDBC-standard timeout rounds to seconds, which would overrun the remaining budget.
        val statement = (preparedStatement as JdbcPreparedStatementImpl).statement.unwrap(PgStatement::class.java)
        statement.setQueryTimeoutMs(remainingMillis())
    }

    override fun beforeCommit(transaction: Transaction) {
        remainingMillis()
    }

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
            val callerOwns: Boolean
            synchronized(this) {
                callerOwns = state == State.ACQUIRING
                if (callerOwns) {
                    value = connection
                    state = State.READY
                    (this as java.lang.Object).notifyAll()
                }
            }
            if (!callerOwns) runCatching { connection.close() }
        }

        fun fail(cause: Throwable) {
            synchronized(this) {
                if (state == State.ACQUIRING) {
                    failure = cause
                    state = State.FAILED
                    (this as java.lang.Object).notifyAll()
                }
            }
        }

        fun await(deadline: DiagnosticPreparationDeadline): T {
            synchronized(this) {
                while (state == State.WAITING || state == State.ACQUIRING) {
                    val remaining = TimeUnit.MILLISECONDS.toNanos(deadline.remainingMillis())
                    TimeUnit.NANOSECONDS.timedWait(this, minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)))
                }
                return when (state) {
                    State.READY -> {
                        deadline.remainingMillis()
                        value!!.also { state = State.TRANSFERRED }
                    }
                    State.FAILED -> throw requireNotNull(failure)
                    else -> throw Expired()
                }
            }
        }

        fun abandon() {
            val late: T?
            synchronized(this) {
                late = if (state == State.READY) value else null
                if (state == State.WAITING || state == State.ACQUIRING || state == State.READY) state = State.ABANDONED
                value = null
                (this as java.lang.Object).notifyAll()
            }
            late?.let { runCatching { it.close() } }
        }
    }

    companion object {
        private val active = ThreadLocal<DiagnosticPreparationDeadline>()

        private fun millisUntil(deadlineNanos: Long, nanoTime: () -> Long): Long {
            val remaining = deadlineNanos - nanoTime()
            if (remaining <= 0L) throw Expired()
            return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
        }

        fun start(timeoutMs: Long, nanoTime: () -> Long = System::nanoTime): Budget {
            require(timeoutMs > 0) { "diagnostic preparation timeout must be positive" }
            return Budget(timeoutMs, nanoTime)
        }

        /** A regular connection remains on the caller thread; only diagnostic acquisitions are isolated. */
        fun <T : AutoCloseable> acquireConnection(
            executor: java.util.concurrent.ThreadPoolExecutor,
            acquire: () -> T,
        ): T {
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

        /** A bounded operation can surface this marker when another coroutine owns its execution. */
        fun deadlineExceeded(): RuntimeException = Expired()

        /** Recognize PostgreSQL query cancellation without exposing SQL or driver messages. */
        fun isTimeout(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.any {
            it is Expired || (it is SQLException && it.sqlState == "57014")
        }

        suspend fun <T : Any> within(timeoutMs: Long, prepare: suspend () -> T): T? =
            within(start(timeoutMs), prepare)

        suspend fun <T : Any> within(budget: Budget, prepare: suspend () -> T): T? {
            val caller = currentCoroutineContext()
            return try {
                val deadlineNanos = budget.deadline(caller.job)
                withTimeoutOrNull(millisUntil(deadlineNanos, budget.nanoTime)) {
                    val deadline = DiagnosticPreparationDeadline(deadlineNanos, coroutineContext.job, budget.nanoTime)
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
