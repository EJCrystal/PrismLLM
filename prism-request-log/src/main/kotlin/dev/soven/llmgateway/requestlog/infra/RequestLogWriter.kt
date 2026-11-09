package dev.soven.llmgateway.requestlog.infra

import dev.soven.llmgateway.pricing.domain.ResolvedModelPrice
import dev.soven.llmgateway.requestlog.domain.BillingCalculator
import dev.soven.llmgateway.requestlog.domain.RequestLogRecord
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
class RequestLogWriter(
    private val insertRecord: suspend (RequestLogRecord) -> Unit,
    private val resolvePrice: suspend (RequestLogRecord) -> ResolvedModelPrice?,
    scope: CoroutineScope,
) {

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
        val price = try {
            resolvePrice(record)
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
            cachedInputWritePricePerMillion = cost.cachedInputWritePricePerMillion,
            pricingMode = cost.pricingMode,
            perRequestPrice = cost.perRequestPrice,
            priceTiers = cost.priceTiers,
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
