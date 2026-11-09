package dev.soven.llmgateway.report

import dev.soven.llmgateway.requestlog.domain.UpstreamAttemptRecord
import kotlinx.serialization.Serializable

enum class AttemptOutcome { SUCCESS, FAILURE, EXCLUDED }

/** Cancelled clients and invalid request parameters are not provider outages. */
fun attemptOutcome(attempt: UpstreamAttemptRecord): AttemptOutcome {
    if (attempt.skipped) return AttemptOutcome.EXCLUDED
    if (attempt.errorCode in setOf("client_cancelled", "client_disconnect", "protocol_conversion_failed", "protocol_conversion_unsupported")) {
        return AttemptOutcome.EXCLUDED
    }
    val status = attempt.upstreamErrorStatusCode ?: attempt.statusCode
    if ((status == 400 || status == 422) && !attempt.retryable) return AttemptOutcome.EXCLUDED
    return if (status in 200..299 && attempt.errorCode == null && attempt.upstreamErrorCode == null) {
        AttemptOutcome.SUCCESS
    } else AttemptOutcome.FAILURE
}

val healthWindows = linkedMapOf("24h" to 86_400_000L, "7d" to 7 * 86_400_000L, "15d" to 15 * 86_400_000L, "30d" to 30 * 86_400_000L)

object ChannelHealthThresholds {
    const val MINIMUM_SAMPLES = 20
    const val STALE_AFTER_MS = 3_600_000L
    const val WARNING_ERROR_RATE = 0.05
    const val CRITICAL_ERROR_RATE = 0.20
}

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

/** 渠道当前冷却概览：最早恢复时间、主因和候选计数；仅供管理面展示。 */
data class ChannelCooldownSummary(
    val until: Long? = null,
    val reason: String? = null,
    val coolingCandidates: Int = 0,
    val totalCandidates: Int = 0,
)

@Serializable
data class ChannelHealthSummary(
    val channelId: String,
    val metrics: HealthMetrics = HealthMetrics(),
    val requests: HealthRequests = HealthRequests(),
    val state: String = "UNKNOWN",
    val reason: String = "暂无请求",
    val cooldownUntil: Long? = null,
    val cooldownReason: String? = null,
    val coolingCandidates: Int = 0,
    val totalCandidates: Int = 0,
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
    /** 生效的冷却范围：channel / key / candidate；非冷却状态为 null。 */
    val cooldownScope: String? = null,
)

@Serializable
data class ChannelHealthReport(
    val window: String, val from: Long, val to: Long,
    val retentionDays: Int, val indexing: Boolean,
    val minimumSamples: Int = ChannelHealthThresholds.MINIMUM_SAMPLES,
    val staleAfterMs: Long = ChannelHealthThresholds.STALE_AFTER_MS,
    val warningErrorRate: Double = ChannelHealthThresholds.WARNING_ERROR_RATE,
    val criticalErrorRate: Double = ChannelHealthThresholds.CRITICAL_ERROR_RATE,
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

/**
 * 渠道健康状态的判定顺序：停用 → 无可用凭据 → 样本不足 → 成功率与延迟。
 *
 * `DISABLED` 是运营者主动停用；`NO_CREDENTIALS` 是渠道仍启用、但支持它的凭据全部不可路由
 * （人工停用、永久停用或尚未到期的临时停用），此时渠道接不到任何请求。
 * [routableCredentials] 为 null 表示调用方没有提供凭据信息，不参与判定。
 */
fun healthState(
    metrics: HealthMetrics,
    enabled: Boolean,
    now: Long,
    slowFirstTokenMs: Long,
    routableCredentials: Int? = null,
): Pair<String, String> = when {
    !enabled -> "DISABLED" to "渠道已停用"
    routableCredentials == 0 -> "NO_CREDENTIALS" to "没有可用的上游凭据"
    metrics.attempts == 0L -> "UNKNOWN" to "暂无有效样本"
    metrics.lastObservedAt == null || now - metrics.lastObservedAt > ChannelHealthThresholds.STALE_AFTER_MS -> "UNKNOWN" to "最近一小时无有效样本"
    metrics.attempts < ChannelHealthThresholds.MINIMUM_SAMPLES -> "UNKNOWN" to "样本不足（至少 ${ChannelHealthThresholds.MINIMUM_SAMPLES} 次）"
    metrics.failures.toDouble() / metrics.attempts >= ChannelHealthThresholds.CRITICAL_ERROR_RATE ->
        "CRITICAL" to "上游失败率达到 ${ChannelHealthThresholds.CRITICAL_ERROR_RATE.asPercent()}%"
    metrics.failures.toDouble() / metrics.attempts >= ChannelHealthThresholds.WARNING_ERROR_RATE ->
        "WARNING" to "上游失败率达到 ${ChannelHealthThresholds.WARNING_ERROR_RATE.asPercent()}%"
    metrics.firstTokenSamples >= ChannelHealthThresholds.MINIMUM_SAMPLES && (metrics.p95FirstTokenMs ?: 0.0) > slowFirstTokenMs -> "WARNING" to "P95 首 token 超过软阈值"
    else -> "HEALTHY" to "成功率正常"
}

private fun Double.asPercent(): Int = (this * 100).toInt()
