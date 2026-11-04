package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class ChatStreamDecoder : StreamDecoder {
    override var usage: TokenUsage? = null
        private set
    private val fallbackId = "chatcmpl-"+System.currentTimeMillis()

    override fun decode(event: JsonObject): List<StreamEvent> = listOfNotNull(parse(event))

    private fun parse(json: JsonObject): StreamEvent? {
        val id = json.string("id") ?: fallbackId
        json["error"]?.takeIf { it !is JsonNull }?.let { return StreamEvent(id, error = streamErrorText(it)) }
        val usageJson = json["usage"] as? JsonObject
        if (usageJson != null) usage = TokenUsage.fromChat(usageJson)
        val choice = json.array("choices").firstOrNull() as? JsonObject
        if (choice == null) return if (usageJson != null) StreamEvent(id) else null
        val delta = choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject
        val refusal = delta?.string("refusal")
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
        return StreamEvent(
            id = id,
            role = delta?.string("role"),
            content = delta?.string("content"),
            refusal = refusal,
            toolCalls = tools,
            finishReason = FinishReason.fromChat(choice.string("finish_reason")),
        )
    }
}

internal class MessagesStreamDecoder : StreamDecoder {
    private var nativeUsage: JsonObject? = null
    private var messageId = "msg_"+System.currentTimeMillis()
    override val usage: TokenUsage? get() = TokenUsage.fromMessages(nativeUsage)

