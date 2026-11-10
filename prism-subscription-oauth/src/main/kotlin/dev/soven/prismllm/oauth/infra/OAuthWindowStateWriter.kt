package dev.soven.prismllm.oauth.infra

import dev.soven.prismllm.oauth.domain.OAuthWindowSnapshot
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
 * 账号级调度状态的异步写透队列。同一账号只保留最新快照，
 * 内存随账号数量有界；写失败不影响内存态。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthWindowStateWriter(
    private val persist: suspend (OAuthWindowSnapshot) -> Unit,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(OAuthWindowStateWriter::class.java)
    private val pending = ConcurrentHashMap<String, OAuthWindowSnapshot>()
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val consumer: Job = scope.launch {
        for (ignored in signal) drain()
        // close 后把关闭信号前积压的事件处理完。
        drain()
    }

    fun submit(snapshot: OAuthWindowSnapshot) {
        if (signal.isClosedForSend) {
            logger.warn("oauth window state writer closed, dropped accountId={}", snapshot.accountId)
            return
        }
        pending[snapshot.accountId] = snapshot
        signal.trySend(Unit)
    }

    private suspend fun drain() {
        var wrote = false
        while (true) {
            val entry = pending.entries.firstOrNull() ?: break
            if (!pending.remove(entry.key, entry.value)) continue
            try {
                persist(entry.value)
                wrote = true
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("oauth window state write failed, accountId={}", entry.key, cause)
            }
        }
        // 高 QPS 下窗口命中持续变化；限制落库频率，内存态仍然是权威来源。
        if (wrote) delay(WRITE_INTERVAL_MS)
    }

    suspend fun closeAndDrain(timeoutMs: Long) {
        signal.close()
        withTimeoutOrNull(timeoutMs) { consumer.join() }
    }

    private companion object {
        const val WRITE_INTERVAL_MS = 200L
    }
}
