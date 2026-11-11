package dev.soven.prismllm.api

import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.notFound
import dev.soven.prismllm.shared.error.serviceUnavailable
import dev.soven.prismllm.shared.error.unauthorized

import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.shared.domain.FailureStage
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import dev.soven.prismllm.domain.channelKey
import dev.soven.prismllm.domain.oauthAccount
import dev.soven.prismllm.routing.domain.candidate.RouteCandidate
import dev.soven.prismllm.requestlog.domain.UpstreamAttemptRecord
import dev.soven.prismllm.channel.domain.UpstreamHeaderProfile
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.channel.domain.UpstreamEnvelope
import dev.soven.prismllm.channel.domain.upstreamEnvelope
import dev.soven.prismllm.proxy.codeassist.CodeAssistEnvelope
import dev.soven.prismllm.proxy.codeassist.CodeAssistCloak
import dev.soven.prismllm.proxy.codeassist.CodeAssistSuspension
import dev.soven.prismllm.channel.domain.authTypeFor
import dev.soven.prismllm.domain.effective
import dev.soven.prismllm.oauth.infra.OAuthAccountDecision
import dev.soven.prismllm.oauth.infra.OAuthAccountDenyReason
import dev.soven.prismllm.oauth.infra.OAuthAccountLease
import dev.soven.prismllm.oauth.infra.OAuthRefreshException
import dev.soven.prismllm.oauth.OAuthTokenUnavailableException
import dev.soven.prismllm.oauth.OAuthUnavailableReason
import dev.soven.prismllm.oauth.domain.parseCodexQuotaHeaders
import dev.soven.prismllm.oauth.domain.parseRateLimitResetMs
import dev.soven.prismllm.oauth.infra.proxyAddress
import java.net.InetSocketAddress
import java.net.ProxySelector
import dev.soven.prismllm.proxy.UpstreamRequestPolicy
import dev.soven.prismllm.proxy.upstreamRequestUrl
import dev.soven.prismllm.requestlog.domain.BillingCalculator
import dev.soven.prismllm.quota.infra.RequestLimitLease
import dev.soven.prismllm.quota.domain.quotaTokensFromUsage
import dev.soven.prismllm.protocol.ReasoningEffort
import dev.soven.prismllm.protocol.ShapeRefusal
import dev.soven.prismllm.shared.domain.TraceContextFactory
import dev.soven.prismllm.proxy.applyCodeAssistIdentity
import dev.soven.prismllm.proxy.applyUpstreamAuthentication
import dev.soven.prismllm.shared.domain.Usage
import dev.soven.prismllm.protocol.UsageParser
import dev.soven.prismllm.protocol.extractReportedModel
import dev.soven.prismllm.protocol.MAX_PROTOCOL_EVENT_BYTES
import dev.soven.prismllm.protocol.ProtocolConversion
import dev.soven.prismllm.protocol.ProtocolConversionException
import dev.soven.prismllm.protocol.asChatUpstream
import dev.soven.prismllm.protocol.responsesToChatRequest
import dev.soven.prismllm.proxy.search.ChatCaller
import dev.soven.prismllm.proxy.search.SearchConversation
import dev.soven.prismllm.proxy.search.WebSearchService
import dev.soven.prismllm.proxy.search.addUsage
import dev.soven.prismllm.proxy.search.hasBuiltInWebSearch
import dev.soven.prismllm.proxy.search.prepareResponsesSearchRequest
import dev.soven.prismllm.proxy.search.syntheticWebSearchName
import dev.soven.prismllm.proxy.search.webSearchAllowedDomains
import dev.soven.prismllm.channel.persistence.KeyAutoDisableRepository
import dev.soven.prismllm.channel.persistence.KeyAutoDisablePendingException
import dev.soven.prismllm.resilience.CandidateCooldown
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitDecision
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitLease
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitReason
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.resilience.CooldownPermit
import dev.soven.prismllm.resilience.FailureKind
import dev.soven.prismllm.channel.domain.key.KeyAttemptSignal
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import org.slf4j.LoggerFactory

private val proxyLogger = LoggerFactory.getLogger("dev.soven.prismllm.api.ProxyRoutes")
private val USER_AGENT_WHITESPACE = Regex("[\\r\\n\\t]+")

