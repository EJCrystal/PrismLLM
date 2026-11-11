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

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
