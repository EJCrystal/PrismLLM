package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object ResponsesToChatCompletion : ProtocolConverter {
    override val inbound = GatewayProtocol.RESPONSES
    override val outbound = GatewayProtocol.CHAT_COMPLETIONS

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        responsesToChatRequest(request).asChatUpstream(upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        chatToResponsesResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(ChatStreamDecoder(), ResponsesStreamEncoder(model))
}
