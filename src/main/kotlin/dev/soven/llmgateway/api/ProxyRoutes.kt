package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.domain.UpstreamAttemptRecord
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.authTypeFor
import dev.soven.llmgateway.proxy.UpstreamRequestPolicy
import dev.soven.llmgateway.proxy.upstreamRequestUrl
import dev.soven.llmgateway.proxy.BillingCalculator
import dev.soven.llmgateway.quota.ChannelKeyRateLimitDecision
import dev.soven.llmgateway.quota.ChannelKeyRateLimitLease
import dev.soven.llmgateway.quota.ChannelKeyRateLimitReason
import dev.soven.llmgateway.quota.RequestLimitLease
import dev.soven.llmgateway.quota.quotaTokensFromUsage
import dev.soven.llmgateway.proxy.ReasoningEffort
import dev.soven.llmgateway.proxy.TraceContextFactory
import dev.soven.llmgateway.proxy.applyUpstreamAuthentication
import dev.soven.llmgateway.proxy.Usage
import dev.soven.llmgateway.proxy.UsageParser
import dev.soven.llmgateway.proxy.extractReportedModel
import dev.soven.llmgateway.proxy.protocol.MAX_PROTOCOL_EVENT_BYTES
import dev.soven.llmgateway.proxy.protocol.ProtocolConversion
import dev.soven.llmgateway.proxy.protocol.ProtocolConversionException
import dev.soven.llmgateway.proxy.protocol.asChatUpstream
import dev.soven.llmgateway.repo.KeyAutoDisableRepository
import dev.soven.llmgateway.repo.KeyAutoDisablePendingException
import dev.soven.llmgateway.resilience.CandidateCooldown
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.CooldownPermit
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.resilience.KeyAttemptSignal
import dev.soven.llmgateway.security.ClientKeyHasher
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
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

