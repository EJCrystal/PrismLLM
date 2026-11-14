package dev.soven.prismllm.proxy

import dev.soven.prismllm.pricing.domain.ResolvedModelPrice
import dev.soven.prismllm.quota.domain.quotaTokensFromUsage
import dev.soven.prismllm.quota.infra.RequestLimitLease
import dev.soven.prismllm.requestlog.domain.BillingCalculator
import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/** 未计价的请求结束记录只计算一次 USD 费用；结算故障不改变响应，也不阻止日志与指标收尾。 */
internal class RequestFinalizer(
    private val resolvePrice: suspend (RequestLogRecord) -> ResolvedModelPrice?,
    private val settleUsage: suspend (RequestLimitLease, Long, BigDecimal) -> Unit,
    private val recordMetrics: (RequestLogRecord) -> Unit,
    private val submitRecord: (RequestLogRecord) -> Unit,
    private val onFailure: (RequestLogRecord, String, Exception) -> Unit,
) {
    suspend fun finish(
        record: RequestLogRecord,
        lease: RequestLimitLease?,
        elapsedMs: () -> Long = { record.totalMs },
    ) = withContext(NonCancellable) {
        val priced = try {
            withCost(record)
        } catch (cause: Exception) {
            reportFailure(record, "pricing", cause)
            record
        }
        if (lease != null) {
            try {
                // settleUsage 的 finally 负责唯一一次并发释放；失败时不能重复 release。
                settleUsage(
                    lease,
                    quotaTokensFromUsage(priced.totalTokens, priced.inputTokens, priced.outputTokens),
                    priced.costAmount ?: BigDecimal.ZERO,
                )
            } catch (cause: Exception) {
                reportFailure(priced, "quota", cause)
            }
        }
        val completed = priced.copy(totalMs = elapsedMs())
        try {
            recordMetrics(completed)
        } catch (cause: Exception) {
            reportFailure(completed, "metrics", cause)
        }
        try {
            submitRecord(completed)
        } catch (cause: Exception) {
            reportFailure(completed, "request_log", cause)
        }
    }

    private fun reportFailure(record: RequestLogRecord, stage: String, cause: Exception) {
        runCatching { onFailure(record, stage, cause) }
    }

    private suspend fun withCost(record: RequestLogRecord): RequestLogRecord {
        val price = resolvePrice(record) ?: return record
        // BillingCalculator 的统一计价入口拒绝非 USD 价格；新请求保持费用未知，不把原币金额当美元结算。
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
}
