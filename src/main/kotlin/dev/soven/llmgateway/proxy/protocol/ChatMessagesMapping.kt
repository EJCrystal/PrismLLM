package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun messagesToChatRequest(request: JsonObject): JsonObject = buildJsonObject {
    validateMessagesRequest(request)
    request.string("model")?.let { put("model", it) }
    request.boolean("stream")?.let { put("stream", it) }
    request.number("temperature")?.let { put("temperature", it) }
    request.number("top_p")?.let { put("top_p", it) }
    request.number("max_tokens")?.let { put("max_tokens", it) }
    request.stringArray("stop_sequences")?.let { values ->
        put("stop", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
    (request["metadata"] as? JsonObject)?.string("user_id")?.let { put("user", it) }
    request.thinkingEffort()?.let { put("reasoning_effort", it) }

    val messages = mutableListOf<JsonObject>()
    request.textContentStrict("system", "system").takeIf { it.isNotBlank() }?.let { system ->
        messages += buildJsonObject {
            put("role", "system")
            put("content", system)
        }
    }
    request.array("messages").forEachIndexed { index, element ->
        val message = element as? JsonObject ?: unsupported("messages[$index]", "消息必须是对象")
        val role = message.string("role") ?: unsupported("messages[$index].role", "缺少 role")
        val text = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val toolCalls = mutableListOf<JsonObject>()
        val toolResults = mutableListOf<JsonObject>()
        when (val content = message["content"]) {
            JsonNull -> Unit
            is JsonPrimitive -> text += content.content
            is JsonArray -> content.forEachIndexed { partIndex, partElement ->
                val part = partElement as? JsonObject
                    ?: unsupported("messages[$index].content[$partIndex]", "内容块必须是对象")
                when (part.string("type")) {
                    "text" -> text += part.string("text").orEmpty()
                    // 思考历史转为 Chat 的 reasoning_content；加密的 redacted_thinking 无对应表示，直接丢弃。
                    "thinking" -> part.string("thinking")?.takeIf { it.isNotEmpty() }?.let { reasoning += it }
                    "redacted_thinking" -> Unit
                    "tool_use" -> toolCalls += chatToolCallFromAnthropic(part, "messages[$index].content[$partIndex]")
                    "tool_result" -> toolResults += chatToolMessageFromAnthropic(part, "messages[$index].content[$partIndex]")
                    "image", "document" -> unsupported(
                        "messages[$index].content[$partIndex].type",
                        "第一版不支持 ${part.string("type")} 内容",
                    )
                    else -> unsupported("messages[$index].content[$partIndex].type", "未知内容类型")
                }
            }
            JsonNull, null -> Unit
            else -> unsupported("messages[$index].content", "无法识别的内容")
        }
        messages += toolResults
        // reasoning_content 只属于 assistant 消息；其它角色上的思考块按无效输入忽略。
        val reasoningContent = reasoning.joinToString("\n").takeIf { it.isNotEmpty() && role == "assistant" }
        if (text.isNotEmpty() || toolCalls.isNotEmpty() || reasoningContent != null) {
            messages += buildJsonObject {
                put("role", role)
                if (text.isNotEmpty()) put("content", text.joinToString("\n")) else put("content", JsonNull)
                if (reasoningContent != null) put("reasoning_content", reasoningContent)
                if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
            }
        }
    }
    put("messages", JsonArray(messages))
    request["tools"]?.takeIf { it !is JsonNull }?.let { toolsElement ->
        val tools = toolsElement as? JsonArray ?: unsupported("tools", "tools 必须是数组")
        put("tools", JsonArray(tools.mapIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            validateMessagesTool(tool, "tools[$index]")
            val name = tool.string("name") ?: unsupported("tools[$index].name", "工具缺少 name")
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", name)
                    tool.string("description")?.let { put("description", it) }
                    put("parameters", tool["input_schema"] ?: buildJsonObject { put("type", "object") })
                }
            }
        }))
    }
    request["tool_choice"]?.takeIf { it !is JsonNull }?.let { choice -> put("tool_choice", anthropicToolChoiceToChat(choice as? JsonObject)) }
    if ((request["tool_choice"] as? JsonObject)?.boolean("disable_parallel_tool_use") == true) {
        put("parallel_tool_calls", false)
    }
}

