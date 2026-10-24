package dev.soven.llmgateway.quota

/**
 * 团队级请求速率与并发策略。null 表示该维度不限制，请求速率 0 表示禁止新请求。
 */
data class TeamLimitPolicy(
    val team: String,
    val requestsPerMinute: Int? = null,
    val maxConcurrentRequests: Int? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * 当前 UTC 分钟内的团队请求数与活动并发请求数。只保存在单实例内存中。
 */
data class TeamLimitUsage(
    val currentMinuteRequests: Int = 0,
    val activeConcurrentRequests: Int = 0,
)
