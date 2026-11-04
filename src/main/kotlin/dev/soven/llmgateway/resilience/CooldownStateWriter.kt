package dev.soven.llmgateway.resilience

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
import kotlin.coroutines.cancellation.CancellationException

/**
 * 冷却状态的异步写透队列。按状态键合并待写事件，内存随状态键数量有界；
 * 同一键保留最新状态，写失败不影响内存态。
 * 管理面手动解除可通过 [awaitRemovals] 确认本次清除涉及的删除操作全部成功。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class CooldownStateWriter(
    private val store: CooldownStateStore,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(CooldownStateWriter::class.java)

    // 同一 stateKey 只保留最新事件；高频失败不会让待写事件无限增长。
    private val pending = ConcurrentHashMap<String, CooldownChange>()
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val active = AtomicInteger(0)
    /** 每个状态键最近一次数据库写入是否成功；只在写入完成时更新。 */
    private val writeResults = ConcurrentHashMap<String, Boolean>()
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
                    is CooldownChange.Updated -> store.upsert(
                        CooldownStateSnapshot(change.stateKey, change.consecutiveFailures, change.cooldownUntil, change.reason),
                    )
                    is CooldownChange.Removed -> store.delete(change.stateKey)
                }
                writeResults[entry.key] = true
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                writeResults[entry.key] = false
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

    /**
     * 等待本次手动解除提交的 Removed 事件完成，并确认对应状态键的删除全部成功。
     * 其他状态键的写入失败不影响本次结果。
     */
    suspend fun awaitRemovals(stateKeys: Set<String>, timeoutMs: Long): Boolean {
        val results = withTimeoutOrNull(timeoutMs) {
            while (pending.keys.any { it in stateKeys } || active.get() > 0) delay(10)
            stateKeys.associateWith { writeResults[it] }
        } ?: return false
        return results.values.all { it == true }
    }

    suspend fun closeAndDrain(timeoutMs: Long) {
        signal.close()
        withTimeoutOrNull(timeoutMs) {
            while (pending.isNotEmpty() || active.get() > 0) delay(10)
            consumer.join()
        }
    }
}
