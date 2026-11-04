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

private val geminiRequestFields = setOf("contents", "systemInstruction", "generationConfig", "tools", "toolConfig", "safetySettings", "cachedContent")

internal fun validateGeminiRequest(request: JsonObject) {
    rejectUnknownFields(request, geminiRequestFields, "request")
    if (request.containsKey("cachedContent")) unsupported("cachedContent", "协议转换不支持 Gemini cachedContent")
    if ((request["safetySettings"] as? JsonArray)?.isNotEmpty() == true) unsupported("safetySettings", "协议转换不支持 Gemini safetySettings")
    val config = request["generationConfig"] as? JsonObject
    (config?.get("responseModalities") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
        unsupported("generationConfig.responseModalities", "跨协议转换不支持 Gemini 非文本输出模态")
    }
    (config?.get("candidateCount") as? JsonPrimitive)?.contentOrNull?.takeIf { it != "1" }?.let {
        unsupported("generationConfig.candidateCount", "跨协议转换仅支持单个候选")
    }
}

internal fun geminiToChatRequest(request: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateGeminiRequest(request)
    put("model", upstreamModel)
    val config = request["generationConfig"] as? JsonObject
    config?.number("maxOutputTokens")?.let { put("max_tokens", it) }
    config?.number("temperature")?.let { put("temperature", it) }
    config?.number("topP")?.let { put("top_p", it) }
    config?.number("topK")?.let { put("top_k", it) }
    (config?.get("stopSequences") as? JsonArray)?.let { stop ->
        put("stop", if (stop.size == 1) stop.single() else stop)
    }
    config?.long("seed")?.let { put("seed", it) }
    config?.number("frequencyPenalty")?.let { put("frequency_penalty", it) }
    config?.number("presencePenalty")?.let { put("presence_penalty", it) }
    config?.boolean("responseLogprobs")?.let { put("logprobs", it) }
    config?.long("logprobs")?.let { put("top_logprobs", it) }
    val messages = geminiContentsToChat(request["contents"])
    geminiSystemText(request["systemInstruction"])?.let { system ->
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            addAll(messages)
        })
    } ?: put("messages", messages)
    geminiToolsToChat(request["tools"])?.let { put("tools", it) }
    geminiToolChoiceToChat(request["toolConfig"])?.let { put("tool_choice", it) }
    config?.string("responseMimeType")?.let { mime ->
        val schema = config["responseJsonSchema"]
        put("response_format", when {
            mime == "application/json" && schema != null -> buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "response"); put("schema", schema) }
            }
            mime == "application/json" -> buildJsonObject { put("type", "json_object") }
            else -> unsupported("generationConfig.responseMimeType", "不支持响应类型 $mime")
        })
    }
    geminiThinkingToEffort(config)?.let { put("reasoning_effort", it) }
}.asChatUpstream(upstreamModel)

