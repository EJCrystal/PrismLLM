package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.proxy.CatalogModel
import dev.soven.llmgateway.proxy.fetchUpstreamModels
import dev.soven.llmgateway.proxy.probeUpstreamProtocols
import dev.soven.llmgateway.repo.NewCandidate
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.parseDailyCostLimit
import dev.soven.llmgateway.report.UsageReportQuery
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

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
            val (user, issued) = app.sessions.withUsernameLock(request.username) {
                val user = app.adminUserRepository.findByUsername(request.username)
                if (user == null || !PasswordHasher.verify(request.password, user.passwordHash)) {
                    throw GatewayException(
                        HttpStatusCode.Unauthorized,
                        "invalid_credentials",
                        "用户名或密码错误",
                        "authentication_error",
                    )
                }
                user to app.sessions.create(user.username)
            }
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
            call.markAdminAuditActor(user.username)
            call.respond(MeResponse(user.username, user.avatarDataUrl))
        }

        post("/logout") {
            call.requireAdmin(app)
            app.sessions.remove(call.request.cookies["gw_session"])
            call.clearAdminCookies(app)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/me") {
            val session = call.requireAdmin(app)
            val user = app.adminUserRepository.findByUsername(session.username)
                ?: throw adminUnauthorized("管理员账号不存在")
            call.respond(MeResponse(user.username, user.avatarDataUrl))
        }

        get("/audit-logs") {
            call.requireAdmin(app)
            val limit = call.queryInt("limit", default = 100, min = 1, max = 500)
            val beforeId = call.queryLongOrNull("beforeId")
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(app.adminAuditRepository.list(limit, beforeId).map { it.toResponse() })
        }

        put("/account/avatar") {
            val session = call.requireAdmin(app)
            val request = call.receive<AvatarRequest>()
            val avatarDataUrl = validateAvatarDataUrl(request.avatarDataUrl)
            val user = app.adminUserRepository.updateAvatar(session.username, avatarDataUrl)
                ?: throw notFound("admin_not_found", "管理员账号不存在")
            call.respond(MeResponse(user.username, user.avatarDataUrl))
        }

        put("/account/password") {
            val session = call.requireAdmin(app)
            val request = call.receive<ChangePasswordRequest>()
            app.sessions.withUsernameLock(session.username) {
                call.requireAdmin(app)
                val user = app.adminUserRepository.findByUsername(session.username)
                    ?: throw notFound("admin_not_found", "管理员账号不存在")
                if (!PasswordHasher.verify(request.currentPassword, user.passwordHash)) {
                    throw badRequest("invalid_current_password", "当前密码不正确")
                }
                if (request.newPassword.length !in 8..128) {
                    throw badRequest("invalid_password", "新密码长度必须在 8 到 128 个字符之间")
                }
                if (request.newPassword == request.currentPassword) {
                    throw badRequest("password_unchanged", "新密码不能与当前密码相同")
                }
                if (!app.adminUserRepository.updatePassword(session.username, PasswordHasher.hash(request.newPassword))) {
                    throw notFound("admin_not_found", "管理员账号不存在")
                }
                app.sessions.removeAllForUsername(session.username)
            }
            call.clearAdminCookies(app)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/exchange-rates") {
            call.requireAdmin(app)
            call.respond(app.currencyExchangeService.getRates())
        }

        get("/exposed-models") {
            call.requireAdmin(app)
            call.respond(
                ExposedModelsResponse(
                    data = listExposedModels(app).map {
                        ExposedModelResponse(
                            id = it.id,
                            source = it.source.value,
                            canonicalModelId = it.canonicalModelId,
                            upstreamModels = it.upstreamModels,
                            channelNames = it.channelNames,
                        )
                    },
                ),
            )
        }

        post("/model-suggestions") {
            call.requireAdmin(app)
            val request = call.receive<ModelSuggestionRequest>()
            val models = request.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (models.size > MAX_MODEL_SUGGESTIONS) {
                throw badRequest("too_many_models", "一次最多匹配 $MAX_MODEL_SUGGESTIONS 个模型")
            }
            val snapshot = app.modelPriceReferenceService.catalogSnapshot()
            if (!snapshot.loaded) {
                call.respond(
                    ModelSuggestionsResponse(
                        loaded = false,
                        error = snapshot.error,
                        data = emptyList(),
                    ),
                )
            } else {
                call.respond(
                    ModelSuggestionsResponse(
                        loaded = true,
                        data = models.map { model ->
                            ModelSuggestionItem(
                                model = model,
                                candidates = app.modelPriceReferenceService.findCatalogMatches(model).map { it.toResponse() },
                            )
                        },
                    ),
                )
            }
        }

        get("/models-dev") {
            call.requireAdmin(app)
            val snapshot = app.modelPriceReferenceService.catalogSnapshot()
            val matches = if (snapshot.loaded) {
                listExposedModels(app).associate { model ->
                    model.id to app.modelPriceReferenceService.findCatalogMatches(model.id).map { it.toResponse() }
                }
            } else {
                emptyMap()
            }
            call.respond(
                ModelsDevCatalogResponse(
                    source = app.modelPriceReferenceService.catalogSource,
                    loaded = snapshot.loaded,
                    error = snapshot.error,
                    data = snapshot.models.map { it.toResponse() },
                    matches = matches,
                ),
            )
        }

        get("/channels") {
            call.requireAdmin(app)
            call.respond(app.channelRepository.list().map { it.toResponse() })
        }

        get("/channels/{id}/models") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val channel = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            val mappingByUpstream = channel.modelMappings.associateBy { it.upstreamModel }
            val models = fetchUpstreamModels(channel, app.credentialCipher, app.httpClient, authorizedOnly = false)
                .map {
                    val mapping = mappingByUpstream[it.id]
                    ChannelModelResponse(
                        id = it.id,
                        ownedBy = it.ownedBy,
                        authorized = it.id in channel.models,
                        canonicalModelId = mapping?.canonicalModelId,
                        mappingSource = mapping?.mappingSource,
                    )
                }
            call.respond(ChannelModelsResponse(data = models))
        }

        post("/channels/model-discovery") {
            call.requireAdmin(app)
            val request = call.receive<ChannelModelDiscoveryRequest>()
            val baseUrl = request.baseUrl.trim()
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw badRequest("invalid_channel", "渠道地址必须以 http:// 或 https:// 开头")
            }
            val saved = request.channelId
                ?.takeIf { it.isNotBlank() }
                ?.let { app.channelRepository.find(it) ?: throw notFound("channel_not_found", "渠道不存在") }
            val authType = request.authType ?: saved?.authType ?: "bearer"
            if (authType !in setOf("bearer", "anthropic")) {
                throw badRequest("invalid_channel", "上游鉴权方式必须是 bearer 或 anthropic")
            }
            val credential = request.credential
                ?.takeIf { it.isNotBlank() }
                ?: saved?.let { channel ->
                    if (baseUrl.trimEnd('/') != channel.baseUrl.trimEnd('/')) {
                        throw badRequest("invalid_channel", "基础地址已变更，请重新输入上游凭据")
                    }
                    runCatching {
                        app.credentialCipher.decrypt(channel.credentialCiphertext, channel.credentialNonce)
                    }.getOrElse {
                        throw GatewayException(
                            HttpStatusCode.BadGateway,
                            "upstream_credential_invalid",
                            "渠道凭据无法解密",
                            retryable = false,
                        )
                    }
                }
                ?: throw badRequest("invalid_channel", "发现上游模型需要提供凭据")
            val authorized = saved?.models?.toSet().orEmpty()
            val mappingByUpstream = saved?.modelMappings?.associateBy { it.upstreamModel }.orEmpty()
            val models = fetchUpstreamModels(baseUrl, credential, app.httpClient, authType)
                .map {
                    val mapping = mappingByUpstream[it.id]
                    ChannelModelResponse(
                        id = it.id,
                        ownedBy = it.ownedBy,
                        authorized = it.id in authorized,
                        canonicalModelId = mapping?.canonicalModelId,
                        mappingSource = mapping?.mappingSource,
                    )
                }
            call.respond(ChannelModelsResponse(data = models))
        }

        post("/channels/{id}/probe-protocols") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val channel = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            val request = call.receive<ProtocolProbeRequest>()
            val model = request.model?.takeIf { it.isNotBlank() } ?: run {
                val models = fetchUpstreamModels(channel, app.credentialCipher, app.httpClient)
                models.firstOrNull()?.id ?: throw GatewayException(
                    HttpStatusCode.BadGateway,
                    "no_upstream_models",
                    "上游没有返回模型，无法探测协议",
                    "api_error",
                )
            }
            val results = probeUpstreamProtocols(channel, model, app.credentialCipher, app.httpClient)
            call.respond(
                ProtocolProbeResponse(
                    model = model,
                    results = results.map {
                        ProtocolProbeResultResponse(
                            protocol = it.protocol.value,
                            supported = it.supported,
                            statusCode = it.statusCode,
                            message = it.message,
                        )
                    },
                ),
            )
        }

        post("/channels") {
            call.requireAdmin(app)
            val request = call.receive<ChannelRequest>()
            validateChannel(request, credentialRequired = true)
            val protocols = parseProtocols(request.protocols)
            if (app.channelRepository.findByName(request.name) != null) {
                throw conflict("channel_name_exists", "渠道名称已存在")
            }
            val encrypted = app.credentialCipher.encrypt(request.credential!!)
            val record = app.channelRepository.create(
                name = request.name,
                baseUrl = request.baseUrl,
                credential = encrypted,
                authType = request.authType ?: "bearer",
                protocols = protocols,
                enabled = request.enabled,
                modelMappings = normalizeModelMappings(request).orEmpty(),
                priority = request.priority ?: 1,
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/channels/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val request = call.receive<ChannelRequest>()
            validateChannel(request, credentialRequired = false)
            val protocols = parseProtocols(request.protocols)
            val existing = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            val sameName = app.channelRepository.findByName(request.name)
            if (sameName != null && sameName.id != id) throw conflict("channel_name_exists", "渠道名称已存在")
            val encrypted = request.credential?.takeIf { it.isNotBlank() }?.let(app.credentialCipher::encrypt)
            val requestedMappings = normalizeModelMappings(request)
            val modelMappings = if (request.modelMappings == null && request.models != null && requestedMappings != null) {
                val existingByUpstream = existing.modelMappings.associateBy { it.upstreamModel }
                requestedMappings.map { existingByUpstream[it.upstreamModel] ?: it }
            } else {
                requestedMappings
            }
            if (modelMappings != null) {
                val removed = existing.models.toSet() - modelMappings.map { it.upstreamModel }.toSet()
                removed.forEach { model ->
                    val aliases = app.aliasRepository.findAliasNamesUsingModel(existing.id, model)
                    if (aliases.isNotEmpty()) {
                        throw conflict(
                            "channel_model_in_use",
                            "模型「$model」仍被别名「${aliases.joinToString("、")}」引用，请先调整别名候选",
                        )
                    }
                }
            }
            val updated = app.channelRepository.update(
                id = existing.id,
                name = request.name,
                baseUrl = request.baseUrl,
                credential = encrypted,
                authType = request.authType,
                protocols = protocols,
                enabled = request.enabled,
                modelMappings = modelMappings,
                priority = request.priority,
            ) ?: throw notFound("channel_not_found", "渠道不存在")
            call.respond(updated.toResponse())
        }

        delete("/channels/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
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
                candidates = request.candidates.map { NewCandidate(it.channelId, it.upstreamModel.trim(), it.position) },
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/aliases/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少别名 id")
            val request = call.receive<AliasRequest>()
            validateAlias(app, request)
            val sameAlias = app.aliasRepository.findByAlias(request.alias)
            if (sameAlias != null && sameAlias.id != id) throw conflict("alias_exists", "模型别名已存在")
            val record = app.aliasRepository.update(
                id = id,
                alias = request.alias,
                enabled = request.enabled,
                candidates = request.candidates.map { NewCandidate(it.channelId, it.upstreamModel.trim(), it.position) },
            ) ?: throw notFound("alias_not_found", "模型别名不存在")
            call.respond(record.toResponse())
        }

        delete("/aliases/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少别名 id")
            if (!app.aliasRepository.delete(id)) throw notFound("alias_not_found", "模型别名不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/client-keys") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            val keys = app.clientKeyRepository.list().map { key ->
                key.toResponse(quotaUsageResponse(app.requestLimitService.clientKeyUsage(key.id)))
            }
            call.respond(keys)
        }

        get("/team-limits") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            val policies = app.teamLimitRepository.list().associateBy { it.team }
            val teams = (policies.keys + app.clientKeyRepository.list().mapNotNull { it.team })
                .toSortedSet()
            val responses = teams.map { team ->
                val usage = app.requestLimitService.teamUsage(team)
                policies[team]?.toResponse(usage) ?: unconfiguredTeamLimit(team, usage)
            }
            call.respond(responses)
        }

        put("/team-limits/{team}") {
            call.requireAdmin(app)
            val team = normalizeTeam(call.requiredParam("team", "缺少团队名称"))
                ?: throw badRequest("invalid_team_limit", "团队名称不能为空")
            val request = call.receive<TeamLimitRequest>()
            if (request.requestsPerMinute == null && request.maxConcurrentRequests == null) {
                throw badRequest("invalid_team_limit", "每分钟请求数和最大并发数至少设置一项")
            }
            if (request.requestsPerMinute != null && request.requestsPerMinute < 0) {
                throw badRequest("invalid_team_limit", "团队每分钟请求数必须是非负整数")
            }
            if (request.maxConcurrentRequests != null && request.maxConcurrentRequests <= 0) {
                throw badRequest("invalid_team_limit", "团队最大并发请求数必须是正整数")
            }
            val policy = app.teamLimitRepository.upsert(
                team = team,
                requestsPerMinute = request.requestsPerMinute,
                maxConcurrentRequests = request.maxConcurrentRequests,
            )
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(policy.toResponse(app.requestLimitService.teamUsage(team)))
        }

        delete("/team-limits/{team}") {
            call.requireAdmin(app)
            val team = normalizeTeam(call.requiredParam("team", "缺少团队名称"))
                ?: throw badRequest("invalid_team_limit", "团队名称不能为空")
            if (!app.teamLimitRepository.delete(team)) {
                throw notFound("team_limit_not_found", "团队限流策略不存在")
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/client-keys") {
            call.requireAdmin(app)
            val request = call.receive<ClientKeyRequest>()
            if (request.name.isBlank()) throw badRequest("invalid_client_key", "密钥名称不能为空")
            if (request.expiresAt != null && request.expiresAt <= System.currentTimeMillis()) {
                throw badRequest("invalid_client_key", "过期时间必须晚于当前时间")
            }
            val quotas = validateClientKeyQuotas(request)
            val generated = ClientKeyHasher.generate()
            val record = app.clientKeyRepository.create(
                name = request.name,
                team = normalizeTeam(request.team),
                keyHash = generated.hash,
                keyPrefix = generated.prefix,
                enabled = request.enabled,
                expiresAt = request.expiresAt,
                quotas = quotas,
            )
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(
                HttpStatusCode.Created,
                record.toCreatedResponse(generated.raw),
            )
        }

        put("/client-keys/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少密钥 id")
            val request = call.receive<ClientKeyRequest>()
            if (request.name.isBlank()) throw badRequest("invalid_client_key", "密钥名称不能为空")
            if (request.expiresAt != null && request.expiresAt <= System.currentTimeMillis()) {
                throw badRequest("invalid_client_key", "过期时间必须晚于当前时间")
            }
            val quotas = validateClientKeyQuotas(request)
            val existing = app.clientKeyRepository.find(id)
                ?: throw notFound("client_key_not_found", "调用方密钥不存在")
            if (existing.revokedAt != null) {
                throw conflict("client_key_revoked", "调用方密钥已撤销，不能重新启用或修改")
            }
            val record = app.clientKeyRepository.update(
                id = id,
                name = request.name,
                team = normalizeTeam(request.team),
                enabled = request.enabled,
                expiresAt = request.expiresAt,
                quotas = quotas,
            ) ?: run {
                val current = app.clientKeyRepository.find(id)
                    ?: throw notFound("client_key_not_found", "调用方密钥不存在")
                if (current.revokedAt != null) {
                    throw conflict("client_key_revoked", "调用方密钥已撤销，不能重新启用或修改")
                }
                throw conflict("client_key_update_conflict", "调用方密钥状态已变化，请刷新后重试")
            }
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(
                record.toResponse(quotaUsageResponse(app.requestLimitService.clientKeyUsage(record.id))),
            )
        }

        post("/client-keys/{id}/rotate") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少密钥 id")
            val generated = ClientKeyHasher.generate()
            val record = app.clientKeyRepository.rotate(id, generated.hash, generated.prefix)
            if (record == null) {
                val existing = app.clientKeyRepository.find(id)
                    ?: throw notFound("client_key_not_found", "调用方密钥不存在")
                if (existing.revokedAt != null) {
                    throw conflict("client_key_revoked", "调用方密钥已撤销，不能轮换")
                }
                throw conflict("client_key_rotation_conflict", "调用方密钥状态已变化，请刷新后重试")
            }
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(record.toCreatedResponse(generated.raw))
        }

        post("/client-keys/{id}/revoke") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少密钥 id")
            val record = app.clientKeyRepository.revoke(id)
                ?: throw notFound("client_key_not_found", "调用方密钥不存在")
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(
                record.toResponse(quotaUsageResponse(app.requestLimitService.clientKeyUsage(record.id))),
            )
        }

        delete("/client-keys/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少密钥 id")
            if (!app.clientKeyRepository.delete(id)) throw notFound("client_key_not_found", "调用方密钥不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/model-prices/reference") {
            call.requireAdmin(app)
            val model = call.requiredQuery("model", "缺少模型名称")
            val reference = app.modelPriceReferenceService.lookup(model)
                ?: throw notFound("reference_price_not_found", "未在参考价库中找到模型「$model」的单价，可直接手动输入")
            call.respond(reference.toResponse())
        }

        get("/model-prices") {
            call.requireAdmin(app)
            call.respond(app.modelPriceRepository.list().map { it.toResponse() })
        }

        post("/model-prices") {
            call.requireAdmin(app)
            val request = call.receive<ModelPriceRequest>()
            val price = validateModelPrice(app, request)
            if (app.modelPriceRepository.find(price.channelId, price.upstreamModel) != null) {
                throw conflict("model_price_exists", "该渠道和上游模型的计费单价已存在")
            }
            val record = app.modelPriceRepository.create(
                channelId = price.channelId,
                upstreamModel = price.upstreamModel,
                currency = price.currency,
                inputPricePerMillion = price.input,
                outputPricePerMillion = price.output,
                cachedInputPricePerMillion = price.cached,
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/model-prices/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少价格 id")
            val request = call.receive<ModelPriceRequest>()
            val price = validateModelPrice(app, request)
            app.modelPriceRepository.findById(id) ?: throw notFound("model_price_not_found", "计费单价不存在")
            val duplicate = app.modelPriceRepository.find(price.channelId, price.upstreamModel)
            if (duplicate != null && duplicate.id != id) {
                throw conflict("model_price_exists", "该渠道和上游模型的计费单价已存在")
            }
            val updated = app.modelPriceRepository.update(
                id = id,
                channelId = price.channelId,
                upstreamModel = price.upstreamModel,
                currency = price.currency,
                inputPricePerMillion = price.input,
                outputPricePerMillion = price.output,
                cachedInputPricePerMillion = price.cached,
            ) ?: throw notFound("model_price_not_found", "计费单价不存在")
            call.respond(updated.toResponse())
        }

        delete("/model-prices/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少价格 id")
            if (!app.modelPriceRepository.delete(id)) throw notFound("model_price_not_found", "计费单价不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/request-logs") {
            call.requireAdmin(app)
            val limit = call.queryInt("limit", default = 100, min = 1, max = 1000)
            val beforeId = call.queryLongOrNull("beforeId")
            call.respond(app.requestLogRepository.list(limit, beforeId).map { it.toResponse() })
        }

        get("/usage-report") {
            call.requireAdmin(app)
            val now = System.currentTimeMillis()
            val to = call.queryLongOrNull("to") ?: now
            val from = call.queryLongOrNull("from") ?: (to - 7 * 86_400_000L)
            if (from > to) throw badRequest("invalid_report_range", "报表开始时间不能晚于结束时间")
            if (to - from > 366 * 86_400_000L) throw badRequest("invalid_report_range", "报表时间范围不能超过 366 天")
            val report = app.requestLogRepository.report(
                UsageReportQuery(
                    from = from,
                    to = to,
                    clientKeyId = call.request.queryParameters["clientKeyId"]?.takeIf { it.isNotBlank() },
                    team = call.request.queryParameters["team"]?.takeIf { it.isNotBlank() },
                ),
            )
            call.respond(report.toResponse())
        }

        get("/request-log-retention") {
            call.requireAdmin(app)
            call.respond(app.config.retention.toResponse(System.currentTimeMillis()))
        }

        post("/request-log-retention/cleanup") {
            call.requireAdmin(app)
            val deleted = app.logRetentionService.cleanExpired()
            call.respond(deleted.toResponse())
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
    val token = request.cookies["gw_session"] ?: run {
        markAdminAuditError("admin_session_missing")
        throw adminUnauthorized("管理面未登录")
    }
    val session = app.sessions.get(token) ?: run {
        markAdminAuditError("admin_session_expired")
        throw adminUnauthorized("管理面会话已过期")
    }
    markAdminAuditActor(session.username)
    if (request.httpMethod != HttpMethod.Get && request.httpMethod != HttpMethod.Head) {
        val csrf = request.headers["X-CSRF-Token"] ?: run {
            markAdminAuditError("csrf_missing")
            throw adminForbidden("csrf_missing", "缺少 CSRF Token")
        }
        if (!MessageDigest.isEqual(csrf.toByteArray(Charsets.UTF_8), session.csrf.toByteArray(Charsets.UTF_8))) {
            markAdminAuditError("csrf_invalid")
            throw adminForbidden("csrf_invalid", "CSRF Token 无效")
        }
    }
    return session
}

private fun ApplicationCall.clearAdminCookies(app: GatewayApplication) {
    response.cookies.append(
        Cookie("gw_session", "", maxAge = 0, path = "/", httpOnly = true, secure = app.config.cookieSecure),
    )
    response.cookies.append(
        Cookie("gw_csrf", "", maxAge = 0, path = "/", secure = app.config.cookieSecure),
    )
}

private fun validateAvatarDataUrl(value: String?): String? {
    if (value.isNullOrBlank()) return null
    if (value.length > MAX_AVATAR_DATA_URL_CHARS) {
        throw badRequest("avatar_too_large", "头像数据不能超过 600KB")
    }
    val prefix = AVATAR_DATA_URL_PREFIXES.firstOrNull(value::startsWith)
        ?: throw badRequest("invalid_avatar", "头像仅支持 PNG、JPEG 或 WebP")
    val bytes = runCatching { Base64.getDecoder().decode(value.removePrefix(prefix)) }
        .getOrElse { throw badRequest("invalid_avatar", "头像数据格式无效") }
    if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) {
        throw badRequest("avatar_too_large", "头像文件不能超过 400KB")
    }
    val validMagic = when (prefix) {
        "data:image/png;base64," -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)
        "data:image/jpeg;base64," -> bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(JPEG_MAGIC)
        else -> bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
    }
    if (!validMagic) throw badRequest("invalid_avatar", "头像内容与图片格式不匹配")
    return value
}

