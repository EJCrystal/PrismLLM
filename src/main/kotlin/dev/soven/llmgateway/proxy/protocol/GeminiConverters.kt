package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object ChatCompletionToGemini : ProtocolConverter {
    override val inbound = GatewayProtocol.CHAT_COMPLETIONS
    override val outbound = GatewayProtocol.GEMINI

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        chatToGeminiRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        geminiToChatResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(GeminiStreamDecoder(), ChatStreamEncoder(model))
}

object GeminiToChatCompletion : ProtocolConverter {
    override val inbound = GatewayProtocol.GEMINI
    override val outbound = GatewayProtocol.CHAT_COMPLETIONS

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        geminiToChatRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        chatToGeminiResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(ChatStreamDecoder(), GeminiStreamEncoder(model))
}

object MessagesToGemini : ProtocolConverter {
    override val inbound = GatewayProtocol.MESSAGES
    override val outbound = GatewayProtocol.GEMINI

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        messagesToGeminiRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        geminiToMessagesResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(GeminiStreamDecoder(), MessagesStreamEncoder(model))
}

object GeminiToMessages : ProtocolConverter {
    override val inbound = GatewayProtocol.GEMINI
    override val outbound = GatewayProtocol.MESSAGES

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        geminiToMessagesRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        messagesToGeminiResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(MessagesStreamDecoder(), GeminiStreamEncoder(model))
}

object ResponsesToGemini : ProtocolConverter {
    override val inbound = GatewayProtocol.RESPONSES
    override val outbound = GatewayProtocol.GEMINI

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        responsesToGeminiRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        geminiToResponsesResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(GeminiStreamDecoder(), ResponsesStreamEncoder(model))
}

object GeminiToResponses : ProtocolConverter {
    override val inbound = GatewayProtocol.GEMINI
    override val outbound = GatewayProtocol.RESPONSES

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        geminiToResponsesRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        responsesToGeminiResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(ResponsesStreamDecoder(), GeminiStreamEncoder(model))
}
