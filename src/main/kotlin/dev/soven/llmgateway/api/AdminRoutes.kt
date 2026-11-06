package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelQuotaSettings
import dev.soven.llmgateway.spi.quota.ChannelQuotaProvider
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.domain.UpstreamCredential
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.SUPPORTED_UPSTREAM_AUTH_TYPES
import dev.soven.llmgateway.domain.effectiveAuthType
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.proxy.validateUpstreamRequestOverrides
import dev.soven.llmgateway.domain.ModelPriceTier
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.PricingMode
import dev.soven.llmgateway.oauth.OAuthLoginResult
import dev.soven.llmgateway.oauth.OAuthLoginStartResult
import dev.soven.llmgateway.oauth.OAuthQuotaResult
import dev.soven.llmgateway.oauth.OAuthRefreshException
import dev.soven.llmgateway.oauth.OAuthRefreshResult
import dev.soven.llmgateway.oauth.OAuthUnavailableReason
import dev.soven.llmgateway.oauth.proxyAddress
import dev.soven.llmgateway.proxy.CatalogModel
import dev.soven.llmgateway.proxy.fetchUpstreamModels
import dev.soven.llmgateway.proxy.modelListProtocol
import dev.soven.llmgateway.proxy.probeUpstreamProtocols
import dev.soven.llmgateway.proxy.resolveSavedCredential
import dev.soven.llmgateway.proxy.testChannelModelConnectivity
import dev.soven.llmgateway.repo.ChannelKeyUpdate
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.OAuthAccountCredentialUpdate
import dev.soven.llmgateway.repo.KeyAutoDisableSettingsAuditContext
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.KeyDisablePolicy
import dev.soven.llmgateway.resilience.toKeyDisablePolicy
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.parseDailyCostLimit
import dev.soven.llmgateway.report.ChannelCooldownSummary
import dev.soven.llmgateway.report.UsageReportQuery
import dev.soven.llmgateway.resilience.CandidateCooldown
import dev.soven.llmgateway.security.AdminSessions
import dev.soven.llmgateway.security.ClientKeyHasher
import dev.soven.llmgateway.security.PasswordHasher
import dev.soven.llmgateway.security.credentialFingerprint
import io.ktor.http.Cookie
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * 管理面 API。所有写操作要求会话 Cookie 与 CSRF Token 同时有效。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun Route.adminRoutes(app: GatewayApplication) {
    val channelUpdateMutex = Mutex()
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
            val rawFrom = call.request.queryParameters["from"]
            val rawTo = call.request.queryParameters["to"]
            val from = rawFrom?.trim()?.toLongOrNull()
            val to = rawTo?.trim()?.toLongOrNull()
            if ((rawFrom != null && from == null) || (rawTo != null && to == null)) {
                throw badRequest("invalid_audit_time_range", "审计时间范围无效")
            }
            if (from != null && to != null && from >= to) {
                throw badRequest("invalid_audit_time_range", "审计时间范围无效")
            }
            val actor = call.request.queryParameters["actor"]?.trim()?.takeIf(String::isNotEmpty)
            val action = call.request.queryParameters["action"]?.trim()?.takeIf(String::isNotEmpty)
            val result = call.request.queryParameters["result"]?.trim()?.takeIf(String::isNotEmpty)
            if (result != null && result !in setOf("success", "failure")) {
                throw badRequest("invalid_audit_result", "审计结果筛选值无效")
            }
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(app.adminAuditRepository.list(limit, beforeId, from, to, actor, action, result).map { it.toResponse() })
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

        get("/system-config") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(buildSystemConfigResponse(app.config))
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

        get("/model-test-settings") {
            call.requireAdmin(app)
            call.respond(app.modelTestSettingsRepository.load().toResponse())
        }

        put("/model-test-settings") {
            call.requireAdmin(app)
            val request = call.receive<ModelTestSettingsRequest>()
            val prompt = request.defaultPrompt.trim()
            if (prompt.isEmpty() || prompt.length > MODEL_TEST_PROMPT_MAX) {
                throw badRequest("invalid_model_test_settings", "默认提示词长度必须在 1..$MODEL_TEST_PROMPT_MAX 之间")
            }
            app.modelTestSettingsRepository.save(prompt)
            call.respond(app.modelTestSettingsRepository.load().toResponse())
        }

        get("/channels") {
            call.requireAdmin(app)
            call.respond(app.channelRepository.list().map { it.toResponse() })
        }

        get("/quota-providers") {
            call.requireAdmin(app)
            call.respond(app.channelQuotaProviderRegistry.providerInfos.map {
                QuotaProviderResponse(it.id, it.displayName, it.apiVersion)
            })
        }

        post("/channels/{id}/quota") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val channel = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(app.channelQuotaService.query(channel))
        }

        post("/channels/{id}/keys/{keyId}/quota") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val keyId = call.requiredParam("keyId", "缺少密钥 id")
            val channel = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
            if (channel.keys.none { it.id == keyId }) throw notFound("channel_key_not_found", "该渠道下的密钥不存在")
            call.response.headers.append("Cache-Control", "no-store")
            call.respond(app.channelQuotaService.query(channel, keyId))
        }

        put("/channels/{id}/keys/{keyId}/quota-settings") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val keyId = call.requiredParam("keyId", "缺少密钥 id")
            val settings = call.receive<ChannelQuotaSettingsRequest>()
            validateQuotaSettings(settings)
            val credential = settings.credential?.trim()?.takeIf { it.isNotEmpty() }?.let(app.credentialCipher::encrypt)
            val updated = app.channelRepository.updateKeyQuota(id, keyId, settings.toSettings(), credential, settings.clearCredential)
                ?: throw notFound("channel_key_not_found", "渠道或该渠道下的密钥不存在")
            call.respond(updated.toResponse())
        }

        put("/channels/{id}/quota-settings") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val settings = call.receive<ChannelQuotaSettingsRequest>()
            validateQuotaSettings(settings)
            val credential = settings.credential?.trim()?.takeIf { it.isNotEmpty() }?.let(app.credentialCipher::encrypt)
            val updated = app.channelRepository.updateQuota(id, settings.toSettings(), credential, settings.clearCredential)
                ?: throw notFound("channel_not_found", "渠道不存在")
            call.respond(updated.toResponse())
        }

        get("/upstream-request-settings") {
            call.requireAdmin(app)
            call.respond(
                app.upstreamRequestSettingsRepository.load().toResponse(app.upstreamHeaderCatalogService.snapshot()),
            )
        }

        put("/upstream-request-settings") {
            call.requireAdmin(app)
            val request = call.receive<UpstreamRequestSettingsRequest>()
            validateUpstreamRequestOverrides(request.overrides)
            call.respond(
                app.upstreamRequestSettingsRepository.save(request.overrides)
                    .toResponse(app.upstreamHeaderCatalogService.snapshot()),
            )
        }

        post("/upstream-request-settings/header-catalog/refresh") {
            call.requireAdmin(app)
            val catalog = app.upstreamHeaderCatalogService.refreshNow()
            call.respond(app.upstreamRequestSettingsRepository.load().toResponse(catalog))
        }

        put("/upstream-request-settings/header-profiles/{profileId}/pin") {
            call.requireAdmin(app)
            val profileId = call.requiredParam("profileId", "缺少请求头预设 id")
            val request = call.receive<HeaderProfilePinRequest>()
            val version = request.version.trim()
            if (version.isEmpty()) throw badRequest("invalid_header_profile", "预设版本不能为空")
            val catalog = try {
                app.upstreamHeaderCatalogService.pin(profileId, version)
            } catch (cause: IllegalArgumentException) {
                throw badRequest("invalid_header_profile", cause.message ?: "请求头预设版本无效")
            }
            call.respond(app.upstreamRequestSettingsRepository.load().toResponse(catalog))
        }

        delete("/upstream-request-settings/header-profiles/{profileId}/pin") {
            call.requireAdmin(app)
            val profileId = call.requiredParam("profileId", "缺少请求头预设 id")
            val catalog = try {
                app.upstreamHeaderCatalogService.unpin(profileId)
            } catch (cause: IllegalArgumentException) {
                throw badRequest("invalid_header_profile", cause.message ?: "请求头预设无效")
            }
            call.respond(app.upstreamRequestSettingsRepository.load().toResponse(catalog))
        }

        get("/key-auto-disable-settings") {
            call.requireAdmin(app)
            call.respond(app.keyAutoDisableRepository.loadSettings().toResponse())
        }

        put("/key-auto-disable-settings") {
            call.requireAdmin(app)
            val request = call.receive<KeyAutoDisableSettingsRequest>()
            val policy = try {
                when {
                    request.rules != null -> KeyDisablePolicy(request.enabled, request.rules)
                    request.authFailureThreshold != null && request.failureThreshold != null &&
                        request.failureWindowMinutes != null -> KeyAutoDisablePolicy(
                        request.enabled, request.authFailureThreshold, request.failureThreshold, request.failureWindowMinutes,
                    ).toKeyDisablePolicy()
                    else -> throw IllegalArgumentException("必须提供 rules 或完整的旧版阈值")
                }
            } catch (cause: IllegalArgumentException) {
                throw badRequest("invalid_key_auto_disable_settings", cause.message ?: "自动禁用规则无效")
            }
            val requestId = call.response.headers["X-Request-Id"]
            val change = app.keyAutoDisableRepository.saveSettings(
                policy,
                KeyAutoDisableSettingsAuditContext(
                    actor = call.adminAuditActor()?.take(100),
                    requestId = requestId ?: call.request.headers["X-Request-Id"].orEmpty(),
                    sourceIp = call.request.origin.remoteAddress.take(100),
                ),
            )
            call.markAdminAuditHandled()
            call.respond(change.after.toResponse())
        }

        get("/channels/{id}/models") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val channel = app.channelRepository.find(id)?.let { channelWithCredentials(app, it) }
                ?: throw notFound("channel_not_found", "渠道不存在")
            val credential = resolveChannelCredential(channel, call.request.queryParameters["keyId"])
            val mappingByUpstream = channel.modelMappings.associateBy { it.upstreamModel }
            val models = fetchUpstreamModels(
                channel, credential, app.oauthTokenService, app.httpClient, authorizedOnly = false,
                globalOverrides = app.upstreamRequestSettingsRepository.load().overrides,
                headerProfiles = app.upstreamHeaderCatalogService.profiles(),
            )
                .map {
                    val mapping = mappingByUpstream[it.id]
                    ChannelModelResponse(
                        id = it.id,
                        ownedBy = it.ownedBy,
                        authorized = credential.supports(it.id),
                        canonicalModelId = mapping?.canonicalModelId,
                        mappingSource = mapping?.mappingSource,
                    )
                }
            call.respond(ChannelModelsResponse(data = models))
        }

        post("/channels/model-discovery") {
            call.requireAdmin(app)
            val request = call.receive<ChannelModelDiscoveryRequest>()
            if (!request.keyId.isNullOrBlank() && !request.credential.isNullOrBlank()) {
                throw badRequest("invalid_channel", "keyId 与 credential 不能同时提交")
            }
            val baseUrl = request.baseUrl.trim()
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw badRequest("invalid_channel", "渠道地址必须以 http:// 或 https:// 开头")
            }
            val saved = request.channelId
                ?.takeIf { it.isNotBlank() }
                ?.let { channelId ->
                    app.channelRepository.find(channelId)?.let { channelWithCredentials(app, it) }
                        ?: throw notFound("channel_not_found", "渠道不存在")
                }
            val savedCredential = saved?.let { channel ->
                request.keyId?.takeIf { it.isNotBlank() }?.let { keyId ->
                    (channel.keys + channel.oauthAccounts).firstOrNull { it.id == keyId }?.also { credential ->
                        if (!credential.isRoutable()) throw credentialUnavailable(credential)
                    }
                        ?: throw notFound("channel_key_not_found", "渠道密钥或订阅账号不存在")
                } ?: (channel.keys + channel.oauthAccounts).filter { it.isRoutable() }
                    .minWithOrNull(compareBy({ it.priority }, { it.name }, { it.id }))
            }
            val authType = request.authType ?: saved?.authType ?: "bearer"
            if (authType !in SUPPORTED_UPSTREAM_AUTH_TYPES) {
                throw badRequest("invalid_channel", "上游鉴权方式必须是 bearer、anthropic 或 google")
            }
            validateProtocolAuthTypes(request.protocolAuthTypes)
            request.requestOverrides?.let(::validateUpstreamRequestOverrides)
            val credential = request.credential
                ?.takeIf { it.isNotBlank() }
                ?: saved?.let { channel ->
                    if (baseUrl.trimEnd('/') != channel.baseUrl.trimEnd('/')) {
                        throw badRequest("invalid_channel", "基础地址已变更，请重新输入上游凭据")
                    }
                    val target = savedCredential ?: throw badRequest("invalid_channel", "渠道没有可用上游凭据")
                    resolveSavedCredential(app.oauthTokenService, target)
                }
                ?: throw badRequest("invalid_channel", "发现上游模型需要提供凭据")
            val authorized = savedCredential?.models?.toSet().orEmpty()
            val mappingByUpstream = saved?.modelMappings?.associateBy { it.upstreamModel }.orEmpty()
            val discoveryProtocols = request.protocols?.takeIf { it.isNotEmpty() }?.let(::parseProtocols)
                ?: saved?.protocols.orEmpty()
            val protocolAuthTypes = request.protocolAuthTypes ?: saved?.protocolAuthTypes.orEmpty()
            val discoveryProtocol = modelListProtocol(discoveryProtocols, authType, protocolAuthTypes)
            val discoveryAuthType = effectiveAuthType(authType, protocolAuthTypes, discoveryProtocol)
            val models = fetchUpstreamModels(
                baseUrl, credential, app.httpClient, discoveryAuthType,
                protocol = discoveryProtocol,
                globalOverrides = app.upstreamRequestSettingsRepository.load().overrides,
                channelOverrides = request.requestOverrides ?: saved?.requestOverrides ?: UpstreamRequestOverrides(),
                headerProfiles = app.upstreamHeaderCatalogService.profiles(),
            )
                .map {
                    val mapping = mappingByUpstream[it.id]
                    ChannelModelResponse(
                        id = it.id,
                        ownedBy = it.ownedBy,
                        authorized = (savedCredential?.isOAuth == true && savedCredential.models.isEmpty()) || it.id in authorized,
                        canonicalModelId = mapping?.canonicalModelId,
                        mappingSource = mapping?.mappingSource,
                    )
                }
            call.respond(ChannelModelsResponse(data = models))
        }

        post("/channels/{id}/probe-protocols") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val channel = app.channelRepository.find(id)?.let { channelWithCredentials(app, it) }
                ?: throw notFound("channel_not_found", "渠道不存在")
            val request = call.receive<ProtocolProbeRequest>()
            val credential = resolveChannelCredential(channel, request.keyId)
            val globalOverrides = app.upstreamRequestSettingsRepository.load().overrides
            val headerProfiles = app.upstreamHeaderCatalogService.profiles()
            val model = request.model?.takeIf { it.isNotBlank() } ?: run {
                val models = fetchUpstreamModels(
                    channel, credential, app.oauthTokenService, app.httpClient,
                    globalOverrides = globalOverrides,
                    headerProfiles = headerProfiles,
                )
                models.firstOrNull()?.id ?: throw GatewayException(
                    HttpStatusCode.BadGateway,
                    "no_upstream_models",
                    "上游没有返回模型，无法探测协议",
                    "api_error",
                )
            }
            val results = probeUpstreamProtocols(
                channel, credential, model, app.oauthTokenService, app.httpClient,
                globalOverrides = globalOverrides,
                headerProfiles = headerProfiles,
            )
            call.respond(
                ProtocolProbeResponse(
                    model = model,
                    keyId = credential.id,
                    keyName = credential.name,
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

        post("/channels/{id}/model-test") {
            call.requireAdmin(app)
            val channelId = call.requiredParam("id", "缺少渠道 id")
            val channel = app.channelRepository.find(channelId)?.let { channelWithCredentials(app, it) }
                ?: throw notFound("channel_not_found", "渠道不存在")
            val request = call.receive<ChannelModelTestRequest>()
            val model = request.model.trim()
            if (model.isEmpty() || model.length > 200) {
                throw badRequest("invalid_model_test", "模型名长度必须在 1..200 之间")
            }
            if (model !in channel.models) {
                throw badRequest("model_not_authorized", "渠道「${channel.name}」尚未授权模型「$model」")
            }
            val credential = resolveChannelCredentialForModel(channel, model, request.keyId)
            val protocol = GatewayProtocol.fromValue(request.protocol.trim())
                ?: throw badRequest("invalid_model_test_protocol", "测试协议无效")
            if (protocol !in channel.protocols) {
                throw badRequest("model_test_protocol_unsupported", "渠道未启用协议 ${protocol.value}")
            }
            val settings = app.modelTestSettingsRepository.load()
            val prompt = request.prompt?.trim()?.takeIf { it.isNotEmpty() } ?: settings.defaultPrompt
            if (prompt.length > MODEL_TEST_PROMPT_MAX) {
                throw badRequest("invalid_model_test_prompt", "测试提示词不能超过 $MODEL_TEST_PROMPT_MAX 个字符")
            }
            val result = testChannelModelConnectivity(
                channel = channel,
                credentialRef = credential,
                model = model,
                protocol = protocol,
                prompt = prompt,
                credentials = app.oauthTokenService,
                httpClient = app.httpClient,
                globalOverrides = app.upstreamRequestSettingsRepository.load().overrides,
                headerProfiles = app.upstreamHeaderCatalogService.profiles(),
            )
            call.respond(result.toResponse(model, protocol, credential.id, credential.name, prompt))
        }

        post("/channels") {
            call.requireAdmin(app)
            val request = call.receive<ChannelRequest>()
            validateChannel(request, credentialRequired = false)
            val protocols = parseProtocols(request.protocols ?: GatewayProtocol.default.map { it.value })
            if (app.channelRepository.findByName(request.name) != null) {
                throw conflict("channel_name_exists", "渠道名称已存在")
            }
            val modelMappings = normalizeModelMappings(request).orEmpty()
            val keys = normalizeNewKeys(app, request, modelMappings)
            val record = app.channelRepository.create(
                name = request.name,
                baseUrl = request.baseUrl,
                keys = keys,
                authType = request.authType ?: "bearer",
                protocols = protocols,
                protocolAuthTypes = request.protocolAuthTypes ?: emptyMap(),
                enabled = request.enabled,
                modelMappings = modelMappings,
                priority = request.priority ?: 1,
                requestOverrides = request.requestOverrides ?: UpstreamRequestOverrides(),
                quota = request.quota?.toSettings() ?: ChannelQuotaSettings(),
                quotaCredential = request.quota?.credential?.trim()?.takeIf { it.isNotEmpty() }?.let(app.credentialCipher::encrypt),
            )
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/channels/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            val request = call.receive<ChannelRequest>()
            validateChannel(request, credentialRequired = false)
            val protocols = request.protocols?.let(::parseProtocols)
            val updated = channelUpdateMutex.withLock {
                val existing = app.channelRepository.find(id)
                    ?: throw notFound("channel_not_found", "渠道不存在")
                val sameName = app.channelRepository.findByName(request.name)
                if (sameName != null && sameName.id != id) throw conflict("channel_name_exists", "渠道名称已存在")
                val encrypted = request.credential?.takeIf { it.isNotBlank() }?.let(app.credentialCipher::encrypt)
                val requestedMappings = normalizeModelMappings(request)
                val modelMappings =
                    if (request.modelMappings == null && request.models != null && requestedMappings != null) {
                        val existingByUpstream = existing.modelMappings.associateBy { it.upstreamModel }
                        requestedMappings.map { existingByUpstream[it.upstreamModel] ?: it }
                    } else {
                        requestedMappings
                    }
                val keys = normalizeUpdatedKeys(app, existing, request, modelMappings, encrypted)
                withContext(NonCancellable) {
                    val updated = app.channelRepository.update(
                        id = existing.id,
                        name = request.name,
                        baseUrl = request.baseUrl,
                        keys = keys,
                        authType = request.authType,
                        protocols = protocols,
                        protocolAuthTypes = request.protocolAuthTypes,
                        enabled = request.enabled,
                        modelMappings = modelMappings,
                        priority = request.priority,
                        requestOverrides = request.requestOverrides,
                        quota = request.quota?.toSettings(),
                        quotaCredential = request.quota?.credential?.trim()?.takeIf { it.isNotEmpty() }?.let(app.credentialCipher::encrypt),
                        clearQuotaCredential = request.quota?.clearCredential == true,
                    ) ?: throw notFound("channel_not_found", "渠道不存在")
                    updated.keys.filter { key ->
                        existing.keys.firstOrNull { it.id == key.id }?.stateVersion != key.stateVersion
                    }.forEach { app.candidateCooldown.clearKey(updated.id, it.id) }
                    val previousById = existing.keys.associateBy { it.id }
                    updated.keys.forEach { key ->
                        if (previousById[key.id]?.rateLimit != key.rateLimit) {
                            app.channelKeyRateLimiter.updatePolicy(updated.id, key.id, key.rateLimit)
                        }
                    }
                    (existing.keys.map { it.id }.toSet() - updated.keys.map { it.id }.toSet()).forEach { keyId ->
                        app.channelKeyRateLimiter.clearKey(updated.id, keyId)
                    }
                    updated
                }
            }
            call.respond(updated.toResponse())
        }

        post("/channels/{id}/cooldown/clear") {
            call.requireAdmin(app)
            val channel = app.channelRepository.find(call.requiredParam("id", "缺少渠道 id"))
                ?: throw notFound("channel_not_found", "渠道不存在")
            val request = call.receive<ClearCooldownRequest>()
            val baselineSeq = app.cooldownStateWriter.currentSeq()
            val cleared = when (request.scope) {
                "channel" -> app.candidateCooldown.clearChannel(channel.id)
                "key" -> {
                    val keyId = request.keyId?.trim().orEmpty()
                    if (keyId.isEmpty()) throw badRequest("missing_key_id", "解除密钥冷却需要 keyId")
                    if (channel.keys.none { it.id == keyId }) throw notFound("channel_key_not_found", "渠道密钥不存在")
                    app.candidateCooldown.clearKey(channel.id, keyId)
                }
                "candidate" -> {
                    val keyId = request.keyId?.trim().orEmpty()
                    val upstreamModel = request.upstreamModel?.trim().orEmpty()
                    if (keyId.isEmpty() || upstreamModel.isEmpty()) {
                        throw badRequest("missing_cooldown_target", "解除候选冷却需要 keyId 和 upstreamModel")
                    }
                    if (channel.keys.none { it.id == keyId }) throw notFound("channel_key_not_found", "渠道密钥不存在")
                    app.candidateCooldown.clearCandidate(channel.id, keyId, upstreamModel)
                }
                else -> throw badRequest("invalid_cooldown_scope", "scope 仅支持 channel、key、candidate")
            }
            if (!app.cooldownStateWriter.deleteNow(cleared.toSet(), baselineSeq, 5_000)) {
                throw GatewayException(
                    HttpStatusCode.ServiceUnavailable,
                    "cooldown_persist_failed",
                    "冷却状态持久化失败，请重试",
                    "api_error",
                )
            }
            app.candidateCooldown.confirmDeletions(cleared)
            call.markAdminAuditDetails(buildJsonObject {
                put("channelId", channel.id)
                put("scope", request.scope)
                request.keyId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("keyId", it) }
                request.upstreamModel?.trim()?.takeIf { it.isNotEmpty() }?.let { put("upstreamModel", it) }
            })
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/channels/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少渠道 id")
            channelUpdateMutex.withLock {
                val channel = app.channelRepository.find(id) ?: throw notFound("channel_not_found", "渠道不存在")
                withContext(NonCancellable) {
                    if (!app.channelRepository.delete(id)) throw notFound("channel_not_found", "渠道不存在")
                    channel.keys.forEach { app.channelKeyRateLimiter.clearKey(channel.id, it.id) }
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/oauth-providers") {
            call.requireAdmin(app)
            val configured = app.oauthTokenProviders.available()
            call.respond(
                OAuthProvider.entries.map { provider ->
                    OAuthProviderResponse(
                        id = provider.value,
                        label = OAUTH_PROVIDER_LABELS[provider] ?: provider.value,
                        configured = provider in configured,
                        loginSupported = when (provider) {
                            OAuthProvider.CHATGPT -> app.config.oauth.chatGpt.supportsLogin
                            OAuthProvider.ANTIGRAVITY -> app.config.oauth.antigravity?.supportsLogin == true
                        },
                    )
                },
            )
        }

        get("/oauth-accounts") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            val channelsById = app.channelRepository.list().associateBy { it.id }
            val accounts = app.oauthAccountRepository.listAll().map { account ->
                account.toResponse(
                    channels = account.channelIds.sorted().mapNotNull { channelId ->
                        channelsById[channelId]?.let { channel -> OAuthAccountChannelResponse(channel.id, channel.name) }
                    },
                    policy = account.policy,
                    runtime = app.oauthAccountScheduler.runtimeState(account.id, account.policy),
                )
            }
            call.respond(accounts)
        }

        post("/oauth-accounts") {
            call.requireAdmin(app)
            val request = call.receive<OAuthAccountImportRequest>()
            val provider = OAuthProvider.fromValue(request.provider.trim().lowercase())
                ?: throw badRequest("invalid_oauth_provider", "provider 仅支持 chatgpt 或 antigravity")
            if (app.oauthTokenProviders.find(provider) == null) {
                throw badRequest("oauth_provider_not_configured", "OAuth 提供方 ${provider.value} 未配置，请先设置客户端参数")
            }
            val refreshToken = request.refreshToken.trim()
            if (refreshToken.length > MAX_OAUTH_CREDENTIAL_CHARS) {
                throw badRequest("invalid_oauth_refresh_token", "refresh token 长度超出限制")
            }
            if (refreshToken.isEmpty()) throw badRequest("invalid_oauth_refresh_token", "refresh token 不能为空")
            val accessToken = request.accessToken?.trim()?.takeIf { it.isNotEmpty() }
            val expiresAt = request.accessTokenExpiresAt
            if ((accessToken == null) != (expiresAt == null)) {
                throw badRequest("invalid_oauth_access_token", "access token 与到期时间必须同时提供")
            }
            if (accessToken != null && accessToken.length > MAX_OAUTH_CREDENTIAL_CHARS) {
                throw badRequest("invalid_oauth_access_token", "access token 长度超出限制")
            }
            if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
                throw badRequest("invalid_oauth_access_token", "access token 到期时间必须晚于当前时间")
            }
            val proxyUrl = request.proxyUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (proxyUrl != null && proxyUrl.length > MAX_OAUTH_PROXY_CHARS) {
                throw badRequest("invalid_oauth_proxy", "账号代理地址长度超出限制")
            }
            validateOAuthProxyUrl(app, proxyUrl)
            val channelIds = request.channelIds.map(String::trim).filter(String::isNotEmpty).distinct()
            val existingChannelIds = app.channelRepository.list().map { it.id }.toSet()
            channelIds.firstOrNull { it !in existingChannelIds }?.let { missing ->
                throw badRequest("invalid_oauth_channels", "渠道不存在: $missing")
            }
            if (request.priority !in 1..1000) {
                throw badRequest("invalid_oauth_priority", "账号优先级必须在 1..1000 之间")
            }
            val account = try {
                app.oauthAccountRepository.create(
                    NewOAuthAccount(
                        name = request.name?.trim()?.takeIf { it.isNotEmpty() }
                            ?: request.accountEmail?.trim()?.takeIf { it.isNotEmpty() }
                            ?: OAUTH_PROVIDER_LABELS[provider]
                            ?: provider.value,
                        provider = provider,
                        accountEmail = request.accountEmail?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OAUTH_EMAIL_CHARS),
                        accountPlan = request.accountPlan?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OAUTH_PLAN_CHARS),
                        refreshToken = app.credentialCipher.encrypt(refreshToken),
                        accessToken = accessToken?.let(app.credentialCipher::encrypt),
                        accessTokenExpiresAt = expiresAt,
                        tokenType = request.tokenType?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "Bearer",
                        scope = request.scope?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OAUTH_SCOPE_CHARS),
                        proxyUrl = proxyUrl,
                        channelIds = channelIds,
                        models = request.models,
                        priority = request.priority,
                    ),
                )
            } catch (cause: Throwable) {
                if (isUniqueViolation(cause)) {
                    throw conflict("oauth_account_email_exists", "同一提供方下该邮箱已存在账号")
                }
                throw cause
            }
            clearOAuthAccountCooldown(app, account)
            call.markAdminAuditDetails(buildJsonObject {
                put("accountId", account.id)
                put("provider", provider.value)
                put("channels", channelIds.joinToString(","))
                proxyUrl?.let { put("proxyUrl", it) }
            })
            call.respond(oauthAccountResponse(app, account))
        }

        delete("/oauth-accounts/{id}") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            val existing = app.oauthAccountRepository.findDetailed(accountId)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            if (!app.oauthAccountRepository.delete(accountId)) {
                throw notFound("oauth_account_not_found", "订阅账号不存在")
            }
            clearOAuthAccountCooldown(app, existing)
            call.markAdminAuditDetails(buildJsonObject { put("accountId", accountId) })
            call.respond(HttpStatusCode.NoContent)
        }

        put("/oauth-accounts/{id}") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            val existing = app.oauthAccountRepository.find(accountId)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            val request = call.receive<OAuthAccountImportRequest>()
            val provider = OAuthProvider.fromValue(request.provider.trim().lowercase())
                ?: throw badRequest("invalid_oauth_provider", "provider 仅支持 chatgpt 或 antigravity")
            if (app.oauthTokenProviders.find(provider) == null) {
                throw badRequest("oauth_provider_not_configured", "OAuth 提供方 ${provider.value} 未配置，请先设置客户端参数")
            }
            if (existing.provider != provider) {
                throw badRequest("invalid_oauth_provider", "重新授权不能修改账号的提供方")
            }
            val refreshToken = request.refreshToken.trim()
            if (refreshToken.length > MAX_OAUTH_CREDENTIAL_CHARS) {
                throw badRequest("invalid_oauth_refresh_token", "refresh token 长度超出限制")
            }
            val accessToken = request.accessToken?.trim()?.takeIf { it.isNotEmpty() }
            val expiresAt = request.accessTokenExpiresAt
            if ((accessToken == null) != (expiresAt == null)) {
                throw badRequest("invalid_oauth_access_token", "access token 与到期时间必须同时提供")
            }
            val proxyUrl = request.proxyUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (proxyUrl != null && proxyUrl.length > MAX_OAUTH_PROXY_CHARS) {
                throw badRequest("invalid_oauth_proxy", "账号代理地址长度超出限制")
            }
            validateOAuthProxyUrl(app, proxyUrl)
            val account = try {
                app.oauthAccountRepository.updateCredentials(
                    accountId = accountId,
                    input = OAuthAccountCredentialUpdate(
                        provider = provider,
                        accountEmail = request.accountEmail?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OAUTH_EMAIL_CHARS),
                        accountPlan = request.accountPlan?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OAUTH_PLAN_CHARS),
                        refreshToken = refreshToken.takeIf { it.isNotEmpty() }?.let(app.credentialCipher::encrypt),
                        accessToken = accessToken?.let(app.credentialCipher::encrypt),
                        accessTokenExpiresAt = expiresAt,
                        tokenType = request.tokenType?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "Bearer",
                        scope = request.scope?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_OAUTH_SCOPE_CHARS),
                        proxyUrl = proxyUrl,
                    ),
                ) ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            } catch (cause: Throwable) {
                if (isUniqueViolation(cause)) {
                    throw conflict("oauth_account_email_exists", "同一提供方下该邮箱已存在账号")
                }
                throw cause
            }
            clearOAuthAccountCooldown(app, account)
            call.markAdminAuditDetails(buildJsonObject {
                put("accountId", account.id)
                put("provider", provider.value)
                proxyUrl?.let { put("proxyUrl", it) }
            })
            call.respond(oauthAccountResponse(app, account))
        }

        put("/oauth-accounts/{id}/settings") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            val request = call.receive<OAuthAccountSettingsRequest>()
            if (request.priority != null && request.priority !in 1..1000) {
                throw badRequest("invalid_oauth_priority", "账号优先级必须在 1..1000 之间")
            }
            val proxyUrl = request.proxyUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (proxyUrl != null && proxyUrl.length > MAX_OAUTH_PROXY_CHARS) {
                throw badRequest("invalid_oauth_proxy", "账号代理地址长度超出限制")
            }
            if (request.clearProxy && proxyUrl != null) {
                throw badRequest("invalid_oauth_proxy", "不能同时更换和清除账号代理")
            }
            validateOAuthProxyUrl(app, proxyUrl)
            val account = app.oauthAccountRepository.updateSettings(
                accountId = accountId,
                name = request.name?.trim()?.takeIf { it.isNotEmpty() },
                priority = request.priority,
                enabled = request.enabled,
                proxyUrl = proxyUrl,
                clearProxy = request.clearProxy,
            ) ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            call.markAdminAuditDetails(buildJsonObject {
                put("accountId", accountId)
                request.name?.let { put("name", it) }
                request.priority?.let { put("priority", it) }
                request.enabled?.let { put("enabled", it) }
            })
            call.respond(oauthAccountResponse(app, account))
        }

        put("/oauth-accounts/{id}/channels") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            val request = call.receive<OAuthAccountChannelsRequest>()
            val channels = app.channelRepository.list()
            val channelsById = channels.associateBy { it.id }
            val channelIds = request.channelIds.map(String::trim).filter(String::isNotEmpty).distinct()
            channelIds.firstOrNull { it !in channelsById }?.let { missing ->
                throw badRequest("invalid_oauth_channels", "渠道不存在: $missing")
            }
            val account = app.oauthAccountRepository.setChannels(accountId, channelIds)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            call.markAdminAuditDetails(buildJsonObject {
                put("accountId", accountId)
                put("channels", channelIds.joinToString(","))
            })
            call.respond(
                account.toResponse(
                    channels = account.channelIds.sorted().mapNotNull { id ->
                        channelsById[id]?.let { channel -> OAuthAccountChannelResponse(channel.id, channel.name) }
                    },
                    policy = account.policy,
                    runtime = app.oauthAccountScheduler.runtimeState(account.id, account.policy),
                ),
            )
        }

        put("/oauth-accounts/{id}/models") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            val request = call.receive<OAuthAccountModelsRequest>()
            val models = request.models.map(String::trim).filter(String::isNotEmpty).distinct()
            val account = app.oauthAccountRepository.setModels(accountId, models)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            call.markAdminAuditDetails(buildJsonObject {
                put("accountId", accountId)
                put("modelCount", models.size)
            })
            call.respond(oauthAccountResponse(app, account))
        }

        post("/oauth-login/start") {
            call.requireAdmin(app)
            val request = call.receive<OAuthLoginStartRequest>()
            val provider = OAuthProvider.fromValue(request.provider.trim().lowercase())
                ?: throw badRequest("invalid_oauth_provider", "provider 仅支持 chatgpt 或 antigravity")
            if (app.oauthTokenProviders.find(provider) == null) {
                throw badRequest("oauth_provider_not_configured", "OAuth 提供方 ${provider.value} 未配置，请先设置客户端参数")
            }
            val target = request.accountId?.trim()?.takeIf { it.isNotEmpty() }?.let { accountId ->
                app.oauthAccountRepository.findDetailed(accountId)
                    ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            }
            if (target != null && target.provider != provider) {
                throw badRequest("invalid_oauth_provider", "重新授权不能修改账号的提供方")
            }
            val proxyUrl = request.proxyUrl?.trim()?.takeIf { it.isNotEmpty() }
            if (proxyUrl != null && proxyUrl.length > MAX_OAUTH_PROXY_CHARS) {
                throw badRequest("invalid_oauth_proxy", "账号代理地址长度超出限制")
            }
            validateOAuthProxyUrl(app, proxyUrl)
            val existingChannelIds = app.channelRepository.list().map { it.id }.toSet()
            val channelIds = request.channelIds.map(String::trim).filter(String::isNotEmpty).distinct()
                .ifEmpty { target?.channelIds?.toList().orEmpty() }
            channelIds.firstOrNull { it !in existingChannelIds }?.let { missing ->
                throw badRequest("invalid_oauth_channels", "渠道不存在: $missing")
            }
            when (
                val result = app.oauthLoginService.start(
                    provider = provider,
                    accountId = target?.id,
                    name = request.name?.trim()?.takeIf { it.isNotEmpty() },
                    channelIds = channelIds,
                    proxyUrl = proxyUrl,
                    credentialFingerprint = target?.credentialFingerprint(),
                )
            ) {
                OAuthLoginStartResult.UnsupportedProvider -> throw badRequest(
                    "oauth_login_unsupported",
                    "该提供方未配置授权地址与回调地址，请改为导入 refresh token",
                )
                is OAuthLoginStartResult.Started -> {
                    call.markAdminAuditDetails(buildJsonObject {
                        target?.let { put("accountId", it.id) }
                        put("provider", provider.value)
                        put("channels", channelIds.joinToString(","))
                        proxyUrl?.let { put("proxyUrl", it) }
                    })
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respond(
                        OAuthLoginStartResponse(
                            loginId = result.loginId,
                            authorizeUrl = result.authorizeUrl,
                            redirectUri = result.redirectUri,
                            expiresAt = result.expiresAt,
                        ),
                    )
                }
            }
        }

        post("/oauth-login/{loginId}/complete") {
            call.requireAdmin(app)
            val loginId = call.requiredParam("loginId", "缺少登录会话 id")
            val request = call.receive<OAuthLoginCompleteRequest>()
            if (request.callbackUrl.length > MAX_OAUTH_CALLBACK_CHARS) {
                throw badRequest("oauth_login_invalid_callback", "回调地址长度超出限制")
            }
            val result = try {
                app.oauthLoginService.complete(loginId, request.callbackUrl)
            } catch (cause: Throwable) {
                if (isUniqueViolation(cause)) {
                    throw conflict(
                        "oauth_account_email_exists",
                        "同一提供方下该邮箱已存在账号，删除原账号后可用同一登录会话重试",
                    )
                }
                throw cause
            }
            when (result) {
                OAuthLoginResult.Expired -> throw badRequest(
                    "oauth_login_expired",
                    "登录会话不存在或已过期，请重新生成登录链接",
                )
                is OAuthLoginResult.InvalidRequest -> throw badRequest("oauth_login_invalid_callback", result.reason)
                OAuthLoginResult.StaleTarget -> {
                    call.markAdminAuditDetails(buildJsonObject { put("result", "stale") })
                    throw conflict("oauth_login_stale", "账号凭据在登录期间已被修改，请重新发起登录")
                }
                OAuthLoginResult.UnsupportedProvider -> throw badRequest(
                    "oauth_login_unsupported",
                    "该提供方未配置授权地址与回调地址，请改为导入 refresh token",
                )
                is OAuthLoginResult.Failed -> {
                    call.markAdminAuditDetails(buildJsonObject {
                        put("result", "failed")
                        put("errorCode", result.errorCode)
                    })
                    throw GatewayException(
                        HttpStatusCode.BadGateway,
                        "oauth_login_failed",
                        "登录换取 token 失败（${result.errorCode}），请重新发起登录",
                        "api_error",
                    )
                }
                is OAuthLoginResult.Completed -> {
                    clearOAuthAccountCooldown(app, result.account)
                    call.markAdminAuditDetails(buildJsonObject {
                        put("accountId", result.account.id)
                        put("provider", result.account.provider.value)
                        put("result", "success")
                    })
                    call.response.headers.append("Cache-Control", "no-store")
                    call.respond(oauthAccountResponse(app, result.account))
                }
            }
        }

        put("/oauth-accounts/{id}/policy") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            if (app.oauthAccountRepository.find(accountId) == null) {
                throw notFound("oauth_account_not_found", "订阅账号不存在")
            }
            val request = call.receive<OAuthAccountPolicyRequest>()
            val policy = try {
                OAuthAccountPolicy(
                    windowLimit = request.windowLimit,
                    windowSeconds = request.windowSeconds,
                    maxConcurrentRequests = request.maxConcurrentRequests,
                )
            } catch (cause: IllegalArgumentException) {
                throw badRequest("invalid_oauth_policy", cause.message ?: "账号调度策略无效")
            }
            if (!app.oauthAccountRepository.updatePolicy(accountId, policy)) {
                throw notFound("oauth_account_not_found", "订阅账号不存在")
            }
            call.markAdminAuditDetails(buildJsonObject {
                put("accountId", accountId)
                request.windowLimit?.let { put("windowLimit", it) }
                put("windowSeconds", request.windowSeconds)
                put("maxConcurrentRequests", request.maxConcurrentRequests)
            })
            val account = app.oauthAccountRepository.findDetailed(accountId)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            call.respond(oauthAccountResponse(app, account))
        }

        post("/oauth-accounts/{id}/refresh") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            when (val result = app.oauthTokenService.refreshNow(accountId)) {
                OAuthRefreshResult.NotOAuthAccount -> throw notFound("oauth_account_not_found", "订阅账号不存在")
                is OAuthRefreshResult.Failed -> {
                    call.markAdminAuditDetails(buildJsonObject {
                        put("accountId", accountId)
                        put("result", "failed")
                        result.errorCode?.let { put("errorCode", it) }
                    })
                    throw GatewayException(
                        HttpStatusCode.BadGateway,
                        "oauth_refresh_failed",
                        when (result.reason) {
                            OAuthUnavailableReason.REAUTH_REQUIRED -> "refresh token 已失效，请重新授权"
                            OAuthUnavailableReason.PROVIDER_NOT_CONFIGURED -> "OAuth 提供方未配置"
                            OAuthUnavailableReason.REFRESH_FAILED -> "刷新失败，请稍后重试"
                        },
                        "api_error",
                    )
                }
                is OAuthRefreshResult.Refreshed -> {
                    call.markAdminAuditDetails(buildJsonObject {
                        put("accountId", accountId)
                        put("result", "success")
                    })
                    call.respond(oauthAccountResponse(app, result.account))
                }
            }
        }

        post("/oauth-accounts/{id}/quota/refresh") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            when (val result = app.oauthQuotaService.refreshNow(accountId)) {
                OAuthQuotaResult.NotFound -> throw notFound("oauth_account_not_found", "订阅账号不存在")
                OAuthQuotaResult.NotSupported -> throw badRequest(
                    "oauth_quota_unsupported",
                    "该账号不支持额度查询，或还没有可用的 access token",
                )
                is OAuthQuotaResult.Failed -> {
                    call.markAdminAuditDetails(buildJsonObject {
                        put("accountId", accountId)
                        put("result", "failed")
                        put("errorCode", result.errorCode)
                    })
                    throw GatewayException(
                        HttpStatusCode.BadGateway,
                        "oauth_quota_failed",
                        "额度读取失败（${result.errorCode}），请检查账号凭据与代理出口",
                        "api_error",
                    )
                }
                is OAuthQuotaResult.Refreshed -> {
                    call.markAdminAuditDetails(buildJsonObject {
                        put("accountId", accountId)
                        put("result", "success")
                    })
                    call.respond(oauthAccountResponse(app, result.account))
                }
            }
        }

        post("/oauth-accounts/{id}/cooldown/clear") {
            call.requireAdmin(app)
            val accountId = call.requiredParam("id", "缺少账号 id")
            val account = app.oauthAccountRepository.findDetailed(accountId)
                ?: throw notFound("oauth_account_not_found", "订阅账号不存在")
            clearOAuthAccountCooldown(app, account)
            call.markAdminAuditDetails(buildJsonObject { put("accountId", accountId) })
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
            val prices = app.modelPriceRepository.list().map { price -> price.toResponse() }
            call.respond(prices)
        }

        get("/model-default-prices") {
            call.requireAdmin(app)
            val prices = app.modelDefaultPriceRepository.list().map { price -> price.toResponse() }
            call.respond(prices)
        }

        post("/model-default-prices") {
            call.requireAdmin(app)
            val request = call.receive<ModelDefaultPriceRequest>()
            val price = validateDefaultModelPrice(request)
            if (app.modelDefaultPriceRepository.find(price.modelId) != null) {
                throw conflict("model_default_price_exists", "该模型的默认单价已存在")
            }
            val record = app.modelDefaultPriceRepository.create(
                modelId = price.modelId,
                currency = price.values.currency,
                inputPricePerMillion = price.values.input,
                outputPricePerMillion = price.values.output,
                cachedInputPricePerMillion = price.values.cached,
                cachedInputWritePricePerMillion = price.values.cachedWrite,
                pricingMode = price.values.pricingMode,
                perRequestPrice = price.values.perRequest,
                priceTiers = price.values.priceTiers,
            ) ?: throw conflict("model_default_price_exists", "该模型的默认单价已存在")
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/model-default-prices/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少默认价 id")
            val request = call.receive<ModelDefaultPriceRequest>()
            val price = validateDefaultModelPrice(request)
            val existing = app.modelDefaultPriceRepository.findById(id) ?: throw notFound("model_default_price_not_found", "模型默认单价不存在")
            if (existing.modelId != price.modelId) {
                throw badRequest("model_default_price_model_immutable", "已存在的默认单价不可修改模型 ID，请新建或删除后重新配置")
            }
            val updated = try {
                app.modelDefaultPriceRepository.update(
                    id = id,
                    modelId = price.modelId,
                    currency = price.values.currency,
                    inputPricePerMillion = price.values.input,
                    outputPricePerMillion = price.values.output,
                    cachedInputPricePerMillion = price.values.cached,
                    cachedInputWritePricePerMillion = price.values.cachedWrite,
                    pricingMode = price.values.pricingMode,
                    perRequestPrice = price.values.perRequest,
                    priceTiers = price.values.priceTiers,
                )
            } catch (cause: Throwable) {
                if (isUniqueViolation(cause)) {
                    throw conflict("model_default_price_exists", "该模型的默认单价已存在")
                }
                throw cause
            } ?: throw notFound("model_default_price_not_found", "模型默认单价不存在")
            call.respond(updated.toResponse())
        }

        delete("/model-default-prices/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少默认价 id")
            if (!app.modelDefaultPriceRepository.delete(id)) {
                throw notFound("model_default_price_not_found", "模型默认单价不存在")
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/model-prices/batch") {
            call.requireAdmin(app)
            val request = call.receive<ModelPriceBatchRequest>()
            val batch = validateModelPriceBatch(app, request)
            val records = app.modelPriceRepository.upsertBatch(
                channelId = batch.channelId,
                upstreamModels = batch.upstreamModels,
                currency = batch.values.currency,
                inputPricePerMillion = batch.values.input,
                outputPricePerMillion = batch.values.output,
                cachedInputPricePerMillion = batch.values.cached,
                cachedInputWritePricePerMillion = batch.values.cachedWrite,
                pricingMode = batch.values.pricingMode,
                perRequestPrice = batch.values.perRequest,
                priceTiers = batch.values.priceTiers,
            )
            call.respond(records.map { it.toResponse() })
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
                currency = price.values.currency,
                inputPricePerMillion = price.values.input,
                outputPricePerMillion = price.values.output,
                cachedInputPricePerMillion = price.values.cached,
                cachedInputWritePricePerMillion = price.values.cachedWrite,
                pricingMode = price.values.pricingMode,
                perRequestPrice = price.values.perRequest,
                priceTiers = price.values.priceTiers,
            ) ?: throw conflict("model_price_exists", "该渠道和上游模型的计费单价已存在")
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        put("/model-prices/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少价格 id")
            val request = call.receive<ModelPriceRequest>()
            val price = validateModelPrice(app, request)
            val existing = app.modelPriceRepository.findById(id) ?: throw notFound("model_price_not_found", "计费单价不存在")
            if (existing.channelId != price.channelId || existing.upstreamModel != price.upstreamModel) {
                throw badRequest("model_price_key_immutable", "已存在的单价不可修改渠道或上游模型，请新建或删除后重新配置")
            }
            val updated = app.modelPriceRepository.update(
                id = id,
                channelId = price.channelId,
                upstreamModel = price.upstreamModel,
                currency = price.values.currency,
                inputPricePerMillion = price.values.input,
                outputPricePerMillion = price.values.output,
                cachedInputPricePerMillion = price.values.cached,
                cachedInputWritePricePerMillion = price.values.cachedWrite,
                pricingMode = price.values.pricingMode,
                perRequestPrice = price.values.perRequest,
                priceTiers = price.values.priceTiers,
            ) ?: throw notFound("model_price_not_found", "计费单价不存在")
            call.respond(updated.toResponse())
        }

        delete("/model-prices/{id}") {
            call.requireAdmin(app)
            val id = call.requiredParam("id", "缺少价格 id")
            if (!app.modelPriceRepository.delete(id)) throw notFound("model_price_not_found", "计费单价不存在")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/channel-health") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            val window = call.request.queryParameters["window"] ?: "24h"
            if (window !in dev.soven.llmgateway.report.healthWindows) throw badRequest("invalid_health_window", "时间范围仅支持 24h、7d、15d、30d")
            val channels = app.channelRepository.listHealthChannelOverviews()
            val cooldowns = channels.associate { channel ->
                channel.id to app.candidateCooldown.channelCooldownSummary(
                    channel.id,
                    channel.keys.map { it.id to it.models },
                )
            }
            call.respond(
                app.channelHealthRepository.report(
                    window,
                    channels.associate { it.id to it.enabled },
                    app.config.retention.retentionDays,
                    cooldowns = cooldowns,
                ),
            )
        }

        get("/channels/{id}/health") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            val window = call.request.queryParameters["window"] ?: "24h"
            if (window !in dev.soven.llmgateway.report.healthWindows) throw badRequest("invalid_health_window", "时间范围仅支持 24h、7d、15d、30d")
            val channel = app.channelRepository.find(call.requiredParam("id", "缺少渠道 id"))
                ?: throw notFound("channel_not_found", "渠道不存在")
            val availability = channel.keys.flatMap { key ->
                key.models.distinct().map { model ->
                    val cooldown = app.candidateCooldown.cooldownState(channel.id, key.id, model)
                    val state = when {
                        !channel.enabled -> "CHANNEL_DISABLED"
                        !key.isRoutable() -> key.state.name
                        cooldown != null -> "COOLDOWN"
                        else -> "ACTIVE"
                    }
                    dev.soven.llmgateway.report.HealthAvailability(
                        key.id, key.name, model, state,
                        when (state) {
                            "COOLDOWN" -> cooldown?.reason
                            else -> key.autoDisableReason
                        },
                        when (state) {
                            "COOLDOWN" -> cooldown?.until
                            "AUTO_TEMPORARY", "AUTO_PERMANENT" -> key.autoDisableUntil
                            else -> null
                        },
                        cooldownScope = if (state == "COOLDOWN") cooldown?.scope else null,
                    )
                }
            }
            call.respond(
                app.channelHealthRepository.detail(
                    window, channel, app.config.retention.retentionDays, availability,
                    cooldown = app.candidateCooldown.channelCooldownSummary(
                        channel.id,
                        channel.keys.map { it.id to it.models },
                    ),
                ),
            )
        }

        get("/occupancy") {
            call.requireAdmin(app)
            call.response.headers.append("Cache-Control", "no-store")
            val channels = app.upstreamOccupancy.snapshot().entries
                .groupBy({ it.key.channelId }, { it.key.keyId to it.value })
                .map { (channelId, keys) ->
                    ChannelOccupancyResponse(
                        channelId = channelId,
                        concurrentRequests = keys.sumOf { it.second },
                        keys = keys
                            .sortedBy { it.first }
                            .map { (keyId, count) -> ChannelKeyOccupancyResponse(keyId, count) },
                    )
                }
                .sortedBy { it.channelId }
            val clientKeys = app.requestLimitService.clientKeyOccupancy()
                .map { (clientKeyId, count) -> ClientKeyOccupancyResponse(clientKeyId, count) }
                .sortedBy { it.clientKeyId }
            call.respond(OccupancyResponse(System.currentTimeMillis(), channels, clientKeys))
        }

        get("/request-logs") {
            call.requireAdmin(app)
            val limit = call.queryInt("limit", default = 100, min = 1, max = 1000)
            val beforeId = call.queryLongOrNull("beforeId")
            call.respond(app.requestLogRepository.listForDisplay(limit, beforeId).map { it.toResponse() })
        }

        get("/usage-report") {
            call.requireAdmin(app)
            val now = System.currentTimeMillis()
            val to = call.queryLongOrNull("to") ?: now
            val from = call.queryLongOrNull("from") ?: (to - 7 * 86_400_000L)
            if (from > to) throw badRequest("invalid_report_range", "报表开始时间不能晚于结束时间")
            if (to - from > 30 * 86_400_000L) throw badRequest("invalid_report_range", "报表时间范围不能超过 30 天")
            val report = app.requestLogRepository.report(
                UsageReportQuery(
                    from = from,
                    to = to,
                    clientKeyId = call.request.queryParameters["clientKeyId"]?.takeIf { it.isNotBlank() },
                    channelId = call.request.queryParameters["channelId"]?.takeIf { it.isNotBlank() },
                    model = call.request.queryParameters["model"]?.takeIf { it.isNotBlank() },
                    protocol = call.request.queryParameters["protocol"]?.takeIf { it.isNotBlank() },
                    timezoneOffsetMinutes = call.queryInt("timezoneOffsetMinutes", default = 0, min = -840, max = 840),
                ),
            )
            call.respond(report.toResponse())
        }

        get("/request-log-retention") {
            call.requireAdmin(app)
            call.respond(app.logRetentionService.currentPolicy().toResponse(System.currentTimeMillis()))
        }

        put("/request-log-retention") {
            call.requireAdmin(app)
            val request = call.receive<UpdateRetentionPolicyRequest>()
            app.updateLogCleanupEnabled(request.cleanupEnabled)
            call.respond(app.logRetentionService.currentPolicy().toResponse(System.currentTimeMillis()))
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

/** 汇总渠道当前冷却：候选总数、冷却数、最早恢复时间和对应原因。 */
private fun CandidateCooldown.channelCooldownSummary(
    channelId: String,
    keys: List<Pair<String, List<String>>>,
): ChannelCooldownSummary {
    var total = 0
    var cooling = 0
    var earliest: Long? = null
    var reason: String? = null
    for ((keyId, models) in keys) {
        for (model in models.distinct()) {
            total += 1
            val state = cooldownState(channelId, keyId, model) ?: continue
            cooling += 1
            val current = earliest
            if (current == null || state.until < current) {
                earliest = state.until
                reason = state.reason
            }
        }
    }
    return ChannelCooldownSummary(earliest, reason, cooling, total)
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

private fun parseProtocols(values: List<String>): List<GatewayProtocol> {
    if (values.isEmpty()) throw badRequest("invalid_channel", "渠道至少需要支持一个协议")
    return values.map { value ->
        val normalized = value.trim()
        GatewayProtocol.fromValue(normalized)
            ?: throw badRequest("invalid_channel", "不支持的协议: $value")
    }.distinct()
}

/** 停用密钥的错误：临时停用未到期与人工/永久停用使用不同错误码，便于排障。 */
private fun channelKeyUnavailable(key: ChannelKeyRecord): GatewayException =
    if (key.state == ChannelKeyState.AUTO_TEMPORARY) {
        val message = if (key.probeUntil?.let { it > System.currentTimeMillis() } == true) {
            "密钥「${key.name}」正在探测恢复中"
        } else {
            "密钥「${key.name}」临时停用中，尚未到恢复时间"
        }
        badRequest("channel_key_temporarily_disabled", message)
    } else {
        badRequest("channel_key_disabled", "渠道密钥已停用")
    }

/** 静态密钥复用具体的停用错误码；订阅账号统一返回凭据停用。 */
private fun credentialUnavailable(credential: UpstreamCredential): GatewayException =
    when (credential) {
        is ChannelKeyRecord -> channelKeyUnavailable(credential)
        else -> badRequest("channel_key_disabled", "渠道凭据已停用")
    }

/** 管理面渠道操作需要同时看到静态密钥与关联订阅账号。 */
private suspend fun channelWithCredentials(app: GatewayApplication, channel: ChannelRecord): ChannelRecord =
    channel.copy(oauthAccounts = app.oauthAccountRepository.listByChannel()[channel.id].orEmpty())

/** 选择显式指定的上游凭据（密钥或账号）；未指定时取优先级最高的可路由凭据。 */
private fun resolveChannelCredential(channel: ChannelRecord, credentialId: String?): UpstreamCredential {
    val credentials = channel.keys + channel.oauthAccounts
    if (!credentialId.isNullOrBlank()) {
        val credential = credentials.firstOrNull { it.id == credentialId }
            ?: throw notFound("channel_key_not_found", "渠道密钥或订阅账号不存在")
        if (!credential.isRoutable()) throw credentialUnavailable(credential)
        return credential
    }
    return credentials
        .filter { it.isRoutable() }
        .minWithOrNull(compareBy({ it.priority }, { it.name }, { it.id }))
        ?: throw badRequest("channel_key_unavailable", "渠道没有可用的上游凭据")
}

/** 为指定模型选择上游凭据；显式 id 必须支持该模型。 */
private fun resolveChannelCredentialForModel(
    channel: ChannelRecord,
    model: String,
    credentialId: String?,
): UpstreamCredential {
    if (!credentialId.isNullOrBlank()) {
        val credential = (channel.keys + channel.oauthAccounts).firstOrNull { it.id == credentialId }
            ?: throw notFound("channel_key_not_found", "渠道密钥或订阅账号不存在")
        if (!credential.isRoutable()) throw credentialUnavailable(credential)
        if (model !in channel.models) {
            throw badRequest("model_not_authorized", "渠道「${channel.name}」尚未授权模型「$model」")
        }
        if (!credential.supports(model)) {
            throw badRequest("model_not_authorized", "凭据「${credential.name}」未授权模型「$model」")
        }
        return credential
    }
    return channel.credentialsFor(model).firstOrNull()
        ?: throw badRequest("channel_key_unavailable", "没有启用凭据支持模型「$model」")
}

private fun validateChannel(request: ChannelRequest, credentialRequired: Boolean) {
    if (request.name.isBlank()) throw badRequest("invalid_channel", "渠道名称不能为空")
    if (!request.baseUrl.startsWith("http://") && !request.baseUrl.startsWith("https://")) {
        throw badRequest("invalid_channel", "渠道地址必须以 http:// 或 https:// 开头")
    }
    if (request.authType != null && request.authType !in SUPPORTED_UPSTREAM_AUTH_TYPES) {
        throw badRequest("invalid_channel", "上游鉴权方式必须是 bearer、anthropic 或 google")
    }
    validateProtocolAuthTypes(request.protocolAuthTypes)
    request.requestOverrides?.let(::validateUpstreamRequestOverrides)
    request.quota?.let(::validateQuotaSettings)
    if (request.priority != null && request.priority !in 1..1000) {
        throw badRequest("invalid_channel", "渠道优先级必须在 1..1000 之间")
    }
    if (!request.keys.isNullOrEmpty() && !request.credential.isNullOrBlank()) {
        throw badRequest("invalid_channel", "keys 与 credential 不能同时提交")
    }
    if (credentialRequired && request.credential.isNullOrBlank()) {
        if (request.keys.isNullOrEmpty()) throw badRequest("invalid_channel", "新建渠道必须提供上游凭据")
        if (request.keys.any { it.credential.isNullOrBlank() }) {
            throw badRequest("invalid_channel", "新建渠道的每个密钥都必须提供上游凭据")
        }
    }
}

private fun validateProtocolAuthTypes(protocolAuthTypes: Map<String, String>?) {
    protocolAuthTypes ?: return
    if (protocolAuthTypes.size > GatewayProtocol.entries.size) {
        throw badRequest("invalid_channel", "协议级鉴权配置数量无效")
    }
    protocolAuthTypes.forEach { (protocol, authType) ->
        if (GatewayProtocol.fromValue(protocol) == null) {
            throw badRequest("invalid_channel", "协议级鉴权配置包含未知协议：$protocol")
        }
        if (authType !in SUPPORTED_UPSTREAM_AUTH_TYPES) {
            throw badRequest("invalid_channel", "协议级鉴权方式必须是 bearer、anthropic 或 google")
        }
    }
}

private fun validateQuotaSettings(settings: ChannelQuotaSettingsRequest) {
    if (settings.providerId.isNotEmpty() && !Regex(ChannelQuotaProvider.ID_PATTERN).matches(settings.providerId)) {
        throw badRequest("invalid_quota_settings", "额度插件 ID 格式无效")
    }
    if (settings.config.toString().toByteArray(Charsets.UTF_8).size > 8192) {
        throw badRequest("invalid_quota_settings", "额度插件配置不能超过 8 KB")
    }
    val credential = settings.credential?.trim().orEmpty()
    if (credential.length > 4096 || credential.any { it.isISOControl() }) {
        throw badRequest("invalid_quota_settings", "额度查询凭据不能超过 4096 个字符或包含控制字符")
    }
    if (settings.clearCredential && credential.isNotEmpty()) {
        throw badRequest("invalid_quota_settings", "不能同时更换和清除额度查询凭据")
    }
}

/**
 * 新建渠道时的密钥归一化。未显式提交 keys 时，把旧版单个 credential 视为“默认密钥”。
 */
private fun normalizeNewKeys(
    app: GatewayApplication,
    request: ChannelRequest,
    modelMappings: List<ChannelModelRecord>,
): List<NewChannelKey> {
    val channelModels = modelMappings.map { it.upstreamModel }.toSet()
    if (request.keys.isNullOrEmpty()) {
        val credential = request.credential?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            NewChannelKey(
                name = DEFAULT_CHANNEL_KEY_NAME,
                credential = app.credentialCipher.encrypt(credential),
                models = channelModels.toList(),
            ),
        )
    }
    return normalizeKeys(request.keys, channelModels, app).map { key ->
        NewChannelKey(
            name = key.name,
            credential = key.credential ?: throw badRequest("invalid_channel", "新建渠道的每个密钥都必须提供上游凭据"),
            state = key.state ?: ChannelKeyState.ACTIVE,
            priority = key.priority,
            models = key.models,
            rateLimit = key.rateLimit ?: ChannelKeyRateLimitPolicy(),
        )
    }
}

/**
 * 更新渠道时的密钥归一化。
 *
 * 旧版请求只带 credential 时只修改唯一的默认密钥；渠道已有多个密钥时必须显式提交 keys，
 * 防止旧客户端误删密钥池。
 */
private fun normalizeUpdatedKeys(
    app: GatewayApplication,
    existing: ChannelRecord,
    request: ChannelRequest,
    modelMappings: List<ChannelModelRecord>?,
    encryptedCredential: dev.soven.llmgateway.security.EncryptedCredential?,
): List<ChannelKeyUpdate>? {
    val channelModels = (modelMappings ?: existing.modelMappings).map { it.upstreamModel }.toSet()
    val requested = request.keys
    if (requested != null) {
        val existingIds = existing.keys.map { it.id }.toSet()
        return normalizeKeys(requested, channelModels, app).map { key ->
            if (key.id != null && key.id !in existingIds) {
                throw badRequest("invalid_channel", "密钥不存在: ${key.id}")
            }
            if (key.id == null && key.credential == null) {
                throw badRequest("invalid_channel", "新建密钥「${key.name}」必须提供上游凭据")
            }
            ChannelKeyUpdate(
                id = key.id,
                name = key.name,
                credential = key.credential,
                state = key.state,
                priority = key.priority,
                models = key.models,
                rateLimit = key.rateLimit,
                stateVersion = key.stateVersion,
            )
        }
    }
    if (encryptedCredential == null && modelMappings == null) return null
    if (existing.keys.isEmpty()) {
        return if (encryptedCredential == null) {
            emptyList()
        } else {
            listOf(
                ChannelKeyUpdate(
                    name = DEFAULT_CHANNEL_KEY_NAME,
                    credential = encryptedCredential,
                    models = channelModels.toList(),
                ),
            )
        }
    }
    if (existing.keys.size != 1) throw badRequest("invalid_channel", "渠道已配置多个密钥，请显式提交 keys")
    val current = existing.keys.single()
    return listOf(
        ChannelKeyUpdate(
            id = current.id,
            name = current.name,
            credential = encryptedCredential,
            // 旧客户端未提交 state，保持现有状态，避免把自动停用误改成启用。
            state = current.state,
            priority = current.priority,
            models = if (modelMappings != null) channelModels.toList() else current.models,
            rateLimit = null,
            stateVersion = current.stateVersion,
        ),
    )
}

private data class NormalizedKey(
    val id: String?,
    val name: String,
    val credential: dev.soven.llmgateway.security.EncryptedCredential?,
    val state: ChannelKeyState?,
    val priority: Int,
    val models: List<String>,
    val rateLimit: ChannelKeyRateLimitPolicy?,
    val stateVersion: Long?,
)

private fun normalizeKeys(
    keys: List<ChannelKeyRequest>,
    channelModels: Set<String>,
    app: GatewayApplication,
): List<NormalizedKey> {
    if (keys.isEmpty()) throw badRequest("invalid_channel", "渠道至少需要一个密钥")
    val names = mutableSetOf<String>()
    val ids = mutableSetOf<String>()
    return keys.map { key ->
        val name = key.name.trim()
        if (name.isEmpty() || name.length > 100) {
            throw badRequest("invalid_channel", "密钥名称长度必须在 1..100 之间")
        }
        if (!names.add(name)) throw badRequest("invalid_channel", "密钥名称不能重复: $name")
        val keyId = key.id?.takeIf { it.isNotBlank() }
        if (keyId != null && !ids.add(keyId)) throw badRequest("invalid_channel", "密钥 id 不能重复: $keyId")
        val priority = key.priority ?: 1
        if (priority !in 1..1000) throw badRequest("invalid_channel", "密钥优先级必须在 1..1000 之间")
        if (key.stateVersion != null && key.stateVersion < 0) {
            throw badRequest("invalid_channel", "密钥状态版本不能为负数")
        }
        if (key.state != null && !key.state.manuallyAssignable) {
            throw badRequest(
                "invalid_channel",
                "密钥「$name」的状态只能提交 ACTIVE 或 MANUAL_DISABLED；自动停用状态由系统维护",
            )
        }
        val models = key.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        models.forEach { model ->
            if (model.length > 200) throw badRequest("invalid_channel", "上游模型名长度不能超过 200 个字符")
            if (model !in channelModels) {
                throw badRequest("invalid_channel", "密钥「$name」引用了未授权的模型「$model」")
            }
        }
        if (models.isEmpty()) throw badRequest("invalid_channel", "密钥「$name」至少需要授权一个模型")
        val rateLimit = key.rateLimit?.let { normalizeChannelKeyRateLimit(it, name) }
        NormalizedKey(
            id = keyId,
            name = name,
            credential = key.credential?.takeIf { it.isNotBlank() }?.let(app.credentialCipher::encrypt),
            state = key.state,
            priority = priority,
            models = models,
            rateLimit = rateLimit,
            stateVersion = key.stateVersion,
        )
    }
}

private fun normalizeChannelKeyRateLimit(
    request: ChannelKeyRateLimitRequest,
    keyName: String,
): ChannelKeyRateLimitPolicy {
    val requestsPerMinute = request.requestsPerMinute
    val maxConcurrentRequests = request.maxConcurrentRequests
    when {
        requestsPerMinute != null && requestsPerMinute !in 1..1_000_000 -> throw badRequest(
            "invalid_channel",
            "密钥「$keyName」限流规则无效：每分钟请求数必须在 1..1000000 之间",
        )
        maxConcurrentRequests != null && maxConcurrentRequests !in 1..100_000 -> throw badRequest(
            "invalid_channel",
            "密钥「$keyName」限流规则无效：最大并发请求数必须在 1..100000 之间",
        )
        request.enabled && requestsPerMinute == null && maxConcurrentRequests == null -> throw badRequest(
            "invalid_channel",
            "密钥「$keyName」限流规则无效：启用时至少需要配置每分钟请求数或最大并发请求数",
        )
    }
    return ChannelKeyRateLimitPolicy(
        enabled = request.enabled,
        requestsPerMinute = requestsPerMinute,
        maxConcurrentRequests = maxConcurrentRequests,
    )
}

private const val DEFAULT_CHANNEL_KEY_NAME = "默认密钥"

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

private data class ValidatedPriceValues(
    val currency: String,
    val input: BigDecimal,
    val output: BigDecimal,
    val cached: BigDecimal?,
    val cachedWrite: BigDecimal?,
    val pricingMode: PricingMode,
    val perRequest: BigDecimal?,
    val priceTiers: List<ModelPriceTier>,
)

private data class ValidatedModelPrice(
    val channelId: String,
    val upstreamModel: String,
    val values: ValidatedPriceValues,
)

private data class ValidatedDefaultModelPrice(
    val modelId: String,
    val values: ValidatedPriceValues,
)

private data class ValidatedModelPriceBatch(
    val channelId: String,
    val upstreamModels: List<String>,
    val values: ValidatedPriceValues,
)

private fun validateDefaultModelPrice(request: ModelDefaultPriceRequest): ValidatedDefaultModelPrice {
    val modelId = request.modelId.trim()
    if (modelId.isEmpty() || modelId.length > 200) {
        throw badRequest("invalid_model_default_price", "模型 ID 长度必须在 1..200 之间")
    }
    return ValidatedDefaultModelPrice(
        modelId = modelId,
        values = validatePriceValues(
            request.currency,
            request.inputPricePerMillion,
            request.outputPricePerMillion,
            request.cachedInputPricePerMillion,
            request.cachedInputWritePricePerMillion,
            request.pricingMode,
            request.perRequestPrice,
            request.priceTiers,
        ),
    )
}

private suspend fun validateModelPriceBatch(
    app: GatewayApplication,
    request: ModelPriceBatchRequest,
): ValidatedModelPriceBatch {
    val channelId = request.channelId.trim()
    if (channelId.isEmpty()) throw badRequest("invalid_model_price", "渠道不能为空")
    if (app.channelRepository.find(channelId) == null) {
        throw badRequest("invalid_model_price", "渠道不存在")
    }
    if (request.upstreamModels.size > 200) {
        throw badRequest("invalid_model_price", "一次最多套用 200 个上游模型")
    }
    val upstreamModels = request.upstreamModels
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    if (upstreamModels.isEmpty()) {
        throw badRequest("invalid_model_price", "至少选择一个上游模型")
    }
    if (upstreamModels.any { it.length > 200 }) {
        throw badRequest("invalid_model_price", "上游模型名长度必须在 1..200 之间")
    }
    return ValidatedModelPriceBatch(
        channelId = channelId,
        upstreamModels = upstreamModels,
        values = validatePriceValues(
            request.currency,
            request.inputPricePerMillion,
            request.outputPricePerMillion,
            request.cachedInputPricePerMillion,
            request.cachedInputWritePricePerMillion,
            request.pricingMode,
            request.perRequestPrice,
            request.priceTiers,
        ),
    )
}

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
    return ValidatedModelPrice(
        channelId = channelId,
        upstreamModel = upstreamModel,
        values = validatePriceValues(
            request.currency,
            request.inputPricePerMillion,
            request.outputPricePerMillion,
            request.cachedInputPricePerMillion,
            request.cachedInputWritePricePerMillion,
            request.pricingMode,
            request.perRequestPrice,
            request.priceTiers,
        ),
    )
}