private val AVATAR_DATA_URL_PREFIXES = listOf(
    "data:image/png;base64,",
    "data:image/jpeg;base64,",
    "data:image/webp;base64,",
)
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private const val MAX_AVATAR_DATA_URL_CHARS = 600_000
private const val MAX_AVATAR_BYTES = 400_000

private fun parseProtocols(values: List<String>): Set<GatewayProtocol> {
    if (values.isEmpty()) throw badRequest("invalid_channel", "渠道至少需要支持一个协议")
    return values.map { value ->
        GatewayProtocol.fromValue(value)
            ?: throw badRequest("invalid_channel", "不支持的协议: $value")
    }.toSet()
}

private fun validateChannel(request: ChannelRequest, credentialRequired: Boolean) {
    if (request.name.isBlank()) throw badRequest("invalid_channel", "渠道名称不能为空")
    if (!request.baseUrl.startsWith("http://") && !request.baseUrl.startsWith("https://")) {
        throw badRequest("invalid_channel", "渠道地址必须以 http:// 或 https:// 开头")
    }
    if (request.authType != null && request.authType !in setOf("bearer", "anthropic")) {
        throw badRequest("invalid_channel", "上游鉴权方式必须是 bearer 或 anthropic")
    }
    if (request.priority != null && request.priority !in 1..1000) {
        throw badRequest("invalid_channel", "渠道优先级必须在 1..1000 之间")
    }
    if (credentialRequired && request.credential.isNullOrBlank()) {
        throw badRequest("invalid_channel", "新建渠道必须提供上游凭据")
    }
}

