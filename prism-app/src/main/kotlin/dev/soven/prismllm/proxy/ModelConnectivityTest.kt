package dev.soven.prismllm.proxy

import dev.soven.prismllm.protocol.UsageParser
import dev.soven.prismllm.protocol.extractReportedModel

import dev.soven.prismllm.shared.domain.Usage

import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.UpstreamEnvelope
import dev.soven.prismllm.channel.domain.upstreamEnvelope
import dev.soven.prismllm.proxy.codeassist.CodeAssistCloak
import dev.soven.prismllm.proxy.codeassist.CodeAssistEnvelope
import dev.soven.prismllm.proxy.codeassist.DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT
import dev.soven.prismllm.shared.domain.UpstreamCredential
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.channel.domain.authTypeFor
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.channel.domain.UpstreamHeaderProfile
import dev.soven.prismllm.shared.domain.KeyCredentialResolver
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull

/** 一次渠道模型连通性测试的结果。 */
data class ModelConnectivityTestResult(
    val success: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val responseText: String?,
    val errorMessage: String?,
    val usage: Usage?,
    val upstreamReportedModel: String? = null,
)

/**
 * 使用渠道已保存的凭据直接向上游发送一次真实请求。
 *
 * 该请求不经过网关数据面，不写入请求日志，也不消耗调用方密钥额度。
 */
suspend fun testChannelModelConnectivity(
    channel: ChannelRecord,
    credentialRef: UpstreamCredential,
    model: String,
    protocol: GatewayProtocol,
    prompt: String,
    credentials: KeyCredentialResolver,
    httpClient: HttpClient,
    timeoutMs: Long = 45_000,
    maxOutputTokens: Int = 128,
    maxResponseChars: Int = MAX_RESPONSE_CHARS,
    globalOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    headerProfiles: List<UpstreamHeaderProfile> = UpstreamHeaderProfiles.defaults,
    isolatedIqRequest: Boolean = false,
): ModelConnectivityTestResult {
    val credential = resolveSavedCredential(credentials, credentialRef)
    return runModelConnectivityTest(
        channel = channel,
        credential = credential,
        model = model,
        protocol = protocol,
        prompt = prompt,
        httpClient = httpClient,
        envelope = UpstreamEnvelope.NONE,
        project = null,
        codeAssistUserAgent = DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT,
        timeoutMs = timeoutMs,
        maxOutputTokens = maxOutputTokens,
        maxResponseChars = maxResponseChars,
        globalOverrides = globalOverrides,
        headerProfiles = headerProfiles,
        isolatedIqRequest = isolatedIqRequest,
    )
}

/**
 * 订阅账号的连通性测试：使用账号自己的 access token 与官方端点。
 * Antigravity 的请求必须带上账号 project 并包一层 Code Assist 信封，
 * 与数据面保持一致，否则测出来的结果不能代表真实调用。
 */
suspend fun testOAuthAccountModelConnectivity(
    channel: ChannelRecord,
    accessToken: String,
    project: String?,
    model: String,
    protocol: GatewayProtocol,
    prompt: String,
    httpClient: HttpClient,
    codeAssistUserAgent: String = DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT,
    timeoutMs: Long = 45_000,
    maxOutputTokens: Int = 128,
    maxResponseChars: Int = MAX_RESPONSE_CHARS,
    globalOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    headerProfiles: List<UpstreamHeaderProfile> = UpstreamHeaderProfiles.defaults,
): ModelConnectivityTestResult = runModelConnectivityTest(
    channel = channel,
    credential = accessToken,
    model = model,
    protocol = protocol,
    prompt = prompt,
    httpClient = httpClient,
    envelope = channel.upstreamEnvelope,
    project = project,
    codeAssistUserAgent = codeAssistUserAgent,
    timeoutMs = timeoutMs,
    maxOutputTokens = maxOutputTokens,
    maxResponseChars = maxResponseChars,
    globalOverrides = globalOverrides,
    headerProfiles = headerProfiles,
)

