package dev.soven.prismllm.proxy

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Tag("unit")
class GatewayMetricsTest {
    @Test
    fun `close releases owned binders before the registry exactly once`() {
        val metrics = GatewayMetrics()
        val events = mutableListOf<String>()
        val binder = object : MeterBinder, AutoCloseable {
            override fun bindTo(registry: MeterRegistry) {
                registry.counter("test.binder").increment()
            }

            override fun close() {
                assertTrue(!metrics.registry.isClosed, "the registry must remain open while binders close")
                events += "binder"
            }
        }
        binder.bindTo(metrics.registry)
        metrics.ownBinders(listOf(binder))

        metrics.close()
        metrics.close()

        assertEquals(listOf("binder"), events)
        assertTrue(metrics.registry.isClosed)
    }

    @Test
    fun `close attempts every binder and closes the registry after failures`() {
        val metrics = GatewayMetrics()
        val events = mutableListOf<String>()
        val firstFailure = IllegalStateException("first binder failed")
        val secondFailure = IllegalArgumentException("second binder failed")
        fun binder(name: String, failure: RuntimeException) = object : MeterBinder, AutoCloseable {
            override fun bindTo(registry: MeterRegistry) = Unit

            override fun close() {
                assertTrue(!metrics.registry.isClosed)
                events += name
                throw failure
            }
        }
        metrics.ownBinders(listOf(binder("first", firstFailure), binder("second", secondFailure)))

        val failure = assertFailsWith<IllegalStateException> { metrics.close() }
        metrics.close()

        assertSame(firstFailure, failure)
        assertEquals(listOf(secondFailure), failure.suppressed.toList())
        assertEquals(listOf("first", "second"), events)
        assertTrue(metrics.registry.isClosed)
    }

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
}
