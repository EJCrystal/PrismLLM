package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun messagesToResponsesRequest(request: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateMessagesRequest(request)
    put("model", upstreamModel)
    listOf("stream", "temperature", "top_p").forEach { field -> request[field]?.let { put(field, it) } }
    request.number("max_tokens")?.let { put("max_output_tokens", it) }
    if (request["stop_sequences"] != null) unsupported("stop_sequences", "Responses 不支持 stop_sequences")
    (request["metadata"] as? JsonObject)?.string("user_id")?.let { put("user", it) }
    request.thinkingEffort()?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
    request.textContentStrict("system", "system").takeIf { it.isNotBlank() }?.let { put("instructions", it) }

    val input = mutableListOf<JsonObject>()
    request.array("messages").forEachIndexed { index, element ->
        val path = "messages[$index]"
        val message = element as? JsonObject ?: unsupported(path, "消息必须是对象")
        val role = message.string("role") ?: unsupported("$path.role", "缺少 role")
        if (role !in setOf("user", "assistant")) unsupported("$path.role", "不支持的消息角色")
        val text = mutableListOf<JsonObject>()
        fun flushText() {
            if (text.isEmpty()) return
            input += buildJsonObject {
                put("type", "message")
                put("role", role)
                put("content", JsonArray(text.toList()))
            }
            text.clear()
        }
        when (val content = message["content"]) {
            is JsonPrimitive -> text += responseTextBlock(content.content, role)
            is JsonArray -> content.forEachIndexed { partIndex, partElement ->
                val partPath = "$path.content[$partIndex]"
                val part = partElement as? JsonObject ?: unsupported(partPath, "内容块必须是对象")
                when (part.string("type")) {
                    "text" -> text += responseTextBlock(part.string("text").orEmpty(), role)
                    "tool_use" -> {
                        flushText()
                        input += buildJsonObject {
                            put("type", "function_call")
                            put("call_id", part.string("id") ?: unsupported("$partPath.id", "tool_use 缺少 id"))
                            put("name", part.string("name") ?: unsupported("$partPath.name", "tool_use 缺少 name"))
                            put("arguments", part["input"]?.takeIf { it != JsonNull }?.toString() ?: "{}")
                        }
                    }
                    "tool_result" -> {
                        flushText()
                        input += buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", part.string("tool_use_id") ?: unsupported("$partPath.tool_use_id", "tool_result 缺少 tool_use_id"))
                            put("output", part.toolResultContent("$partPath.content"))
                        }
                    }
                    else -> unsupported("$partPath.type", "第一版不支持该 Messages 内容类型")
                }
            }
            JsonNull, null -> Unit
            else -> unsupported("$path.content", "无法识别的内容")
        }
        flushText()
    }
    put("input", JsonArray(input))
    request["tools"]?.let { element ->
        val tools = element as? JsonArray ?: unsupported("tools", "tools 必须是数组")
        put("tools", JsonArray(tools.mapIndexed { index, value ->
            val tool = value as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            validateMessagesTool(tool, "tools[$index]")
            buildJsonObject {
                put("type", "function")
                put("name", tool.string("name") ?: unsupported("tools[$index].name", "工具缺少 name"))
                tool.string("description")?.let { put("description", it) }
                put("parameters", tool["input_schema"] ?: buildJsonObject { put("type", "object") })
            }
        }))
    }
    request["tool_choice"]?.let { element ->
        val choice = element as? JsonObject
        put("tool_choice", when (choice?.string("type")) {
            "any" -> JsonPrimitive("required")
            "none" -> JsonPrimitive("none")
            "tool" -> buildJsonObject { put("type", "function"); put("name", choice.string("name").orEmpty()) }
            else -> JsonPrimitive("auto")
        })
        if (choice?.boolean("disable_parallel_tool_use") == true) put("parallel_tool_calls", false)
    }
}