internal fun chatToMessagesRequest(chat: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    rejectUnknownFields(chat, chatRequestFields, "request")
    put("model", upstreamModel)
    chat.boolean("stream")?.let { put("stream", it) }
    chat.number("temperature")?.let { put("temperature", it) }
    chat.number("top_p")?.let { put("top_p", it) }
    val requestedMaxTokens = chat.long("max_tokens") ?: chat.long("max_completion_tokens")
    val reasoningEffort = chat.string("reasoning_effort")
    if (reasoningEffort != null) {
        val maxTokensField = if (chat.long("max_tokens") != null) "max_tokens" else "max_completion_tokens"
        val (thinking, maxTokens) = thinkingAndMaxTokens(reasoningEffort, requestedMaxTokens, maxTokensField)
        put("max_tokens", maxTokens)
        put("thinking", thinking)
    } else {
        put("max_tokens", requestedMaxTokens ?: 4096L)
    }
    chat.stringOrStringArray("stop")?.let { values -> put("stop_sequences", JsonArray(values.map(::JsonPrimitive))) }
    chat.string("user")?.let { putJsonObject("metadata") { put("user_id", it) } }
    if (chat["response_format"].let { it != null && it !is JsonNull }) {
        unsupported("response_format", "Anthropic Messages 不支持 OpenAI 结构化输出参数")
    }

    val system = mutableListOf<String>()
    val messages = mutableListOf<JsonObject>()
    val pendingToolResults = mutableListOf<JsonObject>()
    fun flushToolResults() {
        if (pendingToolResults.isEmpty()) return
        messages += buildJsonObject {
            put("role", "user")
            put("content", JsonArray(pendingToolResults.toList()))
        }
        pendingToolResults.clear()
    }
    chat.array("messages").forEachIndexed { index, element ->
        val message = element as? JsonObject ?: unsupported("messages[$index]", "消息必须是对象")
        when (message.string("role")) {
            "system", "developer" -> {
                flushToolResults()
                system += message.textContentStrict("content", "messages[$index].content")
            }
            "user", "assistant" -> {
                flushToolResults()
                chatMessageToAnthropic(message, "messages[$index]")
                    .takeIf { it.array("content").isNotEmpty() }
                    ?.let { messages += it }
            }
            "tool" -> pendingToolResults += buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", message.string("tool_call_id") ?: unsupported("messages[$index].tool_call_id", "tool 消息缺少 tool_call_id"))
                put("content", message.textContentStrict("content", "messages[$index].content"))
            }
            else -> unsupported("messages[$index].role", "不支持的消息角色")
        }
    }
    flushToolResults()
    if (system.isNotEmpty()) put("system", system.joinToString("\n"))
    put("messages", JsonArray(messages))
    chat["tools"]?.takeIf { it !is JsonNull }?.let { toolsElement ->
        val tools = toolsElement as? JsonArray ?: unsupported("tools", "tools 必须是数组")
        put("tools", JsonArray(tools.mapIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            val function = tool["function"] as? JsonObject ?: unsupported("tools[$index].function", "缺少 function")
            buildJsonObject {
                put("name", function.string("name") ?: unsupported("tools[$index].function.name", "工具缺少 name"))
                function.string("description")?.let { put("description", it) }
                put("input_schema", function["parameters"] ?: buildJsonObject { put("type", "object") })
            }
        }))
    }
    val disableParallelToolUse = chat.boolean("parallel_tool_calls") == false
    val hasTools = (chat["tools"] as? JsonArray)?.isNotEmpty() == true
    val toolChoice = chat["tool_choice"]?.takeIf { it !is JsonNull }
    if (toolChoice != null) {
        put("tool_choice", chatToolChoiceToAnthropic(toolChoice, disableParallelToolUse))
    } else if (disableParallelToolUse && hasTools) {
        putJsonObject("tool_choice") {
            put("type", "auto")
            put("disable_parallel_tool_use", true)
        }
    }
}

