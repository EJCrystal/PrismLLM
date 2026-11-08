package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.proxy.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 针对上游 Chat Completions 协议，在思考模型的工具轮里补齐 assistant 消息的 reasoning_content。
 *
 * 背景：
 * DeepSeek 等提供思考模式的模型要求：工具调用轮（该轮 assistant 消息带 tool_calls，或紧跟 tool 结果
 * 之后再作答）的 assistant 消息必须回传 reasoning_content。客户端没有保留思考内容时（例如工具调用的
 * 思考在上一轮被丢弃，或该轮由另一个模型产生），上游会返回 400：
 * "The `reasoning_content` in the thinking mode must be passed back to the API"。
 *
 * 处理规则（对齐 magpie 的 `withReasoningOnToolTurns` 与 `asReasoningContent`）：
 * 1. 是否处理由上游决定，无需配置：
 *    - host 含 `deepseek`，或上游模型名含 `deepseek`，或模型名命中 `reasoner`、`r1`、`qwq`、`thinking`
 *      等中转常见命名时处理；
 *    - host 指向 Mistral 时完全不处理：它会以 extra_forbidden 拒绝 reasoning_content。
 * 2. 只处理工具轮：assistant 消息带非空 tool_calls，或紧跟在 role=tool 的消息之后；
 *    其余 assistant 消息（普通问答）原样保留，DeepSeek 并不要求它们的思考内容。
 * 3. 取值优先级：
 *    - 已有 reasoning_content 字符串（含空串）时保留；
 *    - reasoning 字段；
 *    - reasoning_details[].text / summary 拼接；
 *    - content 中的 `<think>...</think>` 标签（PrismLLM 自己的兼容处理，标签会从 content 剔除）；
 *    - 最后回退为单个空格 " "。不能用空串：中转可能丢弃空字段，DeepSeek 的 Responses 侧也会拒绝空
 *      reasoning_text。
 * 4. 从 reasoning 搬移思考内容时删除 reasoning 字段：把两个字段当同一个读的自建 DeepSeek 会以
 *    "duplicate field reasoning" 拒绝整条请求。reasoning_details 是结构化列表，保留原样。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object ChatReasoningContentNormalizer {
    private val THINK_TAG_REGEX = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    /** 中转常把思考模型改名，这些词根仍能识别；`deepseek` 单独判断，因为 host 也要看。 */
    private val REASONING_MODEL_MARKERS = listOf("reasoner", "-r1", "r1-", "_r1", "qwq", "thinking")

    /** magpie 的 noReasoning：空串会被中转丢掉，DeepSeek 的 Responses 侧也拒绝空 reasoning_text。 */
    private const val NO_REASONING = " "

    private fun isReasoningUpstream(host: String, upstreamModel: String, body: JsonObject): Boolean {
        if (host.contains("mistral")) return false
        if (host.contains("deepseek")) return true
        val models = listOf(upstreamModel, body.string("model").orEmpty())
        return models.any { name ->
            val lower = name.lowercase()
            lower.contains("deepseek") || REASONING_MODEL_MARKERS.any(lower::contains)
        }
    }

    private fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl).host }.getOrNull()?.lowercase()
            ?: baseUrl.lowercase()

    fun normalize(body: JsonObject, upstreamModel: String, baseUrl: String): JsonObject {
        val messagesElement = body["messages"] as? JsonArray ?: return body
        if (messagesElement.isEmpty()) return body

        val host = hostOf(baseUrl)
        if (!isReasoningUpstream(host, upstreamModel, body)) return body

        var modified = false
        var afterTool = false
        val newMessages = buildJsonArray {
            messagesElement.forEach { element ->
                val msg = element as? JsonObject
                val role = msg?.string("role")
                when (role) {
                    "tool" -> afterTool = true
                    "user" -> afterTool = false
                }
                if (msg == null || role != "assistant") {
                    add(element)
                    return@forEach
                }
                val toolCalls = msg["tool_calls"] as? JsonArray
                val isToolTurn = !toolCalls.isNullOrEmpty() || afterTool
                // 与 magpie 一致：afterTool 只由 user 消息清零，连续 assistant 消息都算同一条工具链。
                if (!isToolTurn) {
                    add(element)
                    return@forEach
                }
                val normalizedMsg = normalizeAssistantMessage(msg)
                if (normalizedMsg != msg) modified = true
                add(normalizedMsg)
            }
        }

        return if (modified) {
            buildJsonObject {
                body.forEach { (key, value) ->
                    if (key == "messages") put("messages", newMessages) else put(key, value)
                }
            }
        } else {
            body
        }
    }

    private fun normalizeAssistantMessage(msg: JsonObject): JsonObject {
        // 1. 已有 reasoning_content 字符串（含空串）时保留；null 视为缺失，按后续规则补齐。
        val own = (msg["reasoning_content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (own != null) return msg

        // 2. reasoning / thought / reasoning_details 里带回的思考内容。
        val alias = aliasReasoning(msg)
        if (alias != null) {
            return buildJsonObject {
                msg.forEach { (k, v) ->
                    // reasoning 与 reasoning_content 是同一个字段的两种拼写，只留一个。
                    if (k != "reasoning_content" && k != "reasoning") put(k, v)
                }
                put("reasoning_content", alias)
            }
        }

        // 3. content 中的 <think>...</think>。
        val extracted = extractThinkTags(msg)
        if (extracted != null) return extracted

        // 4. 回退为单个空格。
        return buildJsonObject {
            msg.forEach { (k, v) ->
                if (k != "reasoning_content") put(k, v)
            }
            put("reasoning_content", NO_REASONING)
        }
    }

    private fun aliasReasoning(msg: JsonObject): String? {
        (msg["reasoning"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        (msg["thought"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        val details = msg["reasoning_details"] as? JsonArray ?: return null
        val text = details.joinToString("") { part ->
            val item = part as? JsonObject ?: return@joinToString ""
            (item["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (item["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: ""
        }
        return text.takeIf { it.isNotEmpty() }
    }

    private fun extractThinkTags(msg: JsonObject): JsonObject? {
        val contentElement = msg["content"] as? JsonPrimitive
        if (contentElement != null && contentElement.isString) {
            val contentText = contentElement.content
            val matches = THINK_TAG_REGEX.findAll(contentText).toList()
            if (matches.isEmpty()) return null
            val extracted = matches.joinToString("\n") { it.groupValues[1].trim() }
            val cleaned = THINK_TAG_REGEX.replace(contentText, "").trim()
            return buildJsonObject {
                msg.forEach { (k, v) ->
                    if (k != "reasoning_content" && k != "reasoning" && k != "content") put(k, v)
                }
                put("reasoning_content", extracted)
                put("content", cleaned)
            }
        }
        val contentArray = msg["content"] as? JsonArray ?: return null
        var extracted = ""
        var contentModified = false
        val newParts = buildJsonArray {
            contentArray.forEach { partElement ->
                val part = partElement as? JsonObject
                val text = (part?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (part != null && text != null && THINK_TAG_REGEX.containsMatchIn(text)) {
                    val thinking = THINK_TAG_REGEX.findAll(text).joinToString("\n") { it.groupValues[1].trim() }
                    extracted = if (extracted.isEmpty()) thinking else "$extracted\n$thinking"
                    contentModified = true
                    add(buildJsonObject {
                        part.forEach { (k, v) ->
                            if (k == "text") put("text", THINK_TAG_REGEX.replace(text, "").trim()) else put(k, v)
                        }
                    })
                } else {
                    add(partElement)
                }
            }
        }
        if (extracted.isEmpty()) return null
        return buildJsonObject {
            msg.forEach { (k, v) ->
                if (k != "reasoning_content" && k != "reasoning" && k != "content") put(k, v)
            }
            put("reasoning_content", extracted)
            put("content", if (contentModified) newParts else contentArray)
        }
    }
}
