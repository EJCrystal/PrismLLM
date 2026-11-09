package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.protocol.UsageParser

import dev.soven.llmgateway.shared.error.GatewayError
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeUpstreamRequestTest {
    @Test
    fun `Gemini encodes the model within the path and uses the native SSE action`() {
        assertEquals(
            "https://example.com/v1beta/models/gemini-2.5-pro:generateContent",
            upstreamRequestUrl("https://example.com/v1beta/", GatewayProtocol.GEMINI, "models/gemini-2.5-pro"),
        )
        assertEquals(
            "https://example.com/v1beta/models/custom%3Fkey=secret:streamGenerateContent?alt=sse",
            upstreamRequestUrl("https://example.com/v1beta", GatewayProtocol.GEMINI, "custom?key=secret", stream = true),
        )
    }

    @Test
    fun `Gemini native model test and result use contents and candidates`() {
        val body = Json.parseToJsonElement(buildModelTestBody(GatewayProtocol.GEMINI, "m", "say OK", 128)).jsonObject
        assertFalse(body.containsKey("model"))
        assertFalse(body.containsKey("stream"))
        assertTrue(body.containsKey("contents"))
        val result = Json.parseToJsonElement("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]}}]}").jsonObject
        assertEquals("OK", extractModelTestResponseText(GatewayProtocol.GEMINI, result))
        val inflated = Json.parseToJsonElement("{\"generationConfig\":{\"maxOutputTokens\":10000,\"temperature\":0.5}}").jsonObject
        assertEquals(
            Json.parseToJsonElement("{\"generationConfig\":{\"maxOutputTokens\":128,\"temperature\":0.5}}"),
            limitModelTestTokens(inflated, GatewayProtocol.GEMINI, 128),
        )
    }

    @Test
    fun `Gemini model test rejects a non-object generationConfig instead of replacing it`() {
        listOf("null", "\"invalid\"", "1", "[]").forEach { value ->
            val body = Json.parseToJsonElement("""{"generationConfig":$value}""").jsonObject
            val error = assertFailsWith<GatewayError> {
                limitModelTestTokens(body, GatewayProtocol.GEMINI, 128)
            }
            assertEquals(HttpStatusCode.BadRequest, error.status)
            assertEquals("invalid_request_overrides", error.code)
        }
    }

    @Test
    fun `Gemini usage includes thoughts in output and does not double count cached input`() {
        val response = Json.parseToJsonElement("{\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":3,\"thoughtsTokenCount\":2,\"cachedContentTokenCount\":4,\"totalTokenCount\":15}}").jsonObject
        val usage = UsageParser.fromGeminiJson(response)!!
        assertEquals(10L, usage.input)
        assertEquals(5L, usage.output)
        assertEquals(2L, usage.reasoning)
        assertEquals(4L, usage.cached)
        assertEquals(15L, usage.total)
        assertNull(UsageParser.fromGeminiJson(Json.parseToJsonElement("{\"usageMetadata\":{}}").jsonObject))
    }
}
