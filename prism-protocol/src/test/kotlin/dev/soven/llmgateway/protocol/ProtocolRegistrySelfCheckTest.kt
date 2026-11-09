package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolRegistrySelfCheckTest {
    @Test
    fun `self check passes for the current registry`() {
        ProtocolConversion.selfCheck()
    }

    @Test
    fun `every distinct protocol pair is supported`() {
        GatewayProtocol.entries.forEach { inbound ->
            GatewayProtocol.entries
                .filter { it != inbound }
                .forEach { outbound ->
                    assertTrue(
                        ProtocolConversion.supports(inbound, outbound),
                        "缺少 ${inbound.value} -> ${outbound.value} 的转换器",
                    )
                }
        }
    }

    @Test
    fun `self conversions must be rejected by self check`() {
        val offending = object : ProtocolConverter {
            override val inbound = GatewayProtocol.MESSAGES
            override val outbound = GatewayProtocol.MESSAGES
            override fun convertRequest(request: JsonObject, upstreamModel: String) = request
            override fun convertResponse(response: JsonObject, model: String) = response
            override fun streamConverter(model: String) = throw UnsupportedOperationException()
        }
        val message = assertFailsWith<IllegalStateException> {
            ProtocolConverterRegistry.selfCheck(listOf(offending))
        }.message.orEmpty()
        assertTrue(
            "请勿注册" in message,
            "自转换应当被明确拒绝，实际信息：$message",
        )
    }
}