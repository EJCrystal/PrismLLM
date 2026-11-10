package dev.soven.prismllm.protocol

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

private val geminiSafetyReasons = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL")
private val geminiRequestFields = setOf(
    "contents", "systemInstruction", "generationConfig", "tools", "toolConfig", "safetySettings", "cachedContent",
    "prompt_cache_key", "client_metadata",
)
private val geminiGenerationConfigFields = setOf(
    "maxOutputTokens", "temperature", "topP", "topK", "stopSequences", "seed",
    "frequencyPenalty", "presencePenalty", "responseLogprobs", "logprobs",
    "responseMimeType", "responseJsonSchema", "thinkingConfig", "candidateCount", "responseModalities",
)

internal fun validateGeminiRequest(request: JsonObject) {
    rejectUnknownFields(request, geminiRequestFields, "request")
    if (request["cachedContent"].let { it != null && it !is JsonNull }) unsupported("cachedContent", "协议转换不支持 Gemini cachedContent")
    if ((request["safetySettings"] as? JsonArray)?.isNotEmpty() == true) unsupported("safetySettings", "协议转换不支持 Gemini safetySettings")
    val config = request["generationConfig"] as? JsonObject
    config?.let { rejectUnknownFields(it, geminiGenerationConfigFields, "generationConfig") }
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
    if (config?.get("topK").let { it != null && it !is JsonNull }) unsupported("generationConfig.topK", "Chat Completions 不支持 top_k")
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
    config?.string("responseMimeType")?.takeIf { it != "text/plain" }?.let { mime ->
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
            "user" -> chatContentToGeminiParts(message["content"], "messages[$index].content").takeIf { it.isNotEmpty() }?.let { parts ->
                contents += buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(parts))
                }
            }
            "assistant" -> {
                val parts = mutableListOf<JsonObject>()
                message.textContentStrict("content", "messages[$index].content").takeIf { it.isNotEmpty() }?.let { text ->
                    parts += buildJsonObject { put("text", text) }
                }
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
            "tool" -> {
                val callId = message.string("tool_call_id") ?: unsupported("messages[$index].tool_call_id", "tool 消息缺少 tool_call_id")
                val callName = callNames[callId] ?: unsupported("messages[$index].tool_call_id", "找不到对应的工具调用")
                val result = chatContentToToolResultContent(message["content"], "messages[$index].content")
                contents += buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(listOf(buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", callName)
                            putJsonObject("response") { put("content", result.text) }
                            if (result.images.isNotEmpty()) {
                                put("parts", buildJsonArray { result.images.forEach { add(it.toGeminiPart()) } })
                            }
                        }
                    })))
                }
            }
            else -> unsupported("messages[$index].role", "不支持的消息角色")
        }
    }
    if (system.isNotEmpty()) put("systemInstruction", buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", system.joinToString("\n")) }) })
    })
    put("contents", withGeminiThoughtSignatures(mergeGeminiToolContents(contents)))
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
                            put("parameters", geminiParameters(function["parameters"], "tools[$index].function.parameters"))
                        })
                    }
                })
            })
        })
    }
    chatToolChoiceToGemini(chat["tool_choice"], chat.boolean("parallel_tool_calls") == false)?.let { put("toolConfig", it) }
}

internal fun geminiToChatResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    val responseId = response.string("responseId") ?: "chatcmpl-${System.currentTimeMillis()}"
    put("id", responseId)
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
        val callId = call.string("id") ?: "call_${responseId}_$index"
        buildJsonObject {
            put("id", callId)
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
            chatFinishToGemini(choice?.string("finish_reason"), message?.string("refusal") != null)?.let {
                put("finishReason", it)
            }
            put("index", 0)
        })
    })
    chatUsageToGemini(chat["usage"] as? JsonObject)?.let { put("usageMetadata", it) }
}

