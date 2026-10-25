package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.proxy.BillingCalculator
import dev.soven.llmgateway.quota.RequestLimitLease
import dev.soven.llmgateway.quota.quotaTokensFromUsage
import dev.soven.llmgateway.proxy.ReasoningEffort
import dev.soven.llmgateway.proxy.TraceContextFactory
import dev.soven.llmgateway.proxy.applyUpstreamAuthentication
import dev.soven.llmgateway.proxy.Usage
import dev.soven.llmgateway.proxy.UsageParser
import dev.soven.llmgateway.resilience.CandidateCooldown
import dev.soven.llmgateway.resilience.CooldownPermit
import dev.soven.llmgateway.resilience.FailureKind
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
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val models = listExposedModels(app)
        call.respond(
            buildJsonObject {
                put("object", "list")
                put(
                    "data",
                    buildJsonArray {
                        models.forEach { model ->
                            add(
                                buildJsonObject {
                                    put("id", model.id)
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
        post("/v1${protocol.path}") {
            handleProxy(app, call, protocol)
        }
    }
}

private suspend fun handleProxy(
    app: GatewayApplication,
    call: ApplicationCall,
    protocol: GatewayProtocol,
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
    var upstreamModel: String? = null
    var streamMode: Boolean? = null
    var reasoningEffort: String? = null
    var statusCode = HttpStatusCode.InternalServerError.value
    var errorCode: String? = null
    var failureStage: FailureStage? = null
    var errorMessage: String? = null
    var upstreamErrorCode: String? = null
    var upstreamAttempts = 0
    var phase = FailureStage.REQUEST

    try {
        clientKey = authenticate(call, app, protocol)
        requestLimitLease = app.requestLimitService.acquire(clientKey)
        val requestJson = call.receiveLimitedJson(app.config.requestBodyLimitBytes)
        reasoningEffort = ReasoningEffort.fromRequest(requestJson, protocol)
        val modelElement = requestJson["model"] as? JsonPrimitive
        alias = modelElement
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw badRequest("invalid_model", "model 必须是非空字符串")
        val streamElement = requestJson["stream"]
        val stream = when (streamElement) {
            null -> false
            is JsonPrimitive -> {
                if (streamElement.isString) throw badRequest("invalid_stream", "stream 必须是布尔值")
                streamElement.booleanOrNull ?: throw badRequest("invalid_stream", "stream 必须是布尔值")
            }
            else -> throw badRequest("invalid_stream", "stream 必须是布尔值")
        }
        streamMode = stream
        phase = FailureStage.ROUTING
        val aliasCandidates = app.aliasRepository.findEnabledCandidates(alias)
        var candidates = aliasCandidates.filter { protocol in it.channel.protocols }
        if (aliasCandidates.isNotEmpty() && candidates.isEmpty()) {
            throw protocolNotSupported(alias, protocol)
        }
        if (aliasCandidates.isEmpty() && app.config.exposeUpstreamModels && app.aliasRepository.findByAlias(alias) == null) {
            candidates = app.modelCatalog.findCandidates(alias, protocol)
            if (candidates.isEmpty() && app.modelCatalog.findCandidates(alias, null).isNotEmpty()) {
                throw protocolNotSupported(alias, protocol)
            }
        }
        if (candidates.isEmpty()) {
            throw notFound("model_not_found", "模型别名 $alias 不存在或没有可用渠道")
        }

        phase = FailureStage.UPSTREAM
        // 先按冷却分拣：可尝试的候选进同档轮询，冷却中的保序作为 fail-open 兜底，
        // 轮询份额只在可尝试候选间分摊，不被冷却候选扭曲。
        val eligible = mutableListOf<RouteCandidate>()
        val blocked = mutableListOf<RouteCandidate>()
        for (candidate in candidates) {
            if (app.candidateCooldown.isBlocked(candidate.channel.id, candidate.upstreamModel)) {
                app.metrics.upstreamCooldownSkipped(candidate.channel.name)
                blocked.add(candidate)
            } else {
                eligible.add(candidate)
            }
        }
        val ordered = app.tierSharing.order(alias, eligible)
        var lastError: GatewayException? = null
        var retryFrom: String? = null
        var retryReason: String? = null

        /**
         * 尝试单个候选并把结果记入冷却。返回 null 表示响应已完成，返回异常交由外层决定是否继续。
         */
        suspend fun runAttempt(candidate: RouteCandidate, permit: CooldownPermit): GatewayException? {
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
            upstreamAttempts += 1
            channelId = candidate.channel.id
            channelName = candidate.channel.name
            upstreamModel = candidate.upstreamModel
            retryFrom?.let { from ->
                app.metrics.upstreamRetry(from, candidate.channel.name, retryReason ?: "upstream_error")
                retryFrom = null
                retryReason = null
            }
            return try {
                if (stream) {
                    proxyStream(
                        app,
                        call,
                        requestJson,
                        candidate,
                        alias,
                        startedAt,
                        attemptStartedNanos,
                        remainingMs,
                        state,
                        protocol,
                        traceContext.traceparent,
                        allowUpstreamErrorFailover = upstreamAttempts < app.config.maxAttempts && upstreamAttempts < candidates.size,
                    )
                } else {
                    proxyJson(app, call, requestJson, candidate, remainingMs, state, protocol, traceContext.traceparent)
                }
                statusCode = state.statusCode
                app.metrics.upstreamAttempt(
                    candidate.channel.name,
                    state.statusCode < 400 && state.errorCode == null,
                )
                if (state.errorCode == null) {
                    app.candidateCooldown.recordSuccess(permit)
                } else {
                    app.candidateCooldown
                        .recordFailure(permit, FailureKind.TRANSPORT)
                        ?.let { app.metrics.upstreamCooldownStarted(candidate.channel.name, it.reason) }
                }
                null
            } catch (e: GatewayException) {
                if (e.code == "client_disconnect") {
                    app.candidateCooldown.release(permit)
                    throw e
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
                                    "上游凭据被拒绝，渠道 {} 模型 {} 进入长冷却（{}ms），请检查渠道配置",
                                    candidate.channel.name,
                                    candidate.upstreamModel,
                                    app.config.cooldown.authFailureMs,
                                )
                            }
                        }
                }
                e
            } catch (e: Throwable) {
                app.candidateCooldown.release(permit)
                throw e
            }
        }

        var index = 0
        while (index < ordered.size && upstreamAttempts < app.config.maxAttempts) {
            val candidate = ordered[index]
            val permit = app.candidateCooldown.acquire(candidate.channel.id, candidate.upstreamModel)
            if (permit == null) {
                app.metrics.upstreamCooldownSkipped(candidate.channel.name)
                blocked.add(candidate)
                index++
                continue
            }
            index++
            val error = runAttempt(candidate, permit)
            if (error == null) return
            lastError = error
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
            val permit = app.candidateCooldown.forceAcquire(candidate.channel.id, candidate.upstreamModel)
            val error = runAttempt(candidate, permit)
            if (error == null) return
            lastError = error
            if (!error.retryable || state.committed || upstreamAttempts >= app.config.maxAttempts) throw error
            retryFrom = candidate.channel.name
            retryReason = error.code
        }
        throw lastError ?: GatewayException(
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
        throw e
    } catch (e: CancellationException) {
        statusCode = if (state.committed) HttpStatusCode.OK.value else 499
        errorCode = "client_cancelled"
        failureStage = FailureStage.CLIENT
        errorMessage = "客户端取消请求"
        throw e
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
                else -> "上游流未正常结束（缺少结束帧）"
            }
        }
        val usage = state.usage
        settleRequestLimitOnExit(
            app = app,
            lease = requestLimitLease,
            usage = usage,
            channelId = channelId,
            upstreamModel = upstreamModel,
            requestId = requestId,
            traceId = traceContext.traceId,
            alias = alias,
            channelName = channelName,
            protocol = protocol,
            statusCode = statusCode,
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
                team = clientKey?.team,
                alias = alias,
                channelId = channelId,
                channelName = channelName,
                upstreamModel = upstreamModel,
                protocol = protocol,
                stream = streamMode,
                statusCode = statusCode,
                firstTokenMs = state.firstTokenMs,
                totalMs = totalMs,
                inputTokens = usage?.input,
                outputTokens = usage?.output,
                cachedTokens = usage?.cached,
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
                createdAt = System.currentTimeMillis(),
                upstreamAttempts = upstreamAttempts,
            ),
        )
    }
}

