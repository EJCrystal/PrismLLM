package dev.soven.prismllm.protocol

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
import kotlinx.serialization.json.putJsonObject

internal val messagesRequestFields = setOf(
    "model", "messages", "system", "max_tokens", "stream", "temperature", "top_p",
    "stop_sequences", "metadata", "tools", "tool_choice", "thinking",
    "prompt_cache_key", "client_metadata",
    "context_management", "output_config",
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

public fun JsonObject.asChatUpstream(model: String): JsonObject {
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

/**
 * Anthropic 允许一轮的并行 tool_use 分散在连续多条 assistant 消息里，由同一条 user 消息统一给出结果；
 * Chat、Responses、Gemini 都要求一条 assistant 的工具调用后紧跟全部工具结果，所以先把相邻的 assistant 合并。
 * 只合并前一条已经带 tool_use 的消息，普通连续 assistant 消息保持原样。
 */
internal fun joinSplitToolCalls(messages: List<JsonElement>): List<JsonElement> {
    val joined = mutableListOf<JsonElement>()
    messages.forEach { element ->
        val message = element as? JsonObject
        val previous = joined.lastOrNull() as? JsonObject
        if (message != null && previous != null &&
            previous.string("role") == "assistant" && message.string("role") == "assistant" &&
            previous.containsToolUse()
        ) {
            joined[joined.lastIndex] = JsonObject(
                previous + ("content" to JsonArray(assistantBlocks(previous) + assistantBlocks(message))),
            )
        } else {
            joined += element
        }
    }
    return joined
}

private fun JsonObject.containsToolUse(): Boolean =
    (this["content"] as? JsonArray)?.any { (it as? JsonObject)?.string("type") == "tool_use" } == true

private fun assistantBlocks(message: JsonObject): List<JsonElement> = when (val content = message["content"]) {
    is JsonArray -> content.toList()
    is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }
        ?.let { listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(it)))) }
        .orEmpty()
    else -> emptyList()
}

internal fun validateResponsesRequest(request: JsonObject) {
    request.string("previous_response_id")?.let {
        unsupported("previous_response_id", "协议转换不支持 Responses 有状态续接")
    }
    rejectUnknownFields(request, responsesRequestFields, "request")
    (request["store"] as? JsonPrimitive)?.let { store ->
        if (store.booleanOrNull != false) unsupported("store", "协议转换仅支持 Responses store=false")
    }
    request.string("truncation")?.takeIf { it != "disabled" }?.let {
        unsupported("truncation", "协议转换仅支持 truncation=disabled")
    }
    rejectExtraMetadata(request["metadata"], "metadata")
}

/** 跨协议转换中的图片。base64 与远程 URL 两种来源统一用 [dataUrl] 表示。 */
internal data class ImagePart(
    val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
) {
    fun dataUrl(): String? = when {
        url != null -> url
        data != null -> "data:${mediaType ?: "image/png"};base64,$data"
        else -> null
    }
}

internal data class ToolResultContent(
    val text: String,
    val images: List<ImagePart>,
)

internal fun parseImageUrl(url: String, path: String): ImagePart {
    if (!url.startsWith("data:")) return ImagePart(url = url)
    val comma = url.indexOf(',')
    if (comma <= 5) unsupported(path, "无法识别的 data URL 图片")
    val metadata = url.substring(5, comma)
    val data = url.substring(comma + 1)
    if (!metadata.endsWith(";base64")) unsupported(path, "仅支持 base64 data URL 图片")
    val mediaType = metadata.removeSuffix(";base64").substringBefore(';').ifBlank { "image/png" }
    return ImagePart(mediaType = mediaType, data = data)
}

internal fun imagePartFromAnthropic(block: JsonObject, path: String): ImagePart {
    val source = block["source"] as? JsonObject ?: unsupported("$path.source", "image 缺少 source")
    return when (source.string("type")) {
        "base64" -> ImagePart(
            mediaType = source.string("media_type") ?: unsupported("$path.source.media_type", "image 缺少 media_type"),
            data = source.string("data") ?: unsupported("$path.source.data", "image 缺少 data"),
        )
        "url" -> ImagePart(url = source.string("url") ?: unsupported("$path.source.url", "image 缺少 url"))
        else -> unsupported("$path.source.type", "不支持的 image source 类型 ${source.string("type")}")
    }
}

internal fun imagePartFromChat(part: JsonObject, path: String): ImagePart {
    val value = part["image_url"] ?: unsupported("$path.image_url", "image_url 缺少 url")
    val url = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> value.string("url")
        else -> null
    } ?: unsupported("$path.image_url", "image_url 缺少 url")
    return parseImageUrl(url, "$path.image_url")
}

