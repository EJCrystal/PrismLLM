package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationAlertState
import dev.soven.prismllm.admin.domain.NotificationAlertUpdate
import dev.soven.prismllm.admin.domain.NotificationDelivery
import dev.soven.prismllm.admin.domain.NotificationDeliveryResult
import dev.soven.prismllm.admin.domain.NotificationDeliveryTask
import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.admin.domain.NotificationSettings
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.admin.persistence.NotificationRepository
import dev.soven.prismllm.requestlog.domain.ChannelHealthSummary
import dev.soven.prismllm.requestlog.domain.UpstreamAttemptRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class NotificationRuntimeTest {
    private class Observations {
        val repository = mockk<NotificationRepository>()
        val states = mutableMapOf<String, NotificationAlertState>()
        val events = mutableListOf<NotificationEvent>()
        var failNextWrite = false

        init {
            coEvery { repository.loadSettings() } returns NotificationSettings(true, 2)
            coEvery { repository.resetStreakCounts() } answers {
                states.replaceAll { _, state -> state.copy(consecutiveFailures = 0) }
            }
            coEvery { repository.updateAlert(any(), any(), any()) } coAnswers {
                if (failNextWrite) {
                    failNextWrite = false
                    error("observation write failed")
                }
                val subject = firstArg<String>()
                val transition = thirdArg<(NotificationAlertState?) -> NotificationAlertUpdate>()
                val update = transition(states[subject])
                states[subject] = update.state
                update.event?.let(events::add)
                Unit
            }
            coEvery { repository.cleanup(any()) } returns Unit
            coEvery { repository.claimDue(any(), any()) } returns emptyList()
        }
    }

    private fun attempt(success: Boolean = false) = UpstreamAttemptRecord(
        index = 1, channelId = "channel", channelKeyId = "credential", upstreamModel = "model",
        statusCode = if (success) 200 else 503, elapsedMs = 1,
    )

    @Test
    fun `queue overflow discards uncertain streak and preserves an already opened alert`() = runTest {
        val fixture = Observations()
        var dropped = 0
        val runtime = NotificationRuntime(
            fixture.repository, NotificationTransport { _, _ -> error("no queued deliveries") }, backgroundScope,
            healthSnapshot = { emptyList() }, channelNames = { emptyMap() }, onDropped = { dropped++ }, queueCapacity = 1,
        )
        runtime.start()
        runCurrent()
        runtime.observe(attempt())
        runCurrent()
        assertEquals(1, fixture.states.values.single().consecutiveFailures)

        // One item resumes the waiting consumer; a second fills the queue before the scheduler runs.
        // Losing the next success must invalidate both failures before they can open an alert.
        runtime.observe(attempt())
        runtime.observe(attempt())
        runtime.observe(attempt(success = true))
        assertEquals(1, dropped)
        runCurrent()
        runtime.observe(attempt())
        runCurrent()
        assertEquals(1, fixture.states.values.single().consecutiveFailures)
        assertTrue(fixture.events.isEmpty())
        runtime.observe(attempt())
        runCurrent()
        assertEquals(listOf(NotificationEvents.CONSECUTIVE_FAILURES), fixture.events.map { it.eventType })

        runtime.observe(attempt())
        runtime.observe(attempt())
        runtime.observe(attempt(success = true))
        runCurrent()
        runtime.observe(attempt())
        runCurrent()
        assertEquals(1, fixture.events.size)
        assertTrue(fixture.states.values.single().active)
        runtime.observe(attempt(success = true))
        runCurrent()
        assertEquals(NotificationEvents.CONSECUTIVE_FAILURES_RECOVERED, fixture.events.last().eventType)
        assertFalse(fixture.states.values.single().active)
        runtime.closeAndDrain()
    }

    @Test
    fun `failed observation write resets continuity before later attempts are counted`() = runTest {
        val fixture = Observations()
        val failures = mutableListOf<String>()
        val runtime = NotificationRuntime(
            fixture.repository, NotificationTransport { _, _ -> error("no queued deliveries") }, backgroundScope,
            healthSnapshot = { emptyList() }, channelNames = { emptyMap() }, onFailure = failures::add,
        )
        runtime.start()
        runCurrent()
        runtime.observe(attempt())
        runCurrent()
        fixture.failNextWrite = true
        runtime.observe(attempt(success = true))
        runtime.observe(attempt())
        runCurrent()
        assertEquals(listOf("observation"), failures)
        runtime.observe(attempt())
        runCurrent()
        assertTrue(fixture.events.isEmpty())
        assertEquals(1, fixture.states.values.single().consecutiveFailures)
        runtime.observe(attempt())
        runCurrent()
        assertEquals(1, fixture.events.size)
        runtime.closeAndDrain()
    }

    @Test
    fun `health polling emits on startup and preserves deduplication across a runtime restart`() = runTest {
        val fixture = Observations()
        var state = "NO_CREDENTIALS"
        fun runtime() = NotificationRuntime(
            fixture.repository, NotificationTransport { _, _ -> error("no queued deliveries") }, backgroundScope,
            healthSnapshot = { listOf(ChannelHealthSummary("channel", state = state)) },
            channelNames = { mapOf("channel" to "渠道") },
        )
        val first = runtime()
        first.start()
        runCurrent()
        assertEquals(listOf(NotificationEvents.CHANNEL_UNHEALTHY), fixture.events.map { it.eventType })
        first.checkHealth()
        assertEquals(1, fixture.events.size)
        first.closeAndDrain()
        val restarted = runtime()
        restarted.start()
        runCurrent()
        assertEquals(1, fixture.events.size)
        state = "UNKNOWN"
        restarted.checkHealth()
        assertEquals(1, fixture.events.size)
        state = "HEALTHY"
        restarted.checkHealth()
        assertEquals(NotificationEvents.CHANNEL_RECOVERED, fixture.events.last().eventType)
        restarted.closeAndDrain()
    }

    @Test
    fun `worker honors retry after and stops after fifth claimed attempt`() = runTest {
        val repository = mockk<NotificationRepository>()
        val results = mutableListOf<NotificationDeliveryResult>()
        val task = task(attempts = 1)
        coEvery { repository.claimDue(4, 30_000) } returnsMany listOf(listOf(task), listOf(task.copy(delivery = task.delivery.copy(attempts = 5))))
        coEvery { repository.isTaskCurrent(any()) } returns true
        coEvery { repository.complete(any(), capture(results)) } returns true
        val runtime = NotificationRuntime(
            repository, NotificationTransport { _, _ -> NotificationSendResult(false, true, 429, "rate_limited", 20_000) },
            backgroundScope, healthSnapshot = { emptyList() }, channelNames = { emptyMap() }, nowMs = { 1_000 },
        )
        runtime.deliverDue()
        runtime.deliverDue()
        assertEquals(NotificationDeliveryResult("pending", 429, "rate_limited", 21_000), results[0])
        assertEquals(NotificationDeliveryResult("failed", 429, "rate_limited"), results[1])
    }

    @Test
    fun `cancelled configuration never reaches transport and unknown outcomes retain the lease`() = runTest {
        val repository = mockk<NotificationRepository>()
        val task = task(attempts = 1)
        coEvery { repository.claimDue(4, 30_000) } returns listOf(task)
        coEvery { repository.isTaskCurrent(task) } returnsMany listOf(false, true)
        var sends = 0
        val failures = mutableListOf<String>()
        val runtime = NotificationRuntime(
            repository, NotificationTransport { _, _ -> sends++; error("unknown transport result") }, backgroundScope,
            healthSnapshot = { emptyList() }, channelNames = { emptyMap() }, onFailure = failures::add,
        )
        runtime.deliverDue()
        assertEquals(0, sends)
        runtime.deliverDue()
        assertEquals(1, sends)
        assertEquals(listOf("delivery"), failures)
        coVerify(exactly = 0) { repository.complete(any(), any()) }
    }

    private fun task(attempts: Int): NotificationDeliveryTask {
        val event = NotificationEvent(eventId = "event", eventType = NotificationEvents.TEST, occurredAt = 1_000, severity = "info")
        val target = NotificationTarget(id = "target", name = "目标", type = "webhook")
        return NotificationDeliveryTask(
            NotificationDelivery("delivery", event.eventId, target.id, target.name, event.eventType, "in_flight", attempts, 31_000, 1_000, 1_000),
            target, event, "lease",
        )
    }
}
