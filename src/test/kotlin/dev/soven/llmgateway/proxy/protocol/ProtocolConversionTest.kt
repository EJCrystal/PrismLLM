package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolConversionTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `responses refusal blocks are preserved as chat message refusal`() {
        val response = json("""{
            "id":"resp_refusal",
            "output":[{"type":"message","content":[
                {"type":"output_text","text":"context"},
                {"type":"refusal","refusal":"cannot help"},
                {"type":"refusal","refusal":"with that"}
            ]}]
        }""")
        val chat = ChatCompletionToResponses.convertResponse(response, "public")
        val message = chat.array("choices").single().jsonObject["message"]!!.jsonObject
        assertEquals("context", message.string("content"))
        assertEquals("cannot help\nwith that", message.string("refusal"))
    }

    @Test
    fun `chat refusal is preserved as responses refusal content`() {
        val chat = json("""{
            "id":"chat_refusal",
            "choices":[{"message":{"role":"assistant","content":null,"refusal":"cannot help"},"finish_reason":"stop"}]
        }""")
        val responses = ResponsesToChatCompletion.convertResponse(chat, "public")
        val message = responses.array("output").single().jsonObject
        assertEquals("message", message.string("type"))
        assertEquals("refusal", message.array("content").single().jsonObject.string("type"))
        assertEquals("cannot help", message.array("content").single().jsonObject.string("refusal"))
        assertEquals("completed", responses.string("status"))
    }

    @Test
    fun `streaming responses refusal reaches chat without changing completed status`() {
        val converter = EventStreamConverter(ResponsesStreamDecoder(), ChatStreamEncoder("public"))
        val frames = converter.feed("data: {\"type\":\"response.refusal.delta\",\"response\":{\"id\":\"resp_refusal\"},\"delta\":\"cannot help\"}") +
            converter.feed("") + converter.feed("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_refusal\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"cannot help\"}]}]}}") + converter.feed("")
        val body = frames.joinToString("\n")
        assertTrue(body.contains("\"refusal\":\"cannot help\""), body)
        assertTrue(body.contains("\"finish_reason\":\"stop\""), body)
    }

    @Test
    fun `streaming chat refusal becomes responses refusal event`() {
        val converter = EventStreamConverter(ChatStreamDecoder(), ResponsesStreamEncoder("public"))
        val frames = converter.feed("data: {\"id\":\"chat_refusal\",\"choices\":[{\"delta\":{\"refusal\":\"cannot help\"},\"finish_reason\":null}]}") +
            converter.feed("") + converter.feed("data: {\"id\":\"chat_refusal\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}") +
            converter.feed("") + converter.feed("data: [DONE]") + converter.feed("")
        val body = frames.joinToString("\n")
        assertTrue(body.contains("event: response.refusal.delta"), body)
        assertTrue(body.contains("\"delta\":\"cannot help\""), body)
        assertTrue(body.contains("response.completed"), body)
        assertFalse(body.contains("response.incomplete"), body)
    }

    @Test
    fun `chat refusal and finish reason in one event are emitted once`() {
        val converter = EventStreamConverter(ChatStreamDecoder(), ResponsesStreamEncoder("public"))
        val frames = converter.feed("data: {\"id\":\"chat_refusal\",\"choices\":[{\"delta\":{\"refusal\":\"cannot help\"},\"finish_reason\":\"stop\"}]}") +
            converter.feed("") + converter.feed("data: [DONE]") + converter.feed("")
        assertEquals(1, frames.count { it.contains("event: response.refusal.delta") })
        assertEquals(1, frames.count { it.contains("\"delta\":\"cannot help\"") })
        assertTrue(frames.any { it.contains("response.completed") })
    }

    @Test
    fun `responses completed refusal output is emitted before a normal chat terminal chunk`() {
        val converter = EventStreamConverter(ResponsesStreamDecoder(), ChatStreamEncoder("public"))
        val frames = converter.feed("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_refusal\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"cannot help\"}]}]}}") + converter.feed("")
        val body = frames.joinToString("\n")
        assertTrue(body.contains("\"refusal\":\"cannot help\""), body)
        assertTrue(body.contains("\"finish_reason\":\"stop\""), body)
    }

    @Test
    fun `Gemini stream preserves refusal text and safety from chat and responses`() {
        val upstreamEvents = mapOf(
            GatewayProtocol.CHAT_COMPLETIONS to listOf(
                """{"id":"r","choices":[{"delta":{"refusal":"cannot help"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
            GatewayProtocol.RESPONSES to listOf(
                """{"type":"response.completed","response":{"id":"r","status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"cannot help"}]}]}}""",
            ),
        )
        upstreamEvents.forEach { (protocol, events) ->
            val converter = ProtocolConversion.streamConverter(GatewayProtocol.GEMINI, protocol, "public")!!
            val frames = events.flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
            val body = frames.joinToString("\n")
            assertTrue(body.contains("\"text\":\"cannot help\""), "$protocol: $body")
            assertTrue(body.contains("\"finishReason\":\"SAFETY\""), "$protocol: $body")
        }
    }

    @Test
    fun `unknown responses incomplete reasons never become successful stop`() {
        for (reason in listOf(null, "unknown_reason", "max_output_tokens")) {
            for (withTool in listOf(false, true)) {
                val decoder = ResponsesStreamDecoder()
                if (withTool) {
                    decoder.decode(json("""{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"lookup"}}"""))
                }
                val details = reason?.let { ",\"incomplete_details\":{\"reason\":\"$it\"}" }.orEmpty()
                val event = decoder.decode(json("""{"type":"response.incomplete","response":{"id":"resp_1","status":"incomplete"$details}}""")).single()
                assertEquals(FinishReason.MAX_TOKENS, event.finishReason)
                assertTrue(event.done)
            }
        }
        val filtered = ResponsesStreamDecoder().decode(json("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"content_filter"}}}""")).single()
        assertEquals(FinishReason.CONTENT_FILTER, filtered.finishReason)
    }

    @Test
    fun `chat request converts to messages with tools and system`() {
        val request = json(
            """{
              "model":"public","stream":true,"max_tokens":100,
              "messages":[
                {"role":"system","content":"be concise"},
                {"role":"user","content":"weather?"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\"city\":\"SZ\"}"}}]},
                {"role":"tool","tool_call_id":"call_1","content":"sunny"}
              ],
              "tools":[{"type":"function","function":{"name":"weather","description":"weather","parameters":{"type":"object"}}}],
              "tool_choice":"auto"
            }""",
        )
        val converted = ProtocolConversion.convertRequest(
            GatewayProtocol.CHAT_COMPLETIONS,
            GatewayProtocol.MESSAGES,
            request,
            "claude-upstream",
        )
        assertEquals("claude-upstream", converted["model"]!!.jsonPrimitive.content)
        assertEquals("be concise", converted["system"]!!.jsonPrimitive.content)
        assertEquals(100L, converted["max_tokens"]!!.jsonPrimitive.content.toLong())
        val messages = converted["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        val assistantBlocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, assistantBlocks.size)
        assertEquals("tool_use", assistantBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_result", messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("weather", converted["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `messages request converts to chat`() {
        val request = json(
            """{
              "model":"claude","max_tokens":64,"system":"sys",
              "messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}],
              "tools":[{"name":"lookup","description":"lookup","input_schema":{"type":"object"}}],
              "tool_choice":{"type":"any"}
            }""",
        )
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, request, "gpt-upstream")
        assertEquals("gpt-upstream", converted["model"]!!.jsonPrimitive.content)
        assertEquals("sys", converted["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("function", converted["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("required", converted["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses request converts to chat and rejects stateful continuation`() {
        val request = json(
            """{
              "model":"gpt","instructions":"sys","max_output_tokens":32,
              "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],
              "tools":[{"type":"function","name":"lookup","description":"lookup","parameters":{"type":"object"}}]
            }""",
        )
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, request, "gpt-upstream")
        assertEquals("gpt-upstream", converted["model"]!!.jsonPrimitive.content)
        assertEquals(32L, converted["max_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals("lookup", converted["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val stateful = json("""{"model":"gpt","previous_response_id":"resp_1","input":"hi"}""")
        val error = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, stateful, "gpt")
        }
        assertEquals("previous_response_id", error.field)
    }

    @Test
    fun `chat request converts to responses`() {
        val request = json(
            """{"model":"public","messages":[{"role":"system","content":"sys"},{"role":"user","content":"hi"}],"max_tokens":20}""",
        )
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, request, "gpt-upstream")
        assertEquals("gpt-upstream", converted["model"]!!.jsonPrimitive.content)
        assertEquals("sys", converted["instructions"]!!.jsonPrimitive.content)
        assertEquals(20L, converted["max_output_tokens"]!!.jsonPrimitive.content.toLong())
        assertEquals("input_text", converted["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `messages and responses convert directly`() {
        val messages = json("""{"model":"claude","max_tokens":16,"messages":[{"role":"user","content":"hello"}]}""")
        val responses = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES, messages, "gpt")
        assertEquals("gpt", responses["model"]!!.jsonPrimitive.content)
        assertEquals("hello", responses["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val back = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, responses, "claude")
        assertEquals("claude", back["model"]!!.jsonPrimitive.content)
        assertEquals("hello", back["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non streaming responses convert in all directions`() {
        val messages = json(
            """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}""",
        )
        val chat = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, messages, "public")
        assertEquals("ok", chat["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("3", chat["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.content)

        val responses = ProtocolConversion.convertResponse(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, chat, "public")
        assertEquals("ok", responses["output"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val backToMessages = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES, responses, "public")
        assertEquals("ok", backToMessages["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streaming chat converts to messages events`() {
        val converter = ProtocolConversion.streamConverter(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.CHAT_COMPLETIONS,
            "public",
        )!!
        val first = converter.feed("data: {\"id\":\"chat1\",\"model\":\"gpt\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hel\"},\"finish_reason\":null}]}") +
            converter.feed("")
        assertTrue(first.any { it.contains("message_start") })
        assertTrue(first.any { it.contains("text_delta") })
        val last = converter.feed("data: {\"id\":\"chat1\",\"model\":\"gpt\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}") +
            converter.feed("") + converter.feed("data: [DONE]") + converter.feed("")
        assertTrue(last.any { it.contains("message_delta") })
        assertTrue(last.any { it.contains("message_stop") })
    }


    @Test
    fun `streaming messages converts to chat and responses`() {
        fun feed(converter: ProtocolStreamConverter): String = buildString {
            append(converter.feed("""data: {"type":"message_start","message":{"id":"msg_1","model":"claude","usage":{"input_tokens":3}}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"message_stop"}""") + converter.feed(""))
        }
        val chat = feed(ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, "public")!!)
        assertTrue(chat.contains("chat.completion.chunk"))
        assertTrue(chat.contains("Hi"))
        assertTrue(chat.contains("[DONE]"))
        val responses = feed(ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, "public")!!)
        assertTrue(responses.contains("response.created"))
        assertTrue(responses.contains("response.output_text.delta"))
        assertTrue(responses.contains("response.completed"))
    }

    @Test
    fun `streaming responses converts to chat and messages`() {
        fun feed(converter: ProtocolStreamConverter): String = buildString {
            append(converter.feed("""data: {"type":"response.created","response":{"id":"resp_1","model":"gpt"}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"response.output_text.delta","delta":"Hi"}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","usage":{"input_tokens":3,"output_tokens":2}}}""") + converter.feed(""))
        }
        val chat = feed(ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, "public")!!)
        assertTrue(chat.contains("chat.completion.chunk"))
        assertTrue(chat.contains("Hi"))
        val messages = feed(ProtocolConversion.streamConverter(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES, "public")!!)
        assertTrue(messages.contains("message_start"))
        assertTrue(messages.contains("text_delta"))
        assertTrue(messages.contains("message_stop"))
    }

    @Test
    fun `interleaved responses text and tool events keep first-seen output order`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.RESPONSES, "m")
        assertEquals(null, converter, "同协议不转换")

        val chat = ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, "m")!!
        val output = buildString {
            append(chat.feed("""data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"call_1","name":"f","arguments":""}}""") + chat.feed(""))
            append(chat.feed("""data: {"type":"response.output_text.delta","output_index":0,"delta":"Hi"}""") + chat.feed(""))
            append(chat.feed("""data: {"type":"response.completed","response":{"id":"resp_1","status":"completed"}}""") + chat.feed(""))
        }
        val toolIndex = output.indexOf("tool_calls")
        val contentIndex = output.indexOf("\"content\":\"Hi\"")
        assertTrue(toolIndex >= 0 && contentIndex >= 0, output)
        assertTrue(toolIndex < contentIndex, output)
        assertTrue(output.contains("\"finish_reason\":\"tool_calls\""), output)
    }

    @Test
    fun `streaming chat converts to responses events`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, "public")!!
        val first = converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}""") + converter.feed("")
        assertTrue(first.any { it.contains("response.created") })
        assertTrue(first.any { it.contains("response.output_text.delta") })
        val last = converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""") +
            converter.feed("") + converter.feed("data: [DONE]") + converter.feed("")
        assertTrue(last.any { it.contains("response.completed") })
    }

    @Test
    fun `streaming chat length finishes responses as incomplete`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, "public")!!
        converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}""")
        converter.feed("")
        val last = converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""") +
            converter.feed("") + converter.feed("data: [DONE]") + converter.feed("")
        assertTrue(last.any { it.contains("response.incomplete") }, last.toString())
        assertTrue(last.any { it.contains("max_output_tokens") }, last.toString())
    }

    @Test
    fun `streaming chat content filter finishes responses as incomplete`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, "public")!!
        converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}""")
        converter.feed("")
        val last = converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}""") +
            converter.feed("") + converter.feed("data: [DONE]") + converter.feed("")
        assertTrue(last.any { it.contains("response.incomplete") }, last.toString())
        assertTrue(last.any { it.contains("content_filter") }, last.toString())
    }

    @Test
    fun `malformed upstream sse frame emits protocol error`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, "public")!!
        val output = converter.feed("data: {not-json") + converter.feed("")
        assertTrue(output.any { it.contains("data: {\"error\"") }, output.toString())
        assertTrue(output.any { it.contains("无法解析的 JSON 帧") }, output.toString())
    }

    @Test
    fun `unsupported multimodal content is rejected`() {
        val request = json(
            """{"model":"x","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}]}""",
        )
        val error = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, request, "claude")
        }
        assertTrue(error.field.contains("content"))
    }

    @Test
    fun `unknown request fields are rejected instead of silently dropped`() {
        val chat = json("""{"model":"m","messages":[{"role":"user","content":"hi"}],"audio":{"format":"wav"}}""")
        val chatError = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, chat, "m")
        }
        assertEquals("request.audio", chatError.field)

        val messages = json("""{"model":"m","max_tokens":8,"messages":[{"role":"user","content":"hi"}],"mcp_servers":[]}""")
        val messagesError = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, messages, "m")
        }
        assertEquals("request.mcp_servers", messagesError.field)
    }

    @Test
    fun `structured output schema keeps its protocol-specific shape`() {
        val chat = json(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"response_format":{"type":"json_schema","json_schema":{"name":"answer","schema":{"type":"object"},"strict":true}}}""",
        )
        val responses = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, chat, "m")
        val format = responses["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("answer", format["name"]!!.jsonPrimitive.content)
        assertEquals("object", format["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)

        val back = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, responses, "m")
        val chatFormat = back["response_format"]!!.jsonObject
        assertEquals("json_schema", chatFormat["type"]!!.jsonPrimitive.content)
        assertEquals("answer", chatFormat["json_schema"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat streaming usage in trailing chunk reaches messages and responses`() {
        fun feed(converter: ProtocolStreamConverter): String = buildString {
            append(converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"},"finish_reason":null}]}""") + converter.feed(""))
            append(converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""") + converter.feed(""))
            append(converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}""") + converter.feed(""))
            append(converter.feed("data: [DONE]") + converter.feed(""))
        }
        val messages = feed(ProtocolConversion.streamConverter(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, "m")!!)
        assertTrue(messages.contains("message_delta"))
        assertTrue(messages.contains("\"input_tokens\":3"), messages)
        assertTrue(messages.contains("message_stop"))
        val responses = feed(ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, "m")!!)
        assertTrue(responses.contains("response.completed"))
        assertTrue(responses.contains("\"input_tokens\":3"), responses)
    }

    @Test
    fun `common no-op responses fields convert while stateful options are rejected`() {
        val compatible = json(
            """{"model":"m","input":"hi","store":false,"include":[],"truncation":"disabled","metadata":{"user_id":"u1"}}""",
        )
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, compatible, "m")
        assertEquals("u1", chat["user"]!!.jsonPrimitive.content)

        for ((body, field) in listOf(
            """{"model":"m","input":"hi","store":true}""" to "store",
            """{"model":"m","input":"hi","include":["reasoning.encrypted_content"]}""" to "include",
            """{"model":"m","input":"hi","truncation":"auto"}""" to "truncation",
            """{"model":"m","input":"hi","metadata":{"trace":"x"}}""" to "metadata.trace",
        )) {
            val error = assertFailsWith<ProtocolConversionException> {
                ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, json(body), "m")
            }
            assertEquals(field, error.field)
        }
    }

    @Test
    fun `responses cached tokens are not added twice`() {
        val response = json(
            """{"id":"resp_1","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":4,"cache_write_tokens":2},"output_tokens":3,"total_tokens":13}}""",
        )
        val chat = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, response, "m")
        val usage = chat["usage"]!!.jsonObject
        assertEquals("10", usage["prompt_tokens"]!!.jsonPrimitive.content)
        assertEquals("13", usage["total_tokens"]!!.jsonPrimitive.content)
        assertEquals("4", usage["prompt_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.content)
        assertEquals("2", usage["prompt_tokens_details"]!!.jsonObject["cache_write_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streaming messages usage is normalized for chat and responses`() {
        fun feed(converter: ProtocolStreamConverter): String = buildString {
            append(converter.feed("""data: {"type":"message_start","message":{"id":"msg_1","model":"claude","usage":{"input_tokens":3,"cache_read_input_tokens":1,"cache_creation_input_tokens":2}}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"message_stop"}""") + converter.feed(""))
        }
        val chat = feed(ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, "m")!!)
        assertTrue(chat.contains("\"prompt_tokens\":6"), chat)
        assertTrue(chat.contains("\"cached_tokens\":1"), chat)
        assertTrue(chat.contains("\"cache_write_tokens\":2"), chat)
        assertTrue(chat.contains("\"completion_tokens\":2"), chat)

        val responses = feed(ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, "m")!!)
        assertTrue(responses.contains("\"input_tokens\":6"), responses)
        assertTrue(responses.contains("\"cached_tokens\":1"), responses)
        assertTrue(responses.contains("\"cache_write_tokens\":2"), responses)
        assertTrue(responses.contains("\"output_tokens\":2"), responses)
    }

    @Test
    fun `streaming responses incomplete emits terminal events`() {
        fun feed(converter: ProtocolStreamConverter): String = buildString {
            append(converter.feed("""data: {"type":"response.created","response":{"id":"resp_1","model":"gpt"}}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"response.output_text.delta","delta":"Hi"}""") + converter.feed(""))
            append(converter.feed("""data: {"type":"response.incomplete","response":{"id":"resp_1","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":3,"output_tokens":2}}}""") + converter.feed(""))
        }
        val chat = feed(ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, "m")!!)
        assertTrue(chat.contains("\"finish_reason\":\"length\""), chat)
        assertTrue(chat.contains("[DONE]"), chat)
        val messages = feed(ProtocolConversion.streamConverter(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES, "m")!!)
        assertTrue(messages.contains("\"stop_reason\":\"max_tokens\""), messages)
        assertTrue(messages.contains("message_stop"), messages)
    }

    @Test
    fun `chat streaming text and tools use distinct responses output indexes`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, "m")!!
        val output = buildString {
            append(converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\"city\":\"SZ\"}"}}]},"finish_reason":null}]}""") + converter.feed(""))
            append(converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""") + converter.feed(""))
            append(converter.feed("data: [DONE]") + converter.feed(""))
        }
        assertTrue(output.contains("\"type\":\"response.output_text.delta\""), output)
        assertTrue(output.contains("\"output_index\":0"), output)
        assertTrue(output.contains("\"type\":\"response.output_item.added\""), output)
        assertTrue(output.contains("\"output_index\":1"), output)
        assertTrue(output.contains("\"type\":\"response.function_call_arguments.delta\""), output)
    }

    @Test
    fun `chat consecutive tool messages become one anthropic user message`() {
        val request = json(
            """{"model":"m","messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{}"}},{"id":"b","type":"function","function":{"name":"g","arguments":"{}"}}]},{"role":"tool","tool_call_id":"a","content":"A"},{"role":"tool","tool_call_id":"b","content":"B"}]}""",
        )
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, request, "m")
        val messages = converted["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val user = messages[1].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val blocks = user["content"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("tool_result", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_result", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic tool result precedes user text when converting to chat`() {
        val request = json(
            """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"sunny"},{"type":"text","text":"thanks"}]}]}""",
        )
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, request, "m")
        val messages = converted["messages"]!!.jsonArray
        assertEquals("tool", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic tool result array content converts to chat text`() {
        val request = json(
            """{"model":"m","max_tokens":8,"messages":[{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":[{"type":"text","text":"sunny"},{"type":"text","text":"25C"}]}]}]}""",
        )
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, request, "m")
        val tool = converted["messages"]!!.jsonArray[0].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("sunny\n25C", tool["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat string stop converts to anthropic stop sequences`() {
        val request = json("""{"model":"m","messages":[{"role":"user","content":"hi"}],"stop":"END"}""")
        val converted = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, request, "m")
        assertEquals("END", converted["stop_sequences"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `chat image content is rejected when converting to responses`() {
        val request = json(
            """{"model":"m","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}]}""",
        )
        val error = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, request, "m")
        }
        assertTrue(error.field.contains("content"))
    }

    @Test
    fun `thinking and reasoning outputs map to chat reasoning content`() {
        val messages = json(
            """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}""",
        )
        val chatFromMessages = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, messages, "m")
        val message = chatFromMessages["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("plan", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("ok", message["content"]!!.jsonPrimitive.content)

        val responses = json(
            """{"id":"resp_1","status":"completed","output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"plan"}]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":3,"output_tokens":2}}""",
        )
        val chatFromResponses = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, responses, "m")
        val messageFromResponses = chatFromResponses["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("plan", messageFromResponses["reasoning_content"]!!.jsonPrimitive.content)

        val chat = json(
            """{"id":"chat_1","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"plan","content":"ok"},"finish_reason":"stop"}]}""",
        )
        val responsesBack = ProtocolConversion.convertResponse(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, chat, "m")
        assertEquals("reasoning", responsesBack["output"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream done without finish reason still emits terminal events`() {
        fun feed(converter: ProtocolStreamConverter): String = buildString {
            append(converter.feed("""data: {"id":"chat_1","model":"gpt","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""") + converter.feed(""))
            append(converter.feed("data: [DONE]") + converter.feed(""))
        }
        val messages = feed(ProtocolConversion.streamConverter(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, "m")!!)
        assertTrue(messages.contains("message_delta"), messages)
        assertTrue(messages.contains("message_stop"), messages)
        val responses = feed(ProtocolConversion.streamConverter(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, "m")!!)
        assertTrue(responses.contains("response.completed"), responses)
    }

    @Test
    fun `function tool strict flag survives responses chat conversion`() {
        val responses = json(
            """{"model":"m","input":"hi","tools":[{"type":"function","name":"lookup","parameters":{"type":"object"},"strict":true}]}""",
        )
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, responses, "m")
        assertEquals("true", chat["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["strict"]!!.jsonPrimitive.content)
        val back = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, chat, "m")
        assertEquals("true", back["tools"]!!.jsonArray[0].jsonObject["strict"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parallel tool call preference maps between chat and messages`() {
        val chat = json(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],"parallel_tool_calls":false}""",
        )
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, chat, "m")
        assertEquals("true", messages["tool_choice"]!!.jsonObject["disable_parallel_tool_use"]!!.jsonPrimitive.content)
        val back = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, messages, "m")
        assertEquals("false", back["parallel_tool_calls"]!!.jsonPrimitive.content)
    }

    @Test
    fun `content filter incomplete reason survives responses chat conversion`() {
        val responses = json(
            """{"id":"resp_1","status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}""",
        )
        val chat = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, responses, "m")
        assertEquals("content_filter", chat["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)

        val filteredChat = json(
            """{"id":"chat_1","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"content_filter"}]}""",
        )
        val back = ProtocolConversion.convertResponse(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, filteredChat, "m")
        assertEquals("incomplete", back["status"]!!.jsonPrimitive.content)
        assertEquals("content_filter", back["incomplete_details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `content filter maps to messages refusal and back`() {
        val chat = json(
            """{"id":"chat_1","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"filtered"},"finish_reason":"content_filter"}]}""",
        )
        val messages = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, chat, "m")
        assertEquals("refusal", messages["stop_reason"]!!.jsonPrimitive.content)
        val back = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, messages, "m")
        assertEquals("content_filter", back["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini chat conversion keeps tool results bound to function names`() {
        val chat = json("""{
            "model":"public",
            "messages":[
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\"city\":\"SZ\"}"}}]},
                {"role":"tool","tool_call_id":"call_1","content":"sunny"}
            ]
        }""")
        val gemini = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chat, "upstream")
        val parts = gemini["contents"]!!.jsonArray.flatMap { it.jsonObject["parts"]!!.jsonArray }
        assertEquals("weather", parts.last().jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini messages conversion keeps thinking budget below max tokens`() {
        val gemini = json("""{
            "contents":[{"role":"user","parts":[{"text":"hi"}]}],
            "generationConfig":{"maxOutputTokens":2048,"thinkingConfig":{"includeThoughts":true}}
        }""")
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.MESSAGES, gemini, "upstream")
        val budget = messages["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toLong()
        assertTrue(budget < messages["max_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `gemini thinking budget zero disables thinking`() {
        val gemini = json("""{"contents":[],"generationConfig":{"thinkingConfig":{"thinkingBudget":0}}}""")
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, gemini, "upstream")
        assertEquals("none", chat["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini stream emits tool calls finish reason and stable id`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, "public")!!
        val first = converter.feed("""data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"weather","args":{"city":"SZ"}}}]}}]}""")
        val second = converter.feed("")
        val last = converter.feed("""data: {"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"STOP"}]}""") +
            converter.feed("") + converter.finish()
        val body = (first + second + last).joinToString("\n")
        assertTrue(body.contains("\"finish_reason\":\"tool_calls\""), body)
        val ids = body.split("{\"id\":\"").drop(1).map { it.substringBefore("\"") }.toSet()
        assertEquals(1, ids.size, body)
    }

    @Test
    fun `responses refusal reaches messages as text block`() {
        val responses = json("""{
            "id":"resp_1","status":"completed",
            "output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"cannot help"}]}]
        }""")
        val messages = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES, responses, "public")
        assertEquals("cannot help", messages["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat refusal reaches messages as text block`() {
        val chat = json("""{
            "id":"chat_1","choices":[{"message":{"role":"assistant","content":null,"refusal":"cannot help"},"finish_reason":"stop"}]
        }""")
        val messages = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, chat, "public")
        assertEquals("cannot help", messages["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `null chat content does not become literal null text`() {
        val chat = json("""{
            "model":"public",
            "messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"f","arguments":"{}"}}]}]
        }""")
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, chat, "upstream")
        assertFalse(messages.toString().contains("\"text\":\"null\""), messages.toString())
        val blocks = messages["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("tool_use"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `gemini function responses pair with parallel calls by fifo`() {
        val gemini = json("""{
            "contents":[
                {"role":"model","parts":[{"functionCall":{"name":"f","args":{}}},{"functionCall":{"name":"f","args":{}}}]},
                {"role":"user","parts":[{"functionResponse":{"name":"f","response":{"content":"a"}}},{"functionResponse":{"name":"f","response":{"content":"b"}}}]}
            ]
        }""")
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, gemini, "upstream")
        val messages = chat["messages"]!!.jsonArray
        val ids = messages.filter { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }
            .flatMap { it.jsonObject["tool_calls"]!!.jsonArray }.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val toolIds = messages.filter { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }
            .map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content }
        assertEquals(ids, toolIds)
    }

    @Test
    fun `gemini thoughts count into chat output tokens`() {
        val gemini = json("""{"responseId":"r","candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"thoughtsTokenCount":2,"totalTokenCount":10}}""")
        val chat = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, gemini, "public")
        assertEquals("5", chat["usage"]!!.jsonObject["prompt_tokens"]!!.jsonPrimitive.content)
        assertEquals("5", chat["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini stream encoder skips frames without candidates`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, "public")!!
        val frames = converter.feed("""data: {"id":"c1","choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}""") + converter.feed("")
        assertTrue(frames.isEmpty(), frames.joinToString("\n"))
    }

    @Test
    fun `gemini function responses pair with responses direction`() {
        val gemini = json("""{
            "contents":[
                {"role":"model","parts":[{"functionCall":{"name":"f","args":{}}}]},
                {"role":"user","parts":[{"functionResponse":{"name":"f","response":{"content":"a"}}}]}
            ]
        }""")
        val responses = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.RESPONSES, gemini, "upstream")
        val input = responses["input"]!!.jsonArray.map { it.jsonObject }
        val callId = input.single { it["type"]!!.jsonPrimitive.content == "function_call" }["call_id"]!!.jsonPrimitive.content
        val outputId = input.single { it["type"]!!.jsonPrimitive.content == "function_call_output" }["call_id"]!!.jsonPrimitive.content
        assertEquals(callId, outputId)
    }

    @Test
    fun `gemini stream keeps usage sent after finish reason`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, "public")!!
        val body = converter.feed("""data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"STOP"}]}""") +
            converter.feed("") +
            converter.feed("""data: {"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"thoughtsTokenCount":2}}""") +
            converter.feed("") + converter.finish()
        val text = body.joinToString("\n")
        assertTrue(text.contains("\"completion_tokens\":5"), text)
        assertEquals(1, Regex("\"finish_reason\":\"stop\"").findAll(text).count(), text)
    }

    @Test
    fun `gemini function responses pair with messages direction`() {
        val gemini = json("""{
            "contents":[
                {"role":"model","parts":[
                    {"functionCall":{"name":"lookup","args":{"n":1}}},
                    {"functionCall":{"name":"lookup","args":{"n":2}}}
                ]},
                {"role":"user","parts":[
                    {"functionResponse":{"name":"lookup","response":{"content":"first"}}},
                    {"functionResponse":{"name":"lookup","response":{"content":"second"}}}
                ]}
            ]
        }""")
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.MESSAGES, gemini, "upstream")
        val blocks = messages["messages"]!!.jsonArray.flatMap { it.jsonObject["content"]!!.jsonArray }.map { it.jsonObject }
        val toolUseIds = blocks.filter { it["type"]!!.jsonPrimitive.content == "tool_use" }.map { it["id"]!!.jsonPrimitive.content }
        val toolResultIds = blocks.filter { it["type"]!!.jsonPrimitive.content == "tool_result" }.map { it["tool_use_id"]!!.jsonPrimitive.content }
        assertEquals(2, toolUseIds.distinct().size)
        assertEquals(toolUseIds, toolResultIds)
    }

    @Test
    fun `parallel chat tool rounds are grouped into Gemini contents`() {
        val chat = json("""{
            "messages":[
                {"role":"assistant","tool_calls":[
                    {"id":"a","type":"function","function":{"name":"one","arguments":"{}"}},
                    {"id":"b","type":"function","function":{"name":"two","arguments":"{}"}}
                ]},
                {"role":"tool","tool_call_id":"a","content":"A"},
                {"role":"tool","tool_call_id":"b","content":"B"}
            ]
        }""")
        val contents = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chat, "upstream")["contents"]!!.jsonArray
        assertEquals(2, contents.size)
        assertEquals(listOf("model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(2, contents[0].jsonObject["parts"]!!.jsonArray.size)
        assertEquals(2, contents[1].jsonObject["parts"]!!.jsonArray.size)
    }

    @Test
    fun `parallel responses tool rounds are grouped into Gemini contents`() {
        val responses = json("""{
            "input":[
                {"type":"function_call","call_id":"a","name":"one","arguments":"{}"},
                {"type":"function_call","call_id":"b","name":"two","arguments":"{}"},
                {"type":"function_call_output","call_id":"a","output":"A"},
                {"type":"function_call_output","call_id":"b","output":"B"}
            ]
        }""")
        val contents = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, responses, "upstream")["contents"]!!.jsonArray
        assertEquals(2, contents.size)
        assertEquals(listOf("model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(2, contents[0].jsonObject["parts"]!!.jsonArray.size)
        assertEquals(2, contents[1].jsonObject["parts"]!!.jsonArray.size)
    }

    @Test
    fun `Gemini thinking history is rejected for Messages without a signature`() {
        val gemini = json("""{"contents":[{"role":"model","parts":[{"text":"thought","thought":true}]}]}""")
        assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.MESSAGES, gemini, "upstream")
        }
    }

    @Test
    fun `Gemini tool grouping preserves adjacent text boundaries`() {
        val responses = json("""{"input":[
            {"type":"function_call","call_id":"a","name":"one","arguments":"{}"},
            {"type":"function_call_output","call_id":"a","output":"A"},
            {"type":"message","role":"user","content":"keep separate"}
        ]}""")
        val contents = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, responses, "upstream")["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals("keep separate", contents.last().jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini top k is rejected for chat but kept for messages`() {
        val gemini = json("""{"contents":[],"generationConfig":{"topK":40}}""")
        assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, gemini, "upstream")
        }
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.MESSAGES, gemini, "upstream")
        assertEquals("40", messages["top_k"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini safety reasons all become content filter incomplete`() {
        listOf("RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "SAFETY").forEach { reason ->
            val gemini = json("""{"responseId":"r","candidates":[{"content":{"role":"model","parts":[{"text":"hi"}]},"finishReason":"$reason"}]}""")
            val responses = ProtocolConversion.convertResponse(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, gemini, "public")
            assertEquals("incomplete", responses["status"]!!.jsonPrimitive.content, reason)
            assertEquals("content_filter", responses["incomplete_details"]!!.jsonObject["reason"]!!.jsonPrimitive.content, reason)
        }
    }

    @Test
    fun `responses refusal becomes gemini safety response`() {
        val responses = json("""{
            "id":"resp_1","status":"completed",
            "output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"cannot help"}]}]
        }""")
        val gemini = ProtocolConversion.convertResponse(GatewayProtocol.GEMINI, GatewayProtocol.RESPONSES, responses, "public")
        val candidate = gemini["candidates"]!!.jsonArray.single().jsonObject
        assertEquals("SAFETY", candidate["finishReason"]!!.jsonPrimitive.content)
        assertEquals("cannot help", candidate["content"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini stream tool call without id gets stable fallback id`() {
        val converter = ProtocolConversion.streamConverter(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, "public")!!
        val body = (converter.feed("""data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"f","args":{}}}]}}]}""") +
            converter.feed("")).joinToString("\n")
        assertTrue(Regex("\"id\":\"call_gemini_\\d+_0\"").containsMatchIn(body), body)
    }

    @Test
    fun `chat assistant text blocks survive Gemini conversion`() {
        val chat = json("""{"messages":[{"role":"assistant","content":[
            {"type":"text","text":"first"},{"type":"text","text":"second"}
        ]}]}""")
        val gemini = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chat, "upstream")
        assertEquals("first\nsecond", gemini["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat assistant unsupported content is rejected for Gemini`() {
        val chat = json("""{"messages":[{"role":"assistant","content":[{"type":"image_url","image_url":{"url":"https://example.com/image.png"}}]}]}""")
        val error = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chat, "upstream")
        }
        assertEquals("messages[0].content[0].type", error.field)
    }

    @Test
    fun `Gemini plain text mime equals the default for every target`() {
        val request = json("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}""")
        val explicit = json("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"generationConfig":{"responseMimeType":"text/plain"}}""")
        listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES).forEach { target ->
            assertEquals(
                ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, request, "upstream"),
                ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, explicit, "upstream"),
                target.toString(),
            )
        }
    }

    @Test
    fun `unsupported Gemini generation controls fail with a field path`() {
        val controls = mapOf(
            "stopSequences" to "[\"END\"]", "topK" to "40", "seed" to "7",
            "frequencyPenalty" to "0.5", "presencePenalty" to "0.5",
            "responseLogprobs" to "false", "logprobs" to "3",
        )
        listOf(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES).forEach { target ->
            controls.forEach { (field, value) ->
                if (target == GatewayProtocol.MESSAGES && field in setOf("stopSequences", "topK")) return@forEach
                val request = json("""{"contents":[],"generationConfig":{"$field":$value}}""")
                val error = assertFailsWith<ProtocolConversionException>("$target $field") {
                    ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, request, "upstream")
                }
                assertEquals("generationConfig.$field", error.field)
            }
        }
    }

    @Test
    fun `Gemini thought history is never merged into ordinary target text`() {
        val request = json("""{"contents":[{"role":"model","parts":[
            {"text":"private thinking","thought":true},{"text":"visible answer"}
        ]}]}""")
        listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES).forEach { target ->
            val error = assertFailsWith<ProtocolConversionException> {
                ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, request, "upstream")
            }
            assertEquals("contents[0].parts[0]", error.field)
        }
    }

    @Test
    fun `Gemini thinking budget is independent of summary visibility`() {
        listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES).forEach { target ->
            listOf(0, 1024, 8192, -1).forEach { budget ->
                val visible = json("""{"contents":[],"generationConfig":{"thinkingConfig":{"thinkingBudget":$budget,"includeThoughts":true}}}""")
                val hidden = json("""{"contents":[],"generationConfig":{"thinkingConfig":{"thinkingBudget":$budget,"includeThoughts":false}}}""")
                assertEquals(
                    ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, visible, "upstream"),
                    ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, hidden, "upstream"),
                    "$target budget=$budget",
                )
            }
            val default = json("""{"contents":[]}""")
            val hiddenOnly = json("""{"contents":[],"generationConfig":{"thinkingConfig":{"includeThoughts":false}}}""")
            assertEquals(
                ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, default, "upstream"),
                ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, hiddenOnly, "upstream"),
            )
        }
    }

    @Test
    fun `messages tool_choice converts to Gemini toolConfig`() {
        val cases = listOf(
            """{"type":"none"}""" to ("NONE" to null),
            """{"type":"any"}""" to ("ANY" to null),
            """{"type":"auto"}""" to ("AUTO" to null),
            """{"type":"tool","name":"lookup"}""" to ("ANY" to "lookup"),
        )
        for ((choice, expected) in cases) {
            val (expectedMode, expectedAllowed) = expected
            val request = json("""{"messages":[{"role":"user","content":"hi"}],"tool_choice":$choice}""")
            val gemini = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, request, "upstream")
            val config = gemini["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject
            assertEquals(expectedMode, config?.string("mode"), "mode for $choice")
            val allowed = config?.get("allowedFunctionNames")?.jsonArray?.map { it.jsonPrimitive.content }
            if (expectedAllowed != null) {
                assertEquals(listOf(expectedAllowed), allowed, "allowed for $choice")
            } else {
                assertEquals(null, allowed, "allowed for $choice")
            }
        }

        val missingName = json("""{"messages":[{"role":"user","content":"hi"}],"tool_choice":{"type":"tool"}}""")
        val error = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, missingName, "upstream")
        }
        assertEquals("tool_choice.name", error.field)
    }

    @Test
    fun `forced function tool_choice preserves allowed function names for Gemini`() {
        val chatForced = json("""{
            "messages":[{"role":"user","content":"hi"}],
            "tools":[{"type":"function","function":{"name":"weather"}}],
            "tool_choice":{"type":"function","function":{"name":"weather"}}
        }""")
        val geminiFromChat = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chatForced, "upstream")
        val chatConfig = geminiFromChat["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject
        assertEquals("ANY", chatConfig?.string("mode"))
        assertEquals("weather", chatConfig?.get("allowedFunctionNames")?.jsonArray?.single()?.jsonPrimitive?.content)

        val responsesForced = json("""{
            "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],
            "tools":[{"type":"function","name":"weather"}],
            "tool_choice":{"type":"function","name":"weather"}
        }""")
        val geminiFromResponses = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, responsesForced, "upstream")
        val responsesConfig = geminiFromResponses["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject
        assertEquals("ANY", responsesConfig?.string("mode"))
        assertEquals("weather", responsesConfig?.get("allowedFunctionNames")?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun `Gemini toolConfig with allowedFunctionNames converts symmetrically to all targets`() {
        val forcedGemini = json("""{
            "contents":[{"role":"user","parts":[{"text":"hi"}]}],
            "toolConfig":{"functionCallingConfig":{"mode":"ANY","allowedFunctionNames":["weather"]}}
        }""")
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, forcedGemini, "upstream")
        val chatChoice = chat["tool_choice"]?.jsonObject
        assertEquals("function", chatChoice?.string("type"))
        assertEquals("weather", chatChoice?.get("function")?.jsonObject?.string("name"))

        val messages = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.MESSAGES, forcedGemini, "upstream")
        val messagesChoice = messages["tool_choice"]?.jsonObject
        assertEquals("tool", messagesChoice?.string("type"))
        assertEquals("weather", messagesChoice?.string("name"))

        val responses = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.RESPONSES, forcedGemini, "upstream")
        val responsesChoice = responses["tool_choice"]?.jsonObject
        assertEquals("function", responsesChoice?.string("type"))
        assertEquals("weather", responsesChoice?.string("name"))
    }

    @Test
    fun `explicit null optional fields do not fail conversion`() {
        val chatStopNull = json("""{"messages":[{"role":"user","content":"hi"}],"stop":null}""")
        val resp = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, chatStopNull, "upstream")
        assertFalse(resp.containsKey("stop"))

        val chatFormatNull = json("""{"messages":[{"role":"user","content":"hi"}],"response_format":null}""")
        val msg = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, chatFormatNull, "upstream")
        assertFalse(msg.containsKey("response_format"))

        val msgStopNull = json("""{"messages":[{"role":"user","content":"hi"}],"stop_sequences":null}""")
        val resp2 = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES, msgStopNull, "upstream")
        assertFalse(resp2.containsKey("stop_sequences"))

        val respFormatNull = json("""{"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],"text":{"format":null}}""")
        val msg2 = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, respFormatNull, "upstream")
        assertFalse(msg2.containsKey("format"))

        val geminiTopKNull = json("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"generationConfig":{"topK":null}}""")
        val chat2 = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, geminiTopKNull, "upstream")
        assertFalse(chat2.containsKey("top_k"))

        val geminiCachedNull = json("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"cachedContent":null}""")
        val chat3 = ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, geminiCachedNull, "upstream")
        assertFalse(chat3.containsKey("cachedContent"))

        val chatResponsesNulls = json("""{"messages":[{"role":"user","content":"hi"}],"response_format":null,"parallel_tool_calls":null,"tools":null,"tool_choice":null}""")
        val resp3 = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, chatResponsesNulls, "upstream")
        assertFalse(resp3.containsKey("text"))
        assertFalse(resp3.containsKey("parallel_tool_calls"))
        assertFalse(resp3.containsKey("tools"))
        assertFalse(resp3.containsKey("tool_choice"))

        val chatMessagesNulls = json("""{"messages":[{"role":"user","content":"hi"}],"tools":null,"tool_choice":null}""")
        val msg3 = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, chatMessagesNulls, "upstream")
        assertFalse(msg3.containsKey("tools"))
        assertFalse(msg3.containsKey("tool_choice"))

        val messagesChatNulls = json("""{"messages":[{"role":"user","content":"hi"}],"tools":null,"tool_choice":null}""")
        val chat4 = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.CHAT_COMPLETIONS, messagesChatNulls, "upstream")
        assertFalse(chat4.containsKey("tools"))
        assertFalse(chat4.containsKey("tool_choice"))

        val responsesMessagesNulls = json("""{"input":[{"type":"message","role":"user","content":"hi"}],"tools":null,"tool_choice":null}""")
        val msg4 = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, responsesMessagesNulls, "upstream")
        assertFalse(msg4.containsKey("tools"))
        assertFalse(msg4.containsKey("tool_choice"))

        val responsesChatNulls = json("""{"input":[{"type":"message","role":"user","content":"hi"}],"tools":null,"tool_choice":null}""")
        val chat5 = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, responsesChatNulls, "upstream")
        assertFalse(chat5.containsKey("tools"))
        assertFalse(chat5.containsKey("tool_choice"))
    }

    @Test
    fun `Gemini response tool calls incorporate responseId and preserve upstream id`() {
        val geminiWithoutId = json("""{
            "responseId":"resp_123",
            "candidates":[{"content":{"role":"model","parts":[
                {"functionCall":{"name":"lookup","args":{"q":"test"}}}
            ]}}]
        }""")
        val chatWithoutId = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, geminiWithoutId, "public")
        val chatCalls = chatWithoutId.array("choices").single().jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray
        assertEquals("call_resp_123_0", chatCalls.single().jsonObject.string("id"))

        val messagesWithoutId = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, geminiWithoutId, "public")
        val messageBlocks = messagesWithoutId.array("content")
        assertEquals("toolu_resp_123_0", messageBlocks.single().jsonObject.string("id"))

        val responsesWithoutId = ProtocolConversion.convertResponse(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, geminiWithoutId, "public")
        val responsesOutput = responsesWithoutId.array("output")
        assertEquals("call_resp_123_0", responsesOutput.single().jsonObject.string("call_id"))

        val geminiWithId = json("""{
            "responseId":"resp_123",
            "candidates":[{"content":{"role":"model","parts":[
                {"functionCall":{"id":"custom_call_99","name":"lookup","args":{"q":"test"}}}
            ]}}]
        }""")
        val chatWithId = ProtocolConversion.convertResponse(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, geminiWithId, "public")
        assertEquals("custom_call_99", chatWithId.array("choices").single().jsonObject["message"]!!.jsonObject["tool_calls"]!!.jsonArray.single().jsonObject.string("id"))

        val messagesWithId = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, geminiWithId, "public")
        assertEquals("custom_call_99", messagesWithId.array("content").single().jsonObject.string("id"))

        val responsesWithId = ProtocolConversion.convertResponse(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, geminiWithId, "public")
        assertEquals("custom_call_99", responsesWithId.array("output").single().jsonObject.string("call_id"))
    }

    @Test
    fun `messages stream encoder supplies default usage on delta and start when upstream usage is absent`() {
        val encoder = MessagesStreamEncoder("claude-test")
        val chunk1 = StreamEvent(id = "msg_1", content = "hello")
        val startLines = encoder.emit(chunk1, null)
        val startData = json(startLines.first { it.startsWith("data: ") }.removePrefix("data: "))
        assertEquals(0L, startData["message"]!!.jsonObject["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.content.toLong())

        val chunk2 = StreamEvent(id = "msg_1", finishReason = FinishReason.STOP)
        val deltaLines = encoder.emit(chunk2, null)
        val deltaData = json(deltaLines.first { it.startsWith("data: ") && it.contains("message_delta") }.removePrefix("data: "))
        assertEquals(0L, deltaData["usage"]!!.jsonObject["output_tokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `protocol conversion supports verifies registered pairs and identity`() {
        GatewayProtocol.entries.forEach { p1 ->
            GatewayProtocol.entries.forEach { p2 ->
                assertTrue(ProtocolConversion.supports(p1, p2))
            }
        }
    }

    @Test
    fun `gemini response with thought part does not emit thinking block for messages`() {
        val gemini = json("""{
            "responseId":"resp_thought",
            "candidates":[{"content":{"role":"model","parts":[
                {"text":"private thinking","thought":true},
                {"text":"visible answer"}
            ]}}]
        }""")
        val messages = ProtocolConversion.convertResponse(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, gemini, "claude-model")
        val content = messages.array("content")
        assertEquals(1, content.size)
        assertEquals("text", content.single().jsonObject.string("type"))
        assertEquals("visible answer", content.single().jsonObject.string("text"))
    }

    @Test
    fun `chat response with null finish_reason omits finishReason in Gemini`() {
        val chatNullFinish = json("""{
            "id":"chatcmpl_1",
            "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":null}]
        }""")
        val gemini = ProtocolConversion.convertResponse(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, chatNullFinish, "public")
        val candidate = gemini.array("candidates").single().jsonObject
        assertFalse(candidate.containsKey("finishReason"), "candidate should not have finishReason when chat finish_reason is null")

        val chatStopFinish = json("""{
            "id":"chatcmpl_2",
            "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]
        }""")
        val geminiStop = ProtocolConversion.convertResponse(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, chatStopFinish, "public")
        assertEquals("STOP", geminiStop.array("candidates").single().jsonObject.string("finishReason"))
    }

    @Test
    fun `gemini stream encoder emits usage on finish chunks and skips empty intermediate chunks`() {
        val encoder = GeminiStreamEncoder("gemini-test")
        val usage = TokenUsage(inputTokens = 10, outputTokens = 20, totalTokens = 30)

        val intermediate = StreamEvent(id = "chunk_usage")
        val emptyLines = encoder.emit(intermediate, usage)
        assertTrue(emptyLines.isEmpty(), "intermediate empty chunk without finishReason should be skipped")

        val finishEvent = StreamEvent(id = "chunk_usage", finishReason = FinishReason.STOP)
        val lines = encoder.emit(finishEvent, usage)
        assertTrue(lines.isNotEmpty())
        val data = json(lines.first { it.startsWith("data: ") }.removePrefix("data: "))
        assertTrue(data.containsKey("candidates"), "finish chunk must contain candidates")
        assertEquals(10L, data["usageMetadata"]!!.jsonObject["promptTokenCount"]!!.jsonPrimitive.content.toLong())
        assertEquals(20L, data["usageMetadata"]!!.jsonObject["candidatesTokenCount"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `responses reasoning output replay is accepted by all target request conversions`() {
        val request = json("""{
            "input":[
                {"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]},
                {"type":"reasoning","summary":[{"type":"summary_text","text":"thought text"}]},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"world"}]}
            ]
        }""")
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, request, "upstream")
        val chatMessages = chat.array("messages")
        assertEquals(2, chatMessages.size)
        assertEquals("user", chatMessages[0].jsonObject.string("role"))
        assertEquals("assistant", chatMessages[1].jsonObject.string("role"))

        val messages = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, request, "upstream")
        val msgList = messages.array("messages")
        assertEquals(2, msgList.size)
        assertEquals("user", msgList[0].jsonObject.string("role"))
        assertEquals("assistant", msgList[1].jsonObject.string("role"))

        val gemini = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, request, "upstream")
        val contents = gemini.array("contents")
        assertEquals(2, contents.size)
        assertEquals("user", contents[0].jsonObject.string("role"))
        assertEquals("model", contents[1].jsonObject.string("role"))
    }

    @Test
    fun `Gemini generationConfig rejects unknown fields and responseSchema`() {
        val schemaRequest = json("""{
            "contents":[{"role":"user","parts":[{"text":"hi"}]}],
            "generationConfig":{"responseSchema":{"type":"object"}}
        }""")
        val error1 = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, schemaRequest, "upstream")
        }
        assertEquals("generationConfig.responseSchema", error1.field)

        val unknownRequest = json("""{
            "contents":[{"role":"user","parts":[{"text":"hi"}]}],
            "generationConfig":{"speechConfig":{}}
        }""")
        val error2 = assertFailsWith<ProtocolConversionException> {
            ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, unknownRequest, "upstream")
        }
        assertEquals("generationConfig.speechConfig", error2.field)
    }

    @Test
    fun `Gemini built-in tools are rejected for all target protocols`() {
        val tools = listOf("googleSearch", "codeExecution", "urlContext")
        val targets = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES)
        for (tool in tools) {
            val request = json("""{
                "contents":[{"role":"user","parts":[{"text":"hi"}]}],
                "tools":[{"$tool":{}}]
            }""")
            for (target in targets) {
                val error = assertFailsWith<ProtocolConversionException>("$target $tool") {
                    ProtocolConversion.convertRequest(GatewayProtocol.GEMINI, target, request, "upstream")
                }
                assertEquals("tools[0].$tool", error.field)
            }
        }
    }

    @Test
    fun `messages stream encoder preserves refusal when content is empty or both are present`() {
        val encoder1 = MessagesStreamEncoder("claude-test")
        val chunkEmptyContent = StreamEvent(id = "msg_1", content = "", refusal = "refused by safety")
        val lines1 = encoder1.emit(chunkEmptyContent, null)
        val deltaLine1 = lines1.first { it.startsWith("data: ") && it.contains("content_block_delta") }
        assertTrue(deltaLine1.contains("refused by safety"), deltaLine1)

        val encoder2 = MessagesStreamEncoder("claude-test")
        val chunkBoth = StreamEvent(id = "msg_2", content = "sorry, ", refusal = "refused by safety")
        val lines2 = encoder2.emit(chunkBoth, null)
        val deltaLine2 = lines2.first { it.startsWith("data: ") && it.contains("content_block_delta") }
        assertTrue(deltaLine2.contains("sorry, refused by safety"), deltaLine2)
    }

    @Test
    fun `chat to messages request skips empty text blocks and messages but keeps tool use`() {
        val chat = json("""{
            "messages":[
                {"role":"user","content":""},
                {"role":"assistant","content":[{"type":"text","text":""}]},
                {"role":"user","content":"real question"},
                {"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}
            ]
        }""")
        val messages = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, chat, "upstream")
        val msgArray = messages.array("messages")
        assertEquals(2, msgArray.size)
        assertEquals("user", msgArray[0].jsonObject.string("role"))
        assertEquals("real question", msgArray[0].jsonObject.array("content").single().jsonObject.string("text"))
        assertEquals("assistant", msgArray[1].jsonObject.string("role"))
        assertEquals("tool_use", msgArray[1].jsonObject.array("content").single().jsonObject.string("type"))
    }

    @Test
    fun `tool call history converted to Gemini includes thoughtSignature on first functionCall part`() {
        val chat = json("""{
            "messages":[
                {"role":"user","content":"hi"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}
            ]
        }""")
        val geminiFromChat = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chat, "upstream")
        val modelPartChat = geminiFromChat.array("contents")[1].jsonObject.array("parts").single().jsonObject
        assertEquals("skip_thought_signature_validator", modelPartChat.string("thoughtSignature"))

        val msg = json("""{
            "messages":[
                {"role":"user","content":"hi"},
                {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"lookup","input":{}}]}
            ]
        }""")
        val geminiFromMsg = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, msg, "upstream")
        val modelPartMsg = geminiFromMsg.array("contents")[1].jsonObject.array("parts").single().jsonObject
        assertEquals("skip_thought_signature_validator", modelPartMsg.string("thoughtSignature"))

        val resp = json("""{
            "input":[
                {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                {"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}
            ]
        }""")
        val geminiFromResp = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, resp, "upstream")
        val modelPartResp = geminiFromResp.array("contents")[1].jsonObject.array("parts").single().jsonObject
        assertEquals("skip_thought_signature_validator", modelPartResp.string("thoughtSignature"))
    }

    @Test
    fun `user messages with empty text do not generate empty parts or contents in Gemini`() {
        val chat = json("""{"messages":[{"role":"user","content":""},{"role":"user","content":"hello"}]}""")
        val geminiFromChat = ProtocolConversion.convertRequest(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI, chat, "upstream")
        val chatContents = geminiFromChat.array("contents")
        assertEquals(1, chatContents.size)
        assertEquals("hello", chatContents[0].jsonObject.array("parts").single().jsonObject.string("text"))

        val msg = json("""{"messages":[{"role":"user","content":[{"type":"text","text":""}]},{"role":"user","content":"hello"}]}""")
        val geminiFromMsg = ProtocolConversion.convertRequest(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI, msg, "upstream")
        val msgContents = geminiFromMsg.array("contents")
        assertEquals(1, msgContents.size)
        assertEquals("hello", msgContents[0].jsonObject.array("parts").single().jsonObject.string("text"))

        val respEmpty = json("""{"input":""}""")
        val geminiFromEmptyResp = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, respEmpty, "upstream")
        assertTrue(geminiFromEmptyResp.array("contents").isEmpty())

        val respItems = json("""{"input":[{"type":"message","role":"user","content":""},{"type":"message","role":"user","content":"hello"}]}""")
        val geminiFromResp = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, respItems, "upstream")
        val respContents = geminiFromResp.array("contents")
        assertEquals(1, respContents.size)
        assertEquals("hello", respContents[0].jsonObject.array("parts").single().jsonObject.string("text"))
    }

    @Test
    fun `responses refusal output replay is accepted by all target request conversions`() {
        val request = json("""{
            "input":[
                {"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]},
                {"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"cannot help"}]}
            ]
        }""")
        val chat = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.CHAT_COMPLETIONS, request, "upstream")
        val chatMessages = chat.array("messages")
        assertEquals(2, chatMessages.size)
        assertEquals("assistant", chatMessages[1].jsonObject.string("role"))
        assertEquals("cannot help", chatMessages[1].jsonObject.string("refusal"))

        val messages = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES, request, "upstream")
        val msgList = messages.array("messages")
        assertEquals(2, msgList.size)
        assertEquals("assistant", msgList[1].jsonObject.string("role"))
        assertEquals("cannot help", msgList[1].jsonObject.array("content").single().jsonObject.string("text"))

        val gemini = ProtocolConversion.convertRequest(GatewayProtocol.RESPONSES, GatewayProtocol.GEMINI, request, "upstream")
        val contents = gemini.array("contents")
        assertEquals(2, contents.size)
        assertEquals("model", contents[1].jsonObject.string("role"))
        assertEquals("cannot help", contents[1].jsonObject.array("parts").single().jsonObject.string("text"))
    }
}