internal fun geminiToMessagesRequest(request: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateGeminiRequest(request)
    put("model", upstreamModel)
    val config = request["generationConfig"] as? JsonObject
    rejectUnsupportedGeminiConfig(
        config, setOf("seed", "frequencyPenalty", "presencePenalty", "responseLogprobs", "logprobs"), "Messages",
    )
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
    config?.string("responseMimeType")?.takeIf { it != "text/plain" }?.let { mime ->
        if (mime == "application/json") unsupported("generationConfig.responseMimeType", "Anthropic Messages 不支持 OpenAI 结构化输出参数")
        unsupported("generationConfig.responseMimeType", "不支持响应类型 $mime")
    }
}

internal fun messagesToGeminiRequest(messages: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
    validateMessagesRequest(messages)
    val contents = mutableListOf<JsonObject>()
    val callNames = mutableMapOf<String, String>()
    messages.textContentStrict("system", "system").takeIf { it.isNotBlank() }?.let { system ->
        put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
    }
    joinSplitToolCalls(messages.array("messages")).forEachIndexed { index, element ->
        val message = element as? JsonObject ?: unsupported("messages[$index]", "消息必须是对象")
        val parts = mutableListOf<JsonObject>()
        when (val content = message["content"]) {
            JsonNull -> Unit
            is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text -> parts += buildJsonObject { put("text", text) } }
            is JsonArray -> content.forEachIndexed { blockIndex, blockElement ->
                val block = blockElement as? JsonObject ?: unsupported("messages[$index].content[$blockIndex]", "内容块必须是对象")
                when (block.string("type")) {
                    "text" -> block.string("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                        parts += buildJsonObject { put("text", text) }
                    }
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
                        val result = block.toolResultContent("messages[$index].content[$blockIndex].content")
                        parts += buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", callName)
                                putJsonObject("response") { put("content", result.text) }
                                // Gemini 的 functionResponse 自带 parts，工具返回的图片直接放在这里。
                                if (result.images.isNotEmpty()) {
                                    put("parts", buildJsonArray { result.images.forEach { add(it.toGeminiPart()) } })
                                }
                            }
                        }
                    }
                    "image" -> parts += imagePartFromAnthropic(block, "messages[$index].content[$blockIndex]").toGeminiPart()
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
    put("contents", withGeminiThoughtSignatures(mergeGeminiToolContents(contents)))
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
                            put("parameters", geminiParameters(tool["input_schema"], "tools[$index].input_schema"))
                        })
                    }
                })
            })
        })
    }
    messagesToolChoiceToGemini(messages["tool_choice"] as? JsonObject)?.let { put("toolConfig", it) }
}

internal fun geminiToMessagesResponse(response: JsonObject, model: String): JsonObject = buildJsonObject {
    val responseId = response.string("responseId") ?: "msg_${System.currentTimeMillis()}"
    put("id", responseId)
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
                    val call = obj["functionCall"] as JsonObject
                    val callId = call.string("id") ?: "toolu_${responseId}_$index"
                    put("type", "tool_use")
                    put("id", callId)
                    put("name", call.string("name").orEmpty())
                    put("input", call["args"] ?: buildJsonObject { })
                })
                obj.boolean("thought") == true -> Unit
                obj.string("text") != null -> add(messageTextBlock(obj.string("text").orEmpty()))
            }
        }
    })
    val hasToolUse = parts.any { (it as? JsonObject)?.get("functionCall") is JsonObject }
    put("stop_reason", geminiFinishToMessages(candidate?.string("finishReason"), hasToolUse))
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
            messagesStopToGemini(messages.string("stop_reason"))?.let { put("finishReason", it) }
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
    rejectUnsupportedGeminiConfig(
        config,
        setOf("stopSequences", "topK", "seed", "frequencyPenalty", "presencePenalty", "responseLogprobs", "logprobs"),
        "Responses",
    )
    config?.long("maxOutputTokens")?.let { put("max_output_tokens", it) }
    config?.number("temperature")?.let { put("temperature", it) }
    config?.number("topP")?.let { put("top_p", it) }
    put("input", geminiContentsToResponses(request["contents"]))
    geminiToolsToResponses(request["tools"])?.let { put("tools", it) }
    geminiToolChoiceToResponses(request["toolConfig"])?.let { put("tool_choice", it) }
    geminiThinkingToEffort(config)?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
    config?.string("responseMimeType")?.takeIf { it != "text/plain" }?.let { mime ->
        if (mime != "application/json") unsupported("generationConfig.responseMimeType", "不支持响应类型 $mime")
        val schema = config["responseJsonSchema"]
        putJsonObject("text") {
            putJsonObject("format") {
                if (schema != null) {
                    put("type", "json_schema")
                    put("name", "response")
                    put("schema", schema)
                } else {
                    put("type", "json_object")
                }
            }
        }
    }
}