/**
 * 原生协议数据面。负责调用方鉴权、别名路由、流式透传和请求日志。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun Route.proxyRoutes(app: GatewayApplication) {
    get("/v1/models") {
        authenticate(call, app)
        val models = listExposedModelIds(app)
        call.respond(
            buildJsonObject {
                put("object", "list")
                put(
                    "data",
                    buildJsonArray {
                        models.forEach { model ->
                            add(
                                buildJsonObject {
                                    put("id", model)
                                    put("object", "model")
                                    put("created", 0)
                                    put("owned_by", "PrismLLM")
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    GatewayProtocol.entries.forEach { protocol ->
        val endpointPath = protocol.endpointPath ?: return@forEach
        post("/v1$endpointPath") {
            handleProxy(app, call, protocol)
        }
    }
    get("/v1beta/models") {
        authenticate(call, app, GatewayProtocol.GEMINI)
        val models = listExposedModelIds(app, GatewayProtocol.GEMINI)
        call.respond(buildJsonObject {
            put("models", buildJsonArray {
                models.forEach { model ->
                    add(buildJsonObject {
                        put("name", "models/$model")
                        put("displayName", model)
                        put("supportedGenerationMethods", buildJsonArray {
                            add(JsonPrimitive("generateContent"))
                            add(JsonPrimitive("streamGenerateContent"))
                        })
                    })
                }
            })
        })
    }
    listOf("/v1beta", "/v1").forEach { version ->
        post("$version/models/{modelAction...}") {
            val modelAction = call.parameters.getAll("modelAction")?.joinToString("/").orEmpty()
            val action = modelAction.substringAfterLast(':', "")
            if (action !in setOf("generateContent", "streamGenerateContent")) {
                throw notFound("unsupported_gemini_action", "仅支持 generateContent 和 streamGenerateContent")
            }
            val model = modelAction.substringBeforeLast(':').takeIf { it.isNotBlank() }
                ?: throw badRequest("invalid_model", "模型名不能为空")
            handleProxy(app, call, GatewayProtocol.GEMINI, model, action == "streamGenerateContent")
        }
    }
}

private suspend fun handleProxy(
    app: GatewayApplication,
    call: ApplicationCall,
    protocol: GatewayProtocol,
    nativeModel: String? = null,
    nativeStream: Boolean? = null,
) {
    val traceContext = TraceContextFactory.fromHeaderOrCreate(call.request.headers["traceparent"])
    call.response.header("traceparent", traceContext.traceparent)
    call.response.header("X-Trace-Id", traceContext.traceId)
    // 在任何提前抛出的错误前捕获来源信息，保证失败请求的日志同样带 IP 与 User-Agent。
    val clientAccess = requestAccessLogMetadata(call, app.config.trustedProxyNetworks)
    // 复用访问日志的 IP 解析（信任代理、XFF 反向遍历）；UA 单独清洗，避免日志转义把空格变成 %20。
    val clientUserAgent = call.request.headers[HttpHeaders.UserAgent]
        ?.replace(USER_AGENT_WHITESPACE, " ")
        ?.trim()
        ?.take(300)
        ?.takeUnless { it.isEmpty() }

    val requestId = call.request.headers["X-Request-Id"]
        ?.take(64)
        ?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()
    call.response.header("X-Request-Id", requestId)

    val startedAt = System.nanoTime()
    val deadlineNanos = startedAt + app.config.totalTimeoutMs * 1_000_000
    val state = ProxyState()
    var clientKey: ClientKeyRecord? = null
    var requestLimitLease: RequestLimitLease? = null
    var alias = "unknown"
    var channelId: String? = null
    var channelName: String? = null
    var channelKeyId: String? = null
    var channelKeyName: String? = null
    var upstreamModel: String? = null
    var upstreamProtocol: GatewayProtocol? = null
    var pricingModelId: String? = null
    var streamMode: Boolean? = null
    var reasoningEffort: String? = null
    var statusCode = HttpStatusCode.InternalServerError.value
    var errorCode: String? = null
    var failureStage: FailureStage? = null
    var errorMessage: String? = null
    var upstreamErrorCode: String? = null
    var upstreamErrorBody: String? = null
    var upstreamAttempts = 0
    val attemptEvents = mutableListOf<UpstreamAttemptRecord>()
    val skippedEvents = mutableMapOf<RouteCandidate, UpstreamAttemptRecord>()
    var recordedAttempts = 0
    var phase = FailureStage.REQUEST

    try {
        clientKey = authenticate(call, app, protocol)
        requestLimitLease = app.requestLimitService.acquire(clientKey)
        val requestJson = call.receiveLimitedJson(app.config.requestBodyLimitBytes)
        reasoningEffort = ReasoningEffort.fromRequest(requestJson, protocol)
        val modelElement = requestJson["model"] as? JsonPrimitive
        alias = nativeModel ?: modelElement
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw badRequest("invalid_model", "model 必须是非空字符串")
        val streamElement = requestJson["stream"]
        val stream = nativeStream ?: when (streamElement) {
            null -> false
            is JsonPrimitive -> {
                if (streamElement.isString) throw badRequest("invalid_stream", "stream 必须是布尔值")
                streamElement.booleanOrNull ?: throw badRequest("invalid_stream", "stream 必须是布尔值")
            }
            else -> throw badRequest("invalid_stream", "stream 必须是布尔值")
        }
        streamMode = stream
        phase = FailureStage.ROUTING
        // 搜索设置每次请求读取一次：管理台保存后立即生效，未保存时沿用环境变量默认值。
        // 读取失败只代表搜索不可用，不能影响本来可以透传的请求；也不回退环境变量，避免意外重新开启搜索。
        val webSearchRuntime = if (protocol == GatewayProtocol.RESPONSES && hasBuiltInWebSearch(requestJson)) {
            val settings = try {
                app.webSearchSettingsRepository.load().effective(app.config.webSearch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            settings?.let {
                WebSearchRuntime(WebSearchService.fromConfig(it, app.searchHttpClient), it.maxRounds)
            }
        } else {
            null
        }
        // 路由与「为什么不可用」的判定共用一次目录快照：分开查询会在凭据恢复的瞬间
        // 得出「无候选却又查不出原因」的自相矛盾结论，进而把已配置的模型误报成 404。
        val availability = app.modelCatalog.resolve(alias, protocol)
        val candidates = availability.candidates
        if (candidates.isEmpty()) {
            // 当前协议本来就没有路可走（渠道既不原生支持也无法转换）时优先报协议错误，
            // 否则会把「换个协议就能用」误导成「凭据暂时不可用」。
            if (!availability.protocolReachable) {
                if (!availability.configured) throw notFound("model_not_found", "模型 $alias 不存在或没有可用渠道")
                throw protocolNotSupported(alias, protocol)
            }
            // 模型已配置但凭据全部停用时，客户端重试或等自动恢复就能成功，不能用 404 通知其改配置。
            throw serviceUnavailable(
                "model_credentials_unavailable",
                "模型 $alias 已配置，但当前没有可用的上游凭据",
                availability.retryAt?.let { it - System.currentTimeMillis() }?.takeIf { it > 0 },
            )
        }
        val globalOverrides = app.upstreamRequestSettingsRepository.load().overrides
        val headerProfiles = app.upstreamHeaderCatalogService.profiles()

        phase = FailureStage.UPSTREAM

        /** 记录候选在路由阶段被跳过的事件，保持与实际上游尝试的相对顺序。 */
        fun markSkipped(candidate: RouteCandidate, reason: String) {
            if (skippedEvents.containsKey(candidate)) return
            val event = UpstreamAttemptRecord(
                index = 0,
                channelId = candidate.channel.id,
                channelName = candidate.channel.name,
                channelKeyId = candidate.credential.id,
                channelKeyName = candidate.credential.name,
                upstreamModel = candidate.upstreamModel,
                upstreamProtocol = if (protocol in candidate.channel.protocols) {
                    protocol
                } else {
                    candidate.channel.protocols.firstOrNull { ProtocolConversion.supports(protocol, it) }
                },
                elapsedMs = 0,
                skipped = true,
                skipReason = reason,
            )
            attemptEvents += event
            skippedEvents[candidate] = event
        }

        var lastError: GatewayError? = null
        var conversionError: ProtocolConversionException? = null
        var retryFrom: String? = null
        var retryReason: String? = null
        var rateLimitRetryAfterMs: Long? = null
        var rateLimitReason: ChannelKeyRateLimitReason? = null
        var oauthRateLimitReason: OAuthAccountDenyReason? = null
        val rateLimitedCandidates = mutableSetOf<RouteCandidate>()
        fun rememberRateLimitDenial(candidate: RouteCandidate, decision: ChannelKeyRateLimitDecision.Denied) {
            rateLimitedCandidates += candidate
            val current = rateLimitRetryAfterMs
            if (current == null || decision.retryAfterMs < current) {
                rateLimitRetryAfterMs = decision.retryAfterMs
                rateLimitReason = decision.reason
                oauthRateLimitReason = null
            }
        }
        fun rememberRateLimitDenial(candidate: RouteCandidate, decision: OAuthAccountDecision.Denied) {
            rateLimitedCandidates += candidate
            val current = rateLimitRetryAfterMs
            if (current == null || decision.retryAfterMs < current) {
                rateLimitRetryAfterMs = decision.retryAfterMs
                oauthRateLimitReason = decision.reason
                rateLimitReason = null
            }
        }

        // 先按冷却与账号级调度分拣：可尝试的候选进同档轮询，冷却中的保序作为 fail-open 兜底，
        // 轮询份额只在可尝试候选间分摊，不被冷却候选扭曲。
        // 账号级窗口/并发/冷却属于硬限制，不进入 fail-open。
        val eligibleNative = mutableListOf<RouteCandidate>()
        val eligibleConversion = mutableListOf<RouteCandidate>()
        val blocked = mutableListOf<RouteCandidate>()
        for (candidate in candidates) {
            if (app.candidateCooldown.isBlocked(candidate.channel.id, candidate.credential.id, candidate.upstreamModel)) {
                app.metrics.upstreamCooldownSkipped(candidate.channel.name)
                blocked.add(candidate)
                markSkipped(candidate, "cooldown")
                continue
            }
            val oauthDenial = candidate.oauthAccount?.let { app.oauthAccountScheduler.check(it) }
            if (oauthDenial != null) {
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, oauthDenial.reason.metricValue)
                rememberRateLimitDenial(candidate, oauthDenial)
                markSkipped(candidate, oauthDenial.reason.metricValue)
                continue
            }
            if (protocol in candidate.channel.protocols) {
                eligibleNative.add(candidate)
            } else {
                eligibleConversion.add(candidate)
            }
        }
        val promptCacheKey = (requestJson["prompt_cache_key"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: call.request.headers["X-Prompt-Cache-Key"]?.takeIf { it.isNotBlank() }
        // 每个 scope 每请求只推进一次轮询计数，避免同档两渠道时起点固定；提供 prompt_cache_key 时同档按亲和性路由。
        // 订阅账号可再按 drain / least_used / pace 重排，但亲和优先，避免打散 prompt cache。
        val ordered = app.oauthAccountRouting.order(
            app.tierSharing.order(alias, eligibleNative, promptCacheKey),
            affinityApplied = promptCacheKey != null,
        ) + app.oauthAccountRouting.order(
            app.tierSharing.order("$alias|conversion", eligibleConversion, promptCacheKey),
            affinityApplied = promptCacheKey != null,
        )

        /**
         * 尝试单个候选并把结果记入冷却。返回 null 表示响应已完成，返回异常交由外层决定是否继续。
         */
        suspend fun runAttempt(
            candidate: RouteCandidate,
            permit: CooldownPermit,
            rateLimitLease: ChannelKeyRateLimitLease?,
            oauthLease: OAuthAccountLease?,
        ): GatewayError? = try {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                app.candidateCooldown.release(permit)
                throw GatewayError(
                    HttpStatusCode.GatewayTimeout,
                    "upstream_timeout",
                    "上游响应超时",
                    "timeout_error",
                )
            }
            // 首 token 软阈值按本次尝试计时，不继承前面候选消耗掉的时间。
            val attemptStartedNanos = System.nanoTime()
            state.attemptFirstTokenMs = null
            state.upstreamStatus = null
            state.upstreamReportedModel = null
            state.upstreamCallStarted = false
            state.streamError = null
            upstreamProtocol = null
            upstreamAttempts += 1
            channelId = candidate.channel.id
            channelName = candidate.channel.name
            channelKeyId = candidate.credential.id
            channelKeyName = candidate.credential.name
            upstreamModel = candidate.upstreamModel
            // 内置 web_search 无法转成 Chat；有搜索后端时，统一走“合成函数工具 + 多轮搜索”的 Chat 代理。
            // 同时支持原生 Responses 与 Chat 的渠道也走代理，保证配置搜索后端后内置 web_search 一定被执行。
            val searchRuntime = webSearchRuntime?.takeIf { it.available }
                ?.takeIf { GatewayProtocol.CHAT_COMPLETIONS in candidate.channel.protocols }
            val webSearchProxy = searchRuntime != null
            val attemptProtocol = if (webSearchProxy) {
                GatewayProtocol.CHAT_COMPLETIONS
            } else if (protocol in candidate.channel.protocols) {
                protocol
            } else {
                candidate.channel.protocols.firstOrNull { ProtocolConversion.supports(protocol, it) }
            }
            attemptProtocol?.takeIf { it != protocol }?.let { target ->
                app.metrics.protocolConversion(protocol.value, target.value)
            }
            pricingModelId = candidate.pricingModelId
            retryFrom?.let { from ->
                app.metrics.upstreamRetry(from, candidate.channel.name, retryReason ?: "upstream_error")
                retryFrom = null
                retryReason = null
            }
            var attemptRecorded = false
            fun recordAttempt(status: Int?, error: GatewayError?, committed: Boolean, errorCode: String? = null) {
                if (attemptRecorded) return
                attemptRecorded = true
                if (!state.upstreamCallStarted) {
                    upstreamAttempts -= 1
                    return
                }
                skippedEvents.remove(candidate)?.let { attemptEvents.remove(it) }
                upstreamProtocol = attemptProtocol
                val upstreamError = state.streamError ?: error
                recordedAttempts += 1
                attemptEvents += UpstreamAttemptRecord(
                    index = recordedAttempts,
                    channelId = candidate.channel.id,
                    channelName = candidate.channel.name,
                    channelKeyId = candidate.credential.id,
                    channelKeyName = candidate.credential.name,
                    upstreamModel = candidate.upstreamModel,
                    upstreamReportedModel = state.upstreamReportedModel,
                    upstreamProtocol = attemptProtocol,
                    statusCode = state.upstreamStatus ?: status,
                    upstreamErrorStatusCode = upstreamError?.upstreamStatus,
                    errorCode = errorCode ?: error?.code,
                    upstreamErrorCode = upstreamError?.upstreamCode?.take(200),
                    errorMessage = upstreamError?.message?.asLogMessage(),
                    elapsedMs = (System.nanoTime() - attemptStartedNanos) / 1_000_000,
                    firstTokenMs = state.attemptFirstTokenMs,
                    committed = committed,
                    retryable = error?.retryable ?: false,
                )
            }
            var probeClaimed = false
            try {
                app.keyAvailabilityGate.withReader(candidate.credential.id) {
                    // 自动禁用与探测租约只属于静态渠道密钥；订阅账号由自身 authState 与冷却控制。
                    val channelKey = candidate.channelKey
                    if (channelKey != null) {
                        if (!app.keyAutoDisableRepository.isAvailable(channelKey)) {
                            throw CandidateUnavailableException()
                        }
                        // 到期临时密钥先原子抢探测租约；抢不到说明已有并发探测，直接换候选。
                        if (channelKey.state == ChannelKeyState.AUTO_TEMPORARY) {
                            val leaseMs = maxOf(
                                KeyAutoDisableRepository.PROBE_LEASE_MS,
                                app.config.firstTokenTimeoutMs + 5_000,
                                app.config.totalTimeoutMs + 5_000,
                            )
                            // 请求转换在抢租约前完成，避免转换失败留下未释放的探测租约。
                            // 搜索代理会先把内置 web_search 改写成普通函数工具，探测也必须用改写后的请求。
                            val probeRequest = if (webSearchProxy) {
                                prepareResponsesSearchRequest(requestJson, syntheticWebSearchName(requestJson))
                            } else {
                                requestJson
                            }
                            ProtocolConversion.convertRequest(
                                protocol,
                                attemptProtocol ?: protocol,
                                probeRequest,
                                candidate.upstreamModel,
                            )
                            probeClaimed = app.keyAutoDisableRepository.claimProbe(channelKey, leaseMs = leaseMs)
                            if (!probeClaimed) throw CandidateUnavailableException()
                        }
                    }
                    if (searchRuntime != null) {
                        if (stream) {
                            proxySearchStream(
                                app, call, requestJson, candidate, alias, remainingMs, state,
                                traceContext.traceparent, globalOverrides, headerProfiles, searchRuntime,
                            )
                        } else {
                            proxySearchJson(
                                app, call, requestJson, candidate, alias, remainingMs, state,
                                traceContext.traceparent, globalOverrides, headerProfiles, searchRuntime,
                            )
                        }
                    } else if (stream) {
                        proxyStream(
                            app,
                            call,
                            requestJson,
                            candidate,
                            permit,
                            alias,
                            startedAt,
                            attemptStartedNanos,
                            remainingMs,
                            state,
                            protocol,
                            attemptProtocol ?: protocol,
                            traceContext.traceparent,
                            allowUpstreamErrorFailover = upstreamAttempts < app.config.maxAttempts && upstreamAttempts < candidates.size,
                            globalOverrides = globalOverrides,
                            headerProfiles = headerProfiles,
                            clientKeyId = clientKey?.id,
                        )
                    } else {
                        proxyJson(
                            app, call, requestJson, alias, candidate, remainingMs, state, protocol,
                            attemptProtocol ?: protocol, traceContext.traceparent, globalOverrides, headerProfiles,
                            clientKey?.id,
                        )
                    }
                }
                statusCode = state.statusCode
                app.metrics.upstreamAttempt(
                    candidate.channel.name,
                    state.statusCode < 400 && state.errorCode == null,
                )
                recordAttempt(state.statusCode, null, state.committed, state.errorCode)
                if (state.errorCode == null) {
                    app.candidateCooldown.recordSuccess(permit)
                    app.oauthAccountScheduler.recordSuccess(candidate.credential.id)
                    recordKeyOutcome(app, candidate, KeyAttemptSignal.Success, requestId, probeClaimed)
                } else {
                    // 首 token 之后才出现的流异常不算凭据失效，只进入渠道冷却。
                    app.candidateCooldown
                        .recordFailure(permit, FailureKind.TRANSPORT)
                        ?.let { app.metrics.upstreamCooldownStarted(candidate.channel.name, it.reason) }
                }
                null
            } catch (_: CandidateUnavailableException) {
                upstreamAttempts -= 1
                app.candidateCooldown.release(permit)
                GatewayError(
                    HttpStatusCode.BadGateway, "no_candidate", "密钥已停用，请尝试其他候选", "api_error",
                    retryable = true,
                )
            } catch (e: GatewayError) {
                if (e.code == "client_disconnect") {
                    app.candidateCooldown.release(permit)
                    recordAttempt(state.statusCode.takeIf { state.committed }, e, state.committed)
                    throw e
                }
                try {
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    app.candidateCooldown.release(permit)
                    recordAttempt(state.upstreamStatus ?: state.statusCode.takeIf { state.committed }, null, state.committed, "client_cancelled")
                    throw cancelled
                }
                app.metrics.upstreamError(candidate.channel.name, e.code)
                val oauthAccount = candidate.oauthAccount
                if (e.upstreamStatus == 429 && oauthAccount != null) {
                    // 账号级 429 冷却：优先使用 Retry-After，其次解析响应体里的重置时间。
                    app.oauthAccountScheduler.recordRateLimited(
                        oauthAccount,
                        e.retryAfterMs ?: parseRateLimitResetMs(e.upstreamErrorBody),
                    )
                }
                if (e.upstreamStatus == 403 && oauthAccount != null &&
                    CodeAssistSuspension.isSuspended(e.upstreamErrorBody)
                ) {
                    // Antigravity 封禁账号：不是鉴权故障，按两小时账号级冷却处理并继续换候选。
                    app.oauthAccountScheduler.recordRateLimited(oauthAccount, CodeAssistSuspension.REST_MS)
                }
                val kind = classifyFailure(e)
                if (kind == null) {
                    app.candidateCooldown.release(permit)
                } else {
                    app.candidateCooldown
                        .recordFailure(permit, kind, e.retryAfterMs)
                        ?.let { trigger ->
                            app.metrics.upstreamCooldownStarted(candidate.channel.name, trigger.reason)
                            if (trigger.reason == CandidateCooldown.REASON_AUTH) {
                                proxyLogger.error(
                                    "上游凭据被拒绝，渠道 {} 密钥 {} 模型 {} 进入长冷却（{}ms），请检查密钥配置",
                                    candidate.channel.name,
                                    candidate.credential.name,
                                    candidate.upstreamModel,
                                    app.config.cooldown.authFailureMs,
                                )
                            }
                        }
                }
                val failureCommitted = state.committed || e.code == "stream_interrupted" || e.code == "stream_timeout"
                recordAttempt(
                    // 优先记录实际收到的 HTTP 状态码；流内错误事件里的状态码只是事件语义。
                    state.upstreamStatus ?: e.upstreamStatus ?: state.statusCode.takeIf { failureCommitted },
                    e,
                    failureCommitted,
                )
                val signal = e.toKeyAttemptSignal()
                if (signal != null) {
                    recordKeyOutcome(app, candidate, signal, requestId, probeClaimed)
                }
                e
            } catch (e: ProtocolConversionException) {
                app.candidateCooldown.release(permit)
                if (state.upstreamCallStarted) {
                    recordAttempt(
                        state.upstreamStatus,
                        GatewayError(
                            if (state.committed) HttpStatusCode.OK else HttpStatusCode.BadGateway,
                            if (state.committed) "protocol_conversion_failed" else "protocol_conversion_unsupported",
                            "${e.field}: ${e.message}",
                        ),
                        state.committed,
                    )
                }
                // 上游已返回 2xx 但响应无法转换：说明凭据可用，按成功结算并释放探测租约。
                if (probeClaimed) {
                    recordKeyOutcome(app, candidate, KeyAttemptSignal.Success, requestId, probeClaimed = true)
                }
                if (state.committed) {
                    // 响应已提交后才发生转换失败：保留真实原因，便于和普通流中断区分。
                    throw e
                }
                // 请求在本地转换阶段就失败时没有真正调用上游，不写入尝试明细，也不消耗尝试预算。
                if (!state.upstreamCallStarted) {
                    upstreamAttempts -= 1
                    conversionError = e
                    return GatewayError(
                        HttpStatusCode.BadGateway,
                        "no_candidate",
                        "协议转换不支持，尝试其他候选",
                        "api_error",
                        retryable = true,
                    )
                }
                conversionError = e
                GatewayError(
                    HttpStatusCode.BadGateway,
                    "no_candidate",
                    "协议转换不支持，尝试其他候选",
                    "api_error",
                    retryable = true,
                )
            } catch (e: Throwable) {
                app.candidateCooldown.release(permit)
                if (e is CancellationException) {
                    recordAttempt(
                        state.upstreamStatus ?: state.statusCode.takeIf { state.committed },
                        null,
                        state.committed,
                        "client_cancelled",
                    )
                    throw e
                }
                recordAttempt(
                    state.statusCode.takeIf { state.committed },
                    GatewayError(HttpStatusCode.InternalServerError, "internal_error", e.message ?: e::class.simpleName.toString()),
                    state.committed,
                )
                throw e
            }
        } finally {
            try {
                app.channelKeyRateLimiter.release(rateLimitLease)
            } finally {
                app.oauthAccountScheduler.release(oauthLease)
            }
        }

        var index = 0
        while (index < ordered.size && upstreamAttempts < app.config.maxAttempts) {
            val candidate = ordered[index]
            val permit = app.candidateCooldown.acquire(candidate.channel.id, candidate.credential.id, candidate.upstreamModel)
            if (permit == null) {
                app.metrics.upstreamCooldownSkipped(candidate.channel.name)
                blocked.add(candidate)
                markSkipped(candidate, "cooldown")
                index++
                continue
            }
            index++
            val rateDecision = candidate.channelKey
                ?.let { app.channelKeyRateLimiter.acquire(it) }
                ?: ChannelKeyRateLimitDecision.Allowed(null)
            if (rateDecision is ChannelKeyRateLimitDecision.Denied) {
                app.candidateCooldown.release(permit)
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, rateDecision.reason.metricValue)
                rememberRateLimitDenial(candidate, rateDecision)
                continue
            }
            val rateLimitLease = (rateDecision as ChannelKeyRateLimitDecision.Allowed).lease
            val oauthDecision = candidate.oauthAccount
                ?.let { app.oauthAccountScheduler.acquire(it) }
                ?: OAuthAccountDecision.Allowed(null)
            if (oauthDecision is OAuthAccountDecision.Denied) {
                // 账号级限制是硬限制：归还本地并发额度，但不把候选放进 fail-open 重试。
                app.channelKeyRateLimiter.release(rateLimitLease)
                app.candidateCooldown.release(permit)
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, oauthDecision.reason.metricValue)
                rememberRateLimitDenial(candidate, oauthDecision)
                continue
            }
            val error = app.upstreamOccupancy.track(candidate.channel.id, candidate.credential.id) {
                runAttempt(
                    candidate,
                    permit,
                    rateLimitLease,
                    (oauthDecision as OAuthAccountDecision.Allowed).lease,
                )
            }
            if (error == null) return
            if (error.code != "no_candidate") lastError = error
            if (error.code == "no_candidate") continue
            if (!error.retryable || state.committed || upstreamAttempts >= app.config.maxAttempts) throw error
            retryFrom = candidate.channel.name
            retryReason = error.code
        }
        // fail-open：仍有尝试预算时，被冷却跳过的候选按原档位顺序照常尝试，绝不因冷却状态拒绝服务。
        // 重新按候选原始顺序遍历：预分拣与 acquire 之间被拦下的候选应插回原位，而不是追加到尾部。
        val blockedSet = blocked.toHashSet()
        for (candidate in candidates) {
            if (candidate !in blockedSet) continue
            if (upstreamAttempts >= app.config.maxAttempts) break
            val permit = app.candidateCooldown.forceAcquire(candidate.channel.id, candidate.credential.id, candidate.upstreamModel)
            val rateDecision = candidate.channelKey
                ?.let { app.channelKeyRateLimiter.acquire(it) }
                ?: ChannelKeyRateLimitDecision.Allowed(null)
            if (rateDecision is ChannelKeyRateLimitDecision.Denied) {
                app.candidateCooldown.release(permit)
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, rateDecision.reason.metricValue)
                rememberRateLimitDenial(candidate, rateDecision)
                continue
            }
            val rateLimitLease = (rateDecision as ChannelKeyRateLimitDecision.Allowed).lease
            val oauthDecision = candidate.oauthAccount
                ?.let { app.oauthAccountScheduler.acquire(it) }
                ?: OAuthAccountDecision.Allowed(null)
            if (oauthDecision is OAuthAccountDecision.Denied) {
                app.channelKeyRateLimiter.release(rateLimitLease)
                app.candidateCooldown.release(permit)
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, oauthDecision.reason.metricValue)
                rememberRateLimitDenial(candidate, oauthDecision)
                continue
            }
            val error = app.upstreamOccupancy.track(candidate.channel.id, candidate.credential.id) {
                runAttempt(
                    candidate,
                    permit,
                    rateLimitLease,
                    (oauthDecision as OAuthAccountDecision.Allowed).lease,
                )
            }
            if (error == null) return
            if (error.code != "no_candidate") lastError = error
            if (error.code == "no_candidate") continue
            if (!error.retryable || state.committed || upstreamAttempts >= app.config.maxAttempts) throw error
            retryFrom = candidate.channel.name
            retryReason = error.code
        }
        if (
            lastError == null &&
            rateLimitRetryAfterMs != null &&
            rateLimitedCandidates.containsAll(candidates)
        ) {
            val oauthReason = oauthRateLimitReason
            throw GatewayError(
                HttpStatusCode.TooManyRequests,
                if (oauthReason != null) "subscription_account_rate_limited" else "upstream_key_rate_limit_exceeded",
                when {
                    oauthReason == OAuthAccountDenyReason.WINDOW -> "订阅账号请求窗口已用尽"
                    oauthReason == OAuthAccountDenyReason.CONCURRENCY -> "订阅账号并发请求数已达上限"
                    oauthReason == OAuthAccountDenyReason.COOLDOWN -> "订阅账号触发上游限流，冷却中"
                    rateLimitReason == ChannelKeyRateLimitReason.CONCURRENCY -> "上游密钥分组并发请求数已达上限"
                    else -> "上游密钥分组每分钟请求数已达上限"
                },
                "rate_limit_error",
                retryAfterMs = rateLimitRetryAfterMs,
            )
        }
        throw lastError ?: conversionError?.let { e ->
            badRequest("protocol_conversion_unsupported", "${e.field}: ${e.message}")
        } ?: GatewayError(
            HttpStatusCode.BadGateway,
            "no_candidate",
            "没有可用候选",
            "api_error",
        )
    } catch (e: TimeoutCancellationException) {
        statusCode = if (state.committed) HttpStatusCode.OK.value else HttpStatusCode.GatewayTimeout.value
        errorCode = if (state.committed) "stream_timeout" else "upstream_timeout"
        failureStage = if (state.committed) FailureStage.STREAMING else FailureStage.UPSTREAM
        errorMessage = if (state.committed) "上游流式响应超时" else "上游响应超时"
        throw GatewayError(HttpStatusCode.GatewayTimeout, "upstream_timeout", "上游响应超时", "timeout_error")
    } catch (e: GatewayError) {
        if (e.code == "client_disconnect") {
            statusCode = e.status.value
            errorCode = e.code
            failureStage = FailureStage.CLIENT
            errorMessage = e.message.asLogMessage()
            throw e
        }
        if (e.status == HttpStatusCode.TooManyRequests && e.code in QUOTA_REJECTION_CODES) {
            app.metrics.quotaRejection(e.code)
        }
        statusCode = if (state.committed) HttpStatusCode.OK.value else e.status.value
        errorCode = if (state.committed) {
            if (e.code == "upstream_timeout") "stream_timeout" else "stream_interrupted"
        } else {
            e.code
        }
        failureStage = when {
            state.committed -> FailureStage.STREAMING
            phase == FailureStage.UPSTREAM -> FailureStage.UPSTREAM
            phase == FailureStage.ROUTING -> FailureStage.ROUTING
            else -> FailureStage.REQUEST
        }
        errorMessage = e.message.asLogMessage()
        upstreamErrorCode = e.upstreamCode?.take(200)
        upstreamErrorBody = sanitizeUpstreamErrorBody(e.upstreamErrorBody)
        throw e
    } catch (e: CancellationException) {
        statusCode = if (state.committed) HttpStatusCode.OK.value else 499
        errorCode = "client_cancelled"
        failureStage = FailureStage.CLIENT
        errorMessage = "客户端取消请求"
        throw e
    } catch (e: ProtocolConversionException) {
        app.metrics.protocolConversionFailed(
            protocol.value,
            (upstreamProtocol ?: protocol).value,
            "unsupported_field",
        )
        statusCode = if (state.committed) HttpStatusCode.OK.value else HttpStatusCode.BadRequest.value
        errorCode = if (state.committed) "stream_interrupted" else "protocol_conversion_unsupported"
        failureStage = if (state.committed) FailureStage.STREAMING else FailureStage.REQUEST
        errorMessage = "${e.field}: ${e.message}".asLogMessage()
        if (state.committed) throw e
        throw badRequest("protocol_conversion_unsupported", "${e.field}: ${e.message}")
    } catch (e: Throwable) {
        statusCode = if (state.committed) HttpStatusCode.OK.value else HttpStatusCode.InternalServerError.value
        errorCode = if (state.committed) "stream_interrupted" else "internal_error"
        failureStage = when {
            state.committed -> FailureStage.STREAMING
            phase == FailureStage.UPSTREAM -> FailureStage.UPSTREAM
            else -> FailureStage.REQUEST
        }
        errorMessage = e.message.asLogMessage() ?: e::class.simpleName
        throw e
    } finally {
        if (errorCode == null && state.errorCode != null) {
            failureStage = FailureStage.STREAMING
            errorMessage = when (state.errorCode) {
                "responses_failed" -> "上游响应失败"
                "responses_incomplete" -> "上游响应未完成"
                "messages_failed" -> "上游响应失败"
                "gemini_failed" -> "上游响应失败"
                "protocol_conversion_failed" -> "上游流无法转换为调用方协议"
                else -> "上游流未正常结束（缺少结束帧）"
            }
        }
        if (upstreamErrorBody == null) {
            upstreamErrorBody = sanitizeUpstreamErrorBody(state.upstreamErrorBody)
        }
        val usage = state.usage
        settleRequestLimitOnExit(
            app = app,
            lease = requestLimitLease,
            usage = usage,
            channelId = channelId,
            upstreamModel = upstreamModel,
            pricingModelId = pricingModelId,
            requestId = requestId,
            traceId = traceContext.traceId,
            alias = alias,
            channelName = channelName,
            protocol = protocol,
            statusCode = statusCode,
            requestSucceeded = statusCode in 200..299 && errorCode == null && state.errorCode == null && failureStage == null,
            firstTokenMs = state.firstTokenMs,
            reasoningEffort = reasoningEffort,
        )
        val totalMs = (System.nanoTime() - startedAt) / 1_000_000
        val finalAttempts = attemptEvents.toList()
        app.metrics.request(
            if (channelId == null) "unresolved" else alias,
            channelId,
            statusCode,
            totalMs,
            failed = statusCode >= 400 || errorCode != null || state.errorCode != null || failureStage != null,
        )
        if (usage != null) app.metrics.usage(alias, usage)
        app.requestLogWriter.submit(
            RequestLogRecord(
                requestId = requestId,
                traceId = traceContext.traceId,
                clientKeyId = clientKey?.id,
                clientKeyName = clientKey?.name,
                clientIp = clientAccess.clientIp.takeUnless { it == "-" }?.take(64),
                clientUserAgent = clientUserAgent,
                alias = alias,
                channelId = channelId,
                channelName = channelName,
                channelKeyId = channelKeyId,
                channelKeyName = channelKeyName,
                upstreamModel = upstreamModel,
                pricingModelId = pricingModelId,
                protocol = protocol,
                upstreamProtocol = upstreamProtocol,
                stream = streamMode,
                statusCode = statusCode,
                firstTokenMs = state.firstTokenMs,
                totalMs = totalMs,
                inputTokens = usage?.input,
                outputTokens = usage?.output,
                cachedTokens = usage?.cached,
                cachedWriteTokens = usage?.cacheWrite,
                reasoningTokens = usage?.reasoning,
                reasoningEffort = reasoningEffort,
                totalTokens = usage?.total,
                usageKnown = usage?.known ?: false,
                costAmount = null,
                costCurrency = null,
                inputPricePerMillion = null,
                outputPricePerMillion = null,
                cachedInputPricePerMillion = null,
                errorCode = errorCode ?: state.errorCode,
                failureStage = failureStage,
                errorMessage = errorMessage,
                upstreamErrorCode = upstreamErrorCode,
                upstreamErrorBody = upstreamErrorBody,
                createdAt = System.currentTimeMillis(),
                upstreamAttempts = upstreamAttempts,
                attempts = finalAttempts,
            ),
        )
    }
}