private fun normalizeModelMappings(request: ChannelRequest): List<ChannelModelRecord>? {
    val raw = when {
        request.modelMappings != null -> request.modelMappings.map {
            ChannelModelRecord(
                upstreamModel = it.upstreamModel,
                canonicalModelId = it.canonicalModelId,
                mappingSource = it.mappingSource,
            )
        }
        request.models != null -> request.models.map { ChannelModelRecord(upstreamModel = it) }
        else -> return null
    }
    val normalized = raw.map { mapping ->
        val upstreamModel = mapping.upstreamModel.trim()
        if (upstreamModel.isEmpty() || upstreamModel.length > 200) {
            throw badRequest("invalid_channel", "上游模型名长度必须在 1..200 之间")
        }
        val canonicalModelId = mapping.canonicalModelId?.trim()?.takeIf { it.isNotEmpty() }
        if (canonicalModelId != null && canonicalModelId.length > 200) {
            throw badRequest("invalid_channel", "规范 ID 长度不能超过 200 个字符")
        }
        val mappingSource = if (canonicalModelId == null) {
            null
        } else {
            mapping.mappingSource?.trim()?.takeIf { it.isNotEmpty() } ?: MAPPING_SOURCE_MANUAL
        }
        if (mappingSource != null && mappingSource !in MAPPING_SOURCES) {
            throw badRequest("invalid_channel", "未知的映射来源: $mappingSource")
        }
        ChannelModelRecord(upstreamModel, canonicalModelId, mappingSource)
    }.distinctBy { it.upstreamModel }

    val duplicatePublicIds = normalized
        .groupBy { (it.canonicalModelId ?: it.upstreamModel).lowercase() }
        .filterValues { entries -> entries.size > 1 && entries.any { it.canonicalModelId != null } }
        .keys
    if (duplicatePublicIds.isNotEmpty()) {
        throw badRequest("invalid_channel", "同一渠道内对外模型 ID 不能重复: ${duplicatePublicIds.joinToString("、")}")
    }
    return normalized
}