private fun validatePriceValues(
    currencyValue: String,
    inputValue: String?,
    outputValue: String?,
    cachedValue: String?,
    cachedWriteValue: String?,
    modeValue: String,
    perRequestValue: String?,
    tierValues: List<ModelPriceTierRequest>,
): ValidatedPriceValues {
    val mode = PricingMode.entries.firstOrNull { it.value == modeValue }
        ?: throw badRequest("invalid_model_price", "计费方式无效")
    val input = if (mode == PricingMode.TOKEN) {
        parsePrice(inputValue.orEmpty(), "输入单价")
    } else {
        parseOptionalPrice(inputValue, "输入单价") ?: BigDecimal.ZERO
    }
    val output = if (mode == PricingMode.TOKEN) {
        parsePrice(outputValue.orEmpty(), "输出单价")
    } else {
        parseOptionalPrice(outputValue, "输出单价") ?: BigDecimal.ZERO
    }
    val cached = parseOptionalPrice(cachedValue, "缓存读取单价")
    val cachedWrite = parseOptionalPrice(cachedWriteValue, "缓存写入单价")
    val perRequest = parseOptionalPrice(perRequestValue, "按次费用")
    if (mode == PricingMode.PER_REQUEST && perRequest == null) {
        throw badRequest("invalid_model_price", "按次费用必须是非负数字")
    }
    val tiers = if (mode == PricingMode.TIERED || mode == PricingMode.VOLUME) {
        if (tierValues.isEmpty() || tierValues.size > 20) {
            throw badRequest("invalid_model_price", "阶梯计费需要 1..20 个价格档位")
        }
        var previous = 0L
        val parsed = tierValues.mapIndexed { index, tier ->
            val isLast = index == tierValues.lastIndex
            val limit = tier.upToTokens
            if (isLast && limit != null) {
                throw badRequest("invalid_model_price", "最后一个价格档位必须不设上限")
            }
            val maxLimit = 100_000_000_000L
            if (!isLast && (limit == null || limit <= previous || limit > maxLimit)) {
                throw badRequest("invalid_model_price", "阶梯上限必须为正整数并严格递增（最大不超过 1000 亿），最后一档不设上限")
            }
            if (limit != null) previous = limit
            ModelPriceTier(
                upToTokens = limit,
                inputPricePerMillion = parsePrice(tier.inputPricePerMillion, "阶梯输入单价"),
                outputPricePerMillion = parsePrice(tier.outputPricePerMillion, "阶梯输出单价"),
                cachedInputPricePerMillion = parseOptionalPrice(tier.cachedInputPricePerMillion, "阶梯缓存读取单价"),
                cachedInputWritePricePerMillion = parseOptionalPrice(tier.cachedInputWritePricePerMillion, "阶梯缓存写入单价"),
            )
        }
        parsed
    } else {
        emptyList()
    }
    return ValidatedPriceValues(
        currency = parseCurrency(currencyValue),
        input = input,
        output = output,
        cached = cached,
        cachedWrite = cachedWrite,
        pricingMode = mode,
        perRequest = perRequest,
        priceTiers = tiers,
    )
}