private fun String?.asLogMessage(): String? = this?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }

/** 统计写入失败不改变已经完成的上游结果，也不触发重复请求。 */
private suspend fun recordKeyOutcome(
    app: GatewayApplication,
    candidate: RouteCandidate,
    signal: KeyAttemptSignal,
    requestId: String,
    probeClaimed: Boolean = false,
) {
    val channelKey = candidate.channelKey ?: return
    currentCoroutineContext().ensureActive()
    try {
        val reason = try {
            app.keyAutoDisableRepository.recordOutcome(
                channelKey, signal, requestId, allowDisable = false, probeClaimed = probeClaimed,
            )
        } catch (_: KeyAutoDisablePendingException) {
            app.keyAvailabilityGate.withWriter(candidate.credential.id) {
                app.keyAutoDisableRepository.recordOutcome(
                    channelKey, signal, requestId, allowDisable = true, probeClaimed = probeClaimed,
                )
            }
        }
        reason?.let { reason ->
            app.metrics.keyAutoDisabled(candidate.channel.name, reason)
            proxyLogger.warn(
                "上游密钥自动禁用，channelId={}, keyId={}, reason={}, requestId={}",
                candidate.channel.id, channelKey.id, reason, requestId,
            )
        }
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        app.metrics.keyAutoDisableWriteFailed()
        proxyLogger.error(
            "密钥自动禁用统计或审计写入失败，keyId={}, requestId={}, exceptionType={}",
            channelKey.id, requestId, cause.javaClass.simpleName,
        )
    }
}

