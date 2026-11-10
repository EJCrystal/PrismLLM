package dev.soven.prismllm.requestlog.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChannelHealthTest {
    private val now = 1_800_000_000_000L

    private val policy = HealthPolicy(slowFirstTokenMs = 2_000, slowP50FirstTokenMs = 500)

    private fun state(metrics: HealthMetrics, enabled: Boolean = true, routableCredentials: Int? = null) =
        healthState(metrics, enabled, now, policy, routableCredentials).first

    @Test
    fun `unknown covers empty sparse and stale observations`() {
        assertEquals("UNKNOWN", state(HealthMetrics()))
        assertEquals("UNKNOWN", state(HealthMetrics(
            attempts = ChannelHealthThresholds.MINIMUM_SAMPLES - 1L,
            successes = ChannelHealthThresholds.MINIMUM_SAMPLES - 1L,
            lastObservedAt = now,
        )))
        assertEquals("UNKNOWN", state(HealthMetrics(
            attempts = ChannelHealthThresholds.MINIMUM_SAMPLES.toLong(),
            successes = ChannelHealthThresholds.MINIMUM_SAMPLES.toLong(),
            lastObservedAt = now - ChannelHealthThresholds.STALE_AFTER_MS - 1,
        )))
    }

    @Test
    fun `report defaults and state evaluation share the published thresholds`() {
        val report = ChannelHealthReport(
            "24h", 0, now, 30, false,
            slowFirstTokenMs = 2_000, slowP50FirstTokenMs = 500, channels = emptyList(),
        )
        assertEquals(ChannelHealthThresholds.MINIMUM_SAMPLES, report.minimumSamples)
        assertEquals(ChannelHealthThresholds.STALE_AFTER_MS, report.staleAfterMs)
        assertEquals(ChannelHealthThresholds.WARNING_ERROR_RATE, report.warningErrorRate)
        assertEquals(ChannelHealthThresholds.CRITICAL_ERROR_RATE, report.criticalErrorRate)
        assertEquals(2_000L, report.slowFirstTokenMs)
        assertEquals(500L, report.slowP50FirstTokenMs)

        val enough = ChannelHealthThresholds.MINIMUM_SAMPLES.toLong()
        assertEquals("HEALTHY", state(HealthMetrics(attempts = enough, lastObservedAt = now)))
        assertEquals("WARNING", state(HealthMetrics(attempts = 100, failures = (ChannelHealthThresholds.WARNING_ERROR_RATE * 100).toLong(), lastObservedAt = now)))
        assertEquals("CRITICAL", state(HealthMetrics(attempts = 100, failures = (ChannelHealthThresholds.CRITICAL_ERROR_RATE * 100).toLong(), lastObservedAt = now)))
    }

    @Test
    fun `failure bands and disabled state are classified explicitly`() {
        assertEquals("HEALTHY", state(HealthMetrics(attempts = 100, failures = 4, lastObservedAt = now)))
        assertEquals("WARNING", state(HealthMetrics(attempts = 20, failures = 1, lastObservedAt = now)))
        assertEquals("CRITICAL", state(HealthMetrics(attempts = 20, failures = 4, lastObservedAt = now)))
        assertEquals("DISABLED", state(HealthMetrics(), false))
    }

    @Test
    fun `enabled channel without routable credentials reports no credentials`() {
        // 运营者停用优先于凭据状态。
        assertEquals("DISABLED", state(HealthMetrics(), enabled = false, routableCredentials = 0))
        // 渠道启用但没有任何可路由凭据：接不到请求，不能显示成「暂无样本」。
        assertEquals("NO_CREDENTIALS", state(HealthMetrics(), routableCredentials = 0))
        assertEquals("NO_CREDENTIALS", state(HealthMetrics(attempts = 100, successes = 100, lastObservedAt = now), routableCredentials = 0))
        // 只要还有一条凭据，就回到常规健康判定。
        assertEquals("HEALTHY", state(HealthMetrics(attempts = 100, successes = 100, lastObservedAt = now), routableCredentials = 1))
        // 未提供凭据信息时不参与判定，保持既有调用方行为。
        assertEquals("UNKNOWN", state(HealthMetrics()))
    }

    @Test
    fun `slow first token only warns with enough streaming samples`() {
        val metrics = HealthMetrics(attempts = 100, successes = 100, firstTokenSamples = 19, p95FirstTokenMs = 2_001.0, lastObservedAt = now)
        assertEquals("HEALTHY", state(metrics))
        assertEquals("WARNING", state(metrics.copy(firstTokenSamples = 20)))
        assertEquals("HEALTHY", state(metrics.copy(firstTokenSamples = 20, p95FirstTokenMs = 2_000.0)))
    }

    @Test
    fun `p50 and p95 thresholds warn independently and p95 wins the reason`() {
        val enough = ChannelHealthThresholds.MINIMUM_SAMPLES.toLong()
        val slowP50 = HealthMetrics(
            attempts = enough, successes = enough, lastObservedAt = now,
            firstTokenSamples = enough, p50FirstTokenMs = 600.0, p95FirstTokenMs = 1_900.0,
        )
        // P95 未越界但 P50 越界：整体偏慢也要提示，原因指向 P50。
        assertEquals("WARNING" to "P50 首 token 超过 500ms 提示阈值", healthState(slowP50, true, now, policy))
        // 两个阈值都没超过才算健康。
        assertEquals("HEALTHY", state(slowP50.copy(p50FirstTokenMs = 500.0, p95FirstTokenMs = 2_000.0)))
        // 同时越界时先报 P95。
        assertEquals(
            "WARNING" to "P95 首 token 超过 2s 判定阈值",
            healthState(slowP50.copy(p50FirstTokenMs = 100.0, p95FirstTokenMs = 2_001.0), true, now, policy),
        )
        // 流式样本不足时只是跳过延迟档，成功率正常时仍判健康。
        assertEquals("HEALTHY", state(slowP50.copy(firstTokenSamples = enough - 1)))
    }

    @Test
    fun `health policy rejects a p50 threshold above the p95 threshold`() {
        assertFailsWith<IllegalArgumentException> {
            HealthPolicy(slowFirstTokenMs = 1_000, slowP50FirstTokenMs = 2_000)
        }
        assertEquals(30_000L, HealthPolicy().slowFirstTokenMs)
        assertEquals(10_000L, HealthPolicy().slowP50FirstTokenMs)
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
            assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(upstreamErrorStatusCode = status, retryable = true)))
        }
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(upstreamErrorStatusCode = 429)))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(upstreamErrorCode = "overloaded")))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(statusCode = null, errorCode = "upstream_timeout")))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(attempt.copy(statusCode = 401)))
        assertEquals(AttemptOutcome.EXCLUDED, attemptOutcome(attempt.copy(skipped = true, skipReason = "cooldown")))
    }
}