private fun parseOptionalPrice(value: String?, label: String): BigDecimal? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { parsePrice(it, label) }

private fun parseCurrency(value: String): String {
    val currency = value.trim().uppercase()
    if (!CURRENCY_PATTERN.matches(currency)) {
        throw badRequest("invalid_model_price", "币种必须是 3 到 8 位字母")
    }
    return currency
}

private fun parsePrice(value: String, label: String): BigDecimal {
    val parsed = value.trim().toBigDecimalOrNull()
        ?: throw badRequest("invalid_model_price", "$label 必须是数字")
    if (parsed < BigDecimal.ZERO) throw badRequest("invalid_model_price", "$label 不能为负数")
    return parsed.setScale(8, RoundingMode.HALF_UP)
}

private fun isUniqueViolation(cause: Throwable): Boolean =
    generateSequence(cause) { it.cause }
        .any { (it as? java.sql.SQLException)?.sqlState == "23505" }

private const val MODEL_TEST_PROMPT_MAX = 4_000

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

/** 管理面账号响应：把账号关联的渠道 ID 展开成名称，并带上实时调度状态。 */
private suspend fun oauthAccountResponse(
    app: GatewayApplication,
    account: dev.soven.llmgateway.domain.OAuthAccountRecord,
): OAuthAccountResponse {
    val channelsById = app.channelRepository.list().associateBy { it.id }
    return account.toResponse(
        channels = account.channelIds.sorted().mapNotNull { channelId ->
            channelsById[channelId]?.let { channel -> OAuthAccountChannelResponse(channel.id, channel.name) }
        },
        policy = account.policy,
        runtime = app.oauthAccountScheduler.runtimeState(account.id, account.policy),
    )
}

