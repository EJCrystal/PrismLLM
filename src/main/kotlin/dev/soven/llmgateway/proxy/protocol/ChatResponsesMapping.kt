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

internal fun responsesToChatRequest(request: JsonObject): JsonObject = buildJsonObject {
    validateResponsesRequest(request)
    request.string("model")?.let { put("model", it) }
    request.boolean("stream")?.let { put("stream", it) }
    request.number("temperature")?.let { put("temperature", it) }
    request.number("top_p")?.let { put("top_p", it) }
    request.number("max_output_tokens")?.let { put("max_tokens", it) }
    (request.string("user") ?: (request["metadata"] as? JsonObject)?.string("user_id"))
        ?.let { put("user", it) }
    (request["reasoning"] as? JsonObject)?.string("effort")?.let { put("reasoning_effort", it) }
    request.boolean("parallel_tool_calls")?.let { put("parallel_tool_calls", it) }
    (request["text"] as? JsonObject)?.get("format")?.let {
        put("response_format", responsesTextFormatToChat(it))
    }

    val messages = mutableListOf<JsonObject>()
    request.string("instructions")?.takeIf { it.isNotBlank() }?.let { instructions ->
        messages += buildJsonObject {
            put("role", "system")
            put("content", instructions)
        }
    }
    when (val input = request["input"]) {
        is JsonPrimitive -> messages += buildJsonObject {
            put("role", "user")
            put("content", input.content)
        }
        is JsonArray -> input.forEachIndexed { index, element ->
            val item = element as? JsonObject ?: unsupported("input[$index]", "输入项必须是对象")
            when (item.string("type")) {
                "message", null -> messages += responsesMessageToChat(item, "input[$index]")
                "function_call" -> {
                    val call = buildJsonObject {
                        put("id", item.string("call_id") ?: item.string("id") ?: unsupported("input[$index].call_id", "function_call 缺少 call_id"))
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", item.string("name") ?: unsupported("input[$index].name", "function_call 缺少 name"))
                            put("arguments", item.string("arguments").orEmpty().ifEmpty { "{}" })
                        }
                    }
                    val previous = messages.lastOrNull()
                    if (previous?.string("role") == "assistant") {
                        messages[messages.lastIndex] = JsonObject(previous + ("tool_calls" to JsonArray(previous.array("tool_calls") + call)))
                    } else {
                        messages += buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonNull)
                            put("tool_calls", JsonArray(listOf(call)))
                        }
                    }
                }
                "function_call_output" -> messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", item.string("call_id") ?: unsupported("input[$index].call_id", "function_call_output 缺少 call_id"))
                    put("content", item.textContentStrict("output", "input[$index].output"))
                }
                else -> unsupported("input[$index].type", "第一版不支持 Responses 输入项 ${item.string("type")}")
            }
        }
        JsonNull, null -> Unit
        else -> unsupported("input", "input 必须是字符串或数组")
    }
    put("messages", JsonArray(messages))
    request["tools"]?.let { toolsElement ->
        val tools = toolsElement as? JsonArray ?: unsupported("tools", "tools 必须是数组")
        put("tools", JsonArray(tools.mapIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            if (tool.string("type") != "function") {
                unsupported("tools[$index].type", "第一版不支持内置工具 ${tool.string("type")}")
            }
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.string("name") ?: unsupported("tools[$index].name", "工具缺少 name"))
                    tool.string("description")?.let { put("description", it) }
                    put("parameters", tool["parameters"] ?: buildJsonObject { put("type", "object") })
                    tool.boolean("strict")?.let { put("strict", it) }
                }
            }
        }))
    }
    request["tool_choice"]?.let { choice -> put("tool_choice", responsesToolChoiceToChat(choice)) }
}