private val proxyLogger = LoggerFactory.getLogger("dev.soven.llmgateway.api.ProxyRoutes")

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
                                    put("owned_by", "llm-gateway")
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
    val attemptRecords = mutableListOf<UpstreamAttemptRecord>()
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
        val allCandidates = app.modelCatalog.findCandidates(alias, null)
        if (allCandidates.isEmpty()) {
            throw notFound("model_not_found", "模型 $alias 不存在或没有可用渠道")
        }
        val nativeCandidates = allCandidates.filter { protocol in it.channel.protocols }
        val conversionCandidates = allCandidates.filter { candidate ->
            protocol !in candidate.channel.protocols
        }
        val candidates = nativeCandidates + conversionCandidates
        if (candidates.isEmpty()) throw protocolNotSupported(alias, protocol)
        val globalOverrides = app.upstreamRequestSettingsRepository.load().overrides
        val headerProfiles = app.upstreamHeaderCatalogService.profiles()

        phase = FailureStage.UPSTREAM
        // 先按冷却分拣：可尝试的候选进同档轮询，冷却中的保序作为 fail-open 兜底，
        // 轮询份额只在可尝试候选间分摊，不被冷却候选扭曲。
        val eligibleNative = mutableListOf<RouteCandidate>()
        val eligibleConversion = mutableListOf<RouteCandidate>()
        val blocked = mutableListOf<RouteCandidate>()
        for (candidate in candidates) {
            if (app.candidateCooldown.isBlocked(candidate.channel.id, candidate.key.id, candidate.upstreamModel)) {
                app.metrics.upstreamCooldownSkipped(candidate.channel.name)
                blocked.add(candidate)
            } else if (protocol in candidate.channel.protocols) {
                eligibleNative.add(candidate)
            } else {
                eligibleConversion.add(candidate)
            }
        }
        // 每个 scope 每请求只推进一次轮询计数，避免同档两渠道时起点固定。
        val ordered = app.tierSharing.order(alias, eligibleNative) +
            app.tierSharing.order("$alias|conversion", eligibleConversion)
        var lastError: GatewayException? = null
        var conversionError: ProtocolConversionException? = null
        var retryFrom: String? = null
        var retryReason: String? = null
        var rateLimitRetryAfterMs: Long? = null
        var rateLimitReason: ChannelKeyRateLimitReason? = null
        val rateLimitedCandidates = mutableSetOf<RouteCandidate>()
        fun rememberRateLimitDenial(candidate: RouteCandidate, decision: ChannelKeyRateLimitDecision.Denied) {
            rateLimitedCandidates += candidate
            val current = rateLimitRetryAfterMs
            if (current == null || decision.retryAfterMs < current) {
                rateLimitRetryAfterMs = decision.retryAfterMs
                rateLimitReason = decision.reason
            }
        }

        /**
         * 尝试单个候选并把结果记入冷却。返回 null 表示响应已完成，返回异常交由外层决定是否继续。
         */
        suspend fun runAttempt(
            candidate: RouteCandidate,
            permit: CooldownPermit,
            rateLimitLease: ChannelKeyRateLimitLease?,
        ): GatewayException? = try {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                app.candidateCooldown.release(permit)
                throw GatewayException(
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
            channelKeyId = candidate.key.id
            channelKeyName = candidate.key.name
            upstreamModel = candidate.upstreamModel
            val attemptProtocol = if (protocol in candidate.channel.protocols) {
                protocol
            } else {
                candidate.channel.protocols.firstOrNull()
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
            fun recordAttempt(status: Int?, error: GatewayException?, committed: Boolean, errorCode: String? = null) {
                if (attemptRecorded) return
                attemptRecorded = true
                if (!state.upstreamCallStarted) {
                    upstreamAttempts -= 1
                    return
                }
                upstreamProtocol = attemptProtocol
                val upstreamError = state.streamError ?: error
                attemptRecords += UpstreamAttemptRecord(
                    index = attemptRecords.size + 1,
                    channelId = candidate.channel.id,
                    channelName = candidate.channel.name,
                    channelKeyId = candidate.key.id,
                    channelKeyName = candidate.key.name,
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
                app.keyAvailabilityGate.withReader(candidate.key.id) {
                    if (!app.keyAutoDisableRepository.isAvailable(candidate.key)) {
                        throw CandidateUnavailableException()
                    }
                    // 到期临时密钥先原子抢探测租约；抢不到说明已有并发探测，直接换候选。
                    if (candidate.key.state == ChannelKeyState.AUTO_TEMPORARY) {
                        val leaseMs = maxOf(
                            KeyAutoDisableRepository.PROBE_LEASE_MS,
                            app.config.firstTokenTimeoutMs + 5_000,
                            app.config.totalTimeoutMs + 5_000,
                        )
                        // 请求转换在抢租约前完成，避免转换失败留下未释放的探测租约。
                        ProtocolConversion.convertRequest(
                            protocol,
                            attemptProtocol ?: protocol,
                            requestJson,
                            candidate.upstreamModel,
                        )
                        probeClaimed = app.keyAutoDisableRepository.claimProbe(candidate.key, leaseMs = leaseMs)
                        if (!probeClaimed) throw CandidateUnavailableException()
                    }
                    if (stream) {
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
                        )
                    } else {
                        proxyJson(
                            app, call, requestJson, alias, candidate, remainingMs, state, protocol,
                            attemptProtocol ?: protocol, traceContext.traceparent, globalOverrides, headerProfiles,
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
                GatewayException(
                    HttpStatusCode.BadGateway, "no_candidate", "密钥已停用，请尝试其他候选", "api_error",
                    retryable = true,
                )
            } catch (e: GatewayException) {
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
                                    candidate.key.name,
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
                        GatewayException(
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
                    return GatewayException(
                        HttpStatusCode.BadGateway,
                        "no_candidate",
                        "协议转换不支持，尝试其他候选",
                        "api_error",
                        retryable = true,
                    )
                }
                conversionError = e
                GatewayException(
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
                    GatewayException(HttpStatusCode.InternalServerError, "internal_error", e.message ?: e::class.simpleName.toString()),
                    state.committed,
                )
                throw e
            }
        } finally {
            app.channelKeyRateLimiter.release(rateLimitLease)
        }

        var index = 0
        while (index < ordered.size && upstreamAttempts < app.config.maxAttempts) {
            val candidate = ordered[index]
            val permit = app.candidateCooldown.acquire(candidate.channel.id, candidate.key.id, candidate.upstreamModel)
            if (permit == null) {
                app.metrics.upstreamCooldownSkipped(candidate.channel.name)
                blocked.add(candidate)
                index++
                continue
            }
            index++
            val rateDecision = app.channelKeyRateLimiter.acquire(candidate.key)
            if (rateDecision is ChannelKeyRateLimitDecision.Denied) {
                app.candidateCooldown.release(permit)
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, rateDecision.reason.metricValue)
                rememberRateLimitDenial(candidate, rateDecision)
                continue
            }
            val error = runAttempt(candidate, permit, (rateDecision as ChannelKeyRateLimitDecision.Allowed).lease)
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
            val permit = app.candidateCooldown.forceAcquire(candidate.channel.id, candidate.key.id, candidate.upstreamModel)
            val rateDecision = app.channelKeyRateLimiter.acquire(candidate.key)
            if (rateDecision is ChannelKeyRateLimitDecision.Denied) {
                app.candidateCooldown.release(permit)
                app.metrics.upstreamKeyRateLimitSkipped(candidate.channel.name, rateDecision.reason.metricValue)
                rememberRateLimitDenial(candidate, rateDecision)
                continue
            }
            val error = runAttempt(candidate, permit, (rateDecision as ChannelKeyRateLimitDecision.Allowed).lease)
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
            throw GatewayException(
                HttpStatusCode.TooManyRequests,
                "upstream_key_rate_limit_exceeded",
                when (rateLimitReason) {
                    ChannelKeyRateLimitReason.CONCURRENCY -> "上游密钥分组并发请求数已达上限"
                    else -> "上游密钥分组每分钟请求数已达上限"
                },
                "rate_limit_error",
                retryAfterMs = rateLimitRetryAfterMs,
            )
        }
        throw lastError ?: conversionError?.let { e ->
            badRequest("protocol_conversion_unsupported", "${e.field}: ${e.message}")
        } ?: GatewayException(
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
        throw GatewayException(HttpStatusCode.GatewayTimeout, "upstream_timeout", "上游响应超时", "timeout_error")
    } catch (e: GatewayException) {
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
                attempts = attemptRecords.toList(),
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
    currentCoroutineContext().ensureActive()
    try {
        val reason = try {
            app.keyAutoDisableRepository.recordOutcome(
                candidate.key, signal, requestId, allowDisable = false, probeClaimed = probeClaimed,
            )
        } catch (_: KeyAutoDisablePendingException) {
            app.keyAvailabilityGate.withWriter(candidate.key.id) {
                app.keyAutoDisableRepository.recordOutcome(
                    candidate.key, signal, requestId, allowDisable = true, probeClaimed = probeClaimed,
                )
            }
        }
        reason?.let { reason ->
            app.metrics.keyAutoDisabled(candidate.channel.name, reason)
            proxyLogger.warn(
                "上游密钥自动禁用，channelId={}, keyId={}, reason={}, requestId={}",
                candidate.channel.id, candidate.key.id, reason, requestId,
            )
        }
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        app.metrics.keyAutoDisableWriteFailed()
        proxyLogger.error(
            "密钥自动禁用统计或审计写入失败，keyId={}, requestId={}, exceptionType={}",
            candidate.key.id, requestId, cause.javaClass.simpleName,
        )
    }
}

/** 把上游异常映射成规则引擎信号；请求侧错误（400/404/422）交给规则自行决定是否计数。 */
private fun GatewayException.toKeyAttemptSignal(): KeyAttemptSignal? = when {
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

private class CandidateUnavailableException : RuntimeException(null, null, false, false)

/**
 * 把上游异常映射为冷却分类的粗分类；null 表示不参与冷却记账（400/422 等请求侧错误）。
 */
private fun classifyFailure(e: GatewayException): FailureKind? = when (e.code) {
    "upstream_unreachable", "upstream_empty_stream", "upstream_timeout", "stream_interrupted" -> FailureKind.TRANSPORT
    "upstream_error" -> when (e.upstreamStatus) {
        429 -> FailureKind.RATE_LIMITED
        401, 403 -> FailureKind.AUTH_REJECTED
        404 -> FailureKind.MODEL_NOT_FOUND
        null -> null
        else -> if (e.upstreamStatus >= 500) FailureKind.SERVER_ERROR else null
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
        calculatedCost?.let { mapOf(it.currency to it.amount) } ?: emptyMap(),
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
    var streamError: GatewayException? = null
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
    if (record.expiresAt != null && record.expiresAt <= System.currentTimeMillis()) {
        throw unauthorized("调用方密钥已过期")
    }
    return record
}

private suspend fun ApplicationCall.receiveLimitedJson(limit: Long): JsonObject {
    val declared = request.contentLength()
    if (declared != null && declared > limit) {
        throw GatewayException(
            HttpStatusCode.PayloadTooLarge,
            "request_too_large",
            "请求体超过 $limit 字节",
            "invalid_request_error",
        )
    }
    val bytes = request.receiveChannel().readRemaining(limit + 1).readBytes()
    if (bytes.size > limit) {
        throw GatewayException(
            HttpStatusCode.PayloadTooLarge,
            "request_too_large",
            "请求体超过 $limit 字节",
            "invalid_request_error",
        )
    }
    val element = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    return element as? JsonObject ?: throw badRequest("invalid_json", "请求体必须是 JSON 对象")
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
) {
    val upstreamUrl = upstreamRequestUrl(candidate.channel.baseUrl, upstreamProtocol, candidate.upstreamModel, stream = true)
    val credential = decryptCredential(app, candidate)
    val authType = candidate.channel.authTypeFor(upstreamProtocol)
    val policy = UpstreamRequestPolicy(
        globalOverrides, candidate.channel.requestOverrides, upstreamProtocol, authType,
        headerProfiles,
    )
    val convertedRequest = if (protocol == upstreamProtocol) {
        requestJson
    } else {
        ProtocolConversion.convertRequest(protocol, upstreamProtocol, requestJson, candidate.upstreamModel)
    }
    val streamRequest = withUpstreamStreamFlag(convertedRequest, upstreamProtocol, stream = true)
    val usageRequest = if (upstreamProtocol == GatewayProtocol.CHAT_COMPLETIONS && protocol != upstreamProtocol) {
        streamRequest.asChatUpstream(candidate.upstreamModel)
    } else {
        streamRequest
    }
    val body = policy.body(usageRequest, candidate.upstreamModel, app.config.requestBodyLimitBytes).toString()
    val converter = ProtocolConversion.streamConverter(protocol, upstreamProtocol, alias)

    try {
        withTimeout(timeoutMs) {
            state.upstreamCallStarted = true
            app.httpClient.preparePost(upstreamUrl) {
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
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                state.upstreamStatus = response.status.value
                if (!response.status.isSuccess()) {
                    throw upstreamException(
                        response.status,
                        response.bodyAsText(),
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
                            val next = readProtocolLine(upstream, maxLineBytes) ?: break
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
                    throw GatewayException(
                        HttpStatusCode.GatewayTimeout,
                        "upstream_timeout",
                        "上游首 token 超时",
                        "timeout_error",
                        retryable = true,
                    )
                } ?: throw GatewayException(
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
                            var streamFailure: GatewayException? = null
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
                                        withTimeout(app.config.idleTimeoutMs) { readProtocolLine(upstream, maxLineBytes) }
                                    } catch (e: TimeoutCancellationException) {
                                        throw GatewayException(
                                            HttpStatusCode.GatewayTimeout,
                                            "upstream_timeout",
                                            "上游流式响应空闲超时",
                                            "timeout_error",
                                        )
                                    } catch (e: GatewayException) {
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
        throw GatewayException(
            HttpStatusCode.GatewayTimeout,
            "upstream_timeout",
            "上游响应超时",
            "timeout_error",
            retryable = !state.committed,
        )
    } catch (e: ProtocolConversionException) {
        throw e
    } catch (e: GatewayException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw transportFailure(state.committed)
    }
}

internal suspend fun readProtocolLine(upstream: ByteReadChannel, maxLineBytes: Int): String? = try {
    upstream.readUTF8Line(max = maxLineBytes)
} catch (_: TooLongLineException) {
    throw GatewayException(
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
) {
    val upstreamUrl = upstreamRequestUrl(candidate.channel.baseUrl, upstreamProtocol, candidate.upstreamModel)
    val credential = decryptCredential(app, candidate)
    val authType = candidate.channel.authTypeFor(upstreamProtocol)
    val policy = UpstreamRequestPolicy(
        globalOverrides, candidate.channel.requestOverrides, upstreamProtocol, authType,
        headerProfiles,
    )
    val convertedRequest = if (protocol == upstreamProtocol) {
        requestJson
    } else {
        ProtocolConversion.convertRequest(protocol, upstreamProtocol, requestJson, candidate.upstreamModel)
    }
    val nonStreamRequest = withUpstreamStreamFlag(convertedRequest, upstreamProtocol, stream = false)
    val body = policy.body(nonStreamRequest, candidate.upstreamModel, app.config.requestBodyLimitBytes).toString()
    try {
        withTimeout(timeoutMs) {
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
                setBody(TextContent(body, ContentType.Application.Json))
            }
            state.upstreamStatus = response.status.value
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw upstreamException(
                    response.status,
                    text,
                    retryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                    upstreamProtocol,
                    allowNativeMessagesError = protocol == upstreamProtocol,
                )
            }
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
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
                text
            } else {
                val parsed = json ?: throw transportFailure(false)
                ProtocolConversion.convertResponse(protocol, upstreamProtocol, parsed, model = alias).toString()
            }
            state.committed = true
            state.statusCode = response.status.value
            call.respondText(clientBody, ContentType.Application.Json, response.status)
        }
    } catch (e: ChannelWriteException) {
        throw clientDisconnected()
    } catch (e: TimeoutCancellationException) {
        throw GatewayException(
            HttpStatusCode.GatewayTimeout,
            "upstream_timeout",
            "上游响应超时",
            "timeout_error",
            retryable = !state.committed,
        )
    } catch (e: ProtocolConversionException) {
        throw e
    } catch (e: GatewayException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw transportFailure(state.committed)
    }
}

private fun protocolNotSupported(alias: String, protocol: GatewayProtocol): GatewayException =
    GatewayException(
        HttpStatusCode.BadRequest,
        "protocol_not_supported",
        "模型别名 $alias 没有支持 ${protocol.value} 的渠道",
        "invalid_request_error",
    )

private fun decryptCredential(app: GatewayApplication, candidate: RouteCandidate): String = runCatching {
    app.credentialCipher.decrypt(candidate.key.credentialCiphertext, candidate.key.credentialNonce)
}.getOrElse {
    throw GatewayException(
        HttpStatusCode.BadGateway,
        "upstream_credential_invalid",
        "渠道凭据无法解密",
        retryable = false,
    )
}

private fun transportFailure(committed: Boolean): GatewayException =
    GatewayException(
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

private fun clientDisconnected(): GatewayException =
    GatewayException(
        HttpStatusCode(499, "Client Closed Request"),
        "client_disconnect",
        "客户端连接中断",
        "api_error",
    )

private const val MAX_KEY_MATCH_BODY_CHARS = 8_192

private fun upstreamException(
    status: HttpStatusCode,
    body: String,
    retryAfterMs: Long?,
    protocol: GatewayProtocol,
    allowNativeMessagesError: Boolean = true,
): GatewayException {
    val nativeResponseBody = body.takeIf {
        (allowNativeMessagesError && protocol == GatewayProtocol.MESSAGES && isAnthropicErrorBody(it)) ||
            (protocol == GatewayProtocol.GEMINI && isGeminiErrorBody(it))
    }
    val upstreamCode = runCatching {
        val error = Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
        (error?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (error?.get("type") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
    val mapped = when {
        nativeResponseBody != null -> status
        status.value == 429 -> HttpStatusCode.TooManyRequests
        status.value in setOf(400, 404, 422) -> status
        else -> HttpStatusCode.BadGateway
    }
    val message = when {
        status.value == 401 || status.value == 403 -> "上游凭据无效或无权访问"
        status.value == 429 -> "上游限流"
        status.value >= 500 -> "上游服务不可用"
        else -> "上游返回错误 ${status.value}"
    }
    val retryable = status.value == 429 || status.value >= 500 || status.value == 401 || status.value == 403 || status.value == 404
    return GatewayException(
        status = mapped,
        code = "upstream_error",
        message = message,
        type = "api_error",
        upstreamCode = upstreamCode,
        retryable = retryable,
        retryAfterMs = retryAfterMs.takeIf { retryable },
        upstreamStatus = status.value,
        nativeResponseBody = nativeResponseBody,
        upstreamErrorBody = body.take(MAX_KEY_MATCH_BODY_CHARS),
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

private fun anthropicStreamException(line: String): GatewayException {
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

private fun geminiStreamException(line: String): GatewayException {
    val status = ((geminiSseJson(line)?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)
        ?.intOrNull?.takeIf { it in 400..599 } ?: 500
    return upstreamException(HttpStatusCode.fromValue(status), ssePayload(line).orEmpty(), null, GatewayProtocol.GEMINI)
}
