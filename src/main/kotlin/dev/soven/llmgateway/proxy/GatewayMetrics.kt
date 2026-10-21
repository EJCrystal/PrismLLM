package dev.soven.llmgateway.proxy

import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit

/**
 * 网关自有指标。只记录路由与用量维度，不记录调用方密钥或正文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class GatewayMetrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    fun request(alias: String, channel: String?, status: Int) {
        registry.counter(
            "gateway_requests_total",
            Tags.of("alias", alias, "channel", channel ?: "none", "status", status.toString()),
        ).increment()
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
    }

    private fun recordTokens(alias: String, kind: String, value: Long) {
        registry.summary("gateway_tokens", Tags.of("alias", alias, "kind", kind)).record(value.toDouble())
    }

    fun scrape(): String = registry.scrape()
}
