package dev.soven.prismllm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class ChatStreamEncoder(private val model: String) : StreamEncoder {
    private var started = false
    private val toolIndexes = linkedMapOf<Int, Int>()
    private val toolsWithArguments = mutableSetOf<Int>()

    override fun emit(chunk: StreamEvent, usage: TokenUsage?): List<String> {
        val toolCalls = chunk.toolCalls.toMutableList()
        toolCalls.forEach { tool ->
            toolIndexes.getOrPut(tool.index) { toolIndexes.size }
            if (!tool.arguments.isNullOrEmpty()) toolsWithArguments += tool.index
        }
        if (chunk.finishReason != null) {
            toolIndexes.keys.filterNot { it in toolsWithArguments }.forEach { index ->
                val position = toolCalls.indexOfFirst { it.index == index }
                if (position >= 0) toolCalls[position] = toolCalls[position].copy(arguments = "{}")
                else toolCalls += ToolCallDelta(index = index, arguments = "{}")
                toolsWithArguments += index
            }
        }
        val delta = buildJsonObject {
            if (!started) put("role", chunk.role ?: "assistant")
            chunk.content?.let { put("content", it) }
            chunk.refusal?.let { put("refusal", it) }
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { tool ->
                        add(buildJsonObject {
                            put("index", toolIndexes.getOrPut(tool.index) { toolIndexes.size })
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
                    put("finish_reason", chunk.finishReason?.chatValue)
                })
            })
            if (chunk.finishReason != null) usage?.let { put("usage", it.toChat()) }
        }
        return listOf("data: $objectJson", "")
    }

    override fun error(message: String, id: String): List<String> = listOf("data: "+buildJsonObject {
        put("error", errorObject(message))
    }, "") + terminalLines()

    override fun terminalLines(): List<String> = listOf("data: [DONE]", "")
}

internal class MessagesStreamEncoder(private val model: String) : StreamEncoder {
    private var messageStarted = false
    private var nextBlockIndex = 0
    private var openTextBlock: Int? = null
    private val openToolBlocks = linkedMapOf<Int, Int>()

    override fun emit(chunk: StreamEvent, usage: TokenUsage?): List<String> {
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
                    putJsonObject("usage") {
                        put("input_tokens", 0)
                        put("output_tokens", 0)
                    }
                }
            }
            lines += "event: message_start"
            lines += "data: $start"
            lines += ""
        }
        listOfNotNull(chunk.content, chunk.refusal).filter { it.isNotEmpty() }
            .joinToString("").takeIf { it.isNotEmpty() }?.let { text ->
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
            lines += "data: ${buildJsonObject { put("type", "message_delta"); putJsonObject("delta") { put("stop_reason", chunk.finishReason.messagesValue); put("stop_sequence", JsonNull) }; put("usage", usage?.toMessages() ?: buildJsonObject { put("output_tokens", 0) }) }}"
            lines += ""
            lines += "event: message_stop"
            lines += "data: ${buildJsonObject { put("type", "message_stop") }}"
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

    override fun error(message: String, id: String): List<String> = streamError("error", message)
}

internal class ResponsesStreamEncoder(private val model: String) : StreamEncoder {
    private var responseId: String? = null
    private var sequenceNumber = 0
    private val createdAt = System.currentTimeMillis() / 1000
    private val responseText = StringBuilder()
    private val responseRefusal = StringBuilder()
    private val responseTools = linkedMapOf<Int, MutableResponseTool>()
    private var nextResponseOutputIndex = 0
    private var responseTextIndex: Int? = null
    private var nextResponseContentIndex = 0
    private var responseTextContentIndex: Int? = null
    private var responseRefusalContentIndex: Int? = null

