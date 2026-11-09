package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object ChatCompletionToResponses : ProtocolConverter {
    override val inbound = GatewayProtocol.CHAT_COMPLETIONS
    override val outbound = GatewayProtocol.RESPONSES

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        chatToResponsesRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        responsesToChatResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(ResponsesStreamDecoder(), ChatStreamEncoder(model))
}
