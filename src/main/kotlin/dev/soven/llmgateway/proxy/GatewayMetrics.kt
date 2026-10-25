package dev.soven.llmgateway.proxy

import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 网关自有指标。只记录路由与用量维度，不记录调用方密钥或正文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class GatewayMetrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val databaseReadyValue = AtomicReference(0.0)
    private val adminAuditWriteFailures =
        registry.counter("gateway_admin_audit_write_failures_total")

    init {
        Gauge.builder("gateway_database_ready", databaseReadyValue) { it.get() }
            .description("Database readiness status")
            .register(registry)
    }

    fun request(alias: String, channel: String?, status: Int, durationMs: Long, failed: Boolean) {
        val outcome = if (failed || status >= 400) "error" else "success"
        val tags = Tags.of("alias", alias, "channel", channel ?: "none", "status", status.toString(), "outcome", outcome)
        registry.counter(
            "gateway_requests_total",
            tags,
        ).increment()
        Timer.builder("gateway_request_duration_seconds")
            .description("Complete client request latency")
            .publishPercentileHistogram()
            .tags(tags)
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    fun firstToken(alias: String, channel: String, millis: Long) {
        Timer.builder("gateway_ttft_seconds")
            .description("Time to first token")
            .publishPercentileHistogram()
            .tags(Tags.of("alias", alias, "channel", channel))
            .register(registry)
            .record(millis, TimeUnit.MILLISECONDS)
    }

    fun usage(alias: String, usage: Usage) {
        usage.input?.let { recordTokens(alias, "input", it) }
        usage.output?.let { recordTokens(alias, "output", it) }
        usage.cached?.let { recordTokens(alias, "cached", it) }
        usage.total?.let { recordTokens(alias, "total", it) }
    }

    fun upstreamError(channel: String, code: String) {
        registry.counter("gateway_upstream_errors_total", Tags.of("channel", channel, "code", code)).increment()
        upstreamAttempt(channel, false)
    }

    fun upstreamRetry(fromChannel: String, toChannel: String, reason: String) {
        registry.counter(
            "gateway_upstream_retries_total",
            Tags.of("from_channel", fromChannel, "to_channel", toChannel, "reason", reason),
        ).increment()
    }

    fun upstreamCooldownSkipped(channel: String) {
        registry.counter("gateway_upstream_cooldown_skips_total", Tags.of("channel", channel)).increment()
    }

    fun upstreamCooldownStarted(channel: String, reason: String) {
        registry.counter(
            "gateway_upstream_cooldown_started_total",
            Tags.of("channel", channel, "reason", reason),
        ).increment()
    }

    fun upstreamAttempt(channel: String, healthy: Boolean) {
        registry.counter(
            "gateway_upstream_attempts_total",
            Tags.of("channel", channel, "outcome", if (healthy) "success" else "error"),
        ).increment()
    }

    fun quotaRejection(reason: String) {
        registry.counter("gateway_quota_rejections_total", Tags.of("reason", reason)).increment()
    }

    fun adminAuditWriteFailed() {
        adminAuditWriteFailures.increment()
    }

    fun databaseReady(ready: Boolean) {
        databaseReadyValue.set(if (ready) 1.0 else 0.0)
    }

    private fun recordTokens(alias: String, kind: String, value: Long) {
        registry.summary("gateway_tokens", Tags.of("alias", alias, "kind", kind)).record(value.toDouble())
    }

    fun scrape(): String = registry.scrape()
}
