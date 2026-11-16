package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationAlertState
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.requestlog.domain.AttemptOutcome
import dev.soven.prismllm.requestlog.domain.ChannelHealthSummary
import dev.soven.prismllm.requestlog.domain.HealthMetrics
import dev.soven.prismllm.requestlog.domain.UpstreamAttemptRecord
import dev.soven.prismllm.requestlog.domain.attemptOutcome
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class NotificationRulesTest {
    private fun attempt(outcome: AttemptOutcome, model: String = "m1") = NotificationAttempt(
        "channel", "渠道", "credential", "密钥", model, outcome, 1234,
    )

    @Test
    fun `failure threshold emits once and success rearms`() {
        var state: NotificationAlertState? = null
        repeat(4) {
            val update = streakTransition(state, attempt(AttemptOutcome.FAILURE), 5)
            assertNull(update.event)
            state = update.state
        }
        val triggered = streakTransition(state, attempt(AttemptOutcome.FAILURE), 5)
        assertEquals(NotificationEvents.CONSECUTIVE_FAILURES, triggered.event?.eventType)
        assertEquals(5, triggered.event?.consecutiveFailures)
        assertNull(streakTransition(triggered.state, attempt(AttemptOutcome.FAILURE), 5).event)
        val recovered = streakTransition(triggered.state, attempt(AttemptOutcome.SUCCESS), 5)
        assertEquals(NotificationEvents.CONSECUTIVE_FAILURES_RECOVERED, recovered.event?.eventType)
        assertEquals(0, recovered.state.consecutiveFailures)
        assertFalse(recovered.state.active)
        assertNull(streakTransition(recovered.state, attempt(AttemptOutcome.SUCCESS), 5).event)
        assertNotNull(streakTransition(recovered.state, attempt(AttemptOutcome.FAILURE), 1).event)
    }

    @Test
    fun `excluded attempts neither increment nor reset and subjects isolate models`() {
        val prior = streakTransition(null, attempt(AttemptOutcome.FAILURE), 2).state
        val excluded = streakTransition(prior, attempt(AttemptOutcome.EXCLUDED), 2)
        assertEquals(prior, excluded.state)
        assertNull(excluded.event)
        assertNotNull(streakTransition(excluded.state, attempt(AttemptOutcome.FAILURE), 2).event)
        assertNotEquals(attempt(AttemptOutcome.FAILURE).subject, attempt(AttemptOutcome.FAILURE, "m2").subject)
        val first = attempt(AttemptOutcome.FAILURE).copy(channelId = "a:b", credentialId = "c")
        val second = first.copy(channelId = "a", credentialId = "b:c")
        assertNotEquals(first.subject, second.subject)
    }

    @Test
    fun `restart or queue gap resets streak without reopening existing alert`() {
        val armed = streakTransition(null, attempt(AttemptOutcome.FAILURE), 1).state
        val afterGap = armed.copy(consecutiveFailures = 0)
        assertNull(streakTransition(afterGap, attempt(AttemptOutcome.FAILURE), 1).event)
        assertNotNull(streakTransition(afterGap, attempt(AttemptOutcome.SUCCESS), 1).event)
        val pending = armed.copy(active = false, consecutiveFailures = 0)
        assertNull(streakTransition(pending, attempt(AttemptOutcome.FAILURE), 2).event)
    }

    @Test
    fun `health uses first observation escalation and genuine recovery only`() {
        fun summary(state: String) = ChannelHealthSummary("channel", HealthMetrics(attempts = 100, failures = 20), state = state)
        val warning = healthTransition(null, summary("WARNING"), "渠道", 1)
        assertEquals(NotificationEvents.CHANNEL_UNHEALTHY, warning.event?.eventType)
        assertNull(healthTransition(warning.state, summary("WARNING"), "渠道", 2).event)
        val critical = healthTransition(warning.state, summary("CRITICAL"), "渠道", 3)
        assertEquals("critical", critical.event?.severity)
        val downgrade = healthTransition(critical.state, summary("WARNING"), "渠道", 4)
        assertNull(downgrade.event)
        assertNull(healthTransition(downgrade.state, summary("CRITICAL"), "渠道", 5).event)
        val unknown = healthTransition(critical.state, summary("UNKNOWN"), "渠道", 6)
        assertTrue(unknown.state.active)
        assertNull(unknown.event)
        val recovery = healthTransition(unknown.state, summary("HEALTHY"), "渠道", 7)
        assertEquals(NotificationEvents.CHANNEL_RECOVERED, recovery.event?.eventType)
        assertFalse(recovery.state.active)
        assertNotNull(healthTransition(recovery.state, summary("CRITICAL"), "渠道", 8).event)
        val disabled = healthTransition(critical.state, summary("DISABLED"), "渠道", 9)
        assertNull(disabled.event)
        assertFalse(disabled.state.active)
        assertNull(healthTransition(disabled.state, summary("HEALTHY"), "渠道", 10).event)
    }

    @Test
    fun `unavailable credentials alert without minimum traffic samples`() {
        val result = healthTransition(null, ChannelHealthSummary("channel", state = "NO_CREDENTIALS"), "渠道", 1)
        assertEquals("no_credentials", result.event?.reasonCode)
        assertEquals("critical", result.event?.severity)
    }

    @Test
    fun `existing attempt classifier excludes client and local failures`() {
        val success = UpstreamAttemptRecord(index = 1, statusCode = 200, elapsedMs = 1)
        assertEquals(AttemptOutcome.SUCCESS, attemptOutcome(success))
        listOf("client_cancelled", "client_disconnect", "protocol_conversion_failed").forEach { error ->
            assertEquals(AttemptOutcome.EXCLUDED, attemptOutcome(success.copy(errorCode = error)))
        }
        assertEquals(AttemptOutcome.EXCLUDED, attemptOutcome(success.copy(skipped = true)))
        assertEquals(AttemptOutcome.EXCLUDED, attemptOutcome(success.copy(statusCode = 400)))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(success.copy(errorCode = "upstream_stream_error")))
        assertEquals(AttemptOutcome.FAILURE, attemptOutcome(success.copy(statusCode = 503)))
    }

    @Test
    fun `delivery backoff is bounded and honors retry after`() {
        assertEquals(5_000L, retryDelay(1, null))
        assertEquals(30_000L, retryDelay(2, null))
        assertEquals(120_000L, retryDelay(3, null))
        assertEquals(600_000L, retryDelay(4, null))
        assertEquals(900_000L, retryDelay(1, 900_000))
        assertEquals(3_600_000L, retryDelay(1, Long.MAX_VALUE))
        assertEquals(5_000L, retryDelay(1, -1))
    }
}