internal fun responsesToGeminiRequest(responses: JsonObject, upstreamModel: String): JsonObject {
    // Codex 的 Responses Lite 形状把工具放在 additional_tools 输入项里；转换前先抬升为请求级 tools。
    val responses = liftAdditionalTools(responses)
    return buildJsonObject {
    validateResponsesRequest(responses)
    val system = mutableListOf<String>()
    responses.string("instructions")?.takeIf { it.isNotBlank() }?.let { system += it }
    val contents = mutableListOf<JsonObject>()
    val callNames = mutableMapOf<String, String>()
    when (val input = responses["input"]) {
        JsonNull -> Unit
        is JsonPrimitive -> input.content.takeIf { it.isNotEmpty() }?.let { text ->
            contents += buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) }) }
        }
        is JsonArray -> input.forEachIndexed { index, element ->
            val item = element as? JsonObject ?: unsupported("input[$index]", "输入项必须是对象")
            when (item.string("type")) {
                "message", null -> {
                    val role = item.string("role")
                    when (role) {
                        "system", "developer" -> system += item.textContentStrict("content", "input[$index].content")
                        else -> {
                            val parts = responsesContentToGeminiParts(item["content"], "input[$index].content")
                            if (parts.isNotEmpty()) contents += buildJsonObject {
                                put("role", if (role == "assistant") "model" else "user")
                                put("parts", JsonArray(parts))
                            }
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
                    val result = responsesToolOutputContent(item["output"], "input[$index].output")
                    contents += buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", callName)
                                putJsonObject("response") { put("content", result.text) }
                                if (result.images.isNotEmpty()) {
                                    put("parts", buildJsonArray { result.images.forEach { add(it.toGeminiPart()) } })
                                }
                            }
                        }) })
                    }
                }
                "reasoning" -> Unit
                else -> unsupported("input[$index].type", "第一版不支持 Responses 输入项")
            }
        }
        JsonNull, null -> Unit
        else -> unsupported("input", "input 必须是字符串或数组")
    }
    put("contents", withGeminiThoughtSignatures(mergeGeminiToolContents(contents)))
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
                        put("parameters", geminiParameters(tool["parameters"], "tools[$index].parameters"))
                    })
                }
            })
        }) })
    }
    responsesToolChoiceToGemini(responses["tool_choice"])?.let { put("toolConfig", it) }
    }
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
                val call = obj["functionCall"] as JsonObject
                val callId = call.string("id") ?: "call_${id}_$index"
                put("type", "function_call")
                put("id", "fc_${id}_$index")
                put("call_id", callId)
                put("name", call.string("name").orEmpty())
                put("arguments", (call["args"] ?: buildJsonObject { }).toString())
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
    val incomplete = finish == "MAX_TOKENS" || finish in geminiSafetyReasons
    put("status", if (incomplete) "incomplete" else "completed")
    if (incomplete) putJsonObject("incomplete_details") {
        put("reason", if (finish == "MAX_TOKENS") "max_output_tokens" else "content_filter")
    }
    put("model", model)
    put("output", JsonArray(output))
    geminiUsageToResponses(response["usageMetadata"] as? JsonObject)?.let { put("usage", it) }
}

