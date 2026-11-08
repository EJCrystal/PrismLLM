package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.proxy.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
 * 2. 只处理工具轮：assistant 消息带非空 tool_calls，或紧跟在 role=tool 的消息之后的那一条，
 *    工具轮处理完即结束（`afterTool` 清零）。其余 assistant 消息（普通问答）原样保留，
 *    DeepSeek 并不要求它们的思考内容。magpie 把 `afterTool` 保持到下一个 user 消息才清零，
 *    这里只在工具结果后的第一条 assistant 上补值，避免给后续无关轮次也塞字段。
 * 3. 取值优先级：
 *    - 已有 reasoning_content 字符串（含空串）时保留；
 *    - reasoning 字段；
 *    - reasoning_details[].text / summary 拼接；
 *    - content 中的 `<think>...</think>` 标签（PrismLLM 自己的兼容处理，标签会从 content 剔除）；
 *    - 最后回退为单个空格 " "。不能用空串：中转可能丢弃空字段，DeepSeek 的 Responses 侧也会拒绝空
 *      reasoning_text。
 * 4. 从 reasoning 搬移思考内容时删除 reasoning 字段：把两个字段当同一个读的自建 DeepSeek 会以
 *    "duplicate field reasoning" 拒绝整条请求。已有 reasoning_content 的消息若同时带 reasoning，
 *    也删掉 reasoning 并保留原值。reasoning_details 是结构化列表，保留原样。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object ChatReasoningContentNormalizer {
    private val THINK_TAG_REGEX = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    /** 中转常把思考模型改名，这些词根仍能识别；`deepseek` 单独判断，因为 host 也要看。 */
    private val REASONING_MODEL_MARKERS = listOf("reasoner", "r1", "qwq", "thinking")

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
                // 工具轮在第一条 assistant 消息后就结束：它是 tool 结果的那条回答。magpie 把
                // afterTool 一直保持到 user，会让之后所有不带 tool_calls 的 assistant 都被补字段，
                // 扩大注入面；这里只认「紧跟 tool」的那一条。
                afterTool = false
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
        if (own != null) {
            // 已有思考内容时值不变，但 reasoning 仍要删：两个字段并存的自建 DeepSeek 会以
            // "duplicate field reasoning" 拒绝整条请求。
            return if (msg.containsKey("reasoning")) rebuild(msg) {} else msg
        }

        // 2. reasoning / thought / reasoning_details 里带回的思考内容。
        val alias = aliasReasoning(msg)
        if (alias != null) {
            return rebuild(msg) { put("reasoning_content", alias) }
        }

        // 3. content 中的 <think>...</think>。
        val extracted = extractThinkTags(msg)
        if (extracted != null) return extracted

        // 4. 回退为单个空格。
        return rebuild(msg) {
            put("reasoning_content", NO_REASONING)
        }
    }

    /** 重建消息并去掉 reasoning（reasoning 与 reasoning_content 是同一个字段的两种拼写），再用 [extra] 追加改写后的字段。 */
    private inline fun rebuild(msg: JsonObject, extra: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            msg.forEach { (k, v) ->
                if (k != "reasoning") put(k, v)
            }
            extra()
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
            // Qwen3 非思考模式会输出空的 <think></think>：标签照常剔除，但思考内容为空时
            // 必须回退为 NO_REASONING，否则又写出空串，中转会把它丢掉。
            val extracted = matches.joinToString("\n") { it.groupValues[1].trim() }.ifBlank { NO_REASONING }
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
        // 与字符串分支一致：以「标签是否出现」决定是否改写，思考内容为空时回退为 NO_REASONING。
        if (!contentModified) return null
        return buildJsonObject {
            msg.forEach { (k, v) ->
                if (k != "reasoning_content" && k != "reasoning" && k != "content") put(k, v)
            }
            put("reasoning_content", extracted.ifBlank { NO_REASONING })
            put("content", newParts)
        }
    }
}
