package dev.soven.llmgateway.quota.domain

import java.math.BigDecimal

/**
 * 调用方密钥的可选每日额度、每分钟请求数和并发上限。null 表示该维度不设限。
 */
data class ClientKeyQuotaLimits(
    val dailyTokens: Long? = null,
    val dailyCosts: Map<String, BigDecimal> = emptyMap(),
    val maxConcurrentRequests: Int? = null,
    val requestsPerMinute: Int? = null,
) {
    fun dailyCostLimit(currency: String): BigDecimal? = dailyCosts[currency.uppercase()]
}

/**
 * 按自然日累计的额度用量。费用按计费币种分别累计，不做隐含汇率换算。
 */
data class ClientKeyQuotaUsage(
    val dailyTokens: Long = 0,
    val dailyCosts: Map<String, BigDecimal> = emptyMap(),
    val concurrentRequests: Int = 0,
    val currentMinuteRequests: Int = 0,
) {
    fun dailyCost(currency: String): BigDecimal =
        dailyCosts[currency.uppercase()] ?: BigDecimal.ZERO
}

enum class QuotaRejectionReason(val code: String) {
    DAILY_TOKENS("daily_token_quota_exceeded"),
    DAILY_COST("daily_cost_quota_exceeded"),
    CONCURRENCY("concurrency_limit_exceeded"),
}

data class QuotaCheckResult(
    val allowed: Boolean,
    val reason: QuotaRejectionReason? = null,
    val message: String = "",
)

/**
 * 在请求进入上游前检查已用额度；本轮请求的实际费用在完成后独立结算。
 */
fun checkClientKeyQuota(
    limits: ClientKeyQuotaLimits,
    usage: ClientKeyQuotaUsage,
): QuotaCheckResult {
    limits.dailyTokens?.let { limit ->
        if (usage.dailyTokens >= limit) {
            return QuotaCheckResult(false, QuotaRejectionReason.DAILY_TOKENS, "今日 token 额度已用完")
        }
    }
    limits.maxConcurrentRequests?.let { limit ->
        if (usage.concurrentRequests >= limit) {
            return QuotaCheckResult(false, QuotaRejectionReason.CONCURRENCY, "并发请求数已达上限")
        }
    }
    limits.dailyCosts.keys.sorted().forEach { currency ->
        val limit = limits.dailyCostLimit(currency) ?: return@forEach
        if (limit.signum() >= 0 && usage.dailyCost(currency) >= limit) {
            return QuotaCheckResult(
                false,
                QuotaRejectionReason.DAILY_COST,
                "今日 $currency 费用额度已用完",
            )
        }
    }
    return QuotaCheckResult(true)
}

/**
 * 每日价格上限校验。金额为非负有限小数，最多保留 8 位。
 */
fun parseDailyCostLimit(value: String?): BigDecimal? {
    if (value == null || value.isBlank()) return null
    val amount = value.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("每日费用上限必须是数字")
    if (amount.signum() < 0 || amount.scale() > 8) throw IllegalArgumentException("每日费用上限必须是非负数字，最多 8 位小数")
    return amount.stripTrailingZeros()
}

/**
 * 自然日 key 使用 UTC，避免跨时区部署时出现额度重置不一致。
 */
fun dailyQuotaPeriod(epochMillis: Long): Long = Math.floorDiv(epochMillis, 86_400_000L)

/**
 * 在缺少用量或计费结果时，至少累计上游已返回的 token，避免费用额度无法约束这种请求。
 */
fun quotaTokensFromUsage(total: Long?, input: Long?, output: Long?): Long =
    (total ?: ((input ?: 0L) + (output ?: 0L))).coerceAtLeast(0L)
