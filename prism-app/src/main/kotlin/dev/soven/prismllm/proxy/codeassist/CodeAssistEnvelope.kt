package dev.soven.prismllm.proxy.codeassist

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
 * 信封字段与 Antigravity 2.32.1 的实际请求对齐（抓包见 docs/codeassist.md）：
 * - userAgent 固定为 "antigravity"，requestType 固定为 "agent"
 * - requestId 形如 agent/<cascadeId>/<epochMs>/<trajectoryId>/<step>
 * - request 内带 labels（cascade/trajectory/request 编号）与 generationConfig
 *   默认值（maxOutputTokens + thinkingConfig）
 *
 * @author 文杰
 * @since 2026-11-06
 */
object CodeAssistEnvelope {
    private val json = Json { ignoreUnknownKeys = true }

    /** Antigravity 的 xhigh 档用数字 100，其余档位用枚举名字符串。 */
    private const val THINKING_LEVEL_XHIGH = 100

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
     * 把发往 Gemini 的请求体包成 Code Assist 信封。会话 id 只由 [scope]（客户端凭据、
     * 渠道、模型别名与调用方会话标识）派生：同一次对话内它保持不变，因此多轮落在同一个
     * 上游会话上；不同客户端的相同首条消息属于不同 scope，不会共享上游会话状态。
     *
     * [effort] 是本次实际发出的模型档位（从选中的变体 id 解析），用于补 Antigravity
     * 一定会带的 `thinkingConfig.thinkingLevel`；[maxOutputTokens] 是目录里该变体的
     * 输出上限。调用方已经显式设置的字段不会被覆盖。
     */
    fun wrap(
        body: JsonObject,
        model: String,
        project: String? = null,
        scope: String? = null,
        effort: String? = null,
        maxOutputTokens: Long? = null,
        now: () -> Long = System::currentTimeMillis,
    ): JsonObject {
        val cascadeId = stableId("cascade", scope)
        val trajectoryId = stableId("trajectory", scope)
        val stepIndex = (((body["contents"] as? JsonArray)?.size ?: 0) - 1).coerceAtLeast(0)
        val request = decorateRequest(
            body = JsonObject(body - "model"),
            model = model,
            effort = effort,
            maxOutputTokens = maxOutputTokens,
            scope = scope,
            cascadeId = cascadeId,
            trajectoryId = trajectoryId,
            stepIndex = stepIndex,
        )
        return buildJsonObject {
            if (!project.isNullOrBlank()) put("project", JsonPrimitive(project))
            put("requestId", JsonPrimitive("agent/$cascadeId/${now()}/$trajectoryId/${stepIndex + 1}"))
            put("request", request)
            put("model", JsonPrimitive(model))
            put("userAgent", JsonPrimitive("antigravity"))
            put("requestType", JsonPrimitive("agent"))
        }
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
     * 这些在多轮对话里都不变；不要放入整段请求或每次请求都变的 request-id，
     * 那会让每一轮都换上游会话。
     */
    fun sessionScope(alias: String, channelId: String, clientKeyId: String?, sessionId: String?): String =
        alias + "\u0000" + channelId + "\u0000" + clientKeyId.orEmpty() + "\u0000" + sessionId.orEmpty()

    /**
     * 由 [scope] 派生的稳定 sessionId：同一 scope 的每一轮都得到同一个值，因此同一段
     * 对话落在同一个上游会话上。
     *
     * 没有 scope（调用方没给出任何可区分会话的标识）时每次请求都用新值：此时无法判断
     * 两个请求是否属于同一段对话，宁可放弃上游会话连续性，也不让不同调用方共用会话。
     */
    fun sessionId(scope: String?): String {
        if (scope.isNullOrEmpty()) return "-" + (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xff)
        }
        return "-" + (value and Long.MAX_VALUE)
    }

    private fun decorateRequest(
        body: JsonObject,
        model: String,
        effort: String?,
        maxOutputTokens: Long?,
        scope: String?,
        cascadeId: String,
        trajectoryId: String,
        stepIndex: Int,
    ): JsonObject {
        var request = body

        // Antigravity 的 systemInstruction 固定带 role=user。
        val system = request["systemInstruction"] as? JsonObject
        if (system != null && system["role"] == null) {
            request = JsonObject(
                request + ("systemInstruction" to JsonObject(system + ("role" to JsonPrimitive("user")))),
            )
        }

        // Antigravity 对 Gemini 模型总会带 thinkingConfig 与 maxOutputTokens。
        if (model.startsWith("gemini-")) {
            request = request.withGeminiGenerationDefaults(effort, maxOutputTokens)
        }

        val usedClaude = model.startsWith("claude")
        val labels = buildJsonObject {
            (request["labels"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("cascade_id", cascadeId)
            put("root_cascade_id", cascadeId)
            put("trajectory_id", trajectoryId)
            put("request_id", "$trajectoryId-$stepIndex")
            put("last_step_index", stepIndex.toString())
            put("used_claude", usedClaude.toString())
            put("used_claude_conservative", "false")
            put("used_non_gemini_model", (!model.startsWith("gemini")).toString())
        }

        return JsonObject(
            request +
                ("labels" to labels) +
                ("sessionId" to JsonPrimitive(sessionId(scope))),
        )
    }

    private fun JsonObject.withGeminiGenerationDefaults(effort: String?, maxOutputTokens: Long?): JsonObject {
        val config = this["generationConfig"] as? JsonObject ?: JsonObject(emptyMap())
        val thinking = config["thinkingConfig"] as? JsonObject ?: JsonObject(emptyMap())
        val normalizedEffort = effort?.takeIf { it.isNotBlank() }?.let { AntigravityModels.normalizeEffort(it) }
        val mergedThinking = buildJsonObject {
            thinking.forEach { (key, value) -> put(key, value) }
            if (!thinking.containsKey("includeThoughts")) put("includeThoughts", JsonPrimitive(true))
            // thinkingBudget 已经表达了档位；再写 thinkingLevel 会让上游收到两个可能互斥的字段。
            if (
                normalizedEffort != null &&
                !thinking.containsKey("thinkingLevel") &&
                !thinking.containsKey("thinkingBudget")
            ) {
                put("thinkingLevel", thinkingLevel(normalizedEffort))
            }
        }
        val mergedConfig = buildJsonObject {
            config.forEach { (key, value) -> put(key, value) }
            if (!config.containsKey("maxOutputTokens") && maxOutputTokens != null) {
                put("maxOutputTokens", JsonPrimitive(maxOutputTokens))
            }
            put("thinkingConfig", mergedThinking)
        }
        return JsonObject(this + ("generationConfig" to mergedConfig))
    }

    private fun thinkingLevel(normalizedEffort: String): JsonPrimitive = when (normalizedEffort) {
        "xhigh" -> JsonPrimitive(THINKING_LEVEL_XHIGH)
        "high" -> JsonPrimitive("HIGH")
        "medium" -> JsonPrimitive("MEDIUM")
        else -> JsonPrimitive("LOW")
    }

    private fun stableId(kind: String, scope: String?): String =
        if (scope.isNullOrEmpty()) {
            UUID.randomUUID().toString()
        } else {
            UUID.nameUUIDFromBytes(("$kind\u0000$scope").toByteArray()).toString()
        }
}
