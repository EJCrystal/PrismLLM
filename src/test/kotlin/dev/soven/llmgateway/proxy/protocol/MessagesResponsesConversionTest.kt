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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesResponsesConversionTest {
    @Test
    fun `empty Responses messages do not create invalid Messages text blocks`() {
        val converted = ResponsesToMessages.convertRequest(json("""{
            "input":[
                {"type":"message","role":"user","content":""},
                {"type":"message","role":"assistant","content":[]},
                {"type":"message","role":"user","content":[{"type":"output_text","text":""}]},
                {"type":"message","role":"user","content":"hello"}
            ]
        }"""), "upstream")
        val messages = converted["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("hello", messages.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val emptyInput = ResponsesToMessages.convertRequest(json("""{"input":""}"""), "upstream")
        assertTrue(emptyInput["messages"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `messages request preserves interleaved text and tool order without a chat intermediary`() {
        val request = json("""{
            "model":"public","max_tokens":32,"system":[{"type":"text","text":"system"}],
            "messages":[
                {"role":"assistant","content":[{"type":"text","text":"before"},{"type":"tool_use","id":"call_1","name":"lookup","input":{"city":"SZ"}},{"type":"text","text":"after"}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":[{"type":"text","text":"sunny"},{"type":"text","text":"warm"}]},{"type":"text","text":"thanks"}]}
            ],
            "tools":[{"name":"lookup","description":"weather","input_schema":{"type":"object"}}],
            "tool_choice":{"type":"tool","name":"lookup","disable_parallel_tool_use":true},
            "thinking":{"type":"enabled","budget_tokens":4096},"metadata":{"user_id":"user_1"}
        }""")
        val converted = MessagesToResponses.convertRequest(request, "upstream")
        val input = converted["input"]!!.jsonArray
        assertEquals(listOf("message", "function_call", "message", "function_call_output", "message"), input.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("before", input[0].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("{\"city\":\"SZ\"}", input[1].jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("after", input[2].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("sunny\nwarm", input[3].jsonObject["output"]!!.jsonPrimitive.content)
        assertEquals("thanks", input[4].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("system", converted["instructions"]!!.jsonPrimitive.content)
        assertEquals("user_1", converted["user"]!!.jsonPrimitive.content)
        assertEquals("medium", converted["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("false", converted["parallel_tool_calls"]!!.jsonPrimitive.content)
        assertEquals("lookup", converted["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("lookup", converted["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses request converts grouped tool results and parallel tool preference directly`() {
        val request = json("""{
            "model":"public","instructions":"system","input":[
                {"type":"function_call","call_id":"first","name":"lookup","arguments":"{}"},
                {"type":"function_call_output","call_id":"first","output":"A"},
                {"type":"function_call_output","call_id":"second","output":"B"},
                {"role":"user","content":[{"type":"input_text","text":"next"}]}
            ],"max_output_tokens":16384,"tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}],
            "parallel_tool_calls":false,"reasoning":{"effort":"high"},"user":"user_1","store":false,"include":[],"truncation":"disabled"
        }""")
        val converted = ResponsesToMessages.convertRequest(request, "upstream")
        val messages = converted["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(2, messages[1].jsonObject["content"]!!.jsonArray.size)
        assertEquals("first", messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("user_1", converted["metadata"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
        assertEquals("16384", converted["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("8192", converted["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("true", converted["tool_choice"]!!.jsonObject["disable_parallel_tool_use"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct responses preserve output order tool identifiers and cache usage`() {
        val messages = json("""{
            "id":"msg_1","stop_reason":"max_tokens","content":[
                {"type":"text","text":"before"},{"type":"tool_use","id":"call_1","name":"lookup","input":{"city":"SZ"}},
                {"type":"text","text":"after"}
            ],"usage":{"input_tokens":3,"cache_read_input_tokens":4,"cache_creation_input_tokens":2,"output_tokens":5}
        }""")
        val responses = ResponsesToMessages.convertResponse(messages, "public")
        val output = responses["output"]!!.jsonArray
        assertEquals(listOf("message", "function_call", "message"), output.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("call_1", output[1].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("incomplete", responses["status"]!!.jsonPrimitive.content)
        assertEquals("9", responses["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content)
        val back = MessagesToResponses.convertResponse(responses, "public")
        assertEquals("max_tokens", back["stop_reason"]!!.jsonPrimitive.content)
        assertEquals("call_1", back["content"]!!.jsonArray[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("3", back["usage"]!!.jsonObject["input_tokens"]!!.jsonPrimitive.content)
        assertEquals("4", back["usage"]!!.jsonObject["cache_read_input_tokens"]!!.jsonPrimitive.content)
        assertEquals("2", back["usage"]!!.jsonObject["cache_creation_input_tokens"]!!.jsonPrimitive.content)
        val filtered = json("""{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[{"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}]}""")
        assertEquals("refusal", MessagesToResponses.convertResponse(filtered, "public")["stop_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct conversion rejects unsupported semantics instead of dropping them`() {
        val cases = listOf(
            Triple(MessagesToResponses, """{"messages":[],"stop_sequences":["END"]}""", "stop_sequences"),
            Triple(MessagesToResponses, """{"messages":[],"metadata":{"trace":"value"}}""", "metadata.trace"),
            Triple(ResponsesToMessages, """{"input":"hi","previous_response_id":"resp_1"}""", "previous_response_id"),
            Triple(ResponsesToMessages, """{"input":"hi","store":true}""", "store"),
            Triple(ResponsesToMessages, """{"input":"hi","truncation":"auto"}""", "truncation"),
            Triple(ResponsesToMessages, """{"input":"hi","text":{"format":{"type":"json_schema"}}}""", "text.format"),
        )
        cases.forEach { (converter, request, field) ->
            val error = assertFailsWith<ProtocolConversionException> { converter.convertRequest(json(request), "upstream") }
            assertEquals(field, error.field)
        }
    }

    @Test
    fun `direct conversion strips compatible client extensions without failing`() {
        val messagesReq = json("""{"messages":[{"role":"user","content":"hi"}],"context_management":{"edits":[]},"output_config":{"format":{"type":"text"}}}""")
        val responsesFromMessages = MessagesToResponses.convertRequest(messagesReq, "upstream")
        assertFalse(responsesFromMessages.containsKey("context_management"))
        assertFalse(responsesFromMessages.containsKey("output_config"))

        val responsesReq = json("""{"input":"hi","include":["reasoning.encrypted_content","message.output_tokens"]}""")
        val messagesFromResponses = ResponsesToMessages.convertRequest(responsesReq, "upstream")
        assertFalse(messagesFromResponses.containsKey("include"))

        val messagesWithEffort = json("""{"messages":[{"role":"user","content":"hi"}],"output_config":{"effort":"high"}}""")
        val responsesWithEffort = MessagesToResponses.convertRequest(messagesWithEffort, "upstream")
        assertEquals("high", responsesWithEffort["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(responsesWithEffort.containsKey("output_config"))
    }

    @Test
    fun `messages thinking history is omitted instead of failing a responses request`() {
        val converted = MessagesToResponses.convertRequest(json("""{
            "model":"public","max_tokens":64,
            "messages":[
                {"role":"user","content":"hi"},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"weigh the options","signature":"sig"},
                    {"type":"redacted_thinking","data":"opaque"},
                    {"type":"text","text":"answer"}
                ]},
                {"role":"user","content":"again"},
                {"role":"assistant","content":[{"type":"thinking","thinking":"only thought","signature":"sig"}]},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"call the tool","signature":"sig"},
                    {"type":"tool_use","id":"call_1","name":"lookup","input":{}}
                ]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"ok"}]}
            ]
        }"""), "upstream")
        val input = converted["input"]!!.jsonArray
        assertEquals(
            listOf("message", "message", "message", "function_call", "function_call_output"),
            input.map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
        assertEquals("answer", input[1].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `messages system turns fold into instructions before a reply and reminders after`() {
        val converted = MessagesToResponses.convertRequest(json("""{
            "model":"public","max_tokens":64,
            "system":"You are Claude Code.",
            "messages":[
                {"role":"system","content":[{"type":"text","text":"env: working dir /home/me"}]},
                {"role":"user","content":"hi"},
                {"role":"assistant","content":"hello"},
                {"role":"system","content":"the user switched rooms"},
                {"role":"user","content":"again"}
            ]
        }"""), "upstream")

        assertEquals(
            "You are Claude Code.\n\nenv: working dir /home/me",
            converted["instructions"]!!.jsonPrimitive.content,
        )
        val input = converted["input"]!!.jsonArray
        assertEquals(listOf("message", "message", "message"), input.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertTrue(input.none { it.jsonObject["role"]?.jsonPrimitive?.content in setOf("system", "developer") })
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", input[0].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("assistant", input[1].jsonObject["role"]!!.jsonPrimitive.content)

        val reminder = input[2].jsonObject["content"]!!.jsonArray
        assertEquals(2, reminder.size)
        val reminderText = reminder[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(reminderText.startsWith("<system-reminder>\n"))
        assertTrue(reminderText.contains("the user switched rooms"))
        assertTrue(reminderText.endsWith("\n</system-reminder>"))
        assertEquals("again", reminder[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `messages developer turns fold like system turns`() {
        val converted = MessagesToResponses.convertRequest(json("""{
            "model":"public","max_tokens":64,
            "messages":[
                {"role":"developer","content":"follow the harness rules"},
                {"role":"user","content":"hi"}
            ]
        }"""), "upstream")

        assertEquals("follow the harness rules", converted["instructions"]!!.jsonPrimitive.content)
        assertEquals("user", converted["input"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `messages system turn after a user but before a reply stays in instructions`() {
        val converted = MessagesToResponses.convertRequest(json("""{
            "model":"public","max_tokens":64,
            "messages":[
                {"role":"user","content":"hi"},
                {"role":"system","content":"apply the pending plan"},
                {"role":"assistant","content":"ok"}
            ]
        }"""), "upstream")

        assertEquals("apply the pending plan", converted["instructions"]!!.jsonPrimitive.content)
        val input = converted["input"]!!.jsonArray
        assertEquals(listOf("user", "assistant"), input.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("hi", input[0].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct responses reject reasoning blocks that cannot be represented faithfully`() {
        val unsupportedReasoning = json("""{"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"plan"}]}]}""")
        assertEquals("output[0].type", assertFailsWith<ProtocolConversionException> {
            MessagesToResponses.convertResponse(unsupportedReasoning, "public")
        }.field)
        val redacted = json("""{"content":[{"type":"redacted_thinking","data":"opaque"}]}""")
        assertEquals("content[0].type", assertFailsWith<ProtocolConversionException> {
            ResponsesToMessages.convertResponse(redacted, "public")
        }.field)
    }

    @Test
    fun `all six streams preserve fragmented tool arguments identifiers and cache usage`() {
        val protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES)
        protocols.forEach { source -> protocols.filter { it != source }.forEach { client ->
            val converter = ProtocolConverterRegistry.get(client, source).streamConverter("public")
            val frames = toolStream(source).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
            val events = frames.filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }
            val arguments = events.joinToString("") { event ->
                when (client) {
                    GatewayProtocol.CHAT_COMPLETIONS -> (event["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("tool_calls")?.jsonArray?.joinToString("") {
                        it.jsonObject["function"]!!.jsonObject["arguments"]?.jsonPrimitive?.content.orEmpty()
                    }.orEmpty()
                    GatewayProtocol.MESSAGES -> (event["delta"] as? JsonObject)?.string("partial_json").orEmpty()
                    GatewayProtocol.RESPONSES -> if (event.string("type") == "response.function_call_arguments.delta") event.string("delta").orEmpty() else ""
                    else -> error("No Gemini conversion")
                }
            }
            assertEquals("{\"city\":\"SZ\"}", arguments, "$source → $client: $frames")
            assertTrue(frames.joinToString().contains("call_1"))
            assertTrue(frames.joinToString().contains("lookup"))
            val usage = events.mapNotNull { event ->
                (event["usage"] as? JsonObject) ?: (event["response"] as? JsonObject)?.get("usage") as? JsonObject
            }.last()
            val inputField = if (client == GatewayProtocol.CHAT_COMPLETIONS) "prompt_tokens" else "input_tokens"
            assertEquals(if (client == GatewayProtocol.MESSAGES) "3" else "9", usage[inputField]!!.jsonPrimitive.content)
            assertFalse(frames.joinToString().contains("response.failed"))
        } }
    }

    @Test
    fun `chat finish chunk text is emitted exactly once before terminal with done marker or EOF`() {
        listOf(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES).forEach { client ->
            listOf(true, false).forEach { useDoneMarker ->
                listOf("stop", "length", "content_filter").forEach { finishReason ->
                    val converter = ProtocolConverterRegistry.get(client, GatewayProtocol.CHAT_COMPLETIONS).streamConverter("public")
                    val prefix = converter.feed("""data: {"id":"reply","choices":[{"delta":{"content":"before "},"finish_reason":null}]}""") + converter.feed("")
                    val finishChunk = converter.feed("""data: {"id":"reply","choices":[{"delta":{"content":"last"},"finish_reason":"$finishReason"}]}""") + converter.feed("")
                    assertTrue(finishChunk.joinToString().contains("last"))
                    assertFalse(finishChunk.joinToString().contains("response.completed"))
                    assertFalse(finishChunk.joinToString().contains("message_stop"))
                    val events = finishChatStream(converter, prefix + finishChunk, useDoneMarker)
                    val text = events.joinToString("") { event ->
                        if (client == GatewayProtocol.MESSAGES) (event["delta"] as? JsonObject)?.string("text").orEmpty()
                        else if (event.string("type") == "response.output_text.delta") event.string("delta").orEmpty() else ""
                    }
                    assertEquals("before last", text, "$client, done=$useDoneMarker, reason=$finishReason")
                    if (client == GatewayProtocol.RESPONSES) {
                        val terminal = events.last()["response"]!!.jsonObject
                        assertEquals("before last", terminal["output"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
                        assertEquals(if (finishReason == "stop") "completed" else "incomplete", terminal.string("status"))
                    } else {
                        assertEquals(when (finishReason) {
                            "length" -> "max_tokens"
                            "content_filter" -> "refusal"
                            else -> "end_turn"
                        }, events.first { it.string("type") == "message_delta" }["delta"]!!.jsonObject.string("stop_reason"))
                    }
                }
            }
        }
    }

    @Test
    fun `chat finish chunk tool arguments are emitted exactly once with done marker or EOF`() {
        listOf(GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES).forEach { client ->
            listOf(true, false).forEach { useDoneMarker ->
                listOf(true, false).forEach { fragmented ->
                    val converter = ProtocolConverterRegistry.get(client, GatewayProtocol.CHAT_COMPLETIONS).streamConverter("public")
                    val prefix = if (fragmented) {
                        converter.feed("""data: {"id":"reply","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{\"city\":"}}]},"finish_reason":null}]}""") + converter.feed("")
                    } else emptyList()
                    val tool = if (fragmented) """{"index":0,"function":{"arguments":"\"SZ\"}"}}"""
                        else """{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{}"}}"""
                    val finishChunk = converter.feed("""data: {"id":"reply","choices":[{"delta":{"tool_calls":[$tool]},"finish_reason":"tool_calls"}]}""") + converter.feed("")
                    val events = finishChatStream(converter, prefix + finishChunk, useDoneMarker)
                    val arguments = events.joinToString("") { event ->
                        if (client == GatewayProtocol.MESSAGES) (event["delta"] as? JsonObject)?.string("partial_json").orEmpty()
                        else if (event.string("type") == "response.function_call_arguments.delta") event.string("delta").orEmpty() else ""
                    }
                    val expected = if (fragmented) "{\"city\":\"SZ\"}" else "{}"
                    assertEquals(expected, arguments, "$client, done=$useDoneMarker, fragmented=$fragmented")
                    if (client == GatewayProtocol.RESPONSES) {
                        val toolOutput = events.last()["response"]!!.jsonObject["output"]!!.jsonArray.single().jsonObject
                        assertEquals(expected, toolOutput.string("arguments"))
                        assertEquals("call_1", toolOutput.string("call_id"))
                        assertEquals("lookup", toolOutput.string("name"))
                    }
                }
            }
        }
    }

    private fun finishChatStream(converter: ProtocolStreamConverter, prefix: List<String>, useDoneMarker: Boolean): List<JsonObject> {
        val usage = converter.feed("""data: {"id":"reply","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3}}""") + converter.feed("")
        val terminal = if (useDoneMarker) converter.feed("data: [DONE]") + converter.finish() else converter.finish()
        val events = (prefix + usage + terminal).filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }
        assertEquals(1, events.count { it.string("type") in setOf("message_stop", "response.completed", "response.incomplete") })
        val finalUsage = events.mapNotNull { event ->
            (event["usage"] as? JsonObject) ?: (event["response"] as? JsonObject)?.get("usage") as? JsonObject
        }.last()
        assertEquals("7", finalUsage["input_tokens"]!!.jsonPrimitive.content)
        assertEquals("3", finalUsage["output_tokens"]!!.jsonPrimitive.content)
        assertTrue(converter.finish().isEmpty())
        assertTrue(converter.feed("data: [DONE]").isEmpty())
        return events
    }

    private fun toolStream(protocol: GatewayProtocol): List<String> = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> listOf(
            """{"id":"reply","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"lookup","arguments":"{\"city\":"}}]},"finish_reason":null}]}""",
            """{"id":"reply","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"SZ\"}"}}]},"finish_reason":null}]}""",
            """{"id":"reply","choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":9,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":4,"cache_write_tokens":2}}}""",
            "[DONE]",
        )
        GatewayProtocol.MESSAGES -> listOf(
            """{"type":"message_start","message":{"id":"reply","usage":{"input_tokens":3,"cache_read_input_tokens":4,"cache_creation_input_tokens":2}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_1","name":"lookup","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"SZ\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        )
        GatewayProtocol.RESPONSES -> listOf(
            """{"type":"response.created","response":{"id":"reply"}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"lookup","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"city\":"}""",
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"\"SZ\"}"}""",
            """{"type":"response.completed","response":{"id":"reply","usage":{"input_tokens":9,"output_tokens":5,"input_tokens_details":{"cached_tokens":4,"cache_write_tokens":2}}}}""",
        )
        else -> error("No Gemini conversion")
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
