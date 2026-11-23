package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningCarryTest {
    private class MemoryStore : ReasoningCarryStore {
        val entries = linkedMapOf<String, String>()

        override fun keep(payload: String): String {
            val key = "k" + entries.size.toString().padStart(15, '0')
            entries[key] = payload
            return key
        }

        override fun recall(key: String): String? = entries[key]
    }

    @Test
    fun `sealed reasoning rides in the tool call id and returns on the next messages request`() {
        val store = MemoryStore()
        val converted = responsesToMessagesResponse(json("""{"id":"resp_1","output":[
            {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque"},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"calling"}]},
            {"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}
        ]}"""), "public", store)

        val tool = converted["content"]!!.jsonArray.single { it.jsonObject.string("type") == "tool_use" }
        val id = tool.jsonObject.string("id")!!
        assertTrue(id.startsWith("call_1__rk16__"), id)
        assertEquals(1, store.entries.size)

        val upstream = messagesToResponsesRequest(json("""{"model":"public","max_tokens":64,"messages":[
            {"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"lookup","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"done"}]}
        ]}"""), "upstream", store)

        val input = upstream["input"]!!.jsonArray
        assertEquals(listOf("reasoning", "function_call", "function_call_output"), input.map { it.jsonObject.string("type") })
        assertEquals("opaque", input[0].jsonObject.string("encrypted_content"))
        assertEquals("call_1", input[1].jsonObject.string("call_id"))
        assertEquals("call_1", input[2].jsonObject.string("call_id"))
    }

    @Test
    fun `streamed sealed reasoning attaches to the following tool call id`() {
        val store = MemoryStore()
        val converter = ProtocolConversion.streamConverter(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.RESPONSES,
            "public",
            carry = store,
        )!!
        val frames = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
            """{"type":"response.reasoning_summary_text.delta","delta":"think"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque"}}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}}""",
            """{"type":"response.completed","response":{"id":"resp_1","output":[],"usage":{"input_tokens":1,"output_tokens":2}}}""",
        ).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
        val events = frames.filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }

        val start = events.single {
            it.string("type") == "content_block_start" && (it["content_block"] as? JsonObject)?.string("type") == "tool_use"
        }
        val id = start["content_block"]!!.jsonObject.string("id")!!
        assertTrue(id.startsWith("call_1__rk16__"), id)
        assertEquals(1, store.entries.size)
    }

    @Test
    fun `a suffix whose reasoning is gone restores the call id without replaying an item`() {
        val store = MemoryStore()
        val upstream = messagesToResponsesRequest(json("""{"model":"public","max_tokens":64,"messages":[
            {"role":"assistant","content":[{"type":"tool_use","id":"call_1__rk16__AAAAAAAAAAAAAAAA","name":"lookup","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1__rk16__AAAAAAAAAAAAAAAA","content":"done"}]}
        ]}"""), "upstream", store)

        val input = upstream["input"]!!.jsonArray
        assertEquals(listOf("function_call", "function_call_output"), input.map { it.jsonObject.string("type") })
        assertEquals("call_1", input[0].jsonObject.string("call_id"))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `chat sealed reasoning round trips through tool call ids`() {
        val store = MemoryStore()
        val converted = responsesToChatResponse(json("""{"id":"resp_1","output":[
            {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque"},
            {"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}
        ]}"""), "public", store)

        val message = converted["choices"]!!.jsonArray.single().jsonObject["message"]!!.jsonObject
        val id = message["tool_calls"]!!.jsonArray.single().jsonObject.string("id")!!
        assertTrue(id.startsWith("call_1__rk16__"), id)

        val upstream = chatToResponsesRequest(json("""{"model":"public","messages":[
            {"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"$id","content":"done"}
        ]}"""), "upstream", store)

        val input = upstream["input"]!!.jsonArray
        assertEquals(listOf("reasoning", "function_call", "function_call_output"), input.map { it.jsonObject.string("type") })
        assertEquals("opaque", input[0].jsonObject.string("encrypted_content"))
        assertEquals("call_1", input[1].jsonObject.string("call_id"))
        assertEquals("call_1", input[2].jsonObject.string("call_id"))
    }

    @Test
    fun `converted requests ask for encrypted reasoning only on thinking tool turns`() {
        val withThinking = json("""{"messages":[{"role":"user","content":"hi"}],"max_tokens":64,
            "thinking":{"type":"enabled","budget_tokens":2048},
            "tools":[{"name":"lookup","input_schema":{"type":"object"}}]}""")
        val include = messagesToResponsesRequest(withThinking, "m")["include"]!!.jsonArray
        assertEquals(listOf("reasoning.encrypted_content"), include.map { it.jsonPrimitive.content })

        val withoutThinking = json("""{"messages":[{"role":"user","content":"hi"}],"max_tokens":64,
            "tools":[{"name":"lookup","input_schema":{"type":"object"}}]}""")
        assertFalse(messagesToResponsesRequest(withoutThinking, "m").containsKey("include"))

        val chat = json("""{"messages":[{"role":"user","content":"hi"}],"reasoning_effort":"high",
            "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}]}""")
        assertEquals(1, chatToResponsesRequest(chat, "m")["include"]!!.jsonArray.size)
    }

    @Test
    fun `gemini stream emits thinking_delta signature_delta and carries functionCall thoughtSignature across rounds`() {
        val store = MemoryStore()
        val converter = ProtocolConversion.streamConverter(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            "gemini-4-hydrogen",
            carry = store,
        )!!
        val frames = listOf(
            """{"responseId":"resp_g1","candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"step 1 "}]}}]}""",
            """{"responseId":"resp_g1","candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"step 2","thoughtSignature":"sig_think_1"}]}}]}""",
            """{"responseId":"resp_g1","candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call_g1","name":"lookup","args":{"q":"weather"}},"thoughtSignature":"sig_tool_1"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"thoughtsTokenCount":20,"totalTokenCount":35}}""",
        ).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
        val events = frames.filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }

        val starts = events.filter { it.string("type") == "content_block_start" }
        assertEquals(listOf("thinking", "tool_use"), starts.map { it["content_block"]!!.jsonObject.string("type") })

        val deltas = events.filter { it.string("type") == "content_block_delta" }.map { it["delta"]!!.jsonObject }
        val thinkingText = deltas.filter { it.string("type") == "thinking_delta" }.joinToString("") { it.string("thinking").orEmpty() }
        assertEquals("step 1 step 2", thinkingText)
        val sigDeltas = deltas.filter { it.string("type") == "signature_delta" }.map { it.string("signature") }
        assertEquals(listOf("sig_think_1"), sigDeltas)

        // signature_delta 必须出现在 thinking 块的 content_block_stop 之前。
        val sigIdx = events.indexOfFirst { (it["delta"] as? JsonObject)?.string("type") == "signature_delta" }
        val thinkStopIdx = events.indexOfFirst { it.string("type") == "content_block_stop" && it.long("index") == 0L }
        assertTrue(sigIdx in 0 until thinkStopIdx, "sigIdx=$sigIdx thinkStopIdx=$thinkStopIdx")

        val carriedToolId = starts[1]["content_block"]!!.jsonObject.string("id")!!
        assertTrue(carriedToolId.startsWith("call_g1__gs16__"), carriedToolId)

        // 下一轮 Anthropic Messages 请求带回 thinking + tool_use(carriedToolId) + tool_result：
        // 应还原真实 thoughtSignature，且不退回 skip_thought_signature_validator。
        val nextReq = ProtocolConversion.convertRequest(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            json("""{
                "model":"gemini-4-hydrogen",
                "max_tokens":1024,
                "messages":[
                    {"role":"user","content":"weather?"},
                    {"role":"assistant","content":[
                        {"type":"thinking","thinking":"step 1 step 2","signature":"sig_think_1"},
                        {"type":"tool_use","id":"$carriedToolId","name":"lookup","input":{"q":"weather"}}
                    ]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"$carriedToolId","content":"sunny"}]}
                ]
            }"""),
            "gemini-4-hydrogen-high",
            carry = store,
        )
        val modelParts = nextReq["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        assertEquals(2, modelParts.size)
        assertEquals(true, modelParts[0].jsonObject.boolean("thought"))
        assertEquals("sig_think_1", modelParts[0].jsonObject.string("thoughtSignature"))
        assertEquals("sig_tool_1", modelParts[1].jsonObject.string("thoughtSignature"))
        val funcResp = nextReq["contents"]!!.jsonArray[2].jsonObject["parts"]!!.jsonArray.single().jsonObject["functionResponse"]!!.jsonObject
        assertEquals("lookup", funcResp.string("name"))
    }

    @Test
    fun `gemini trailing thoughtSignature on text part is preserved as a signed thinking block`() {
        val converter = ProtocolConversion.streamConverter(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            "gemini-4-hydrogen",
        )!!
        val frames = listOf(
            """{"responseId":"resp_g2","candidates":[{"content":{"role":"model","parts":[{"text":"hello","thoughtSignature":"sig_trailing"}]},"finishReason":"STOP"}]}""",
        ).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
        val events = frames.filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }

        val starts = events.filter { it.string("type") == "content_block_start" }
        assertEquals(listOf("text", "thinking"), starts.map { it["content_block"]!!.jsonObject.string("type") })
        val sigDeltas = events.mapNotNull { (it["delta"] as? JsonObject)?.takeIf { d -> d.string("type") == "signature_delta" }?.string("signature") }
        assertEquals(listOf("sig_trailing"), sigDeltas)
    }

    @Test
    fun `gemini stream emits reasoning_content for chat completions and carries functionCall thoughtSignature`() {
        val store = MemoryStore()
        val converter = ProtocolConversion.streamConverter(
            GatewayProtocol.CHAT_COMPLETIONS,
            GatewayProtocol.GEMINI,
            "gemini-4-hydrogen",
            carry = store,
        )!!
        val frames = listOf(
            """{"responseId":"resp_c1","candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"plan"}]}}]}""",
            """{"responseId":"resp_c1","candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call_c1","name":"search","args":{"q":"hi"}},"thoughtSignature":"sig_chat_1"}]},"finishReason":"STOP"}]}""",
        ).flatMap { converter.feed("data: $it") + converter.feed("") } + converter.finish()
        val chunks = frames.filter { it.startsWith("data: {") }.map { json(it.removePrefix("data: ")) }
        val deltas = chunks.mapNotNull { it["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject }
        assertEquals("plan", deltas.mapNotNull { it.string("reasoning_content") }.joinToString(""))
        val carriedId = deltas.firstNotNullOf { it["tool_calls"]?.jsonArray?.firstOrNull()?.jsonObject?.string("id") }
        assertTrue(carriedId.startsWith("call_c1__gs16__"), carriedId)

        val nextReq = ProtocolConversion.convertRequest(
            GatewayProtocol.CHAT_COMPLETIONS,
            GatewayProtocol.GEMINI,
            json("""{
                "model":"gemini-4-hydrogen",
                "messages":[
                    {"role":"user","content":"hi"},
                    {"role":"assistant","content":null,"tool_calls":[{"id":"$carriedId","type":"function","function":{"name":"search","arguments":"{\"q\":\"hi\"}"}}]},
                    {"role":"tool","tool_call_id":"$carriedId","content":"ok"}
                ]
            }"""),
            "gemini-4-hydrogen-high",
            carry = store,
        )
        val fnPart = nextReq["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals("sig_chat_1", fnPart.string("thoughtSignature"))
    }

    @Test
    fun `antigravity claude model on gemini protocol degrades unsigned thinking and refuses dummy signature`() {
        val store = MemoryStore()
        val request = json("""{
            "model":"claude-sonnet-5-5",
            "max_tokens":4096,
            "thinking":{"type":"enabled","budget_tokens":2048},
            "messages":[
                {"role":"user","content":"hi"},
                {"role":"assistant","content":[
                    {"type":"thinking","thinking":"unsigned thought","signature":""},
                    {"type":"tool_use","id":"toolu_1","name":"lookup","input":{}}
                ]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"ok"}]}
            ]
        }""")
        val converted = ProtocolConversion.convertRequest(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            request,
            "claude-sonnet-5-5-high",
            carry = store,
        )
        val modelParts = converted["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        // 无签名 thinking 被降级为普通 text，不带 thought=true；tool_use 不注入 skip_thought_signature_validator。
        assertEquals("unsigned thought", modelParts[0].jsonObject.string("text"))
        assertFalse(modelParts[0].jsonObject.containsKey("thought"))
        assertFalse(modelParts[1].jsonObject.containsKey("thoughtSignature"))
        // 因历史 thinking 被降级，本轮 thinkingConfig 被禁用避免上游 400。
        val genConfig = converted["generationConfig"]?.jsonObject
        assertFalse(genConfig?.containsKey("thinkingConfig") == true)
    }

    @Test
    fun `antigravity claude model preserves real thinking signature and tool signature`() {
        val store = MemoryStore()
        val resp = ProtocolConversion.convertResponse(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            json("""{"responseId":"r1","candidates":[{"content":{"role":"model","parts":[
                {"thought":true,"text":"real thought","thoughtSignature":"real_sig_t"},
                {"functionCall":{"id":"call_1","name":"lookup","args":{}},"thoughtSignature":"real_sig_f"}
            ]},"finishReason":"STOP"}]}"""),
            "claude-sonnet-5-5",
            carry = store,
        )
        val carriedId = resp["content"]!!.jsonArray[1].jsonObject.string("id")!!
        val nextReq = ProtocolConversion.convertRequest(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            json("""{
                "model":"claude-sonnet-5-5",
                "max_tokens":4096,
                "thinking":{"type":"enabled","budget_tokens":2048},
                "messages":[
                    {"role":"user","content":"hi"},
                    {"role":"assistant","content":[
                        {"type":"thinking","thinking":"real thought","signature":"real_sig_t"},
                        {"type":"tool_use","id":"$carriedId","name":"lookup","input":{}}
                    ]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"$carriedId","content":"ok"}]}
                ]
            }"""),
            "claude-sonnet-5-5-high",
            carry = store,
        )
        val modelParts = nextReq["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        assertEquals(true, modelParts[0].jsonObject.boolean("thought"))
        assertEquals("real_sig_t", modelParts[0].jsonObject.string("thoughtSignature"))
        assertEquals("real_sig_f", modelParts[1].jsonObject.string("thoughtSignature"))
        assertTrue(nextReq["generationConfig"]!!.jsonObject.containsKey("thinkingConfig"))
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
