package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.shared.domain.Usage

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.channel.domain.UpstreamCredential
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.channel.domain.authTypeFor
import dev.soven.llmgateway.shared.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.oauth.KeyCredentialResolver
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
): ModelConnectivityTestResult {
    val credential = resolveSavedCredential(credentials, credentialRef)

    val startedAt = System.currentTimeMillis()
    return try {
        withTimeout(timeoutMs) {
            val authType = channel.authTypeFor(protocol)
            val policy = UpstreamRequestPolicy(
                globalOverrides, channel.requestOverrides, protocol, authType, headerProfiles, channel.baseUrl,
            )
            val requestBody = policy.body(
                Json.parseToJsonElement(buildModelTestBody(protocol, model, prompt, maxOutputTokens)) as JsonObject, model,
            )
            val response = httpClient.post(upstreamRequestUrl(channel.baseUrl, protocol, model)) {
                applyUpstreamAuthentication(credential, authType, protocol)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                policy.applyHeaders(this)
                setBody(
                    TextContent(
                        limitModelTestTokens(requestBody, protocol, maxOutputTokens).toString(),
                        ContentType.Application.Json,
                    ),
                )
            }
            val body = response.bodyAsText()
            val latencyMs = System.currentTimeMillis() - startedAt
            val status = response.status.value
            val json = parseJsonObject(body)
            if (status in 200..299) {
                ModelConnectivityTestResult(
                    success = true,
                    statusCode = status,
                    latencyMs = latencyMs,
                    responseText = extractModelTestResponseText(protocol, json)?.take(maxResponseChars),
                    errorMessage = null,
                    usage = extractModelTestUsage(protocol, json),
                    upstreamReportedModel = extractReportedModel(protocol, json),
                )
            } else {
                ModelConnectivityTestResult(
                    success = false,
                    statusCode = status,
                    latencyMs = latencyMs,
                    responseText = null,
                    errorMessage = extractModelTestError(json, body, status),
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
        put("stream", false)
        put("max_output_tokens", maxOutputTokens)
        put("input", prompt)
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

private fun collectText(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonArray -> element.mapNotNull(::collectText).joinToString("").takeIf { it.isNotBlank() }
    is JsonObject -> collectText(element["text"]) ?: collectText(element["content"]) ?: collectText(element["parts"])
    else -> null
}

private const val MAX_RESPONSE_CHARS = 1_000
private const val MAX_ERROR_CHARS = 500
