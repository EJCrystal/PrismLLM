package dev.soven.llmgateway.proxy.codeassist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * Google Code Assist 的信封：请求是 {model, project, request, ...}，响应是
 * {response}。内层 payload 就是标准 Gemini 结构，所以这里只做包装/解包。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object CodeAssistEnvelope {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 错误信封：网关自己有探测/目录/对话三种非密封调用，上游 4xx/5xx 的正文可能是
     * `{"error": {...}}` 或 `{"response": null, "error": {...}}`，统一取 error 交给下游识别。
     */
    fun errorBody(text: String): String {
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
        val error = parsed["error"] ?: return text
        return buildJsonObject { put("error", error) }.toString()
    }

    /**
     * 把发往 Gemini 的请求体包成 Code Assist 信封。会话 id 由 [scope] 加首条用户文本派生，
     * 让同一段对话落在同一个会话上；取不到文本时退化成随机值。
     */
    fun wrap(body: JsonObject, model: String, project: String?, scope: String? = null): JsonObject {
        val request = JsonObject(body - "model")
        val withSession = JsonObject(request + ("sessionId" to JsonPrimitive(sessionId(request, scope))))
        return JsonObject(
            buildMap {
                put("model", JsonPrimitive(model))
                if (!project.isNullOrBlank()) put("project", JsonPrimitive(project))
                put("request", withSession)
                put("userAgent", JsonPrimitive("antigravity"))
                put("requestType", JsonPrimitive("agent"))
                put("requestId", JsonPrimitive("agent-" + requestId()))
            },
        )
    }

    /** 单次响应：解出 response 字段；没有信封时原样返回。 */
    fun unwrap(parsed: JsonObject): JsonObject = parsed["response"] as? JsonObject ?: parsed

    /** SSE 的每条 data: 也可能是信封，解包后交给下游的 Gemini 解码器。 */
    fun unwrapSseLine(line: String): String {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("data:")) return line
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return line
        val parsed = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return line
        val inner = parsed["response"] as? JsonObject
        if (inner != null) return "data: " + inner
        // 错误事件：信封可能是 {"error": ...} 或 {"response": null, "error": ...}。
        val error = parsed["error"] ?: return line
        return "data: " + buildJsonObject { put("error", error) }
    }

    /**
     * 会话归属：客户端模型别名、渠道、调用方凭据与（可选的）调用方会话标识。
     * 这些在多轮对话里都不变，配合首条用户文本构成稳定的 sessionId；
     * 不同客户端即使首条消息相同，凭据或会话标识不同也不会落到同一个上游会话。
     * 注意不要放入整段请求或每次请求都变的 request-id，那会让每一轮都换会话。
     */
    fun sessionScope(alias: String, channelId: String, clientKeyId: String?, sessionId: String?): String =
        alias + "\u0000" + channelId + "\u0000" + clientKeyId.orEmpty() + "\u0000" + sessionId.orEmpty()

    /** [scope] 与首条用户文本的哈希，作为稳定的 sessionId；没有文本时随机。 */
    fun sessionId(request: JsonObject, scope: String? = null): String {
        var first = ""
        val contents = request["contents"] as? JsonArray
        if (contents != null) {
            for (content in contents) {
                val message = content as? JsonObject ?: continue
                if ((message["role"] as? JsonPrimitive)?.content != "user") continue
                val parts = message["parts"] as? JsonArray ?: continue
                for (part in parts) {
                    val text = ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content
                    if (!text.isNullOrEmpty()) {
                        first = text
                        break
                    }
                }
                break
            }
        }
        if (first.isEmpty()) return "-" + (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(((scope?.let { it + "\u0000" } ?: "") + first).toByteArray())
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xff)
        }
        return "-" + (value and Long.MAX_VALUE)
    }

    private fun requestId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
