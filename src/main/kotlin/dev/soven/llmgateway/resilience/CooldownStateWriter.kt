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
    /** 保护「认领待写事件」与「手动删除检查」两步的原子性；数据库调用不在锁内。 */
    private val lock = Any()
    private val consumer: Job = scope.launch {
        for (ignored in signal) drain()
        // close 后把信号关闭前积压的事件处理完。
        drain()
    }

    private enum class DeleteDecision { WAIT, SKIP, DELETE }

    private suspend fun drain() {
        while (true) {
            val (key, change) = claimNext() ?: return
            try {
                when (change) {
                    is CooldownChange.Updated -> store.upsert(
                        CooldownStateSnapshot(change.stateKey, change.consecutiveFailures, change.cooldownUntil, change.reason),
                    )
                    is CooldownChange.Removed -> store.delete(change.stateKey)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("cooldown state write failed, key={}", key, cause)
            } finally {
                synchronized(lock) { writing.remove(key) }
            }
        }
    }

    /** 在锁内把事件从待写队列取出并登记为写入中，避免与手动删除检查交错。 */
    private fun claimNext(): Pair<String, CooldownChange>? = synchronized(lock) {
        var claimed: Pair<String, CooldownChange>? = null
        while (claimed == null) {
            val entry = pending.entries.firstOrNull() ?: break
            if (!pending.remove(entry.key, entry.value)) continue
            writing.add(entry.key)
            claimed = entry.key to entry.value
        }
        claimed
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
                val decision = awaitDeleteDecision(key)
                if (decision == DeleteDecision.SKIP) continue
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

    private suspend fun awaitDeleteDecision(key: String): DeleteDecision {
        while (true) {
            val decision = synchronized(lock) {
                val change = pending[key]
                when {
                    writing.contains(key) -> DeleteDecision.WAIT
                    change != null && change !is CooldownChange.Removed -> DeleteDecision.SKIP
                    else -> {
                        if (change != null) pending.remove(key, change)
                        DeleteDecision.DELETE
                    }
                }
            }
            if (decision != DeleteDecision.WAIT) return decision
            delay(5)
        }
    }

    suspend fun closeAndDrain(timeoutMs: Long) {
        signal.close()
        withTimeoutOrNull(timeoutMs) { consumer.join() }
    }
}
