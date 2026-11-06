package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.proxy.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 针对上游 Chat Completions 协议自动规范化并补齐 assistant 消息中的 reasoning_content。
 *
 * 背景：
 * DeepSeek 等提供思考模式的模型在 API 规范中强制要求：
 * 在开启思考模式的多轮对话中，如果历史消息中包含 role=assistant 的消息，该消息必须传回 reasoning_content 字段。
 * 若客户端未回传 reasoning_content（或将其合并至 content 的 <think> 标签中），上游会报 400 错误：
 * "The `reasoning_content` in the thinking mode must be passed back to the API."
 *
 * 处理规则：
 * 1. 当请求处于思考模式（显式 reasoning_effort/reasoning）、或者上游模型为思考模型/DeepSeek、
 *    或者消息历史中已存在 reasoning 迹象时触发补齐。
 * 2. 遍历 messages 数组中 role="assistant" 的消息：
 *    - 若已存在有效的 reasoning_content 字符串，保留原样；
 *    - 若存在 reasoning_content=null，替换为空字符串 ""；
 *    - 若不存在 reasoning_content，但存在 reasoning 或 thought 字段，提取其内容填入 reasoning_content；
 *    - 若 content 字符串中包含 <think>...</think> 标签，将标签内文本提取为 reasoning_content，并从 content 中剔除该标签；
 *    - 若均无，则自动补齐 reasoning_content=""。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object ChatReasoningContentNormalizer {
    private val THINK_TAG_REGEX = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    fun normalize(body: JsonObject, upstreamModel: String): JsonObject {
        val messagesElement = body["messages"] as? JsonArray ?: return body
        if (messagesElement.isEmpty()) return body

        if (!shouldNormalize(body, upstreamModel, messagesElement)) {
            return body
        }

        var modified = false
        val newMessages = buildJsonArray {
            messagesElement.forEach { element ->
                val msg = element as? JsonObject
                if (msg == null || msg.string("role") != "assistant") {
                    add(element)
                    return@forEach
                }

                val normalizedMsg = normalizeAssistantMessage(msg)
                if (normalizedMsg != msg) {
                    modified = true
                }
                add(normalizedMsg)
            }
        }

        return if (modified) {
            buildJsonObject {
                body.forEach { (key, value) ->
                    if (key == "messages") {
                        put("messages", newMessages)
                    } else {
                        put(key, value)
                    }
                }
            }
        } else {
            body
        }
    }

    private fun shouldNormalize(
        body: JsonObject,
        upstreamModel: String,
        messages: JsonArray,
    ): Boolean {
        // 1. 显式启用了推理强度 (reasoning_effort 存在)
        val effort = body.string("reasoning_effort")
            ?: (body["reasoning"] as? JsonObject)?.string("effort")
        if (effort != null) {
            if (effort.equals("none", ignoreCase = true)) {
                // 如果显式关闭思考，仅当模型强制包含 "reasoner" 时才需处理
                val lowerModel = upstreamModel.lowercase()
                val requestModel = body.string("model")?.lowercase().orEmpty()
                return lowerModel.contains("reasoner") || requestModel.contains("reasoner")
            }
            return true
        }

        // 2. 模型名称包含已知思考模型或 DeepSeek 标识
        val lowerModel = upstreamModel.lowercase()
        val requestModel = body.string("model")?.lowercase().orEmpty()
        val isThinkingModel = listOf(lowerModel, requestModel).any { name ->
            name.contains("deepseek") ||
                name.contains("reasoner") ||
                name.contains("-r1") ||
                name.contains("r1-") ||
                name.contains("_r1") ||
                name.contains("thinking") ||
                name.contains("qwq")
        }
        if (isThinkingModel) {
            return true
        }

        // 3. 消息历史中存在思考迹象 (含有 reasoning_content、reasoning、thought 或 <think>)
        return messages.any { element ->
            val msg = element as? JsonObject ?: return@any false
            if (msg.string("role") != "assistant") return@any false

            if (msg.containsKey("reasoning_content") ||
                msg.containsKey("reasoning") ||
                msg.containsKey("thought")
            ) {
                return@any true
            }

            val textContent = (msg["content"] as? JsonPrimitive)?.contentOrNull
            textContent != null && textContent.contains("<think", ignoreCase = true)
        }
    }

    private fun normalizeAssistantMessage(msg: JsonObject): JsonObject {
        // 1. 如果已有有效 reasoning_content
        val existingReasoning = msg["reasoning_content"]
        if (existingReasoning != null && existingReasoning !is JsonNull) {
            return msg
        }

        // 2. 检查是否有 reasoning 或 thought 别名字段
        val aliasReasoning = (msg["reasoning"] as? JsonPrimitive)?.contentOrNull
            ?: (msg["thought"] as? JsonPrimitive)?.contentOrNull

        if (aliasReasoning != null) {
            return buildJsonObject {
                msg.forEach { (k, v) ->
                    if (k != "reasoning_content") put(k, v)
                }
                put("reasoning_content", aliasReasoning)
            }
        }

        // 3. 检查 content 中是否包含 <think>...</think>
        val contentElement = msg["content"]
        if (contentElement is JsonPrimitive && contentElement.isString) {
            val contentText = contentElement.content
            val matches = THINK_TAG_REGEX.findAll(contentText).toList()
            if (matches.isNotEmpty()) {
                val extractedThinking = matches.joinToString("\n") { it.groupValues[1].trim() }
                val cleanedContent = THINK_TAG_REGEX.replace(contentText, "").trim()
                return buildJsonObject {
                    msg.forEach { (k, v) ->
                        if (k != "reasoning_content" && k != "content") put(k, v)
                    }
                    put("reasoning_content", extractedThinking)
                    put("content", cleanedContent)
                }
            }
        } else if (contentElement is JsonArray) {
            var extractedThinking = ""
            var contentModified = false
            val newParts = buildJsonArray {
                contentElement.forEach { partElement ->
                    val partObj = partElement as? JsonObject
                    val text = (partObj?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (partObj != null && text != null && THINK_TAG_REGEX.containsMatchIn(text)) {
                        val matches = THINK_TAG_REGEX.findAll(text).toList()
                        val thinkingText = matches.joinToString("\n") { it.groupValues[1].trim() }
                        extractedThinking = if (extractedThinking.isEmpty()) thinkingText else "$extractedThinking\n$thinkingText"
                        val cleanedText = THINK_TAG_REGEX.replace(text, "").trim()
                        contentModified = true
                        add(buildJsonObject {
                            partObj.forEach { (k, v) ->
                                if (k == "text") put("text", cleanedText) else put(k, v)
                            }
                        })
                    } else {
                        add(partElement)
                    }
                }
            }
            if (extractedThinking.isNotEmpty()) {
                return buildJsonObject {
                    msg.forEach { (k, v) ->
                        if (k != "reasoning_content" && k != "content") put(k, v)
                    }
                    put("reasoning_content", extractedThinking)
                    put("content", if (contentModified) newParts else contentElement)
                }
            }
        }

        // 4. 默认补齐空字符串 ""
        return buildJsonObject {
            msg.forEach { (k, v) ->
                if (k != "reasoning_content") put(k, v)
            }
            put("reasoning_content", "")
        }
    }
}
