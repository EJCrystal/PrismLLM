package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.NotificationCredentialFields
import dev.soven.prismllm.admin.domain.NotificationCredentialUpdate
import dev.soven.prismllm.admin.domain.NotificationDelivery
import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.notification.NotificationCredentialStatus
import dev.soven.prismllm.notification.NotificationUrlPolicy
import dev.soven.prismllm.notification.NotificationUrlRejected
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
internal data class NotificationSettingsRequest(val enabled: Boolean, val consecutiveFailureThreshold: Int = 5)

/** Write-only credentials; empty fields keep the stored value and clear flags delete it. */
@Serializable
internal data class NotificationCredentialWrite(
    val url: String? = null,
    val bearerToken: String? = null,
    val signingSecret: String? = null,
    val clearUrl: Boolean = false,
    val clearBearerToken: Boolean = false,
    val clearSigningSecret: Boolean = false,
)

@Serializable
internal data class NotificationTargetRequest(
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val events: List<String> = NotificationEvents.subscriptions,
    val channelIds: List<String> = emptyList(),
    val credentials: NotificationCredentialWrite? = null,
    val revision: Long? = null,
)

@Serializable
internal data class NotificationTargetResponse(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val events: List<String>,
    val channelIds: List<String>,
    val revision: Long,
    val updatedAt: Long,
    val credentials: NotificationCredentialStatus,
    val latestDelivery: NotificationDelivery? = null,
)

@Serializable
internal data class NotificationTestResponse(val deliveryId: String, val eventId: String)

