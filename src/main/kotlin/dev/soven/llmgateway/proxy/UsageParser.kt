package dev.soven.llmgateway.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 一次请求的 token 用量。拿不到的字段保持 null，不估算。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class Usage(
    val input: Long?,
    val output: Long?,
    val cached: Long?,
    val reasoning: Long?,
    val total: Long?,
) {
    val known: Boolean
        get() = input != null || output != null || cached != null || reasoning != null || total != null
}

/**
 * 从 OpenAI 兼容响应中解析 usage。流式响应只在包含 usage 的 chunk 上返回结果。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object UsageParser {
    fun fromSseLine(line: String): Usage? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val json = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        return fromJson(json)
    }

    fun fromJson(json: JsonObject): Usage? {
        val usage = json["usage"] as? JsonObject ?: return null
        val input = usage.long("prompt_tokens")
        val output = usage.long("completion_tokens")
        val total = usage.long("total_tokens")
        val cached = (usage["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens")
            ?: usage.long("prompt_cache_hit_tokens")
        val reasoning = (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: usage.long("reasoning_tokens")
        if (input == null && output == null && cached == null && reasoning == null && total == null) return null
        return Usage(input = input, output = output, cached = cached, reasoning = reasoning, total = total)
    }

    /**
     * Responses API 的 usage 可能直接在响应对象上，也可能包在流式事件的 `response` 字段里。
     */
    fun fromResponsesJson(json: JsonObject): Usage? {
        val response = json["response"] as? JsonObject ?: json
        val usage = response["usage"] as? JsonObject ?: return null
        val input = usage.long("input_tokens")
        val output = usage.long("output_tokens")
        val total = usage.long("total_tokens")
        val cached = (usage["input_tokens_details"] as? JsonObject)?.long("cached_tokens")
            ?: usage.long("prompt_cache_hit_tokens")
        val reasoning = (usage["output_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: usage.long("reasoning_tokens")
        if (input == null && output == null && cached == null && reasoning == null && total == null) return null
        return Usage(
            input = input,
            output = output,
            cached = cached,
            reasoning = reasoning,
            total = total,
        )
    }

    /**
     * Anthropic usage appears on the non-streaming message, inside a streaming message_start,
     * and partially on message_delta. Cache reads are tracked separately; cache creation is
     * included in input because the current billing model has no cache-write price tier.
     */
    fun fromAnthropicJson(json: JsonObject): Usage? {
        val message = json["message"] as? JsonObject
        val usage = (message?.get("usage") as? JsonObject)
            ?: (json["usage"] as? JsonObject)
            ?: return null
        val inputTokens = usage.long("input_tokens")
        val cacheCreationTokens = usage.long("cache_creation_input_tokens")
        val cacheReadTokens = usage.long("cache_read_input_tokens")
        val input = listOfNotNull(inputTokens, cacheCreationTokens, cacheReadTokens)
            .takeIf { it.isNotEmpty() }
            ?.sum()
        val output = usage.long("output_tokens")
        // Streaming message_start carries an initial output count, which message_delta later
        // replaces. Keep total unknown until the final output count arrives.
        val eventType = (json["type"] as? JsonPrimitive)?.content
        val isPartialStreamEvent = eventType == "message_start" || eventType == "message_delta"
        val total = usage.long("total_tokens")
            ?: if (!isPartialStreamEvent && input != null && output != null) input + output else null
        if (input == null && output == null && cacheReadTokens == null && total == null) return null
        return Usage(
            input = input,
            output = output,
            cached = cacheReadTokens,
            reasoning = null,
            total = total,
        )
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
