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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtocolConversionTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

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
        assertEquals("tool_use", messages[1].jsonObject["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
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
    fun `messages and responses convert through canonical chat`() {
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
}
