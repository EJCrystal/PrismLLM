package dev.soven.prismllm.api

import dev.soven.prismllm.repo.ModelCredentialRouteDirectory
import dev.soven.prismllm.repo.ModelCredentialRouteDirectorySnapshot
import dev.soven.prismllm.repo.ModelCredentialRouteRevisionChangedException
import dev.soven.prismllm.routing.readmodel.ModelCredentialRouteSnapshot
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mounted inside /admin/api; model IDs remain query/body values because canonical IDs may contain '/'. */
internal fun Route.modelCredentialRoutes(app: GatewayApplication) {
    val directory = ModelCredentialRouteDirectory(app.databaseFactory.database)

    get("/model-routes") {
        call.requireAdmin(app)
        val offset = call.queryInt("offset", default = 0, min = 0)
        val limit = call.queryInt("limit", default = 100, min = 1, max = 500)
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.length > 200 || query.any(Char::isISOControl)) {
            throw badRequest("invalid_model_route_query", "模型路由搜索条件无效")
        }
        val current = directory.load()
        val saved = app.modelCredentialRouteRepository.list().associateBy { it.modelId }
        val modelIds = (current.modelIds + saved.keys).filter { it.contains(query, ignoreCase = true) }.sorted()
        val items = modelIds.drop(offset).take(limit).map { modelId ->
            val route = saved[modelId]
            ModelCredentialRouteListItem(
                modelId = modelId,
                configured = route != null,
                entryCount = route?.entries?.size ?: 0,
                availableCount = current.available(modelId).count { it.selectable },
                revision = route?.revision ?: 0,
            )
        }
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(ModelCredentialRouteListResponse(items, modelIds.size, offset, limit))
    }

    get("/model-routes/detail") {
        call.requireAdmin(app)
        val modelId = validateModelRouteModelId(call.requiredQuery("modelId", "缺少对外模型 ID"))
        val route = app.modelCredentialRouteRepository.find(modelId)
        val current = directory.load()
        if (route == null && modelId !in current.modelIds) throw notFound("model_route_not_found", "对外模型不存在")
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(modelRouteDetail(modelId, route, current))
    }

    put("/model-routes") {
        call.requireAdmin(app)
        val request = call.receive<SaveModelCredentialRouteRequest>()
        val entries = request.identities()
        val saved = try {
            app.modelCredentialRouteRepository.save(request.modelId, request.expectedRevision, entries) { existing ->
                val current = directory.loadInTransaction(entries)
                if (request.modelId !in current.modelIds && !existing.configured) {
                    throw notFound("model_route_not_found", "对外模型不存在")
                }
                val previouslySelected = existing.entries.toSet()
                entries.filter { it !in previouslySelected }.forEach { identity ->
                    val metadata = current.metadata(request.modelId, identity)
                    if (!metadata.selectable) {
                        throw badRequest("invalid_model_route_credential", "新增凭据不可选择：${metadata.reason}")
                    }
                }
            }
        } catch (cause: ModelCredentialRouteRevisionChangedException) {
            throw GatewayError(
                HttpStatusCode.Conflict,
                "model_route_revision_changed",
                cause.message ?: "模型凭据顺序已变化，请刷新后再保存",
                "invalid_request_error",
            )
        }
        call.markAdminAuditDetails(buildJsonObject {
            put("modelId", saved.modelId)
            put("revision", saved.revision)
            put("entryCount", saved.entries.size)
        })
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(modelRouteDetail(saved.modelId, saved, directory.load()))
    }
}

private fun modelRouteDetail(
    modelId: String,
    route: ModelCredentialRouteSnapshot?,
    current: ModelCredentialRouteDirectorySnapshot,
) = ModelCredentialRouteDetailResponse(
    modelId = modelId,
    revision = route?.revision ?: 0,
    configured = route != null,
    entries = route?.entries.orEmpty().map { current.metadata(modelId, it).toResponse() },
    availableCredentials = current.available(modelId).map { it.toResponse() },
)
