package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class ChatStreamEncoder(private val model: String) : StreamEncoder {
    private var started = false

    override fun emit(chunk: StreamEvent, usage: TokenUsage?): List<String> {
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
                    put("finish_reason", chunk.finishReason?.chatValue)
                })
            })
            if (chunk.finishReason != null) usage?.let { put("usage", it.toChat()) }
        }
        return listOf("data: $objectJson", "")
    }

    override fun error(message: String): List<String> = listOf("data: "+buildJsonObject {
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
            lines += "data: ${buildJsonObject { put("type", "message_delta"); putJsonObject("delta") { put("stop_reason", chunk.finishReason.messagesValue); put("stop_sequence", JsonNull) }; usage?.let { put("usage", it.toMessages()) } }}"
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

    override fun error(message: String): List<String> = streamError("error", message)
}

internal class ResponsesStreamEncoder(private val model: String) : StreamEncoder {
    private var responseStarted = false
    private val responseText = StringBuilder()
    private val responseTools = linkedMapOf<Int, MutableResponseTool>()
    private var nextResponseOutputIndex = 0
    private var responseTextIndex: Int? = null

    override fun emit(chunk: StreamEvent, usage: TokenUsage?): List<String> {
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
            val incomplete = chunk.finishReason == FinishReason.MAX_TOKENS || chunk.finishReason == FinishReason.CONTENT_FILTER
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
                            put("reason", if (chunk.finishReason == FinishReason.CONTENT_FILTER) "content_filter" else "max_output_tokens")
                        }
                    }
                    usage?.let { put("usage", it.toResponses()) }
                }
            }}"
            lines += ""
        }
        return lines
    }

    override fun error(message: String): List<String> = streamError("response.failed", message)

    private data class MutableResponseTool(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: StringBuilder,
        var started: Boolean = false,
        var outputIndex: Int = -1,
    )
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