internal fun chatToGeminiRequest(chat: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    rejectUnknownFields(chat, chatRequestFields, "request")
    val system = mutableListOf<String>()
    val contents = mutableListOf<JsonObject>()
    val callNames = mutableMapOf<String, String>()
    chat.array("messages").forEachIndexed { index, element ->
        val message = element as? JsonObject ?: unsupported("messages[$index]", "消息必须是对象")
        when (message.string("role")) {
            "system", "developer" -> system += message.textContentStrict("content", "messages[$index].content")
            "user" -> contents += buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(listOf(buildJsonObject { put("text", message.textContentStrict("content", "messages[$index].content")) })))
            }
            "assistant" -> {
                val parts = mutableListOf<JsonObject>()
                message.string("content")?.takeIf { it.isNotEmpty() }?.let { text -> parts += buildJsonObject { put("text", text) } }
                (message["tool_calls"] as? JsonArray)?.forEachIndexed { callIndex, callElement ->
                    val call = callElement as? JsonObject ?: unsupported("messages[$index].tool_calls[$callIndex]", "工具调用必须是对象")
                    val function = call["function"] as? JsonObject ?: unsupported("messages[$index].tool_calls[$callIndex].function", "缺少 function")
                    val callId = call.string("id") ?: unsupported("messages[$index].tool_calls[$callIndex].id", "工具调用缺少 id")
                    val callName = function.string("name") ?: unsupported("messages[$index].tool_calls[$callIndex].function.name", "工具调用缺少 name")
                    callNames[callId] = callName
                    parts += buildJsonObject {
                        putJsonObject("functionCall") {
                            put("name", callName)
                            put("args", parseJsonObjectOrEmpty(function.string("arguments")))
                        }
                    }
                }
                if (parts.isNotEmpty()) contents += buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) }
            }
            "tool" -> contents += buildJsonObject {
                put("role", "user")
                val callId = message.string("tool_call_id") ?: unsupported("messages[$index].tool_call_id", "tool 消息缺少 tool_call_id")
                val callName = callNames[callId] ?: unsupported("messages[$index].tool_call_id", "找不到对应的工具调用")
                put("parts", JsonArray(listOf(buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", callName)
                        putJsonObject("response") { put("content", message.textContentStrict("content", "messages[$index].content")) }
                    }
                })))
            }
            else -> unsupported("messages[$index].role", "不支持的消息角色")
        }
    }
    if (system.isNotEmpty()) put("systemInstruction", buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", system.joinToString("\n")) }) })
    })
    put("contents", JsonArray(contents))
    val generation = buildJsonObject {
        (chat.long("max_tokens") ?: chat.long("max_completion_tokens"))?.let { put("maxOutputTokens", it) }
        chat.number("temperature")?.let { put("temperature", it) }
        chat.number("top_p")?.let { put("topP", it) }
        chat.number("top_k")?.let { put("topK", it) }
        chat.long("seed")?.let { put("seed", it) }
        chat.number("frequency_penalty")?.let { put("frequencyPenalty", it) }
        chat.number("presence_penalty")?.let { put("presencePenalty", it) }
        (chat["stop"] as? JsonArray)?.let { put("stopSequences", it) }
        chat.string("stop")?.let { stop -> put("stopSequences", buildJsonArray { add(JsonPrimitive(stop)) }) }
        chat.boolean("logprobs")?.let { put("responseLogprobs", it) }
        chat.long("top_logprobs")?.let { put("logprobs", it) }
        chat.string("reasoning_effort")?.let { effort ->
            putJsonObject("thinkingConfig") { put("thinkingBudget", thinkingBudget(effort)); put("includeThoughts", effort != "none") }
        }
        chat["response_format"]?.let { format ->
            val obj = format as? JsonObject ?: return@let
            when (obj.string("type")) {
                "json_schema" -> {
                    put("responseMimeType", "application/json")
                    (obj["json_schema"] as? JsonObject)?.get("schema")?.let { put("responseJsonSchema", it) }
                }
                "json_object" -> put("responseMimeType", "application/json")
                "text" -> Unit
                else -> unsupported("response_format.type", "不支持响应格式 ${obj.string("type")}")
            }
        }
    }
    if (generation.isNotEmpty()) put("generationConfig", generation)
    (chat["tools"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { tools ->
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    tools.forEachIndexed { index, element ->
                        val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
                        val function = tool["function"] as? JsonObject ?: unsupported("tools[$index].function", "缺少 function")
                        add(buildJsonObject {
                            put("name", function.string("name") ?: unsupported("tools[$index].function.name", "工具缺少 name"))
                            function.string("description")?.let { put("description", it) }
                            put("parameters", function["parameters"] ?: buildJsonObject { put("type", "object") })
                        })
                    }
                })
            })
        })
    }
    chatToolChoiceToGemini(chat["tool_choice"], chat.boolean("parallel_tool_calls") == false)?.let { put("toolConfig", it) }
}

internal fun geminiToChatResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    put("id", response.string("responseId") ?: "chatcmpl-${System.currentTimeMillis()}")
    put("object", "chat.completion")
    put("created", System.currentTimeMillis() / 1000)
    put("model", model)
    val candidate = response.array("candidates").firstOrNull() as? JsonObject
    val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
    val text = parts.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj.string("text")?.takeIf { obj.boolean("thought") != true }
    }.joinToString("")
    val reasoning = parts.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        obj.string("text")?.takeIf { obj.boolean("thought") == true }
    }.joinToString("")
    val toolCalls = parts.mapIndexedNotNull { index, part ->
        val obj = part as? JsonObject ?: return@mapIndexedNotNull null
        val call = obj["functionCall"] as? JsonObject ?: return@mapIndexedNotNull null
        buildJsonObject {
            put("id", call.string("id") ?: "call_$index")
            put("type", "function")
            putJsonObject("function") {
                put("name", call.string("name").orEmpty())
                put("arguments", (call["args"] ?: buildJsonObject { }).toString())
            }
        }
    }
    put("choices", buildJsonArray {
        add(buildJsonObject {
            put("index", 0)
            putJsonObject("message") {
                put("role", "assistant")
                put("content", text.ifEmpty { null })
                if (reasoning.isNotEmpty()) put("reasoning_content", reasoning)
                if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
            }
            put("finish_reason", geminiFinishToChat(candidate?.string("finishReason"), toolCalls.isNotEmpty()))
        })
    })
    geminiUsageToChat(response["usageMetadata"] as? JsonObject)?.let { put("usage", it) }
}

internal fun chatToGeminiResponse(chat: JsonObject, model: String): JsonObject = buildJsonObject {
    val choice = chat.array("choices").firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    val parts = mutableListOf<JsonObject>()
    message?.string("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { reasoning ->
        parts += buildJsonObject { put("text", reasoning); put("thought", true) }
    }
    message?.string("content")?.takeIf { it.isNotEmpty() }?.let { text -> parts += buildJsonObject { put("text", text) } }
    (message?.get("tool_calls") as? JsonArray)?.forEachIndexed { index, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        val function = call["function"] as? JsonObject ?: return@forEachIndexed
        parts += buildJsonObject {
            putJsonObject("functionCall") {
                put("name", function.string("name").orEmpty())
                put("args", parseJsonObjectOrEmpty(function.string("arguments")))
            }
        }
    }
    message?.string("refusal")?.takeIf { it.isNotEmpty() }?.let { refusal -> parts += buildJsonObject { put("text", refusal) } }
    put("responseId", chat.string("id") ?: "resp_${System.currentTimeMillis()}")
    put("modelVersion", model)
    put("candidates", buildJsonArray {
        add(buildJsonObject {
            put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
            put("finishReason", chatFinishToGemini(choice?.string("finish_reason"), message?.string("refusal") != null))
            put("index", 0)
        })
    })
    chatUsageToGemini(chat["usage"] as? JsonObject)?.let { put("usageMetadata", it) }
}