internal fun chatToResponsesRequest(chat: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    rejectUnknownFields(chat, chatRequestFields, "request")
    put("model", upstreamModel)
    chat.boolean("stream")?.let { put("stream", it) }
    chat.number("temperature")?.let { put("temperature", it) }
    chat.number("top_p")?.let { put("top_p", it) }
    (chat.long("max_tokens") ?: chat.long("max_completion_tokens"))?.let { put("max_output_tokens", it) }
    if (chat["stop"] != null) unsupported("stop", "Responses 不支持 stop/stop_sequences")
    chat.string("user")?.let { put("user", it) }
    chat.string("reasoning_effort")?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
    chat["response_format"]?.let { format ->
        putJsonObject("text") { put("format", chatResponseFormatToResponses(format)) }
    }
    chat["parallel_tool_calls"]?.let { put("parallel_tool_calls", it) }

    val system = mutableListOf<String>()
    val input = mutableListOf<JsonObject>()
    chat.array("messages").forEachIndexed { index, element ->
        val message = element as? JsonObject ?: unsupported("messages[$index]", "消息必须是对象")
        when (message.string("role")) {
            "system", "developer" -> system += message.textContentStrict("content", "messages[$index].content")
            "user", "assistant" -> {
                val role = message.string("role")
                val text = message.textContentStrict("content", "messages[$index].content")
                if (text.isNotEmpty() || message["tool_calls"] == null) {
                    input += buildJsonObject {
                        put("type", "message")
                        put("role", role)
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", if (role == "assistant") "output_text" else "input_text")
                                put("text", text)
                            })
                        })
                    }
                }
                (message["tool_calls"] as? JsonArray)?.forEachIndexed { callIndex, callElement ->
                    val call = callElement as? JsonObject ?: unsupported("messages[$index].tool_calls[$callIndex]", "工具调用必须是对象")
                    val function = call["function"] as? JsonObject ?: unsupported("messages[$index].tool_calls[$callIndex].function", "缺少 function")
                    input += buildJsonObject {
                        put("type", "function_call")
                        put("call_id", call.string("id") ?: unsupported("messages[$index].tool_calls[$callIndex].id", "工具调用缺少 id"))
                        put("name", function.string("name") ?: unsupported("messages[$index].tool_calls[$callIndex].function.name", "工具调用缺少 name"))
                        put("arguments", function.string("arguments").orEmpty().ifEmpty { "{}" })
                    }
                }
            }
            "tool" -> input += buildJsonObject {
                put("type", "function_call_output")
                put("call_id", message.string("tool_call_id") ?: unsupported("messages[$index].tool_call_id", "tool 消息缺少 tool_call_id"))
                put("output", message.textContentStrict("content", "messages[$index].content"))
            }
            else -> unsupported("messages[$index].role", "不支持的消息角色")
        }
    }
    if (system.isNotEmpty()) put("instructions", system.joinToString("\n"))
    put("input", JsonArray(input))
    chat["tools"]?.let { toolsElement ->
        val tools = toolsElement as? JsonArray ?: unsupported("tools", "tools 必须是数组")
        put("tools", JsonArray(tools.mapIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            val function = tool["function"] as? JsonObject ?: unsupported("tools[$index].function", "缺少 function")
            buildJsonObject {
                put("type", "function")
                put("name", function.string("name") ?: unsupported("tools[$index].function.name", "工具缺少 name"))
                function.string("description")?.let { put("description", it) }
                put("parameters", function["parameters"] ?: buildJsonObject { put("type", "object") })
                function.boolean("strict")?.let { put("strict", it) }
            }
        }))
    }
    chat["tool_choice"]?.let { choice -> put("tool_choice", chatToolChoiceToResponses(choice)) }
}

internal fun responsesToChatResponse(response: JsonObject, model: String): JsonObject {
    val content = mutableListOf<String>()
    val reasoning = mutableListOf<String>()
    val toolCalls = mutableListOf<JsonObject>()
    response.array("output").forEachIndexed { index, element ->
        val item = element as? JsonObject ?: unsupported("output[$index]", "输出项必须是对象")
        when (item.string("type")) {
            "message" -> item.array("content").forEachIndexed { partIndex, part ->
                val obj = part as? JsonObject ?: return@forEachIndexed
                when (obj.string("type")) {
                    "output_text", "text" -> content += obj.string("text").orEmpty()
                    else -> unsupported("output[$index].content[$partIndex].type", "第一版不支持该 Responses 输出内容")
                }
            }
            "function_call" -> toolCalls += buildJsonObject {
                put("id", item.string("call_id") ?: item.string("id").orEmpty())
                put("type", "function")
                putJsonObject("function") {
                    put("name", item.string("name").orEmpty())
                    put("arguments", item.string("arguments").orEmpty().ifEmpty { "{}" })
                }
            }
            "reasoning" -> {
                val summary = item.array("summary").mapNotNull { part ->
                    (part as? JsonObject)?.string("text")?.takeIf { it.isNotBlank() }
                }
                val parts = summary.ifEmpty {
                    item.array("content").mapNotNull { part ->
                        (part as? JsonObject)?.string("text")?.takeIf { it.isNotBlank() }
                    }
                }
                reasoning += parts
            }
            else -> unsupported("output[$index].type", "第一版不支持 Responses 输出项 ${item.string("type")}")
        }
    }
    return buildJsonObject {
        put("id", response.string("id") ?: "chatcmpl-${System.currentTimeMillis()}")
        put("object", "chat.completion")
        put("created", response.long("created_at") ?: System.currentTimeMillis() / 1000)
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
                val incompleteReason = (response["incomplete_details"] as? JsonObject)?.string("reason")
                put("finish_reason", when {
                    response.string("status") == "incomplete" && incompleteReason == "content_filter" -> "content_filter"
                    response.string("status") == "incomplete" -> "length"
                    toolCalls.isNotEmpty() -> "tool_calls"
                    else -> "stop"
                })
            })
        })
        responsesUsageToChat(response["usage"] as? JsonObject)?.let { put("usage", it) }
    }
}