/** 账号重新授权或管理员解除冷却后，同时清理各关联渠道下该账号的候选冷却。 */
private suspend fun clearOAuthAccountCooldown(
    app: GatewayApplication,
    account: dev.soven.llmgateway.domain.OAuthAccountRecord,
) {
    app.oauthAccountScheduler.clearCooldown(account.id)
    if (account.channelIds.isEmpty()) return
    val baselineSeq = app.cooldownStateWriter.currentSeq()
    val cleared = account.channelIds.flatMap { channelId ->
        app.candidateCooldown.clearKey(channelId, account.id)
    }
    if (!app.cooldownStateWriter.deleteNow(cleared.toSet(), baselineSeq, 5_000)) {
        throw GatewayException(
            HttpStatusCode.ServiceUnavailable,
            "cooldown_persist_failed",
            "冷却状态持久化失败，请重试",
            "api_error",
        )
    }
    app.candidateCooldown.confirmDeletions(cleared)
}

private fun validateOAuthProxyUrl(app: GatewayApplication, proxyUrl: String?) {
    if (proxyUrl == null) return
    try {
        proxyAddress(proxyUrl, app.config.oauth.proxyAllowedHosts)
    } catch (cause: OAuthRefreshException) {
        throw badRequest("invalid_oauth_proxy", cause.message ?: "账号代理地址无效")
    }
}

private val OAUTH_PROVIDER_LABELS = mapOf(
    OAuthProvider.CHATGPT to "ChatGPT",
    OAuthProvider.ANTIGRAVITY to "Antigravity",
)

/** refresh token 与 access token 的长度上限；正常 JWT 远小于该值。 */
private const val MAX_OAUTH_CREDENTIAL_CHARS = 8_192
private const val MAX_OAUTH_EMAIL_CHARS = 320
private const val MAX_OAUTH_PLAN_CHARS = 64
private const val MAX_OAUTH_PROXY_CHARS = 512
private const val MAX_OAUTH_SCOPE_CHARS = 512
private const val MAX_OAUTH_CALLBACK_CHARS = 4_096

private val CURRENCY_PATTERN = Regex("[A-Za-z]{3,8}")
private const val MAX_MODEL_SUGGESTIONS = 500
private const val MAPPING_SOURCE_MANUAL = "manual"
private val MAPPING_SOURCES = setOf(MAPPING_SOURCE_MANUAL, "models_dev")