internal fun geminiToMessagesRequest(request: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateGeminiRequest(request)
    put("model", upstreamModel)
    val config = request["generationConfig"] as? JsonObject
    val requestedMaxTokens = config?.long("maxOutputTokens")
    config?.number("temperature")?.let { put("temperature", it) }
    config?.number("topP")?.let { put("top_p", it) }
    config?.number("topK")?.let { put("top_k", it) }
    (config?.get("stopSequences") as? JsonArray)?.let { put("stop_sequences", it) }
    geminiSystemText(request["systemInstruction"])?.let { put("system", it) }
    put("messages", geminiContentsToMessages(request["contents"]))
    geminiToolsToMessages(request["tools"])?.let { put("tools", it) }
    geminiToolChoiceToMessages(request["toolConfig"])?.let { put("tool_choice", it) }
    val thinkingEffort = geminiThinkingToEffort(config)
    if (thinkingEffort == null) {
        put("max_tokens", requestedMaxTokens ?: 4096)
    } else {
        val (thinking, maxTokens) = thinkingAndMaxTokens(thinkingEffort, requestedMaxTokens, "generationConfig.maxOutputTokens")
        put("max_tokens", maxTokens)
        put("thinking", thinking)
    }
    config?.string("responseMimeType")?.let { mime ->
        if (mime != "application/json") unsupported("generationConfig.responseMimeType", "不支持响应类型 $mime")
        putJsonObject("output_config") {
            putJsonObject("format") {
                put("type", "json_schema")
                putJsonObject("schema") { put("name", "response"); put("schema", config["responseJsonSchema"] ?: buildJsonObject { }) }
            }
        }
    }
}

internal fun messagesToGeminiRequest(messages: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateMessagesRequest(messages)
    val contents = mutableListOf<JsonObject>()
    val callNames = mutableMapOf<String, String>()
    messages.textContentStrict("system", "system").takeIf { it.isNotBlank() }?.let { system ->
        put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
    }
    messages.array("messages").forEachIndexed { index, element ->
        val message = element as? JsonObject ?: unsupported("messages[$index]", "消息必须是对象")
        val parts = mutableListOf<JsonObject>()
        when (val content = message["content"]) {
            JsonNull -> Unit
            is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text -> parts += buildJsonObject { put("text", text) } }
            is JsonArray -> content.forEachIndexed { blockIndex, blockElement ->
                val block = blockElement as? JsonObject ?: unsupported("messages[$index].content[$blockIndex]", "内容块必须是对象")
                when (block.string("type")) {
                    "text" -> parts += buildJsonObject { put("text", block.string("text").orEmpty()) }
                    "thinking" -> parts += buildJsonObject { put("text", block.string("thinking").orEmpty()); put("thought", true) }
                    "tool_use" -> {
                        val callId = block.string("id") ?: unsupported("messages[$index].content[$blockIndex].id", "tool_use 缺少 id")
                        val callName = block.string("name") ?: unsupported("messages[$index].content[$blockIndex].name", "tool_use 缺少 name")
                        callNames[callId] = callName
                        parts += buildJsonObject {
                            putJsonObject("functionCall") {
                                put("name", callName)
                                put("args", block["input"] ?: buildJsonObject { })
                            }
                        }
                    }
                    "tool_result" -> {
                        val callId = block.string("tool_use_id") ?: unsupported("messages[$index].content[$blockIndex].tool_use_id", "tool_result 缺少 tool_use_id")
                        val callName = callNames[callId] ?: unsupported("messages[$index].content[$blockIndex].tool_use_id", "找不到对应的工具调用")
                        parts += buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", callName)
                                putJsonObject("response") { put("content", block.textContentStrict("content", "messages[$index].content[$blockIndex].content")) }
                            }
                        }
                    }
                    "redacted_thinking" -> unsupported("messages[$index].content[$blockIndex].type", "Gemini 不支持 redacted_thinking")
                    else -> unsupported("messages[$index].content[$blockIndex].type", "第一版不支持 Messages 内容块")
                }
            }
            JsonNull, null -> Unit
            else -> unsupported("messages[$index].content", "内容必须是字符串或数组")
        }
        if (parts.isNotEmpty()) contents += buildJsonObject {
            put("role", if (message.string("role") == "assistant") "model" else "user")
            put("parts", JsonArray(parts))
        }
    }
    put("contents", JsonArray(contents))
    val generation = buildJsonObject {
        messages.long("max_tokens")?.let { put("maxOutputTokens", it) }
        messages.number("temperature")?.let { put("temperature", it) }
        messages.number("top_p")?.let { put("topP", it) }
        (messages["stop_sequences"] as? JsonArray)?.let { put("stopSequences", it) }
    }
    val thinking = messages.thinkingEffort()?.let { effort ->
        buildJsonObject { put("thinkingBudget", thinkingBudget(effort)); put("includeThoughts", effort != "none") }
    }
    val generationWithThinking = if (thinking != null) JsonObject(generation + ("thinkingConfig" to thinking)) else generation
    if (generationWithThinking.isNotEmpty()) put("generationConfig", generationWithThinking)
    (messages["tools"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { tools ->
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    tools.forEachIndexed { index, element ->
                        val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
                        validateMessagesTool(tool, "tools[$index]")
                        add(buildJsonObject {
                            put("name", tool.string("name") ?: unsupported("tools[$index].name", "工具缺少 name"))
                            tool.string("description")?.let { put("description", it) }
                            put("parameters", tool["input_schema"] ?: buildJsonObject { put("type", "object") })
                        })
                    }
                })
            })
        })
    }
}

