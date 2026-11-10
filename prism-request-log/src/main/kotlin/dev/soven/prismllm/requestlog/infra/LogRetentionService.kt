package dev.soven.prismllm.requestlog.infra

import dev.soven.prismllm.requestlog.domain.CleanupResult
import dev.soven.prismllm.requestlog.domain.RetentionPolicy
import dev.soven.prismllm.requestlog.domain.retentionCutoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** 请求日志保留清理。按批次删除，避免一次大事务影响数据面。 */
class LogRetentionService(
    private val deleteBatch: suspend (Long, Int) -> CleanupResult,
    val policy: RetentionPolicy,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(LogRetentionService::class.java)
    private val running = AtomicBoolean(false)
    private val cleanupMutex = Mutex()
    private val cleanupEnabled = AtomicBoolean(policy.cleanupEnabled)

    val scheduledCleanupRunning: Boolean
        get() = running.get()

    fun currentPolicy(): RetentionPolicy = policy.copy(cleanupEnabled = cleanupEnabled.get())

    fun setCleanupEnabled(enabled: Boolean) {
        cleanupEnabled.set(enabled)
    }

    suspend fun cleanExpired(): CleanupResult = cleanupMutex.withLock {
        val cutoff = retentionCutoff(now(), policy.retentionDays)
        var total = 0L
        try {
            while (true) {
                val result = deleteBatch(cutoff, policy.batchSize)
                total += result.deleted
                if (result.deleted < policy.batchSize) break
            }
            logger.info("request log cleanup completed, deleted={}, cutoff={}", total, cutoff)
            CleanupResult(total, cutoff)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            logger.error("request log cleanup failed, totalDeleted={}, cutoff={}", total, cutoff, cause)
            throw cause
        }
    }

    fun start(scope: CoroutineScope): Job? {
        if (!cleanupEnabled.get() || !running.compareAndSet(false, true)) return null
        logger.info("request log scheduled cleanup started")
        return scope.launch {
            while (isActive) {
                try {
                    cleanExpired()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: Throwable) {
                    // Retry on the next interval; failures are logged by cleanExpired.
                }
                delay(policy.cleanupIntervalMs)
            }
        }.also { job ->
            // Completion handlers also run when cancellation prevents the body from starting.
            job.invokeOnCompletion {
                running.set(false)
                logger.info("request log scheduled cleanup stopped")
            }
        }
    }
}
