package dev.soven.prismllm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** 上游代执行的宿主工具项（如内置 web_search）没有客户端可表示的形状：响应与历史都应跳过而不是拒绝。 */
class WebSearchCallConversionTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `chat response skips upstream web search item`() {
        val converted = responsesToChatResponse(json("""{"id":"resp_1","output":[
            {"type":"web_search_call","id":"ws_1","status":"completed"},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"答案"}]}
        ]}"""), "public")

        val choice = converted["choices"]!!.jsonArray.single().jsonObject
        assertEquals("答案", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice.string("finish_reason"))
    }

    @Test
    fun `chat request skips web search history item`() {
        val converted = responsesToChatRequest(json("""{"input":[
            {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
            {"type":"web_search_call","id":"ws_1","status":"completed"}
        ]}"""))

        val messages = converted["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages.single().jsonObject.string("role"))
    }

    @Test
    fun `messages response skips upstream web search item`() {
        val converted = responsesToMessagesResponse(json("""{"id":"resp_1","output":[
            {"type":"web_search_call","id":"ws_1","status":"completed"},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"答案"}]}
        ]}"""), "public")

        val content = converted["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("答案", content.single().jsonObject.string("text"))
        assertEquals("end_turn", converted.string("stop_reason"))
    }

    @Test
    fun `messages request skips web search history item`() {
        val converted = responsesToMessagesRequest(json("""{"input":[
            {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
            {"type":"web_search_call","id":"ws_1","status":"completed"}
        ]}"""), "upstream")

        val messages = converted["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages.single().jsonObject.string("role"))
    }

    @Test
    fun `gemini response skips upstream web search item`() {
        val converted = responsesToGeminiResponse(json("""{"id":"resp_1","output":[
            {"type":"web_search_call","id":"ws_1","status":"completed"},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"答案"}]}
        ]}"""), "public")

        val parts = converted["candidates"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("答案", parts.single().jsonObject.string("text"))
    }

    @Test
    fun `gemini request skips web search history item`() {
        val converted = responsesToGeminiRequest(json("""{"input":[
            {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
            {"type":"web_search_call","id":"ws_1","status":"completed"}
        ]}"""), "upstream")

        val contents = converted["contents"]!!.jsonArray
        assertEquals(1, contents.size)
        assertEquals("user", contents.single().jsonObject.string("role"))
    }
}
