package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject

class ProtocolConversionException(
    val field: String,
    message: String,
) : IllegalArgumentException(message)

object ProtocolConversion {
    /** 启动自检，校验协议转换注册表覆盖所有有序组合。 */
    fun selfCheck() = ProtocolConverterRegistry.selfCheck()

    fun supports(inbound: GatewayProtocol, outbound: GatewayProtocol): Boolean =
        ProtocolConverterRegistry.supports(inbound, outbound)

    fun convertRequest(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        request: JsonObject,
        upstreamModel: String,
        carry: ReasoningCarryStore? = null,
    ): JsonObject = when {
        inbound == outbound -> request.withModel(upstreamModel)
        carry != null && inbound == GatewayProtocol.MESSAGES && outbound == GatewayProtocol.RESPONSES ->
            messagesToResponsesRequest(request, upstreamModel, carry)
        carry != null && inbound == GatewayProtocol.CHAT_COMPLETIONS && outbound == GatewayProtocol.RESPONSES ->
            chatToResponsesRequest(request, upstreamModel, carry)
        else -> ProtocolConverterRegistry.get(inbound, outbound).convertRequest(request, upstreamModel)
    }

    fun convertResponse(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        response: JsonObject,
        model: String,
        carry: ReasoningCarryStore? = null,
    ): JsonObject = when {
        inbound == outbound -> response
        carry != null && inbound == GatewayProtocol.MESSAGES && outbound == GatewayProtocol.RESPONSES ->
            responsesToMessagesResponse(response, model, carry)
        carry != null && inbound == GatewayProtocol.CHAT_COMPLETIONS && outbound == GatewayProtocol.RESPONSES ->
            responsesToChatResponse(response, model, carry)
        else -> ProtocolConverterRegistry.get(inbound, outbound).convertResponse(response, model)
    }

    fun streamConverter(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        model: String,
        carry: ReasoningCarryStore? = null,
    ): ProtocolStreamConverter? = when {
        inbound == outbound -> null
        carry != null && inbound == GatewayProtocol.MESSAGES && outbound == GatewayProtocol.RESPONSES ->
            EventStreamConverter(ResponsesStreamDecoder(), MessagesStreamEncoder(model), carry)
        carry != null && inbound == GatewayProtocol.CHAT_COMPLETIONS && outbound == GatewayProtocol.RESPONSES ->
            EventStreamConverter(ResponsesStreamDecoder(), ChatStreamEncoder(model), carry)
        else -> ProtocolConverterRegistry.get(inbound, outbound).streamConverter(model)
    }
}
