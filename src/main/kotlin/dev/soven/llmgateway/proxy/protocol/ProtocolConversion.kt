package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.max

/** 请求在发送上游前被判定为无法无损转换。 */
class ProtocolConversionException(
    val field: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * Chat Completions JSON 作为内部规范格式。Messages/Responses 先转换到规范格式，
 * 再转换到目标协议，避免维护 6 套点对点映射。
 */
object ProtocolConversion {
    private val messagesRequestFields = setOf(
        "model", "messages", "system", "max_tokens", "stream", "temperature", "top_p",
        "stop_sequences", "metadata", "tools", "tool_choice", "thinking",
    )
    private val responsesRequestFields = setOf(
        "model", "input", "instructions", "max_output_tokens", "stream", "temperature", "top_p",
        "tools", "tool_choice", "reasoning", "text", "user", "metadata", "parallel_tool_calls",
        "store", "include", "truncation",
    )
    private val chatRequestFields = setOf(
        "model", "messages", "stream", "temperature", "top_p", "max_tokens", "max_completion_tokens",
        "stop", "user", "reasoning_effort", "response_format", "tools", "tool_choice",
        "parallel_tool_calls", "stream_options",
    )

    fun convertRequest(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        request: JsonObject,
        upstreamModel: String,
    ): JsonObject {
        if (inbound == outbound) return request.withModel(upstreamModel)
        val chat = toChatRequest(inbound, request)
        return fromChatRequest(outbound, chat, upstreamModel)
    }

    fun convertResponse(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        response: JsonObject,
        model: String,
    ): JsonObject {
        if (inbound == outbound) return response
        val chat = toChatResponse(outbound, response, model)
        return fromChatResponse(inbound, chat, model)
    }

    fun streamConverter(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        model: String,
    ): ProtocolStreamConverter? =
        if (inbound == outbound) null else ProtocolStreamConverter(inbound, outbound, model)

    // ---------- inbound request -> canonical Chat request ----------

    private fun toChatRequest(protocol: GatewayProtocol, request: JsonObject): JsonObject = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> request
        GatewayProtocol.MESSAGES -> messagesToChatRequest(request)
        GatewayProtocol.RESPONSES -> responsesToChatRequest(request)
    }

    private fun messagesToChatRequest(request: JsonObject): JsonObject = buildJsonObject {
        rejectUnknownFields(request, messagesRequestFields, "request")
        rejectExtraMetadata(request["metadata"], "metadata")
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
            val toolCalls = mutableListOf<JsonObject>()
            val toolResults = mutableListOf<JsonObject>()
            when (val content = message["content"]) {
                is JsonPrimitive -> text += content.content
                is JsonArray -> content.forEachIndexed { partIndex, partElement ->
                    val part = partElement as? JsonObject
                        ?: unsupported("messages[$index].content[$partIndex]", "内容块必须是对象")
                    when (part.string("type")) {
                        "text" -> text += part.string("text").orEmpty()
                        "tool_use" -> toolCalls += chatToolCallFromAnthropic(part, "messages[$index].content[$partIndex]")
                        "tool_result" -> toolResults += chatToolMessageFromAnthropic(part, "messages[$index].content[$partIndex]")
                        "image", "document", "thinking", "redacted_thinking" -> unsupported(
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
            if (text.isNotEmpty() || toolCalls.isNotEmpty()) {
                messages += buildJsonObject {
                    put("role", role)
                    if (text.isNotEmpty()) put("content", text.joinToString("\n")) else put("content", JsonNull)
                    if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
                }
            }
        }
        put("messages", JsonArray(messages))
        request["tools"]?.let { toolsElement ->
            val tools = toolsElement as? JsonArray ?: unsupported("tools", "tools 必须是数组")
            put("tools", JsonArray(tools.mapIndexed { index, element ->
                val tool = element as? JsonObject ?: unsupported("tools[$index]", "工具必须是对象")
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
        request["tool_choice"]?.let { choice -> put("tool_choice", anthropicToolChoiceToChat(choice as? JsonObject)) }
        if ((request["tool_choice"] as? JsonObject)?.boolean("disable_parallel_tool_use") == true) {
            put("parallel_tool_calls", false)
        }
    }

    private fun responsesToChatRequest(request: JsonObject): JsonObject = buildJsonObject {
        request.string("previous_response_id")?.let {
            unsupported("previous_response_id", "协议转换不支持 Responses 有状态续接")
        }
        rejectUnknownFields(request, responsesRequestFields, "request")
        (request["store"] as? JsonPrimitive)?.let { store ->
            if (store.booleanOrNull != false) unsupported("store", "协议转换仅支持 Responses store=false")
        }
        (request["include"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
            unsupported("include", "协议转换不支持 Responses include 扩展")
        }
        request.string("truncation")?.takeIf { it != "disabled" }?.let {
            unsupported("truncation", "协议转换仅支持 truncation=disabled")
        }
        rejectExtraMetadata(request["metadata"], "metadata")
        request.string("model")?.let { put("model", it) }
        request.boolean("stream")?.let { put("stream", it) }
        request.number("temperature")?.let { put("temperature", it) }
        request.number("top_p")?.let { put("top_p", it) }
        request.number("max_output_tokens")?.let { put("max_tokens", it) }
        request.string("user")?.let { put("user", it) }
            ?: (request["metadata"] as? JsonObject)?.string("user_id")?.let { put("user", it) }
        (request["reasoning"] as? JsonObject)?.string("effort")?.let { put("reasoning_effort", it) }
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
                    "function_call" -> messages += buildJsonObject {
                        put("role", "assistant")
                        put("content", JsonNull)
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("id", item.string("call_id") ?: item.string("id") ?: unsupported("input[$index].call_id", "function_call 缺少 call_id"))
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", item.string("name") ?: unsupported("input[$index].name", "function_call 缺少 name"))
                                    put("arguments", item.string("arguments").orEmpty())
                                }
                            })
                        })
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

    // ---------- canonical Chat request -> outbound protocol ----------

    private fun fromChatRequest(
        protocol: GatewayProtocol,
        chat: JsonObject,
        upstreamModel: String,
    ): JsonObject = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> chat.withModel(upstreamModel).let { request ->
            if (request.boolean("stream") != true) return@let request
            val options = request["stream_options"] as? JsonObject ?: JsonObject(emptyMap())
            JsonObject(request + ("stream_options" to JsonObject(options + ("include_usage" to JsonPrimitive(true)))))
        }
        GatewayProtocol.MESSAGES -> chatToMessagesRequest(chat, upstreamModel)
        GatewayProtocol.RESPONSES -> chatToResponsesRequest(chat, upstreamModel)
    }

    private fun chatToMessagesRequest(chat: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
        rejectUnknownFields(chat, chatRequestFields, "request")
        put("model", upstreamModel)
        chat.boolean("stream")?.let { put("stream", it) }
        chat.number("temperature")?.let { put("temperature", it) }
        chat.number("top_p")?.let { put("top_p", it) }
        put("max_tokens", chat.long("max_tokens") ?: chat.long("max_completion_tokens") ?: 4096L)
        chat.stringOrStringArray("stop")?.let { values -> put("stop_sequences", JsonArray(values.map(::JsonPrimitive))) }
        chat.string("user")?.let { putJsonObject("metadata") { put("user_id", it) } }
        chat.string("reasoning_effort")?.let { effort -> put("thinking", thinkingFromEffort(effort)) }
        if (chat["response_format"] != null) {
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
                    messages += chatMessageToAnthropic(message, "messages[$index]")
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
        chat["tools"]?.let { toolsElement ->
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
        val toolChoice = chat["tool_choice"]
        if (toolChoice != null) {
            put("tool_choice", chatToolChoiceToAnthropic(toolChoice, disableParallelToolUse))
        } else if (disableParallelToolUse && hasTools) {
            putJsonObject("tool_choice") {
                put("type", "auto")
                put("disable_parallel_tool_use", true)
            }
        }
    }

    private fun chatToResponsesRequest(chat: JsonObject, upstreamModel: String): JsonObject = buildJsonObject {
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
                            put("arguments", function.string("arguments").orEmpty())
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

    // ---------- outbound response -> canonical Chat response ----------

    private fun toChatResponse(protocol: GatewayProtocol, response: JsonObject, model: String): JsonObject = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> response
        GatewayProtocol.MESSAGES -> messagesToChatResponse(response, model)
        GatewayProtocol.RESPONSES -> responsesToChatResponse(response, model)
    }

    private fun messagesToChatResponse(response: JsonObject, model: String): JsonObject {
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

    private fun responsesToChatResponse(response: JsonObject, model: String): JsonObject {
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

    // ---------- canonical Chat response -> inbound response ----------

    private fun fromChatResponse(protocol: GatewayProtocol, chat: JsonObject, model: String): JsonObject = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> chat
        GatewayProtocol.MESSAGES -> chatToMessagesResponse(chat, model)
        GatewayProtocol.RESPONSES -> chatToResponsesResponse(chat, model)
    }

    private fun chatToMessagesResponse(chat: JsonObject, model: String): JsonObject {
        val choice = chat.array("choices").firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = mutableListOf<JsonObject>()
        message?.string("content")?.takeIf { it.isNotEmpty() }?.let { text ->
            content += buildJsonObject {
                put("type", "text")
                put("text", text)
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

    private fun chatToResponsesResponse(chat: JsonObject, model: String): JsonObject {
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
                put("arguments", function.string("arguments").orEmpty())
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

    // ---------- helpers ----------

    private fun responsesMessageToChat(item: JsonObject, path: String): JsonObject {
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

    private fun chatMessageToAnthropic(message: JsonObject, path: String): JsonObject {
        val role = message.string("role") ?: "user"
        val blocks = mutableListOf<JsonObject>()
        when (val content = message["content"]) {
            is JsonPrimitive -> if (content.content.isNotEmpty()) blocks += buildJsonObject {
                put("type", "text")
                put("text", content.content)
            }
            is JsonArray -> content.forEachIndexed { index, part ->
                val obj = part as? JsonObject ?: unsupported("$path.content[$index]", "内容块必须是对象")
                when (obj.string("type")) {
                    "text" -> blocks += buildJsonObject {
                        put("type", "text")
                        put("text", obj.string("text").orEmpty())
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

    private fun chatToolCallFromAnthropic(block: JsonObject, path: String): JsonObject = buildJsonObject {
        put("id", block.string("id") ?: unsupported("$path.id", "tool_use 缺少 id"))
        put("type", "function")
        putJsonObject("function") {
            put("name", block.string("name") ?: unsupported("$path.name", "tool_use 缺少 name"))
            put("arguments", block["input"]?.takeIf { it !is JsonNull }?.toString() ?: "{}")
        }
    }

    private fun chatToolMessageFromAnthropic(block: JsonObject, path: String): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", block.string("tool_use_id") ?: unsupported("$path.tool_use_id", "tool_result 缺少 tool_use_id"))
        put("content", block.toolResultContent("$path.content"))
    }

    /** Anthropic tool_result 的 content 既可以是字符串，也可以是 text/image 内容块数组。 */
    private fun JsonObject.toolResultContent(path: String): String = when (val value = this["content"]) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: unsupported("$path[$index]", "tool_result 内容块必须是对象")
            when (obj.string("type")) {
                "text" -> obj.string("text").orEmpty()
                "image" -> unsupported("$path[$index].type", "Chat Completions 不支持 tool_result 图片内容")
                else -> unsupported("$path[$index].type", "第一版不支持 tool_result 内容类型 ${obj.string("type")}")
            }
        }.joinToString("\n")
        JsonNull, null -> ""
        else -> unsupported(path, "无法识别的 tool_result 内容")
    }

    private fun anthropicToolChoiceToChat(choice: JsonObject?): JsonElement {
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

    private fun responsesToolChoiceToChat(choice: JsonElement): JsonElement {
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

    private fun chatToolChoiceToAnthropic(choice: JsonElement, disableParallelToolUse: Boolean = false): JsonElement {
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

    private fun chatToolChoiceToResponses(choice: JsonElement): JsonElement {
        if (choice is JsonPrimitive) return JsonPrimitive(choice.contentOrNull ?: "auto")
        val obj = choice as? JsonObject ?: return JsonPrimitive("auto")
        val function = obj["function"] as? JsonObject ?: return JsonPrimitive("auto")
        return buildJsonObject {
            put("type", "function")
            put("name", function.string("name").orEmpty())
        }
    }

    private fun thinkingFromEffort(effort: String): JsonObject = buildJsonObject {
        when (effort) {
            "none" -> put("type", "disabled")
            "minimal", "low" -> { put("type", "enabled"); put("budget_tokens", 1024) }
            "medium" -> { put("type", "enabled"); put("budget_tokens", 4096) }
            "high" -> { put("type", "enabled"); put("budget_tokens", 8192) }
            "xhigh", "max" -> { put("type", "enabled"); put("budget_tokens", 16384) }
            else -> unsupported("reasoning_effort", "未知 reasoning effort: $effort")
        }
    }

    private fun JsonObject.thinkingEffort(): String? {
        val thinking = this["thinking"] as? JsonObject ?: return null
        if (thinking.string("type") == "disabled") return "none"
        val budget = thinking.long("budget_tokens") ?: return null
        return when {
            budget <= 1024 -> "low"
            budget <= 4096 -> "medium"
            budget <= 8192 -> "high"
            else -> "max"
        }
    }

    internal fun messagesUsageToChat(usage: JsonObject?): JsonObject? {
        if (usage == null) return null
        val input = usage.long("input_tokens")
        val cacheRead = usage.long("cache_read_input_tokens")
        val cacheWrite = usage.long("cache_creation_input_tokens")
        val output = usage.long("output_tokens")
        val prompt = listOfNotNull(input, cacheRead, cacheWrite).sum()
        return buildJsonObject {
            put("prompt_tokens", prompt)
            put("completion_tokens", output ?: 0L)
            put("total_tokens", prompt + (output ?: 0L))
            if (cacheRead != null || cacheWrite != null) {
                putJsonObject("prompt_tokens_details") {
                    cacheRead?.let { put("cached_tokens", it) }
                    cacheWrite?.let { put("cache_write_tokens", it) }
                }
            }
        }
    }

    internal fun responsesUsageToChat(usage: JsonObject?): JsonObject? {
        if (usage == null) return null
        val input = usage.long("input_tokens") ?: 0L
        val output = usage.long("output_tokens") ?: 0L
        val cached = (usage["input_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0L
        val cacheWrite = (usage["input_tokens_details"] as? JsonObject)?.long("cache_write_tokens") ?: 0L
        return buildJsonObject {
            // Responses 与 Chat 一样，input_tokens/prompt_tokens 已包含缓存读写 token。
            put("prompt_tokens", input)
            put("completion_tokens", output)
            put("total_tokens", usage.long("total_tokens") ?: input + output)
            if (cached > 0 || cacheWrite > 0) {
                putJsonObject("prompt_tokens_details") {
                    if (cached > 0) put("cached_tokens", cached)
                    if (cacheWrite > 0) put("cache_write_tokens", cacheWrite)
                }
            }
            (usage["output_tokens_details"] as? JsonObject)?.long("reasoning_tokens")?.let { reasoning ->
                putJsonObject("completion_tokens_details") { put("reasoning_tokens", reasoning) }
            }
        }
    }

    private fun chatUsageToMessages(usage: JsonObject?): JsonObject? {
        if (usage == null) return null
        val prompt = usage.long("prompt_tokens") ?: 0L
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0L
        val cacheWrite = (usage["prompt_tokens_details"] as? JsonObject)?.long("cache_write_tokens") ?: 0L
        return buildJsonObject {
            put("input_tokens", max(0L, prompt - cached - cacheWrite))
            put("output_tokens", usage.long("completion_tokens") ?: 0L)
            if (cached > 0) put("cache_read_input_tokens", cached)
            if (cacheWrite > 0) put("cache_creation_input_tokens", cacheWrite)
        }
    }

    private fun chatUsageToResponses(usage: JsonObject?): JsonObject? {
        if (usage == null) return null
        val input = usage.long("prompt_tokens") ?: 0L
        val output = usage.long("completion_tokens") ?: 0L
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens")
        val cacheWrite = (usage["prompt_tokens_details"] as? JsonObject)?.long("cache_write_tokens")
        return buildJsonObject {
            put("input_tokens", input)
            put("output_tokens", output)
            put("total_tokens", usage.long("total_tokens") ?: input + output)
            if (cached != null || cacheWrite != null) {
                putJsonObject("input_tokens_details") {
                    cached?.let { put("cached_tokens", it) }
                    cacheWrite?.let { put("cache_write_tokens", it) }
                }
            }
            (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens")?.let { reasoning ->
                putJsonObject("output_tokens_details") { put("reasoning_tokens", reasoning) }
            }
        }
    }

    private fun anthropicStopToChat(stop: String?): String = when (stop) {
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        else -> "stop"
    }

    private fun chatFinishToAnthropic(finish: String?): String = when (finish) {
        "length" -> "max_tokens"
        "tool_calls" -> "tool_use"
        else -> "end_turn"
    }

    private fun parseJsonObjectOrEmpty(value: String?): JsonObject =
        runCatching { value?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject } }
            .getOrNull() ?: buildJsonObject { }

    private fun chatResponseFormatToResponses(format: JsonElement): JsonElement {
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

    private fun responsesTextFormatToChat(format: JsonElement): JsonElement {
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

    private fun rejectUnknownFields(obj: JsonObject, allowed: Set<String>, path: String) {
        val unknown = obj.keys.filterNot { it in allowed }
        if (unknown.isNotEmpty()) {
            unsupported("$path.${unknown.first()}", "协议转换不支持字段 ${unknown.joinToString(", ")}")
        }
    }

    private fun rejectExtraMetadata(value: JsonElement?, path: String) {
        val metadata = value as? JsonObject ?: return
        val extra = metadata.keys.filterNot { it == "user_id" }
        if (extra.isNotEmpty()) {
            unsupported("$path.${extra.first()}", "协议转换不支持 metadata 字段 ${extra.joinToString(", ")}")
        }
    }

    private fun JsonObject.withModel(model: String): JsonObject = buildJsonObject {
        this@withModel.forEach { (key, value) -> if (key != "model") put(key, value) }
        put("model", model)
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.number(key: String): JsonElement? = this[key]?.takeIf { it is JsonPrimitive && !it.isString }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.stringArray(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.stringOrStringArray(key: String): List<String>? = when (val value = this[key]) {
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.let { listOf(it) }
        is JsonArray -> value.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
        else -> null
    }

    private fun JsonObject.textContent(key: String): String? = when (val value = this[key]) {
        is JsonPrimitive -> value.contentOrNull
        is JsonArray -> value.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.string("text") ?: element.string("content")
                else -> null
            }
        }.joinToString("\n")
        else -> null
    }

    private fun JsonObject.textContentStrict(key: String, path: String): String = when (val value = this[key]) {
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.mapIndexed { index, element ->
            val obj = element as? JsonObject ?: unsupported("$path[$index]", "内容块必须是对象")
            when (obj.string("type")) {
                "text", "input_text", "output_text" -> obj.string("text").orEmpty()
                else -> unsupported("$path[$index].type", "第一版不支持该内容类型")
            }
        }.joinToString("\n")
        JsonNull, null -> ""
        else -> unsupported(path, "无法识别的内容")
    }

    private fun unsupported(field: String, message: String): Nothing =
        throw ProtocolConversionException(field, message)
}

/** 把上游协议 SSE 事件增量转换为入站协议 SSE 事件。 */
class ProtocolStreamConverter(
    private val inbound: GatewayProtocol,
    private val outbound: GatewayProtocol,
    private val model: String,
) {
    private val dataLines = mutableListOf<String>()
    private var started = false
    private var finished = false
    private var messageStarted = false
    private var responseStarted = false
    private var nextBlockIndex = 0
    private var openTextBlock: Int? = null
    private val openToolBlocks = linkedMapOf<Int, Int>()
    private val responseText = StringBuilder()
    private val responseTools = linkedMapOf<Int, MutableResponseTool>()
    private var responseId: String? = null
    private var responseModel: String = model
    private var nextResponseOutputIndex = 0
    private var responseTextIndex: Int? = null
    private var responseToolSeen = false
    private var usage: JsonObject? = null
    private var lastId: String = "chatcmpl-${System.currentTimeMillis()}"
    private var pendingFinish: ChatChunk? = null

    fun feed(line: String): List<String> {
        if (finished) return emptyList()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return flushEvent()
        if (trimmed.startsWith("data:")) {
            dataLines += trimmed.removePrefix("data:").trimStart()
            if (dataLines.size == 1 && dataLines.first() == "[DONE]") return flushEvent()
        }
        return emptyList()
    }

    fun finish(): List<String> {
        if (finished) return emptyList()
        val lines = flushEvent().toMutableList()
        if (!finished && pendingFinish?.finishReason != null) {
            finished = true
            lines += emitFinal(pendingFinish!!)
            pendingFinish = null
            lines += doneLines()
        }
        return lines
    }

    private fun flushEvent(): List<String> {
        if (dataLines.isEmpty()) return emptyList()
        val payload = dataLines.joinToString("\n")
        dataLines.clear()
        if (payload == "[DONE]") {
            return emitChunk(ChatChunk(lastId, responseModel, done = true))
        }
        val json = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject
        }.getOrNull() ?: run {
            finished = true
            return emitError("上游流返回了无法解析的 JSON 帧")
        }
        val chunks = when (outbound) {
            GatewayProtocol.CHAT_COMPLETIONS -> listOfNotNull(parseChatChunk(json))
            GatewayProtocol.MESSAGES -> parseMessagesChunks(json)
            GatewayProtocol.RESPONSES -> parseResponsesChunks(json)
        }
        val lines = mutableListOf<String>()
        chunks.forEach { chunk -> lines += emitChunk(chunk) }
        return lines
    }

    private fun parseChatChunk(json: JsonObject): ChatChunk? {
        val id = json.string("id") ?: "chatcmpl-${System.currentTimeMillis()}"
        val chunkModel = json.string("model") ?: model
        val usage = json["usage"] as? JsonObject
        val choice = json.array("choices").firstOrNull() as? JsonObject
        if (choice == null) return if (usage != null) ChatChunk(id, chunkModel, usage = usage) else null
        val delta = choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject
        val tools = mutableListOf<ToolCallDelta>()
        (delta?.get("tool_calls") as? JsonArray)?.forEachIndexed { position, element ->
            val call = element as? JsonObject ?: return@forEachIndexed
            val function = call["function"] as? JsonObject
            tools += ToolCallDelta(
                index = call.long("index")?.toInt() ?: position,
                id = call.string("id"),
                name = function?.string("name"),
                arguments = function?.string("arguments"),
            )
        }
        return ChatChunk(
            id = id,
            model = chunkModel,
            role = delta?.string("role"),
            content = delta?.string("content"),
            toolCalls = tools,
            finishReason = choice.string("finish_reason"),
            usage = usage,
        )
    }

    private fun parseMessagesChunks(json: JsonObject): List<ChatChunk> {
        val type = json.string("type") ?: return emptyList()
        val id = json.string("id") ?: "msg_${System.currentTimeMillis()}"
        return when (type) {
            "message_start" -> {
                val message = json["message"] as? JsonObject
                responseModel = message?.string("model") ?: model
                usage = mergeUsage(usage, message?.get("usage") as? JsonObject)
                listOf(ChatChunk(message?.string("id") ?: id, responseModel, role = "assistant"))
            }
            "content_block_start" -> {
                val block = json["content_block"] as? JsonObject
                if (block?.string("type") == "tool_use") {
                    listOf(ChatChunk(id, responseModel, toolCalls = listOf(ToolCallDelta(
                        index = json.long("index")?.toInt() ?: 0,
                        id = block.string("id"),
                        name = block.string("name"),
                        arguments = "",
                    ))))
                } else emptyList()
            }
            "content_block_delta" -> {
                val delta = json["delta"] as? JsonObject
                when (delta?.string("type")) {
                    "text_delta" -> listOf(ChatChunk(id, responseModel, content = delta.string("text")))
                    "input_json_delta" -> listOf(ChatChunk(id, responseModel, toolCalls = listOf(ToolCallDelta(
                        index = json.long("index")?.toInt() ?: 0,
                        arguments = delta.string("partial_json"),
                    ))))
                    else -> emptyList()
                }
            }
            "message_delta" -> {
                val delta = json["delta"] as? JsonObject
                usage = mergeUsage(usage, json["usage"] as? JsonObject)
                listOf(ChatChunk(id, responseModel, finishReason = chatFinishFromAnthropic(delta?.string("stop_reason"))))
            }
            "message_stop" -> listOf(ChatChunk(id, responseModel, done = true))
            "error" -> listOf(ChatChunk(id, responseModel, error = json["error"]?.toString() ?: "upstream stream error"))
            else -> emptyList()
        }
    }

    private fun parseResponsesChunks(json: JsonObject): List<ChatChunk> {
        val type = json.string("type") ?: return emptyList()
        val response = json["response"] as? JsonObject
        responseId = response?.string("id") ?: responseId
        responseModel = response?.string("model") ?: model
        val id = responseId ?: "resp_${System.currentTimeMillis()}"
        return when (type) {
            "response.created" -> listOf(ChatChunk(id, responseModel, role = "assistant"))
            "response.output_text.delta" -> listOf(ChatChunk(id, responseModel, content = json.string("delta")))
            "response.output_item.added" -> {
                val item = json["item"] as? JsonObject
                if (item?.string("type") == "function_call") {
                    responseToolSeen = true
                    listOf(ChatChunk(id, responseModel, toolCalls = listOf(ToolCallDelta(
                        index = json.long("output_index")?.toInt() ?: 0,
                        id = item.string("call_id") ?: item.string("id"),
                        name = item.string("name"),
                        arguments = item.string("arguments"),
                    ))))
                } else emptyList()
            }
            "response.function_call_arguments.delta" -> listOf(ChatChunk(id, responseModel, toolCalls = listOf(ToolCallDelta(
                index = json.long("output_index")?.toInt() ?: 0,
                arguments = json.string("delta"),
            ))))
            "response.completed" -> {
                usage = response?.get("usage") as? JsonObject
                val hasTool = responseToolSeen ||
                    (response?.get("output") as? JsonArray)?.any { (it as? JsonObject)?.string("type") == "function_call" } == true
                listOf(ChatChunk(id, responseModel, finishReason = if (hasTool) "tool_calls" else "stop", done = true))
            }
            "response.incomplete" -> {
                usage = response?.get("usage") as? JsonObject
                val reason = (response?.get("incomplete_details") as? JsonObject)?.string("reason")
                listOf(ChatChunk(
                    id,
                    responseModel,
                    finishReason = when (reason) {
                        "max_output_tokens" -> "length"
                        "content_filter" -> "content_filter"
                        else -> if (responseToolSeen) "tool_calls" else "stop"
                    },
                    done = true,
                ))
            }
            "response.failed" -> {
                val message = (response?.get("error") as? JsonObject)?.string("message")
                    ?: json["error"]?.toString()
                    ?: "upstream stream error"
                listOf(ChatChunk(id, responseModel, error = message))
            }
            "error" -> listOf(ChatChunk(id, responseModel, error = json["error"]?.toString() ?: "upstream stream error"))
            else -> emptyList()
        }
    }

    private fun emitChunk(chunk: ChatChunk): List<String> {
        chunk.error?.let { error ->
            finished = true
            return emitError(error)
        }
        if (chunk.done) {
            finished = true
            val output = mutableListOf<String>()
            val finish = pendingFinish?.takeIf { it.finishReason != null }
                ?: chunk.takeIf { it.finishReason != null }
                ?: ChatChunk(chunk.id, chunk.model, finishReason = "stop")
            output += emitFinal(finish)
            pendingFinish = null
            output += doneLines()
            return output
        }
        if (chunk.usage != null) usage = chunk.usage
        lastId = chunk.id
        // Chat 上游常把 finish_reason 与最终 usage 拆成两个 chunk；延后到 [DONE] 再收尾，
        // 避免 Anthropic/Responses 客户端收到不含 usage 的结束事件。
        if (chunk.finishReason != null) {
            pendingFinish = chunk
            return emptyList()
        }
        return when (inbound) {
            GatewayProtocol.CHAT_COMPLETIONS -> emitChat(chunk)
            GatewayProtocol.MESSAGES -> emitMessages(chunk)
            GatewayProtocol.RESPONSES -> emitResponses(chunk)
        }
    }

    private fun emitFinal(chunk: ChatChunk): List<String> {
        val finalChunk = chunk.copy(usage = usage ?: chunk.usage, done = false)
        return when (inbound) {
            GatewayProtocol.CHAT_COMPLETIONS -> emitChat(finalChunk)
            GatewayProtocol.MESSAGES -> emitMessages(finalChunk)
            GatewayProtocol.RESPONSES -> emitResponses(finalChunk)
        }
    }

    private fun canonicalUsage(): JsonObject? {
        val current = usage ?: return null
        return when (outbound) {
            GatewayProtocol.CHAT_COMPLETIONS -> current
            GatewayProtocol.MESSAGES -> ProtocolConversion.messagesUsageToChat(current)
            GatewayProtocol.RESPONSES -> ProtocolConversion.responsesUsageToChat(current)
        }
    }

    private fun mergeUsage(base: JsonObject?, next: JsonObject?): JsonObject? = when {
        base == null -> next
        next == null -> base
        else -> JsonObject(base + next)
    }

    private fun emitChat(chunk: ChatChunk): List<String> {
        val delta = buildJsonObject {
            if (!started) put("role", chunk.role ?: "assistant")
            chunk.content?.let { put("content", it) }
            if (chunk.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    chunk.toolCalls.forEach { tool ->
                        add(buildJsonObject {
                            put("index", tool.index)
                            tool.id?.let { put("id", it) }
                            put("type", "function")
                            putJsonObject("function") {
                                tool.name?.let { put("name", it) }
                                tool.arguments?.let { put("arguments", it) }
                            }
                        })
                    }
                })
            }
        }
        started = true
        val objectJson = buildJsonObject {
            put("id", chunk.id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", chunk.finishReason)
                })
            })
            if (chunk.finishReason != null) canonicalUsage()?.let { put("usage", it) }
        }
        return listOf("data: $objectJson", "")
    }

    private fun emitMessages(chunk: ChatChunk): List<String> {
        val lines = mutableListOf<String>()
        if (!messageStarted) {
            messageStarted = true
            val start = buildJsonObject {
                put("type", "message_start")
                putJsonObject("message") {
                    put("id", chunk.id)
                    put("type", "message")
                    put("role", "assistant")
                    put("model", model)
                    put("content", JsonArray(emptyList()))
                    put("stop_reason", JsonNull)
                    put("stop_sequence", JsonNull)
                    putJsonObject("usage") { put("input_tokens", 0) }
                }
            }
            lines += "event: message_start"
            lines += "data: $start"
            lines += ""
        }
        chunk.content?.takeIf { it.isNotEmpty() }?.let { text ->
            val index = openTextBlock ?: nextBlockIndex++.also { block ->
                openTextBlock = block
                lines += "event: content_block_start"
                lines += "data: ${buildJsonObject { put("type", "content_block_start"); put("index", block); putJsonObject("content_block") { put("type", "text"); put("text", "") } }}"
                lines += ""
            }
            lines += "event: content_block_delta"
            lines += "data: ${buildJsonObject { put("type", "content_block_delta"); put("index", index); putJsonObject("delta") { put("type", "text_delta"); put("text", text) } }}"
            lines += ""
        }
        chunk.toolCalls.forEach { tool ->
            closeTextBlock(lines)
            val blockIndex = openToolBlocks.getOrPut(tool.index) {
                nextBlockIndex++.also { block ->
                    lines += "event: content_block_start"
                    lines += "data: ${buildJsonObject { put("type", "content_block_start"); put("index", block); putJsonObject("content_block") { put("type", "tool_use"); put("id", tool.id ?: "toolu_${tool.index}"); put("name", tool.name.orEmpty()); put("input", buildJsonObject { }) } }}"
                    lines += ""
                }
            }
            tool.arguments?.takeIf { it.isNotEmpty() }?.let { arguments ->
                lines += "event: content_block_delta"
                lines += "data: ${buildJsonObject { put("type", "content_block_delta"); put("index", blockIndex); putJsonObject("delta") { put("type", "input_json_delta"); put("partial_json", arguments) } }}"
                lines += ""
            }
        }
        if (chunk.finishReason != null) {
            closeTextBlock(lines)
            openToolBlocks.values.forEach { block ->
                lines += "event: content_block_stop"
                lines += "data: ${buildJsonObject { put("type", "content_block_stop"); put("index", block) }}"
                lines += ""
            }
            openToolBlocks.clear()
            lines += "event: message_delta"
            lines += "data: ${buildJsonObject { put("type", "message_delta"); putJsonObject("delta") { put("stop_reason", anthropicStopFromChat(chunk.finishReason)); put("stop_sequence", JsonNull) }; canonicalUsage()?.let { put("usage", chatUsageToAnthropic(it)) } }}"
            lines += ""
            finished = true
            lines += "event: message_stop"
            lines += "data: ${buildJsonObject { put("type", "message_stop") }}"
            lines += ""
        }
        return lines
    }

    private fun emitResponses(chunk: ChatChunk): List<String> {
        val lines = mutableListOf<String>()
        if (!responseStarted) {
            responseStarted = true
            lines += "event: response.created"
            lines += "data: ${buildJsonObject { put("type", "response.created"); putJsonObject("response") { put("id", chunk.id); put("object", "response"); put("status", "in_progress"); put("model", model); put("output", JsonArray(emptyList())) } }}"
            lines += ""
        }
        chunk.content?.takeIf { it.isNotEmpty() }?.let { text ->
            val outputIndex = responseTextIndex ?: nextResponseOutputIndex++.also { responseTextIndex = it }
            responseText.append(text)
            lines += "event: response.output_text.delta"
            lines += "data: ${buildJsonObject { put("type", "response.output_text.delta"); put("output_index", outputIndex); put("content_index", 0); put("delta", text) }}"
            lines += ""
        }
        chunk.toolCalls.forEach { tool ->
            val item = responseTools.getOrPut(tool.index) { MutableResponseTool(tool.index, tool.id, tool.name, StringBuilder()) }
            if (item.started.not()) {
                item.started = true
                item.outputIndex = nextResponseOutputIndex++
                lines += "event: response.output_item.added"
                lines += "data: ${buildJsonObject { put("type", "response.output_item.added"); put("output_index", item.outputIndex); putJsonObject("item") { put("type", "function_call"); put("id", "fc_${chunk.id}_${tool.index}"); put("call_id", item.id ?: "call_${tool.index}"); put("name", item.name.orEmpty()); put("arguments", ""); put("status", "in_progress") } }}"
                lines += ""
            }
            tool.arguments?.takeIf { it.isNotEmpty() }?.let { args ->
                item.arguments.append(args)
                lines += "event: response.function_call_arguments.delta"
                lines += "data: ${buildJsonObject { put("type", "response.function_call_arguments.delta"); put("output_index", item.outputIndex); put("delta", args) }}"
                lines += ""
            }
        }
        if (chunk.finishReason != null) {
            finished = true
            val outputItems = mutableListOf<Pair<Int, JsonObject>>()
            if (responseText.isNotEmpty()) {
                outputItems += (responseTextIndex ?: 0) to buildJsonObject {
                    put("type", "message")
                    put("id", "msg_${chunk.id}")
                    put("role", "assistant")
                    put("status", "completed")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", responseText.toString()); put("annotations", JsonArray(emptyList())) }) })
                }
            }
            responseTools.values.forEach { tool ->
                outputItems += tool.outputIndex to buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc_${chunk.id}_${tool.index}")
                    put("call_id", tool.id ?: "call_${tool.index}")
                    put("name", tool.name.orEmpty())
                    put("arguments", tool.arguments.toString())
                    put("status", "completed")
                }
            }
            val output = JsonArray(outputItems.sortedBy { it.first }.map { it.second })
            val incomplete = chunk.finishReason == "length" || chunk.finishReason == "content_filter"
            val eventType = if (incomplete) "response.incomplete" else "response.completed"
            lines += "event: $eventType"
            lines += "data: ${buildJsonObject {
                put("type", eventType)
                putJsonObject("response") {
                    put("id", chunk.id)
                    put("object", "response")
                    put("status", if (incomplete) "incomplete" else "completed")
                    put("model", model)
                    put("output", output)
                    if (incomplete) {
                        putJsonObject("incomplete_details") {
                            put("reason", if (chunk.finishReason == "content_filter") "content_filter" else "max_output_tokens")
                        }
                    }
                    canonicalUsage()?.let { put("usage", chatUsageToResponses(it)) }
                }
            }}"
            lines += ""
        }
        return lines
    }

    private fun closeTextBlock(lines: MutableList<String>) {
        openTextBlock?.let { block ->
            lines += "event: content_block_stop"
            lines += "data: ${buildJsonObject { put("type", "content_block_stop"); put("index", block) }}"
            lines += ""
            openTextBlock = null
        }
    }

    private fun finishOutput(): List<String> = emptyList()

    private fun doneLines(): List<String> =
        if (inbound == GatewayProtocol.CHAT_COMPLETIONS) listOf("data: [DONE]", "") else emptyList()

    private fun emitError(error: String): List<String> = when (inbound) {
        GatewayProtocol.CHAT_COMPLETIONS -> listOf("data: ${buildJsonObject { put("error", buildJsonObject { put("message", error); put("type", "api_error") }) }}", "", "data: [DONE]", "")
        GatewayProtocol.RESPONSES -> listOf("event: response.failed", "data: ${buildJsonObject { put("type", "response.failed"); put("error", buildJsonObject { put("message", error); put("type", "api_error") }) }}", "")
        GatewayProtocol.MESSAGES -> listOf("event: error", "data: ${buildJsonObject { put("type", "error"); put("error", buildJsonObject { put("type", "api_error"); put("message", error) }) }}", "")
    }

    private fun chatFinishFromAnthropic(stop: String?): String? = when (stop) {
        "max_tokens" -> "length"
        "tool_use" -> "tool_calls"
        null -> null
        else -> "stop"
    }

    private fun anthropicStopFromChat(finish: String): String = when (finish) {
        "length" -> "max_tokens"
        "tool_calls" -> "tool_use"
        else -> "end_turn"
    }

    private fun chatUsageToAnthropic(usage: JsonObject): JsonObject {
        val prompt = usage.long("prompt_tokens") ?: 0L
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0L
        val cacheWrite = (usage["prompt_tokens_details"] as? JsonObject)?.long("cache_write_tokens") ?: 0L
        return buildJsonObject {
            put("input_tokens", max(0L, prompt - cached - cacheWrite))
            put("output_tokens", usage.long("completion_tokens") ?: 0L)
            if (cached > 0) put("cache_read_input_tokens", cached)
            if (cacheWrite > 0) put("cache_creation_input_tokens", cacheWrite)
        }
    }

    private fun chatUsageToResponses(usage: JsonObject): JsonObject {
        val input = usage.long("prompt_tokens") ?: 0L
        val output = usage.long("completion_tokens") ?: 0L
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens")
        val cacheWrite = (usage["prompt_tokens_details"] as? JsonObject)?.long("cache_write_tokens")
        return buildJsonObject {
            put("input_tokens", input)
            put("output_tokens", output)
            put("total_tokens", usage.long("total_tokens") ?: input + output)
            if (cached != null || cacheWrite != null) {
                putJsonObject("input_tokens_details") {
                    cached?.let { put("cached_tokens", it) }
                    cacheWrite?.let { put("cache_write_tokens", it) }
                }
            }
            (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens")?.let { reasoning ->
                putJsonObject("output_tokens_details") { put("reasoning_tokens", reasoning) }
            }
        }
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private data class ChatChunk(
        val id: String,
        val model: String,
        val role: String? = null,
        val content: String? = null,
        val toolCalls: List<ToolCallDelta> = emptyList(),
        val finishReason: String? = null,
        val usage: JsonObject? = null,
        val done: Boolean = false,
        val error: String? = null,
    )

    private data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    )

    private data class MutableResponseTool(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: StringBuilder,
        var started: Boolean = false,
        var outputIndex: Int = -1,
    )
}
