package dev.soven.prismllm.admin.persistence

import org.jetbrains.exposed.v1.core.Table

object NotificationSettingsTable : Table("notification_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val consecutiveFailureThreshold = integer("consecutive_failure_threshold")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object NotificationTargets : Table("notification_targets") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val type = varchar("type", 20)
    val enabled = bool("enabled")
    val events = text("events")
    val channelIds = text("channel_ids")
    val environmentPrefix = varchar("environment_prefix", 64)
    val revision = long("revision")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object NotificationAlertStates : Table("notification_alert_states") {
    val subject = varchar("subject", 200)
    val kind = varchar("kind", 32)
    val active = bool("active")
    val state = varchar("state", 100).nullable()
    val consecutiveFailures = integer("consecutive_failures")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(subject, kind)
}

object NotificationEventRecords : Table("notification_events") {
    val eventId = varchar("event_id", 36)
    val eventType = varchar("event_type", 80)
    val payload = text("payload")
    val occurredAt = long("occurred_at")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(eventId)
}

/** Target names and revisions are snapshots; deleting a target must retain its history. */
object NotificationDeliveries : Table("notification_deliveries") {
    val id = varchar("id", 36)
    val queueOrder = long("queue_order").autoIncrement()
    val eventId = varchar("event_id", 36).references(NotificationEventRecords.eventId)
    val targetId = varchar("target_id", 36)
    val targetName = varchar("target_name", 100)
    val targetRevision = long("target_revision")
    val eventType = varchar("event_type", 80)
    val status = varchar("status", 20)
    val attempts = integer("attempts")
    val nextAttemptAt = long("next_attempt_at")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastStatusCode = integer("last_status_code").nullable()
    val errorCode = varchar("error_code", 100).nullable()
    val leaseToken = varchar("lease_token", 36).nullable()
    val leaseUntil = long("lease_until").nullable()

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(eventId, targetId)
        uniqueIndex(queueOrder)
    }
}
