package dev.soven.llmgateway.api

import dev.soven.llmgateway.repo.AdminAuditRepository
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import java.util.UUID

private val AuditStateKey = AttributeKey<AuditState>("AdminAuditState")
private val AuditActorKey = AttributeKey<String>("AdminAuditActor")
private val AuditErrorCodeKey = AttributeKey<String>("AdminAuditErrorCode")
private val AuditDetailsKey = AttributeKey<JsonObject>("AdminAuditDetails")
private val AuditHandledKey = AttributeKey<Unit>("AdminAuditHandled")
private val auditedMethods = setOf(
    HttpMethod.Post,
    HttpMethod.Put,
    HttpMethod.Patch,
    HttpMethod.Delete,
)

private data class AuditState(
    val requestId: String,
    val action: String,
    val method: String,
    val path: String,
)

/**
 * 管理写操作审计。响应发送时记录最终状态，失败记录不改变原响应。
 */
fun adminAuditPlugin(
    repository: AdminAuditRepository,
    logger: Logger,
    onWriteFailure: () -> Unit,
): ApplicationPlugin<Unit> = createApplicationPlugin("AdminAudit") {
    onCall { call ->
        val path = call.request.path()
        if (!path.startsWith("/admin/api/") || call.request.httpMethod !in auditedMethods) return@onCall
        val requestId = sanitizeRequestId(call.request.headers["X-Request-Id"])
        val state = AuditState(
            requestId = requestId,
            action = auditAction(call.request.httpMethod.value, path),
            method = call.request.httpMethod.value,
            path = path.take(500),
        )
        call.attributes.put(AuditStateKey, state)
        call.response.header("X-Request-Id", requestId)
    }

    on(ResponseBodyReadyForSend) { call, content ->
        val state = call.attributes.getOrNull(AuditStateKey) ?: return@on
        if (call.attributes.contains(AuditHandledKey)) return@on
        val statusCode = call.response.status()?.value
            ?: content.status?.value
            ?: HttpStatusCode.OK.value
        val actor = call.attributes.getOrNull(AuditActorKey)?.take(100)
        if (state.action == "admin.write" && actor == null) return@on
        val errorCode = call.attributes.getOrNull(AuditErrorCodeKey)
            ?: if (statusCode >= 400) "http_$statusCode" else null
        val sourceIp = call.request.origin.remoteAddress.take(100)
        try {
            repository.record(
                actor = actor,
                action = state.action,
                method = state.method,
                path = state.path,
                statusCode = statusCode,
                requestId = state.requestId,
                sourceIp = sourceIp,
                errorCode = errorCode,
                details = call.attributes.getOrNull(AuditDetailsKey),
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            onWriteFailure()
            logger.error(
                "admin audit write failed, action={}, requestId={}",
                state.action,
                state.requestId,
                cause,
            )
        }
    }
}

internal fun ApplicationCall.markAdminAuditActor(username: String) {
    attributes.put(AuditActorKey, username.take(100))
}

internal fun ApplicationCall.adminAuditActor(): String? = attributes.getOrNull(AuditActorKey)

internal fun ApplicationCall.markAdminAuditError(code: String) {
    if (!attributes.contains(AuditErrorCodeKey)) {
        attributes.put(AuditErrorCodeKey, code.take(100))
    }
}

/** 只接收由路由生成的非敏感配置元数据，不能传入原始请求正文。 */
internal fun ApplicationCall.markAdminAuditDetails(details: JsonObject) {
    attributes.put(AuditDetailsKey, details)
}

internal fun ApplicationCall.markAdminAuditHandled() {
    attributes.put(AuditHandledKey, Unit)
}

internal fun auditAction(method: String, path: String): String = when {
    method == "POST" && path == "/admin/api/login" -> "admin.login"
    method == "POST" && path == "/admin/api/logout" -> "admin.logout"
    method == "PUT" && path == "/admin/api/account/avatar" -> "account.avatar.update"
    method == "PUT" && path == "/admin/api/account/password" -> "account.password.update"
    method == "POST" && path == "/admin/api/model-suggestions" -> "admin.model_suggestions"
    method == "POST" && path == "/admin/api/channels/model-discovery" -> "channel.discover_models"
    method == "POST" && CHANNEL_PROBE_PATTERN.matches(path) -> "channel.probe_protocols"
    method == "POST" && CHANNEL_MODEL_TEST_PATTERN.matches(path) -> "channel.model_test"
    method == "POST" && path == "/admin/api/channels" -> "channel.create"
    method == "PUT" && CHANNEL_ITEM_PATTERN.matches(path) -> "channel.update"
    method == "DELETE" && CHANNEL_ITEM_PATTERN.matches(path) -> "channel.delete"
    method == "POST" && path == "/admin/api/client-keys" -> "client_key.create"
    method == "PUT" && CLIENT_KEY_ITEM_PATTERN.matches(path) -> "client_key.update"
    method == "DELETE" && CLIENT_KEY_ITEM_PATTERN.matches(path) -> "client_key.delete"
    method == "POST" && CLIENT_KEY_ROTATE_PATTERN.matches(path) -> "client_key.rotate"
    method == "POST" && CLIENT_KEY_REVOKE_PATTERN.matches(path) -> "client_key.revoke"
    method == "POST" && path == "/admin/api/model-prices" -> "model_price.create"
    method == "POST" && path == "/admin/api/model-prices/batch" -> "model_price.batch"
    method == "PUT" && MODEL_PRICE_ITEM_PATTERN.matches(path) -> "model_price.update"
    method == "DELETE" && MODEL_PRICE_ITEM_PATTERN.matches(path) -> "model_price.delete"
    method == "POST" && path == "/admin/api/model-default-prices" -> "model_default_price.create"
    method == "PUT" && MODEL_DEFAULT_PRICE_ITEM_PATTERN.matches(path) -> "model_default_price.update"
    method == "DELETE" && MODEL_DEFAULT_PRICE_ITEM_PATTERN.matches(path) -> "model_default_price.delete"
    method == "PUT" && path == "/admin/api/request-log-retention" -> "request_log.retention.update"
    method == "POST" && path == "/admin/api/request-log-retention/cleanup" -> "request_log.cleanup"
    method == "PUT" && path == "/admin/api/model-test-settings" -> "model_test_settings.update"
    method == "PUT" && path == "/admin/api/upstream-request-settings" -> "upstream_request_settings.update"
    method == "PUT" && path == "/admin/api/key-auto-disable-settings" -> "key_auto_disable_settings.update"
    else -> "admin.write"
}

private fun sanitizeRequestId(value: String?): String {
    val candidate = value?.trim().orEmpty()
    return if (
        candidate.length in 1..64 &&
        candidate.all { it.isLetterOrDigit() || it in "-_.:" }
    ) {
        candidate
    } else {
        UUID.randomUUID().toString()
    }
}

private val CHANNEL_PROBE_PATTERN = Regex("^/admin/api/channels/[^/]+/probe-protocols$")
private val CHANNEL_MODEL_TEST_PATTERN = Regex("^/admin/api/channels/[^/]+/model-test$")
private val CHANNEL_ITEM_PATTERN = Regex("^/admin/api/channels/[^/]+$")
private val CLIENT_KEY_ITEM_PATTERN = Regex("^/admin/api/client-keys/[^/]+$")
private val CLIENT_KEY_ROTATE_PATTERN = Regex("^/admin/api/client-keys/[^/]+/rotate$")
private val CLIENT_KEY_REVOKE_PATTERN = Regex("^/admin/api/client-keys/[^/]+/revoke$")
private val MODEL_PRICE_ITEM_PATTERN = Regex("^/admin/api/model-prices/[^/]+$")
private val MODEL_DEFAULT_PRICE_ITEM_PATTERN = Regex("^/admin/api/model-default-prices/[^/]+$")