internal fun responsesToGeminiResponse(responses: JsonObject, model: String): JsonObject = buildJsonObject {
    var hasRefusal = false
    val parts = responses.array("output").mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: unsupported("output[$index]", "输出项必须是对象")
        when (item.string("type")) {
            "message" -> {
                val text = item.array("content").joinToString("") { part ->
                    val obj = part as? JsonObject ?: return@joinToString ""
                    if (obj.string("type") == "refusal") {
                        hasRefusal = true
                        obj.string("refusal").orEmpty()
                    } else {
                        obj.string("text").orEmpty()
                    }
                }
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
            put(
                "finishReason",
                if (hasRefusal) "SAFETY"
                else responsesStatusToGemini(responses.string("status"), (responses["incomplete_details"] as? JsonObject)?.string("reason")),
            )
            put("index", 0)
        })
    })
    responsesUsageToGemini(responses["usage"] as? JsonObject)?.let { put("usageMetadata", it) }
}

private const val GEMINI_SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator"

private fun withGeminiThoughtSignatures(contents: JsonArray): JsonArray = JsonArray(contents.map { element ->
    val content = element as? JsonObject ?: return@map element
    if (content.string("role") != "model") return@map content
    val parts = content.array("parts")
    val first = parts.indexOfFirst { (it as? JsonObject)?.get("functionCall") is JsonObject }
    if (first < 0) return@map content
    val part = parts[first] as JsonObject
    if (part.containsKey("thoughtSignature")) return@map content
    val patched = parts.toMutableList().also {
        it[first] = JsonObject(part + ("thoughtSignature" to JsonPrimitive(GEMINI_SKIP_THOUGHT_SIGNATURE)))
    }
    JsonObject(content + ("parts" to JsonArray(patched)))
})

private fun mergeGeminiToolContents(contents: List<JsonObject>): JsonArray {
    val merged = mutableListOf<JsonObject>()
    contents.forEach { content ->
        val previous = merged.lastOrNull()
        val role = content.string("role")
        val field = if (role == "model") "functionCall" else "functionResponse"
        val parts = content.array("parts")
        val previousParts = previous?.array("parts").orEmpty()
        if (previous != null && previous.string("role") == role && parts.isNotEmpty() && previousParts.isNotEmpty() &&
            (previousParts + parts).all { (it as? JsonObject)?.get(field) is JsonObject }
        ) {
            merged[merged.lastIndex] = JsonObject(previous + ("parts" to JsonArray(previousParts + parts)))
        } else {
            merged += content
        }
    }
    return JsonArray(merged)
}

