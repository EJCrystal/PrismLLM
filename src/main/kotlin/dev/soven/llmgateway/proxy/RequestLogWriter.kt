package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.repo.ModelPriceRepository
import dev.soven.llmgateway.repo.RequestLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * 请求日志的异步写入队列。队列满时丢弃并记录告警，不阻塞数据面。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class RequestLogWriter(
    private val repository: RequestLogRepository,
    private val priceRepository: ModelPriceRepository,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(RequestLogWriter::class.java)
    private val queue = Channel<RequestLogRecord>(capacity = 1024)
    private val consumer: Job = scope.launch {
        for (record in queue) {
            runCatching { repository.insert(withCost(record)) }
                .onFailure { logger.error("request log write failed, requestId={}", record.requestId, it) }
        }
    }

    private suspend fun withCost(record: RequestLogRecord): RequestLogRecord {
        val channelId = record.channelId ?: return record
        val upstreamModel = record.upstreamModel ?: return record
        if (!record.usageKnown) return record
        val price = runCatching { priceRepository.find(channelId, upstreamModel) }
            .onFailure { logger.warn("model price lookup failed, requestId={}", record.requestId, it) }
            .getOrNull() ?: return record
        val cost = BillingCalculator.calculate(record, price) ?: return record
        return record.copy(
            costAmount = cost.amount,
            costCurrency = cost.currency,
            inputPricePerMillion = cost.inputPricePerMillion,
            outputPricePerMillion = cost.outputPricePerMillion,
            cachedInputPricePerMillion = cost.cachedInputPricePerMillion,
        )
    }

    fun submit(record: RequestLogRecord) {
        if (!queue.trySend(record).isSuccess) {
            logger.warn("request log queue full, dropped requestId={}", record.requestId)
        }
    }

    suspend fun closeAndDrain(timeoutMs: Long) {
        queue.close()
        withTimeoutOrNull(timeoutMs) { consumer.join() }
    }
}
