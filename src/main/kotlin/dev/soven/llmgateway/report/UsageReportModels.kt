package dev.soven.llmgateway.report

import java.math.BigDecimal

data class UsageReportQuery(
    val from: Long,
    val to: Long,
    val clientKeyId: String? = null,
)

data class UsageTotal(
    val requests: Long = 0,
    val errors: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val costs: Map<String, BigDecimal> = emptyMap(),
)

data class UsageGroup(
    val id: String,
    val name: String? = null,
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val costs: Map<String, BigDecimal>,
)

data class UsageReport(
    val from: Long,
    val to: Long,
    val total: UsageTotal,
    val byClientKey: List<UsageGroup>,
)
