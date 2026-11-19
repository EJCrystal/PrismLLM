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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
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
 * 默认保留诊断指定的模型和提示词，正文覆盖只允许解码设置。
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
    isolatedIqRequest: Boolean = true,
): ModelConnectivityTestResult = testSavedChannelModelConnectivity(
    channel, credentialRef, model, protocol, prompt, credentials, httpClient, timeoutMs,
    maxOutputTokens, maxResponseChars, globalOverrides, headerProfiles, isolatedIqRequest, captureRawSample = false,
).result

/** Internal result for one completed execution. Keep raw text out of serializable and printable results. */
internal class ModelConnectivityExecution(
    val result: ModelConnectivityTestResult,
    val rawSample: String? = null,
    private val sanitize: (String) -> String = { it },
) {
    fun safeText(text: String?): String? = text?.let(sanitize)
}

/** The detector sees only a bounded original sample; all returned diagnostic fields are display-safe. */
internal suspend fun sampleFingerprintModelConnectivity(
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
): ModelConnectivityExecution {
    return testSavedChannelModelConnectivity(
        channel, credentialRef, model, protocol, prompt, credentials, httpClient, timeoutMs,
        maxOutputTokens, maxResponseChars, globalOverrides, headerProfiles, isolatedIqRequest = true, captureRawSample = true,
        requireCompleteSample = true,
    )
}

internal suspend fun testSavedChannelModelConnectivity(
    channel: ChannelRecord,
    credentialRef: UpstreamCredential,
    model: String,
    protocol: GatewayProtocol,
    prompt: String,
    credentials: KeyCredentialResolver,
    httpClient: HttpClient,
    timeoutMs: Long,
    maxOutputTokens: Int,
    maxResponseChars: Int,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    isolatedIqRequest: Boolean,
    captureRawSample: Boolean,
    requireCompleteSample: Boolean = false,
): ModelConnectivityExecution {
    val credential = resolveSavedCredential(credentials, credentialRef)
    return runModelConnectivityTest(
        credential, protocol, httpClient, UpstreamEnvelope.NONE, timeoutMs, maxResponseChars, captureRawSample, requireCompleteSample,
    ) {
        buildModelTestRequest(
            channel, credential, model, protocol, prompt, UpstreamEnvelope.NONE, null,
            DEFAULT_ANTIGRAVITY_CODE_ASSIST_USER_AGENT, maxOutputTokens, globalOverrides, headerProfiles, isolatedIqRequest,
        )
    }
}

/** Build the native OAuth request during preparation; the returned probe owns only the HTTP phase. */
internal fun prepareOAuthAccountModelConnectivity(
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
): suspend () -> ModelConnectivityTestResult {
    val envelope = channel.upstreamEnvelope
    if (envelope == UpstreamEnvelope.CODE_ASSIST && project.isNullOrBlank()) {
        return {
            ModelConnectivityTestResult(
                success = false,
                statusCode = null,
                latencyMs = 0,
                responseText = null,
                errorMessage = "未获取到 Antigravity 项目，无法发起请求；请确认账号已完成开通",
                usage = null,
            )
        }
    }
    val request = try {
        buildModelTestRequest(
            channel, accessToken, model, protocol, prompt, envelope, project, codeAssistUserAgent,
            maxOutputTokens, globalOverrides, headerProfiles, isolatedIqRequest = true,
        )
    } catch (cause: Exception) {
        if (cause is CancellationException) throw cause
        val failure = modelTestConnectionFailure(cause, accessToken, latencyMs = 0)
        return { failure }
    }
    return { runModelConnectivityTest(accessToken, protocol, httpClient, envelope, timeoutMs, maxResponseChars) { request }.result }
}

