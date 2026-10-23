package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.repo.ModelPriceRepository
import dev.soven.llmgateway.repo.RequestLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * 请求日志的异步写入队列。队列满时丢弃并记录告警，不阻塞数据面。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class RequestLogWriter private constructor(
    private val insertRecord: suspend (RequestLogRecord) -> Unit,
    private val findPrice: suspend (String, String) -> ModelPriceRecord?,
    scope: CoroutineScope,
) {
    constructor(
        repository: RequestLogRepository,
        priceRepository: ModelPriceRepository,
        scope: CoroutineScope,
    ) : this(repository::insert, priceRepository::find, scope)

    internal constructor(
        scope: CoroutineScope,
        insertRecord: suspend (RequestLogRecord) -> Unit,
        findPrice: suspend (String, String) -> ModelPriceRecord?,
    ) : this(insertRecord, findPrice, scope)

    private val logger = LoggerFactory.getLogger(RequestLogWriter::class.java)
    private val queue = Channel<RequestLogRecord>(capacity = 1024)
    private val consumer: Job = scope.launch {
        for (record in queue) {
            try {
                insertRecord(withCost(record))
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.error("request log write failed, requestId={}", record.requestId, cause)
            }
        }
    }

    private suspend fun withCost(record: RequestLogRecord): RequestLogRecord {
        val channelId = record.channelId ?: return record
        val upstreamModel = record.upstreamModel ?: return record
        if (!record.usageKnown) return record
        val price = try {
            findPrice(channelId, upstreamModel)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            logger.warn("model price lookup failed, requestId={}", record.requestId, cause)
            null
        } ?: return record
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
