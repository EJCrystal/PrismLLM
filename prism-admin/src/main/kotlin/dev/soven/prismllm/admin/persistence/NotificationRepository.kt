package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.NotificationAlertState
import dev.soven.prismllm.admin.domain.NotificationAlertUpdate
import dev.soven.prismllm.admin.domain.NotificationCredentialUpdate
import dev.soven.prismllm.admin.domain.NotificationDelivery
import dev.soven.prismllm.admin.domain.NotificationDeliveryPage
import dev.soven.prismllm.admin.domain.NotificationDeliveryResult
import dev.soven.prismllm.admin.domain.NotificationDeliveryTask
import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.admin.domain.NotificationSettings
import dev.soven.prismllm.admin.domain.NotificationStoredCredentials
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.shared.crypto.CredentialCipher
import dev.soven.prismllm.shared.crypto.EncryptedCredential
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** PostgreSQL persistence for notification configuration, alert state, and leased deliveries. */
class NotificationRepository(
    private val database: Database,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadSettings(): NotificationSettings = suspendTransaction(database) {
        NotificationSettingsTable.selectAll()
            .where { NotificationSettingsTable.id eq SINGLETON_ID }
            .singleOrNull()
            ?.let(::toSettings)
            ?: NotificationSettings()
    }

    suspend fun saveSettings(enabled: Boolean, consecutiveFailureThreshold: Int): NotificationSettings =
        suspendTransaction(database) {
            require(consecutiveFailureThreshold in 1..100) { "invalid_notification_threshold" }
            lockSettings()
            val now = nowMs()
            NotificationSettingsTable.update({ NotificationSettingsTable.id eq SINGLETON_ID }) {
                it[NotificationSettingsTable.enabled] = enabled
                it[NotificationSettingsTable.consecutiveFailureThreshold] = consecutiveFailureThreshold
                it[NotificationSettingsTable.updatedAt] = now
            }
            if (!enabled) cancelDeliveries(now)
            NotificationSettings(enabled, consecutiveFailureThreshold, now)
        }

    suspend fun listTargets(): List<NotificationTarget> = suspendTransaction(database) {
        NotificationTargets.selectAll()
            .orderBy(NotificationTargets.name to SortOrder.ASC, NotificationTargets.id to SortOrder.ASC)
            .map(::toTarget)
    }

    suspend fun findTarget(id: String): NotificationTarget? = suspendTransaction(database) {
        NotificationTargets.selectAll().where { NotificationTargets.id eq id }
            .singleOrNull()?.let(::toTarget)
    }

    /**
     * revision=0 creates; an existing revision is an optimistic update precondition.
     * Credential changes share the same revision guard and cancel pending deliveries.
     * Credentials must already be encrypted by the caller.
     */
    suspend fun saveTarget(
        target: NotificationTarget,
        credentials: NotificationCredentialUpdate? = null,
    ): NotificationTarget = suspendTransaction(database) {
        lockSettings()
        val existing = NotificationTargets.selectAll()
            .where { NotificationTargets.id eq target.id }
            .forUpdate()
            .singleOrNull()
        val now = nowMs()
        val credentialChange = credentials?.takeIf { it.changesAnything }
        if (target.revision == 0L) {
            require(existing == null) { "notification_target_exists" }
            val count = NotificationTargets.selectAll().count()
            require(count < MAX_TARGETS) { "notification_target_limit" }
            val created = target.copy(revision = 1, updatedAt = now)
            NotificationTargets.insert {
                it[id] = created.id
                it[name] = created.name
                it[type] = created.type
                it[enabled] = created.enabled
                it[events] = json.encodeToString(created.events)
                it[channelIds] = json.encodeToString(created.channelIds)
                it[templateTitle] = created.templateTitle
                it[templateBody] = created.templateBody
                it[revision] = created.revision
                it[updatedAt] = created.updatedAt
            }
            if (credentialChange != null) writeCredentials(created.id, credentialChange, now)
            created
        } else {
            require(target.revision > 0) { "notification_target_revision_conflict" }
            val row = existing ?: throw IllegalArgumentException("notification_target_not_found")
            val current = toTarget(row)
            require(current.revision == target.revision) { "notification_target_revision_conflict" }
            if (sameConfiguration(current, target) && credentialChange == null) return@suspendTransaction current
            val updated = target.copy(revision = Math.addExact(current.revision, 1), updatedAt = now)
            NotificationTargets.update({ NotificationTargets.id eq target.id }) {
                it[name] = updated.name
                it[type] = updated.type
                it[enabled] = updated.enabled
                it[events] = json.encodeToString(updated.events)
                it[channelIds] = json.encodeToString(updated.channelIds)
                it[templateTitle] = updated.templateTitle
                it[templateBody] = updated.templateBody
                it[revision] = updated.revision
                it[updatedAt] = updated.updatedAt
            }
            if (credentialChange != null) writeCredentials(updated.id, credentialChange, now)
            cancelTargetDeliveries(target.id, now)
            updated
        }
    }

    /** 读取目标的加密凭据；没有任何凭据行时返回 null。 */
    suspend fun loadCredentials(targetId: String): NotificationStoredCredentials? = suspendTransaction(database) {
        NotificationTargetCredentials.selectAll()
            .where { NotificationTargetCredentials.targetId eq targetId }
            .singleOrNull()
            ?.let(::toStoredCredentials)
    }

    suspend fun deleteTarget(id: String): Boolean = suspendTransaction(database) {
        lockSettings()
        val now = nowMs()
        val deleted = NotificationTargets.deleteWhere { NotificationTargets.id eq id }
        if (deleted > 0) cancelTargetDeliveries(id, now)
        deleted > 0
    }

    /** Use inside a caller-owned Exposed transaction to atomically couple state changes with enqueue. */
    fun enqueueInTransaction(event: NotificationEvent, onlyTargetId: String? = null): List<String> {
        requireTransaction()
        val settings = lockSettings()
        if (!settings.enabled) return emptyList()
        val storedEvent = NotificationEventRecords.selectAll()
            .where { NotificationEventRecords.eventId eq event.eventId }
            .singleOrNull()
        val immutableEvent = storedEvent?.let { json.decodeFromString<NotificationEvent>(it[NotificationEventRecords.payload]) } ?: event
        val test = immutableEvent.eventType == NotificationEvents.TEST
        val targets = NotificationTargets.selectAll().map(::toTarget).filter { target ->
            target.enabled && (onlyTargetId == null || target.id == onlyTargetId) &&
                (test || (immutableEvent.eventType in target.events &&
                    (target.channelIds.isEmpty() || immutableEvent.channelId in target.channelIds)))
        }
        if (targets.isEmpty()) return emptyList()

        val now = nowMs()
        NotificationEventRecords.insertIgnore {
            it[eventId] = event.eventId
            it[eventType] = event.eventType
            it[payload] = json.encodeToString(event)
            it[occurredAt] = event.occurredAt
            it[createdAt] = now
        }
        val stored = NotificationEventRecords.selectAll()
            .where { NotificationEventRecords.eventId eq event.eventId }
            .single()
        val storedType = stored[NotificationEventRecords.eventType]
        targets.forEach { target ->
            NotificationDeliveries.insertIgnore {
                it[id] = UUID.randomUUID().toString()
                it[eventId] = event.eventId
                it[targetId] = target.id
                it[targetName] = target.name
                it[targetRevision] = target.revision
                it[eventType] = storedType
                it[status] = STATUS_PENDING
                it[attempts] = 0
                it[nextAttemptAt] = now
                it[createdAt] = now
                it[updatedAt] = now
                it[lastStatusCode] = null
                it[errorCode] = null
                it[leaseToken] = null
                it[leaseUntil] = null
            }
        }
        return NotificationDeliveries.selectAll()
            .where { (NotificationDeliveries.eventId eq event.eventId) and (NotificationDeliveries.targetId inList targets.map { it.id }) }
            .map { it[NotificationDeliveries.id] }
    }

    suspend fun enqueue(event: NotificationEvent, onlyTargetId: String? = null): List<String> =
        suspendTransaction(database) { enqueueInTransaction(event, onlyTargetId) }

    suspend fun updateAlert(
        subject: String,
        kind: String,
        transition: (NotificationAlertState?) -> NotificationAlertUpdate,
    ): Unit = suspendTransaction(database) {
        val settings = lockSettings()
        if (!settings.enabled) return@suspendTransaction
        val existing = NotificationAlertStates.selectAll()
            .where { (NotificationAlertStates.subject eq subject) and (NotificationAlertStates.kind eq kind) }
            .singleOrNull()
            ?.let(::toAlertState)
        val update = transition(existing)
        require(update.state.subject == subject && update.state.kind == kind) { "notification_alert_identity_mismatch" }
        var next = update.state
        if (update.event != null) {
            val deliveryIds = enqueueInTransaction(update.event)
            if (deliveryIds.isEmpty()) {
                next = when {
                    existing?.active != true && next.active -> next.copy(active = false)
                    existing?.active == true && next.active -> next.copy(state = existing.state)
                    else -> next
                }
            }
        }
        val changed = NotificationAlertStates.update({
            (NotificationAlertStates.subject eq subject) and (NotificationAlertStates.kind eq kind)
        }) {
            it[active] = next.active
            it[state] = next.state
            it[consecutiveFailures] = next.consecutiveFailures
            it[updatedAt] = next.updatedAt
        }
        if (changed == 0) {
            NotificationAlertStates.insert {
                it[NotificationAlertStates.subject] = subject
                it[NotificationAlertStates.kind] = kind
                it[active] = next.active
                it[NotificationAlertStates.state] = next.state
                it[consecutiveFailures] = next.consecutiveFailures
                it[updatedAt] = next.updatedAt
            }
        }
    }

    suspend fun resetStreakCounts(): Unit = suspendTransaction(database) {
        lockSettings()
        NotificationAlertStates.update({ NotificationAlertStates.kind eq ALERT_KIND_STREAK }) {
            it[consecutiveFailures] = 0
        }
        Unit
    }

    suspend fun claimDue(limit: Int = 4, leaseMs: Long = 30_000): List<NotificationDeliveryTask> = suspendTransaction(database) {
        require(limit > 0) { "limit must be positive" }
        require(leaseMs > 0) { "leaseMs must be positive" }
        val settings = lockSettings(shared = true)
        if (!settings.enabled) return@suspendTransaction emptyList()
        val now = nowMs()
        val leaseExpiresAt = Math.addExact(now, leaseMs)
        // Only a target's oldest unfinished delivery may run, including when its retry is not due.
        // The sequence preserves alert/recovery ordering even when both are enqueued in one millisecond.
        val ids = exec(
            """
                SELECT d.id FROM notification_deliveries d
                WHERE ((d.status = 'pending' AND d.next_attempt_at <= ?)
                    OR (d.status = 'in_flight' AND d.lease_until <= ?))
                  AND NOT EXISTS (
                    SELECT 1 FROM notification_deliveries older
                    WHERE older.target_id = d.target_id AND older.queue_order < d.queue_order
                      AND older.status IN ('pending', 'in_flight')
                  )
                ORDER BY d.queue_order
                LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
            """.trimIndent(),
            listOf(LongColumnType() to now, LongColumnType() to now, IntegerColumnType() to limit.coerceAtMost(MAX_CLAIM)),
        ) { result ->
            buildList { while (result.next()) add(result.getString("id")) }
        }.orEmpty()
        if (ids.isEmpty()) return@suspendTransaction emptyList()
        val rows = NotificationDeliveries.selectAll()
            .where { NotificationDeliveries.id inList ids }
            .orderBy(NotificationDeliveries.queueOrder to SortOrder.ASC)
            .toList()
        val tasks = mutableListOf<NotificationDeliveryTask>()
        for (row in rows) {
            val id = row[NotificationDeliveries.id]
            val targetId = row[NotificationDeliveries.targetId]
            val previousAttempts = row[NotificationDeliveries.attempts]
            if (previousAttempts >= MAX_ATTEMPTS) {
                NotificationDeliveries.update({ NotificationDeliveries.id eq id }) {
                    it[status] = STATUS_FAILED
                    it[updatedAt] = now
                    it[leaseToken] = null
                    it[leaseUntil] = null
                    it[errorCode] = row[NotificationDeliveries.errorCode] ?: "lease_exhausted"
                }
                continue
            }
            val target = NotificationTargets.selectAll()
                .where {
                    (NotificationTargets.id eq targetId) and
                        (NotificationTargets.revision eq row[NotificationDeliveries.targetRevision]) and
                        (NotificationTargets.enabled eq true)
                }
                .singleOrNull()
                ?.let(::toTarget)
            val eventRow = NotificationEventRecords.selectAll()
                .where { NotificationEventRecords.eventId eq row[NotificationDeliveries.eventId] }
                .singleOrNull()
            if (target == null || eventRow == null) {
                NotificationDeliveries.update({ NotificationDeliveries.id eq id }) {
                    it[status] = STATUS_CANCELLED
                    it[updatedAt] = now
                    it[leaseToken] = null
                    it[leaseUntil] = null
                }
                continue
            }
            val token = UUID.randomUUID().toString()
            val attempts = previousAttempts + 1
            NotificationDeliveries.update({ NotificationDeliveries.id eq id }) {
                it[status] = STATUS_IN_FLIGHT
                it[NotificationDeliveries.attempts] = attempts
                it[nextAttemptAt] = leaseExpiresAt
                it[updatedAt] = now
                it[leaseToken] = token
                it[NotificationDeliveries.leaseUntil] = leaseExpiresAt
            }
            // Keep the task snapshot consistent with the values just written by this claim.
            tasks += NotificationDeliveryTask(
                delivery = toDelivery(row).copy(
                    status = STATUS_IN_FLIGHT,
                    attempts = attempts,
                    nextAttemptAt = leaseExpiresAt,
                    updatedAt = now,
                ),
                target = target,
                event = json.decodeFromString(eventRow[NotificationEventRecords.payload]),
                leaseToken = token,
            )
        }
        tasks
    }

    suspend fun isTaskCurrent(task: NotificationDeliveryTask): Boolean = suspendTransaction(database) {
        val settings = lockSettings(shared = true)
        if (!settings.enabled) return@suspendTransaction false
        val now = nowMs()
        val targetRevision = task.target.revision
        NotificationTargets.selectAll()
            .where {
                (NotificationTargets.id eq task.target.id) and
                    (NotificationTargets.revision eq targetRevision) and
                    (NotificationTargets.enabled eq true)
            }
            .singleOrNull() ?: return@suspendTransaction false
        NotificationDeliveries.selectAll()
            .where {
                (NotificationDeliveries.id eq task.delivery.id) and
                    (NotificationDeliveries.targetId eq task.target.id) and
                    (NotificationDeliveries.targetRevision eq targetRevision) and
                    (NotificationDeliveries.eventId eq task.event.eventId) and
                    (NotificationDeliveries.status eq STATUS_IN_FLIGHT) and
                    (NotificationDeliveries.leaseToken eq task.leaseToken) and
                    (NotificationDeliveries.leaseUntil greater now)
            }
            .singleOrNull() != null
    }

    suspend fun complete(task: NotificationDeliveryTask, result: NotificationDeliveryResult): Boolean =
        suspendTransaction(database) {
            require(result.status in setOf(STATUS_PENDING, STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED)) {
                "invalid_notification_delivery_status"
            }
            val settings = lockSettings(shared = true)
            if (!settings.enabled) return@suspendTransaction false
            val currentTarget = NotificationTargets.selectAll()
                .where {
                    (NotificationTargets.id eq task.target.id) and
                        (NotificationTargets.revision eq task.target.revision) and
                        (NotificationTargets.enabled eq true)
                }
                .singleOrNull() ?: return@suspendTransaction false
            val now = nowMs()
            val row = NotificationDeliveries.selectAll()
                .where {
                    (NotificationDeliveries.id eq task.delivery.id) and
                        (NotificationDeliveries.targetId eq task.target.id) and
                        (NotificationDeliveries.targetRevision eq currentTarget[NotificationTargets.revision]) and
                        (NotificationDeliveries.eventId eq task.event.eventId) and
                        (NotificationDeliveries.status eq STATUS_IN_FLIGHT) and
                        (NotificationDeliveries.leaseToken eq task.leaseToken) and
                        (NotificationDeliveries.leaseUntil greater now)
                }
                .forUpdate()
                .singleOrNull() ?: return@suspendTransaction false
            val finalStatus = if (result.status == STATUS_PENDING && row[NotificationDeliveries.attempts] >= MAX_ATTEMPTS) {
                STATUS_FAILED
            } else result.status
            NotificationDeliveries.update({
                (NotificationDeliveries.id eq task.delivery.id) and
                    (NotificationDeliveries.status eq STATUS_IN_FLIGHT) and
                    (NotificationDeliveries.leaseToken eq task.leaseToken)
            }) {
                it[status] = finalStatus
                it[NotificationDeliveries.nextAttemptAt] = if (finalStatus == STATUS_PENDING) {
                    result.nextAttemptAt ?: throw IllegalArgumentException("notification_retry_time_required")
                } else now
                it[updatedAt] = now
                it[lastStatusCode] = result.statusCode
                it[errorCode] = result.errorCode?.take(100)
                it[leaseToken] = null
                it[leaseUntil] = null
            } == 1
        }

    suspend fun deliveries(
        targetId: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): NotificationDeliveryPage = suspendTransaction(database) {
        val safePage = page.coerceIn(1, MAX_PAGE)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val query = NotificationDeliveries.selectAll().apply {
            if (targetId != null) andWhere { NotificationDeliveries.targetId eq targetId }
        }
        val total = query.count()
        val offset = (safePage.toLong() - 1L) * safePageSize
        val items = query.orderBy(NotificationDeliveries.createdAt to SortOrder.DESC, NotificationDeliveries.queueOrder to SortOrder.DESC)
            .limit(safePageSize)
            .offset(offset)
            .map(::toDelivery)
        NotificationDeliveryPage(items, total, safePage, safePageSize)
    }

    suspend fun cleanup(before: Long): Unit = suspendTransaction(database) {
        // Cleanup shares the first-lock order with writers; it cannot remove a newly referenced
        // event or a recently refreshed alert from an earlier snapshot.
        lockSettings()
        val retentionCutoff = minOf(before, nowMs() - RETENTION_MS)
        exec(
            """
                DELETE FROM notification_deliveries WHERE id IN (
                    SELECT id FROM notification_deliveries
                    WHERE status IN ('succeeded', 'failed', 'cancelled') AND updated_at < ?
                    ORDER BY updated_at, queue_order LIMIT $MAX_CLEANUP_BATCH
                )
            """.trimIndent(),
            listOf(LongColumnType() to retentionCutoff),
        )
        exec(
            """
                DELETE FROM notification_events WHERE event_id IN (
                    SELECT e.event_id FROM notification_events e
                    WHERE e.created_at < ? AND NOT EXISTS (
                        SELECT 1 FROM notification_deliveries d WHERE d.event_id = e.event_id
                    )
                    ORDER BY e.created_at, e.event_id LIMIT $MAX_CLEANUP_BATCH
                )
            """.trimIndent(),
            listOf(LongColumnType() to retentionCutoff),
        )
        exec(
            """
                DELETE FROM notification_alert_states WHERE (subject, kind) IN (
                    SELECT subject, kind FROM notification_alert_states
                    WHERE active = FALSE AND updated_at < ?
                    ORDER BY updated_at, subject, kind LIMIT $MAX_CLEANUP_BATCH
                )
            """.trimIndent(),
            listOf(LongColumnType() to retentionCutoff),
        )
        Unit
    }

    private fun requireTransaction() {
        check(TransactionManager.current().db === database) { "notification repository transaction database mismatch" }
    }

    private fun lockSettings(shared: Boolean = false): NotificationSettings {
        NotificationSettingsTable.insertIgnore {
            it[id] = SINGLETON_ID
            it[enabled] = false
            it[consecutiveFailureThreshold] = DEFAULT_THRESHOLD
            it[updatedAt] = 0
        }
        val query = NotificationSettingsTable.selectAll().where { NotificationSettingsTable.id eq SINGLETON_ID }
        val row = if (shared) query.forUpdate(ForUpdateOption.PostgreSQL.ForShare).single()
        else query.forUpdate().single()
        return toSettings(row)
    }

    private fun cancelDeliveries(now: Long) {
        NotificationDeliveries.update({
            (NotificationDeliveries.status eq STATUS_PENDING) or (NotificationDeliveries.status eq STATUS_IN_FLIGHT)
        }) {
            it[status] = STATUS_CANCELLED
            it[updatedAt] = now
            it[leaseToken] = null
            it[leaseUntil] = null
        }
    }

    private fun cancelTargetDeliveries(targetId: String, now: Long) {
        NotificationDeliveries.update({
            (NotificationDeliveries.targetId eq targetId) and
                ((NotificationDeliveries.status eq STATUS_PENDING) or (NotificationDeliveries.status eq STATUS_IN_FLIGHT))
        }) {
            it[status] = STATUS_CANCELLED
            it[updatedAt] = now
            it[leaseToken] = null
            it[leaseUntil] = null
        }
    }

    private fun toSettings(row: ResultRow) = NotificationSettings(
        enabled = row[NotificationSettingsTable.enabled],
        consecutiveFailureThreshold = row[NotificationSettingsTable.consecutiveFailureThreshold],
        updatedAt = row[NotificationSettingsTable.updatedAt],
    )

    private fun toTarget(row: ResultRow) = NotificationTarget(
        id = row[NotificationTargets.id],
        name = row[NotificationTargets.name],
        type = row[NotificationTargets.type],
        enabled = row[NotificationTargets.enabled],
        events = json.decodeFromString(row[NotificationTargets.events]),
        channelIds = json.decodeFromString(row[NotificationTargets.channelIds]),
        templateTitle = row[NotificationTargets.templateTitle],
        templateBody = row[NotificationTargets.templateBody],
        revision = row[NotificationTargets.revision],
        updatedAt = row[NotificationTargets.updatedAt],
    )

    private fun toStoredCredentials(row: ResultRow) = NotificationStoredCredentials(
        url = encryptedOrNull(
            row, NotificationTargetCredentials.urlCiphertext,
            NotificationTargetCredentials.urlNonce, NotificationTargetCredentials.urlKeyVersion,
        ),
        bearerToken = encryptedOrNull(
            row, NotificationTargetCredentials.bearerTokenCiphertext,
            NotificationTargetCredentials.bearerTokenNonce, NotificationTargetCredentials.bearerTokenKeyVersion,
        ),
        signingSecret = encryptedOrNull(
            row, NotificationTargetCredentials.signingSecretCiphertext,
            NotificationTargetCredentials.signingSecretNonce, NotificationTargetCredentials.signingSecretKeyVersion,
        ),
    )

    private fun encryptedOrNull(
        row: ResultRow,
        ciphertext: Column<String>,
        nonce: Column<String>,
        keyVersion: Column<Int>,
    ): EncryptedCredential? {
        val value = row[ciphertext]
        if (value.isEmpty()) return null
        return EncryptedCredential(value, row[nonce], row[keyVersion])
    }

    private fun writeCredentials(targetId: String, update: NotificationCredentialUpdate, now: Long) {
        val existing = NotificationTargetCredentials.selectAll()
            .where { NotificationTargetCredentials.targetId eq targetId }
            .forUpdate()
            .singleOrNull()
        val current = existing?.let(::toStoredCredentials) ?: NotificationStoredCredentials()
        val url = applyCredential(update.url, update.clearUrl, current.url)
        val bearerToken = applyCredential(update.bearerToken, update.clearBearerToken, current.bearerToken)
        val signingSecret = applyCredential(update.signingSecret, update.clearSigningSecret, current.signingSecret)
        if (existing == null) {
            NotificationTargetCredentials.insert {
                it[NotificationTargetCredentials.targetId] = targetId
                it[NotificationTargetCredentials.urlCiphertext] = url?.ciphertext.orEmpty()
                it[NotificationTargetCredentials.urlNonce] = url?.nonce.orEmpty()
                it[NotificationTargetCredentials.urlKeyVersion] = url?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[NotificationTargetCredentials.bearerTokenCiphertext] = bearerToken?.ciphertext.orEmpty()
                it[NotificationTargetCredentials.bearerTokenNonce] = bearerToken?.nonce.orEmpty()
                it[NotificationTargetCredentials.bearerTokenKeyVersion] =
                    bearerToken?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[NotificationTargetCredentials.signingSecretCiphertext] = signingSecret?.ciphertext.orEmpty()
                it[NotificationTargetCredentials.signingSecretNonce] = signingSecret?.nonce.orEmpty()
                it[NotificationTargetCredentials.signingSecretKeyVersion] =
                    signingSecret?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[NotificationTargetCredentials.updatedAt] = now
            }
        } else {
            NotificationTargetCredentials.update({ NotificationTargetCredentials.targetId eq targetId }) {
                it[NotificationTargetCredentials.urlCiphertext] = url?.ciphertext.orEmpty()
                it[NotificationTargetCredentials.urlNonce] = url?.nonce.orEmpty()
                it[NotificationTargetCredentials.urlKeyVersion] = url?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[NotificationTargetCredentials.bearerTokenCiphertext] = bearerToken?.ciphertext.orEmpty()
                it[NotificationTargetCredentials.bearerTokenNonce] = bearerToken?.nonce.orEmpty()
                it[NotificationTargetCredentials.bearerTokenKeyVersion] =
                    bearerToken?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[NotificationTargetCredentials.signingSecretCiphertext] = signingSecret?.ciphertext.orEmpty()
                it[NotificationTargetCredentials.signingSecretNonce] = signingSecret?.nonce.orEmpty()
                it[NotificationTargetCredentials.signingSecretKeyVersion] =
                    signingSecret?.keyVersion ?: CredentialCipher.CURRENT_KEY_VERSION
                it[NotificationTargetCredentials.updatedAt] = now
            }
        }
    }

    private fun applyCredential(
        incoming: EncryptedCredential?,
        clear: Boolean,
        current: EncryptedCredential?,
    ): EncryptedCredential? = when {
        clear -> null
        incoming != null -> incoming
        else -> current
    }

    private fun toAlertState(row: ResultRow) = NotificationAlertState(
        subject = row[NotificationAlertStates.subject],
        kind = row[NotificationAlertStates.kind],
        active = row[NotificationAlertStates.active],
        state = row[NotificationAlertStates.state],
        consecutiveFailures = row[NotificationAlertStates.consecutiveFailures],
        updatedAt = row[NotificationAlertStates.updatedAt],
    )

    private fun toDelivery(row: ResultRow) = NotificationDelivery(
        id = row[NotificationDeliveries.id],
        eventId = row[NotificationDeliveries.eventId],
        targetId = row[NotificationDeliveries.targetId],
        targetName = row[NotificationDeliveries.targetName],
        eventType = row[NotificationDeliveries.eventType],
        status = row[NotificationDeliveries.status],
        attempts = row[NotificationDeliveries.attempts],
        nextAttemptAt = row[NotificationDeliveries.nextAttemptAt],
        createdAt = row[NotificationDeliveries.createdAt],
        updatedAt = row[NotificationDeliveries.updatedAt],
        lastStatusCode = row[NotificationDeliveries.lastStatusCode],
        errorCode = row[NotificationDeliveries.errorCode],
    )

    private fun sameConfiguration(a: NotificationTarget, b: NotificationTarget) =
        a.name == b.name && a.type == b.type && a.enabled == b.enabled && a.events == b.events &&
            a.channelIds == b.channelIds && a.templateTitle == b.templateTitle && a.templateBody == b.templateBody

    private companion object {
        const val SINGLETON_ID = 1
        const val DEFAULT_THRESHOLD = 5
        const val MAX_TARGETS = 20
        const val MAX_CLAIM = 100
        const val MAX_ATTEMPTS = 5
        const val MAX_PAGE = 100_000
        const val MAX_PAGE_SIZE = 100
        const val MAX_CLEANUP_BATCH = 1_000
        const val RETENTION_MS = 30L * 86_400_000L
        const val ALERT_KIND_STREAK = "streak"
        const val STATUS_PENDING = "pending"
        const val STATUS_IN_FLIGHT = "in_flight"
        const val STATUS_SUCCEEDED = "succeeded"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
    }
}
