package dev.soven.prismllm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MessagesThinkingNormalizerTest {
    private val toolTurn = json("""{"messages":[
        {"role":"user","content":"hi"},
        {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"lookup","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"ok"}]}
    ]}""")

    @Test
    fun `deepseek tool turns without thinking get a placeholder block`() {
        val normalized = MessagesThinkingNormalizer.normalize(toolTurn, "deepseek-v3.2", "https://api.deepseek.com/anthropic")
        val content = normalized["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "tool_use"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(" ", content[0].jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("", content[0].jsonObject["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an assistant answer after tool results alone also gets the placeholder`() {
        val body = json("""{"messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"lookup","input":{}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"ok"}]},
            {"role":"assistant","content":[{"type":"text","text":"done"}]}
        ]}""")
        val normalized = MessagesThinkingNormalizer.normalize(body, "deepseek-v3.2", "https://api.deepseek.com/anthropic")
        val last = normalized["messages"]!!.jsonArray[3].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "text"), last.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `turns that already carry thinking are left alone`() {
        val body = json("""{"messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","content":[
                {"type":"thinking","thinking":"plan","signature":""},
                {"type":"tool_use","id":"call_1","name":"lookup","input":{}}
            ]}
        ]}""")
        assertSame(body, MessagesThinkingNormalizer.normalize(body, "deepseek-v3.2", "https://api.deepseek.com/anthropic"))
    }

    @Test
    fun `redacted thinking becomes the placeholder or is dropped when thinking exists`() {
        val sealed = json("""{"messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","content":[{"type":"redacted_thinking","data":"opaque"},{"type":"text","text":"done"}]}
        ]}""")
        val replaced = MessagesThinkingNormalizer.normalize(sealed, "deepseek-v3.2", "https://api.deepseek.com/anthropic")
        val blocks = replaced["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "text"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })

        val withThinking = json("""{"messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","content":[
                {"type":"thinking","thinking":"plan","signature":""},
                {"type":"redacted_thinking","data":"opaque"},
                {"type":"text","text":"done"}
            ]}
        ]}""")
        val dropped = MessagesThinkingNormalizer.normalize(withThinking, "deepseek-v3.2", "https://api.deepseek.com/anthropic")
        val kept = dropped["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "text"), kept.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `redacted thinking before a real thinking block is dropped without a placeholder`() {
        val body = json("""{"messages":[
            {"role":"user","content":"hi"},
            {"role":"assistant","content":[
                {"type":"redacted_thinking","data":"opaque"},
                {"type":"thinking","thinking":"plan","signature":""},
                {"type":"text","text":"done"}
            ]}
        ]}""")
        val normalized = MessagesThinkingNormalizer.normalize(body, "deepseek-v3.2", "https://api.deepseek.com/anthropic")
        val blocks = normalized["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "text"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("plan", blocks[0].jsonObject["thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun `official anthropic and other vendors keep their requests byte for byte`() {
        assertSame(toolTurn, MessagesThinkingNormalizer.normalize(toolTurn, "claude-sonnet-4-5", "https://api.anthropic.com"))
        assertSame(toolTurn, MessagesThinkingNormalizer.normalize(toolTurn, "claude-sonnet-4-5", "https://some-relay.example.com"))
        assertSame(toolTurn, MessagesThinkingNormalizer.normalize(toolTurn, "gpt-5-codex", "https://api.openai.com"))
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