private fun CatalogModel.toResponse() = ModelsDevModelResponse(
    id = id,
    canonicalId = canonicalId,
    provider = provider,
    providerId = providerId,
    developer = developer,
    developerId = developerId,
)

private suspend fun validateAlias(app: GatewayApplication, request: AliasRequest) {
    if (!ALIAS_PATTERN.matches(request.alias)) {
        throw badRequest("invalid_alias", "模型别名只能包含字母、数字、点、下划线和短横线，长度 1..100")
    }
    if (request.candidates.isEmpty()) throw badRequest("invalid_alias", "模型别名至少需要一个候选")
    request.candidates.forEach { candidate ->
        if (candidate.upstreamModel.isBlank()) throw badRequest("invalid_alias", "上游模型名不能为空")
        if (candidate.position != null && candidate.position !in 0..999) {
            throw badRequest("invalid_alias", "候选档位必须在 0..999 之间")
        }
        val channel = app.channelRepository.find(candidate.channelId)
        if (channel == null) {
            throw badRequest("invalid_alias", "候选渠道 ${candidate.channelId} 不存在")
        }
        if (candidate.upstreamModel.trim() !in channel.models) {
            throw badRequest("model_not_authorized", "渠道「${channel.name}」尚未授权模型「${candidate.upstreamModel}」")
        }
    }
}

