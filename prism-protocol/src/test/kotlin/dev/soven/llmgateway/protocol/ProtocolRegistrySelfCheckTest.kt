package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlin.test.Test
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
}