internal fun imagePartFromResponses(part: JsonObject, path: String): ImagePart {
    if (part.string("file_id") != null) unsupported("$path.file_id", "跨协议转换不支持 Responses file_id 图片")
    val value = part["image_url"] ?: unsupported("$path.image_url", "input_image 缺少 image_url")
    val url = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> value.string("url")
        else -> null
    } ?: unsupported("$path.image_url", "input_image 缺少 image_url")
    return parseImageUrl(url, "$path.image_url")
}

internal fun imagePartFromGemini(part: JsonObject, path: String): ImagePart {
    val inline = part["inlineData"] as? JsonObject
    if (inline != null) {
        val mediaType = inline.string("mimeType") ?: unsupported("$path.inlineData.mimeType", "inlineData 缺少 mimeType")
        if (!mediaType.startsWith("image/")) unsupported("$path.inlineData.mimeType", "第一版不支持非图片多模态内容")
        return ImagePart(
            mediaType = mediaType,
            data = inline.string("data") ?: unsupported("$path.inlineData.data", "inlineData 缺少 data"),
        )
    }
    val file = part["fileData"] as? JsonObject
    if (file != null) {
        val mediaType = file.string("mimeType")
        if (mediaType != null && !mediaType.startsWith("image/")) {
            unsupported("$path.fileData.mimeType", "第一版不支持非图片多模态内容")
        }
        return ImagePart(
            mediaType = mediaType,
            url = file.string("fileUri") ?: unsupported("$path.fileData.fileUri", "fileData 缺少 fileUri"),
        )
    }
    unsupported(path, "不是图片内容")
}

internal fun chatTextPart(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

internal fun responsesTextPart(text: String): JsonObject = buildJsonObject {
    put("type", "input_text")
    put("text", text)
}

internal fun ImagePart.toChatPart(): JsonObject = buildJsonObject {
    put("type", "image_url")
    putJsonObject("image_url") {
        put("url", dataUrl() ?: unsupported("image", "图片缺少可用的 URL 或数据"))
    }
}

internal fun ImagePart.toResponsesPart(): JsonObject = buildJsonObject {
    put("type", "input_image")
    put("image_url", dataUrl() ?: unsupported("image", "图片缺少可用的 URL 或数据"))
}

internal fun ImagePart.toAnthropicBlock(): JsonObject = buildJsonObject {
    put("type", "image")
    putJsonObject("source") {
        if (url != null && data == null) {
            put("type", "url")
            put("url", url)
        } else {
            put("type", "base64")
            put("media_type", mediaType ?: "image/png")
            put("data", data ?: unsupported("image", "图片缺少数据"))
        }
    }
}

internal fun ImagePart.toGeminiPart(): JsonObject = buildJsonObject {
    if (data != null) {
        putJsonObject("inlineData") {
            put("mimeType", mediaType ?: "image/png")
            put("data", data)
        }
    } else if (url != null) {
        putJsonObject("fileData") {
            put("mimeType", mediaType ?: "image/png")
            put("fileUri", url)
        }
    } else {
        unsupported("image", "图片缺少可用的 URL 或数据")
    }
}

/**
 * Chat 的 tool 消息只能承载文本，工具返回的图片要挪到后面的 user 消息里。
 *
 * 图片先攒起来，作为下一条 user 消息的开头；下一条是 assistant 时单独补一条 user 消息，
 * 避免出现两条连续的 user 消息（部分模型的对话模板会拒绝）。
 */
internal class ChatToolImageRelocator(private val messages: MutableList<JsonObject>) {
    private val toolNames = mutableMapOf<String, String>()
    private val deferred = mutableListOf<JsonElement>()

    fun rememberToolName(callId: String, name: String) {
        toolNames[callId] = name
    }

    /** tool 消息的文本：原内容加一句图片去向说明。 */
    fun toolText(content: ToolResultContent): String {
        if (content.images.isEmpty()) return content.text
        val note = if (content.images.size == 1) {
            "[The tool returned an image; it follows in the next message.]"
        } else {
            "[The tool returned ${content.images.size} images; they follow in the next message.]"
        }
        return if (content.text.isBlank()) note else "${content.text}\n\n$note"
    }

    /** 把工具返回的图片记为下一条 user 消息的开头。 */
    fun defer(callId: String, images: List<ImagePart>) {
        if (images.isEmpty()) return
        val name = toolNames[callId]
        val source = if (name != null) "$name (tool call $callId)" else "tool call $callId"
        deferred += chatTextPart("[From the result of $source:]")
        images.forEach { deferred += it.toChatPart() }
    }

    /** 取出攒下的图片内容；没有则返回空列表。 */
    fun take(): List<JsonElement> {
        if (deferred.isEmpty()) return emptyList()
        val parts = deferred.toList()
        deferred.clear()
        return parts
    }

    /** 下一条是 assistant 时，把攒下的图片单独补成一条 user 消息。 */
    fun flush() {
        val parts = take()
        if (parts.isEmpty()) return
        messages += buildJsonObject {
            put("role", "user")
            put("content", JsonArray(parts))
        }
    }
}

/** Chat 的 tool 消息 content 通常是字符串；部分客户端会传 text/image 内容块数组。 */
internal fun chatContentToToolResultContent(content: JsonElement?, path: String): ToolResultContent = when (content) {
    null, JsonNull -> ToolResultContent("", emptyList())
    is JsonPrimitive -> ToolResultContent(content.contentOrNull.orEmpty(), emptyList())
    is JsonArray -> {
        val text = mutableListOf<String>()
        val images = mutableListOf<ImagePart>()
        content.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: unsupported("$path[$index]", "内容块必须是对象")
            when (obj.string("type")) {
                "text" -> text += obj.string("text").orEmpty()
                "image_url" -> images += imagePartFromChat(obj, "$path[$index]")
                "input_image" -> images += imagePartFromResponses(obj, "$path[$index]")
                else -> unsupported("$path[$index].type", "第一版不支持 tool 消息中的该内容类型")
            }
        }
        ToolResultContent(text.joinToString("\n"), images)
    }
    else -> unsupported(path, "无法识别的内容")
}