/** 把上游异常映射成规则引擎信号；请求侧错误（400/404/422）交给规则自行决定是否计数。 */
private fun GatewayError.toKeyAttemptSignal(): KeyAttemptSignal? {
    val upstreamStatus = upstreamStatus
    return when {
        code == "client_disconnect" -> null
        upstreamStatus != null -> KeyAttemptSignal.UpstreamResponse(
            statusCode = upstreamStatus,
            errorBody = upstreamErrorBody.orEmpty(),
            upstreamCode = upstreamCode,
        )
        code in setOf("upstream_unreachable", "upstream_empty_stream", "upstream_timeout", "stream_interrupted") ->
            KeyAttemptSignal.TransportFailure(message)
        else -> null
    }
}

private class CandidateUnavailableException : RuntimeException(null, null, false, false)

/**
 * 把上游异常映射为冷却分类的粗分类；null 表示不参与冷却记账（400/422 等请求侧错误）。
 */
private fun classifyFailure(e: GatewayError): FailureKind? = when (e.code) {
    "upstream_unreachable", "upstream_empty_stream", "upstream_timeout", "stream_interrupted" -> FailureKind.TRANSPORT
    // 订阅账号需要重新授权：长冷却按密钥记键，修复靠管理员重新导入。
    "subscription_account_unavailable" -> FailureKind.AUTH_REJECTED
    // token 刷新失败：短冷却按密钥记键，不影响同渠道其他账号。
    "subscription_account_refresh_failed" -> FailureKind.CREDENTIAL_TRANSIENT
    "upstream_error" -> {
        val upstreamStatus = e.upstreamStatus
        when (upstreamStatus) {
            429 -> FailureKind.RATE_LIMITED
            401, 403 -> FailureKind.AUTH_REJECTED
            404 -> FailureKind.MODEL_NOT_FOUND
            null -> null
            // 形状拒绝只说明该上游读不懂请求，换候选即可，当前渠道不应因此冷却。
            else -> if (upstreamStatus >= 500 || (e.retryable && !e.shapeRefused)) FailureKind.SERVER_ERROR else null
        }
    }
    else -> null
}

private val QUOTA_REJECTION_CODES = setOf(
    "daily_token_quota_exceeded",
    "daily_cost_quota_exceeded",
    "concurrency_limit_exceeded",
    "key_rate_limit_exceeded",
)

private suspend fun settleRequestLimitOnExit(
    app: GatewayApplication,
    lease: RequestLimitLease?,
    usage: Usage?,
    channelId: String?,
    upstreamModel: String?,
    pricingModelId: String?,
    requestId: String,
    traceId: String?,
    alias: String,
    channelName: String?,
    protocol: GatewayProtocol,
    statusCode: Int,
    requestSucceeded: Boolean,
    firstTokenMs: Long?,
    reasoningEffort: String?,
) {
    if (lease == null) return
    val calculatedCost = try {
        if (channelId != null && upstreamModel != null) {
            try {
                app.modelPricingService.resolve(channelId, upstreamModel, pricingModelId)?.let { price ->
                    BillingCalculator.calculate(
                        RequestLogRecord(
                            requestId = requestId,
                            traceId = traceId,
                            clientKeyId = lease.clientKeyId,
                            clientKeyName = null,
                            alias = alias,
                            channelId = channelId,
                            channelName = channelName,
                            upstreamModel = upstreamModel,
                            pricingModelId = pricingModelId,
                            protocol = protocol,
                            statusCode = statusCode,
                            firstTokenMs = firstTokenMs,
                            totalMs = 0,
                            inputTokens = usage?.input,
                            outputTokens = usage?.output,
                            cachedTokens = usage?.cached,
                            cachedWriteTokens = usage?.cacheWrite,
                            reasoningTokens = usage?.reasoning,
                            reasoningEffort = reasoningEffort,
                            totalTokens = usage?.total,
                            usageKnown = usage?.known ?: false,
                            costAmount = null,
                            costCurrency = null,
                            inputPricePerMillion = null,
                            outputPricePerMillion = null,
                            cachedInputPricePerMillion = null,
                            errorCode = if (requestSucceeded) null else "failed",
                            failureStage = if (requestSucceeded) null else FailureStage.UPSTREAM,
                            errorMessage = null,
                            upstreamErrorCode = null,
                            createdAt = System.currentTimeMillis(),
                        ),
                        price,
                        requestSucceeded,
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                null
            }
        } else {
            null
        }
    } catch (cause: Throwable) {
        app.requestLimitService.release(lease)
        throw cause
    }
    app.requestLimitService.settle(
        lease,
        quotaTokensFromUsage(usage?.total, usage?.input, usage?.output),
        calculatedCost?.amount ?: java.math.BigDecimal.ZERO,
    )
}

private class ProxyState {
    var committed = false
    var statusCode = HttpStatusCode.OK.value
    var usage: Usage? = null
    var firstTokenMs: Long? = null
    /** 本次尝试的首 token 耗时，每次尝试开始时重置。 */
    var attemptFirstTokenMs: Long? = null
    /** 本次尝试收到的上游 HTTP 状态码；连接失败时为 null。 */
    var upstreamStatus: Int? = null
    /** 本次尝试的上游响应自报模型。 */
    var upstreamReportedModel: String? = null
    var upstreamCallStarted = false
    var streamError: GatewayError? = null
    var errorCode: String? = null
    var upstreamErrorBody: String? = null
}

private suspend fun authenticate(
    call: ApplicationCall,
    app: GatewayApplication,
    protocol: GatewayProtocol? = null,
): ClientKeyRecord {
    val authorization = call.request.headers[HttpHeaders.Authorization]
    val raw = if (authorization != null) {
        authorization.removePrefix("Bearer ").trim()
    } else if (protocol == GatewayProtocol.MESSAGES) {
        call.request.headers["x-api-key"]?.trim() ?: throw unauthorized()
    } else if (protocol == GatewayProtocol.GEMINI) {
        (call.request.headers["x-goog-api-key"] ?: call.request.queryParameters["key"])?.trim() ?: throw unauthorized()
    } else {
        throw unauthorized()
    }
    if (!raw.startsWith(ClientKeyHasher.PREFIX)) throw unauthorized()
    val record = app.clientKeyRepository.findByHash(ClientKeyHasher.hash(raw)) ?: throw unauthorized()
    if (record.revokedAt != null) throw unauthorized("调用方密钥已撤销")
    if (!record.enabled) throw unauthorized("调用方密钥已禁用")
    val expiresAt = record.expiresAt
    if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
        throw unauthorized("调用方密钥已过期")
    }
    return record
}

private suspend fun ApplicationCall.receiveLimitedJson(limit: Long): JsonObject {
    val declared = request.contentLength()
    if (declared != null && declared > limit) {
        throw GatewayError(
            HttpStatusCode.PayloadTooLarge,
            "request_too_large",
            "请求体超过 $limit 字节",
            "invalid_request_error",
        )
    }
    val bytes = request.receiveChannel().readRemaining(limit + 1).readBytes()
    if (bytes.size > limit) {
        throw GatewayError(
            HttpStatusCode.PayloadTooLarge,
            "request_too_large",
            "请求体超过 $limit 字节",
            "invalid_request_error",
        )
    }
    val element = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    return element as? JsonObject ?: throw badRequest("invalid_json", "请求体必须是 JSON 对象")
}

/** 单次请求使用的搜索运行时：设置已合并管理台值与环境变量默认值。 */
private data class WebSearchRuntime(
    val service: WebSearchService,
    val maxRounds: Int,
) {
    val available: Boolean get() = service.available
}

/**
 * Responses 内置 web_search 的代理路径：把请求转成 Chat，注入普通 web_search
 * 函数工具，由网关代跑搜索并多轮续写，最后再编码回 Responses。
 */
private suspend fun proxySearchStream(
    app: GatewayApplication,
    call: ApplicationCall,
    requestJson: JsonObject,
    candidate: RouteCandidate,
    alias: String,
    timeoutMs: Long,
    state: ProxyState,
    traceparent: String,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    webSearch: WebSearchRuntime,
) {
    val responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").take(24)
    val createdAt = System.currentTimeMillis() / 1000
    val emitter = SearchSseEmitter(alias, responseId, createdAt)
    state.committed = true
    state.statusCode = HttpStatusCode.OK.value
    try {
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            // 先发 response.created，客户端立即拿到首个事件；搜索与文本产出时再增量发送。
            emitter.created().forEach { writeSseLine(it) }
            val result = try {
                runSearchConversation(
                    app, call, requestJson, candidate, timeoutMs, state, traceparent, globalOverrides,
                    headerProfiles, webSearch,
                ) { part -> emitter.part(part).forEach { writeSseLine(it) } }
            } catch (e: GatewayError) {
                state.errorCode = e.code
                emitter.failed(e).forEach { writeSseLine(it) }
                return@respondBytesWriter
            }
            emitter.completed(result).forEach { writeSseLine(it) }
        }
    } catch (e: ChannelWriteException) {
        throw clientDisconnected()
    }
}