internal fun geminiToMessagesResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    put("id", response.string("responseId") ?: "msg_${System.currentTimeMillis()}")
    put("type", "message")
    put("role", "assistant")
    put("model", model)
    val candidate = response.array("candidates").firstOrNull() as? JsonObject
    val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
    put("content", buildJsonArray {
        parts.forEachIndexed { index, part ->
            val obj = part as? JsonObject ?: return@forEachIndexed
            when {
                obj["functionCall"] is JsonObject -> add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", "toolu_$index")
                    put("name", (obj["functionCall"] as JsonObject).string("name").orEmpty())
                    put("input", (obj["functionCall"] as JsonObject)["args"] ?: buildJsonObject { })
                })
                obj.boolean("thought") == true -> add(buildJsonObject { put("type", "thinking"); put("thinking", obj.string("text").orEmpty()); put("signature", "") })
                obj.string("text") != null -> add(messageTextBlock(obj.string("text").orEmpty()))
            }
        }
    })
    put("stop_reason", geminiFinishToMessages(candidate?.string("finishReason")))
    put("stop_sequence", JsonNull)
    geminiUsageToMessages(response["usageMetadata"] as? JsonObject)?.let { put("usage", it) }
}

internal fun messagesToGeminiResponse(messages: JsonObject, model: String): JsonObject = buildJsonObject {
    val parts = messages.array("content").mapIndexedNotNull { index, element ->
        val block = element as? JsonObject ?: unsupported("content[$index]", "内容块必须是对象")
        when (block.string("type")) {
            "text" -> buildJsonObject { put("text", block.string("text").orEmpty()) }
            "thinking" -> buildJsonObject { put("text", block.string("thinking").orEmpty()); put("thought", true) }
            "tool_use" -> buildJsonObject { putJsonObject("functionCall") { put("name", block.string("name").orEmpty()); put("args", block["input"] ?: buildJsonObject { }) } }
            "redacted_thinking" -> unsupported("content[$index].type", "Gemini 不支持 redacted_thinking")
            else -> unsupported("content[$index].type", "不支持的内容块")
        }
    }
    put("responseId", messages.string("id") ?: "resp_${System.currentTimeMillis()}")
    put("modelVersion", model)
    put("candidates", buildJsonArray {
        add(buildJsonObject {
            put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
            put("finishReason", messagesStopToGemini(messages.string("stop_reason")))
            put("index", 0)
        })
    })
    messagesUsageToGemini(messages["usage"] as? JsonObject)?.let { put("usageMetadata", it) }
}

internal fun geminiToResponsesRequest(request: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateGeminiRequest(request)
    put("model", upstreamModel)
    geminiSystemText(request["systemInstruction"])?.let { put("instructions", it) }
    val config = request["generationConfig"] as? JsonObject
    config?.long("maxOutputTokens")?.let { put("max_output_tokens", it) }
    config?.number("temperature")?.let { put("temperature", it) }
    config?.number("topP")?.let { put("top_p", it) }
    put("input", geminiContentsToResponses(request["contents"]))
    geminiToolsToResponses(request["tools"])?.let { put("tools", it) }
    geminiToolChoiceToResponses(request["toolConfig"])?.let { put("tool_choice", it) }
    geminiThinkingToEffort(config)?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
    config?.string("responseMimeType")?.let { mime ->
        if (mime != "application/json") unsupported("generationConfig.responseMimeType", "不支持响应类型 $mime")
        putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema")
                put("name", "response")
                put("schema", config["responseJsonSchema"] ?: buildJsonObject { })
            }
        }
    }
}

