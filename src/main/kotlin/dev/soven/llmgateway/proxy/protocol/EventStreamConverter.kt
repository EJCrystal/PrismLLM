package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal class EventStreamConverter(
    private val decoder: StreamDecoder,
    private val encoder: StreamEncoder,
) : ProtocolStreamConverter {
    private val dataLines = mutableListOf<String>()
    private var finished = false
    private var pendingFinish: StreamEvent? = null
    private var lastId = "response_"+System.currentTimeMillis()

    override fun feed(line: String): List<String> {
        if (finished) return emptyList()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return flushEvent()
        if (trimmed.startsWith("data:")) {
            dataLines += trimmed.removePrefix("data:").trimStart()
            if (dataLines.size == 1 && dataLines.first() == "[DONE]") return flushEvent()
        }
        return emptyList()
    }

    override fun finish(): List<String> {
        if (finished) return emptyList()
        val lines = flushEvent().toMutableList()
        if (!finished) {
            pendingFinish?.let { lines += complete(it) }
            finished = true
        }
        return lines
    }

    private fun flushEvent(): List<String> {
        if (dataLines.isEmpty()) return emptyList()
        val payload = dataLines.joinToString("\n")
        dataLines.clear()
        if (payload == "[DONE]") return complete(pendingFinish ?: StreamEvent(lastId, finishReason = FinishReason.STOP))
        val json = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return fail("上游流返回了无法解析的 JSON 帧")
        return decoder.decode(json).flatMap { event ->
            if (finished) return@flatMap emptyList()
            lastId = event.id
            when {
                event.error != null -> fail(event.error)
                event.done -> complete(pendingFinish ?: event)
                event.finishReason != null -> {
                    pendingFinish = event
                    if (event.content != null || event.toolCalls.isNotEmpty()) encoder.emit(event.copy(finishReason = null), decoder.usage)
                    else emptyList()
                }
                else -> encoder.emit(event, decoder.usage)
            }
        }
    }

    private fun complete(event: StreamEvent): List<String> {
        finished = true
        pendingFinish = null
        return encoder.emit(event.copy(finishReason = event.finishReason ?: FinishReason.STOP, done = false), decoder.usage) + encoder.terminalLines()
    }

    private fun fail(message: String): List<String> {
        finished = true
        pendingFinish = null
        return encoder.error(message)
    }
}