internal fun Route.notificationRoutes(app: GatewayApplication) {
    val repository = app.notificationRepository
    suspend fun response(target: NotificationTarget): NotificationTargetResponse = NotificationTargetResponse(
        target.id, target.name, target.type, target.enabled, target.events, target.channelIds,
        target.revision, target.updatedAt, app.notificationCredentials.status(target),
        repository.deliveries(targetId = target.id, pageSize = 1).items.firstOrNull(),
    )
    suspend fun saveTarget(
        value: NotificationTarget,
        credentials: NotificationCredentialUpdate?,
    ): NotificationTarget = try {
        repository.saveTarget(value, credentials)
    } catch (cause: IllegalArgumentException) {
        when (cause.message) {
            "notification_target_not_found" -> throw notFound("notification_target_not_found", "通知目标不存在")
            "notification_target_limit" -> throw badRequest("notification_target_limit", "最多配置 20 个通知目标")
            "notification_target_revision_conflict", "notification_target_exists" -> throw dev.soven.prismllm.shared.error.GatewayError(
                HttpStatusCode.Conflict, "notification_target_changed", "通知目标已变化，请刷新后重试", "api_error",
            )
            else -> throw cause
        }
    }
    fun credentialText(raw: String?, label: String, maxLength: Int): String? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        if (value.length > maxLength) throw badRequest("invalid_notification_credentials", "$label 不能超过 $maxLength 个字符")
        if (value.any { it.isISOControl() }) throw badRequest("invalid_notification_credentials", "$label 不能包含控制字符")
        return value
    }
    fun credentialUpdate(request: NotificationCredentialWrite?, targetId: String): NotificationCredentialUpdate? {
        if (request == null) return null
        val url = credentialText(request.url, "接收端 URL", 2_048)?.trim()
        val bearerToken = credentialText(request.bearerToken, "Bearer 令牌", 4_096)
        val signingSecret = credentialText(request.signingSecret, "签名密钥", 4_096)
        if (url != null && request.clearUrl) throw badRequest("invalid_notification_credentials", "URL 不能同时填写并清除")
        if (bearerToken != null && request.clearBearerToken) throw badRequest("invalid_notification_credentials", "Bearer 令牌不能同时填写并清除")
        if (signingSecret != null && request.clearSigningSecret) throw badRequest("invalid_notification_credentials", "签名密钥不能同时填写并清除")
        if (bearerToken != null && bearerToken.any { it.code !in 0x21..0x7e }) {
            throw badRequest("invalid_notification_credentials", "Bearer 令牌只能包含可打印 ASCII 字符")
        }
        if (signingSecret != null && signingSecret.any { it.code !in 0x21..0x7e }) {
            throw badRequest("invalid_notification_credentials", "签名密钥只能包含可打印 ASCII 字符")
        }
        if (url != null) {
            try {
                NotificationUrlPolicy.parse(url)
            } catch (_: NotificationUrlRejected) {
                throw badRequest("invalid_notification_url", "接收端 URL 仅支持 HTTP/HTTPS 公网地址，且不能包含账号、片段或本机地址")
            }
        }
        val update = NotificationCredentialUpdate(
            url = url?.let {
                app.credentialCipher.encrypt(it, NotificationCredentialFields.aad(targetId, NotificationCredentialFields.URL))
            },
            bearerToken = bearerToken?.let {
                app.credentialCipher.encrypt(it, NotificationCredentialFields.aad(targetId, NotificationCredentialFields.BEARER))
            },
            signingSecret = signingSecret?.let {
                app.credentialCipher.encrypt(it, NotificationCredentialFields.aad(targetId, NotificationCredentialFields.SIGNATURE))
            },
            clearUrl = request.clearUrl,
            clearBearerToken = request.clearBearerToken,
            clearSigningSecret = request.clearSigningSecret,
        )
        return update.takeIf { it.changesAnything }
    }
    suspend fun target(request: NotificationTargetRequest): NotificationTarget {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > 100 || name.any { it.isISOControl() }) {
            throw badRequest("invalid_notification_name", "通知目标名称需要 1..100 个字符")
        }
        if (request.type !in setOf("webhook", "feishu")) throw badRequest("invalid_notification_type", "通知类型仅支持 webhook 或 feishu")
        if (request.events.isEmpty() || request.events.size > NotificationEvents.subscriptions.size || request.events.any { it !in NotificationEvents.subscriptions }) {
            throw badRequest("invalid_notification_events", "请至少选择一种有效通知事件")
        }
        if (request.channelIds.size > 100 || request.channelIds.any { it.length > 100 }) {
            throw badRequest("invalid_notification_channels", "最多选择 100 个渠道")
        }
        val existing = app.channelRepository.listNames().keys
        if (request.channelIds.any { it !in existing }) throw badRequest("invalid_notification_channels", "所选渠道不存在")
        return NotificationTarget(
            id = "", name = name, type = request.type, enabled = request.enabled,
            events = request.events.distinct(), channelIds = request.channelIds.distinct(),
        )
    }

    get("/notification-settings") {
        call.requireAdmin(app)
        call.respond(repository.loadSettings())
    }
    put("/notification-settings") {
        call.requireAdmin(app)
        val request = call.receive<NotificationSettingsRequest>()
        if (request.consecutiveFailureThreshold !in 1..100) throw badRequest("invalid_notification_threshold", "连续失败阈值须在 1..100 之间")
        val saved = app.notificationRuntime.updateSettings(request.enabled, request.consecutiveFailureThreshold)
        call.markAdminAuditDetails(buildJsonObject {
            put("enabled", saved.enabled)
            put("consecutiveFailureThreshold", saved.consecutiveFailureThreshold)
        })
        call.respond(saved)
    }
    get("/notification-targets") {
        call.requireAdmin(app)
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(repository.listTargets().map { response(it) })
    }
    post("/notification-targets") {
        call.requireAdmin(app)
        if (repository.listTargets().size >= 20) throw badRequest("notification_target_limit", "最多配置 20 个通知目标")
        val request = call.receive<NotificationTargetRequest>()
        val id = UUID.randomUUID().toString()
        val credentials = credentialUpdate(request.credentials, id)
        val saved = saveTarget(target(request).copy(id = id, revision = 0), credentials)
        call.markAdminAuditDetails(buildJsonObject {
            put("targetId", saved.id)
            put("type", saved.type)
            put("credentialsChanged", credentials != null)
        })
        call.respond(HttpStatusCode.Created, response(saved))
    }
    put("/notification-targets/{id}") {
        call.requireAdmin(app)
        val id = call.requiredParam("id")
        repository.findTarget(id) ?: throw notFound("notification_target_not_found", "通知目标不存在")
        val request = call.receive<NotificationTargetRequest>()
        val revision = request.revision?.takeIf { it > 0 }
            ?: throw badRequest("invalid_notification_revision", "通知目标版本无效，请刷新后重试")
        val credentials = credentialUpdate(request.credentials, id)
        val saved = saveTarget(target(request).copy(id = id, revision = revision), credentials)
        call.markAdminAuditDetails(buildJsonObject {
            put("targetId", saved.id)
            put("enabled", saved.enabled)
            put("credentialsChanged", credentials != null)
        })
        call.respond(response(saved))
    }
    delete("/notification-targets/{id}") {
        call.requireAdmin(app)
        val id = call.requiredParam("id")
        if (!repository.deleteTarget(id)) throw notFound("notification_target_not_found", "通知目标不存在")
        call.markAdminAuditDetails(buildJsonObject { put("targetId", id) })
        call.respond(HttpStatusCode.NoContent)
    }
    post("/notification-targets/{id}/test") {
        call.requireAdmin(app)
        val id = call.requiredParam("id")
        val configured = repository.findTarget(id) ?: throw notFound("notification_target_not_found", "通知目标不存在")
        val credentials = app.notificationCredentials.status(configured)
        if (credentials.urlUnreadable || credentials.bearerTokenUnreadable || credentials.signingSecretUnreadable) {
            throw badRequest("notification_credentials_unreadable", "目标凭据无法解密，请在管理台重新填写")
        }
        if (!credentials.urlConfigured) {
            throw badRequest("notification_url_missing", "请先在管理台填写接收端 URL")
        }
        val event = NotificationEvent(
            eventId = UUID.randomUUID().toString(), eventType = NotificationEvents.TEST,
            occurredAt = System.currentTimeMillis(), severity = "info", reasonCode = "admin_test",
        )
        val deliveryId = repository.enqueue(event, onlyTargetId = id).firstOrNull()
            ?: throw badRequest("notification_disabled", "请先启用通知及此目标")
        call.markAdminAuditDetails(buildJsonObject { put("targetId", id); put("eventId", event.eventId) })
        call.respond(HttpStatusCode.Accepted, NotificationTestResponse(deliveryId, event.eventId))
    }
    get("/notification-deliveries") {
        call.requireAdmin(app)
        val targetId = call.request.queryParameters["targetId"]?.takeIf { it.isNotBlank() }
        if (targetId != null && targetId.length > 100) throw badRequest("invalid_notification_target", "通知目标无效")
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(repository.deliveries(
            targetId = targetId,
            page = call.queryInt("page", default = 1, min = 1, max = 100_000),
            pageSize = call.queryInt("pageSize", default = 20, min = 1, max = 100),
        ))
    }
}
