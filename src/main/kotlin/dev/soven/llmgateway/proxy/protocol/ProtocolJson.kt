package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal val messagesRequestFields = setOf(
    "model", "messages", "system", "max_tokens", "stream", "temperature", "top_p",
    "stop_sequences", "metadata", "tools", "tool_choice", "thinking",
    "prompt_cache_key", "client_metadata",
)
internal val responsesRequestFields = setOf(
    "model", "input", "instructions", "max_output_tokens", "stream", "temperature", "top_p",
    "tools", "tool_choice", "reasoning", "text", "user", "metadata", "parallel_tool_calls",
    "store", "include", "truncation",
    "prompt_cache_key", "client_metadata",
)
internal val chatRequestFields = setOf(
    "model", "messages", "stream", "temperature", "top_p", "max_tokens", "max_completion_tokens",
    "stop", "user", "reasoning_effort", "response_format", "tools", "tool_choice",
    "parallel_tool_calls", "stream_options",
    "prompt_cache_key", "client_metadata",
)

internal fun JsonObject.asChatUpstream(model: String): JsonObject {
    val rewritten = withModel(model)
    if (rewritten.boolean("stream") != true) return rewritten
    val options = rewritten["stream_options"] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(rewritten + ("stream_options" to JsonObject(options + ("include_usage" to JsonPrimitive(true)))))
}

internal fun validateMessagesRequest(request: JsonObject) {
    rejectUnknownFields(request, messagesRequestFields, "request")
    rejectExtraMetadata(request["metadata"], "metadata")
}

internal fun validateMessagesTool(tool: JsonObject, path: String) {
    if (tool.containsKey("type") && tool.string("type") != "custom") {
        unsupported("$path.type", "跨协议转换仅支持自定义工具，不支持内置工具或无效工具类型")
    }
}

internal fun validateResponsesRequest(request: JsonObject) {
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
}

internal fun JsonObject.toolResultContent(path: String): String = when (val value = this["content"]) {
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.mapIndexed { index, element ->
        val obj = element as? JsonObject ?: unsupported("$path[$index]", "tool_result 内容块必须是对象")
        when (obj.string("type")) {
            "text" -> obj.string("text").orEmpty()
            "image" -> unsupported("$path[$index].type", "跨协议转换不支持 tool_result 图片内容")
            else -> unsupported("$path[$index].type", "第一版不支持 tool_result 内容类型 ${obj.string("type")}")
        }
    }.joinToString("\n")
    JsonNull, null -> ""
    else -> unsupported(path, "无法识别的 tool_result 内容")
}

internal fun thinkingFromEffort(effort: String): JsonObject = buildJsonObject {
    when (effort) {
        "none" -> put("type", "disabled")
        "minimal", "low" -> { put("type", "enabled"); put("budget_tokens", 1024) }
        "medium" -> { put("type", "enabled"); put("budget_tokens", 4096) }
        "high" -> { put("type", "enabled"); put("budget_tokens", 8192) }
        "xhigh", "max" -> { put("type", "enabled"); put("budget_tokens", 16384) }
        else -> unsupported("reasoning_effort", "未知 reasoning effort: $effort")
    }
}

internal fun thinkingAndMaxTokens(effort: String, requestedMaxTokens: Long?, maxTokensField: String): Pair<JsonObject, Long> {
    val thinking = thinkingFromEffort(effort)
    val requestedBudget = thinking.long("budget_tokens")
    val maxTokens = requestedMaxTokens ?: 4096L
    if (requestedBudget == null) return thinking to maxTokens
    if (requestedMaxTokens == null) return thinking to maxOf(maxTokens, requestedBudget + 1)
    if (maxTokens <= 1024) {
        unsupported(maxTokensField, "启用思考时 token 上限必须大于 1024")
    }
    val adjustedThinking = buildJsonObject {
        thinking.forEach { (key, value) -> if (key != "budget_tokens") put(key, value) }
        put("budget_tokens", minOf(requestedBudget, maxTokens - 1))
    }
    return adjustedThinking to maxTokens
}

internal fun JsonObject.thinkingEffort(): String? {
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

internal fun parseJsonObjectOrEmpty(value: String?): JsonObject =
    runCatching { value?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject } }
        .getOrNull() ?: buildJsonObject { }

internal fun rejectUnknownFields(obj: JsonObject, allowed: Set<String>, path: String) {
    val unknown = obj.keys.filterNot { it in allowed }
    if (unknown.isNotEmpty()) {
        unsupported("$path.${unknown.first()}", "协议转换不支持字段 ${unknown.joinToString(", ")}")
    }
}

internal fun rejectExtraMetadata(value: JsonElement?, path: String) {
    val metadata = value as? JsonObject ?: return
    val extra = metadata.keys.filterNot { it == "user_id" }
    if (extra.isNotEmpty()) {
        unsupported("$path.${extra.first()}", "协议转换不支持 metadata 字段 ${extra.joinToString(", ")}")
    }
}

internal fun JsonObject.withModel(model: String): JsonObject = buildJsonObject {
    this@withModel.forEach { (key, value) -> if (key != "model") put(key, value) }
    put("model", model)
}

internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.number(key: String): JsonElement? = this[key]?.takeIf { it is JsonPrimitive && !it.isString }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.stringArray(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

internal fun JsonObject.stringOrStringArray(key: String): List<String>? = when (val value = this[key]) {
    is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.let { listOf(it) }
    is JsonArray -> value.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
    else -> null
}

internal fun JsonObject.textContentStrict(key: String, path: String): String = when (val value = this[key]) {
    JsonNull -> ""
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    is JsonArray -> value.mapIndexed { index, element ->
        val obj = element as? JsonObject ?: unsupported("$path[$index]", "内容块必须是对象")
        when (obj.string("type")) {
            "text", "input_text", "output_text" -> obj.string("text").orEmpty()
            "refusal" -> obj.string("refusal").orEmpty()
            else -> unsupported("$path[$index].type", "第一版不支持该内容类型")
        }
    }.joinToString("\n")
    null -> ""
    else -> unsupported(path, "无法识别的内容")
}

internal fun unsupported(field: String, message: String): Nothing =
    throw ProtocolConversionException(field, message)
