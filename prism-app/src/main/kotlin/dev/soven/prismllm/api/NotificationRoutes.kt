package dev.soven.prismllm.api

import dev.soven.prismllm.admin.domain.NotificationDelivery
import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.admin.domain.NotificationTarget
import dev.soven.prismllm.notification.NotificationCredentialStatus
import dev.soven.prismllm.notification.NotificationSecrets
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

@Serializable
internal data class NotificationTargetRequest(
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val events: List<String> = NotificationEvents.subscriptions,
    val channelIds: List<String> = emptyList(),
    val environmentPrefix: String,
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
    val environmentPrefix: String,
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
        target.id, target.name, target.type, target.enabled, target.events, target.channelIds, target.environmentPrefix,
        target.revision, target.updatedAt, app.notificationSecrets.status(target.environmentPrefix),
        repository.deliveries(targetId = target.id, pageSize = 1).items.firstOrNull(),
    )
    suspend fun saveTarget(value: NotificationTarget): NotificationTarget = try {
        repository.saveTarget(value)
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
    suspend fun target(request: NotificationTargetRequest, id: String): NotificationTarget {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > 100 || name.any { it.isISOControl() }) {
            throw badRequest("invalid_notification_name", "通知目标名称需要 1..100 个字符")
        }
        if (request.type !in setOf("webhook", "feishu")) throw badRequest("invalid_notification_type", "通知类型仅支持 webhook 或 feishu")
        if (!NotificationSecrets.isValidPrefix(request.environmentPrefix)) {
            throw badRequest("invalid_notification_environment", "请填写 PRISM_NOTIFY_ 开头的环境变量前缀，后接 1..48 位大写字母、数字或下划线，首位须为字母")
        }
        if (request.events.isEmpty() || request.events.size > NotificationEvents.subscriptions.size || request.events.any { it !in NotificationEvents.subscriptions }) {
            throw badRequest("invalid_notification_events", "请至少选择一种有效通知事件")
        }
        if (request.channelIds.size > 100 || request.channelIds.any { it.length > 100 }) {
            throw badRequest("invalid_notification_channels", "最多选择 100 个渠道")
        }
        val existing = app.channelRepository.listNames().keys
        if (request.channelIds.any { it !in existing }) throw badRequest("invalid_notification_channels", "所选渠道不存在")
        return NotificationTarget(
            id = id, name = name, type = request.type, enabled = request.enabled,
            events = request.events.distinct(), channelIds = request.channelIds.distinct(), environmentPrefix = request.environmentPrefix,
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
        val saved = saveTarget(target(call.receive(), UUID.randomUUID().toString()).copy(revision = 0))
        call.markAdminAuditDetails(buildJsonObject { put("targetId", saved.id); put("type", saved.type) })
        call.respond(HttpStatusCode.Created, response(saved))
    }
    put("/notification-targets/{id}") {
        call.requireAdmin(app)
        val id = call.requiredParam("id")
        repository.findTarget(id) ?: throw notFound("notification_target_not_found", "通知目标不存在")
        val request = call.receive<NotificationTargetRequest>()
        val revision = request.revision?.takeIf { it > 0 }
            ?: throw badRequest("invalid_notification_revision", "通知目标版本无效，请刷新后重试")
        val updated = target(request, id).copy(revision = revision)
        val saved = saveTarget(updated)
        call.markAdminAuditDetails(buildJsonObject { put("targetId", saved.id); put("enabled", saved.enabled) })
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
        if (!app.notificationSecrets.status(configured.environmentPrefix).urlConfigured) {
            throw badRequest("notification_url_missing", "目标 URL 环境变量尚未配置")
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