private suspend fun runModelConnectivityTest(
    channel: ChannelRecord,
    credential: String,
    model: String,
    protocol: GatewayProtocol,
    prompt: String,
    httpClient: HttpClient,
    envelope: UpstreamEnvelope,
    project: String?,
    codeAssistUserAgent: String,
    timeoutMs: Long,
    maxOutputTokens: Int,
    maxResponseChars: Int,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    isolatedIqRequest: Boolean = false,
): ModelConnectivityTestResult {
    if (envelope == UpstreamEnvelope.CODE_ASSIST && project.isNullOrBlank()) {
        return ModelConnectivityTestResult(
            success = false,
            statusCode = null,
            latencyMs = 0,
            responseText = null,
            errorMessage = "未获取到 Antigravity 项目，无法发起请求；请确认账号已完成开通",
            usage = null,
        )
    }
    val startedAt = System.currentTimeMillis()
    return try {
        withTimeout(timeoutMs) {
            val authType = channel.authTypeFor(protocol)
            val policy = UpstreamRequestPolicy(
                globalOverrides, channel.requestOverrides, protocol, authType, headerProfiles, channel.baseUrl,
            )
            val original = Json.parseToJsonElement(buildModelTestBody(protocol, model, prompt, maxOutputTokens)) as JsonObject
            val overridden = policy.body(original, model)
            val requestBody = if (isolatedIqRequest) isolateIqRequestBody(original, overridden, protocol) else overridden
            val limited = limitModelTestTokens(requestBody, protocol, maxOutputTokens)
            val body = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
                CodeAssistEnvelope.wrap(CodeAssistCloak.apply(limited), model, project).toString()
            } else {
                limited.toString()
            }
            val response = httpClient.post(
                upstreamRequestUrl(channel.baseUrl, protocol, model, envelope = envelope),
            ) {
                applyUpstreamAuthentication(credential, authType, protocol)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                policy.applyHeaders(this)
                if (envelope == UpstreamEnvelope.CODE_ASSIST) {
                    applyCodeAssistIdentity(codeAssistUserAgent)
                }
                setBody(TextContent(body, ContentType.Application.Json))
            }
            val bodyText = response.bodyAsText()
            val latencyMs = System.currentTimeMillis() - startedAt
            val status = response.status.value
            val parsed = parseJsonObject(bodyText)
                ?: if (protocol == GatewayProtocol.RESPONSES) parseResponsesStreamText(bodyText) else null
            val json = if (envelope == UpstreamEnvelope.CODE_ASSIST && parsed != null) {
                CodeAssistEnvelope.unwrap(parsed)
            } else {
                parsed
            }
            // Responses 流可能在 HTTP 2xx 后以 response.failed 终止：只要流内带错误，
            // 即使已收到部分文本也不能报成功。
            val streamError = if (protocol == GatewayProtocol.RESPONSES && status in 200..299) {
                json?.get("error") as? JsonObject
            } else {
                null
            }
            if (status in 200..299) {
                ModelConnectivityTestResult(
                    success = streamError == null,
                    statusCode = status,
                    latencyMs = latencyMs,
                    responseText = extractModelTestResponseText(protocol, json)?.take(maxResponseChars),
                    errorMessage = streamError?.let { extractModelTestError(json, bodyText, status) },
                    usage = extractModelTestUsage(protocol, json),
                    upstreamReportedModel = extractReportedModel(protocol, json),
                )
            } else {
                val errorText = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
                    CodeAssistEnvelope.errorBody(bodyText)
                } else {
                    bodyText
                }
                ModelConnectivityTestResult(
                    success = false,
                    statusCode = status,
                    latencyMs = latencyMs,
                    responseText = null,
                    errorMessage = extractModelTestError(parseJsonObject(errorText), errorText, status),
                    usage = null,
                )
            }
        }
    } catch (cause: TimeoutCancellationException) {
        ModelConnectivityTestResult(
            success = false,
            statusCode = null,
            latencyMs = System.currentTimeMillis() - startedAt,
            responseText = null,
            errorMessage = "测试超时（${timeoutMs / 1000} 秒）",
            usage = null,
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        ModelConnectivityTestResult(
            success = false,
            statusCode = null,
            latencyMs = System.currentTimeMillis() - startedAt,
            responseText = null,
            errorMessage = "无法连接上游：${cause.message?.take(200) ?: cause::class.simpleName}",
            usage = null,
        )
    }
}

