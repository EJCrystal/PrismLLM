package dev.soven.llmgateway.report

import dev.soven.llmgateway.repo.RequestLogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/** 请求日志保留清理。按批次删除，避免一次大事务影响数据面。 */
class LogRetentionService(
    private val deleteBatch: suspend (Long, Int) -> CleanupResult,
    val policy: RetentionPolicy,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        repository: RequestLogRepository,
        policy: RetentionPolicy,
        now: () -> Long = System::currentTimeMillis,
    ) : this(repository::deleteOlderThan, policy, now)

    private val logger = LoggerFactory.getLogger(LogRetentionService::class.java)
    private val running = AtomicBoolean(false)
    private val cleanupMutex = Mutex()

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
        if (!policy.cleanupEnabled || !running.compareAndSet(false, true)) return null
        return scope.launch {
            try {
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
            } finally {
                running.set(false)
            }
        }
    }
}
