package dev.soven.prismllm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 针对上游 Messages 协议，在 DeepSeek 的思考模型工具轮里补齐 thinking 块。
 *
 * 背景：
 * DeepSeek 的 Anthropic 端点要求工具循环中的 assistant 轮把 thinking 原样带回，否则整条请求
 * 被拒："The `content[].thinking` in the thinking mode must be passed back to the API"。
 * 另一家模型（例如 Claude）产生的 `redacted_thinking` 它也不认识，会以 422 拒绝。
 *
 * 处理规则（对齐 magpie 的 `withThinkingOnToolTurns`）：
 * 1. 是否处理由上游决定：host 或上游模型名含 `deepseek` 时处理；官方 Anthropic 端点与
 *    `claude-*` 模型不处理，它们按自己的签名规则校验，塞占位 thinking 会被拒绝。
 * 2. 只补工具轮：assistant 消息带 `tool_use`，或紧跟一条只含 `tool_result` 的 user 消息。
 *    已有 thinking 的轮次保持原样。
 * 3. 没有 thinking 时在内容开头插入 `{"type":"thinking","thinking":" ","signature":""}`。
 *    用单个空格而不是空串：中转可能丢弃空字段，DeepSeek 也接受这个占位（magpie 的 noReasoning）。
 * 4. `redacted_thinking` 是别家模型的密封推理，DeepSeek 读不懂：该轮已有 thinking 时丢弃，
 *    否则替换为上述占位 thinking。
 *
 * @author 文杰
 * @since 2026-11-11
 */
object MessagesThinkingNormalizer {
    /** magpie 的 noReasoning：空串会被中转丢掉。 */
    private const val NO_REASONING = " "

    private val ANTHROPIC_MODEL = Regex("(?i)(?:^|[/.:-])claude-")

    fun normalize(body: JsonObject, upstreamModel: String, baseUrl: String): JsonObject {
        val host = hostOf(baseUrl)
        if (!replaysThinking(host, upstreamModel)) return body
        val messages = body["messages"] as? JsonArray ?: return body
        if (messages.isEmpty()) return body

        var modified = false
        var afterTool = false
        val rewritten = buildJsonArray {
            messages.forEach { element ->
                val message = element as? JsonObject
                val role = message?.string("role")
                if (message == null || role != "assistant") {
                    afterTool = role == "user" && isToolResultsOnly(message)
                    add(element)
                    return@forEach
                }
                val after = afterTool
                afterTool = false
                val content = message["content"] as? JsonArray ?: run {
                    add(element)
                    return@forEach
                }
                var thought = false
                var called = false
                var edited = false
                val blocks = mutableListOf<JsonElement>()
                content.forEach { partElement ->
                    when ((partElement as? JsonObject)?.string("type")) {
                        "thinking" -> {
                            thought = true
                            blocks += partElement
                        }
                        "tool_use" -> {
                            called = true
                            blocks += partElement
                        }
                        "redacted_thinking" -> {
                            edited = true
                            if (!thought) {
                                thought = true
                                blocks += placeholderThinking()
                            }
                            // 已有 thinking 时这个块对 DeepSeek 无意义，直接丢弃。
                        }
                        else -> blocks += partElement
                    }
                }
                if ((called || after) && !thought) {
                    edited = true
                    blocks.add(0, placeholderThinking())
                }
                if (!edited) {
                    add(element)
                    return@forEach
                }
                modified = true
                add(JsonObject(message + ("content" to JsonArray(blocks))))
            }
        }
        if (!modified) return body
        return buildJsonObject {
            body.forEach { (key, value) -> put(key, if (key == "messages") rewritten else value) }
        }
    }

    private fun replaysThinking(host: String, upstreamModel: String): Boolean {
        if (host == "api.anthropic.com" || ANTHROPIC_MODEL.containsMatchIn(upstreamModel)) return false
        return host.contains("deepseek") || upstreamModel.lowercase().contains("deepseek")
    }

    private fun hostOf(baseUrl: String): String =
        runCatching { java.net.URI(baseUrl).host }.getOrNull()?.lowercase()
            ?: baseUrl.lowercase()

    private fun isToolResultsOnly(message: JsonObject?): Boolean {
        val content = message?.get("content") as? JsonArray ?: return false
        if (content.isEmpty()) return false
        return content.all { (it as? JsonObject)?.string("type") == "tool_result" }
    }

    private fun placeholderThinking(): JsonObject = buildJsonObject {
        put("type", "thinking")
        put("thinking", NO_REASONING)
        put("signature", "")
    }
}
