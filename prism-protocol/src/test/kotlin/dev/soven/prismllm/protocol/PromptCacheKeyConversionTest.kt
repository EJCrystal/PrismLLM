package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Tag("unit")
class PromptCacheKeyConversionTest {
    @Test
    fun `converting to Responses preserves the client cache key before upstream defaults`() {
        for (protocol in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES)) {
            for (value in listOf("\"client-key\"", "\"\"", "null")) {
                val request = Json.parseToJsonElement("""{
                    "model":"m","messages":[{"role":"user","content":"hi"}],"max_tokens":128,
                    "prompt_cache_key":$value,"client_metadata":{"session_id":"private"}
                }""").jsonObject
                val result = ProtocolConversion.convertRequest(protocol, GatewayProtocol.RESPONSES, request, "upstream")
                assertEquals(request["prompt_cache_key"], result["prompt_cache_key"])
                assertFalse(result.containsKey("client_metadata"))
            }
            val missing = Json.parseToJsonElement("""{"model":"m","messages":[{"role":"user","content":"hi"}],"max_tokens":128}""").jsonObject
            assertFalse(ProtocolConversion.convertRequest(protocol, GatewayProtocol.RESPONSES, missing, "upstream").containsKey("prompt_cache_key"))
        }
    }
}
