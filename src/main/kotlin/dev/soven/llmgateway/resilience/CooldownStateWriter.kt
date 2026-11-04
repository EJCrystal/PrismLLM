package dev.soven.llmgateway.resilience

import dev.soven.llmgateway.repo.CooldownStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * 冷却状态的异步写透队列。按状态键合并待写事件，内存随状态键数量有界；
 * 同一键保留最新状态，写失败不影响内存态。
 * 管理面手动解除可通过 [awaitIdle] 等待已提交事件处理完成后再确认成功。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class CooldownStateWriter(
    private val repository: CooldownStateRepository,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(CooldownStateWriter::class.java)

    // 同一 stateKey 只保留最新事件；高频失败不会让待写事件无限增长。
    private val pending = ConcurrentHashMap<String, CooldownChange>()
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val active = AtomicInteger(0)
    private val failures = AtomicLong(0)
    private val consumer: Job = scope.launch {
        for (ignored in signal) drain()
        // close 后把信号关闭前积压的事件处理完。
        drain()
    }

    private suspend fun drain() {
        while (true) {
            val entry = pending.entries.firstOrNull() ?: return
            active.incrementAndGet()
            if (!pending.remove(entry.key, entry.value)) {
                active.decrementAndGet()
                continue
            }
            try {
                when (val change = entry.value) {
                    is CooldownChange.Updated -> repository.upsert(
                        CooldownStateSnapshot(change.stateKey, change.consecutiveFailures, change.cooldownUntil, change.reason),
                    )
                    is CooldownChange.Removed -> repository.delete(change.stateKey)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                failures.incrementAndGet()
                logger.error("cooldown state write failed, key={}", entry.key, cause)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    fun submit(change: CooldownChange) {
        if (signal.isClosedForSend) {
            logger.warn("cooldown state writer closed, dropped key={}", change.stateKey)
            return
        }
        pending[change.stateKey] = change
        signal.trySend(Unit)
    }

    /** 写透失败次数；管理面据此判断本次解除期间是否有持久化失败。 */
    fun failureCount(): Long = failures.get()

    /** 等待已提交的写透事件处理完，供管理面手动解除在返回成功前确认持久化。 */
    suspend fun awaitIdle(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (pending.isNotEmpty() || active.get() > 0) delay(10)
            true
        } ?: false

    suspend fun closeAndDrain(timeoutMs: Long) {
        signal.close()
        withTimeoutOrNull(timeoutMs) {
            while (pending.isNotEmpty() || active.get() > 0) delay(10)
            consumer.join()
        }
    }
}
