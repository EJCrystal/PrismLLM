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
    private val protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES)
    private val requests = mapOf(
        GatewayProtocol.CHAT_COMPLETIONS to json("""{"model":"public","messages":[{"role":"user","content":"hello"}],"max_tokens":32}"""),
        GatewayProtocol.RESPONSES to json("""{"model":"public","input":"hello","max_output_tokens":32}"""),
        GatewayProtocol.MESSAGES to json("""{"model":"public","messages":[{"role":"user","content":"hello"}],"max_tokens":32}"""),
    )
    private val responses = mapOf(
        GatewayProtocol.CHAT_COMPLETIONS to json("""{"id":"reply","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}"""),
        GatewayProtocol.RESPONSES to json("""{"id":"reply","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hello"}]}],"usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}"""),
        GatewayProtocol.MESSAGES to json("""{"id":"reply","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":7,"output_tokens":3}}"""),
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
    )

    @Test
    fun `six directions preserve request text and rewrite the upstream model`() = eachDirection { inbound, outbound ->
        val converted = ProtocolConversion.convertRequest(inbound, outbound, requests.getValue(inbound), "upstream")
        assertEquals("upstream", converted["model"]!!.jsonPrimitive.content)
        assertTrue(converted.toString().contains("hello"))
        assertTrue(converted.toString().contains("32"))
    }

    @Test
    fun `six directions return the client protocol and usage`() = eachDirection { inbound, outbound ->
        val converted = ProtocolConversion.convertResponse(inbound, outbound, responses.getValue(outbound), "public")
        assertEquals("public", converted["model"]!!.jsonPrimitive.content)
        assertTrue(converted.toString().contains("hello"))
        val usage = converted["usage"]!!.jsonObject
        val inputField = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) "prompt_tokens" else "input_tokens"
        val outputField = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) "completion_tokens" else "output_tokens"
        assertEquals("7", usage[inputField]!!.jsonPrimitive.content)
        assertEquals("3", usage[outputField]!!.jsonPrimitive.content)
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
        val inputField = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) "prompt_tokens" else "input_tokens"
        assertTrue(output.contains("\"$inputField\":7"), output)
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
    fun `registry resolves six explicit request directions with reversed response conversion`() {
        val expected = setOf(
            MessagesToChatCompletion, ChatCompletionToMessages,
            ResponsesToChatCompletion, ChatCompletionToResponses,
            MessagesToResponses, ResponsesToMessages,
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
            assertEquals("public", converter.convertResponse(responses.getValue(outbound), "public")["model"]!!.jsonPrimitive.content)
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
    fun `Gemini remains unavailable for cross protocol conversion`() {
        protocols.forEach { protocol ->
            assertFailsWith<ProtocolConversionException> {
                ProtocolConversion.convertRequest(protocol, GatewayProtocol.GEMINI, requests.getValue(protocol), "upstream")
            }
            assertFailsWith<ProtocolConversionException> {
                ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, protocol, json("{}"), "upstream")
            }
        }
    }

    private fun eachDirection(test: (GatewayProtocol, GatewayProtocol) -> Unit) {
        protocols.forEach { inbound -> protocols.filter { it != inbound }.forEach { outbound -> test(inbound, outbound) } }
    }

    private fun terminal(protocol: GatewayProtocol): String = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> "[DONE]"
        GatewayProtocol.RESPONSES -> "response.completed"
        GatewayProtocol.MESSAGES -> "message_stop"
        GatewayProtocol.GEMINI -> error("No Gemini conversion")
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
