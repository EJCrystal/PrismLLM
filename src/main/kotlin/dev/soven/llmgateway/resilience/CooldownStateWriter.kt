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
import java.util.concurrent.atomic.AtomicLong
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

    private data class SeqChange(val seq: Long, val change: CooldownChange)

    private data class Claimed(val key: String, val seq: Long, val change: CooldownChange)

    // 同一 stateKey 只保留最新事件；高频失败不会让待写事件无限增长。
    private val pending = ConcurrentHashMap<String, SeqChange>()
    /** 每个键最近一次提交的事件，用于判断清除之后是否又产生了新冷却。 */
    private val submitted = ConcurrentHashMap<String, SeqChange>()
    /** 正在写数据库的键，供手动删除只等待本次涉及的目标键。 */
    private val inFlight = ConcurrentHashMap<String, SeqChange>()
    /** 正在手动删除的键；写透消费者暂不认领这些键的事件，保证删除与后续写入有序。 */
    private val deleting = mutableSetOf<String>()

    private val sequence = AtomicLong(0)
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 保护事件认领、删除检查和删除登记的原子性；数据库调用不在锁内。 */
    private val lock = Any()
    private val consumer: Job = scope.launch {
        for (ignored in signal) drain()
        // close 后把信号关闭前积压的事件处理完。
        drain()
    }

    private enum class DeleteDecision { WAIT, SKIP, DELETE }

    /** 当前已分配的最大事件序号；手动解除在清除前记录它作为分界。 */
    fun currentSeq(): Long = sequence.get()

    private suspend fun drain() {
        while (true) {
            val claimed = claimNext() ?: return
            try {
                when (val change = claimed.change) {
                    is CooldownChange.Updated -> store.upsert(
                        CooldownStateSnapshot(change.stateKey, change.consecutiveFailures, change.cooldownUntil, change.reason),
                    )
                    is CooldownChange.Removed -> store.delete(change.stateKey)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("cooldown state write failed, key={}", claimed.key, cause)
            } finally {
                synchronized(lock) { inFlight.remove(claimed.key) }
            }
        }
    }

    /** 认领下一个可写的待写事件；正在手动删除的键留到删除结束后再写。 */
    private fun claimNext(): Claimed? = synchronized(lock) {
        var claimed: Claimed? = null
        while (claimed == null) {
            val entry = pending.entries.firstOrNull { it.key !in deleting } ?: break
            if (!pending.remove(entry.key, entry.value)) continue
            inFlight[entry.key] = entry.value
            claimed = Claimed(entry.key, entry.value.seq, entry.value.change)
        }
        claimed
    }

    fun submit(change: CooldownChange) {
        if (signal.isClosedForSend) {
            logger.warn("cooldown state writer closed, dropped key={}", change.stateKey)
            return
        }
        val entry = SeqChange(sequence.incrementAndGet(), change)
        submitted[change.stateKey] = entry
        pending[change.stateKey] = entry
        signal.trySend(Unit)
    }

    /**
     * 同步删除本次手动解除涉及的状态键，并返回本次删除是否全部成功。
     * 只等待这些键上正在进行的写入；其他键的慢写入不影响结果。
     * 清除之后同一键又产生的新冷却事件会保留下来，由写入器在删除结束后持久化。
     */
    suspend fun deleteNow(stateKeys: Set<String>, baselineSeq: Long, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            var allSucceeded = true
            for (key in stateKeys) {
                val decision = awaitDeleteDecision(key, baselineSeq)
                if (decision == DeleteDecision.SKIP) continue
                try {
                    store.delete(key)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    allSucceeded = false
                    logger.error("cooldown state delete failed, key={}", key, cause)
                } finally {
                    synchronized(lock) { deleting.remove(key) }
                    // 删除期间到达的新事件现在可以继续写透。
                    signal.trySend(Unit)
                }
            }
            allSucceeded
        } ?: false

    private suspend fun awaitDeleteDecision(key: String, baselineSeq: Long): DeleteDecision {
        while (true) {
            val decision = synchronized(lock) {
                val active = inFlight[key]
                val latest = submitted[key]
                when {
                    active != null -> DeleteDecision.WAIT
                    latest != null && latest.seq > baselineSeq && latest.change !is CooldownChange.Removed ->
                        DeleteDecision.SKIP
                    else -> {
                        pending.remove(key)
                        deleting.add(key)
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
