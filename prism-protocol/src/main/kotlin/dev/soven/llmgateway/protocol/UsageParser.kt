package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull


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
        val cacheWrite = (usage["prompt_tokens_details"] as? JsonObject)?.long("cache_write_tokens")
            ?: (usage["prompt_tokens_details"] as? JsonObject)?.long("cache_creation_tokens")
            ?: usage.long("cache_creation_input_tokens")
        val reasoning = (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: usage.long("reasoning_tokens")
        if (input == null && output == null && cached == null && cacheWrite == null && reasoning == null && total == null) return null
        return Usage(input = input, output = output, cached = cached, cacheWrite = cacheWrite, reasoning = reasoning, total = total)
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
        val cacheWrite = (usage["input_tokens_details"] as? JsonObject)?.long("cache_write_tokens")
            ?: (usage["input_tokens_details"] as? JsonObject)?.long("cache_creation_tokens")
            ?: usage.long("cache_creation_input_tokens")
        val reasoning = (usage["output_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: (usage["completion_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: usage.long("reasoning_tokens")
        if (input == null && output == null && cached == null && cacheWrite == null && reasoning == null && total == null) return null
        return Usage(
            input = input,
            output = output,
            cached = cached,
            cacheWrite = cacheWrite,
            reasoning = reasoning,
            total = total,
        )
    }

    /**
     * Anthropic usage appears on the non-streaming message, inside a streaming message_start,
     * and partially on message_delta.
     *
     * Note on Anthropic token accounting:
     * In Anthropic Messages API, `input_tokens` only counts uncached prompt tokens;
     * `cache_creation_input_tokens` (cache write) and `cache_read_input_tokens` (cache read)
     * are reported separately without being included in `input_tokens`.
     * To align with Gateway's unified billing model (where `input` represents total prompt volume),
     * `Usage.input` is the sum of (uncached input + cache creation + cache read). Downstream,
     * `BillingCalculator` subtracts `cachedRead` and `cachedWrite` from `input` to recover the uncached
     * input count, ensuring each token category is priced exactly once without overlap or double billing.
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
        val reasoning = (usage["output_tokens_details"] as? JsonObject)?.long("reasoning_tokens")
            ?: (usage["output_tokens_details"] as? JsonObject)?.long("thinking_tokens")
            ?: usage.long("reasoning_tokens")
            ?: usage.long("thinking_tokens")
        if (input == null && output == null && cacheReadTokens == null && total == null && reasoning == null) return null
        return Usage(
            input = input,
            output = output,
            cached = cacheReadTokens,
            cacheWrite = cacheCreationTokens,
            reasoning = reasoning,
            total = total,
        )
    }

    /** Gemini prompt tokens include cached tokens; generated thoughts are billed as output tokens. */
    fun fromGeminiJson(json: JsonObject): Usage? {
        val metadata = json["usageMetadata"] as? JsonObject ?: return null
        val input = metadata.long("promptTokenCount")
        val candidates = metadata.long("candidatesTokenCount")
        val reasoning = metadata.long("thoughtsTokenCount")
        val output = listOfNotNull(candidates, reasoning).takeIf { it.isNotEmpty() }?.sum()
        val cached = metadata.long("cachedContentTokenCount")
        val total = metadata.long("totalTokenCount")
        return Usage(input, output, cached, reasoning = reasoning, total = total).takeIf { it.known }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