private fun buildModelTestRequest(
    channel: ChannelRecord,
    credential: String,
    model: String,
    protocol: GatewayProtocol,
    prompt: String,
    envelope: UpstreamEnvelope,
    project: String?,
    codeAssistUserAgent: String,
    maxOutputTokens: Int,
    globalOverrides: UpstreamRequestOverrides,
    headerProfiles: List<UpstreamHeaderProfile>,
    isolatedIqRequest: Boolean,
): HttpRequestBuilder {
    val authType = channel.authTypeFor(protocol)
    val policy = UpstreamRequestPolicy(globalOverrides, channel.requestOverrides, protocol, authType, headerProfiles, channel.baseUrl)
    val original = Json.parseToJsonElement(buildModelTestBody(protocol, model, prompt, maxOutputTokens)) as JsonObject
    val overridden = policy.body(original, model)
    val requestBody = if (isolatedIqRequest) isolateIqRequestBody(original, overridden, protocol) else overridden
    val limited = limitModelTestTokens(requestBody, protocol, maxOutputTokens)
    val body = if (envelope == UpstreamEnvelope.CODE_ASSIST) {
        // 连通性探测只验证信封可达，不参与真实对话的档位/编号；labels/sessionId 用默认派生值。
        CodeAssistEnvelope.wrap(CodeAssistCloak.apply(limited), model, project).toString()
    } else {
        limited.toString()
    }
    return HttpRequestBuilder().apply {
        url(upstreamRequestUrl(channel.baseUrl, protocol, model, envelope = envelope))
        applyUpstreamAuthentication(credential, authType, protocol)
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        policy.applyHeaders(this)
        if (envelope == UpstreamEnvelope.CODE_ASSIST) applyCodeAssistIdentity(codeAssistUserAgent)
        setBody(TextContent(body, ContentType.Application.Json))
    }
}