/** Responses function_call_output 的 output 可以是字符串，也可以是 input_text / input_image 内容项数组。 */
internal fun responsesToolOutputContent(output: JsonElement?, path: String): ToolResultContent = when (output) {
    null, JsonNull -> ToolResultContent("", emptyList())
    is JsonPrimitive -> ToolResultContent(output.contentOrNull.orEmpty(), emptyList())
    is JsonArray -> {
        val text = mutableListOf<String>()
        val images = mutableListOf<ImagePart>()
        output.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: unsupported("$path[$index]", "内容块必须是对象")
            when (obj.string("type")) {
                "text", "input_text", "output_text" -> text += obj.string("text").orEmpty()
                "input_image" -> images += imagePartFromResponses(obj, "$path[$index]")
                else -> unsupported("$path[$index].type", "第一版不支持 function_call_output 中的该内容类型")
            }
        }
        ToolResultContent(text.joinToString("\n"), images)
    }
    else -> unsupported(path, "无法识别的内容")
}

internal fun JsonObject.toolResultContent(path: String): ToolResultContent = when (val value = this["content"]) {
    JsonNull, null -> ToolResultContent("", emptyList())
    is JsonPrimitive -> ToolResultContent(value.contentOrNull.orEmpty(), emptyList())
    is JsonArray -> {
        val text = mutableListOf<String>()
        val images = mutableListOf<ImagePart>()
        value.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: unsupported("$path[$index]", "tool_result 内容块必须是对象")
            when (obj.string("type")) {
                "text" -> text += obj.string("text").orEmpty()
                "image" -> images += imagePartFromAnthropic(obj, "$path[$index]")
                // Claude Code 的 ToolSearch 用 tool_reference 声明某个延迟加载的工具已就绪；目标协议没有对应块，
                // 转成一句说明，避免整轮请求因为一个没有内容的信息块失败。
                "tool_reference" -> obj.string("tool_name")?.takeIf { it.isNotBlank() }?.let { name ->
                    text += "Tool $name is loaded and can be called now."
                }
                else -> unsupported("$path[$index].type", "第一版不支持 tool_result 内容类型 ${obj.string("type")}")
            }
        }
        ToolResultContent(text.joinToString("\n"), images)
    }
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

public fun JsonObject.thinkingEffort(): String? {
    val outputConfigEffort = ((this["output_config"] as? JsonObject)?.get("effort") as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    if (outputConfigEffort != null) return outputConfigEffort

    val thinking = this["thinking"] as? JsonObject ?: return null
    val thinkingEffort = (thinking["effort"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    if (thinkingEffort != null) return thinkingEffort

    if (thinking.string("type") == "disabled") return "none"
    val budget = thinking.long("budget_tokens") ?: return null
    return when {
        budget <= 1024 -> "low"
        budget <= 4096 -> "medium"
        budget <= 8192 -> "high"
        budget <= 16384 -> "xhigh"
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

public fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

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
