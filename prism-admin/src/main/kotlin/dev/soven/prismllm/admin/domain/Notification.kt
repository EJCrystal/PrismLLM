package dev.soven.prismllm.admin.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object NotificationEvents {
    const val CHANNEL_UNHEALTHY = "channel.unhealthy"
    const val CHANNEL_RECOVERED = "channel.recovered"
    const val KEY_DISABLED = "key.disabled"
    const val KEY_RECOVERED = "key.recovered"
    const val CONSECUTIVE_FAILURES = "attempt.consecutive_failures"
    const val CONSECUTIVE_FAILURES_RECOVERED = "attempt.recovered"
    const val TEST = "notification.test"

    val subscriptions = listOf(
        CHANNEL_UNHEALTHY, CHANNEL_RECOVERED, KEY_DISABLED, KEY_RECOVERED,
        CONSECUTIVE_FAILURES, CONSECUTIVE_FAILURES_RECOVERED,
    )
}

@Serializable
data class NotificationSettings(
    val enabled: Boolean = false,
    val consecutiveFailureThreshold: Int = 5,
    val updatedAt: Long = 0,
)

@Serializable
data class NotificationTarget(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val events: List<String> = NotificationEvents.subscriptions,
    val channelIds: List<String> = emptyList(),
    val environmentPrefix: String,
    val revision: Long = 1,
    val updatedAt: Long = 0,
)

@Serializable
data class NotificationEvent(
    val schemaVersion: Int = 1,
    val eventId: String,
    val eventType: String,
    val occurredAt: Long,
    val severity: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val credentialId: String? = null,
    val credentialName: String? = null,
    val upstreamModel: String? = null,
    val previousState: String? = null,
    val state: String? = null,
    val reasonCode: String? = null,
    val consecutiveFailures: Int? = null,
    val metrics: JsonObject? = null,
)

data class NotificationAlertState(
    val subject: String,
    val kind: String,
    val active: Boolean = false,
    val state: String? = null,
    val consecutiveFailures: Int = 0,
    val updatedAt: Long = 0,
)

data class NotificationAlertUpdate(
    val state: NotificationAlertState,
    val event: NotificationEvent? = null,
)

@Serializable
data class NotificationDelivery(
    val id: String,
    val eventId: String,
    val targetId: String,
    val targetName: String,
    val eventType: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastStatusCode: Int? = null,
    val errorCode: String? = null,
)

@Serializable
data class NotificationDeliveryPage(
    val items: List<NotificationDelivery>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

data class NotificationDeliveryTask(
    val delivery: NotificationDelivery,
    val target: NotificationTarget,
    val event: NotificationEvent,
    val leaseToken: String,
)

data class NotificationDeliveryResult(
    val status: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val nextAttemptAt: Long? = null,
)