    override fun emit(chunk: StreamEvent, usage: TokenUsage?): List<String> {
        val lines = mutableListOf<String>()
        val id = responseId ?: chunk.id.also {
            responseId = it
            lines += event("response.created") { put("response", response("in_progress", JsonArray(emptyList()))) }
        }
        val messageId = "msg_$id"
        val refusal = chunk.refusal?.takeIf { it.isNotEmpty() }
        if (refusal != null) {
            val outputIndex = responseTextIndex ?: nextResponseOutputIndex++.also { index ->
                responseTextIndex = index
                lines += event("response.output_item.added") {
                    put("output_index", index)
                    put("item", textItem("in_progress"))
                }
            }
            if (responseRefusalContentIndex == null) {
                val contentIndex = nextResponseContentIndex++
                responseRefusalContentIndex = contentIndex
                lines += event("response.content_part.added") {
                    put("item_id", messageId)
                    put("output_index", outputIndex)
                    put("content_index", contentIndex)
                    put("part", refusalPart(""))
                }
            }
            responseRefusal.append(refusal)
            lines += event("response.refusal.delta") {
                put("item_id", messageId)
                put("output_index", outputIndex)
                put("content_index", responseRefusalContentIndex)
                put("delta", refusal)
            }
        }
        chunk.content?.takeIf { it.isNotEmpty() }?.let { text ->
            val outputIndex = responseTextIndex ?: nextResponseOutputIndex++.also { index ->
                responseTextIndex = index
                lines += event("response.output_item.added") {
                    put("output_index", index)
                    put("item", textItem("in_progress"))
                }
            }
            val contentIndex = responseTextContentIndex ?: nextResponseContentIndex++.also { index ->
                responseTextContentIndex = index
                lines += event("response.content_part.added") {
                    put("item_id", messageId)
                    put("output_index", outputIndex)
                    put("content_index", index)
                    put("part", textPart(""))
                }
            }
            responseText.append(text)
            lines += event("response.output_text.delta") {
                put("item_id", messageId)
                put("output_index", outputIndex)
                put("content_index", contentIndex)
                put("delta", text)
                put("logprobs", JsonArray(emptyList()))
            }
        }
        chunk.toolCalls.forEach { tool ->
            val item = responseTools.getOrPut(tool.index) {
                MutableResponseTool("fc_${id}_${tool.index}", tool.id, tool.name, nextResponseOutputIndex++).also { added ->
                    lines += event("response.output_item.added") {
                        put("output_index", added.outputIndex)
                        put("item", toolItem(added, "in_progress"))
                    }
                }
            }
            tool.id?.let { item.callId = it }
            tool.name?.let { item.name = it }
            tool.arguments?.takeIf { it.isNotEmpty() }?.let { arguments ->
                item.arguments.append(arguments)
                lines += event("response.function_call_arguments.delta") {
                    put("item_id", item.id)
                    put("output_index", item.outputIndex)
                    put("delta", arguments)
                }
            }
        }
        if (chunk.finishReason != null) {
            responseTools.values.filter { it.arguments.isEmpty() }.forEach { tool ->
                tool.arguments.append("{}")
                lines += event("response.function_call_arguments.delta") {
                    put("item_id", tool.id)
                    put("output_index", tool.outputIndex)
                    put("delta", "{}")
                }
            }
            val incomplete = chunk.finishReason == FinishReason.MAX_TOKENS || chunk.finishReason == FinishReason.CONTENT_FILTER
            val status = if (incomplete) "incomplete" else "completed"
            val output = output(status)
            output.forEachIndexed { index, element ->
                val item = element as JsonObject
                if (item.string("type") == "message") {
                    if (responseText.isNotEmpty()) {
                        lines += event("response.output_text.done") {
                            put("item_id", messageId)
                            put("output_index", index)
                            put("content_index", responseTextContentIndex)
                            put("text", responseText.toString())
                            put("logprobs", JsonArray(emptyList()))
                        }
                        lines += event("response.content_part.done") {
                            put("item_id", messageId)
                            put("output_index", index)
                            put("content_index", responseTextContentIndex)
                            put("part", textPart(responseText.toString()))
                        }
                    }
                    if (responseRefusal.isNotEmpty()) {
                        lines += event("response.refusal.done") {
                            put("item_id", messageId)
                            put("output_index", index)
                            put("content_index", responseRefusalContentIndex)
                            put("refusal", responseRefusal.toString())
                        }
                        lines += event("response.content_part.done") {
                            put("item_id", messageId)
                            put("output_index", index)
                            put("content_index", responseRefusalContentIndex)
                            put("part", refusalPart(responseRefusal.toString()))
                        }
                    }
                } else {
                    lines += event("response.function_call_arguments.done") {
                        put("item_id", item.string("id"))
                        put("output_index", index)
                        put("arguments", item.string("arguments").orEmpty())
                    }
                }
                lines += event("response.output_item.done") {
                    put("output_index", index)
                    put("item", item)
                }
            }
            lines += event("response.$status") {
                put("response", response(status, output, usage, chunk.finishReason))
            }
        }
        return lines
    }

    override fun error(message: String, id: String): List<String> {
        if (responseId == null) responseId = id
        return event("response.failed") {
            put("response", JsonObject(response("failed", output("incomplete")) + ("error" to buildJsonObject {
                put("code", "server_error")
                put("message", message)
            })))
        }
    }

    private fun event(type: String, body: JsonObjectBuilder.() -> Unit): List<String> = listOf(
        "event: $type",
        "data: "+buildJsonObject {
            put("type", type)
            put("sequence_number", sequenceNumber++)
            body()
        },
        "",
    )

    private fun response(status: String, output: JsonArray, usage: TokenUsage? = null, finishReason: FinishReason? = null): JsonObject = buildJsonObject {
        put("id", responseId)
        put("object", "response")
        put("created_at", createdAt)
        put("status", status)
        put("model", model)
        put("output", output)
        if (status == "incomplete") putJsonObject("incomplete_details") {
            put("reason", if (finishReason == FinishReason.CONTENT_FILTER) "content_filter" else "max_output_tokens")
        }
        usage?.let { put("usage", it.toResponses()) }
    }

