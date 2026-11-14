package dev.soven.prismllm.requestlog.infra

import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * 请求日志的异步写入队列。费用在提交前固定，队列满时丢弃并记录告警，不阻塞数据面。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class RequestLogWriter(
    private val insertRecord: suspend (RequestLogRecord) -> Unit,
    scope: CoroutineScope,
    private val onWriteFailure: () -> Unit = {},
    private val onDropped: () -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(RequestLogWriter::class.java)
    private val queue = Channel<RequestLogRecord>(capacity = 1024)
    private val consumer: Job = scope.launch {
        for (record in queue) {
            try {
                insertRecord(record)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                runCatching { onWriteFailure() }
                logger.error("request log write failed, requestId={}", record.requestId, cause)
            }
        }
    }

    fun submit(record: RequestLogRecord) {
        if (!queue.trySend(record).isSuccess) {
            runCatching { onDropped() }
            logger.warn("request log queue unavailable, dropped requestId={}", record.requestId)
        }
    }

    suspend fun closeAndDrain(timeoutMs: Long) {
        queue.close()
        withTimeoutOrNull(timeoutMs) { consumer.join() }
    }
}