internal fun responsesToMessagesRequest(request: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateResponsesRequest(request)
    put("model", upstreamModel)
    listOf("stream", "temperature", "top_p").forEach { field -> request[field]?.let { put(field, it) } }
    val requestedMaxTokens = request.long("max_output_tokens")
    val reasoningEffort = (request["reasoning"] as? JsonObject)?.string("effort")
    if (reasoningEffort != null) {
        val (thinking, maxTokens) = thinkingAndMaxTokens(reasoningEffort, requestedMaxTokens, "max_output_tokens")
        put("max_tokens", maxTokens)
        put("thinking", thinking)
    } else {
        put("max_tokens", requestedMaxTokens ?: 4096L)
    }
    (request.string("user") ?: (request["metadata"] as? JsonObject)?.string("user_id"))?.let {
        putJsonObject("metadata") { put("user_id", it) }
    }
    if ((request["text"] as? JsonObject)?.get("format") != null) {
        unsupported("text.format", "Anthropic Messages 不支持 OpenAI 结构化输出参数")
    }
    val system = mutableListOf<String>()
    request.string("instructions")?.takeIf { it.isNotBlank() }?.let { system += it }
    val messages = mutableListOf<JsonObject>()
    val toolResults = mutableListOf<JsonObject>()
    fun flushToolResults() {
        if (toolResults.isEmpty()) return
        messages += messageWithBlocks("user", toolResults.toList())
        toolResults.clear()
    }
    when (val input = request["input"]) {
        is JsonPrimitive -> messages += messageWithBlocks("user", listOf(messageTextBlock(input.content)))
        is JsonArray -> input.forEachIndexed { index, element ->
            val path = "input[$index]"
            val item = element as? JsonObject ?: unsupported(path, "输入项必须是对象")
            when (item.string("type")) {
                "message", null -> {
                    flushToolResults()
                    val role = item.string("role") ?: "user"
                    val text = item.textContentStrict("content", "$path.content")
                    when (role) {
                        "system", "developer" -> system += text
                        "user", "assistant" -> messages += messageWithBlocks(role, listOf(messageTextBlock(text)))
                        else -> unsupported("$path.role", "不支持的消息角色")
                    }
                }
                "function_call" -> {
                    flushToolResults()
                    messages += messageWithBlocks("assistant", listOf(buildJsonObject {
                        put("type", "tool_use")
                        put("id", item.string("call_id") ?: item.string("id") ?: unsupported("$path.call_id", "function_call 缺少 call_id"))
                        put("name", item.string("name") ?: unsupported("$path.name", "function_call 缺少 name"))
                        put("input", parseJsonObjectOrEmpty(item.string("arguments")))
                    }))
                }
                "function_call_output" -> toolResults += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", item.string("call_id") ?: unsupported("$path.call_id", "function_call_output 缺少 call_id"))
                    put("content", item.textContentStrict("output", "$path.output"))
                }
                else -> unsupported("$path.type", "第一版不支持 Responses 输入项 "+item.string("type"))
            }
        }
        JsonNull, null -> Unit
        else -> unsupported("input", "input 必须是字符串或数组")
    }
    flushToolResults()
    if (system.isNotEmpty()) put("system", system.joinToString("\n"))
    put("messages", JsonArray(messages))
    request["tools"]?.let { element ->
        val tools = element as? JsonArray ?: unsupported("tools", "tools 必须是数组")
        put("tools", JsonArray(tools.mapIndexed { index, value ->
            val tool = value as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            if (tool.string("type") != "function") unsupported("tools[$index].type", "第一版不支持内置工具")
            buildJsonObject {
                put("name", tool.string("name") ?: unsupported("tools[$index].name", "工具缺少 name"))
                tool.string("description")?.let { put("description", it) }
                put("input_schema", tool["parameters"] ?: buildJsonObject { put("type", "object") })
            }
        }))
    }
    val choice = request["tool_choice"]
    val disableParallel = request.boolean("parallel_tool_calls") == false
    if (choice != null || disableParallel && (request["tools"] as? JsonArray)?.isNotEmpty() == true) {
        putJsonObject("tool_choice") {
            val choiceObject = choice as? JsonObject
            val type = when {
                choiceObject?.string("type") == "function" -> "tool"
                choiceObject?.string("type") == "required" || (choice as? JsonPrimitive)?.content == "required" -> "any"
                choiceObject?.string("type") == "none" || (choice as? JsonPrimitive)?.content == "none" -> "none"
                else -> "auto"
            }
            put("type", type)
            if (type == "tool") put("name", choiceObject?.string("name").orEmpty())
            if (disableParallel) put("disable_parallel_tool_use", true)
        }
    }
}

