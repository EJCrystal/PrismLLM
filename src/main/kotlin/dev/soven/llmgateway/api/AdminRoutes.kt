package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.proxy.fetchUpstreamModels
import dev.soven.llmgateway.repo.NewCandidate
import dev.soven.llmgateway.security.AdminSessions
import dev.soven.llmgateway.security.ClientKeyHasher
import dev.soven.llmgateway.security.PasswordHasher
import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 管理面 API。所有写操作要求会话 Cookie 与 CSRF Token 同时有效。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun Route.adminRoutes(app: GatewayApplication) {
    route("/admin/api") {
        post("/login") {
            val request = call.receive<LoginRequest>()
            val user = app.adminUserRepository.findByUsername(request.username)
            if (user == null || !PasswordHasher.verify(request.password, user.passwordHash)) {
                throw GatewayException(
                    HttpStatusCode.Unauthorized,
                    "invalid_credentials",
                    "用户名或密码错误",
                    "authentication_error",
                )
            }
            val issued = app.sessions.create(user.username)
            val maxAge = 12 * 60 * 60
            call.response.cookies.append(
                Cookie(
                    name = "gw_session",
                    value = issued.token,
                    maxAge = maxAge,
                    path = "/",
                    httpOnly = true,
                    secure = app.config.cookieSecure,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.response.cookies.append(
                Cookie(
                    name = "gw_csrf",
                    value = issued.session.csrf,
                    maxAge = maxAge,
                    path = "/",
                    httpOnly = false,
                    secure = app.config.cookieSecure,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.respond(MeResponse(user.username))
        }

        post("/logout") {
            call.requireAdmin(app)
            app.sessions.remove(call.request.cookies["gw_session"])
            call.response.cookies.append(Cookie("gw_session", "", maxAge = 0, path = "/", httpOnly = true))
            call.response.cookies.append(Cookie("gw_csrf", "", maxAge = 0, path = "/"))
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val session = call.requireAdmin(app)
            call.respond(MeResponse(session.username))
        }

        get("/channels") {
            call.requireAdmin(app)
            call.respond(app.channelRepository.list().map { it.toResponse() })
        }

        get("/channels/{id}/models") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少渠道 id")
            val channel = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            val models = fetchUpstreamModels(channel, app.credentialCipher, app.httpClient)
                .map { ChannelModelResponse(id = it.id, ownedBy = it.ownedBy) }
            call.respond(ChannelModelsResponse(data = models))
        }

        post("/channels") {
            call.requireAdmin(app)
            val request = call.receive<ChannelRequest>()
            validateChannel(request, credentialRequired = true)
            if (app.channelRepository.findByName(request.name) != null) {
                throw conflict("channel_name_exists", "渠道名称已存在")
            }
            val encrypted = app.credentialCipher.encrypt(request.credential!!)
            val record = app.channelRepository.create(
                name = request.name,
                baseUrl = request.baseUrl,
                credential = encrypted,
                authType = request.authType,
                enabled = request.enabled,
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/channels/{id}") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少渠道 id")
            val request = call.receive<ChannelRequest>()
            validateChannel(request, credentialRequired = false)
            val existing = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            val sameName = app.channelRepository.findByName(request.name)
            if (sameName != null && sameName.id != id) throw conflict("channel_name_exists", "渠道名称已存在")
            val encrypted = request.credential?.takeIf { it.isNotBlank() }?.let(app.credentialCipher::encrypt)
            val updated = app.channelRepository.update(
                id = existing.id,
                name = request.name,
                baseUrl = request.baseUrl,
                credential = encrypted,
                authType = request.authType,
                enabled = request.enabled,
            ) ?: throw notFound("channel_not_found", "渠道不存在")
            call.respond(updated.toResponse())
        }

        delete("/channels/{id}") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少渠道 id")
            if (app.aliasRepository.isChannelInUse(id)) {
                throw conflict("channel_in_use", "渠道仍被模型别名引用，不能删除")
            }
            if (!app.channelRepository.delete(id)) throw notFound("channel_not_found", "渠道不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/aliases") {
            call.requireAdmin(app)
            call.respond(app.aliasRepository.list().map { it.toResponse() })
        }

        post("/aliases") {
            call.requireAdmin(app)
            val request = call.receive<AliasRequest>()
            validateAlias(app, request)
            if (app.aliasRepository.findByAlias(request.alias) != null) {
                throw conflict("alias_exists", "模型别名已存在")
            }
            val record = app.aliasRepository.create(
                alias = request.alias,
                enabled = request.enabled,
                candidates = request.candidates.map { NewCandidate(it.channelId, it.upstreamModel) },
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/aliases/{id}") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少别名 id")
            val request = call.receive<AliasRequest>()
            validateAlias(app, request)
            val sameAlias = app.aliasRepository.findByAlias(request.alias)
            if (sameAlias != null && sameAlias.id != id) throw conflict("alias_exists", "模型别名已存在")
            val record = app.aliasRepository.update(
                id = id,
                alias = request.alias,
                enabled = request.enabled,
                candidates = request.candidates.map { NewCandidate(it.channelId, it.upstreamModel) },
            ) ?: throw notFound("alias_not_found", "模型别名不存在")
            call.respond(record.toResponse())
        }

        delete("/aliases/{id}") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少别名 id")
            if (!app.aliasRepository.delete(id)) throw notFound("alias_not_found", "模型别名不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/client-keys") {
            call.requireAdmin(app)
            call.respond(app.clientKeyRepository.list().map { it.toResponse() })
        }

        post("/client-keys") {
            call.requireAdmin(app)
            val request = call.receive<ClientKeyRequest>()
            if (request.name.isBlank()) throw badRequest("invalid_client_key", "密钥名称不能为空")
            if (request.expiresAt != null && request.expiresAt <= System.currentTimeMillis()) {
                throw badRequest("invalid_client_key", "过期时间必须晚于当前时间")
            }
            val generated = ClientKeyHasher.generate()
            val record = app.clientKeyRepository.create(
                name = request.name,
                keyHash = generated.hash,
                keyPrefix = generated.prefix,
                enabled = request.enabled,
                expiresAt = request.expiresAt,
            )
            call.respond(
                HttpStatusCode.Created,
                ClientKeyCreatedResponse(
                    id = record.id,
                    name = record.name,
                    key = generated.raw,
                    keyPrefix = record.keyPrefix,
                    enabled = record.enabled,
                    expiresAt = record.expiresAt,
                    createdAt = record.createdAt,
                ),
            )
        }

        put("/client-keys/{id}") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少密钥 id")
            val request = call.receive<ClientKeyRequest>()
            if (request.name.isBlank()) throw badRequest("invalid_client_key", "密钥名称不能为空")
            if (request.expiresAt != null && request.expiresAt <= System.currentTimeMillis()) {
                throw badRequest("invalid_client_key", "过期时间必须晚于当前时间")
            }
            val record = app.clientKeyRepository.update(
                id = id,
                name = request.name,
                enabled = request.enabled,
                expiresAt = request.expiresAt,
            ) ?: throw notFound("client_key_not_found", "调用方密钥不存在")
            call.respond(record.toResponse())
        }

        delete("/client-keys/{id}") {
            call.requireAdmin(app)
            val id = call.parameters["id"] ?: throw badRequest("missing_id", "缺少密钥 id")
            if (!app.clientKeyRepository.delete(id)) throw notFound("client_key_not_found", "调用方密钥不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/request-logs") {
            call.requireAdmin(app)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val beforeId = call.request.queryParameters["beforeId"]?.toLongOrNull()
            call.respond(app.requestLogRepository.list(limit, beforeId).map { it.toResponse() })
        }
    }
}

/**
 * 管理界面静态资源。未知路径回退到 index.html，交给前端路由处理。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun Route.adminUi(app: GatewayApplication) {
    val root = app.config.staticDir.toAbsolutePath().normalize()
    get("/admin") { call.respondAdminFile(root, "") }
    get("/admin/") { call.respondAdminFile(root, "") }
    get("/admin/{path...}") {
        val relative = call.parameters.getAll("path")?.joinToString("/") ?: ""
        if (relative.startsWith("api/")) throw notFound("not_found", "资源不存在")
        call.respondAdminFile(root, relative)
    }
}

private suspend fun ApplicationCall.respondAdminFile(root: Path, relative: String) {
    val resolved = root.resolve(relative).normalize()
    if (!resolved.startsWith(root)) throw notFound("not_found", "资源不存在")
    val file = if (resolved.toFile().isFile) resolved else root.resolve("index.html")
    if (!file.toFile().isFile) throw notFound("ui_not_built", "管理界面尚未构建")
    respondFile(file.toFile())
}

private fun ApplicationCall.requireAdmin(app: GatewayApplication): AdminSessions.Session {
    val token = request.cookies["gw_session"] ?: throw unauthorized("管理面未登录")
    val session = app.sessions.get(token) ?: throw unauthorized("管理面会话已过期")
    if (request.httpMethod != HttpMethod.Get && request.httpMethod != HttpMethod.Head) {
        val csrf = request.headers["X-CSRF-Token"] ?: throw forbidden("缺少 CSRF Token")
        if (!MessageDigest.isEqual(csrf.toByteArray(Charsets.UTF_8), session.csrf.toByteArray(Charsets.UTF_8))) {
            throw forbidden("CSRF Token 无效")
        }
    }
    return session
}

private fun validateChannel(request: ChannelRequest, credentialRequired: Boolean) {
    if (request.name.isBlank()) throw badRequest("invalid_channel", "渠道名称不能为空")
    if (!request.baseUrl.startsWith("http://") && !request.baseUrl.startsWith("https://")) {
        throw badRequest("invalid_channel", "渠道地址必须以 http:// 或 https:// 开头")
    }
    if (request.authType != "bearer") throw badRequest("invalid_channel", "v1 只支持 bearer 凭据")
    if (credentialRequired && request.credential.isNullOrBlank()) {
        throw badRequest("invalid_channel", "新建渠道必须提供上游凭据")
    }
}

private suspend fun validateAlias(app: GatewayApplication, request: AliasRequest) {
    if (!ALIAS_PATTERN.matches(request.alias)) {
        throw badRequest("invalid_alias", "模型别名只能包含字母、数字、点、下划线和短横线，长度 1..100")
    }
    if (request.candidates.isEmpty()) throw badRequest("invalid_alias", "模型别名至少需要一个候选")
    request.candidates.forEach { candidate ->
        if (candidate.upstreamModel.isBlank()) throw badRequest("invalid_alias", "上游模型名不能为空")
        if (app.channelRepository.find(candidate.channelId) == null) {
            throw badRequest("invalid_alias", "候选渠道 ${candidate.channelId} 不存在")
        }
    }
}

private fun conflict(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.Conflict, code, message, "invalid_request_error")

private val ALIAS_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
