package dev.soven.llmgateway.report

import dev.soven.llmgateway.domain.UpstreamAttemptRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelHealthTest {
    private val now = 1_800_000_000_000L

    private fun state(metrics: HealthMetrics, enabled: Boolean = true) =
        healthState(metrics, enabled, now, 2_000).first

    @Test
    fun `unknown covers empty sparse and stale observations`() {
        assertEquals("UNKNOWN", state(HealthMetrics()))
        assertEquals("UNKNOWN", state(HealthMetrics(attempts = 19, successes = 19, lastObservedAt = now)))
        assertEquals("UNKNOWN", state(HealthMetrics(attempts = 100, successes = 100, lastObservedAt = now - 3_600_001)))
    }

    @Test
    fun `failure bands and disabled state are classified explicitly`() {
        assertEquals("HEALTHY", state(HealthMetrics(attempts = 100, failures = 4, lastObservedAt = now)))
        assertEquals("WARNING", state(HealthMetrics(attempts = 20, failures = 1, lastObservedAt = now)))
        assertEquals("CRITICAL", state(HealthMetrics(attempts = 20, failures = 4, lastObservedAt = now)))
        assertEquals("DISABLED", state(HealthMetrics(), false))
    }

    @Test
    fun `slow first token only warns with enough streaming samples`() {
        val metrics = HealthMetrics(attempts = 100, successes = 100, firstTokenSamples = 19, p95FirstTokenMs = 2_001.0, lastObservedAt = now)
        assertEquals("HEALTHY", state(metrics))
        assertEquals("WARNING", state(metrics.copy(firstTokenSamples = 20)))
        assertEquals("HEALTHY", state(metrics.copy(firstTokenSamples = 20, p95FirstTokenMs = 2_000.0)))
    }

    @Test
    fun `upstream failure excludes client input and cancellations but includes stream errors after HTTP 200`() {
        val attempt = UpstreamAttemptRecord(index = 1, statusCode = 200, elapsedMs = 10)
        assertEquals(AttemptOutcome.SUCCESS, attemptOutcome(attempt))
        for (code in listOf("client_cancelled", "client_disconnect", "protocol_conversion_failed", "protocol_conversion_unsupported")) {
            assertEquals(AttemptOutcome.EXCLUDED, attemptOutcome(attempt.copy(errorCode = code)))
        }
        for (status in listOf(400, 422)) {
            assertEquals(AttemptOutcome.EXCLUDED, attemptOutcome(attempt.copy(upstreamErrorStatusCode = status)))
        }
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(upstreamErrorStatusCode = 429)))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(upstreamErrorCode = "overloaded")))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(statusCode = null, errorCode = "upstream_timeout")))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(statusCode = 401)))
    }
}
