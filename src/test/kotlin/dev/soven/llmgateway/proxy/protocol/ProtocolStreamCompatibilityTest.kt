package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolStreamCompatibilityTest {
    @Test
    fun `chat tool indexes are dense and stable across sparse upstream blocks`() {
        listOf(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES).forEach { source ->
            val converter = ProtocolConverterRegistry.get(GatewayProtocol.CHAT_COMPLETIONS, source).streamConverter("public")
            val input = if (source == GatewayProtocol.MESSAGES) listOf(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}""",
                """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"first","name":"lookup"}}""",
                """{"type":"content_block_start","index":5,"content_block":{"type":"tool_use","id":"second","name":"lookup"}}""",
                """{"type":"content_block_delta","index":5,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
                """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            ) else listOf(
                """{"type":"response.output_text.delta","output_index":0,"delta":"hello"}""",
                """{"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"first","name":"lookup","arguments":""}}""",
                """{"type":"response.output_item.added","output_index":5,"item":{"type":"function_call","call_id":"second","name":"lookup","arguments":""}}""",
                """{"type":"response.function_call_arguments.delta","output_index":5,"delta":"{}"}""",
                """{"type":"response.function_call_arguments.delta","output_index":2,"delta":"{}"}""",
            )
            val calls = events(feed(converter, input)).flatMap { event ->
                event.array("choices").flatMap { it.jsonObject["delta"]!!.jsonObject.array("tool_calls") }.map { it.jsonObject }
            }
            assertEquals(listOf(0, 1, 1, 0), calls.map { it.long("index")!!.toInt() }, source.value)
            assertEquals(listOf("first", "second"), calls.mapNotNull { it.string("id") })
            val independent = ProtocolConverterRegistry.get(GatewayProtocol.CHAT_COMPLETIONS, source).streamConverter("public")
            val first = events(feed(independent, input.take(2))).last().array("choices").single().jsonObject["delta"]!!.jsonObject.array("tool_calls").single().jsonObject
            assertEquals(0, first.long("index")!!.toInt())
        }
    }

    @Test
    fun `normal chat chunks with null error do not fail but real errors retain response identity`() {
        listOf(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES).forEach { client ->
            val converter = ProtocolConverterRegistry.get(client, GatewayProtocol.CHAT_COMPLETIONS).streamConverter("public")
            val normal = events(feed(converter, listOf("""{"id":"reply","error":null,"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}""")))
            assertTrue(normal.toString().contains("hello"))
            assertFalse(normal.any { it.string("type") in setOf("error", "response.failed") })
            val failed = events(feed(converter, listOf("""{"id":"reply","error":{"message":"denied"}}"""))).single()
            if (client == GatewayProtocol.RESPONSES) {
                assertEquals("response.failed", failed.string("type"))
                val response = failed["response"]!!.jsonObject
                assertEquals("reply", response.string("id"))
                assertEquals("failed", response.string("status"))
                assertEquals("server_error", response["error"]!!.jsonObject.string("code"))
                assertTrue(response["error"]!!.jsonObject.string("message")!!.contains("denied"))
                assertFalse(failed.containsKey("error"))
            } else {
                assertEquals("error", failed.string("type"))
                assertTrue(failed["error"]!!.jsonObject.string("message")!!.contains("denied"))
            }
            assertTrue(converter.finish().isEmpty())
        }
    }

    @Test
    fun `first frame errors expose a failed response with upstream id`() {
        val converter = ResponsesToChatCompletion.streamConverter("public")
        val failed = events(feed(converter, listOf("""{"id":"denied_id","error":{"message":"denied"}}"""))).single()
        assertEquals("denied_id", failed["response"]!!.jsonObject.string("id"))
        assertEquals(0, failed.long("sequence_number")!!.toInt())
    }

    @Test
    fun `full tool calls default missing or empty arguments without changing explicit JSON`() {
        listOf("", ",\"arguments\":\"\"", ",\"arguments\":\"{\\\"city\\\":\\\"SZ\\\"}\"").forEach { arguments ->
            val expected = if (arguments.contains("city")) "{\"city\":\"SZ\"}" else "{}"
            val responses = json("""{"model":"m","input":[{"type":"function_call","call_id":"call","name":"lookup"$arguments}]}""")
            val chat = ResponsesToChatCompletion.convertRequest(responses, "upstream")
            assertEquals(expected, chat.array("messages").single().jsonObject.array("tool_calls").single().jsonObject["function"]!!.jsonObject.string("arguments"))
            val chatRequest = json("""{"model":"m","messages":[{"role":"assistant","tool_calls":[{"id":"call","type":"function","function":{"name":"lookup"$arguments}}]}]}""")
            assertEquals(expected, ChatCompletionToResponses.convertRequest(chatRequest, "upstream").array("input").single().jsonObject.string("arguments"))
            val chatResponse = json("""{"choices":[{"message":{"tool_calls":[{"id":"call","function":{"name":"lookup"$arguments}}]},"finish_reason":"tool_calls"}]}""")
            assertEquals(expected, ResponsesToChatCompletion.convertResponse(chatResponse, "public").array("output").single().jsonObject.string("arguments"))
        }
    }

    @Test
    fun `responses lifecycle reconstructs text and parallel tools by stable item identity`() {
        listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES).forEach { source ->
            listOf(FinishReason.STOP, FinishReason.MAX_TOKENS, FinishReason.CONTENT_FILTER).forEach { reason ->
                val converter = ProtocolConverterRegistry.get(GatewayProtocol.RESPONSES, source).streamConverter("public")
                val output = events(feed(converter, lifecycleInput(source, reason)) + converter.finish())
                assertEquals(output.indices.toList(), output.map { it.long("sequence_number")!!.toInt() })
                val added = output.filter { it.string("type") == "response.output_item.added" }
                assertEquals(3, added.size)
                val indexes = added.associate { it["item"]!!.jsonObject.string("id")!! to it.long("output_index")!! }
                val accumulated = indexes.keys.associateWith { StringBuilder() }
                output.filter { it.string("type") in setOf("response.output_text.delta", "response.function_call_arguments.delta") }.forEach { event ->
                    val id = event.string("item_id")!!
                    assertEquals(indexes.getValue(id), event.long("output_index"))
                    accumulated.getValue(id).append(event.string("delta"))
                    assertTrue(output.indexOf(event) > output.indexOf(added.first { it["item"]!!.jsonObject.string("id") == id }))
                }
                val itemDone = output.filter { it.string("type") == "response.output_item.done" }
                assertEquals(3, itemDone.size)
                itemDone.forEach { done ->
                    val item = done["item"]!!.jsonObject
                    val id = item.string("id")!!
                    assertEquals(indexes.getValue(id), done.long("output_index"))
                    assertEquals(if (reason == FinishReason.STOP) "completed" else "incomplete", item.string("status"))
                    if (item.string("type") == "message") {
                        assertEquals("hello", accumulated.getValue(id).toString())
                        val textDone = output.single { it.string("type") == "response.output_text.done" && it.string("item_id") == id }
                        val partAdded = output.single { it.string("type") == "response.content_part.added" && it.string("item_id") == id }
                        val partDone = output.single { it.string("type") == "response.content_part.done" && it.string("item_id") == id }
                        assertEquals("hello", textDone.string("text"))
                        assertEquals(item.array("content").single(), partDone["part"])
                        assertTrue(output.indexOf(partAdded) < output.indexOf(textDone))
                        assertTrue(output.indexOf(textDone) < output.indexOf(partDone))
                        assertTrue(output.indexOf(partDone) < output.indexOf(done))
                    } else {
                        val argumentsDone = output.single { it.string("type") == "response.function_call_arguments.done" && it.string("item_id") == id }
                        assertEquals(accumulated.getValue(id).toString(), argumentsDone.string("arguments"))
                        assertEquals(argumentsDone["arguments"], item["arguments"])
                        assertTrue(output.indexOf(argumentsDone) < output.indexOf(done))
                    }
                    assertTrue(output.indexOf(done) < output.lastIndex)
                }
                val finalResponse = output.last()["response"]!!.jsonObject
                assertEquals(itemDone.map { it["item"] }, finalResponse.array("output").toList())
                assertEquals("reply", finalResponse.string("id"))
                assertTrue(converter.finish().isEmpty())
            }
        }
    }

    @Test
    fun `event byte limit rejects single multiline and multibyte payloads without leaking content`() {
        val payloads = listOf(
            listOf("x".repeat(MAX_PROTOCOL_EVENT_BYTES + 1)),
            listOf("x".repeat(MAX_PROTOCOL_EVENT_BYTES / 2), "x".repeat(MAX_PROTOCOL_EVENT_BYTES / 2)),
            listOf("界".repeat(MAX_PROTOCOL_EVENT_BYTES / 3 + 1)),
        )
        payloads.forEach { lines ->
            val converter = ResponsesToChatCompletion.streamConverter("public")
            val output = lines.flatMap { converter.feed("data: $it") }.joinToString()
            assertTrue(output.contains("上游流单个事件超过大小上限"))
            assertFalse(output.contains("xxx"))
            assertFalse(output.contains("界"))
            assertTrue(converter.finish().isEmpty())
            assertTrue(converter.feed("data: [DONE]").isEmpty())
        }
    }

    @Test
    fun `event byte limit resets after every frame and accepts the exact boundary`() {
        val converter = ResponsesToChatCompletion.streamConverter("public")
        val prefix = """{"id":"reply","choices":[{"delta":{"content":""""
        val suffix = """"},"finish_reason":null}]}"""
        val payload = prefix + "x".repeat(MAX_PROTOCOL_EVENT_BYTES - prefix.length - suffix.length) + suffix
        repeat(2) {
            val output = events(feed(converter, listOf(payload)))
            assertTrue(output.any { it.string("type") == "response.output_text.delta" })
            assertFalse(output.any { it.string("type") == "response.failed" })
        }
    }

    private fun lifecycleInput(source: GatewayProtocol, reason: FinishReason): List<String> = if (source == GatewayProtocol.CHAT_COMPLETIONS) listOf(
        """{"id":"reply","choices":[{"delta":{"content":"hello"},"finish_reason":null}]}""",
        """{"id":"reply","choices":[{"delta":{"tool_calls":[{"index":2,"id":"first","function":{"name":"lookup","arguments":"{"}},{"index":5,"id":"second","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":null}]}""",
        """{"id":"reply","choices":[{"delta":{"tool_calls":[{"index":2,"function":{"arguments":"\"city\":\"SZ\"}"}}]},"finish_reason":"${reason.chatValue}"}]}""",
        "[DONE]",
    ) else listOf(
        """{"type":"message_start","message":{"id":"reply"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}""",
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"first","name":"lookup"}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{"}}""",
        """{"type":"content_block_start","index":5,"content_block":{"type":"tool_use","id":"second","name":"lookup"}}""",
        """{"type":"content_block_delta","index":5,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"city\":\"SZ\"}"}}""",
        """{"type":"message_delta","delta":{"stop_reason":"${reason.messagesValue}"}}""",
        """{"type":"message_stop"}""",
    )

    private fun feed(converter: ProtocolStreamConverter, input: List<String>): List<String> =
        input.flatMap { converter.feed("data: $it") + converter.feed("") }

    private fun events(lines: List<String>): List<JsonObject> = lines.filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