internal fun messagesToChatResponse(response: JsonObject, model: String): JsonObject {
    val content = mutableListOf<String>()
    val reasoning = mutableListOf<String>()
    val toolCalls = mutableListOf<JsonObject>()
    response.array("content").forEachIndexed { index, element ->
        val block = element as? JsonObject ?: unsupported("content[$index]", "内容块必须是对象")
        when (block.string("type")) {
            "text" -> content += block.string("text").orEmpty()
            "tool_use" -> toolCalls += buildJsonObject {
                put("id", block.string("id").orEmpty())
                put("type", "function")
                putJsonObject("function") {
                    put("name", block.string("name").orEmpty())
                    put("arguments", block["input"]?.takeIf { it !is JsonNull }?.toString() ?: "{}")
                }
            }
            "thinking" -> block.string("thinking")?.takeIf { it.isNotEmpty() }?.let { reasoning += it }
            "redacted_thinking" -> Unit
            else -> unsupported("content[$index].type", "未知内容块")
        }
    }
    return buildJsonObject {
        put("id", response.string("id") ?: "chatcmpl-${System.currentTimeMillis()}")
        put("object", "chat.completion")
        put("created", System.currentTimeMillis() / 1000)
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                putJsonObject("message") {
                    put("role", "assistant")
                    if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.joinToString("\n"))
                    put("content", content.joinToString("\n").ifEmpty { null })
                    if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                }
                put("finish_reason", anthropicStopToChat(response.string("stop_reason")))
            })
        })
        messagesUsageToChat(response["usage"] as? JsonObject)?.let { put("usage", it) }
    }
}

internal fun chatToMessagesResponse(chat: JsonObject, model: String): JsonObject {
    val choice = chat.array("choices").firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    val content = mutableListOf<JsonObject>()
    message?.string("content")?.takeIf { it.isNotEmpty() }?.let { text ->
        content += buildJsonObject {
            put("type", "text")
            put("text", text)
        }
    }
    message?.string("refusal")?.takeIf { it.isNotEmpty() }?.let { refusal ->
        content += buildJsonObject {
            put("type", "text")
            put("text", refusal)
        }
    }
    (message?.get("tool_calls") as? JsonArray)?.forEachIndexed { index, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        val function = call["function"] as? JsonObject ?: return@forEachIndexed
        content += buildJsonObject {
            put("type", "tool_use")
            put("id", call.string("id") ?: "toolu_$index")
            put("name", function.string("name").orEmpty())
            put("input", parseJsonObjectOrEmpty(function.string("arguments")))
        }
    }
    return buildJsonObject {
        put("id", chat.string("id") ?: "msg_${System.currentTimeMillis()}")
        put("type", "message")
        put("role", "assistant")
        put("model", model)
        put("content", JsonArray(content))
        put("stop_reason", chatFinishToAnthropic(choice?.string("finish_reason")))
        put("stop_sequence", JsonNull)
        chatUsageToMessages(chat["usage"] as? JsonObject)?.let { put("usage", it) }
    }
}

