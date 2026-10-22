package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.ClientKeyRecord
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.RouteCandidate
import dev.soven.llmgateway.proxy.Usage
import dev.soven.llmgateway.proxy.UsageParser
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

/**
 * OpenAI 兼容数据面。负责调用方鉴权、别名路由、流式透传和请求日志。
 *
 * @author 文杰
 * @since 2026-10-21
 */
fun Route.proxyRoutes(app: GatewayApplication) {
    get("/v1/models") {
        authenticate(call, app)
        val aliases = app.aliasRepository.listAvailableAliases()
        val exposedModels = if (app.config.exposeUpstreamModels) {
            val aliasNames = app.aliasRepository.listAllAliasNames()
            app.modelCatalog.listEnabledModels().filterNot { it in aliasNames }
        } else {
            emptyList()
        }
        val models = (aliases + exposedModels).distinct().sorted()
        call.respond(
            buildJsonObject {
                put("object", "list")
                put(
                    "data",
                    buildJsonArray {
                        models.forEach { alias ->
                            add(
                                buildJsonObject {
                                    put("id", alias)
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

    post("/v1/chat/completions") {
        handleProxy(app, call, GatewayProtocol.CHAT_COMPLETIONS)
    }

    post("/v1/responses") {
        handleProxy(app, call, GatewayProtocol.RESPONSES)
    }
}

private suspend fun handleProxy(
    app: GatewayApplication,
    call: ApplicationCall,
    protocol: GatewayProtocol,
) {
    val requestId = call.request.headers["X-Request-Id"]
        ?.take(64)
        ?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()
    call.response.header("X-Request-Id", requestId)

    val startedAt = System.nanoTime()
    val deadlineNanos = startedAt + app.config.totalTimeoutMs * 1_000_000
    val state = ProxyState()
    var clientKey: ClientKeyRecord? = null
    var alias = "unknown"
    var channelId: String? = null
    var channelName: String? = null
    var upstreamModel: String? = null
    var statusCode = HttpStatusCode.InternalServerError.value
    var errorCode: String? = null
    var failureStage: FailureStage? = null
    var errorMessage: String? = null
    var upstreamErrorCode: String? = null
    var phase = FailureStage.REQUEST

    try {
        clientKey = authenticate(call, app)
        val requestJson = call.receiveLimitedJson(app.config.requestBodyLimitBytes)
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

        val attempts = candidates.take(app.config.maxAttempts)
        phase = FailureStage.UPSTREAM
        var lastError: GatewayException? = null
        for ((index, candidate) in attempts.withIndex()) {
            channelId = candidate.channel.id
            channelName = candidate.channel.name
            upstreamModel = candidate.upstreamModel
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                throw GatewayException(
                    HttpStatusCode.GatewayTimeout,
                    "upstream_timeout",
                    "上游响应超时",
                    "timeout_error",
                )
            }
            try {
                if (stream) {
                    proxyStream(app, call, requestJson, candidate, alias, startedAt, remainingMs, state, protocol)
                } else {
                    proxyJson(app, call, requestJson, candidate, remainingMs, state, protocol)
                }
                statusCode = state.statusCode
                return
            } catch (e: GatewayException) {
                lastError = e
                app.metrics.upstreamError(candidate.channel.name, e.code)
                if (!e.retryable || state.committed || index == attempts.lastIndex) throw e
            }
        }
        throw lastError ?: GatewayException(
            HttpStatusCode.BadGateway,
            "no_candidate",
            "没有可用候选",
        )
    } catch (e: TimeoutCancellationException) {
        statusCode = if (state.committed) HttpStatusCode.OK.value else HttpStatusCode.GatewayTimeout.value
        errorCode = if (state.committed) "stream_timeout" else "upstream_timeout"
        failureStage = if (state.committed) FailureStage.STREAMING else FailureStage.UPSTREAM
        errorMessage = if (state.committed) "上游流式响应超时" else "上游响应超时"
        throw GatewayException(HttpStatusCode.GatewayTimeout, "upstream_timeout", "上游响应超时", "timeout_error")
    } catch (e: GatewayException) {
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
            errorMessage = "上游流未正常结束（缺少结束帧）"
        }
        val usage = state.usage
        app.metrics.request(alias, channelId, statusCode)
        if (usage != null) app.metrics.usage(alias, usage)
        app.requestLogWriter.submit(
            RequestLogRecord(
                requestId = requestId,
                clientKeyId = clientKey?.id,
                alias = alias,
                channelId = channelId,
                channelName = channelName,
                upstreamModel = upstreamModel,
                protocol = protocol,
                statusCode = statusCode,
                firstTokenMs = state.firstTokenMs,
                totalMs = (System.nanoTime() - startedAt) / 1_000_000,
                inputTokens = usage?.input,
                outputTokens = usage?.output,
                cachedTokens = usage?.cached,
                totalTokens = usage?.total,
                usageKnown = usage?.known ?: false,
                errorCode = errorCode ?: state.errorCode,
                failureStage = failureStage,
                errorMessage = errorMessage,
                upstreamErrorCode = upstreamErrorCode,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}

private fun String?.asLogMessage(): String? = this?.trim()?.take(1000)?.takeIf { it.isNotEmpty() }

private class ProxyState {
    var committed = false
    var statusCode = HttpStatusCode.OK.value
    var usage: Usage? = null
    var firstTokenMs: Long? = null
    var errorCode: String? = null
}

private suspend fun authenticate(call: ApplicationCall, app: GatewayApplication): ClientKeyRecord {
    val authorization = call.request.headers[HttpHeaders.Authorization] ?: throw unauthorized()
    val raw = authorization.removePrefix("Bearer ").trim()
    if (!raw.startsWith(ClientKeyHasher.PREFIX)) throw unauthorized()
    val record = app.clientKeyRepository.findByHash(ClientKeyHasher.hash(raw)) ?: throw unauthorized()
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
    timeoutMs: Long,
    state: ProxyState,
    protocol: GatewayProtocol,
) {
    val upstreamUrl = buildUpstreamUrl(candidate, protocol)
    val credential = decryptCredential(app, candidate)
    val body = requestJson.withModel(candidate.upstreamModel).toString()

    try {
        withTimeout(timeoutMs) {
            app.httpClient.preparePost(upstreamUrl) {
                header(HttpHeaders.Authorization, "Bearer $credential")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw upstreamException(response.status, response.bodyAsText())
                }
                val upstream = response.bodyAsChannel()
                val prefixLines = mutableListOf<String>()
                val firstDataLine = try {
                    withTimeout(app.config.firstTokenTimeoutMs) {
                        var found: String? = null
                        while (found == null) {
                            val next = upstream.readUTF8Line() ?: break
                            prefixLines.add(next)
                            if (next.trim().startsWith("data:")) found = next
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
                                markFirstToken(line, protocol, state, startedAt, app, alias, candidate.channel.name)
                                recordUsage(line, state, protocol)
                                if (isStreamDone(line, protocol)) {
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
                                    markFirstToken(line, protocol, state, startedAt, app, alias, candidate.channel.name)
                                    recordUsage(line, state, protocol)
                                    if (isStreamDone(line, protocol)) {
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
) {
    val upstreamUrl = buildUpstreamUrl(candidate, protocol)
    val credential = decryptCredential(app, candidate)
    val body = requestJson.withModel(candidate.upstreamModel).toString()
    try {
        withTimeout(timeoutMs) {
            val response = app.httpClient.post(upstreamUrl) {
                header(HttpHeaders.Authorization, "Bearer $credential")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(TextContent(body, ContentType.Application.Json))
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw upstreamException(response.status, text)
            }
            state.committed = true
            state.statusCode = response.status.value
            state.usage = runCatching {
                val json = Json.parseToJsonElement(text).jsonObject
                when (protocol) {
                    GatewayProtocol.CHAT_COMPLETIONS -> UsageParser.fromJson(json)
                    GatewayProtocol.RESPONSES -> UsageParser.fromResponsesJson(json)
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
    candidate.channel.baseUrl.trimEnd('/') + when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> "/chat/completions"
        GatewayProtocol.RESPONSES -> "/responses"
    }

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

private fun upstreamException(status: HttpStatusCode, body: String): GatewayException {
    val upstreamCode = runCatching {
        val error = Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
        (error?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (error?.get("type") as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
    val mapped = when {
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
    return GatewayException(mapped, "upstream_error", message, "api_error", upstreamCode, retryable)
}

private suspend fun ByteWriteChannel.writeSseLine(line: String) {
    writeFully((line + "\n").toByteArray(Charsets.UTF_8))
    flush()
}

private fun markFirstToken(
    line: String,
    protocol: GatewayProtocol,
    state: ProxyState,
    startedAt: Long,
    app: GatewayApplication,
    alias: String,
    channelName: String,
) {
    if (state.firstTokenMs != null) return
    val payload = ssePayload(line) ?: return
    if (payload == "[DONE]") return
    val type = if (protocol == GatewayProtocol.RESPONSES) {
        runCatching { Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
    } else {
        null
    }
    val isFirst = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> true
        GatewayProtocol.RESPONSES -> type in RESPONSES_END_EVENTS || type == "response.output_text.delta"
    }
    if (isFirst) {
        state.firstTokenMs = (System.nanoTime() - startedAt) / 1_000_000
        app.metrics.firstToken(alias, channelName, state.firstTokenMs!!)
    }
}

private fun recordUsage(line: String, state: ProxyState, protocol: GatewayProtocol) {
    val payload = ssePayload(line) ?: return
    if (payload == "[DONE]") return
    val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
    val usage = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> UsageParser.fromJson(json)
        GatewayProtocol.RESPONSES -> UsageParser.fromResponsesJson(json)
    }
    if (usage != null) state.usage = usage
}

private fun isStreamDone(line: String, protocol: GatewayProtocol): Boolean {
    val payload = ssePayload(line) ?: return false
    if (payload == "[DONE]") return true
    if (protocol != GatewayProtocol.RESPONSES) return false
    val type = runCatching {
        Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return type in RESPONSES_END_EVENTS
}

private fun ssePayload(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("data:")) return null
    return trimmed.removePrefix("data:").trim().takeIf { it.isNotEmpty() }
}

private val RESPONSES_END_EVENTS = setOf("response.completed", "response.failed", "response.incomplete")

private fun JsonObject.withModel(model: String): JsonObject =
    JsonObject(toMap() + ("model" to JsonPrimitive(model)))
