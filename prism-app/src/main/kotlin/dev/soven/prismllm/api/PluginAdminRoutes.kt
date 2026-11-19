package dev.soven.prismllm.api

import dev.soven.prismllm.plugin.host.PluginExecution
import dev.soven.prismllm.plugin.sdk.DiagnosticInput
import dev.soven.prismllm.shared.error.badRequest
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PluginInstallationRequest(
    val version: String,
    val approvedPermissions: List<String> = emptyList(),
    val expectedSha256: String? = null,
)

@Serializable
data class PluginEnabledRequest(val enabled: Boolean, val approvedPermissions: List<String>? = null)

@Serializable
data class PluginConfigurationRequest(
    val values: JsonObject,
    val expectedRevision: Long? = null,
    val installedVersion: String? = null,
)

@Serializable
data class PluginInvocationContext(val kind: String, val id: String)

@Serializable
data class PluginInvocationRequest(
    val actionId: String,
    val context: PluginInvocationContext,
    val values: JsonObject,
    val expectedActiveVersion: String,
)

/** 市场管理沿用管理会话和 CSRF；插件描述只提供资源名，不能决定请求地址。 */
internal fun Route.pluginAdminRoutes(app: GatewayApplication) {
    get("/plugin-catalog") {
        call.requireAdmin(app)
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(withContext(Dispatchers.IO) { app.pluginRuntime.catalogItems() })
    }
    get("/plugins") {
        call.requireAdmin(app)
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(app.pluginRuntime.states())
    }
    get("/plugins/ui") {
        call.requireAdmin(app)
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(app.pluginRuntime.contributions())
    }
    get("/plugins/{id}") {
        call.requireAdmin(app)
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(app.pluginRuntime.state(call.requiredParam("id", "缺少插件 id")))
    }
    put("/plugins/{id}/installation") {
        call.requireAdmin(app)
        val id = call.requiredParam("id", "缺少插件 id")
        val request = call.receive<PluginInstallationRequest>()
        val expectedSha256 = request.expectedSha256
            ?: throw badRequest("plugin_digest_required", "安装必须提供已审阅目录的 SHA-256 摘要")
        val state = app.pluginRuntime.install(id, request.version, request.approvedPermissions, expectedSha256) {
            if (id == CONNECTIVITY_PLUGIN_ID) {
                buildJsonObject { put("defaultPrompt", app.modelTestSettingsRepository.loadInTransaction().defaultPrompt) }
            } else JsonObject(emptyMap())
        }
        call.markAdminAuditDetails(buildJsonObject {
            put("pluginId", id)
            put("version", request.version)
        })
        call.respond(state)
    }
    put("/plugins/{id}/enabled") {
        call.requireAdmin(app)
        val id = call.requiredParam("id", "缺少插件 id")
        val request = call.receive<PluginEnabledRequest>()
        val state = app.pluginRuntime.setEnabled(id, request.enabled, request.approvedPermissions)
        call.markAdminAuditDetails(buildJsonObject { put("pluginId", id); put("enabled", request.enabled) })
        call.respond(state)
    }
    put("/plugins/{id}/configuration") {
        call.requireAdmin(app)
        val id = call.requiredParam("id", "缺少插件 id")
        val request = call.receive<PluginConfigurationRequest>()
        val expectedRevision = request.expectedRevision
            ?: throw badRequest("plugin_revision_required", "保存配置必须提供草稿基准 revision")
        val installedVersion = request.installedVersion
            ?: throw badRequest("plugin_version_required", "保存配置必须提供草稿所属安装版本")
        val state = app.pluginRuntime.configure(id, request.values, expectedRevision, installedVersion)
        call.markAdminAuditDetails(buildJsonObject { put("pluginId", id) })
        call.respond(state)
    }
    delete("/plugins/{id}") {
        call.requireAdmin(app)
        val id = call.requiredParam("id", "缺少插件 id")
        val state = app.pluginRuntime.uninstall(id)
        call.markAdminAuditDetails(buildJsonObject { put("pluginId", id) })
        call.respond(state)
    }
    get("/plugins/{id}/resources/model-test-targets") {
        call.requireAdmin(app)
        val id = call.requiredParam("id", "缺少插件 id")
        app.requirePluginEntry(id, call.request.queryParameters["contextKind"])
        val kind = call.request.queryParameters["contextKind"]
        val contextId = call.request.queryParameters["contextId"]
        val targets = when (kind) {
            null, "channel" -> app.modelTestTargets().filter { contextId.isNullOrBlank() || it.channelId == contextId }
            "oauth-account" -> app.accountPluginTargets(contextId ?: throw badRequest("invalid_plugin_context", "缺少账号 id"))
            else -> throw badRequest("invalid_plugin_context", "插件目标类型无效")
        }
        call.respond(targets)
    }
    post("/plugins/{id}/invocations") {
        call.requireAdmin(app)
        val id = call.requiredParam("id", "缺少插件 id")
        val request = call.receive<PluginInvocationRequest>()
        val result: PluginExecution = app.executePluginProbe(
            id, request.expectedActiveVersion, request.context,
            DiagnosticInput(request.actionId, request.values),
        )
        call.markAdminAuditDetails(buildJsonObject {
            put("pluginId", id)
            put("model", result.target.model)
            put("protocol", result.target.protocol)
            put("success", result.outcome.success)
        })
        call.respond(result)
    }
}
