package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.protocol.extractReportedModel
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModelConnectivityTestTest {
    @Test
    fun `builds chat body with prompt and no reasoning effort`() {
        val body = Json.parseToJsonElement(
            buildModelTestBody(
                protocol = GatewayProtocol.CHAT_COMPLETIONS,
                model = "gpt-test",
                prompt = "say ok",
                maxOutputTokens = 128,
            ),
        ).jsonObject

        assertEquals("gpt-test", body["model"]?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
        assertEquals("say ok", body["messages"]?.jsonArray?.first()?.jsonObject?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun `builds responses body without reasoning effort`() {
        val body = Json.parseToJsonElement(
            buildModelTestBody(
                protocol = GatewayProtocol.RESPONSES,
                model = "gpt-test",
                prompt = "say ok",
                maxOutputTokens = 128,
            ),
        ).jsonObject

        assertNull(body["reasoning"])
        assertEquals("say ok", body["input"]?.jsonPrimitive?.content)
    }

    @Test
    fun `messages body omits reasoning effort`() {
        val body = Json.parseToJsonElement(
            buildModelTestBody(
                protocol = GatewayProtocol.MESSAGES,
                model = "claude-test",
                prompt = "say ok",
                maxOutputTokens = 128,
            ),
        ).jsonObject

        assertNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
    }

    @Test
    fun `extracts text from each protocol response`() {
        val chat = Json.parseToJsonElement(
            """{"choices":[{"message":{"content":"chat ok"}}]}""",
        ).jsonObject
        val responses = Json.parseToJsonElement(
            """{"output":[{"content":[{"type":"output_text","text":"responses ok"}]}]}""",
        ).jsonObject
        val messages = Json.parseToJsonElement(
            """{"content":[{"type":"text","text":"messages ok"}]}""",
        ).jsonObject

        assertEquals("chat ok", extractModelTestResponseText(GatewayProtocol.CHAT_COMPLETIONS, chat))
        assertEquals("responses ok", extractModelTestResponseText(GatewayProtocol.RESPONSES, responses))
        assertEquals("messages ok", extractModelTestResponseText(GatewayProtocol.MESSAGES, messages))
    }

    @Test
    fun extracts_model_identifier_reported_in_upstream_response_metadata() {
        val responses = Json.parseToJsonElement("{\"model\":\" mapped-model \"}").jsonObject
        val gemini = Json.parseToJsonElement("{\"modelVersion\":\"gemini-test\"}").jsonObject
        val invalid = Json.parseToJsonElement("{\"model\":42}").jsonObject

        assertEquals("mapped-model", extractReportedModel(GatewayProtocol.RESPONSES, responses))
        assertEquals("gemini-test", extractReportedModel(GatewayProtocol.GEMINI, gemini))
        assertNull(extractReportedModel(GatewayProtocol.CHAT_COMPLETIONS, invalid))
        assertNull(extractReportedModel(GatewayProtocol.MESSAGES, null))
    }

    @Test
    fun extracts_model_from_protocol_specific_stream_metadata_shapes() {
        val responses = Json.parseToJsonElement("{\"response\":{\"model\":\"responses-model\"}}").jsonObject
        val messages = Json.parseToJsonElement("{\"type\":\"message_start\",\"message\":{\"model\":\"messages-model\"}}").jsonObject

        assertEquals("responses-model", extractReportedModel(GatewayProtocol.RESPONSES, responses))
        assertEquals("messages-model", extractReportedModel(GatewayProtocol.MESSAGES, messages))
    }
}