    private fun output(status: String): JsonArray {
        val items = mutableListOf<Pair<Int, JsonObject>>()
        responseTextIndex?.let { items += it to textItem(status) }
        responseTools.values.forEach { tool -> items += tool.outputIndex to toolItem(tool, status) }
        return JsonArray(items.sortedBy { it.first }.map { it.second })
    }

    private fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", "output_text")
        put("text", text)
        put("annotations", JsonArray(emptyList()))
        put("logprobs", JsonArray(emptyList()))
    }

    private fun refusalPart(refusal: String): JsonObject = buildJsonObject {
        put("type", "refusal")
        put("refusal", refusal)
    }

    private fun textItem(status: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", "msg_$responseId")
        put("role", "assistant")
        put("status", status)
        put("content", if (status == "in_progress") JsonArray(emptyList()) else JsonArray(
            listOfNotNull(
                responseTextContentIndex?.let { it to textPart(responseText.toString()) },
                responseRefusalContentIndex?.let { it to refusalPart(responseRefusal.toString()) },
            ).sortedBy { it.first }.map { it.second },
        ))
    }

    private fun toolItem(tool: MutableResponseTool, status: String): JsonObject = buildJsonObject {
        put("type", "function_call")
        put("id", tool.id)
        put("call_id", tool.callId ?: "call_${tool.outputIndex}")
        put("name", tool.name.orEmpty())
        put("arguments", tool.arguments.toString())
        put("status", status)
    }

    private data class MutableResponseTool(
        val id: String,
        var callId: String?,
        var name: String?,
        val outputIndex: Int,
        val arguments: StringBuilder = StringBuilder(),
    )
}

internal class GeminiStreamEncoder(private val model: String) : StreamEncoder {
    private var responseId: String? = null
    private var hasRefusal = false
    private val pendingTools = mutableListOf<MutableGeminiTool>()

    override fun emit(chunk: StreamEvent, usage: TokenUsage?): List<String> {
        val lines = mutableListOf<String>()
        val id = responseId ?: chunk.id.also { responseId = it }
        val parts = mutableListOf<JsonObject>()
        chunk.content?.takeIf { it.isNotEmpty() }?.let { text -> parts += buildJsonObject { put("text", text) } }
        chunk.refusal?.takeIf { it.isNotEmpty() }?.let { text ->
            hasRefusal = true
            parts += buildJsonObject { put("text", text) }
        }
        chunk.toolCalls.forEach { tool ->
            val existing = pendingTools.firstOrNull { it.index == tool.index }
            val state = existing ?: MutableGeminiTool(tool.index, tool.name.orEmpty(), StringBuilder()).also { pendingTools += it }
            tool.arguments?.takeIf { it.isNotEmpty() }?.let { state.arguments.append(it) }
        }
        if (chunk.finishReason != null) {
            pendingTools.forEach { tool ->
                parts += buildJsonObject {
                    putJsonObject("functionCall") {
                        put("name", tool.name)
                        put("args", parseJsonObjectOrEmpty(tool.arguments.toString().ifEmpty { "{}" }))
                    }
                }
            }
        }
        if (parts.isEmpty() && chunk.finishReason == null) return emptyList()
        val body = buildJsonObject {
            put("responseId", id)
            put("modelVersion", model)
            if (parts.isNotEmpty() || chunk.finishReason != null) {
                put("candidates", buildJsonArray {
                    add(buildJsonObject {
                        put("content", buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
                        chunk.finishReason?.let { put("finishReason", if (hasRefusal) "SAFETY" else it.toGeminiFinish()) }
                        put("index", 0)
                    })
                })
            }
            if (chunk.finishReason != null) usage?.let { put("usageMetadata", it.toGemini()) }
        }
        lines += "data: $body"
        lines += ""
        return lines
    }

    override fun error(message: String, id: String): List<String> = listOf(
        "data: "+buildJsonObject { putJsonObject("error") { put("code", 500); put("message", message); put("status", "INTERNAL") } },
        "",
    )

    private data class MutableGeminiTool(
        val index: Int,
        val name: String,
        val arguments: StringBuilder,
    )
}

private fun FinishReason.toGeminiFinish(): String = when (this) {
    FinishReason.MAX_TOKENS -> "MAX_TOKENS"
    FinishReason.CONTENT_FILTER -> "SAFETY"
    else -> "STOP"
}

private fun errorObject(message: String): JsonObject = buildJsonObject {
    put("message", message)
    put("type", "api_error")
}

private fun streamError(type: String, message: String): List<String> = listOf(
    "event: $type",
    "data: "+buildJsonObject { put("type", type); put("error", errorObject(message)) },
    "",
)