private fun rejectUnsupportedGeminiConfig(config: JsonObject?, fields: Set<String>, target: String) {
    fields.firstOrNull { config?.get(it).let { v -> v != null && v !is JsonNull } }?.let {
        unsupported("generationConfig.$it", "当前 $target 转换不支持 Gemini $it")
    }
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
            val messages = mutableListOf<JsonObject>()
            val toolImages = ChatToolImageRelocator(messages)
            val pendingIds = mutableMapOf<String, ArrayDeque<String>>()
            contents.forEachIndexed { index, element ->
                val content = element as? JsonObject ?: unsupported("contents[$index]", "内容必须是对象")
                val role = if (content.string("role") == "model") "assistant" else "user"
                val parts = mutableListOf<JsonElement>()
                val text = mutableListOf<String>()
                fun flushText() {
                    if (text.isEmpty()) return
                    parts += chatTextPart(text.joinToString(""))
                    text.clear()
                }
                val toolCalls = mutableListOf<JsonObject>()
                content.array("parts").forEachIndexed { partIndex, part ->
                    val obj = part as? JsonObject ?: unsupported("contents[$index].parts[$partIndex]", "part 必须是对象")
                    val call = obj["functionCall"] as? JsonObject
                    val response = obj["functionResponse"] as? JsonObject
                    when {
                        call != null -> {
                            val name = call.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionCall.name", "functionCall 缺少 name")
                            val callId = "call_${index}_$partIndex"
                            pendingIds.getOrPut(name) { ArrayDeque() }.addLast(callId)
                            toolImages.rememberToolName(callId, name)
                            toolCalls += buildJsonObject {
                                put("id", callId)
                                put("type", "function")
                                putJsonObject("function") { put("name", name); put("arguments", (call["args"] ?: buildJsonObject { }).toString()) }
                            }
                        }
                        response != null -> {
                            val name = response.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "functionResponse 缺少 name")
                            val callId = pendingIds[name]?.removeFirstOrNull() ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "找不到对应的函数调用")
                            val result = geminiFunctionResponseContent(response, "contents[$index].parts[$partIndex].functionResponse")
                            messages += buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", callId)
                                put("content", toolImages.toolText(result))
                            }
                            toolImages.defer(callId, result.images)
                        }
                        obj.boolean("thought") == true -> unsupported("contents[$index].parts[$partIndex]", "Chat 不支持 Gemini thinking 历史")
                        obj.string("text") != null -> text += obj.string("text").orEmpty()
                        obj["inlineData"] != null || obj["fileData"] != null -> {
                            flushText()
                            parts += imagePartFromGemini(obj, "contents[$index].parts[$partIndex]").toChatPart()
                        }
                        else -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini part")
                    }
                }
                flushText()
                if (parts.isNotEmpty() || toolCalls.isNotEmpty()) {
                    if (role == "assistant") toolImages.flush()
                    // 用户内容正好可以承载上一条工具结果里的图片，避免图片单独成一条 user 消息。
                    val contentParts = if (role == "assistant" || parts.isEmpty()) parts else toolImages.take() + parts
                    messages += buildJsonObject {
                        put("role", role)
                        when {
                            contentParts.isEmpty() -> put("content", JsonNull)
                            contentParts.size == 1 && (contentParts[0] as JsonObject).string("type") == "text" ->
                                put("content", (contentParts[0] as JsonObject).string("text").orEmpty())
                            else -> put("content", JsonArray(contentParts))
                        }
                        if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                    }
                }
            }
            toolImages.flush()
            addAll(messages)
        }
        else -> Unit
    }
}