internal fun responsesToGeminiRequest(responses: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateResponsesRequest(responses)
    val system = mutableListOf<String>()
    responses.string("instructions")?.takeIf { it.isNotBlank() }?.let { system += it }
    val contents = mutableListOf<JsonObject>()
    val callNames = mutableMapOf<String, String>()
    when (val input = responses["input"]) {
        JsonNull -> Unit
        is JsonPrimitive -> contents += buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", input.content) }) }) }
        is JsonArray -> input.forEachIndexed { index, element ->
            val item = element as? JsonObject ?: unsupported("input[$index]", "输入项必须是对象")
            when (item.string("type")) {
                "message", null -> {
                    val text = item.textContentStrict("content", "input[$index].content")
                    when (item.string("role")) {
                        "system", "developer" -> system += text
                        else -> if (text.isNotEmpty()) contents += buildJsonObject {
                            put("role", if (item.string("role") == "assistant") "model" else "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
                        }
                    }
                }
                "function_call" -> {
                    val callId = item.string("call_id") ?: unsupported("input[$index].call_id", "function_call 缺少 call_id")
                    val callName = item.string("name") ?: unsupported("input[$index].name", "function_call 缺少 name")
                    callNames[callId] = callName
                    contents += buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray { add(buildJsonObject {
                            putJsonObject("functionCall") {
                                put("name", callName)
                                put("args", parseJsonObjectOrEmpty(item.string("arguments")))
                            }
                        }) })
                    }
                }
                "function_call_output" -> {
                    val callId = item.string("call_id") ?: unsupported("input[$index].call_id", "function_call_output 缺少 call_id")
                    val callName = callNames[callId] ?: unsupported("input[$index].call_id", "找不到对应的工具调用")
                    contents += buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", callName)
                                putJsonObject("response") { put("content", item.textContentStrict("output", "input[$index].output")) }
                            }
                        }) })
                    }
                }
                else -> unsupported("input[$index].type", "第一版不支持 Responses 输入项")
            }
        }
        JsonNull, null -> Unit
        else -> unsupported("input", "input 必须是字符串或数组")
    }
    put("contents", JsonArray(contents))
    if (system.isNotEmpty()) put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system.joinToString("\n")) }) }) })
    val generation = buildJsonObject {
        responses.long("max_output_tokens")?.let { put("maxOutputTokens", it) }
        responses.number("temperature")?.let { put("temperature", it) }
        responses.number("top_p")?.let { put("topP", it) }
        (responses["reasoning"] as? JsonObject)?.string("effort")?.let { effort ->
            putJsonObject("thinkingConfig") { put("thinkingBudget", thinkingBudget(effort)); put("includeThoughts", effort != "none") }
        }
        (responses["text"] as? JsonObject)?.get("format")?.let { format ->
            val obj = format as? JsonObject ?: return@let
            when (obj.string("type")) {
                "json_schema" -> {
                    put("responseMimeType", "application/json")
                    obj["schema"]?.let { put("responseJsonSchema", it) }
                }
                "json_object" -> put("responseMimeType", "application/json")
                "text" -> Unit
                else -> unsupported("text.format.type", "不支持响应格式 ${obj.string("type")}")
            }
        }
    }
    if (generation.isNotEmpty()) put("generationConfig", generation)
    (responses["tools"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { tools ->
        put("tools", buildJsonArray { add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                tools.forEachIndexed { index, element ->
                    val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
                    if (tool.string("type") != "function") unsupported("tools[$index].type", "第一版不支持内置工具")
                    add(buildJsonObject {
                        put("name", tool.string("name") ?: unsupported("tools[$index].name", "工具缺少 name"))
                        tool.string("description")?.let { put("description", it) }
                        put("parameters", tool["parameters"] ?: buildJsonObject { put("type", "object") })
                    })
                }
            })
        }) })
    }
    responsesToolChoiceToGemini(responses["tool_choice"])?.let { put("toolConfig", it) }
}

internal fun geminiToResponsesResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    val id = response.string("responseId") ?: "resp_${System.currentTimeMillis()}"
    put("id", id)
    put("object", "response")
    put("created_at", System.currentTimeMillis() / 1000)
    val candidate = response.array("candidates").firstOrNull() as? JsonObject
    val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
    val output = mutableListOf<JsonObject>()
    parts.forEachIndexed { index, part ->
        val obj = part as? JsonObject ?: return@forEachIndexed
        when {
            obj["functionCall"] is JsonObject -> output += buildJsonObject {
                put("type", "function_call")
                put("id", "fc_${id}_$index")
                put("call_id", "call_$index")
                put("name", (obj["functionCall"] as JsonObject).string("name").orEmpty())
                put("arguments", ((obj["functionCall"] as JsonObject)["args"] ?: buildJsonObject { }).toString())
                put("status", "completed")
            }
            obj.boolean("thought") == true -> output += buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_${id}_$index")
                put("summary", buildJsonArray { add(buildJsonObject { put("type", "summary_text"); put("text", obj.string("text").orEmpty()) }) })
            }
            obj.string("text") != null -> output += buildJsonObject {
                put("type", "message")
                put("id", "msg_${id}_$index")
                put("role", "assistant")
                put("status", "completed")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", obj.string("text")); put("annotations", JsonArray(emptyList())) }) })
            }
        }
    }
    val finish = candidate?.string("finishReason")
    put("status", if (finish == "MAX_TOKENS" || finish == "SAFETY") "incomplete" else "completed")
    if (finish == "MAX_TOKENS" || finish == "SAFETY") putJsonObject("incomplete_details") {
        put("reason", if (finish == "SAFETY") "content_filter" else "max_output_tokens")
    }
    put("model", model)
    put("output", JsonArray(output))
    geminiUsageToResponses(response["usageMetadata"] as? JsonObject)?.let { put("usage", it) }
}

