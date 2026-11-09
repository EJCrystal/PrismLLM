package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
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
    private val registered = listOf(
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
    )
    private val converters = registered.associateBy { it.inbound to it.outbound }

    /**
     * 启动自检：注册表必须覆盖所有协议的有序组合，且不能有重复键。
     * 缺失组合只会在真正收到该组合的请求时才暴露，所以必须在进程启动阶段拦截。
     */
    fun selfCheck() {
        val protocols = GatewayProtocol.entries
        val expected = protocols
            .flatMap { inbound -> protocols.filter { it != inbound }.map { inbound to it } }
            .toSet()
        val duplicates = registered
            .groupingBy { it.inbound to it.outbound }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val missing = expected - converters.keys
        val unexpected = converters.keys - expected
        check(duplicates.isEmpty() && missing.isEmpty() && unexpected.isEmpty()) {
            buildString {
                append("协议转换注册表不完整")
                if (duplicates.isNotEmpty()) {
                    append("；重复键：").append(duplicates.joinToString { "${it.first.value} -> ${it.second.value}" })
                }
                if (missing.isNotEmpty()) {
                    append("；缺少：").append(missing.joinToString { "${it.first.value} -> ${it.second.value}" })
                }
                if (unexpected.isNotEmpty()) {
                    append("；多余：").append(unexpected.joinToString { "${it.first.value} -> ${it.second.value}" })
                }
            }
        }
    }

    fun supports(inbound: GatewayProtocol, outbound: GatewayProtocol): Boolean =
        inbound == outbound || converters.containsKey(inbound to outbound)

    fun get(inbound: GatewayProtocol, outbound: GatewayProtocol): ProtocolConverter =
        converters[inbound to outbound]
            ?: throw ProtocolConversionException("protocol", "不支持 "+inbound.value+" → "+outbound.value+" 协议转换")
}