private fun geminiContentsToMessages(contents: JsonElement?): JsonArray = buildJsonArray {
    when (contents) {
        JsonNull -> Unit
        is JsonPrimitive -> add(messageWithBlocks("user", listOf(messageTextBlock(contents.content))))
        is JsonArray -> {
        val pendingIds = mutableMapOf<String, ArrayDeque<String>>()
        contents.forEachIndexed { index, element ->
            val content = element as? JsonObject ?: unsupported("contents[$index]", "内容必须是对象")
            val blocks = content.array("parts").mapIndexedNotNull { partIndex, part ->
                val obj = part as? JsonObject ?: unsupported("contents[$index].parts[$partIndex]", "part 必须是对象")
                when {
                    obj["functionCall"] is JsonObject -> {
                        val name = (obj["functionCall"] as JsonObject).string("name").orEmpty()
                        val callId = "toolu_${index}_$partIndex"
                        pendingIds.getOrPut(name) { ArrayDeque() }.addLast(callId)
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", callId)
                            put("name", name)
                            put("input", (obj["functionCall"] as JsonObject)["args"] ?: buildJsonObject { })
                        }
                    }
                    obj["functionResponse"] is JsonObject -> {
                        val response = obj["functionResponse"] as JsonObject
                        val name = response.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "functionResponse 缺少 name")
                        val result = geminiFunctionResponseContent(response, "contents[$index].parts[$partIndex].functionResponse")
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", pendingIds[name]?.removeFirstOrNull() ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "找不到对应的工具调用"))
                            if (result.images.isEmpty()) {
                                put("content", result.text)
                            } else {
                                put("content", buildJsonArray {
                                    if (result.text.isNotEmpty()) add(messageTextBlock(result.text))
                                    result.images.forEach { add(it.toAnthropicBlock()) }
                                })
                            }
                        }
                    }
                    obj["inlineData"] != null || obj["fileData"] != null ->
                        imagePartFromGemini(obj, "contents[$index].parts[$partIndex]").toAnthropicBlock()
                    obj.boolean("thought") == true -> unsupported("contents[$index].parts[$partIndex]", "Gemini thinking 历史缺少 Anthropic 签名，无法转换为 Messages")
                    else -> obj.string("text")?.takeIf { it.isNotEmpty() }?.let { messageTextBlock(it) }
                }
            }
            if (blocks.isNotEmpty()) add(messageWithBlocks(if (content.string("role") == "model") "assistant" else "user", blocks))
        }
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
        is JsonArray -> {
        val pendingIds = mutableMapOf<String, ArrayDeque<String>>()
        contents.forEachIndexed { index, element ->
            val content = element as? JsonObject ?: unsupported("contents[$index]", "内容必须是对象")
            val role = if (content.string("role") == "model") "assistant" else "user"
            val parts = mutableListOf<JsonElement>()
            val text = mutableListOf<String>()
            fun flushText() {
                if (text.isEmpty()) return
                parts += buildJsonObject {
                    put("type", if (role == "assistant") "output_text" else "input_text")
                    put("text", text.joinToString(""))
                }
                text.clear()
            }
            val toolCalls = mutableListOf<JsonObject>()
            content.array("parts").forEachIndexed { partIndex, part ->
                val obj = part as? JsonObject ?: unsupported("contents[$index].parts[$partIndex]", "part 必须是对象")
                val call = obj["functionCall"] as? JsonObject
                val response = obj["functionResponse"] as? JsonObject
                when {
                    call != null -> {
                        val name = call.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionCall.name", "functionCall 缺少 name")
                        val callId = "call_${index}_$partIndex"
                        pendingIds.getOrPut(name) { ArrayDeque() }.addLast(callId)
                        toolCalls += buildJsonObject {
                            put("type", "function_call")
                            put("call_id", callId)
                            put("name", name)
                            put("arguments", (call["args"] ?: buildJsonObject { }).toString())
                        }
                    }
                    response != null -> {
                        val name = response.string("name") ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "functionResponse 缺少 name")
                        val result = geminiFunctionResponseContent(response, "contents[$index].parts[$partIndex].functionResponse")
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", pendingIds[name]?.removeFirstOrNull() ?: unsupported("contents[$index].parts[$partIndex].functionResponse.name", "找不到对应的函数调用"))
                            if (result.images.isEmpty()) {
                                put("output", result.text)
                            } else {
                                put("output", buildJsonArray {
                                    if (result.text.isNotEmpty()) add(responsesTextPart(result.text))
                                    result.images.forEach { add(it.toResponsesPart()) }
                                })
                            }
                        })
                    }
                    obj["inlineData"] != null || obj["fileData"] != null -> {
                        flushText()
                        parts += imagePartFromGemini(obj, "contents[$index].parts[$partIndex]").toResponsesPart()
                    }
                    obj.boolean("thought") == true -> unsupported("contents[$index].parts[$partIndex]", "Responses 不支持 Gemini thinking 历史")
                    obj.string("text") != null -> text += obj.string("text").orEmpty()
                    else -> unsupported("contents[$index].parts[$partIndex]", "第一版不支持 Gemini part")
                }
            }
            flushText()
            if (parts.isNotEmpty()) add(buildJsonObject {
                put("type", "message")
                put("role", role)
                put("content", JsonArray(parts))
            })
            addAll(toolCalls)
        }
        }
        else -> Unit
    }
}

/** Gemini 的 functionResponse 自带 parts，工具返回的图片就放在那里。 */
private fun geminiFunctionResponseContent(response: JsonObject, path: String): ToolResultContent {
    val text = mutableListOf(geminiFunctionResponseText(response["response"]))
    val images = mutableListOf<ImagePart>()
    response.array("parts").forEachIndexed { index, element ->
        val obj = element as? JsonObject ?: unsupported("$path.parts[$index]", "part 必须是对象")
        val partText = obj.string("text")
        if (partText != null) text += partText else images += imagePartFromGemini(obj, "$path.parts[$index]")
    }
    return ToolResultContent(text.filter { it.isNotEmpty() }.joinToString("\n"), images)
}