private suspend fun proxySearchJson(
    app: GatewayApplication,
    call: ApplicationCall,
    requestJson: JsonObject,
    candidate: RouteCandidate,
    alias: String,
    timeoutMs: Long,
    state: ProxyState,
    traceparent: String,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    webSearch: WebSearchRuntime,
) {
    val result = runSearchConversation(
        app, call, requestJson, candidate, timeoutMs, state, traceparent, globalOverrides, headerProfiles, webSearch,
    )
    val responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").take(24)
    val createdAt = System.currentTimeMillis() / 1000
    state.committed = true
    state.statusCode = HttpStatusCode.OK.value
    try {
        call.respondText(
            renderSearchResponse(result, alias, responseId, createdAt).toString(),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    } catch (e: ChannelWriteException) {
        throw clientDisconnected()
    }
}

private suspend fun runSearchConversation(
    app: GatewayApplication,
    call: ApplicationCall,
    requestJson: JsonObject,
    candidate: RouteCandidate,
    timeoutMs: Long,
    state: ProxyState,
    traceparent: String,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    webSearch: WebSearchRuntime,
    onPart: suspend (dev.soven.prismllm.proxy.search.ConversationPart) -> Unit = {},
): dev.soven.prismllm.proxy.search.SearchConversationResult {
    val toolName = syntheticWebSearchName(requestJson)
    val prepared = prepareResponsesSearchRequest(requestJson, toolName)
    val chatRequest = responsesToChatRequest(prepared).asChatUpstream(candidate.upstreamModel)
    val messages = (chatRequest["messages"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
    val startedAt = System.nanoTime()
    val caller = searchChatCaller(
        app, call, candidate, chatRequest, state, startedAt, traceparent, globalOverrides, headerProfiles,
    )
    // 第一轮之后不再强制调用工具，避免 tool_choice=required 让每一轮都被迫搜索。
    val followUpCaller = searchChatCaller(
        app, call, candidate, withAutoToolChoice(chatRequest, toolName), state, startedAt, traceparent, globalOverrides, headerProfiles,
    )
    val finalRequest = withoutSyntheticTool(chatRequest, toolName)
    val finalCaller = searchChatCaller(
        app, call, candidate, finalRequest, state, startedAt, traceparent, globalOverrides, headerProfiles,
    )
    // 重试时保留前一候选已产生的用量：token 已经消耗，不能因为换候选而丢失。
    val previousUsage = state.usage
    var attemptUsage: Usage? = null
    try {
        return withTimeout(timeoutMs) {
            SearchConversation(
                toolName,
                webSearch.service,
                webSearch.maxRounds,
                webSearchAllowedDomains(requestJson),
            )
                .run(messages, caller, followUpCaller, finalCaller, onPart) { roundUsage ->
                    attemptUsage = addUsage(attemptUsage, roundUsage)
                    state.usage = addUsage(previousUsage, attemptUsage)
                }
                .also { result -> state.usage = addUsage(previousUsage, result.usage) }
        }
    } catch (e: TimeoutCancellationException) {
        throw GatewayError(
            HttpStatusCode.GatewayTimeout, "upstream_timeout", "上游响应超时", "timeout_error",
            retryable = true,
        )
    } catch (e: GatewayError) {
        throw e
    } catch (e: dev.soven.prismllm.proxy.search.WebSearchException) {
        throw GatewayError(
            HttpStatusCode.BadGateway, "upstream_invalid_response",
            e.message ?: "上游返回了无效响应", "api_error",
            retryable = true,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw GatewayError(
            HttpStatusCode.BadGateway, "web_search_failed",
            "web_search 代理失败: ${e.message ?: e::class.simpleName}", "api_error",
            retryable = false,
        )
    }
}

/**
 * 后续搜索轮次把强制调用降级为 auto，让模型可以在已有结果上直接回答。
 * 只降级 `required` 和强制合成搜索函数；客户端强制调用自己的函数时保持原样。
 */
internal fun withAutoToolChoice(request: JsonObject, syntheticName: String): JsonObject {
    val choice = request["tool_choice"]
    val forcedSynthetic = choice is JsonObject &&
        (((choice["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull == syntheticName)
    val required = (choice as? JsonPrimitive)?.contentOrNull == "required"
    if (!forcedSynthetic && !required) return request
    return buildJsonObject {
        request.forEach { (key, value) ->
            if (key == "tool_choice") put(key, JsonPrimitive("auto")) else put(key, value)
        }
    }
}

/** 收尾调用不再向模型暴露合成搜索工具，并把强制调用该工具的 tool_choice 降级为 auto。 */
private fun withoutSyntheticTool(request: JsonObject, toolName: String): JsonObject {
    val tools = (request["tools"] as? JsonArray).orEmpty().filter { raw ->
        val function = (raw as? JsonObject)?.get("function") as? JsonObject
        (function?.get("name") as? JsonPrimitive)?.contentOrNull != toolName
    }
    val choice = request["tool_choice"]
    val forcedSynthetic = choice is JsonObject &&
        (((choice["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull == toolName)
    val adjustedChoice = when {
        forcedSynthetic -> JsonPrimitive("auto")
        choice is JsonPrimitive && choice.contentOrNull == "required" && tools.isEmpty() -> JsonPrimitive("auto")
        else -> choice
    }
    return buildJsonObject {
        request.forEach { (key, value) ->
            when (key) {
                "tools" -> if (tools.isNotEmpty()) put("tools", JsonArray(tools))
                "tool_choice" -> if (adjustedChoice != null) put("tool_choice", adjustedChoice)
                else -> put(key, value)
            }
        }
    }
}

/** 搜索代理单轮模型响应的字节上限；超出时取消读取，避免上游超大响应耗尽堆内存。 */
private const val MAX_SEARCH_RESPONSE_BYTES = 8 * 1024 * 1024

private suspend fun readSearchResponseBody(response: HttpResponse): String {
    val channel = response.bodyAsChannel()
    val bytes = channel.readRemaining(MAX_SEARCH_RESPONSE_BYTES.toLong() + 1L).readBytes()
    if (bytes.size > MAX_SEARCH_RESPONSE_BYTES) {
        channel.cancel()
        throw GatewayError(
            HttpStatusCode.BadGateway,
            "upstream_invalid_response",
            "上游响应超过 ${MAX_SEARCH_RESPONSE_BYTES / (1024 * 1024)} MB 上限",
            "api_error",
            retryable = false,
        )
    }
    return bytes.toString(Charsets.UTF_8)
}

private suspend fun searchChatCaller(
    app: GatewayApplication,
    call: ApplicationCall,
    candidate: RouteCandidate,
    baseRequest: JsonObject,
    state: ProxyState,
    startedAt: Long,
    traceparent: String,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
): ChatCaller {
    val upstreamProtocol = GatewayProtocol.CHAT_COMPLETIONS
    val upstreamUrl = upstreamRequestUrl(candidate.channel.baseUrl, upstreamProtocol, candidate.upstreamModel)
    val credential = decryptCredential(app, candidate)
    val authType = candidate.channel.authTypeFor(upstreamProtocol)
    val policy = UpstreamRequestPolicy(
        globalOverrides, candidate.channel.requestOverrides, upstreamProtocol, authType, headerProfiles,
        candidate.channel.baseUrl,
    )
    return ChatCaller { messages ->
        val body = buildJsonObject {
            baseRequest.forEach { (key, value) ->
                if (key !in setOf("messages", "stream", "stream_options")) put(key, value)
            }
            put("messages", JsonArray(messages))
            put("stream", false)
        }
        val requestBody = policy.body(body, candidate.upstreamModel, app.config.requestBodyLimitBytes).toString()
        state.upstreamCallStarted = true
        val response = app.httpClient.post(upstreamUrl) {
            applyUpstreamAuthentication(
                credential = credential,
                authType = authType,
                protocol = upstreamProtocol,
                anthropicVersion = call.request.headers["anthropic-version"],
                anthropicBeta = call.request.headers["anthropic-beta"],
            )
            header("traceparent", traceparent)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            policy.applyHeaders(this)
            setBody(TextContent(requestBody, ContentType.Application.Json))
        }
        state.upstreamStatus = response.status.value
        if (state.attemptFirstTokenMs == null) {
            state.attemptFirstTokenMs = (System.nanoTime() - startedAt) / 1_000_000
            state.firstTokenMs = state.attemptFirstTokenMs
        }
        candidate.oauthAccount?.let { account ->
            app.oauthAccountScheduler.noteQuotaHeaders(
                account,
                parseCodexQuotaHeaders({ name -> response.headers[name] }, System.currentTimeMillis()),
            )
        }
        val text = readSearchResponseBody(response)
        if (!response.status.isSuccess()) {
            throw upstreamException(
                response.status,
                text,
                retryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                upstreamProtocol,
                allowNativeMessagesError = false,
            )
        }
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw GatewayError(
                HttpStatusCode.BadGateway, "upstream_invalid_response", "上游返回了无效 JSON", "api_error",
                retryable = true,
            )
        state.upstreamReportedModel = extractReportedModel(upstreamProtocol, json)
        json
    }
}

private suspend fun proxyStream(
    app: GatewayApplication,
    call: ApplicationCall,
    requestJson: JsonObject,
    candidate: RouteCandidate,
    permit: CooldownPermit,
    alias: String,
    startedAt: Long,
    attemptStartedNanos: Long,
    timeoutMs: Long,
    state: ProxyState,
    protocol: GatewayProtocol,
    upstreamProtocol: GatewayProtocol,
    traceparent: String,
    allowUpstreamErrorFailover: Boolean,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    clientKeyId: String? = null,
) {
    val envelope = candidate.channel.upstreamEnvelope
    val upstreamUrl = upstreamRequestUrl(
        candidate.channel.baseUrl,
        upstreamProtocol,
        candidate.upstreamModel,
        stream = true,
        envelope = envelope,
    )
    val credential = decryptCredential(app, candidate)
    val authType = candidate.channel.authTypeFor(upstreamProtocol)
    val policy = UpstreamRequestPolicy(
        globalOverrides, candidate.channel.requestOverrides, upstreamProtocol, authType,
        headerProfiles, candidate.channel.baseUrl,
    )
    val convertedRequest = if (protocol == upstreamProtocol) {
        requestJson
    } else {
        ProtocolConversion.convertRequest(
            protocol,
            upstreamProtocol,
            requestJson,
            candidate.upstreamModel,
            carry = app.reasoningCarryCache.scoped(candidate.channel.id),
        )
    }
    val streamRequest = withUpstreamStreamFlag(convertedRequest, upstreamProtocol, stream = true)
    val usageRequest = if (upstreamProtocol == GatewayProtocol.CHAT_COMPLETIONS && protocol != upstreamProtocol) {
        streamRequest.asChatUpstream(candidate.upstreamModel)
    } else {
        streamRequest
    }
    val bodyJson = policy.body(usageRequest, candidate.upstreamModel, app.config.requestBodyLimitBytes)
    // 账号出口：OAuth 账号配置了 proxy_url 时，发现、目录与对话请求都必须走同一个代理。
    val egressProxy = accountEgressProxy(app, candidate)
    // 准备阶段（project 发现、目录补拉）与对话请求共用同一份剩余预算：
    // 发现 + 目录合计最多花掉 min(剩余预算, 20s)，对话阶段只拿剩下的部分。
    val callDeadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
    val preparationDeadlineNanos =
        System.nanoTime() + preparationTimeoutMs(timeoutMs) * 1_000_000
    val codeAssistContext = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
        withTimeoutOrNull(remainingMs(preparationDeadlineNanos)) {
            app.codeAssistProjectResolver.context(candidate.oauthAccount!!, credential, egressProxy)
        }
    } else {
        null
    }
    val codeAssistProject = codeAssistContext?.project
    // 进程启动后第一次请求时补一次模型目录，避免重启后家族 id 失去变体映射。
    // 目录按 project 缓存，不会跨账号串用。
    val codeAssistCatalog = if (envelope == UpstreamEnvelope.CODE_ASSIST && codeAssistProject != null) {
        val cached = app.codeAssistModelCatalog.cached(codeAssistProject, codeAssistContext?.tier)
        cached ?: withTimeoutOrNull(remainingMs(preparationDeadlineNanos)) {
            catalogOrNull {
                app.codeAssistModelCatalog.fetchFor(
                    credential,
                    codeAssistProject,
                    listOfNotNull(codeAssistContext?.tier),
                    egressProxy,
                )
            }
        }
    } else {
        null
    }
    val body = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
        val sentModel = codeAssistCatalog?.sentId(
            candidate.upstreamModel,
            ReasoningEffort.fromRequest(bodyJson, GatewayProtocol.GEMINI),
        ) ?: candidate.upstreamModel
        val request = CodeAssistCloak.apply(bodyJson)
        // 会话 id 绑定客户端凭据、渠道、模型别名与调用方会话标识；不再用请求正文派生，
        // 否则不同客户端只要首条用户文本相同就会共用一个上游会话。
        CodeAssistEnvelope.wrap(
            request,
            sentModel,
            codeAssistProject,
            CodeAssistEnvelope.sessionScope(
                alias,
                candidate.channel.id,
                clientKeyId,
                clientSessionId(call),
            ),
        ).toString()
    } else {
        bodyJson.toString()
    }
    val converter = ProtocolConversion.streamConverter(
        protocol,
        upstreamProtocol,
        alias,
        carry = app.reasoningCarryCache.scoped(candidate.channel.id),
    )

    try {
        withTimeout(remainingMs(callDeadlineNanos)) {
            state.upstreamCallStarted = true
            upstreamClient(app, egressProxy).preparePost(upstreamUrl) {
                applyUpstreamAuthentication(
                    credential = credential,
                    authType = authType,
                    protocol = upstreamProtocol,
                    anthropicVersion = call.request.headers["anthropic-version"],
                    anthropicBeta = call.request.headers["anthropic-beta"],
                )
                header("traceparent", traceparent)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                policy.applyHeaders(this)
                if (envelope == UpstreamEnvelope.CODE_ASSIST) {
                    // 客户端身份由全局请求头目录维护，避免把版本号写死在数据面。
                    applyCodeAssistIdentity(app.currentAntigravityUserAgent())
                }
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                state.upstreamStatus = response.status.value
                candidate.oauthAccount?.let { account ->
                    app.oauthAccountScheduler.noteQuotaHeaders(
                        account,
                        parseCodexQuotaHeaders({ name -> response.headers[name] }, System.currentTimeMillis()),
                    )
                }
                if (!response.status.isSuccess()) {
                    val errorText = response.bodyAsText()
                    throw upstreamException(
                        response.status,
                        // Code Assist 的错误同样是 {"error": ...} 信封，解包后才能按 Gemini 错误识别。
                        if (envelope == UpstreamEnvelope.CODE_ASSIST) CodeAssistEnvelope.errorBody(errorText) else errorText,
                        retryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                        upstreamProtocol,
                        allowNativeMessagesError = protocol == upstreamProtocol,
                    )
                }
                val upstream = response.bodyAsChannel()
                val maxLineBytes = if (converter != null) MAX_PROTOCOL_EVENT_BYTES + 6 else Int.MAX_VALUE
                val prefixLines = mutableListOf<String>()
                val firstDataLine = try {
                    withTimeout(app.config.firstTokenTimeoutMs) {
                        var found: String? = null
                        while (found == null) {
                            val next = readProtocolLine(upstream, maxLineBytes, envelope) ?: break
                            prefixLines.add(next)
                            recordReportedModel(next, state, upstreamProtocol)
                            if (
                                allowUpstreamErrorFailover &&
                                ((upstreamProtocol == GatewayProtocol.MESSAGES && isAnthropicStreamError(next)) ||
                                    (upstreamProtocol == GatewayProtocol.GEMINI && isGeminiStreamError(next)))
                            ) {
                                val error = if (upstreamProtocol == GatewayProtocol.GEMINI) geminiStreamException(next)
                                    else anthropicStreamException(next)
                                state.streamError = error
                                if (error.retryable) {
                                    upstream.cancel(error)
                                    throw error
                                }
                            }
                            if (next.trim().startsWith("data:") && isFirstStreamData(next, upstreamProtocol)) {
                                found = next
                            }
                        }
                        found
                    }
                } catch (e: TimeoutCancellationException) {
                    response.call.cancel(e)
                    upstream.cancel(e)
                    throw GatewayError(
                        HttpStatusCode.GatewayTimeout,
                        "upstream_timeout",
                        "上游首 token 超时",
                        "timeout_error",
                        retryable = true,
                    )
                } ?: throw GatewayError(
                    HttpStatusCode.BadGateway,
                    "upstream_empty_stream",
                    "上游没有返回流式数据",
                    retryable = true,
                )

                state.committed = true
                state.statusCode = HttpStatusCode.OK.value

                val completion = CompletableDeferred<Unit>()
                try {
                    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                        try {
                            var sawDone = false
                            var streamFailure: GatewayError? = null
                            var lastDataLine: String? = null
                            for (line in prefixLines) {
                                if (converter != null && line.trim().startsWith("data:")) lastDataLine = line
                                converter?.feed(line)?.forEach { writeSseLine(it) } ?: writeSseLine(line)
                                if (converter?.failed == true) {
                                    lastDataLine?.let { markTerminalStreamFailure(it, upstreamProtocol, state) }
                                    if (state.errorCode == null) state.errorCode = "protocol_conversion_failed"
                                    break
                                }
                                markFirstToken(line, upstreamProtocol, state, startedAt, attemptStartedNanos, app, alias, candidate, permit)
                                recordUsage(line, state, upstreamProtocol)
                                if (isStreamDone(line, upstreamProtocol)) {
                                    markTerminalStreamFailure(line, upstreamProtocol, state)
                                    writeSseLine("")
                                    sawDone = true
                                    if (upstreamProtocol != GatewayProtocol.GEMINI) break
                                }
                            }
                            if (converter?.failed != true && (!sawDone || upstreamProtocol == GatewayProtocol.GEMINI)) {
                                while (true) {
                                    val line = try {
                                        withTimeout(app.config.idleTimeoutMs) {
                                            readProtocolLine(upstream, maxLineBytes, envelope)
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        throw GatewayError(
                                            HttpStatusCode.GatewayTimeout,
                                            "upstream_timeout",
                                            "上游流式响应空闲超时",
                                            "timeout_error",
                                        )
                                    } catch (e: GatewayError) {
                                        if (e.code != "upstream_event_too_large") throw e
                                        streamFailure = e
                                        converter?.fail(e.message)?.forEach { writeSseLine(it) }
                                        break
                                    } ?: break
                                    recordReportedModel(line, state, upstreamProtocol)
                                    if (converter != null && line.trim().startsWith("data:")) lastDataLine = line
                                    converter?.feed(line)?.forEach { writeSseLine(it) } ?: writeSseLine(line)
                                    if (converter?.failed == true) {
                                        lastDataLine?.let { markTerminalStreamFailure(it, upstreamProtocol, state) }
                                        if (state.errorCode == null) state.errorCode = "protocol_conversion_failed"
                                        break
                                    }
                                    markFirstToken(line, upstreamProtocol, state, startedAt, attemptStartedNanos, app, alias, candidate, permit)
                                    recordUsage(line, state, upstreamProtocol)
                                    if (isStreamDone(line, upstreamProtocol)) {
                                        markTerminalStreamFailure(line, upstreamProtocol, state)
                                        writeSseLine("")
                                        sawDone = true
                                        if (upstreamProtocol != GatewayProtocol.GEMINI) break
                                    }
                                }
                            }
                            converter?.finish()?.forEach { writeSseLine(it) }
                            if (converter?.failed == true && state.errorCode == null) {
                                lastDataLine?.let { markTerminalStreamFailure(it, upstreamProtocol, state) }
                                if (state.errorCode == null) state.errorCode = "protocol_conversion_failed"
                            }
                            if (streamFailure != null) {
                                completion.completeExceptionally(streamFailure)
                            } else {
                                if (!sawDone && state.errorCode == null) state.errorCode = "stream_interrupted"
                                completion.complete(Unit)
                            }
                        } catch (e: Throwable) {
                            completion.completeExceptionally(e)
                            throw e
                        }
                    }
                    completion.await()
                } catch (e: Throwable) {
                    upstream.cancel(e)
                    throw e
                }
            }
        }
    } catch (e: ChannelWriteException) {
        throw clientDisconnected()
    } catch (e: TimeoutCancellationException) {
        throw GatewayError(
            HttpStatusCode.GatewayTimeout,
            "upstream_timeout",
            "上游响应超时",
            "timeout_error",
            retryable = !state.committed,
        )
    } catch (e: ProtocolConversionException) {
        throw e
    } catch (e: GatewayError) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw transportFailure(state.committed)
    }
}

internal suspend fun readProtocolLine(
    upstream: ByteReadChannel,
    maxLineBytes: Int,
    envelope: UpstreamEnvelope = UpstreamEnvelope.NONE,
): String? = try {
    upstream.readUTF8Line(max = maxLineBytes)?.let { line ->
        if (envelope == UpstreamEnvelope.CODE_ASSIST) CodeAssistEnvelope.unwrapSseLine(line) else line
    }
} catch (_: TooLongLineException) {
    throw GatewayError(
        HttpStatusCode.BadGateway,
        "upstream_event_too_large",
        "上游流单个事件超过大小上限",
        "upstream_error",
        retryable = false,
    )
}

private suspend fun proxyJson(
    app: GatewayApplication,
    call: ApplicationCall,
    requestJson: JsonObject,
    alias: String,
    candidate: RouteCandidate,
    timeoutMs: Long,
    state: ProxyState,
    protocol: GatewayProtocol,
    upstreamProtocol: GatewayProtocol,
    traceparent: String,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    clientKeyId: String? = null,
) {
    val envelope = candidate.channel.upstreamEnvelope
    val upstreamUrl = upstreamRequestUrl(
        candidate.channel.baseUrl,
        upstreamProtocol,
        candidate.upstreamModel,
        envelope = envelope,
    )
    val credential = decryptCredential(app, candidate)
    val authType = candidate.channel.authTypeFor(upstreamProtocol)
    val policy = UpstreamRequestPolicy(
        globalOverrides, candidate.channel.requestOverrides, upstreamProtocol, authType,
        headerProfiles, candidate.channel.baseUrl,
    )
    val convertedRequest = if (protocol == upstreamProtocol) {
        requestJson
    } else {
        ProtocolConversion.convertRequest(
            protocol,
            upstreamProtocol,
            requestJson,
            candidate.upstreamModel,
            carry = app.reasoningCarryCache.scoped(candidate.channel.id),
        )
    }
    val nonStreamRequest = withUpstreamStreamFlag(convertedRequest, upstreamProtocol, stream = false)
    val bodyJson = policy.body(nonStreamRequest, candidate.upstreamModel, app.config.requestBodyLimitBytes)
    // 账号出口：OAuth 账号配置了 proxy_url 时，发现、目录与对话请求都必须走同一个代理。
    val egressProxy = accountEgressProxy(app, candidate)
    // 准备阶段（project 发现、目录补拉）与对话请求共用同一份剩余预算：
    // 发现 + 目录合计最多花掉 min(剩余预算, 20s)，对话阶段只拿剩下的部分。
    val callDeadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
    val preparationDeadlineNanos =
        System.nanoTime() + preparationTimeoutMs(timeoutMs) * 1_000_000
    val codeAssistContext = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
        withTimeoutOrNull(remainingMs(preparationDeadlineNanos)) {
            app.codeAssistProjectResolver.context(candidate.oauthAccount!!, credential, egressProxy)
        }
    } else {
        null
    }
    val codeAssistProject = codeAssistContext?.project
    // 进程启动后第一次请求时补一次模型目录，避免重启后家族 id 失去变体映射。
    // 目录按 project 缓存，不会跨账号串用。
    val codeAssistCatalog = if (envelope == UpstreamEnvelope.CODE_ASSIST && codeAssistProject != null) {
        val cached = app.codeAssistModelCatalog.cached(codeAssistProject, codeAssistContext?.tier)
        cached ?: withTimeoutOrNull(remainingMs(preparationDeadlineNanos)) {
            catalogOrNull {
                app.codeAssistModelCatalog.fetchFor(
                    credential,
                    codeAssistProject,
                    listOfNotNull(codeAssistContext?.tier),
                    egressProxy,
                )
            }
        }
    } else {
        null
    }
    val body = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
        val sentModel = codeAssistCatalog?.sentId(
            candidate.upstreamModel,
            ReasoningEffort.fromRequest(bodyJson, GatewayProtocol.GEMINI),
        ) ?: candidate.upstreamModel
        val request = CodeAssistCloak.apply(bodyJson)
        // 会话 id 绑定客户端凭据、渠道、模型别名与调用方会话标识；不再用请求正文派生，
        // 否则不同客户端只要首条用户文本相同就会共用一个上游会话。
        CodeAssistEnvelope.wrap(
            request,
            sentModel,
            codeAssistProject,
            CodeAssistEnvelope.sessionScope(
                alias,
                candidate.channel.id,
                clientKeyId,
                clientSessionId(call),
            ),
        ).toString()
    } else {
        bodyJson.toString()
    }
    try {
        withTimeout(remainingMs(callDeadlineNanos)) {
            state.upstreamCallStarted = true
            val response = upstreamClient(app, egressProxy).post(upstreamUrl) {
                applyUpstreamAuthentication(
                    credential = credential,
                    authType = authType,
                    protocol = upstreamProtocol,
                    anthropicVersion = call.request.headers["anthropic-version"],
                    anthropicBeta = call.request.headers["anthropic-beta"],
                )
                header("traceparent", traceparent)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                policy.applyHeaders(this)
                if (envelope == UpstreamEnvelope.CODE_ASSIST) {
                    // 客户端身份由全局请求头目录维护，避免把版本号写死在数据面。
                    applyCodeAssistIdentity(app.currentAntigravityUserAgent())
                }
                setBody(TextContent(body, ContentType.Application.Json))
            }
            state.upstreamStatus = response.status.value
            candidate.oauthAccount?.let { account ->
                app.oauthAccountScheduler.noteQuotaHeaders(
                    account,
                    parseCodexQuotaHeaders({ name -> response.headers[name] }, System.currentTimeMillis()),
                )
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw upstreamException(
                    response.status,
                    // Code Assist 的错误同样是 {"error": ...} 信封，解包后才能按 Gemini 错误识别。
                    if (envelope == UpstreamEnvelope.CODE_ASSIST) CodeAssistEnvelope.errorBody(text) else text,
                    retryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                    upstreamProtocol,
                    allowNativeMessagesError = protocol == upstreamProtocol,
                )
            }
            val rawJson = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            val json = if (envelope == UpstreamEnvelope.CODE_ASSIST && rawJson != null) {
                CodeAssistEnvelope.unwrap(rawJson)
            } else {
                rawJson
            }
            state.upstreamReportedModel = extractReportedModel(upstreamProtocol, json)
            state.usage = runCatching {
                json ?: return@runCatching null
                when (upstreamProtocol) {
                    GatewayProtocol.CHAT_COMPLETIONS -> UsageParser.fromJson(json)
                    GatewayProtocol.RESPONSES -> UsageParser.fromResponsesJson(json)
                    GatewayProtocol.MESSAGES -> UsageParser.fromAnthropicJson(json)
                    GatewayProtocol.GEMINI -> UsageParser.fromGeminiJson(json)
                }
            }.getOrNull()
            val clientBody = if (protocol == upstreamProtocol) {
                if (envelope == UpstreamEnvelope.CODE_ASSIST) {
                    (json ?: throw transportFailure(false)).toString()
                } else {
                    text
                }
            } else {
                val parsed = json ?: throw transportFailure(false)
                ProtocolConversion.convertResponse(
                    protocol,
                    upstreamProtocol,
                    parsed,
                    model = alias,
                    carry = app.reasoningCarryCache.scoped(candidate.channel.id),
                ).toString()
            }
            state.committed = true
            state.statusCode = response.status.value
            call.respondText(clientBody, ContentType.Application.Json, response.status)
        }
    } catch (e: ChannelWriteException) {
        throw clientDisconnected()
    } catch (e: TimeoutCancellationException) {
        throw GatewayError(
            HttpStatusCode.GatewayTimeout,
            "upstream_timeout",
            "上游响应超时",
            "timeout_error",
            retryable = !state.committed,
        )
    } catch (e: ProtocolConversionException) {
        throw e
    } catch (e: GatewayError) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw transportFailure(state.committed)
    }
}

private fun protocolNotSupported(alias: String, protocol: GatewayProtocol): GatewayError =
    GatewayError(
        HttpStatusCode.BadRequest,
        "protocol_not_supported",
        "模型别名 $alias 没有支持 ${protocol.value} 的渠道",
        "invalid_request_error",
    )

/**
 * 数据面凭据解析：OAuth 账号取 access token，普通密钥解密静态凭据。
 * 账号不可用按可切换错误处理，让路由继续尝试其他候选，绝不回退到 refresh token。
 */
/**
 * 账号出口：project 发现、模型目录与对话请求都要与 token 刷新走同一个代理。
 * 配置了 proxy_url 时改用按该代理创建的客户端，没有解析结果时沿用应用共享客户端。
 */
internal fun upstreamClient(app: GatewayApplication, proxy: InetSocketAddress?): HttpClient =
    proxy?.let { address ->
        app.accountEgressClients.computeIfAbsent(address.hostString + ":" + address.port) { _ ->
            HttpClient(Java) {
                engine {
                    config { proxy(ProxySelector.of(address)) }
                }
                // 与共享客户端保持一致：整次请求由调用方的 withTimeout 控制。
                install(HttpTimeout) {
                    connectTimeoutMillis = app.config.connectTimeoutMs
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            }
        }
    } ?: app.httpClient

/**
 * 调用方显式声明的会话标识。不能用 x-request-id / traceparent 这类每次请求都变的
 * 标识，否则多轮对话每轮都会换上游会话。
 */
private fun clientSessionId(call: ApplicationCall): String? =
    listOf("x-session-id", "x-conversation-id", "conversation-id", "session-id")
        .firstNotNullOfOrNull { call.request.headers[it]?.trim()?.takeIf(String::isNotEmpty) }

/**
 * 目录补拉失败只降级为 null（请求照常发出），但协程取消必须原样抛出，
 * 否则已取消的请求还会继续占用连接与上游资源。
 */
private suspend fun <T> catalogOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cause: CancellationException) {
    throw cause
} catch (cause: Throwable) {
    proxyLogger.warn("code assist model catalog fetch failed, error={}", cause::class.simpleName)
    null
}

/** 对话阶段可用的剩余预算；准备阶段耗尽时立即按超时处理。 */
private fun remainingMs(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

/** 准备阶段（project 发现、目录补拉）的上限：不超过对话请求本身的超时。 */
private fun preparationTimeoutMs(timeoutMs: Long): Long = minOf(timeoutMs, PREPARATION_TIMEOUT_MS)

private const val PREPARATION_TIMEOUT_MS = 20_000L

/**
 * 账号代理配置无效时拒绝请求，绝不静默回落直连：那会绕过账号指定出口并暴露网关源 IP。
 * 按渠道配置错误处理，不切换到其他候选。
 */
private fun accountEgressProxy(app: GatewayApplication, candidate: RouteCandidate): InetSocketAddress? {
    val proxyUrl = candidate.oauthAccount?.proxyUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        proxyAddress(proxyUrl, app.config.oauth.proxyAllowedHosts)
    } catch (cause: OAuthRefreshException) {
        proxyLogger.warn("invalid account proxy, channelId={}, errorCode={}", candidate.channel.id, cause.errorCode)
        throw GatewayError(
            HttpStatusCode.BadRequest,
            "invalid_oauth_proxy",
            "订阅账号代理配置无效: " + (cause.message ?: "账号代理地址无效"),
            "invalid_request_error",
        )
    }
}

private suspend fun decryptCredential(app: GatewayApplication, candidate: RouteCandidate): String = try {
    app.oauthTokenService.resolveCredential(candidate.credential)
} catch (cause: CancellationException) {
    throw cause
} catch (cause: OAuthTokenUnavailableException) {
    throw GatewayError(
        HttpStatusCode.BadGateway,
        when (cause.reason) {
            OAuthUnavailableReason.REFRESH_FAILED -> "subscription_account_refresh_failed"
            else -> "subscription_account_unavailable"
        },
        cause.message ?: "订阅账号不可用",
        "api_error",
        retryable = true,
    )
} catch (cause: Throwable) {
    if (cause is VirtualMachineError) throw cause
    throw GatewayError(
        HttpStatusCode.BadGateway,
        "upstream_credential_invalid",
        "渠道凭据无法解密",
        retryable = false,
    )
}

private fun transportFailure(committed: Boolean): GatewayError =
    GatewayError(
        HttpStatusCode.BadGateway,
        if (committed) "stream_interrupted" else "upstream_unreachable",
        if (committed) "上游连接中断" else "无法连接上游",
        "api_error",
        retryable = !committed,
    )

/** Gemini carries streaming in the URL action; other protocols need an explicit JSON flag. */
private fun withUpstreamStreamFlag(request: JsonObject, protocol: GatewayProtocol, stream: Boolean): JsonObject =
    if (protocol == GatewayProtocol.GEMINI) request
    else JsonObject(request + ("stream" to JsonPrimitive(stream)))

private fun clientDisconnected(): GatewayError =
    GatewayError(
        HttpStatusCode(499, "Client Closed Request"),
        "client_disconnect",
        "客户端连接中断",
        "api_error",
    )

private const val MAX_KEY_MATCH_BODY_CHARS = 8_192

private data class ParsedUpstreamError(
    val code: String? = null,
    val type: String? = null,
    val status: String? = null,
    val message: String? = null,
    val isStructured: Boolean = false,
)

private fun parseUpstreamError(body: String): ParsedUpstreamError = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val error = root["error"] as? JsonObject
    val isStructured = error != null || root.containsKey("error") || root.containsKey("message") || root.containsKey("detail")
    val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        ?: (root["code"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
    val type = (error?.get("type") as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        ?: (root["type"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
    val status = (error?.get("status") as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        ?: (root["status"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        ?: (root["message"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
        ?: (root["detail"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() }
    ParsedUpstreamError(code = code, type = type, status = status, message = message, isStructured = isStructured)
}.getOrDefault(ParsedUpstreamError(isStructured = false))

// 常见中转商在内部渠道故障时返回的伪 400 特征错误码
private val UPSTREAM_ERROR_CODES = setOf(
    "upstream_error",
    "one_api_error",
    "new_api_error",
    "channel_error",
    "no_available_channel",
    "upstream_unavailable",
    "all_channels_failed",
    "all_upstreams_failed",
    "provider_error",
    "upstream_timeout",
)

// 已知的标准客户端错误类型与状态，避免兜底文本匹配误伤常规参数错误
private val KNOWN_CLIENT_ERROR_TYPES = setOf(
    "invalid_request_error",
    "client_error",
)

private val KNOWN_CLIENT_ERROR_STATUSES = setOf(
    "invalid_argument",
    "failed_precondition",
    "out_of_range",
)

private fun isUpstreamPseudo400(statusCode: Int, parsed: ParsedUpstreamError, body: String): Boolean {
    if (statusCode != 400) return false
    // 1. 如果显式包含上游故障特征码或类型，直接判定为伪 400
    val codeMatch = parsed.code?.lowercase()?.let { it in UPSTREAM_ERROR_CODES } == true
    val typeMatch = parsed.type?.lowercase()?.let { it in UPSTREAM_ERROR_CODES } == true
    if (codeMatch || typeMatch) {
        return true
    }
    // 2. 如果存在明确的标准客户端错误类型（如 invalid_request_error）或 Gemini 客户端状态（如 INVALID_ARGUMENT），排除匹配
    if (parsed.type?.lowercase() in KNOWN_CLIENT_ERROR_TYPES) {
        return false
    }
    if (parsed.status?.lowercase() in KNOWN_CLIENT_ERROR_STATUSES) {
        return false
    }
    // 3. 目标文本提取：结构化响应仅匹配显式错误消息字段；只有非结构化纯文本响应才对整个 body 进行兜底匹配
    val targetMessage = if (parsed.isStructured) {
        parsed.message?.lowercase() ?: return false
    } else {
        body.lowercase()
    }
    return targetMessage.contains("模型请求失败") ||
        targetMessage.contains("请稍后重试或更换模型") ||
        targetMessage.contains("无可用渠道") ||
        targetMessage.contains("all upstream channels failed") ||
        targetMessage.contains("all channels failed") ||
        targetMessage.contains("upstream error") ||
        targetMessage.contains("no channel available") ||
        targetMessage.contains("no available upstream")
}

private fun upstreamException(
    status: HttpStatusCode,
    body: String,
    retryAfterMs: Long?,
    protocol: GatewayProtocol,
    allowNativeMessagesError: Boolean = true,
): GatewayError {
    val nativeResponseBody = body.takeIf {
        (allowNativeMessagesError && protocol == GatewayProtocol.MESSAGES && isAnthropicErrorBody(it)) ||
            (protocol == GatewayProtocol.GEMINI && isGeminiErrorBody(it))
    }
    val parsedError = parseUpstreamError(body)
    val upstreamCode = parsedError.code ?: parsedError.type ?: parsedError.status
    val isPseudo400 = isUpstreamPseudo400(status.value, parsedError, body)
    // 422 与命中 shape refusal 词表的 400 说明上游读不懂请求的形状，换一个候选渠道可能就能服务：
    // 这类错误可重试，但不让当前渠道冷却。伪 400 是渠道自身故障，仍按原逻辑处理。
    val shapeRefused = !isPseudo400 && ShapeRefusal.matches(status.value, body)
    val mapped = when {
        nativeResponseBody != null && !isPseudo400 -> status
        status.value == 429 -> HttpStatusCode.TooManyRequests
        isPseudo400 -> HttpStatusCode.BadGateway
        status.value in setOf(400, 404, 422) -> status
        else -> HttpStatusCode.BadGateway
    }
    val message = when {
        isPseudo400 -> "上游服务不可用"
        status.value == 401 || status.value == 403 -> "上游凭据无效或无权访问"
        status.value == 429 -> "上游限流"
        status.value >= 500 -> "上游服务不可用"
        else -> "上游返回错误 ${status.value}"
    }
    val retryable = isPseudo400 || shapeRefused || status.value == 429 || status.value >= 500 ||
        status.value == 401 || status.value == 403 || status.value == 404
    return GatewayError(
        status = mapped,
        code = "upstream_error",
        message = message,
        type = "api_error",
        upstreamCode = upstreamCode,
        retryable = retryable,
        retryAfterMs = retryAfterMs.takeIf { retryable },
        upstreamStatus = status.value,
        nativeResponseBody = nativeResponseBody.takeUnless { isPseudo400 },
        upstreamErrorBody = body.take(MAX_KEY_MATCH_BODY_CHARS),
        shapeRefused = shapeRefused,
    )
}

private fun isAnthropicErrorBody(body: String): Boolean = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    val error = root["error"] as? JsonObject ?: return false
    root["type"]?.jsonPrimitive?.contentOrNull == "error" &&
        error["type"]?.jsonPrimitive?.contentOrNull != null &&
        error["message"]?.jsonPrimitive?.contentOrNull != null
}.getOrDefault(false)

private fun retryAfterMillis(value: String?): Long? =
    value
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.coerceAtMost(86_400L)
        ?.times(1_000L)

private suspend fun ByteWriteChannel.writeSseLine(line: String) {
    writeFully((line + "\n").toByteArray(Charsets.UTF_8))
    flush()
}

private fun markFirstToken(
    line: String,
    protocol: GatewayProtocol,
    state: ProxyState,
    startedAt: Long,
    attemptStartedNanos: Long,
    app: GatewayApplication,
    alias: String,
    candidate: RouteCandidate,
    permit: CooldownPermit,
) {
    if (state.firstTokenMs != null) return
    val payload = ssePayload(line) ?: return
    if (payload == "[DONE]") return
    val isFirst = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> true
        GatewayProtocol.RESPONSES -> isFirstResponsesToken(line)
        GatewayProtocol.MESSAGES -> isFirstAnthropicToken(line)
        GatewayProtocol.GEMINI -> isFirstGeminiToken(line)
    }
    if (isFirst) {
        val nowNanos = System.nanoTime()
        state.firstTokenMs = (nowNanos - startedAt) / 1_000_000
        app.metrics.firstToken(alias, candidate.channel.name, state.firstTokenMs!!)
        // 首 token 软阈值按本次尝试计时：快速兜底不应继承前面候选撞掉的秒数。
            val attemptFirstTokenMs = (nowNanos - attemptStartedNanos) / 1_000_000
            state.attemptFirstTokenMs = attemptFirstTokenMs
            if (app.candidateCooldown.recordFirstToken(
                permit,
                attemptFirstTokenMs,
            )
        ) {
            app.metrics.upstreamCooldownStarted(
                candidate.channel.name,
                CandidateCooldown.REASON_TTFT_SLOW,
            )
        }
    }
}

private fun recordUsage(line: String, state: ProxyState, protocol: GatewayProtocol) {
    val payload = ssePayload(line) ?: return
    if (payload == "[DONE]") return
    val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
    val usage = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> UsageParser.fromJson(json)
        GatewayProtocol.RESPONSES -> UsageParser.fromResponsesJson(json)
        GatewayProtocol.MESSAGES -> UsageParser.fromAnthropicJson(json)
        GatewayProtocol.GEMINI -> UsageParser.fromGeminiJson(json)
    }
    if (usage != null) state.usage = state.usage?.mergeWith(usage, protocol) ?: usage
}

private fun recordReportedModel(line: String, state: ProxyState, protocol: GatewayProtocol) {
    val payload = ssePayload(line) ?: return
    if (payload == "[DONE]") return
    // Metadata is best effort; do not catch cancellation or transport failures.
    val json = try {
        Json.parseToJsonElement(payload) as? JsonObject ?: return
    } catch (_: kotlinx.serialization.SerializationException) {
        return
    }
    extractReportedModel(protocol, json)?.let { state.upstreamReportedModel = it }
}

private fun isFirstStreamData(line: String, protocol: GatewayProtocol): Boolean = when (protocol) {
    GatewayProtocol.CHAT_COMPLETIONS -> true
    GatewayProtocol.RESPONSES -> isFirstResponsesToken(line)
    GatewayProtocol.MESSAGES -> isFirstAnthropicToken(line) || isStreamDone(line, protocol)
    GatewayProtocol.GEMINI -> isFirstGeminiToken(line) || isStreamDone(line, protocol)
}

private fun isFirstAnthropicToken(line: String): Boolean {
    val payload = ssePayload(line) ?: return false
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return type == "content_block_start" || type == "content_block_delta"
}

private fun isAnthropicStreamError(line: String): Boolean {
    val payload = ssePayload(line) ?: return false
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return type == "error"
}

private fun anthropicStreamException(line: String): GatewayError {
    val payload = ssePayload(line).orEmpty()
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["error"]
            ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    val status = when (type) {
        "invalid_request_error" -> HttpStatusCode.BadRequest
        "authentication_error" -> HttpStatusCode.Unauthorized
        "permission_error" -> HttpStatusCode.Forbidden
        "not_found_error" -> HttpStatusCode.NotFound
        "rate_limit_error" -> HttpStatusCode.TooManyRequests
        "overloaded_error" -> HttpStatusCode(529, "Overloaded")
        else -> HttpStatusCode.InternalServerError
    }
    return upstreamException(status, payload, retryAfterMs = null, protocol = GatewayProtocol.MESSAGES)
}

private fun isFirstResponsesToken(line: String): Boolean {
    val payload = ssePayload(line) ?: return false
    if (payload == "[DONE]") return false
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return type in RESPONSES_END_EVENTS || type == "error" || type?.endsWith(".delta") == true
}

private fun markTerminalStreamFailure(
    line: String,
    protocol: GatewayProtocol,
    state: ProxyState,
) {
    val payload = ssePayload(line) ?: return
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> Unit
        GatewayProtocol.RESPONSES -> when (type) {
            "response.failed", "error" -> {
                state.errorCode = "responses_failed"
                state.upstreamErrorBody = extractResponsesDiagnosticPayload(payload)
            }
            "response.incomplete" -> {
                state.errorCode = "responses_incomplete"
                state.upstreamErrorBody = extractResponsesDiagnosticPayload(payload)
            }
        }
        GatewayProtocol.MESSAGES -> if (type == "error") {
            state.errorCode = "messages_failed"
            state.upstreamErrorBody = payload
            state.streamError = anthropicStreamException(line)
        }
        GatewayProtocol.GEMINI -> if (isGeminiStreamError(line)) {
            state.errorCode = "gemini_failed"
            state.upstreamErrorBody = payload
            state.streamError = geminiStreamException(line)
        }
    }
}

private fun extractResponsesDiagnosticPayload(payload: String): String {
    return runCatching {
        val root = Json.parseToJsonElement(payload).jsonObject
        val response = root["response"] as? JsonObject
        if (response != null && "output" in response) {
            val sanitizedResponse = JsonObject(response.filterKeys { it != "output" })
            val sanitizedRoot = JsonObject(root.toMutableMap().apply { put("response", sanitizedResponse) })
            Json.encodeToString(JsonObject.serializer(), sanitizedRoot)
        } else {
            payload
        }
    }.getOrDefault(payload)
}

private fun isStreamDone(line: String, protocol: GatewayProtocol): Boolean {
    val payload = ssePayload(line) ?: return false
    if (payload == "[DONE]") return true
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> false
        GatewayProtocol.RESPONSES -> type in RESPONSES_END_EVENTS || type == "error"
        GatewayProtocol.MESSAGES -> type == "message_stop" || type == "error"
        GatewayProtocol.GEMINI -> isGeminiStreamTerminal(line)
    }
}

private fun Usage.mergeWith(next: Usage, protocol: GatewayProtocol): Usage {
    val mergedInput = next.input ?: input
    val mergedOutput = next.output ?: output
    val total = next.total
        ?: if (protocol == GatewayProtocol.MESSAGES && (next.input != null || next.output != null)) {
            if (mergedInput != null && mergedOutput != null) mergedInput + mergedOutput else this.total
        } else {
            this.total
        }
    return Usage(
        input = mergedInput,
        output = mergedOutput,
        cached = next.cached ?: cached,
        cacheWrite = next.cacheWrite ?: cacheWrite,
        reasoning = next.reasoning ?: reasoning,
        total = total,
    )
}

private fun ssePayload(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("data:")) return null
    return trimmed.removePrefix("data:").trim().takeIf { it.isNotEmpty() }
}

private val RESPONSES_END_EVENTS = setOf("response.completed", "response.failed", "response.incomplete")

private fun geminiSseJson(line: String): JsonObject? = ssePayload(line)?.let {
    runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
}

private fun isFirstGeminiToken(line: String): Boolean =
    (geminiSseJson(line)?.get("candidates") as? JsonArray)?.any { candidate ->
        val content = (candidate as? JsonObject)?.get("content") as? JsonObject
        (content?.get("parts") as? JsonArray)?.isNotEmpty() == true
    } == true

private fun isGeminiStreamError(line: String): Boolean = geminiSseJson(line)?.get("error") is JsonObject

private fun isGeminiStreamTerminal(line: String): Boolean {
    val json = geminiSseJson(line) ?: return false
    if (json["error"] is JsonObject) return true
    if ((json["promptFeedback"] as? JsonObject)?.get("blockReason") is JsonPrimitive) return true
    val candidates = json["candidates"] as? JsonArray ?: return false
    return candidates.isNotEmpty() && candidates.all {
        ((it as? JsonObject)?.get("finishReason") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
    }
}

private fun isGeminiErrorBody(body: String): Boolean = runCatching {
    val error = (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
    (error?.get("code") as? JsonPrimitive)?.intOrNull != null && error["message"] is JsonPrimitive
}.getOrDefault(false)

private fun geminiStreamException(line: String): GatewayError {
    val status = ((geminiSseJson(line)?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)
        ?.intOrNull?.takeIf { it in 400..599 } ?: 500
    return upstreamException(HttpStatusCode.fromValue(status), ssePayload(line).orEmpty(), null, GatewayProtocol.GEMINI)
}