internal fun chatMessageToAnthropic(message: JsonObject, path: String): JsonObject {
    val role = message.string("role") ?: "user"
    val blocks = mutableListOf<JsonObject>()
    when (val content = message["content"]) {
        JsonNull -> Unit
        is JsonPrimitive -> if (content.content.isNotEmpty()) blocks += buildJsonObject {
            put("type", "text")
            put("text", content.content)
        }
        is JsonArray -> content.forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: unsupported("$path.content[$index]", "内容块必须是对象")
            when (obj.string("type")) {
                "text" -> obj.string("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                    blocks += buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                "image_url", "input_image", "input_file", "input_audio" -> unsupported("$path.content[$index].type", "第一版不支持多模态内容")
                else -> unsupported("$path.content[$index].type", "第一版不支持该 Chat 内容")
            }
        }
        JsonNull, null -> Unit
        else -> unsupported("$path.content", "无法识别的内容")
    }
    (message["tool_calls"] as? JsonArray)?.forEachIndexed { index, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        val function = call["function"] as? JsonObject ?: return@forEachIndexed
        blocks += buildJsonObject {
            put("type", "tool_use")
            put("id", call.string("id") ?: "toolu_$index")
            put("name", function.string("name").orEmpty())
            put("input", parseJsonObjectOrEmpty(function.string("arguments")))
        }
    }
    return buildJsonObject {
        put("role", role)
        put("content", JsonArray(blocks))
    }
}

internal fun chatToolCallFromAnthropic(block: JsonObject, path: String): JsonObject = buildJsonObject {
    put("id", block.string("id") ?: unsupported("$path.id", "tool_use 缺少 id"))
    put("type", "function")
    putJsonObject("function") {
        put("name", block.string("name") ?: unsupported("$path.name", "tool_use 缺少 name"))
        put("arguments", block["input"]?.takeIf { it !is JsonNull }?.toString() ?: "{}")
    }
}

internal fun chatToolMessageFromAnthropic(block: JsonObject, path: String): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", block.string("tool_use_id") ?: unsupported("$path.tool_use_id", "tool_result 缺少 tool_use_id"))
    put("content", block.toolResultContent("$path.content"))
}

/** Anthropic tool_result 的 content 既可以是字符串，也可以是 text/image 内容块数组。 */

internal fun anthropicToolChoiceToChat(choice: JsonObject?): JsonElement {
    if (choice == null) return JsonPrimitive("auto")
    return when (choice.string("type")) {
        "auto" -> JsonPrimitive("auto")
        "any" -> JsonPrimitive("required")
        "none" -> JsonPrimitive("none")
        "tool" -> buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", choice.string("name").orEmpty()) }
        }
        else -> JsonPrimitive("auto")
    }
}

internal fun chatToolChoiceToAnthropic(choice: JsonElement, disableParallelToolUse: Boolean = false): JsonElement {
    if (choice is JsonPrimitive) {
        return buildJsonObject {
            put("type", when (choice.contentOrNull) {
                "required" -> "any"
                "none" -> "none"
                else -> "auto"
            })
            if (disableParallelToolUse) put("disable_parallel_tool_use", true)
        }
    }
    val obj = choice as? JsonObject ?: return buildJsonObject {
        put("type", "auto")
        if (disableParallelToolUse) put("disable_parallel_tool_use", true)
    }
    val function = obj["function"] as? JsonObject
    return if (function != null) buildJsonObject {
        put("type", "tool")
        put("name", function.string("name").orEmpty())
        if (disableParallelToolUse) put("disable_parallel_tool_use", true)
    } else buildJsonObject {
        put("type", "auto")
        if (disableParallelToolUse) put("disable_parallel_tool_use", true)
    }
}

internal fun messagesUsageToChat(usage: JsonObject?): JsonObject? = TokenUsage.fromMessages(usage)?.toChat()

internal fun chatUsageToMessages(usage: JsonObject?): JsonObject? = TokenUsage.fromChat(usage)?.toMessages()

internal fun anthropicStopToChat(stop: String?): String = when (stop) {
    "max_tokens" -> "length"
    "tool_use" -> "tool_calls"
    "refusal" -> "content_filter"
    else -> "stop"
}

internal fun chatFinishToAnthropic(finish: String?): String = when (finish) {
    "length" -> "max_tokens"
    "tool_calls" -> "tool_use"
    "content_filter" -> "refusal"
    else -> "end_turn"
}
