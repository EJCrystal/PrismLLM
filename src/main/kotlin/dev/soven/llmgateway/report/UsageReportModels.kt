package dev.soven.llmgateway.report

import java.math.BigDecimal

data class UsageReportQuery(
    val from: Long,
    val to: Long,
    val clientKeyId: String? = null,
    val channelId: String? = null,
    val model: String? = null,
    val protocol: String? = null,
    val timezoneOffsetMinutes: Int = 0,
)

data class UsageTotal(
    val requests: Long = 0,
    val errors: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cachedWriteTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val totalTokens: Long = 0,
    val activeClientKeys: Long = 0,
    val failovers: Long = 0,
    val avgFirstTokenMs: Long? = null,
    val avgTotalMs: Long? = null,
    val costs: Map<String, BigDecimal> = emptyMap(),
)

data class UsageGroup(
    val id: String,
    val name: String? = null,
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0,
    val cachedWriteTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val totalTokens: Long,
    val failovers: Long = 0,
    val avgFirstTokenMs: Long? = null,
    val avgTotalMs: Long? = null,
    val costs: Map<String, BigDecimal>,
)

data class UsageTrendPoint(
    val bucketStart: Long,
    val requests: Long,
    val errors: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val cachedWriteTokens: Long,
    val reasoningTokens: Long,
    val totalTokens: Long,
    val costs: Map<String, BigDecimal>,
)

data class UsageReport(
    val from: Long,
    val to: Long,
    val granularity: String,
    val total: UsageTotal,
    val trend: List<UsageTrendPoint>,
    val byClientKey: List<UsageGroup>,
    val byChannel: List<UsageGroup>,
    val byModel: List<UsageGroup>,
    val byChannelKey: List<UsageGroup>,
    val byProtocol: List<UsageGroup>,
    val byErrorCode: List<UsageGroup>,
)