private suspend fun runModelConnectivityTest(
    credential: String,
    protocol: GatewayProtocol,
    httpClient: HttpClient,
    envelope: UpstreamEnvelope,
    timeoutMs: Long,
    maxResponseChars: Int,
    captureRawSample: Boolean = false,
    requireCompleteSample: Boolean = false,
    request: () -> HttpRequestBuilder,
): ModelConnectivityExecution {
    val startedAt = System.currentTimeMillis()
    return try {
        withTimeoutOrNull(timeoutMs) {
            val prepared = request()
            currentCoroutineContext().ensureActive()
            val response = httpClient.post(prepared)
            val rawBody = response.bodyAsText()
            val latencyMs = System.currentTimeMillis() - startedAt
            val status = response.status.value
            val ok = status in 200..299
            val body = if (!ok && envelope == UpstreamEnvelope.CODE_ASSIST) CodeAssistEnvelope.errorBody(rawBody) else rawBody
            val parsedBody = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            val parsed = parsedBody as? JsonObject
                ?: if (ok && protocol == GatewayProtocol.RESPONSES) parseResponsesStreamText(body) else null
            val json = if (ok && envelope == UpstreamEnvelope.CODE_ASSIST && parsed != null) CodeAssistEnvelope.unwrap(parsed) else parsed
            // Parse status and usage on the original JSON. Redact only the decoded display values.
            val responseState = (json?.get("status") as? JsonPrimitive)?.contentOrNull
            val incompleteResponse = protocol == GatewayProtocol.RESPONSES &&
                responseState in setOf("incomplete", "failed", "cancelled", "queued", "in_progress")
            val responsesError = ok && protocol == GatewayProtocol.RESPONSES &&
                (json?.get("error") is JsonObject || incompleteResponse)
            val displayText = if (ok) extractModelTestResponseText(protocol, json) else null
            val result = ModelConnectivityTestResult(
                success = ok && !responsesError,
                statusCode = status,
                latencyMs = latencyMs,
                responseText = displayText?.let { redactModelTestCredential(it, credential).take(maxResponseChars) },
                errorMessage = if (!ok || responsesError) extractModelTestError(json, status, credential) {
                    if (ok && incompleteResponse) {
                        "上游 Responses 尚未成功完成：${redactModelTestCredential(responseState.orEmpty(), credential)}"
                    } else {
                        redactModelTestErrorPreview(body, parsedBody ?: parsed, credential)
                    }
                } else null,
                usage = if (ok) extractModelTestUsage(protocol, json) else null,
                upstreamReportedModel = if (ok) extractReportedModel(protocol, json) { redactModelTestCredential(it, credential) } else null,
            )
            ModelConnectivityExecution(
                result = result,
                rawSample = if (captureRawSample && result.success && (!requireCompleteSample || !isModelTestOutputTruncated(protocol, json))) {
                    displayText?.take(maxResponseChars)
                } else null,
                sanitize = { redactModelTestCredential(it, credential) },
            )
        } ?: ModelConnectivityExecution(
            result = ModelConnectivityTestResult(
                success = false,
                statusCode = null,
                latencyMs = System.currentTimeMillis() - startedAt,
                responseText = null,
                errorMessage = "测试超时（${timeoutMs / 1000} 秒）",
                usage = null,
            ),
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        ModelConnectivityExecution(modelTestConnectionFailure(cause, credential, System.currentTimeMillis() - startedAt))
    }
}

private fun modelTestConnectionFailure(cause: Throwable, credential: String, latencyMs: Long): ModelConnectivityTestResult =
    ModelConnectivityTestResult(
        success = false,
        statusCode = null,
        latencyMs = latencyMs,
        responseText = null,
        errorMessage = "无法连接上游：${redactModelTestCredential(cause.message ?: cause::class.simpleName.orEmpty(), credential).take(200)}",
        usage = null,
    )

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

private fun firstChatCompletionChoice(json: JsonObject?): JsonObject? =
    (json?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject

/** A reachable endpoint may return partial text; explicit truncation disqualifies only the raw sample. */
private fun isModelTestOutputTruncated(protocol: GatewayProtocol, json: JsonObject?): Boolean = when (protocol) {
    GatewayProtocol.CHAT_COMPLETIONS -> when ((firstChatCompletionChoice(json)?.get("finish_reason") as? JsonPrimitive)?.contentOrNull) {
        "length", "content_filter" -> true
        else -> false
    }
    GatewayProtocol.MESSAGES -> when ((json?.get("stop_reason") as? JsonPrimitive)?.contentOrNull) {
        "max_tokens", "model_context_window_exceeded", "refusal" -> true
        else -> false
    }
    GatewayProtocol.GEMINI -> (json?.get("candidates") as? JsonArray)?.any { candidate ->
        val reason = ((candidate as? JsonObject)?.get("finishReason") as? JsonPrimitive)?.contentOrNull
        reason != null && reason != "STOP"
    } == true
    GatewayProtocol.RESPONSES -> false // Non-completed Responses already fail the connectivity check.
}

internal fun extractModelTestResponseText(protocol: GatewayProtocol, json: JsonObject?): String? {
    if (json == null) return null
    return when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> collectText((firstChatCompletionChoice(json)?.get("message") as? JsonObject)?.get("content"))
        GatewayProtocol.RESPONSES -> {
            collectText(json["output_text"]) ?: collectText(json["output"]) ?: collectText(json["response"])
        }
        GatewayProtocol.MESSAGES -> collectText(json["content"])
        GatewayProtocol.GEMINI -> collectText(json["candidates"])
    }
}

private fun extractModelTestError(json: JsonObject?, statusCode: Int, credential: String, preview: () -> String): String {
    val error = json?.get("error")
    val message = when (error) {
        is JsonObject -> collectText(error["message"]) ?: collectText(error)
        is JsonPrimitive -> error.contentOrNull
        else -> null
    }
    // An error message can span text blocks. Redact the joined value before bounding it.
    return message?.let { redactModelTestCredential(it, credential).take(MAX_ERROR_CHARS) }
        ?: preview().trim().take(MAX_ERROR_CHARS).takeIf { it.isNotEmpty() }
        ?: "上游返回 HTTP $statusCode"
}

private fun redactModelTestCredential(text: String, credential: String): String {
    if (credential.isEmpty()) return text
    fun hexPattern(hex: String): String = hex.map { char ->
        if (char in 'a'..'f') "[$char${char.uppercaseChar()}]" else char.toString()
    }.joinToString("")
    // Match the whole credential, allowing each code point's common echo encoding.
    // Hex case is flexible; literal token characters remain case-sensitive.
    val pattern = credential.codePoints().toArray().joinToString("") { codePoint ->
        val literal = String(Character.toChars(codePoint))
        val urlEncoded = literal.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            "%" + hexPattern((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
        val unicodeEscaped = literal.map { char ->
            Regex.escape("\\u") + hexPattern(char.code.toString(16).padStart(4, '0'))
        }.joinToString("")
        val jsonEscaped = JsonPrimitive(literal).toString().drop(1).dropLast(1)
        val forms = mutableListOf(urlEncoded, unicodeEscaped, Regex.escape(jsonEscaped))
        if (literal == "/") forms += Regex.escape("\\/")
        if (literal == " ") forms += Regex.escape("+")
        forms += Regex.escape(literal)
        "(?:${forms.distinct().joinToString("|")})"
    }
    return Regex(pattern).replace(text) { "[REDACTED]" }
}

private fun redactModelTestErrorPreview(body: String, json: JsonElement?, credential: String): String =
    if (json == null) redactModelTestCredential(body, credential) else redactModelTestJsonPreview(json, credential).toString()

/** Display-only copy: keys can also contain JSON-escaped echoes. Never use this copy for parsing. */
private fun redactModelTestJsonPreview(element: JsonElement, credential: String): JsonElement {
    return when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) ->
            redactModelTestCredential(key, credential) to redactModelTestJsonPreview(value, credential)
        })
        is JsonArray -> JsonArray(element.map { redactModelTestJsonPreview(it, credential) })
        is JsonPrimitive -> {
            val value = element.content
            val redacted = redactModelTestCredential(value, credential)
            if (redacted != value) JsonPrimitive(redacted) else element
        }
    }
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
 * `output` 保留数组原样，usage、model 与 status 取自终止 response；失败或未完成的
 * 终止事件同样保留元数据与部分文本，并附带错误供调用方把 2xx 流判为失败。
 */
