package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.ModelPriceRecord
import dev.soven.llmgateway.domain.RequestLogRecord
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 单次请求费用。金额按每百万 token 单价计算并保留 8 位小数。
 *
 * @author 文杰
 * @since 2026-10-22
 */
data class CalculatedCost(
    val amount: BigDecimal,
    val currency: String,
    val inputPricePerMillion: BigDecimal,
    val outputPricePerMillion: BigDecimal,
    val cachedInputPricePerMillion: BigDecimal,
)

/**
 * 计费规则：缓存 token 单独计价，未配置缓存单价时按输入单价；推理 token 已包含在输出 token 中，不重复计费。
 *
 * @author 文杰
 * @since 2026-10-22
 */
object BillingCalculator {
    private val MILLION = BigDecimal("1000000")

    fun calculate(record: RequestLogRecord, price: ModelPriceRecord): CalculatedCost? {
        if (!record.usageKnown) return null
        val input = record.inputTokens ?: return null
        val output = record.outputTokens ?: return null
        val cached = (record.cachedTokens ?: 0L).coerceIn(0L, input)
        val cachedPrice = price.cachedInputPricePerMillion ?: price.inputPricePerMillion
        val amount = BigDecimal.valueOf(input - cached)
            .multiply(price.inputPricePerMillion)
            .add(BigDecimal.valueOf(cached).multiply(cachedPrice))
            .add(BigDecimal.valueOf(output).multiply(price.outputPricePerMillion))
            .divide(MILLION, 8, RoundingMode.HALF_UP)
        return CalculatedCost(
            amount = amount,
            currency = price.currency,
            inputPricePerMillion = price.inputPricePerMillion,
            outputPricePerMillion = price.outputPricePerMillion,
            cachedInputPricePerMillion = cachedPrice,
        )
    }
}