/** Gemini CLI 把工具输出包在 {output} 或 {error} 里，其它形状按 JSON 原样带回。 */
private fun geminiFunctionResponseText(body: JsonElement?): String = when (body) {
    null, JsonNull -> ""
    is JsonPrimitive -> body.contentOrNull.orEmpty()
    is JsonObject -> body.string("content")
        ?: body.string("output").takeIf { body.size == 1 }
        ?: body.string("error").takeIf { body.size == 1 }
        ?: body.toString()
    else -> body.toString()
}

/** Chat 消息内容转为 Gemini parts。 */
private fun chatContentToGeminiParts(content: JsonElement?, path: String): List<JsonObject> = when (content) {
    null, JsonNull -> emptyList()
    is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }
        ?.let { listOf(buildJsonObject { put("text", it) }) } ?: emptyList()
    is JsonArray -> content.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: unsupported("$path[$index]", "内容块必须是对象")
        when (obj.string("type")) {
            "text" -> obj.string("text")?.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("text", it) } }
            "image_url" -> imagePartFromChat(obj, "$path[$index]").toGeminiPart()
            "input_image" -> imagePartFromResponses(obj, "$path[$index]").toGeminiPart()
            else -> unsupported("$path[$index].type", "第一版不支持该 Chat 内容")
        }
    }
    else -> unsupported(path, "无法识别的内容")
}

/** Responses 消息内容转为 Gemini parts。 */
private fun responsesContentToGeminiParts(content: JsonElement?, path: String): List<JsonObject> = when (content) {
    null, JsonNull -> emptyList()
    is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }
        ?.let { listOf(buildJsonObject { put("text", it) }) } ?: emptyList()
    is JsonArray -> content.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: unsupported("$path[$index]", "内容块必须是对象")
        when (obj.string("type")) {
            "text", "input_text", "output_text" -> obj.string("text")?.takeIf { it.isNotEmpty() }
                ?.let { buildJsonObject { put("text", it) } }
            "refusal" -> obj.string("refusal")?.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("text", it) } }
            "input_image" -> imagePartFromResponses(obj, "$path[$index]").toGeminiPart()
            else -> unsupported("$path[$index].type", "第一版不支持该 Responses 内容")
        }
    }
    else -> unsupported(path, "无法识别的内容")
}

private fun validateGeminiTool(tool: JsonObject, path: String) {
    tool.keys.firstOrNull { it != "functionDeclarations" }?.let {
        unsupported("$path.$it", "跨协议转换仅支持 Gemini functionDeclarations，不支持内置工具 $it")
    }
}

private fun geminiToolsToChat(tools: JsonElement?): JsonArray? = when (tools) {
    is JsonArray -> buildJsonArray {
        tools.forEachIndexed { index, element ->
            val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
            validateGeminiTool(tool, "tools[$index]")
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
            validateGeminiTool(tool, "tools[$index]")
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
            validateGeminiTool(tool, "tools[$index]")
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
    val forced = ((choice as? JsonObject)?.get("function") as? JsonObject)?.string("name")
        ?: (choice as? JsonObject)?.string("name")
    val mode = when {
        choice is JsonPrimitive && choice.contentOrNull == "required" -> "ANY"
        choice is JsonPrimitive && choice.contentOrNull == "none" -> "NONE"
        choice is JsonObject && choice.string("type") == "function" -> "ANY"
        else -> "AUTO"
    }
    return buildJsonObject {
        putJsonObject("functionCallingConfig") {
            put("mode", mode)
            forced?.let { put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(it)) }) }
        }
    }
}