internal fun responsesToGeminiResponse(responses: JsonObject, model: String): JsonObject = buildJsonObject {
    val parts = responses.array("output").mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: unsupported("output[$index]", "输出项必须是对象")
        when (item.string("type")) {
            "message" -> {
                val text = item.array("content").joinToString("") { part -> (part as? JsonObject)?.string("text").orEmpty() }
                buildJsonObject { put("text", text) }
            }
            "reasoning" -> buildJsonObject {
                put("text", item.array("summary").joinToString("") { part -> (part as? JsonObject)?.string("text").orEmpty() })
                put("thought", true)
            }
            "function_call" -> buildJsonObject { putJsonObject("functionCall") { put("name", item.string("name").orEmpty()); put("args", parseJsonObjectOrEmpty(item.string("arguments"))) } }
            else -> unsupported("output[$index].type", "第一版不支持 Responses 输出项")
        }
    }
    put("responseId", responses.string("id") ?: "resp_${System.currentTimeMillis()}")
    put("modelVersion", model)
    put("candidates", buildJsonArray {
        add(buildJsonObject {
            put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
            put("finishReason", responsesStatusToGemini(responses.string("status"), (responses["incomplete_details"] as? JsonObject)?.string("reason")))
            put("index", 0)
        })
    })
    responsesUsageToGemini(responses["usage"] as? JsonObject)?.let { put("usageMetadata", it) }
}

private fun geminiSystemText(element: JsonElement?): String? = when (element) {
    JsonNull -> null
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> element.array("parts").mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("\n").takeIf { it.isNotBlank() }
    else -> null
}

private fun geminiContentsToChat(contents: JsonElement?): JsonArray = buildJsonArray {
    when (contents) {
        JsonNull -> Unit
        is JsonPrimitive -> add(buildJsonObject { put("role", "user"); put("content", contents.content) })
        is JsonArray -> {
            val callNames = mutableMapOf<String, String>()
            contents.forEachIndexed { index, element ->
                val content = element as? JsonObject ?: unsupported("contents[$index]", "内容必须是对象")
                val role = if (content.string("role") == "model") "assistant" else "user"
                val text = mutableListOf<String>()
                val toolCalls = mutableListOf<JsonObject>()
                content.array("parts").forEachIndexed { partIndex, part ->
                    val obj = part as? JsonObject ?: unsupported("contents[$index].parts[$partIndex]", "part 必须是对象")
                    val call = obj["functionCall"] as? JsonObject
                    val response = obj["functionResponse"] as? JsonObject
                    when {
                        call != null -> {
                            val name = call.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionCall.name", "functionCall 缺少 name")
                            val callId = "call_${index}_$partIndex"
                            callNames[name] = callId
                            toolCalls += buildJsonObject {
                                put("id", callId)
                                put("type", "function")
                                putJsonObject("function") { put("name", name); put("arguments", (call["args"] ?: buildJsonObject { }).toString()) }
                            }
                        }
                        response != null -> {
                            val name = response.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "functionResponse 缺少 name")
                            add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", callNames[name] ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "找不到对应的函数调用"))
                                put("content", ((response["response"] as? JsonObject)?.string("content") ?: response["response"]?.toString()).orEmpty())
                            })
                        }
                        obj.string("text") != null -> text += obj.string("text").orEmpty()
                        obj["inlineData"] != null || obj["fileData"] != null -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini 多模态内容")
                        else -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini part")
                    }
                }
                if (text.isNotEmpty() || toolCalls.isNotEmpty()) add(buildJsonObject {
                    put("role", role)
                    if (text.isNotEmpty()) put("content", text.joinToString("")) else put("content", JsonNull)
                    if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                })
            }
        }
        else -> Unit
    }
}

private fun geminiContentsToMessages(contents: JsonElement?): JsonArray = buildJsonArray {
    when (contents) {
        JsonNull -> Unit
        is JsonPrimitive -> add(messageWithBlocks("user", listOf(messageTextBlock(contents.content))))
        is JsonArray -> contents.forEachIndexed { index, element ->
            val content = element as? JsonObject ?: unsupported("contents[$index]", "内容必须是对象")
            val blocks = content.array("parts").mapIndexedNotNull { partIndex, part ->
                val obj = part as? JsonObject ?: unsupported("contents[$index].parts[$partIndex]", "part 必须是对象")
                when {
                    obj["functionCall"] is JsonObject -> buildJsonObject {
                        put("type", "tool_use")
                        put("id", "toolu_${index}_$partIndex")
                        put("name", (obj["functionCall"] as JsonObject).string("name").orEmpty())
                        put("input", (obj["functionCall"] as JsonObject)["args"] ?: buildJsonObject { })
                    }
                    obj["functionResponse"] is JsonObject -> {
                        val response = obj["functionResponse"] as JsonObject
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", response.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "functionResponse 缺少 name"))
                            put("content", ((response["response"] as? JsonObject)?.string("content") ?: response["response"]?.toString()).orEmpty())
                        }
                    }
                    obj["inlineData"] != null || obj["fileData"] != null -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini 多模态内容")
                    obj.boolean("thought") == true -> buildJsonObject { put("type", "thinking"); put("thinking", obj.string("text").orEmpty()); put("signature", "") }
                    else -> obj.string("text")?.let { messageTextBlock(it) }
                }
            }
            add(messageWithBlocks(if (content.string("role") == "model") "assistant" else "user", blocks))
        }
        else -> Unit
    }
}

