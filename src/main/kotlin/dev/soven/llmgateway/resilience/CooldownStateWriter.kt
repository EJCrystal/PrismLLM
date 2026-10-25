package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.repo.CooldownStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * 冷却状态的异步写透队列。队列满时丢弃并记录告警，不阻塞数据面；写失败不影响内存态。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class CooldownStateWriter(
    repository: CooldownStateRepository,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(CooldownStateWriter::class.java)
    private val queue = Channel<CooldownChange>(capacity = 256)
    private val consumer: Job = scope.launch {
        for (change in queue) {
            try {
                when (change) {
                    is CooldownChange.Updated -> repository.upsert(
                        CooldownStateSnapshot(change.stateKey, change.consecutiveFailures, change.cooldownUntil),
                    )
                    is CooldownChange.Removed -> repository.delete(change.stateKey)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("cooldown state write failed, key={}", change.stateKey, cause)
            }
        }
    }

    fun submit(change: CooldownChange) {
        if (!queue.trySend(change).isSuccess) {
            logger.warn("cooldown state queue full, dropped key={}", change.stateKey)
        }
    }

    suspend fun closeAndDrain(timeoutMs: Long) {
        queue.close()
        withTimeoutOrNull(timeoutMs) { consumer.join() }
    }
}