internal fun chatToResponsesResponse(chat: JsonObject, model: String): JsonObject {
    val choice = chat.array("choices").firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    val output = mutableListOf<JsonObject>()
    val reasoning = message?.string("reasoning_content") ?: message?.string("reasoning")
    if (!reasoning.isNullOrEmpty()) {
        output += buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_${chat.string("id") ?: System.currentTimeMillis()}")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "summary_text")
                    put("text", reasoning)
                })
            })
        }
    }
    val text = message?.string("content").orEmpty()
    if (text.isNotEmpty()) {
        output += buildJsonObject {
            put("type", "message")
            put("id", "msg_${chat.string("id") ?: System.currentTimeMillis()}")
            put("role", "assistant")
            put("status", "completed")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "output_text")
                    put("text", text)
                    put("annotations", JsonArray(emptyList()))
                })
            })
        }
    }
    (message?.get("tool_calls") as? JsonArray)?.forEachIndexed { index, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        val function = call["function"] as? JsonObject ?: return@forEachIndexed
        output += buildJsonObject {
            put("type", "function_call")
            put("id", "fc_${chat.string("id") ?: System.currentTimeMillis()}_$index")
            put("call_id", call.string("id") ?: "call_$index")
            put("name", function.string("name").orEmpty())
            put("arguments", function.string("arguments").orEmpty().ifEmpty { "{}" })
            put("status", "completed")
        }
    }
    val finishReason = choice?.string("finish_reason")
    val incomplete = finishReason == "length" || finishReason == "content_filter"
    return buildJsonObject {
        put("id", chat.string("id") ?: "resp_${System.currentTimeMillis()}")
        put("object", "response")
        put("created_at", chat.long("created") ?: System.currentTimeMillis() / 1000)
        put("status", if (incomplete) "incomplete" else "completed")
        if (incomplete) {
            putJsonObject("incomplete_details") {
                put("reason", if (finishReason == "content_filter") "content_filter" else "max_output_tokens")
            }
        }
        put("model", model)
        put("output", JsonArray(output))
        chatUsageToResponses(chat["usage"] as? JsonObject)?.let { put("usage", it) }
    }
}

internal fun responsesMessageToChat(item: JsonObject, path: String): JsonObject {
    val text = mutableListOf<String>()
    when (val content = item["content"]) {
        is JsonPrimitive -> text += content.content
        is JsonArray -> content.forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: unsupported("$path.content[$index]", "内容块必须是对象")
            when (obj.string("type")) {
                "input_text", "output_text", "text" -> text += obj.string("text").orEmpty()
                "input_image", "input_file", "input_audio" -> unsupported("$path.content[$index].type", "第一版不支持多模态内容")
                else -> unsupported("$path.content[$index].type", "第一版不支持该 Responses 内容")
            }
        }
        JsonNull, null -> Unit
        else -> unsupported("$path.content", "无法识别的内容")
    }
    return buildJsonObject {
        put("role", item.string("role") ?: "user")
        put("content", text.joinToString("\n"))
    }
}

internal fun responsesToolChoiceToChat(choice: JsonElement): JsonElement {
    if (choice is JsonPrimitive) return choice
    val obj = choice as? JsonObject ?: return JsonPrimitive("auto")
    return when (obj.string("type")) {
        "function" -> buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", obj.string("name").orEmpty()) }
        }
        "required" -> JsonPrimitive("required")
        "none" -> JsonPrimitive("none")
        else -> JsonPrimitive("auto")
    }
}

internal fun chatToolChoiceToResponses(choice: JsonElement): JsonElement {
    if (choice is JsonPrimitive) return JsonPrimitive(choice.contentOrNull ?: "auto")
    val obj = choice as? JsonObject ?: return JsonPrimitive("auto")
    val function = obj["function"] as? JsonObject ?: return JsonPrimitive("auto")
    return buildJsonObject {
        put("type", "function")
        put("name", function.string("name").orEmpty())
    }
}

internal fun responsesUsageToChat(usage: JsonObject?): JsonObject? = TokenUsage.fromResponses(usage)?.toChat()

internal fun chatUsageToResponses(usage: JsonObject?): JsonObject? = TokenUsage.fromChat(usage)?.toResponses()

internal fun chatResponseFormatToResponses(format: JsonElement): JsonElement {
    val obj = format as? JsonObject ?: return format
    return when (obj.string("type")) {
        "json_object" -> buildJsonObject { put("type", "json_object") }
        "json_schema" -> {
            val schema = obj["json_schema"] as? JsonObject ?: unsupported("response_format.json_schema", "缺少 json_schema")
            buildJsonObject {
                put("type", "json_schema")
                schema.string("name")?.let { put("name", it) }
                schema["schema"]?.let { put("schema", it) }
                schema.boolean("strict")?.let { put("strict", it) }
            }
        }
        "text" -> buildJsonObject { put("type", "text") }
        else -> unsupported("response_format.type", "Responses 不支持该结构化输出类型")
    }
}

internal fun responsesTextFormatToChat(format: JsonElement): JsonElement {
    val obj = format as? JsonObject ?: return format
    return when (obj.string("type")) {
        "json_schema" -> buildJsonObject {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                obj.string("name")?.let { put("name", it) }
                obj["schema"]?.let { put("schema", it) }
                obj.boolean("strict")?.let { put("strict", it) }
            }
        }
        "json_object", "text" -> buildJsonObject { put("type", obj.string("type")!!) }
        else -> unsupported("text.format.type", "Chat Completions 不支持该结构化输出类型")
    }
}
