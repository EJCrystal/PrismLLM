package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object MessagesToChatCompletion : ProtocolConverter {
    override val inbound = GatewayProtocol.MESSAGES
    override val outbound = GatewayProtocol.CHAT_COMPLETIONS

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        messagesToChatRequest(request).asChatUpstream(upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        chatToMessagesResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(ChatStreamDecoder(), MessagesStreamEncoder(model))
}