private fun geminiContentsToResponses(contents: JsonElement?): JsonArray = buildJsonArray {
    when (contents) {
        JsonNull -> Unit
        is JsonPrimitive -> add(buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", contents.content) }) })
        })
        is JsonArray -> contents.forEachIndexed { index, element ->
            val content = element as? JsonObject ?: unsupported("contents[$index]", "内容必须是对象")
            val role = if (content.string("role") == "model") "assistant" else "user"
            val text = mutableListOf<String>()
            val toolCalls = mutableListOf<JsonObject>()
            content.array("parts").forEachIndexed { partIndex, part ->
                val obj = part as? JsonObject ?: unsupported("contents[$index].parts[$partIndex]", "part 必须是对象")
                val call = obj["functionCall"] as? JsonObject
                val response = obj["functionResponse"] as? JsonObject
                when {
                    call != null -> toolCalls += buildJsonObject {
                        put("type", "function_call")
                        put("call_id", "call_${index}_$partIndex")
                        put("name", call.string("name").orEmpty())
                        put("arguments", (call["args"] ?: buildJsonObject { }).toString())
                    }
                    response != null -> add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", "call_${index}_$partIndex")
                        put("output", ((response["response"] as? JsonObject)?.string("content") ?: response["response"]?.toString()).orEmpty())
                    })
                    obj["inlineData"] != null || obj["fileData"] != null -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini 多模态内容")
                    obj.string("text") != null -> text += obj.string("text").orEmpty()
                    else -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini part")
                }
            }
            if (text.isNotEmpty()) add(buildJsonObject {
                put("type", "message")
                put("role", role)
                put("content", buildJsonArray { add(buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", text.joinToString("")) }) })
            })
            addAll(toolCalls)
        }
        else -> Unit
    }
}

private fun geminiToolsToChat(tools: JsonElement?): JsonArray? = when (tools) {
    is JsonArray -> buildJsonArray {
        tools.forEachIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            tool.array("functionDeclarations").forEachIndexed { declarationIndex, declarationElement ->
                val declaration = declarationElement as? JsonObject ?: unsupported("tools[$index].functionDeclarations[$declarationIndex]", "函数声明必须是对象")
                add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", declaration.string("name") ?: unsupported("tools[$index].functionDeclarations[$declarationIndex].name", "函数声明缺少 name"))
                        declaration.string("description")?.let { put("description", it) }
                        put("parameters", declaration["parameters"] ?: buildJsonObject { put("type", "object") })
                    }
                })
            }
        }
    }
    null -> null
    else -> unsupported("tools", "tools 必须是数组")
}

