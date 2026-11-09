package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object ResponsesToMessages : ProtocolConverter {
    override val inbound = GatewayProtocol.RESPONSES
    override val outbound = GatewayProtocol.MESSAGES

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        responsesToMessagesRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        messagesToResponsesResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(MessagesStreamDecoder(), ResponsesStreamEncoder(model))
}
