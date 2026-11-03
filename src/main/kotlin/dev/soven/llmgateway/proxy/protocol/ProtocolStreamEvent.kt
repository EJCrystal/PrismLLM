package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.max

internal data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val totalTokens: Long = inputTokens + outputTokens,
) {
    fun toChat(): JsonObject = buildJsonObject {
        put("prompt_tokens", inputTokens)
        put("completion_tokens", outputTokens)
        put("total_tokens", totalTokens)
        if (cachedTokens != null || cacheWriteTokens != null) putJsonObject("prompt_tokens_details") {
            cachedTokens?.let { put("cached_tokens", it) }
            cacheWriteTokens?.let { put("cache_write_tokens", it) }
        }
        reasoningTokens?.let { putJsonObject("completion_tokens_details") { put("reasoning_tokens", it) } }
    }

    fun toMessages(): JsonObject = buildJsonObject {
        put("input_tokens", max(0L, inputTokens - (cachedTokens ?: 0L) - (cacheWriteTokens ?: 0L)))
        put("output_tokens", outputTokens)
        cachedTokens?.takeIf { it > 0 }?.let { put("cache_read_input_tokens", it) }
        cacheWriteTokens?.takeIf { it > 0 }?.let { put("cache_creation_input_tokens", it) }
    }

    fun toResponses(): JsonObject = buildJsonObject {
        put("input_tokens", inputTokens)
        put("output_tokens", outputTokens)
        put("total_tokens", totalTokens)
        if (cachedTokens != null || cacheWriteTokens != null) putJsonObject("input_tokens_details") {
            cachedTokens?.let { put("cached_tokens", it) }
            cacheWriteTokens?.let { put("cache_write_tokens", it) }
        }
        reasoningTokens?.let { putJsonObject("output_tokens_details") { put("reasoning_tokens", it) } }
    }

    fun toGemini(): JsonObject = buildJsonObject {
        put("promptTokenCount", inputTokens)
        put("candidatesTokenCount", outputTokens)
        put("totalTokenCount", totalTokens)
        cachedTokens?.takeIf { it > 0 }?.let { put("cachedContentTokenCount", it) }
        reasoningTokens?.takeIf { it > 0 }?.let { put("thoughtsTokenCount", it) }
    }

    companion object {
        fun fromChat(usage: JsonObject?): TokenUsage? {
            if (usage == null) return null
            val input = usage.long("prompt_tokens") ?: 0L
            val output = usage.long("completion_tokens") ?: 0L
            val details = usage["prompt_tokens_details"] as? JsonObject
            return TokenUsage(
                input, output,
                cachedTokens = details?.long("cached_tokens"),
                cacheWriteTokens = details?.long("cache_write_tokens"),
                reasoningTokens = (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens"),
                totalTokens = usage.long("total_tokens") ?: input + output,
            )
        }

        fun fromMessages(usage: JsonObject?): TokenUsage? {
            if (usage == null) return null
            val cached = usage.long("cache_read_input_tokens")
            val cacheWrite = usage.long("cache_creation_input_tokens")
            return TokenUsage(
                inputTokens = (usage.long("input_tokens") ?: 0L) + (cached ?: 0L) + (cacheWrite ?: 0L),
                outputTokens = usage.long("output_tokens") ?: 0L,
                cachedTokens = cached,
                cacheWriteTokens = cacheWrite,
            )
        }

        fun fromResponses(usage: JsonObject?): TokenUsage? {
            if (usage == null) return null
            val input = usage.long("input_tokens") ?: 0L
            val output = usage.long("output_tokens") ?: 0L
            val details = usage["input_tokens_details"] as? JsonObject
            return TokenUsage(
                input, output,
                cachedTokens = details?.long("cached_tokens")?.takeIf { it > 0 },
                cacheWriteTokens = details?.long("cache_write_tokens")?.takeIf { it > 0 },
                reasoningTokens = (usage["output_tokens_details"] as? JsonObject)?.long("reasoning_tokens"),
                totalTokens = usage.long("total_tokens") ?: input + output,
            )
        }

        fun fromGemini(usage: JsonObject?): TokenUsage? {
            if (usage == null) return null
            val input = usage.long("promptTokenCount") ?: 0L
            val output = usage.long("candidatesTokenCount") ?: 0L
            return TokenUsage(
                input, output,
                cachedTokens = usage.long("cachedContentTokenCount")?.takeIf { it > 0 },
                reasoningTokens = usage.long("thoughtsTokenCount"),
                totalTokens = usage.long("totalTokenCount") ?: input + output,
            )
        }
    }
}

internal enum class FinishReason(val chatValue: String, val messagesValue: String) {
    STOP("stop", "end_turn"),
    MAX_TOKENS("length", "max_tokens"),
    TOOL_CALLS("tool_calls", "tool_use"),
    CONTENT_FILTER("content_filter", "refusal"),
    ;

    companion object {
        fun fromChat(value: String?): FinishReason? = value?.let { reason -> entries.firstOrNull { it.chatValue == reason } ?: STOP }
        fun fromMessages(value: String?): FinishReason? = value?.let { reason -> entries.firstOrNull { it.messagesValue == reason } ?: STOP }
    }
}

internal data class StreamEvent(
    val id: String,
    val role: String? = null,
    val content: String? = null,
    val refusal: String? = null,
    val toolCalls: List<ToolCallDelta> = emptyList(),
    val finishReason: FinishReason? = null,
    val done: Boolean = false,
    val error: String? = null,
)

internal data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

internal interface StreamDecoder {
    val usage: TokenUsage?
    fun decode(event: JsonObject): List<StreamEvent>
}

internal interface StreamEncoder {
    fun emit(event: StreamEvent, usage: TokenUsage?): List<String>
    fun error(message: String, id: String): List<String>
    fun terminalLines(): List<String> = emptyList()
}