private data class ValidatedModelPrice(
    val channelId: String,
    val upstreamModel: String,
    val currency: String,
    val input: BigDecimal,
    val output: BigDecimal,
    val cached: BigDecimal?,
)

private suspend fun validateModelPrice(
    app: GatewayApplication,
    request: ModelPriceRequest,
): ValidatedModelPrice {
    val channelId = request.channelId.trim()
    if (channelId.isEmpty()) throw badRequest("invalid_model_price", "渠道不能为空")
    if (app.channelRepository.find(channelId) == null) {
        throw badRequest("invalid_model_price", "渠道不存在")
    }
    val upstreamModel = request.upstreamModel.trim()
    if (upstreamModel.isEmpty() || upstreamModel.length > 200) {
        throw badRequest("invalid_model_price", "上游模型名长度必须在 1..200 之间")
    }
    val currency = request.currency.trim().uppercase()
    if (!CURRENCY_PATTERN.matches(currency)) {
        throw badRequest("invalid_model_price", "币种必须是 3 到 8 位字母")
    }
    return ValidatedModelPrice(
        channelId = channelId,
        upstreamModel = upstreamModel,
        currency = currency,
        input = parsePrice(request.inputPricePerMillion, "输入单价"),
        output = parsePrice(request.outputPricePerMillion, "输出单价"),
        cached = request.cachedInputPricePerMillion
            ?.takeIf { it.isNotBlank() }
            ?.let { parsePrice(it, "缓存输入单价") },
    )
}

