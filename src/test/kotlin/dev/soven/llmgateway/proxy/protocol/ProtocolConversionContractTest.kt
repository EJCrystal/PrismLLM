package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProtocolConversionContractTest {
    private val protocols = GatewayProtocol.entries
    private val requests = mapOf(
        GatewayProtocol.CHAT_COMPLETIONS to json("""{"model":"public","messages":[{"role":"user","content":"hello"}],"max_tokens":32}"""),
        GatewayProtocol.RESPONSES to json("""{"model":"public","input":"hello","max_output_tokens":32}"""),
        GatewayProtocol.MESSAGES to json("""{"model":"public","messages":[{"role":"user","content":"hello"}],"max_tokens":32}"""),
        GatewayProtocol.GEMINI to json("""{"contents":[{"role":"user","parts":[{"text":"hello"}]}],"generationConfig":{"maxOutputTokens":32}}"""),
    )
    private val responses = mapOf(
        GatewayProtocol.CHAT_COMPLETIONS to json("""{"id":"reply","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}"""),
        GatewayProtocol.RESPONSES to json("""{"id":"reply","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello"}]}],"usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}"""),
        GatewayProtocol.MESSAGES to json("""{"id":"reply","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":7,"output_tokens":3}}"""),
        GatewayProtocol.GEMINI to json("""{"responseId":"reply","candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3,"totalTokenCount":10}}"""),
    )
    private val streams = mapOf(
        GatewayProtocol.CHAT_COMPLETIONS to listOf(
            """{"id":"reply","choices":[{"delta":{"content":"hello"},"finish_reason":null}]}""",
            """{"id":"reply","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}""",
            "[DONE]",
        ),
        GatewayProtocol.RESPONSES to listOf(
            """{"type":"response.created","response":{"id":"reply"}}""",
            """{"type":"response.output_text.delta","delta":"hello"}""",
            """{"type":"response.completed","response":{"id":"reply","usage":{"input_tokens":7,"output_tokens":3}}}""",
        ),
        GatewayProtocol.MESSAGES to listOf(
            """{"type":"message_start","message":{"id":"reply","usage":{"input_tokens":7}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
            """{"type":"message_stop"}""",
        ),
        GatewayProtocol.GEMINI to listOf(
            """{"responseId":"reply","candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]}}]}""",
            """{"responseId":"reply","candidates":[{"content":{"role":"model","parts":[]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}""",
        ),
    )

    @Test
    fun `twelve directions preserve request text and rewrite the upstream model`() = eachDirection { inbound, outbound ->
        val converted = ProtocolConversion.convertRequest(inbound, outbound, requests.getValue(inbound), "upstream")
        if (outbound == GatewayProtocol.GEMINI) {
            assertFalse(converted.containsKey("model"))
            assertTrue(converted.toString().contains("hello"))
            assertTrue(converted.toString().contains("32"))
        } else {
            assertEquals("upstream", converted["model"]!!.jsonPrimitive.content)
            assertTrue(converted.toString().contains("hello"))
            assertTrue(converted.toString().contains("32"))
        }
    }

    @Test
    fun `responses requests prefer explicit user over metadata in both outbound protocols`() {
        val request = json("""{"model":"public","input":"hello","user":"explicit","metadata":{"user_id":"fallback"}}""")
        listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES).forEach { outbound ->
            val converted = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, outbound, request, "upstream")
            val user = if (outbound == GatewayProtocol.MESSAGES) converted["metadata"]!!.jsonObject["user_id"] else converted["user"]
            assertEquals("explicit", user!!.jsonPrimitive.content, outbound.value)
        }
    }

    @Test
    fun `responses requests fall back to metadata user only when explicit user is absent`() {
        val cases = listOf(
            json("""{"input":"hello","user":"explicit"}""") to "explicit",
            json("""{"input":"hello","metadata":{"user_id":"fallback"}}""") to "fallback",
            json("""{"input":"hello"}""") to null,
        )
        listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES).forEach { outbound ->
            cases.forEach { (request, expected) ->
                val converted = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, outbound, request, "upstream")
                val user = if (outbound == GatewayProtocol.MESSAGES) (converted["metadata"] as? JsonObject)?.get("user_id") else converted["user"]
                assertEquals(expected, user?.jsonPrimitive?.content, outbound.value)
            }
        }
    }

    @Test
    fun `twelve directions return the client protocol and usage`() = eachDirection { inbound, outbound ->
        val converted = ProtocolConversion.convertResponse(inbound, outbound, responses.getValue(outbound), "public")
        if (inbound == GatewayProtocol.GEMINI) {
            assertEquals("public", converted["modelVersion"]!!.jsonPrimitive.content)
            assertTrue(converted.toString().contains("hello"))
            val usage = converted["usageMetadata"]!!.jsonObject
            assertEquals("7", usage["promptTokenCount"]!!.jsonPrimitive.content)
            assertEquals("3", usage["candidatesTokenCount"]!!.jsonPrimitive.content)
        } else {
            assertEquals("public", converted["model"]!!.jsonPrimitive.content)
            assertTrue(converted.toString().contains("hello"))
            val usage = converted["usage"]!!.jsonObject
            val inputField = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) "prompt_tokens" else "input_tokens"
            val outputField = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) "completion_tokens" else "output_tokens"
            assertEquals("7", usage[inputField]!!.jsonPrimitive.content)
            assertEquals("3", usage[outputField]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `all six ordinary responses preserve safety stop reasons`() {
        val filteredResponses = mapOf(
            GatewayProtocol.CHAT_COMPLETIONS to json("""{"id":"reply","choices":[{"message":{"role":"assistant","content":"filtered"},"finish_reason":"content_filter"}]}"""),
            GatewayProtocol.RESPONSES to json("""{"id":"reply","status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"filtered"}]}]}"""),
            GatewayProtocol.MESSAGES to json("""{"id":"reply","stop_reason":"refusal","content":[{"type":"text","text":"filtered"}]}"""),
            GatewayProtocol.GEMINI to json("""{"responseId":"reply","candidates":[{"content":{"role":"model","parts":[{"text":"filtered"}]},"finishReason":"SAFETY"}]}"""),
        )
        eachDirection { inbound, outbound ->
            val converted = ProtocolConversion.convertResponse(inbound, outbound, filteredResponses.getValue(outbound), "public")
            when (inbound) {
                GatewayProtocol.MESSAGES -> assertEquals("refusal", converted.string("stop_reason"))
                GatewayProtocol.CHAT_COMPLETIONS -> assertEquals("content_filter", converted.array("choices").single().jsonObject.string("finish_reason"))
                GatewayProtocol.RESPONSES -> {
                    assertEquals("incomplete", converted.string("status"))
                    assertEquals("content_filter", converted["incomplete_details"]!!.jsonObject.string("reason"))
                }
                GatewayProtocol.GEMINI -> assertEquals("SAFETY", converted.array("candidates").single().jsonObject.string("finishReason"))
            }
        }
    }

    @Test
    fun `all six streams preserve safety stop reasons`() {
        val filteredStreams = mapOf(
            GatewayProtocol.CHAT_COMPLETIONS to listOf(
                """{"id":"reply","choices":[{"delta":{"content":"filtered"},"finish_reason":"content_filter"}]}""",
                "[DONE]",
            ),
            GatewayProtocol.RESPONSES to listOf(
                """{"type":"response.output_text.delta","delta":"filtered"}""",
                """{"type":"response.incomplete","response":{"id":"reply","incomplete_details":{"reason":"content_filter"}}}""",
            ),
            GatewayProtocol.MESSAGES to listOf(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"filtered"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"refusal"}}""",
                """{"type":"message_stop"}""",
            ),
            GatewayProtocol.GEMINI to listOf(
                """{"responseId":"reply","candidates":[{"content":{"role":"model","parts":[{"text":"filtered"}]}}]}""",
                """{"responseId":"reply","candidates":[{"content":{"role":"model","parts":[]},"finishReason":"SAFETY"}]}""",
            ),
        )
        eachDirection { inbound, outbound ->
            val converter = ProtocolConverterRegistry.get(inbound, outbound).streamConverter("public")
            val output = (filteredStreams.getValue(outbound).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()).joinToString("\n")
            val expected = when (inbound) {
                GatewayProtocol.MESSAGES -> "\"stop_reason\":\"refusal\""
                GatewayProtocol.CHAT_COMPLETIONS -> "\"finish_reason\":\"content_filter\""
                GatewayProtocol.RESPONSES -> "\"reason\":\"content_filter\""
                GatewayProtocol.GEMINI -> "\"finishReason\":\"SAFETY\""
            }
            assertTrue(output.contains(expected), output)
            assertFalse(output.contains("\"stop_reason\":\"end_turn\""), output)
            assertFalse(output.contains("response.completed"), output)
        }
    }

    @Test
    fun `six streams flush a final frame without a trailing separator exactly once`() = eachDirection { inbound, outbound ->
        val converter = ProtocolConversion.streamConverter(inbound, outbound, "public")!!
        val output = buildList {
            streams.getValue(outbound).forEachIndexed { index, payload ->
                addAll(converter.feed("data: $payload"))
                if (index < streams.getValue(outbound).lastIndex) addAll(converter.feed(""))
            }
            addAll(converter.finish())
        }.joinToString("\n")
        assertTrue(output.contains("hello"), output)
        assertTrue(output.contains(terminal(inbound)), output)
        if (inbound != GatewayProtocol.GEMINI) {
            val inputField = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) "prompt_tokens" else "input_tokens"
            assertTrue(output.contains("\"$inputField\":7"), output)
        }
        assertTrue(converter.finish().isEmpty())
        assertTrue(converter.feed("data: [DONE]").isEmpty())
    }

    @Test
    fun `multiline SSE data and ignored control lines preserve one event`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, "public")!!
        assertTrue(converter.feed(": keepalive").isEmpty())
        assertTrue(converter.feed("event: content_block_delta").isEmpty())
        assertTrue(converter.feed("data: {\"type\":\"content_block_delta\",").isEmpty())
        assertTrue(converter.feed("data: \"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}").isEmpty())
        assertTrue(converter.feed("").joinToString().contains("hello"))
    }

    @Test
    fun `stream factories isolate interleaved attempts`() = eachDirection { inbound, outbound ->
        val first = ProtocolConversion.streamConverter(inbound, outbound, "first")!!
        val second = ProtocolConversion.streamConverter(inbound, outbound, "second")!!
        assertNotSame(first, second)
        val firstOutput = mutableListOf<String>()
        val secondOutput = mutableListOf<String>()
        streams.getValue(outbound).forEach { payload ->
            firstOutput += first.feed("data: $payload") + first.feed("")
            secondOutput += second.feed("data: $payload") + second.feed("")
        }
        assertTrue(firstOutput.joinToString().contains("first"))
        assertFalse(firstOutput.joinToString().contains("second"))
        assertTrue(secondOutput.joinToString().contains("second"))
    }

    @Test
    fun `upstream errors terminate each client stream without success`() = eachDirection { inbound, outbound ->
        val converter = ProtocolConversion.streamConverter(inbound, outbound, "public")!!
        val output = (converter.feed("""data: {"type":"error","error":{"message":"denied"}}""") + converter.feed("")).joinToString("\n")
        assertTrue(output.contains("denied"), output)
        assertFalse(output.contains("response.completed"), output)
        assertTrue(converter.finish().isEmpty())
    }

    @Test
    fun `unfinished streams do not synthesize a successful terminal event`() = eachDirection { inbound, outbound ->
        val converter = ProtocolConversion.streamConverter(inbound, outbound, "public")!!
        val output = streams.getValue(outbound).take(1).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
        assertFalse(output.joinToString().contains(terminal(inbound)))
        assertTrue(converter.finish().isEmpty())
    }

    @Test
    fun `registry resolves twelve explicit request directions with reversed response conversion`() {
        val expected = setOf(
            MessagesToChatCompletion, ChatCompletionToMessages,
            ResponsesToChatCompletion, ChatCompletionToResponses,
            MessagesToResponses, ResponsesToMessages,
            ChatCompletionToGemini, GeminiToChatCompletion,
            MessagesToGemini, GeminiToMessages,
            ResponsesToGemini, GeminiToResponses,
        )
        val found = mutableSetOf<ProtocolConverter>()
        eachDirection { inbound, outbound ->
            val converter = ProtocolConverterRegistry.get(inbound, outbound)
            found += converter
            assertEquals(inbound, converter.inbound)
            assertEquals(outbound, converter.outbound)
            assertEquals(
                ProtocolConversion.convertRequest(inbound, outbound, requests.getValue(inbound), "upstream"),
                converter.convertRequest(requests.getValue(inbound), "upstream"),
            )
            val converted = converter.convertResponse(responses.getValue(outbound), "public")
            if (inbound == GatewayProtocol.GEMINI) {
                assertEquals("public", converted["modelVersion"]!!.jsonPrimitive.content)
            } else {
                assertEquals("public", converted["model"]!!.jsonPrimitive.content)
            }
            assertNotSame(converter.streamConverter("public"), converter.streamConverter("public"))
        }
        assertEquals(expected, found)
    }

    @Test
    fun `native protocols bypass conversion including unsupported extensions`() {
        GatewayProtocol.entries.forEach { protocol ->
            val request = json("""{"model":"public","custom":{"enabled":true}}""")
            assertEquals(json("""{"model":"upstream","custom":{"enabled":true}}"""), ProtocolConversion.convertRequest(protocol, protocol, request, "upstream"))
            assertSame(request, ProtocolConversion.convertResponse(protocol, protocol, request, "public"))
            assertNull(ProtocolConversion.streamConverter(protocol, protocol, "public"))
        }
    }

    @Test
    fun `Gemini rejects unsupported stateful and multimodal fields`() {
        assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(
                GatewayProtocol.GEMINI,
                GatewayProtocol.CHAT_COMPLETIONS,
                json("""{"contents":[],"cachedContent":"cachedContents/1"}"""),
                "upstream",
            )
        }
        assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(
                GatewayProtocol.GEMINI,
                GatewayProtocol.CHAT_COMPLETIONS,
                json("""{"contents":[],"safetySettings":[{"category":"HARM_CATEGORY_HATE_SPEECH","threshold":"BLOCK_ALL"}]}"""),
                "upstream",
            )
        }
    }

    private fun eachDirection(test: (GatewayProtocol, GatewayProtocol) -> Unit) {
        protocols.forEach { inbound -> protocols.filter { it != inbound }.forEach { outbound -> test(inbound, outbound) } }
    }

    private fun terminal(protocol: GatewayProtocol): String = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> "[DONE]"
        GatewayProtocol.RESPONSES -> "response.completed"
        GatewayProtocol.MESSAGES -> "message_stop"
        GatewayProtocol.GEMINI -> "\"finishReason\":"
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
