package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
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
     * 启动自检：注册表必须覆盖所有协议的有序组合，且不能有重复键或自转换注册。
     * 同协议请求由 [ProtocolConversion] 短路直通，不走注册表，因此注册自转换
     * 只会制造歧义。缺失组合只会在真正收到该组合的请求时才暴露，
     * 所以必须在进程启动阶段拦截。
     */
    fun selfCheck(registrations: List<ProtocolConverter> = registered) {
        val protocols = GatewayProtocol.entries
        val expected = protocols
            .flatMap { inbound -> protocols.filter { it != inbound }.map { inbound to it } }
            .toSet()
        val duplicates = registrations
            .groupingBy { it.inbound to it.outbound }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val selfConversions = registrations
            .filter { it.inbound == it.outbound }
            .map { it.inbound }
            .toSet()
        val convertersOf = registrations.associateBy { it.inbound to it.outbound }
        val missing = expected - convertersOf.keys
        val unexpected = convertersOf.keys - expected
        check(
            duplicates.isEmpty() &&
                missing.isEmpty() &&
                unexpected.isEmpty() &&
                selfConversions.isEmpty(),
        ) {
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
                if (selfConversions.isNotEmpty()) {
                    append("；同协议请求由 ProtocolConversion 直通，请勿注册：")
                        .append(selfConversions.joinToString { it.value })
                }
            }
        }
    }

    /** 同协议请求由 [ProtocolConversion] 短路直通，注册表只负责跨协议转换。 */
    fun supports(inbound: GatewayProtocol, outbound: GatewayProtocol): Boolean =
        inbound == outbound || converters.containsKey(inbound to outbound)

    fun get(inbound: GatewayProtocol, outbound: GatewayProtocol): ProtocolConverter {
        if (inbound == outbound) {
            throw ProtocolConversionException(
                "protocol",
                "同协议 "+inbound.value+" 请求应由 ProtocolConversion 直通，无需转换器",
            )
        }
        return converters[inbound to outbound]
            ?: throw ProtocolConversionException("protocol", "不支持 "+inbound.value+" → "+outbound.value+" 协议转换")
    }
}
