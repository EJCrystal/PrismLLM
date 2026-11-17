package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.NotificationAlertState
import dev.soven.prismllm.admin.domain.NotificationAlertUpdate
import dev.soven.prismllm.admin.domain.NotificationCredentialFields
import dev.soven.prismllm.admin.domain.NotificationCredentialUpdate
import dev.soven.prismllm.admin.domain.NotificationDeliveryResult
import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.admin.domain.NotificationSettings
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class NotificationRepositoryTest {
    private lateinit var factory: TestDatabase
    private lateinit var repository: NotificationRepository
    private val clock = AtomicLong(BASE_TIME)

    @BeforeAll
    fun start() {
        val postgres = SharedPostgres.container
        factory = TestDatabase(SharedPostgres.newDatabase(), postgres.username, postgres.password)
        repository = NotificationRepository(factory.database, clock::get)
    }

    @BeforeEach
    fun reset() {
        runBlocking {
            clock.set(BASE_TIME)
            suspendTransaction(factory.database) {
                NotificationDeliveries.deleteAll()
                NotificationEventRecords.deleteAll()
                NotificationAlertStates.deleteAll()
                NotificationTargetCredentials.deleteAll()
                NotificationTargets.deleteAll()
                NotificationSettingsTable.deleteAll()
            }
        }
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
    }

    @Test
    fun `settings defaults and server target revisions survive a repository restart`() = runBlocking {
        assertEquals(NotificationSettings(), repository.loadSettings())
        assertEquals(NotificationSettings(true, 7, BASE_TIME), repository.saveSettings(true, 7))
        val input = target().copy(updatedAt = 1)
        val created = repository.saveTarget(input)
        assertEquals(1L, created.revision)
        assertEquals(BASE_TIME, created.updatedAt)
        assertEquals(created, repository.saveTarget(created.copy(updatedAt = 2)))

        clock.incrementAndGet()
        val updated = repository.saveTarget(created.copy(name = "Updated target"))
        assertEquals(2L, updated.revision)
        assertEquals(clock.get(), updated.updatedAt)
        assertEquals("notification_target_revision_conflict", assertFailsWith<IllegalArgumentException> {
            repository.saveTarget(created.copy(name = "Stale update"))
        }.message)
        assertEquals("notification_target_exists", assertFailsWith<IllegalArgumentException> {
            repository.saveTarget(input)
        }.message)

        val reopened = NotificationRepository(factory.database, clock::get)
        assertEquals(updated, reopened.findTarget(updated.id))
        assertEquals(listOf(updated), reopened.listTargets())
        assertEquals(NotificationSettings(true, 7, BASE_TIME), reopened.loadSettings())
        assertTrue(reopened.deleteTarget(updated.id))
        assertFalse(reopened.deleteTarget(updated.id))
        assertNull(reopened.findTarget(updated.id))
        assertEquals("notification_target_not_found", assertFailsWith<IllegalArgumentException> {
            repository.saveTarget(updated)
        }.message)
    }

    @Test
    fun `target limit is atomic across concurrent creates`() = runBlocking {
        repeat(19) { repository.saveTarget(target(name = "Target $it")) }
        val gate = CompletableDeferred<Unit>()
        val results = coroutineScope {
            val jobs = (1..2).map { index ->
                async(Dispatchers.IO) {
                    gate.await()
                    runCatching { repository.saveTarget(target(name = "Concurrent $index")) }
                }
            }
            gate.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("notification_target_limit", results.single { it.isFailure }.exceptionOrNull()?.message)
        assertEquals(20, repository.listTargets().size)
    }

    @Test
    fun `global and target switches suppress events while test bypasses only event and channel filters`() = runBlocking {
        val subscribed = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_DISABLED), channels = listOf(CHANNEL)))
        val otherEvent = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_RECOVERED)))
        val otherChannel = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_DISABLED), channels = listOf("other-channel")))
        val disabled = repository.saveTarget(target(enabled = false))
        assertTrue(repository.enqueue(event()).isEmpty())
        assertTrue(repository.enqueue(event(type = NotificationEvents.TEST), subscribed.id).isEmpty())
        assertEquals(0L, eventCount())

        repository.saveSettings(true, 5)
        assertTrue(repository.enqueue(event(channel = "no-match")).isEmpty())
        assertEquals(0L, eventCount())
        val ids = repository.enqueue(event())
        assertEquals(listOf(subscribed.id), repository.deliveries().items.filter { it.id in ids }.map { it.targetId })
        assertTrue(repository.enqueue(event(), otherEvent.id).isEmpty())
        val testIds = repository.enqueue(event(type = NotificationEvents.TEST, channel = null), otherChannel.id)
        assertEquals(1, testIds.size)
        assertEquals(otherChannel.id, repository.deliveries().items.single { it.id == testIds.single() }.targetId)
        assertTrue(repository.enqueue(event(type = NotificationEvents.TEST), disabled.id).isEmpty())
        assertEquals(2L, eventCount())
    }

    @Test
    fun `event payload is immutable and replay never duplicates a target delivery`() = runBlocking {
        repository.saveSettings(true, 5)
        val firstTarget = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_DISABLED), channels = listOf(CHANNEL)))
        val original = event().copy(
            reasonCode = "first_reason",
            metrics = buildJsonObject { put("attempts", JsonPrimitive(17)) },
        )
        val firstIds = repository.enqueue(original)
        assertEquals(firstIds.toSet(), repository.enqueue(original.copy(reasonCode = "changed_reason")).toSet())
        val secondTarget = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_DISABLED), channels = listOf(CHANNEL)))
        val allIds = repository.enqueue(original.copy(eventType = NotificationEvents.KEY_RECOVERED, channelId = "wrong-channel"))
        assertEquals(2, allIds.size)
        assertEquals(1L, eventCount())
        val tasks = repository.claimDue()
        assertEquals(setOf(firstTarget.id, secondTarget.id), tasks.map { it.target.id }.toSet())
        assertTrue(tasks.all { it.event == original })
        assertTrue(tasks.all { it.target.revision == 1L })
        assertEquals(2L, repository.deliveries().total)
    }

    @Test
    fun `enqueue participates in caller rollback and alert transition rollback is atomic`() = runBlocking {
        repository.saveSettings(true, 5)
        repository.saveTarget(target())
        assertFailsWith<IllegalStateException> {
            suspendTransaction(factory.database) {
                repository.enqueueInTransaction(event())
                error("rollback caller state and outbox")
            }
        }
        assertEquals(0L, eventCount())
        assertEquals(0L, repository.deliveries().total)
        assertFailsWith<IllegalStateException> {
            repository.updateAlert("broken", "streak") { error("transition failed") }
        }
        assertNull(alert("broken", "streak"))
    }

    @Test
    fun `concurrent alert transitions serialize counts and create one opening event`() = runBlocking {
        repository.saveSettings(true, 5)
        repository.saveTarget(target())
        coroutineScope {
            (1..16).map {
                async(Dispatchers.IO) {
                    repository.updateAlert("subject", "streak") { previous ->
                        val before = previous ?: NotificationAlertState("subject", "streak")
                        val count = before.consecutiveFailures + 1
                        val opening = count >= 3 && !before.active
                        NotificationAlertUpdate(
                            before.copy(active = before.active || opening, consecutiveFailures = count, updatedAt = clock.get()),
                            event().takeIf { opening },
                        )
                    }
                }
            }.awaitAll()
        }
        val state = assertNotNull(alert("subject", "streak"))
        assertTrue(state.active)
        assertEquals(16, state.consecutiveFailures)
        assertEquals(1L, repository.deliveries().total)
        repository.resetStreakCounts()
        assertEquals(state.copy(consecutiveFailures = 0), alert("subject", "streak"))
        repository.saveSettings(false, 5)
        repository.saveSettings(true, 5)
        assertEquals(state.copy(consecutiveFailures = 0), alert("subject", "streak"))
    }

    @Test
    fun `disabled alerts do not run transitions and no recipient does not latch a new alarm`() = runBlocking {
        repository.updateAlert("health", "health") { error("disabled transitions must not run") }
        assertNull(alert("health", "health"))
        repository.saveSettings(true, 5)
        suspend fun open() {
            repository.updateAlert("health", "health") { previous ->
                val before = previous ?: NotificationAlertState("health", "health")
                NotificationAlertUpdate(before.copy(active = true, state = "WARNING", updatedAt = clock.get()), event())
            }
        }
        open()
        assertFalse(assertNotNull(alert("health", "health")).active)
        repository.saveTarget(target(events = listOf(NotificationEvents.KEY_RECOVERED)))
        open()
        assertFalse(assertNotNull(alert("health", "health")).active)
        assertEquals(0L, eventCount())
        val recipient = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_DISABLED)))
        open()
        assertTrue(assertNotNull(alert("health", "health")).active)
        assertEquals(1L, repository.deliveries().total)
        repository.deleteTarget(recipient.id)
        repository.updateAlert("health", "health") { previous ->
            NotificationAlertUpdate(assertNotNull(previous).copy(active = false), event(type = NotificationEvents.CHANNEL_RECOVERED))
        }
        assertFalse(assertNotNull(alert("health", "health")).active)
    }

    @Test
    fun `undelivered active severity escalation stays eligible until a recipient exists`() = runBlocking {
        repository.saveSettings(true, 5)
        repository.updateAlert("escalating", "health") {
            NotificationAlertUpdate(NotificationAlertState("escalating", "health", active = true, state = "WARNING", updatedAt = clock.get()))
        }
        suspend fun escalate() {
            repository.updateAlert("escalating", "health") { previous ->
                val before = assertNotNull(previous)
                NotificationAlertUpdate(
                    before.copy(state = "CRITICAL", updatedAt = clock.get()),
                    event(type = NotificationEvents.CHANNEL_UNHEALTHY),
                )
            }
        }
        escalate()
        assertEquals("WARNING", assertNotNull(alert("escalating", "health")).state)
        assertEquals(0L, repository.deliveries().total)
        repository.saveTarget(target(events = listOf(NotificationEvents.CHANNEL_UNHEALTHY)))
        escalate()
        assertEquals("CRITICAL", assertNotNull(alert("escalating", "health")).state)
        assertEquals(1L, repository.deliveries().total)
        assertEquals(1, repository.claimDue().size)
    }

    @Test
    fun `retry and lease replacement retain FIFO for the same target including equal timestamps`() = runBlocking {
        repository.saveSettings(true, 5)
        repository.saveTarget(target())
        val opening = event()
        val recovery = event(type = NotificationEvents.KEY_RECOVERED)
        repository.enqueue(opening)
        repository.enqueue(recovery)
        val first = repository.claimDue().single()
        assertEquals(opening, first.event)
        assertEquals(1, first.delivery.attempts)
        assertTrue(repository.isTaskCurrent(first))
        assertTrue(repository.complete(first, NotificationDeliveryResult("pending", 429, "rate_limited", BASE_TIME + 5_000)))
        assertTrue(repository.claimDue().isEmpty())
        clock.addAndGet(5_000)
        val retry = repository.claimDue().single()
        assertEquals(opening, retry.event)
        assertEquals(2, retry.delivery.attempts)
        assertNotEquals(first.leaseToken, retry.leaseToken)
        assertFalse(repository.isTaskCurrent(first))
        assertTrue(repository.isTaskCurrent(retry))
        assertFalse(repository.complete(first, NotificationDeliveryResult("succeeded", 200)))
        assertTrue(repository.complete(retry, NotificationDeliveryResult("succeeded", 204)))
        assertFalse(repository.complete(retry, NotificationDeliveryResult("failed", 500)))
        val recoveryTask = repository.claimDue().single()
        assertEquals(recovery, recoveryTask.event)
        assertTrue(repository.complete(recoveryTask, NotificationDeliveryResult("succeeded", 200)))
        val openingHistory = repository.deliveries().items.single { it.eventId == opening.eventId }
        assertEquals("succeeded", openingHistory.status)
        assertEquals(2, openingHistory.attempts)
        assertEquals(204, openingHistory.lastStatusCode)
        assertNull(openingHistory.errorCode)
    }

    @Test
    fun `claim skips a locked target head without claiming its later delivery`() = runBlocking {
        repository.saveSettings(true, 5)
        val first = repository.saveTarget(target(name = "First"))
        val second = repository.saveTarget(target(name = "Second"))
        val head = repository.enqueue(event(), first.id).single()
        repository.enqueue(event(type = NotificationEvents.KEY_RECOVERED), first.id)
        repository.enqueue(event(), second.id)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            suspendTransaction(factory.database) {
                NotificationDeliveries.selectAll().where { NotificationDeliveries.id eq head }.forUpdate().single()
                locked.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { locked.await() }
            val claimed = withTimeout(5_000) { async(Dispatchers.IO) { repository.claimDue() }.await() }
            assertEquals(listOf(second.id), claimed.map { it.target.id })
        } finally {
            release.complete(Unit)
            holder.await()
        }
        assertEquals(listOf(head), repository.claimDue().map { it.delivery.id })
    }

    @Test
    fun `expired crash leases consume attempts and fail after five claims`() = runBlocking {
        repository.saveSettings(true, 5)
        repository.saveTarget(target())
        repository.enqueue(event())
        val tokens = mutableSetOf<String>()
        repeat(5) { index ->
            val task = repository.claimDue(leaseMs = 100).single()
            assertEquals(index + 1, task.delivery.attempts)
            assertTrue(tokens.add(task.leaseToken))
            assertTrue(repository.isTaskCurrent(task))
            clock.addAndGet(100)
            assertFalse(repository.isTaskCurrent(task))
            assertFalse(repository.complete(task, NotificationDeliveryResult("succeeded", 200)))
        }
        assertTrue(repository.claimDue(leaseMs = 100).isEmpty())
        val exhausted = repository.deliveries().items.single()
        assertEquals("failed", exhausted.status)
        assertEquals(5, exhausted.attempts)
        assertEquals("lease_exhausted", exhausted.errorCode)
        suspendTransaction(factory.database) {
            val row = NotificationDeliveries.selectAll().single()
            assertNull(row[NotificationDeliveries.leaseToken])
            assertNull(row[NotificationDeliveries.leaseUntil])
        }
    }

    @Test
    fun `configuration changes cancel old leases and pending rows while history keeps original target name`() = runBlocking {
        repository.saveSettings(true, 5)
        val first = repository.saveTarget(target(name = "Original name"))
        val second = repository.saveTarget(target(name = "Other target"))
        repository.enqueue(event())
        val tasks = repository.claimDue()
        repository.enqueue(event())
        val firstTask = tasks.single { it.target.id == first.id }
        val secondTask = tasks.single { it.target.id == second.id }
        assertEquals(first, repository.saveTarget(first))
        assertTrue(repository.isTaskCurrent(firstTask))
        val updated = repository.saveTarget(first.copy(name = "New name"))
        assertEquals(2L, updated.revision)
        assertFalse(repository.isTaskCurrent(firstTask))
        assertFalse(repository.complete(firstTask, NotificationDeliveryResult("succeeded", 200)))
        assertTrue(repository.isTaskCurrent(secondTask))
        assertTrue(repository.complete(secondTask, NotificationDeliveryResult("succeeded", 200)))
        assertTrue(repository.deleteTarget(first.id))
        val firstHistory = repository.deliveries(first.id)
        assertEquals(2L, firstHistory.total)
        assertTrue(firstHistory.items.all { it.status == "cancelled" && it.targetName == "Original name" })
        repository.saveSettings(false, 5)
        assertTrue(repository.claimDue().isEmpty())
        assertEquals(setOf("succeeded", "cancelled"), repository.deliveries(second.id).items.map { it.status }.toSet())
        assertEquals(4L, repository.deliveries(pageSize = 1).total)
        assertEquals(1, repository.deliveries(page = 2, pageSize = 1).items.size)
        assertTrue(repository.deliveries(page = 5, pageSize = 1).items.isEmpty())
    }

    @Test
    fun `cleanup retains nonterminal deliveries and recent completions and prunes only inactive old states`() = runBlocking {
        repository.saveSettings(true, 5)
        val first = repository.saveTarget(target(name = "First"))
        val second = repository.saveTarget(target(name = "Second"))
        val third = repository.saveTarget(target(name = "Third"))
        repository.enqueue(event(), first.id)
        repository.complete(repository.claimDue().single(), NotificationDeliveryResult("succeeded", 200))
        val lateEvent = event()
        repository.enqueue(lateEvent, first.id)
        repository.claimDue().single()
        repository.enqueue(event(), second.id)
        repository.claimDue().single()
        repository.enqueue(event(), third.id)
        for (active in listOf(true, false)) {
            val subject = if (active) "active" else "inactive"
            repository.updateAlert(subject, "health") {
                NotificationAlertUpdate(NotificationAlertState(subject, "health", active = active, updatedAt = BASE_TIME))
            }
        }
        clock.addAndGet(29 * DAY)
        repository.cleanup(clock.get())
        assertEquals(4L, repository.deliveries().total)
        clock.addAndGet(2 * DAY)
        val lateCompletion = repository.claimDue(limit = 1).single()
        assertEquals(lateEvent.eventId, lateCompletion.event.eventId)
        assertTrue(repository.complete(lateCompletion, NotificationDeliveryResult("succeeded", 200)))
        repository.cleanup(clock.get())
        val retained = repository.deliveries().items
        assertEquals(3, retained.size)
        assertEquals(setOf("succeeded", "pending", "in_flight"), retained.map { it.status }.toSet())
        assertEquals(3L, eventCount())
        assertNotNull(alert("active", "health"))
        assertNull(alert("inactive", "health"))
    }

    @Test
    fun `template configuration persists and bumps revision only when changed`() = runBlocking {
        val target = repository.saveTarget(target().copy(
            templateTitle = "【{{severity}}】{{channel}}",
            templateBody = "渠道 {{channel}}\n原因：{{reason}}",
        ))
        assertEquals("【{{severity}}】{{channel}}", target.templateTitle)
        assertEquals(target, repository.saveTarget(target))

        val updated = repository.saveTarget(target.copy(templateBody = "新正文 {{eventId}}"))
        assertEquals(target.revision + 1, updated.revision)
        assertEquals("新正文 {{eventId}}", assertNotNull(repository.findTarget(target.id)).templateBody)
    }

    @Test
    fun `credentials are stored as bound ciphertext and updated or cleared independently`() = runBlocking {
        val cipher = CredentialCipher(ByteArray(32) { 4 })
        val target = repository.saveTarget(target())
        val url = cipher.encrypt(
            "https://hooks.example.test/notify",
            NotificationCredentialFields.aad(target.id, NotificationCredentialFields.URL),
        )
        val created = repository.saveTarget(target, NotificationCredentialUpdate(url = url))
        assertEquals(2L, created.revision)
        val stored = assertNotNull(repository.loadCredentials(target.id))
        assertEquals(url, stored.url)
        assertNull(stored.bearerToken)
        assertEquals(
            "https://hooks.example.test/notify",
            cipher.decrypt(
                assertNotNull(stored.url).ciphertext, stored.url!!.nonce,
                NotificationCredentialFields.aad(target.id, NotificationCredentialFields.URL),
            ),
        )

        val bearer = cipher.encrypt(
            "generated-token",
            NotificationCredentialFields.aad(target.id, NotificationCredentialFields.BEARER),
        )
        val updated = repository.saveTarget(created, NotificationCredentialUpdate(bearerToken = bearer))
        assertEquals(3L, updated.revision)
        val afterUpdate = assertNotNull(repository.loadCredentials(target.id))
        assertEquals(url, afterUpdate.url)
        assertEquals(bearer, afterUpdate.bearerToken)

        val cleared = repository.saveTarget(updated, NotificationCredentialUpdate(clearBearerToken = true))
        assertEquals(4L, cleared.revision)
        val afterClear = assertNotNull(repository.loadCredentials(target.id))
        assertEquals(url, afterClear.url)
        assertNull(afterClear.bearerToken)
    }

    @Test
    fun `credential changes bump revision and cancel pending deliveries`() = runBlocking {
        repository.saveSettings(true, 5)
        val cipher = CredentialCipher(ByteArray(32) { 5 })
        val target = repository.saveTarget(target(events = listOf(NotificationEvents.KEY_DISABLED)))
        repository.enqueue(event())
        val task = repository.claimDue().single()
        val updated = repository.saveTarget(
            target,
            NotificationCredentialUpdate(
                url = cipher.encrypt(
                    "https://hooks.example.test/notify",
                    NotificationCredentialFields.aad(target.id, NotificationCredentialFields.URL),
                ),
            ),
        )
        assertEquals(2L, updated.revision)
        assertFalse(repository.isTaskCurrent(task))
        assertFalse(repository.complete(task, NotificationDeliveryResult("succeeded", 200)))
        assertEquals("cancelled", repository.deliveries(target.id).items.single().status)
    }

    @Test
    fun `deleting a target removes its stored credentials`() = runBlocking {
        val cipher = CredentialCipher(ByteArray(32) { 6 })
        val target = repository.saveTarget(target())
        repository.saveTarget(
            target,
            NotificationCredentialUpdate(
                url = cipher.encrypt(
                    "https://hooks.example.test/notify",
                    NotificationCredentialFields.aad(target.id, NotificationCredentialFields.URL),
                ),
            ),
        )
        assertNotNull(repository.loadCredentials(target.id))
        assertTrue(repository.deleteTarget(target.id))
        assertNull(repository.loadCredentials(target.id))
    }

    private fun target(
        name: String = "Target",
        enabled: Boolean = true,
        events: List<String> = NotificationEvents.subscriptions,
        channels: List<String> = emptyList(),
    ) = NotificationTarget(
        id = UUID.randomUUID().toString(),
        name = name,
        type = "webhook",
        enabled = enabled,
        events = events,
        channelIds = channels,
        revision = 0,
    )

    private fun event(type: String = NotificationEvents.KEY_DISABLED, channel: String? = CHANNEL) = NotificationEvent(
        eventId = UUID.randomUUID().toString(),
        eventType = type,
        occurredAt = clock.get(),
        severity = "warning",
        channelId = channel,
    )

    private suspend fun eventCount(): Long = suspendTransaction(factory.database) {
        NotificationEventRecords.selectAll().count()
    }

    private suspend fun alert(subject: String, kind: String): NotificationAlertState? = suspendTransaction(factory.database) {
        NotificationAlertStates.selectAll()
            .where { (NotificationAlertStates.subject eq subject) and (NotificationAlertStates.kind eq kind) }
            .singleOrNull()
            ?.let { row ->
                NotificationAlertState(
                    subject = row[NotificationAlertStates.subject],
                    kind = row[NotificationAlertStates.kind],
                    active = row[NotificationAlertStates.active],
                    state = row[NotificationAlertStates.state],
                    consecutiveFailures = row[NotificationAlertStates.consecutiveFailures],
                    updatedAt = row[NotificationAlertStates.updatedAt],
                )
            }
    }

    private companion object {
        const val BASE_TIME = 1_800_000_000_000L
        const val CHANNEL = "test-channel"
        const val DAY = 86_400_000L
    }
}
