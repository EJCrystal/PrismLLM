package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal const val MAX_PROTOCOL_EVENT_BYTES = 1024 * 1024

internal class EventStreamConverter(
    private val decoder: StreamDecoder,
    private val encoder: StreamEncoder,
) : ProtocolStreamConverter {
    override var failed: Boolean = false
        private set
    private val dataLines = mutableListOf<String>()
    private var pendingBytes = 0
    private var finished = false
    private var pendingFinish: StreamEvent? = null
    private var lastId = "response_"+System.currentTimeMillis()

    override fun feed(line: String): List<String> {
        if (finished) return emptyList()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return flushEvent()
        if (trimmed.startsWith("data:")) {
            val data = trimmed.removePrefix("data:").trimStart()
            val separatorBytes = if (dataLines.isEmpty()) 0 else 1
            if (data.length > MAX_PROTOCOL_EVENT_BYTES - pendingBytes - separatorBytes) {
                return fail("上游流单个事件超过大小上限")
            }
            val nextBytes = data.toByteArray(Charsets.UTF_8).size + separatorBytes
            if (nextBytes > MAX_PROTOCOL_EVENT_BYTES - pendingBytes) {
                return fail("上游流单个事件超过大小上限")
            }
            pendingBytes += nextBytes
            dataLines += data
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
        pendingBytes = 0
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
                    pendingFinish = event.copy(content = null, toolCalls = emptyList())
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

    override fun fail(message: String): List<String> {
        if (finished) return emptyList()
        finished = true
        failed = true
        pendingFinish = null
        dataLines.clear()
        pendingBytes = 0
        return encoder.error(message, lastId)
    }
}