private fun parsePrice(value: String, label: String): BigDecimal {
    val parsed = value.trim().toBigDecimalOrNull()
        ?: throw badRequest("invalid_model_price", "$label 必须是数字")
    if (parsed < BigDecimal.ZERO) throw badRequest("invalid_model_price", "$label 不能为负数")
    return parsed.setScale(8, RoundingMode.HALF_UP)
}

private fun normalizeTeam(value: String?): String? {
    val team = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (team.length > 100) throw badRequest("invalid_client_key", "团队名称不能超过 100 个字符")
    return team
}

private fun validateClientKeyQuotas(request: ClientKeyRequest): ClientKeyQuotaLimits {
    if (request.dailyTokenLimit != null && request.dailyTokenLimit < 0) {
        throw badRequest("invalid_client_key", "每日 token 上限必须是非负整数")
    }
    if (request.maxConcurrentRequests != null && request.maxConcurrentRequests <= 0) {
        throw badRequest("invalid_client_key", "最大并发请求数必须是正整数")
    }
    if (request.requestsPerMinute != null && request.requestsPerMinute < 0) {
        throw badRequest("invalid_client_key", "每分钟请求数必须是非负整数")
    }
    val costs = try {
        listOf("USD" to request.dailyUsdLimit, "CNY" to request.dailyCnyLimit, "EUR" to request.dailyEurLimit)
            .mapNotNull { (currency, value) -> parseDailyCostLimit(value)?.let { currency to it } }
            .toMap()
    } catch (cause: IllegalArgumentException) {
        throw badRequest("invalid_client_key", cause.message ?: "每日费用上限无效")
    }
    return ClientKeyQuotaLimits(
        dailyTokens = request.dailyTokenLimit,
        dailyCosts = costs,
        maxConcurrentRequests = request.maxConcurrentRequests,
        requestsPerMinute = request.requestsPerMinute,
    )
}

private fun conflict(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.Conflict, code, message, "invalid_request_error")

private fun adminUnauthorized(message: String): GatewayException =
    GatewayException(HttpStatusCode.Unauthorized, "admin_unauthorized", message, "authentication_error")

private fun adminForbidden(code: String, message: String): GatewayException =
    GatewayException(HttpStatusCode.Forbidden, code, message, "permission_error")

private val ALIAS_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
private val CURRENCY_PATTERN = Regex("[A-Za-z]{3,8}")
private const val MAX_MODEL_SUGGESTIONS = 500
private const val MAPPING_SOURCE_MANUAL = "manual"
private val MAPPING_SOURCES = setOf(MAPPING_SOURCE_MANUAL, "models_dev")
