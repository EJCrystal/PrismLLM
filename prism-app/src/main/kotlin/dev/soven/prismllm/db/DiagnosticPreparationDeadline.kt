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

/** Propagates the OAuth probe's deadline to blocking JDBC statements, not to other transactions. */
internal class DiagnosticPreparationDeadline private constructor(timeoutMs: Long, private val job: Job) : StatementInterceptor {
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    private fun remainingMillis(): Long {
        job.ensureActive()
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0L) throw Expired()
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
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

    companion object {
        private val active = ThreadLocal<DiagnosticPreparationDeadline>()

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

        /** Recognize PostgreSQL query cancellation without exposing SQL or driver messages. */
        fun isTimeout(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.any {
            it is Expired || (it is SQLException && it.sqlState == "57014")
        }

        suspend fun <T : Any> within(timeoutMs: Long, prepare: suspend () -> T): T? = try {
            withTimeoutOrNull(timeoutMs) {
                val deadline = DiagnosticPreparationDeadline(timeoutMs, coroutineContext.job)
                withContext(active.asContextElement(deadline)) {
                    prepare().also { deadline.remainingMillis() }
                }
            }
        } catch (cause: Exception) {
            if (!isTimeout(cause)) throw cause
            currentCoroutineContext().ensureActive()
            null
        }
    }
}