/** Allow only decoding settings from body overrides: tools, search flags, history and alternate inputs cannot enter an IQ test. */
internal fun isolateIqRequestBody(original: JsonObject, overridden: JsonObject, protocol: GatewayProtocol): JsonObject {
    val scalarSettings = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> setOf("temperature", "top_p", "seed", "presence_penalty", "frequency_penalty", "reasoning_effort", "max_tokens", "max_completion_tokens")
        GatewayProtocol.RESPONSES -> setOf("temperature", "top_p", "max_output_tokens")
        GatewayProtocol.MESSAGES -> setOf("temperature", "top_p", "top_k", "max_tokens")
        GatewayProtocol.GEMINI -> emptySet()
    }
    val safe = overridden.filter { (key, value) -> key in scalarSettings && value is JsonPrimitive && value != kotlinx.serialization.json.JsonNull }
    val body = original.toMutableMap().apply { putAll(safe) }
    if (protocol == GatewayProtocol.RESPONSES) {
        (overridden["reasoning"] as? JsonObject)?.let { reasoning ->
            body["reasoning"] = JsonObject(reasoning.filter { (key, value) -> key in setOf("effort", "summary") && value is JsonPrimitive })
        }
        body["store"] = JsonPrimitive(false)
    }
    if (protocol == GatewayProtocol.MESSAGES) {
        (overridden["thinking"] as? JsonObject)?.let { thinking ->
            body["thinking"] = JsonObject(thinking.filter { (key, value) -> key in setOf("type", "budget_tokens") && value is JsonPrimitive })
        }
    }
    if (protocol == GatewayProtocol.GEMINI) {
        val generation = original["generationConfig"] as JsonObject
        val safeGeneration = (overridden["generationConfig"] as? JsonObject).orEmpty().filter { (key, value) ->
            key in setOf("temperature", "topP", "topK", "maxOutputTokens", "seed") && value is JsonPrimitive
        }
        body["generationConfig"] = JsonObject(generation + safeGeneration)
    }
    return JsonObject(body)
}

internal fun buildModelTestBody(
    protocol: GatewayProtocol,
    model: String,
    prompt: String,
    maxOutputTokens: Int,
): String = when (protocol) {
    GatewayProtocol.CHAT_COMPLETIONS -> buildJsonObject {
        put("model", model)
        put("stream", false)
        put("max_tokens", maxOutputTokens)
        put("messages", userMessages(prompt))
    }.toString()
    GatewayProtocol.RESPONSES -> buildJsonObject {
        put("model", model)
        put("stream", true)
        put("max_output_tokens", maxOutputTokens)
        put("input", buildJsonArray {
            add(buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", prompt)
                    })
                })
            })
        })
        put("store", false)
    }.toString()
    GatewayProtocol.MESSAGES -> buildJsonObject {
        put("model", model)
        put("stream", false)
        put("max_tokens", maxOutputTokens)
        put("messages", userMessages(prompt))
    }.toString()
    GatewayProtocol.GEMINI -> buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
            })
        })
        put("generationConfig", buildJsonObject { put("maxOutputTokens", maxOutputTokens) })
    }.toString()
}

/** Administrative checks keep their documented token budget after applying user overrides. */
internal fun limitModelTestTokens(body: JsonObject, protocol: GatewayProtocol, maxTokens: Int): JsonObject {
    val field = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> if ("max_completion_tokens" in body) "max_completion_tokens" else "max_tokens"
        GatewayProtocol.RESPONSES -> "max_output_tokens"
        GatewayProtocol.GEMINI -> "maxOutputTokens"
        else -> "max_tokens"
    }
    val parent = when {
        protocol == GatewayProtocol.GEMINI -> when (val generationConfig = body["generationConfig"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> generationConfig
            else -> throw GatewayError(
                HttpStatusCode.BadRequest,
                "invalid_request_overrides",
                "Gemini generationConfig 必须是 JSON 对象",
                "invalid_request_error",
            )
        }
        field == "max_completion_tokens" -> JsonObject(body - "max_tokens")
        else -> body
    }
    val configured = (parent[field] as? JsonPrimitive)?.intOrNull
    val bounded = JsonObject(parent + (field to JsonPrimitive(configured?.coerceIn(1, maxTokens) ?: maxTokens)))
    return if (protocol == GatewayProtocol.GEMINI) JsonObject(body + ("generationConfig" to bounded)) else bounded
}

internal fun extractModelTestResponseText(protocol: GatewayProtocol, json: JsonObject?): String? {
    if (json == null) return null
    return when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> {
            val choices = json["choices"] as? JsonArray
            collectText((choices?.firstOrNull() as? JsonObject)?.get("message")?.let { message ->
                (message as? JsonObject)?.get("content")
            })
        }
        GatewayProtocol.RESPONSES -> {
            collectText(json["output_text"]) ?: collectText(json["output"]) ?: collectText(json["response"])
        }
        GatewayProtocol.MESSAGES -> collectText(json["content"])
        GatewayProtocol.GEMINI -> collectText(json["candidates"])
    }
}

