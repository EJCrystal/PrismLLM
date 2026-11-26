package dev.soven.prismllm.proxy

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("unit")
class GatewayMetricsTest {
    @Test
    fun `exports request outcomes latency quota and upstream health`() {
        val metrics = GatewayMetrics()
        val initial = metrics.scrape()
        assertTrue(initial.contains("gateway_admin_audit_write_failures_total 0.0"))
        assertTrue(initial.contains("gateway_request_settlement_failures_total{stage=\"pricing\"} 0.0"))
        assertTrue(initial.contains("gateway_request_settlement_failures_total{stage=\"quota\"} 0.0"))
        assertTrue(initial.contains("gateway_request_log_write_failures_total 0.0"))
        assertTrue(initial.contains("gateway_request_log_dropped_total 0.0"))
        metrics.request("fast-chat", "primary", 200, 125, failed = false)
        metrics.request("fast-chat", "primary", 200, 50, failed = true)
        metrics.quotaRejection("concurrency_limit_exceeded")
        metrics.adminAuditWriteFailed()
        metrics.requestSettlementFailed("pricing")
        metrics.requestSettlementFailed("quota")
        metrics.requestLogWriteFailed()
        metrics.requestLogDropped()
        metrics.upstreamAttempt("primary", true)
        metrics.upstreamError("primary", "upstream_error")
        metrics.upstreamRetry("primary", "secondary", "upstream_error")
        metrics.upstreamCooldownSkipped("primary")
        metrics.upstreamCooldownStarted("primary", "transient")
        metrics.upstreamKeyRateLimitSkipped("primary", "requests_per_minute")
        metrics.databaseReady(false)
        metrics.databaseReady(true)

        val output = metrics.scrape()
        assertTrue(output.contains("gateway_requests_total"))
        assertTrue(output.contains("outcome=\"error\""))
        assertTrue(output.contains("gateway_request_duration_seconds_bucket"))
        assertTrue(output.contains("gateway_quota_rejections_total"))
        assertTrue(output.contains("reason=\"concurrency_limit_exceeded\""))
        assertTrue(output.contains("gateway_admin_audit_write_failures_total"))
        assertTrue(output.contains("gateway_request_settlement_failures_total{stage=\"pricing\"} 1.0"))
        assertTrue(output.contains("gateway_request_settlement_failures_total{stage=\"quota\"} 1.0"))
        assertTrue(output.contains("gateway_request_log_write_failures_total 1.0"))
        assertTrue(output.contains("gateway_request_log_dropped_total 1.0"))
        assertTrue(output.contains("gateway_upstream_attempts_total"))
        assertTrue(output.contains("outcome=\"success\""))
        assertTrue(output.contains("gateway_upstream_errors_total"))
        assertTrue(output.contains("gateway_upstream_retries_total"))
        assertTrue(output.contains("gateway_upstream_cooldown_skips_total"))
        assertTrue(output.contains("gateway_upstream_cooldown_started_total"))
        assertTrue(output.contains("gateway_upstream_key_rate_limit_skips_total"))
        assertTrue(output.contains("reason=\"requests_per_minute\""))
        assertTrue(output.contains("gateway_database_ready 1.0"))
    }

    @Test
    fun `closes owned metric binders once and closes the registry`() {
        val metrics = GatewayMetrics()
        val first = TrackingMeterBinder()
        val second = TrackingMeterBinder()
        metrics.ownMeterBinders(listOf(first, second))

        metrics.close()
        metrics.close()

        assertEquals(1, first.closeCalls)
        assertEquals(1, second.closeCalls)
        assertTrue(metrics.registry.isClosed)
    }

    @Test
    fun `a failing metric binder does not prevent remaining cleanup`() {
        val metrics = GatewayMetrics()
        val failing = TrackingMeterBinder(IllegalStateException("synthetic close failure"))
        val remaining = TrackingMeterBinder()
        metrics.ownMeterBinders(listOf(failing, remaining))

        metrics.close()
        metrics.close()

        assertEquals(1, failing.closeCalls)
        assertEquals(1, remaining.closeCalls)
        assertTrue(metrics.registry.isClosed)
    }

    @Test
    fun `non closeable metric binders do not prevent registry cleanup`() {
        val metrics = GatewayMetrics()
        metrics.ownMeterBinders(listOf(MeterBinder {}))

        metrics.close()

        assertTrue(metrics.registry.isClosed)
    }

    private class TrackingMeterBinder(private val closeFailure: Exception? = null) : MeterBinder, AutoCloseable {
        var closeCalls = 0
            private set

        override fun bindTo(registry: MeterRegistry) = Unit

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }
}