private fun geminiToolChoiceToMessages(toolConfig: JsonElement?): JsonObject? {
    val config = (toolConfig as? JsonObject)?.get("functionCallingConfig") as? JsonObject ?: return null
    val mode = config.string("mode") ?: return null
    val allowed = (config["allowedFunctionNames"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
    return buildJsonObject {
        when {
            mode == "AUTO" -> put("type", "auto")
            mode == "NONE" -> put("type", "none")
            mode == "ANY" && allowed != null -> {
                put("type", "tool")
                put("name", allowed)
            }
            mode == "ANY" -> put("type", "any")
            else -> put("type", "auto")
        }
    }
}

private fun messagesToolChoiceToGemini(choice: JsonObject?): JsonObject? {
    if (choice == null) return null
    val type = choice.string("type")
    val mode = when (type) {
        "any" -> "ANY"
        "none" -> "NONE"
        "tool" -> "ANY"
        else -> "AUTO"
    }
    val forced = if (type == "tool") {
        choice.string("name") ?: unsupported("tool_choice.name", "tool_choice 缺少 name")
    } else null
    return buildJsonObject {
        putJsonObject("functionCallingConfig") {
            put("mode", mode)
            forced?.let { put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(it)) }) }
        }
    }
}

private fun geminiToolChoiceToResponses(toolConfig: JsonElement?): JsonElement? {
    val config = (toolConfig as? JsonObject)?.get("functionCallingConfig") as? JsonObject ?: return null
    val allowed = (config["allowedFunctionNames"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
    return when (config.string("mode")) {
        "AUTO" -> JsonPrimitive("auto")
        "NONE" -> JsonPrimitive("none")
        "ANY" -> if (allowed != null) buildJsonObject { put("type", "function"); put("name", allowed) } else JsonPrimitive("required")
        else -> null
    }
}

private fun responsesToolChoiceToGemini(choice: JsonElement?): JsonObject? {
    if (choice == null) return null
    val forced = (choice as? JsonObject)?.string("name")
        ?: ((choice as? JsonObject)?.get("function") as? JsonObject)?.string("name")
    val mode = when {
        choice is JsonPrimitive && choice.contentOrNull == "required" -> "ANY"
        choice is JsonPrimitive && choice.contentOrNull == "none" -> "NONE"
        choice is JsonObject && choice.string("type") == "auto" -> "AUTO"
        choice is JsonObject && choice.string("type") == "none" -> "NONE"
        choice is JsonObject && choice.string("type") == "required" -> "ANY"
        choice is JsonObject -> "ANY"
        else -> "AUTO"
    }
    return buildJsonObject {
        putJsonObject("functionCallingConfig") {
            put("mode", mode)
            forced?.let { put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(it)) }) }
        }
    }
}

private fun geminiThinkingToEffort(config: JsonObject?): String? {
    val thinking = config?.get("thinkingConfig") as? JsonObject ?: return null
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
    in geminiSafetyReasons -> "content_filter"
    null, "STOP" -> if (hasTools) "tool_calls" else "stop"
    else -> "stop"
}

private fun chatFinishToGemini(finishReason: String?, hasRefusal: Boolean): String? = when {
    hasRefusal || finishReason == "content_filter" -> "SAFETY"
    finishReason == "length" -> "MAX_TOKENS"
    finishReason == "stop" || finishReason == "tool_calls" || finishReason == "function_call" -> "STOP"
    finishReason == null -> null
    else -> "STOP"
}

private fun geminiFinishToMessages(finishReason: String?, hasTools: Boolean): String = when (finishReason) {
    "MAX_TOKENS" -> "max_tokens"
    in geminiSafetyReasons -> "refusal"
    null, "STOP" -> if (hasTools) "tool_use" else "end_turn"
    else -> "end_turn"
}

private fun messagesStopToGemini(stopReason: String?): String? = when (stopReason) {
    "max_tokens" -> "MAX_TOKENS"
    "refusal" -> "SAFETY"
    "end_turn", "tool_use", "stop_sequence" -> "STOP"
    null -> null
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
