package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

interface ProtocolConverter {
    val inbound: GatewayProtocol
    val outbound: GatewayProtocol

    fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject
    fun convertResponse(response: JsonObject, model: String): JsonObject
    fun streamConverter(model: String): ProtocolStreamConverter
}

interface ProtocolStreamConverter {
    val failed: Boolean
    fun feed(line: String): List<String>
    fun finish(): List<String>
    fun fail(message: String): List<String>
}

object ProtocolConverterRegistry {
    private val converters = listOf(
        MessagesToChatCompletion,
        ChatCompletionToMessages,
        ResponsesToChatCompletion,
        ChatCompletionToResponses,
        MessagesToResponses,
        ResponsesToMessages,
        ChatCompletionToGemini,
        GeminiToChatCompletion,
        MessagesToGemini,
        GeminiToMessages,
        ResponsesToGemini,
        GeminiToResponses,
    ).associateBy { it.inbound to it.outbound }

    fun get(inbound: GatewayProtocol, outbound: GatewayProtocol): ProtocolConverter =
        converters[inbound to outbound]
            ?: throw ProtocolConversionException("protocol", "不支持 "+inbound.value+" → "+outbound.value+" 协议转换")
}
