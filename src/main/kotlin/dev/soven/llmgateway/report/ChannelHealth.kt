package dev.soven.llmgateway.report

import dev.soven.llmgateway.domain.UpstreamAttemptRecord
import kotlinx.serialization.Serializable

enum class AttemptOutcome { SUCCESS, FAILURE, EXCLUDED }

/** Cancelled clients and invalid request parameters are not provider outages. */
fun attemptOutcome(attempt: UpstreamAttemptRecord): AttemptOutcome {
    if (attempt.errorCode in setOf("client_cancelled", "client_disconnect", "protocol_conversion_failed", "protocol_conversion_unsupported")) {
        return AttemptOutcome.EXCLUDED
    }
    val status = attempt.upstreamErrorStatusCode ?: attempt.statusCode
    if (status == 400 || status == 422) return AttemptOutcome.EXCLUDED
    return if (status in 200..299 && attempt.errorCode == null && attempt.upstreamErrorCode == null) {
        AttemptOutcome.SUCCESS
    } else AttemptOutcome.FAILURE
}

val healthWindows = linkedMapOf("24h" to 86_400_000L, "7d" to 7 * 86_400_000L, "15d" to 15 * 86_400_000L, "30d" to 30 * 86_400_000L)

@Serializable
data class HealthMetrics(
    val attempts: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val excluded: Long = 0,
    val successRate: Double? = null,
    val firstTokenSamples: Long = 0,
    val p50FirstTokenMs: Double? = null,
    val p95FirstTokenMs: Double? = null,
    val p50TotalMs: Double? = null,
    val p95TotalMs: Double? = null,
    val outputTokensPerSecond: Double? = null,
    val throughputSamples: Long = 0,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastObservedAt: Long? = null,
)

@Serializable
data class HealthRequests(
    val requests: Long = 0,
    val successes: Long = 0,
    val successRate: Double? = null,
    val failoverRequests: Long = 0,
    val failoverRate: Double? = null,
    val legacyRequests: Long = 0,
)

@Serializable
data class ChannelHealthSummary(
    val channelId: String,
    val metrics: HealthMetrics = HealthMetrics(),
    val requests: HealthRequests = HealthRequests(),
    val state: String = "UNKNOWN",
    val reason: String = "暂无请求",
)

@Serializable
data class HealthBucket(val from: Long, val to: Long, val metrics: HealthMetrics)

@Serializable
data class HealthBreakdown(val id: String, val name: String, val metrics: HealthMetrics)

@Serializable
data class HealthError(val code: String, val count: Long, val lastAt: Long)

@Serializable
data class HealthAvailability(
    val keyId: String, val keyName: String, val model: String, val state: String,
    val reason: String? = null, val until: Long? = null,
)

@Serializable
data class ChannelHealthReport(
    val window: String, val from: Long, val to: Long,
    val retentionDays: Int, val indexing: Boolean,
    val minimumSamples: Int = 20, val staleAfterMs: Long = 3_600_000,
    val warningErrorRate: Double = 0.05, val criticalErrorRate: Double = 0.20,
    val slowFirstTokenMs: Long,
    val channels: List<ChannelHealthSummary>,
)

@Serializable
data class ChannelHealthDetail(
    val report: ChannelHealthReport, val trend: List<HealthBucket>,
    val byModel: List<HealthBreakdown>, val byProtocol: List<HealthBreakdown>,
    val byKey: List<HealthBreakdown>, val errors: List<HealthError>,
    val availability: List<HealthAvailability>,
)

fun healthState(metrics: HealthMetrics, enabled: Boolean, now: Long, slowFirstTokenMs: Long): Pair<String, String> = when {
    !enabled -> "DISABLED" to "渠道已停用"
    metrics.attempts == 0L -> "UNKNOWN" to "暂无有效样本"
    metrics.lastObservedAt == null || now - metrics.lastObservedAt > 3_600_000L -> "UNKNOWN" to "最近一小时无有效样本"
    metrics.attempts < 20 -> "UNKNOWN" to "样本不足（至少 20 次）"
    metrics.failures.toDouble() / metrics.attempts >= 0.20 -> "CRITICAL" to "上游失败率达到 20%"
    metrics.failures.toDouble() / metrics.attempts >= 0.05 -> "WARNING" to "上游失败率达到 5%"
    metrics.firstTokenSamples >= 20 && (metrics.p95FirstTokenMs ?: 0.0) > slowFirstTokenMs -> "WARNING" to "P95 首 token 超过软阈值"
    else -> "HEALTHY" to "成功率正常"
}