    override fun decode(json: JsonObject): List<StreamEvent> {
        val type = json.string("type") ?: return emptyList()
        val id = (json["message"] as? JsonObject)?.string("id") ?: messageId
        messageId = id
        return when (type) {
            "message_start" -> {
                val message = json["message"] as? JsonObject
                nativeUsage = mergeUsage(nativeUsage, message?.get("usage") as? JsonObject)
                listOf(StreamEvent(message?.string("id") ?: id, role = "assistant"))
            }
            "content_block_start" -> {
                val block = json["content_block"] as? JsonObject
                if (block?.string("type") == "tool_use") {
                    listOf(StreamEvent(id, toolCalls = listOf(ToolCallDelta(
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
                    "text_delta" -> listOf(StreamEvent(id, content = delta.string("text")))
                    "input_json_delta" -> listOf(StreamEvent(id, toolCalls = listOf(ToolCallDelta(
                        index = json.long("index")?.toInt() ?: 0,
                        arguments = delta.string("partial_json"),
                    ))))
                    else -> emptyList()
                }
            }
            "message_delta" -> {
                val delta = json["delta"] as? JsonObject
                nativeUsage = mergeUsage(nativeUsage, json["usage"] as? JsonObject)
                listOf(StreamEvent(id, finishReason = FinishReason.fromMessages(delta?.string("stop_reason"))))
            }
            "message_stop" -> listOf(StreamEvent(id, done = true))
            "error" -> listOf(StreamEvent(id, error = streamErrorText(json["error"])))
            else -> emptyList()
        }
    }
}

internal class ResponsesStreamDecoder : StreamDecoder {
    private var nativeUsage: JsonObject? = null
    private var responseId: String? = null
    private val fallbackId = "resp_" + System.currentTimeMillis()
    private var responseToolSeen = false
    private var responseRefused = false
    override val usage: TokenUsage? get() = TokenUsage.fromResponses(nativeUsage)

    override fun decode(json: JsonObject): List<StreamEvent> {
        val type = json.string("type") ?: return emptyList()
        val response = json["response"] as? JsonObject
        responseId = response?.string("id") ?: responseId
        val id = responseId ?: fallbackId
        return when (type) {
            "response.created" -> listOf(StreamEvent(id, role = "assistant"))
            "response.output_text.delta" -> listOf(StreamEvent(id, content = json.string("delta")))
            "response.refusal.delta" -> {
                responseRefused = true
                listOf(StreamEvent(id, refusal = json.string("delta")))
            }
            "response.output_item.added" -> {
                val item = json["item"] as? JsonObject
                if (item?.string("type") == "function_call") {
                    responseToolSeen = true
                    listOf(StreamEvent(id, toolCalls = listOf(ToolCallDelta(
                        index = json.long("output_index")?.toInt() ?: 0,
                        id = item.string("call_id") ?: item.string("id"),
                        name = item.string("name"),
                        arguments = item.string("arguments"),
                    ))))
                } else emptyList()
            }
            "response.function_call_arguments.delta" -> listOf(StreamEvent(id, toolCalls = listOf(ToolCallDelta(
                index = json.long("output_index")?.toInt() ?: 0,
                arguments = json.string("delta"),
            ))))
            "response.completed" -> {
                nativeUsage = response?.get("usage") as? JsonObject
                val hasTool = responseToolSeen ||
                    (response?.get("output") as? JsonArray)?.any { (it as? JsonObject)?.string("type") == "function_call" } == true
                val refusalTexts = (response?.get("output") as? JsonArray).orEmpty().flatMap { item ->
                    ((item as? JsonObject)?.array("content") ?: emptyList()).mapNotNull { part ->
                        (part as? JsonObject)?.takeIf { it.string("type") == "refusal" }?.string("refusal")
                    }
                }
                val finishReason = when {
                    hasTool -> FinishReason.TOOL_CALLS
                    else -> FinishReason.STOP
                }
                val refusalEvents = if (responseRefused) emptyList() else refusalTexts.map { StreamEvent(id, refusal = it) }
                refusalEvents + StreamEvent(id, finishReason = finishReason, done = true)
            }
            "response.incomplete" -> {
                nativeUsage = response?.get("usage") as? JsonObject
                val reason = (response?.get("incomplete_details") as? JsonObject)?.string("reason")
                listOf(StreamEvent(
                    id,
                    finishReason = when (reason) {
                        "content_filter" -> FinishReason.CONTENT_FILTER
                        else -> FinishReason.MAX_TOKENS
                    },
                    done = true,
                ))
            }
            "response.failed" -> {
                val message = (response?.get("error") as? JsonObject)?.string("message")?.take(MAX_STREAM_ERROR_CHARS)
                    ?: streamErrorText(json["error"])
                listOf(StreamEvent(id, error = message))
            }
            "error" -> listOf(StreamEvent(id, error = streamErrorText(json["error"]?.takeIf { it !is JsonNull } ?: json)))
            else -> emptyList()
        }
    }
}

internal class GeminiStreamDecoder : StreamDecoder {
    private var nativeUsage: JsonObject? = null
    private var responseId: String? = null
    private var toolIndex = 0
    private var toolSeen = false
    private val fallbackId = "gemini_" + System.currentTimeMillis()
    override val usage: TokenUsage? get() = TokenUsage.fromGemini(nativeUsage)

    override fun decode(json: JsonObject): List<StreamEvent> {
        nativeUsage = mergeUsage(nativeUsage, json["usageMetadata"] as? JsonObject)
        responseId = json.string("responseId") ?: responseId
        val id = responseId ?: fallbackId
        if (json["error"] is JsonObject) return listOf(StreamEvent(id, error = streamErrorText(json["error"])))
        val candidates = json.array("candidates")
        val candidate = candidates.firstOrNull() as? JsonObject
        val blockReason = (json["promptFeedback"] as? JsonObject)?.string("blockReason")
        if (blockReason != null) return listOf(StreamEvent(id, finishReason = FinishReason.CONTENT_FILTER, done = true))
        val events = mutableListOf<StreamEvent>()
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        parts.forEach { part ->
            val obj = part as? JsonObject ?: return@forEach
            val call = obj["functionCall"] as? JsonObject
            when {
                call != null -> {
                    toolSeen = true
                    val index = toolIndex++
                    events += StreamEvent(id, toolCalls = listOf(ToolCallDelta(
                        index = index,
                        id = call.string("id") ?: "call_${id}_$index",
                        name = call.string("name"),
                        arguments = (call["args"] ?: JsonObject(emptyMap())).toString(),
                    )))
                }
                obj.boolean("thought") == true -> Unit
                else -> obj.string("text")?.let { events += StreamEvent(id, content = it) }
            }
        }
        candidate?.string("finishReason")?.let { reason ->
            events += StreamEvent(
                id,
                finishReason = if (reason == "STOP" && toolSeen) FinishReason.TOOL_CALLS else geminiStreamFinish(reason),
            )
        }
        return events
    }
}

private const val MAX_STREAM_ERROR_CHARS = 2048

private fun streamErrorText(error: JsonElement?): String {
    val text = (error as? JsonObject)?.string("message") ?: error?.toString()
    return text?.take(MAX_STREAM_ERROR_CHARS) ?: "upstream stream error"
}

private fun mergeUsage(base: JsonObject?, next: JsonObject?): JsonObject? = when {
    base == null -> next
    next == null -> base
    else -> JsonObject(base + next)
}

private fun geminiStreamFinish(reason: String): FinishReason = when (reason) {
    "MAX_TOKENS" -> FinishReason.MAX_TOKENS
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.STOP
}