private fun geminiToolsToMessages(tools: JsonElement?): JsonArray? = when (tools) {
    is JsonArray -> buildJsonArray {
        tools.forEachIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            tool.array("functionDeclarations").forEachIndexed { declarationIndex, declarationElement ->
                val declaration = declarationElement as? JsonObject ?: unsupported("tools[$index].functionDeclarations[$declarationIndex]", "函数声明必须是对象")
                add(buildJsonObject {
                    put("name", declaration.string("name") ?: unsupported("tools[$index].functionDeclarations[$declarationIndex].name", "函数声明缺少 name"))
                    declaration.string("description")?.let { put("description", it) }
                    put("input_schema", declaration["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        }
    }
    null -> null
    else -> unsupported("tools", "tools 必须是数组")
}

private fun geminiToolsToResponses(tools: JsonElement?): JsonArray? = when (tools) {
    is JsonArray -> buildJsonArray {
        tools.forEachIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            tool.array("functionDeclarations").forEachIndexed { declarationIndex, declarationElement ->
                val declaration = declarationElement as? JsonObject ?: unsupported("tools[$index].functionDeclarations[$declarationIndex]", "函数声明必须是对象")
                add(buildJsonObject {
                    put("type", "function")
                    put("name", declaration.string("name") ?: unsupported("tools[$index].functionDeclarations[$declarationIndex].name", "函数声明缺少 name"))
                    declaration.string("description")?.let { put("description", it) }
                    put("parameters", declaration["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        }
    }
    null -> null
    else -> unsupported("tools", "tools 必须是数组")
}

private fun geminiToolChoiceToChat(toolConfig: JsonElement?): JsonElement? {
    val config = (toolConfig as? JsonObject)?.get("functionCallingConfig") as? JsonObject ?: return null
    val allowed = (config["allowedFunctionNames"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
    return when (config.string("mode")) {
        "AUTO" -> JsonPrimitive("auto")
        "NONE" -> JsonPrimitive("none")
        "ANY" -> if (allowed != null) buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", allowed) } } else JsonPrimitive("required")
        else -> null
    }
}

private fun chatToolChoiceToGemini(choice: JsonElement?, disableParallel: Boolean): JsonObject? {
    if (choice == null && !disableParallel) return null
    val mode = when {
        choice is JsonPrimitive && choice.contentOrNull == "required" -> "ANY"
        choice is JsonPrimitive && choice.contentOrNull == "none" -> "NONE"
        choice is JsonObject && choice.string("type") == "function" -> "ANY"
        else -> "AUTO"
    }
    return buildJsonObject { putJsonObject("functionCallingConfig") { put("mode", mode) } }
}

private fun geminiToolChoiceToMessages(toolConfig: JsonElement?): JsonObject? {
    val mode = ((toolConfig as? JsonObject)?.get("functionCallingConfig") as? JsonObject)?.string("mode") ?: return null
    return buildJsonObject { put("type", when (mode) { "AUTO" -> "auto"; "NONE" -> "none"; else -> "any" }) }
}

private fun geminiToolChoiceToResponses(toolConfig: JsonElement?): JsonElement? =
    when (((toolConfig as? JsonObject)?.get("functionCallingConfig") as? JsonObject)?.string("mode")) {
        "AUTO" -> JsonPrimitive("auto")
        "NONE" -> JsonPrimitive("none")
        "ANY" -> JsonPrimitive("required")
        else -> null
    }

private fun responsesToolChoiceToGemini(choice: JsonElement?): JsonObject? {
    if (choice == null) return null
    val mode = when {
        choice is JsonPrimitive && choice.contentOrNull == "required" -> "ANY"
        choice is JsonPrimitive && choice.contentOrNull == "none" -> "NONE"
        choice is JsonObject -> "ANY"
        else -> "AUTO"
    }
    return buildJsonObject { putJsonObject("functionCallingConfig") { put("mode", mode) } }
}

private fun geminiThinkingToEffort(config: JsonObject?): String? {
    val thinking = config?.get("thinkingConfig") as? JsonObject ?: return null
    if (thinking.boolean("includeThoughts") == false) return "none"
    val budget = thinking.long("thinkingBudget") ?: return if (thinking.boolean("includeThoughts") == true) "medium" else null
    return when {
        budget == 0L -> "none"
        budget < 0L -> "medium"
        budget <= 1024 -> "low"
        budget <= 4096 -> "medium"
        budget <= 8192 -> "high"
        else -> "max"
    }
}

private fun thinkingBudget(effort: String): Long = when (effort) {
    "none" -> 0
    "minimal", "low" -> 1024
    "medium" -> 4096
    "high" -> 8192
    "xhigh", "max" -> 16384
    else -> unsupported("reasoning_effort", "未知 reasoning effort: $effort")
}

private fun geminiFinishToChat(finishReason: String?, hasTools: Boolean): String = when (finishReason) {
    "MAX_TOKENS" -> "length"
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL" -> "content_filter"
    null, "STOP" -> if (hasTools) "tool_calls" else "stop"
    else -> "stop"
}

private fun chatFinishToGemini(finishReason: String?, hasRefusal: Boolean): String = when {
    hasRefusal || finishReason == "content_filter" -> "SAFETY"
    finishReason == "length" -> "MAX_TOKENS"
    else -> "STOP"
}

private fun geminiFinishToMessages(finishReason: String?): String = when (finishReason) {
    "MAX_TOKENS" -> "max_tokens"
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL" -> "refusal"
    else -> "end_turn"
}

private fun messagesStopToGemini(stopReason: String?): String = when (stopReason) {
    "max_tokens" -> "MAX_TOKENS"
    "refusal" -> "SAFETY"
    else -> "STOP"
}

private fun responsesStatusToGemini(status: String?, incompleteReason: String?): String = when {
    status == "incomplete" && incompleteReason == "content_filter" -> "SAFETY"
    status == "incomplete" -> "MAX_TOKENS"
    else -> "STOP"
}

private fun geminiUsageToChat(usage: JsonObject?): JsonObject? = TokenUsage.fromGemini(usage)?.toChat()
private fun chatUsageToGemini(usage: JsonObject?): JsonObject? = TokenUsage.fromChat(usage)?.toGemini()
private fun geminiUsageToMessages(usage: JsonObject?): JsonObject? = TokenUsage.fromGemini(usage)?.toMessages()
private fun messagesUsageToGemini(usage: JsonObject?): JsonObject? = TokenUsage.fromMessages(usage)?.toGemini()
private fun geminiUsageToResponses(usage: JsonObject?): JsonObject? = TokenUsage.fromGemini(usage)?.toResponses()
private fun responsesUsageToGemini(usage: JsonObject?): JsonObject? = TokenUsage.fromResponses(usage)?.toGemini()
