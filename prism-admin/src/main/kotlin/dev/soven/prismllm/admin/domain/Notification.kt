package dev.soven.prismllm.admin.domain

import dev.soven.prismllm.shared.crypto.EncryptedCredential
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

/** Variables accepted in per-target Feishu card templates. */
object NotificationTemplateVariables {
    val names = linkedSetOf(
        "eventType", "severity", "channel", "credential", "model",
        "previousState", "state", "reason", "consecutiveFailures",
        "occurredAt", "eventId", "metrics",
    )

    private val pattern = Regex("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*\\}\\}")

    fun unknown(template: String): Set<String> =
        pattern.findAll(template).map { it.groupValues[1] }.filter { it !in names }.toSet()

    fun render(template: String, values: Map<String, String>): String =
        pattern.replace(template) { match -> values[match.groupValues[1]] ?: match.value }
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
    val templateTitle: String? = null,
    val templateBody: String? = null,
    val revision: Long = 1,
    val updatedAt: Long = 0,
)

/** 通知凭据字段标识；同时用于把密文绑定到目标与字段的 AAD。 */
object NotificationCredentialFields {
    const val URL = "url"
    const val BEARER = "bearerToken"
    const val SIGNATURE = "signingSecret"

    fun aad(targetId: String, field: String): ByteArray =
        "prism-notification:$targetId:$field".toByteArray(Charsets.UTF_8)
}

/** 通知目标已加密保存的接收端凭据；只以密文形式存在。 */
data class NotificationStoredCredentials(
    val url: EncryptedCredential? = null,
    val bearerToken: EncryptedCredential? = null,
    val signingSecret: EncryptedCredential? = null,
)

/**
 * 管理台提交的凭据变更。字段为 null 表示保持原值；clear 标记表示删除。
 * 加密在路由层完成，仓储只接收密文。
 */
data class NotificationCredentialUpdate(
    val url: EncryptedCredential? = null,
    val bearerToken: EncryptedCredential? = null,
    val signingSecret: EncryptedCredential? = null,
    val clearUrl: Boolean = false,
    val clearBearerToken: Boolean = false,
    val clearSigningSecret: Boolean = false,
) {
    val changesAnything: Boolean
        get() = url != null || bearerToken != null || signingSecret != null ||
            clearUrl || clearBearerToken || clearSigningSecret
}

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
