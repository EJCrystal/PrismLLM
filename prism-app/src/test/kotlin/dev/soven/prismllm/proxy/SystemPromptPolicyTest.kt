package dev.soven.prismllm.proxy

import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.shared.error.GatewayError
import org.junit.jupiter.api.Tag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Tag("unit")
class SystemPromptPolicyTest {
    @Test
    fun dedicatedPromptWinsAfterGenericBodyOverridesAndIsCountedInBodyLimit() {
        val original = Json.parseToJsonElement("""{"messages":[{"role":"user","content":"hello"}]}""").jsonObject
        val overrides = UpstreamRequestOverrides(body = Json.parseToJsonElement("""{"system":"generic"}""").jsonObject)
        val policy = UpstreamRequestPolicy(overrides, UpstreamRequestOverrides(), GatewayProtocol.MESSAGES, "anthropic", systemPrompt = "dedicated")
        assertEquals(JsonPrimitive("dedicated"), policy.body(original, "model")["system"])
        val error = assertFailsWith<GatewayError> { policy.body(original, "model", limitBytes = 8) }
        assertEquals("request_too_large", error.code)
        assertEquals(null, original["system"])
        val unchanged = UpstreamRequestPolicy(overrides, UpstreamRequestOverrides(), GatewayProtocol.MESSAGES, "anthropic")
        assertEquals(JsonPrimitive("generic"), unchanged.body(original, "model")["system"])
    }
}