internal fun extractModelTestError(json: JsonObject?, body: String, statusCode: Int): String {
    val error = json?.get("error")
    val message = when (error) {
        is JsonObject -> collectText(error["message"]) ?: collectText(error)
        is JsonPrimitive -> error.contentOrNull
        else -> null
    }
    return message?.take(MAX_ERROR_CHARS)
        ?: body.trim().take(MAX_ERROR_CHARS).takeIf { it.isNotEmpty() }
        ?: "上游返回 HTTP $statusCode"
}

private fun extractModelTestUsage(protocol: GatewayProtocol, json: JsonObject?): Usage? {
    if (json == null) return null
    return when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> UsageParser.fromJson(json)
        GatewayProtocol.RESPONSES -> UsageParser.fromResponsesJson(json)
        GatewayProtocol.MESSAGES -> UsageParser.fromAnthropicJson(json)
        GatewayProtocol.GEMINI -> UsageParser.fromGeminiJson(json)
    }
}

private fun userMessages(prompt: String) = buildJsonArray {
    add(buildJsonObject {
        put("role", "user")
        put("content", prompt)
    })
}

private fun parseJsonObject(body: String): JsonObject? =
    runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

/**
 * 把 Responses 的 SSE 流（`data: {json}` 行）折成非流式形状：`output_text` 聚合文本，
 * `output` 保留数组原样，usage 与 reported model 取自最后的完成事件；流以
 * `response.failed` 终止时记录终止状态与上游错误，供调用方把 2xx 流判为失败。
 */
internal fun parseResponsesStreamText(body: String): JsonObject? {
    var text: String? = null
    var output: JsonElement? = null
    var usage: JsonObject? = null
    var reportedModel: String? = null
    var failedError: JsonObject? = null
    var terminated = false
    for (line in body.lineSequence()) {
        if (!line.startsWith("data:")) continue
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") continue
        val event = parseJsonObject(payload) ?: continue
        val type = (event["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val delta = when {
            type.startsWith("response.output_text.delta") -> (event["delta"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        if (delta != null) {
            text = (text.orEmpty() + delta).takeIf { it.isNotEmpty() }
        }
        if (type == "error") {
            // 独立 error 事件的 code/message 在事件顶层；归一化成 error 嵌套形状。
            failedError = buildJsonObject { put("error", event) }
            terminated = true
        }
        if (type.startsWith("response.failed")) {
            failedError = event["response"] as? JsonObject
            terminated = true
        }
        if (type.startsWith("response.completed")) {
            terminated = true
            (event["response"] as? JsonObject)?.let { response ->
                usage = response["usage"] as? JsonObject ?: usage
                reportedModel = (response["model"] as? JsonPrimitive)?.contentOrNull ?: reportedModel
                output = response["output"] ?: output
                val completedText = (response["output"] as? JsonArray)
                    ?.asSequence()
                    ?.filterIsInstance<JsonObject>()
                    ?.mapNotNull { item ->
                        (item["content"] as? JsonArray)
                            ?.filterIsInstance<JsonObject>()
                            ?.mapNotNull { part -> (part["text"] as? JsonPrimitive)?.contentOrNull }
                            ?.joinToString("")
                    }
                    ?.joinToString("")
                // 完成事件的 output 携带完整文本：有增量时以它为准，避免拼接出重复文本。
                if (!completedText.isNullOrEmpty()) {
                    text = completedText
                }
            }
        }
    }
    // 空流（例如只收到 response.created 就断开）：没有任何聚合字段也没有终止事件，
    // 同样按截断失败处理，不能返回 null 让调用方误判成功。
    if (text == null && usage == null && output == null && failedError == null) {
        if (terminated) return null
        return buildJsonObject {
            put("stream_truncated", true)
            put("error", buildJsonObject { put("message", "上游流式响应未收到终止事件，连接可能中断") })
        }
    }
    return buildJsonObject {
        text?.let { put("output_text", it) }
        output?.let { put("output", it) }
        usage?.let { put("usage", it) }
        reportedModel?.let { put("model", it) }
        failedError?.let { failed -> put("error", failed["error"] ?: failed) }
        // 上游或代理可能中途断流：没有终止事件时直接折叠成 error 形状，调用方按失败处理。
        if (!terminated) {
            put("stream_truncated", true)
            put("error", buildJsonObject { put("message", "上游流式响应未收到终止事件，连接可能中断") })
        }
    }
}

internal fun collectText(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonArray -> element.mapNotNull(::collectText).joinToString("").takeIf { it.isNotBlank() }
    is JsonObject -> collectText(element["text"]) ?: collectText(element["content"]) ?: collectText(element["parts"])
    else -> null
}

internal const val MAX_RESPONSE_CHARS = 1_000
private const val MAX_ERROR_CHARS = 500
