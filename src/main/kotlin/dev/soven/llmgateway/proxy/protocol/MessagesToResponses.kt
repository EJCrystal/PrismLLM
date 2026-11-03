package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object MessagesToResponses : ProtocolConverter {
    override val inbound = GatewayProtocol.MESSAGES
    override val outbound = GatewayProtocol.RESPONSES

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        messagesToResponsesRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        responsesToMessagesResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(ResponsesStreamDecoder(), MessagesStreamEncoder(model))
}
