package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun replaceSystemPrompt(body: JsonObject, protocol: GatewayProtocol, prompt: String): JsonObject = when (protocol) {
    GatewayProtocol.MESSAGES -> JsonObject(body + ("system" to JsonPrimitive(prompt)))
    GatewayProtocol.CHAT_COMPLETIONS -> {
        val messages = body["messages"]
        if (messages != null && messages !is JsonArray) {
            throw ProtocolConversionException("messages", "messages 必须是数组")
        }
        val instruction = buildJsonObject {
            put("role", "system")
            put("content", prompt)
        }
        JsonObject(body + ("messages" to JsonArray(listOf(instruction) + withoutSystemMessages(messages).orEmpty())))
    }
    GatewayProtocol.RESPONSES -> {
        val rewritten = body.toMutableMap()
        rewritten["instructions"] = JsonPrimitive(prompt)
        withoutSystemMessages(body["input"])?.let { rewritten["input"] = it }
        JsonObject(rewritten)
    }
    GatewayProtocol.GEMINI -> {
        val cacheField = listOf("cachedContent", "cached_content").firstOrNull { !body.string(it).isNullOrBlank() }
        if (cacheField != null) {
            throw ProtocolConversionException(
                cacheField,
                "Gemini 显式缓存不能同时替换系统提示词。请停用匹配的替换规则，使用已包含目标系统提示词的缓存；" +
                    "或在请求中展开原缓存上下文后重试。网关不会删除缓存引用或丢弃其上下文。",
            )
        }
        JsonObject(
            (body - "system_instruction") + ("systemInstruction" to buildJsonObject {
                put("parts", JsonArray(listOf(buildJsonObject { put("text", prompt) })))
            }),
        )
    }
}

private fun withoutSystemMessages(element: JsonElement?): JsonArray? = (element as? JsonArray)?.let { messages ->
    JsonArray(messages.filterNot { message ->
        (message as? JsonObject)?.string("role") in setOf("system", "developer")
    })
}
