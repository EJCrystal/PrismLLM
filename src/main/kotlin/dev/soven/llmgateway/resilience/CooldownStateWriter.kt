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
import kotlin.coroutines.cancellation.CancellationException

/**
 * 冷却状态的异步写透队列。按状态键合并待写事件，内存随状态键数量有界；
 * 同一键保留最新状态，写失败不影响内存态。
 * 管理面手动解除通过 [deleteNow] 同步删除并拿到本次操作的结果。
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
    /** 正在写数据库的状态键，供手动删除只等待本次涉及的目标键。 */
    private val writing = ConcurrentHashMap.newKeySet<String>()
    private val consumer: Job = scope.launch {
        for (ignored in signal) drain()
        // close 后把信号关闭前积压的事件处理完。
        drain()
    }

    private suspend fun drain() {
        while (true) {
            val entry = pending.entries.firstOrNull() ?: return
            if (!pending.remove(entry.key, entry.value)) continue
            writing.add(entry.key)
            try {
                when (val change = entry.value) {
                    is CooldownChange.Updated -> store.upsert(
                        CooldownStateSnapshot(change.stateKey, change.consecutiveFailures, change.cooldownUntil, change.reason),
                    )
                    is CooldownChange.Removed -> store.delete(change.stateKey)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("cooldown state write failed, key={}", entry.key, cause)
            } finally {
                writing.remove(entry.key)
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
     * 同步删除本次手动解除涉及的状态键，并返回本次删除是否全部成功。
     * 只等待这些键上正在进行的写入；其他键的慢写入不影响结果。
     * 若某个键在清除后产生了更新的冷却事件，则保留该事件，交由写入器持久化。
     */
    suspend fun deleteNow(stateKeys: Set<String>, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            var allSucceeded = true
            for (key in stateKeys) {
                while (writing.contains(key)) delay(5)
                val change = pending[key]
                if (change != null && change !is CooldownChange.Removed) {
                    // 清除后同一键又进入冷却：新的 Updated 才是应持久化的最终状态。
                    continue
                }
                if (change != null) pending.remove(key, change)
                try {
                    store.delete(key)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    allSucceeded = false
                    logger.error("cooldown state delete failed, key={}", key, cause)
                }
            }
            allSucceeded
        } ?: false

    suspend fun closeAndDrain(timeoutMs: Long) {
        signal.close()
        withTimeoutOrNull(timeoutMs) { consumer.join() }
    }
}