internal fun parseResponsesStreamText(body: String): JsonObject? {
    var text: String? = null
    var output: JsonElement? = null
    var usage: JsonObject? = null
    var reportedModel: String? = null
    var responseStatus: String? = null
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
        val terminalStatus = when {
            type.startsWith("response.failed") -> "failed"
            type.startsWith("response.incomplete") -> "incomplete"
            type.startsWith("response.completed") -> "completed"
            else -> null
        }
        if (terminalStatus != null) {
            terminated = true
            val response = event["response"] as? JsonObject
            responseStatus = (response?.get("status") as? JsonPrimitive)?.contentOrNull ?: terminalStatus
            usage = response?.get("usage") as? JsonObject ?: usage
            reportedModel = (response?.get("model") as? JsonPrimitive)?.contentOrNull ?: reportedModel
            output = response?.get("output") ?: output
            val terminalText = collectText(response?.get("output_text")) ?: collectText(response?.get("output"))
            // A terminal response carries the full (possibly partial) text, not another delta.
            if (!terminalText.isNullOrEmpty()) text = terminalText
            val error = response?.get("error") as? JsonObject
            if (error != null) {
                failedError = buildJsonObject { put("error", error) }
            } else if (terminalStatus == "failed") {
                failedError = buildJsonObject { put("error", buildJsonObject { put("message", "上游响应以失败事件终止") }) }
            } else if (terminalStatus == "incomplete") {
                val details = response?.get("incomplete_details") as? JsonObject
                val reason = (details?.get("reason") as? JsonPrimitive)?.contentOrNull ?: "output_token_limit"
                failedError = buildJsonObject {
                    put("error", buildJsonObject { put("message", "响应因达到输出上限被截断（$reason）") })
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
        responseStatus?.let { put("status", it) }
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
