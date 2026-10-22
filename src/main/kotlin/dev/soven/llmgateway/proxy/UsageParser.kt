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
    val total: Long?,
) {
    val known: Boolean get() = input != null || output != null || cached != null || total != null
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
        if (input == null && output == null && cached == null && total == null) return null
        return Usage(input = input, output = output, cached = cached, total = total)
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
        if (input == null && output == null && cached == null && total == null) return null
        return Usage(input = input, output = output, cached = cached, total = total)
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