private fun String?.asLogMessage(): String? = this?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }

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
    "team_rate_limit_exceeded",
    "team_concurrency_limit_exceeded",
)

private suspend fun settleRequestLimitOnExit(
    app: GatewayApplication,
    lease: RequestLimitLease?,
    usage: Usage?,
    channelId: String?,
    upstreamModel: String?,
    requestId: String,
    traceId: String?,
    alias: String,
    channelName: String?,
    protocol: GatewayProtocol,
    statusCode: Int,
    firstTokenMs: Long?,
    reasoningEffort: String?,
) {
    if (lease == null) return
    val calculatedCost = try {
        if (usage?.known == true && channelId != null && upstreamModel != null) {
            try {
                app.modelPriceRepository.find(channelId, upstreamModel)?.let { price ->
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
                            protocol = protocol,
                            statusCode = statusCode,
                            firstTokenMs = firstTokenMs,
                            totalMs = 0,
                            inputTokens = usage.input,
                            outputTokens = usage.output,
                            cachedTokens = usage.cached,
                            reasoningTokens = usage.reasoning,
                            reasoningEffort = reasoningEffort,
                            totalTokens = usage.total,
                            usageKnown = usage.known,
                            costAmount = null,
                            costCurrency = null,
                            inputPricePerMillion = null,
                            outputPricePerMillion = null,
                            cachedInputPricePerMillion = null,
                            errorCode = null,
                            failureStage = null,
                            errorMessage = null,
                            upstreamErrorCode = null,
                            createdAt = System.currentTimeMillis(),
                        ),
                        price,
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
    var errorCode: String? = null
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
    alias: String,
    startedAt: Long,
    attemptStartedNanos: Long,
    timeoutMs: Long,
    state: ProxyState,
    protocol: GatewayProtocol,
    traceparent: String,
    allowUpstreamErrorFailover: Boolean,
) {
    val upstreamUrl = buildUpstreamUrl(candidate, protocol)
    val credential = decryptCredential(app, candidate)
    val body = requestJson.withModel(candidate.upstreamModel).toString()

    try {
        withTimeout(timeoutMs) {
            app.httpClient.preparePost(upstreamUrl) {
                applyUpstreamAuthentication(
                    credential = credential,
                    authType = candidate.channel.authType,
                    protocol = protocol,
                    anthropicVersion = call.request.headers["anthropic-version"],
                    anthropicBeta = call.request.headers["anthropic-beta"],
                )
                header("traceparent", traceparent)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw upstreamException(
                        response.status,
                        response.bodyAsText(),
                        retryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                        protocol,
                    )
                }
                val upstream = response.bodyAsChannel()
                val prefixLines = mutableListOf<String>()
                val firstDataLine = try {
                    withTimeout(app.config.firstTokenTimeoutMs) {
                        var found: String? = null
                        while (found == null) {
                            val next = upstream.readUTF8Line() ?: break
                            prefixLines.add(next)
                            if (
                                protocol == GatewayProtocol.MESSAGES &&
                                allowUpstreamErrorFailover &&
                                isAnthropicStreamError(next)
                            ) {
                                val error = anthropicStreamException(next)
                                if (error.retryable) {
                                    upstream.cancel(error)
                                    throw error
                                }
                            }
                            if (next.trim().startsWith("data:") && isFirstStreamData(next, protocol)) {
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
                            for (line in prefixLines) {
                                writeSseLine(line)
                                markFirstToken(line, protocol, state, startedAt, attemptStartedNanos, app, alias, candidate)
                                recordUsage(line, state, protocol)
                                if (isStreamDone(line, protocol)) {
                                    markTerminalStreamFailure(line, protocol, state)
                                    writeSseLine("")
                                    sawDone = true
                                    break
                                }
                            }
                            if (!sawDone) {
                                while (true) {
                                    val line = try {
                                        withTimeout(app.config.idleTimeoutMs) { upstream.readUTF8Line() }
                                    } catch (e: TimeoutCancellationException) {
                                        throw GatewayException(
                                            HttpStatusCode.GatewayTimeout,
                                            "upstream_timeout",
                                            "上游流式响应空闲超时",
                                            "timeout_error",
                                        )
                                    } ?: break
                                    writeSseLine(line)
                                    markFirstToken(line, protocol, state, startedAt, attemptStartedNanos, app, alias, candidate)
                                    recordUsage(line, state, protocol)
                                    if (isStreamDone(line, protocol)) {
                                        markTerminalStreamFailure(line, protocol, state)
                                        writeSseLine("")
                                        sawDone = true
                                        break
                                    }
                                }
                            }
                            if (!sawDone) state.errorCode = "stream_interrupted"
                            completion.complete(Unit)
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
    } catch (e: GatewayException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw transportFailure(state.committed)
    }
}

private suspend fun proxyJson(
    app: GatewayApplication,
    call: ApplicationCall,
    requestJson: JsonObject,
    candidate: RouteCandidate,
    timeoutMs: Long,
    state: ProxyState,
    protocol: GatewayProtocol,
    traceparent: String,
) {
    val upstreamUrl = buildUpstreamUrl(candidate, protocol)
    val credential = decryptCredential(app, candidate)
    val body = requestJson.withModel(candidate.upstreamModel).toString()
    try {
        withTimeout(timeoutMs) {
            val response = app.httpClient.post(upstreamUrl) {
                applyUpstreamAuthentication(
                    credential = credential,
                    authType = candidate.channel.authType,
                    protocol = protocol,
                    anthropicVersion = call.request.headers["anthropic-version"],
                    anthropicBeta = call.request.headers["anthropic-beta"],
                )
                header("traceparent", traceparent)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(TextContent(body, ContentType.Application.Json))
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw upstreamException(
                    response.status,
                    text,
                    retryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
                    protocol,
                )
            }
            state.committed = true
            state.statusCode = response.status.value
            state.usage = runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                when (protocol) {
                    GatewayProtocol.CHAT_COMPLETIONS -> UsageParser.fromJson(json)
                    GatewayProtocol.RESPONSES -> UsageParser.fromResponsesJson(json)
                    GatewayProtocol.MESSAGES -> UsageParser.fromAnthropicJson(json)
                }
            }.getOrNull()
            call.respondText(text, ContentType.Application.Json, response.status)
        }
    } catch (e: TimeoutCancellationException) {
        throw GatewayException(
            HttpStatusCode.GatewayTimeout,
            "upstream_timeout",
            "上游响应超时",
            "timeout_error",
            retryable = !state.committed,
        )
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

private fun buildUpstreamUrl(candidate: RouteCandidate, protocol: GatewayProtocol): String =
    candidate.channel.baseUrl.trimEnd('/') + protocol.path

private fun decryptCredential(app: GatewayApplication, candidate: RouteCandidate): String = runCatching {
    app.credentialCipher.decrypt(candidate.channel.credentialCiphertext, candidate.channel.credentialNonce)
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

private fun clientDisconnected(): GatewayException =
    GatewayException(
        HttpStatusCode(499, "Client Closed Request"),
        "client_disconnect",
        "客户端连接中断",
        "api_error",
    )

private fun upstreamException(
    status: HttpStatusCode,
    body: String,
    retryAfterMs: Long?,
    protocol: GatewayProtocol,
): GatewayException {
    val nativeResponseBody = body.takeIf { protocol == GatewayProtocol.MESSAGES && isAnthropicErrorBody(it) }
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
        mapped,
        "upstream_error",
        message,
        "api_error",
        upstreamCode,
        retryable,
        retryAfterMs.takeIf { retryable },
        status.value,
        nativeResponseBody,
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
) {
    if (state.firstTokenMs != null) return
    val payload = ssePayload(line) ?: return
    if (payload == "[DONE]") return
    val isFirst = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> true
        GatewayProtocol.RESPONSES -> isFirstResponsesToken(line)
        GatewayProtocol.MESSAGES -> isFirstAnthropicToken(line)
    }
    if (isFirst) {
        val nowNanos = System.nanoTime()
        state.firstTokenMs = (nowNanos - startedAt) / 1_000_000
        app.metrics.firstToken(alias, candidate.channel.name, state.firstTokenMs!!)
        // 首 token 软阈值按本次尝试计时：快速兜底不应继承前面候选撞掉的秒数。
        val attemptFirstTokenMs = (nowNanos - attemptStartedNanos) / 1_000_000
        if (app.candidateCooldown.recordFirstToken(
                candidate.channel.id,
                candidate.upstreamModel,
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
    }
    if (usage != null) state.usage = state.usage?.mergeWith(usage, protocol) ?: usage
}

private fun isFirstStreamData(line: String, protocol: GatewayProtocol): Boolean = when (protocol) {
    GatewayProtocol.CHAT_COMPLETIONS -> true
    GatewayProtocol.RESPONSES -> isFirstResponsesToken(line)
    GatewayProtocol.MESSAGES -> isFirstAnthropicToken(line) || isStreamDone(line, protocol)
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
    return type in RESPONSES_END_EVENTS || type?.endsWith(".delta") == true
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
            "response.failed" -> state.errorCode = "responses_failed"
            "response.incomplete" -> state.errorCode = "responses_incomplete"
        }
        GatewayProtocol.MESSAGES -> if (type == "error") state.errorCode = "messages_failed"
    }
}

private fun isStreamDone(line: String, protocol: GatewayProtocol): Boolean {
    val payload = ssePayload(line) ?: return false
    if (payload == "[DONE]") return true
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> false
        GatewayProtocol.RESPONSES -> type in RESPONSES_END_EVENTS
        GatewayProtocol.MESSAGES -> type == "message_stop" || type == "error"
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

private fun JsonObject.withModel(model: String): JsonObject =
    JsonObject(toMap() + ("model" to JsonPrimitive(model)))
