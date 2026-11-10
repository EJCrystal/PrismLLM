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
    ): JsonObject = if (inbound == outbound) request.withModel(upstreamModel)
        else ProtocolConverterRegistry.get(inbound, outbound).convertRequest(request, upstreamModel)

    fun convertResponse(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        response: JsonObject,
        model: String,
    ): JsonObject = if (inbound == outbound) response
        else ProtocolConverterRegistry.get(inbound, outbound).convertResponse(response, model)

    fun streamConverter(
        inbound: GatewayProtocol,
        outbound: GatewayProtocol,
        model: String,
    ): ProtocolStreamConverter? = if (inbound == outbound) null
        else ProtocolConverterRegistry.get(inbound, outbound).streamConverter(model)
}
