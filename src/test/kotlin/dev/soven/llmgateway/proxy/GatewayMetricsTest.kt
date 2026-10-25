package dev.soven.llmgateway.proxy

import kotlin.test.Test
import kotlin.test.assertTrue

class GatewayMetricsTest {
    @Test
    fun `exports request outcomes latency quota and upstream health`() {
        val metrics = GatewayMetrics()
        val initial = metrics.scrape()
        assertTrue(initial.contains("gateway_admin_audit_write_failures_total 0.0"))
        metrics.request("fast-chat", "primary", 200, 125, failed = false)
        metrics.request("fast-chat", "primary", 200, 50, failed = true)
        metrics.quotaRejection("concurrency_limit_exceeded")
        metrics.adminAuditWriteFailed()
        metrics.upstreamAttempt("primary", true)
        metrics.upstreamError("primary", "upstream_error")
        metrics.upstreamRetry("primary", "secondary", "upstream_error")
        metrics.upstreamCircuitSkipped("primary")
        metrics.upstreamCircuitOpened("primary")
        metrics.databaseReady(false)
        metrics.databaseReady(true)

        val output = metrics.scrape()
        assertTrue(output.contains("gateway_requests_total"))
        assertTrue(output.contains("outcome=\"error\""))
        assertTrue(output.contains("gateway_request_duration_seconds_bucket"))
        assertTrue(output.contains("gateway_quota_rejections_total"))
        assertTrue(output.contains("reason=\"concurrency_limit_exceeded\""))
        assertTrue(output.contains("gateway_admin_audit_write_failures_total"))
        assertTrue(output.contains("gateway_upstream_attempts_total"))
        assertTrue(output.contains("outcome=\"success\""))
        assertTrue(output.contains("gateway_upstream_errors_total"))
        assertTrue(output.contains("gateway_upstream_retries_total"))
        assertTrue(output.contains("gateway_upstream_circuit_skips_total"))
        assertTrue(output.contains("gateway_upstream_circuit_opens_total"))
        assertTrue(output.contains("gateway_database_ready 1.0"))
    }
}