internal fun messagesToResponsesResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    val id = response.string("id") ?: "resp_"+System.currentTimeMillis()
    put("id", id)
    put("object", "response")
    put("created_at", System.currentTimeMillis() / 1000)
    val stopReason = response.string("stop_reason")
    val incomplete = stopReason == "max_tokens" || stopReason == "refusal"
    put("status", if (incomplete) "incomplete" else "completed")
    if (incomplete) putJsonObject("incomplete_details") {
        put("reason", if (stopReason == "refusal") "content_filter" else "max_output_tokens")
    }
    put("model", model)
    put("output", JsonArray(response.array("content").mapIndexedNotNull { index, value ->
        val block = value as? JsonObject ?: unsupported("content[$index]", "内容块必须是对象")
        when (block.string("type")) {
            "text" -> buildJsonObject {
                put("type", "message")
                put("id", "msg_"+id+"_"+index)
                put("role", "assistant")
                put("status", "completed")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "output_text")
                        put("text", block.string("text").orEmpty())
                        put("annotations", JsonArray(emptyList()))
                    })
                })
            }
            "tool_use" -> buildJsonObject {
                put("type", "function_call")
                put("id", "fc_"+id+"_"+index)
                put("call_id", block.string("id").orEmpty())
                put("name", block.string("name").orEmpty())
                put("arguments", block["input"]?.takeIf { it != JsonNull }?.toString() ?: "{}")
                put("status", "completed")
            }
            "thinking" -> buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_"+id+"_"+index)
                put("summary", buildJsonArray {
                    add(buildJsonObject { put("type", "summary_text"); put("text", block.string("thinking").orEmpty()) })
                })
            }
            "redacted_thinking" -> unsupported("content[$index].type", "Responses 不支持 Messages redacted_thinking 内容")
            else -> unsupported("content[$index].type", "未知内容块")
        }
    }))
    TokenUsage.fromMessages(response["usage"] as? JsonObject)?.let { put("usage", it.toResponses()) }
}

internal fun responsesToMessagesResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    put("id", response.string("id") ?: "msg_"+System.currentTimeMillis())
    put("type", "message")
    put("role", "assistant")
    put("model", model)
    val content = mutableListOf<JsonObject>()
    response.array("output").forEachIndexed { index, value ->
        val item = value as? JsonObject ?: unsupported("output[$index]", "输出项必须是对象")
        when (item.string("type")) {
            "message" -> item.array("content").forEachIndexed { partIndex, part ->
                val block = part as? JsonObject ?: unsupported("output[$index].content[$partIndex]", "内容块必须是对象")
                when (block.string("type")) {
                    "text", "output_text" -> content += messageTextBlock(block.string("text").orEmpty())
                    else -> unsupported("output[$index].content[$partIndex].type", "第一版不支持该 Responses 输出内容")
                }
            }
            "function_call" -> content += buildJsonObject {
                put("type", "tool_use")
                put("id", item.string("call_id") ?: item.string("id").orEmpty())
                put("name", item.string("name").orEmpty())
                put("input", parseJsonObjectOrEmpty(item.string("arguments")))
            }
            "reasoning" -> unsupported("output[$index].type", "Messages 不支持缺少 Anthropic thinking signature 的 Responses reasoning")
            else -> unsupported("output[$index].type", "第一版不支持 Responses 输出项 "+item.string("type"))
        }
    }
    put("content", JsonArray(content))
    val incompleteReason = (response["incomplete_details"] as? JsonObject)?.string("reason")
    put("stop_reason", when {
        response.string("status") == "incomplete" -> if (incompleteReason == "content_filter") "refusal" else "max_tokens"
        content.any { it.string("type") == "tool_use" } -> "tool_use"
        else -> "end_turn"
    })
    put("stop_sequence", JsonNull)
    TokenUsage.fromResponses(response["usage"] as? JsonObject)?.let { put("usage", it.toMessages()) }
}


private fun responseTextBlock(text: String, role: String): JsonObject = buildJsonObject {
    put("type", if (role == "assistant") "output_text" else "input_text")
    put("text", text)
}

private fun messageTextBlock(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

private fun messageWithBlocks(role: String, blocks: List<JsonObject>): JsonObject = buildJsonObject {
    put("role", role)
    put("content", JsonArray(blocks))
}
