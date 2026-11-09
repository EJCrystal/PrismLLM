package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

object ChatCompletionToMessages : ProtocolConverter {
    override val inbound = GatewayProtocol.CHAT_COMPLETIONS
    override val outbound = GatewayProtocol.MESSAGES

    override fun convertRequest(request: JsonObject, upstreamModel: String): JsonObject =
        chatToMessagesRequest(request, upstreamModel)

    override fun convertResponse(response: JsonObject, model: String): JsonObject =
        messagesToChatResponse(response, model)

    override fun streamConverter(model: String): ProtocolStreamConverter =
        EventStreamConverter(MessagesStreamDecoder(), ChatStreamEncoder(model))
}
