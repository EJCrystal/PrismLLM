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
import kotlin.test.assertTrue

/** 工具结果里的图片跨协议转换：Chat 的 tool 消息只能放文本，图片要挪到下一条 user 消息。 */
class ToolImageConversionTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun convert(from: GatewayProtocol, to: GatewayProtocol, text: String): JsonObject =
        ProtocolConversion.convertRequest(from, to, json(text), "upstream")

    private fun JsonObject.messages(): List<JsonObject> = this["messages"]!!.jsonArray.map { it.jsonObject }

    private fun List<JsonObject>.roles(): List<String> = map { it["role"]!!.jsonPrimitive.content }

    private fun JsonObject.text(): String = this["content"]!!.jsonPrimitive.content

    private fun JsonObject.parts(): List<JsonObject> = this["content"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.blocks(): List<JsonObject> =
        (this["content"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    private fun JsonObject.imageUrls(): List<String> = parts()
        .filter { it["type"]?.jsonPrimitive?.content == "image_url" }
        .map { it["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content }

    private val pngA = "aGVsbG8="
    private val pngB = "d29ybGQhIQ=="

    private val singleImageResult = """{"type":"tool_result","tool_use_id":"toolu_1","content":[
        {"type":"text","text":"Read shot.png"},
        {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$pngA"}}
    ]}"""

    @Test
    fun `messages tool result image reaches a chat model`() {
        for (after in listOf(""",{"type":"text","text":"<system-reminder>keep going</system-reminder>"}""", "")) {
            val converted = convert(
                GatewayProtocol.MESSAGES,
                GatewayProtocol.CHAT_COMPLETIONS,
                """{"model":"m","max_tokens":64,"messages":[
                    {"role":"user","content":"look at shot.png"},
                    {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"shot.png"}}]},
                    {"role":"user","content":[$singleImageResult$after]}
                ]}""",
            )
            val messages = converted.messages()
            assertEquals(listOf("user", "assistant", "tool", "user"), messages.roles())
            assertEquals(
                "Read shot.png\n\n[The tool returned an image; it follows in the next message.]",
                messages[2].text(),
            )
            val user = messages[3].parts()
            assertEquals("text", user[0]["type"]!!.jsonPrimitive.content)
            assertTrue(user[0]["text"]!!.jsonPrimitive.content.contains("Read (tool call toolu_1)"))
            assertEquals(listOf("data:image/png;base64,$pngA"), messages[3].imageUrls())
            if (after.isNotEmpty()) {
                assertEquals(
                    "<system-reminder>keep going</system-reminder>",
                    user.last()["text"]!!.jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `parallel tool result images follow all tool messages`() {
        val converted = convert(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.CHAT_COMPLETIONS,
            """{"model":"m","max_tokens":64,"messages":[
                {"role":"user","content":"compare the two"},
                {"role":"assistant","content":[
                    {"type":"tool_use","id":"toolu_a","name":"screenshot","input":{}},
                    {"type":"tool_use","id":"toolu_b","name":"Read","input":{"file_path":"b.png"}}
                ]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_a","content":[
                        {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$pngA"}}
                    ]},
                    {"type":"tool_result","tool_use_id":"toolu_b","content":[
                        {"type":"text","text":"b.png"},
                        {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"$pngB"}},
                        {"type":"image","source":{"type":"url","url":"https://example.com/b2.png"}}
                    ]}
                ]}
            ]}""",
        )
        val messages = converted.messages()
        assertEquals(listOf("user", "assistant", "tool", "tool", "user"), messages.roles())
        assertEquals("[The tool returned an image; it follows in the next message.]", messages[2].text())
        assertTrue(messages[3].text().startsWith("b.png\n\n[The tool returned 2 images;"))
        assertEquals(
            listOf("data:image/png;base64,$pngA", "data:image/jpeg;base64,$pngB", "https://example.com/b2.png"),
            messages[4].imageUrls(),
        )
        val labels = messages[4].parts().filter { it["type"]!!.jsonPrimitive.content == "text" }
            .map { it["text"]!!.jsonPrimitive.content }
        assertEquals(2, labels.size)
        assertTrue(labels[0].contains("screenshot (tool call toolu_a)"))
        assertTrue(labels[1].contains("Read (tool call toolu_b)"))
    }

    @Test
    fun `older turn tool result image stays before the assistant reply`() {
        val converted = convert(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.CHAT_COMPLETIONS,
            """{"model":"m","max_tokens":64,"messages":[
                {"role":"user","content":"take a screenshot"},
                {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"screenshot","input":{}}]},
                {"role":"user","content":[$singleImageResult]},
                {"role":"assistant","content":[{"type":"text","text":"It shows a login page."}]},
                {"role":"user","content":"what colour was the button?"}
            ]}""",
        )
        val messages = converted.messages()
        assertEquals(listOf("user", "assistant", "tool", "user", "assistant", "user"), messages.roles())
        assertEquals(listOf("data:image/png;base64,$pngA"), messages[3].imageUrls())
        assertEquals("what colour was the button?", messages[5].text())
    }

    @Test
    fun `tool result image before an assistant tool call keeps a valid turn order`() {
        val converted = convert(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.CHAT_COMPLETIONS,
            """{"model":"m","max_tokens":64,"messages":[
                {"role":"user","content":"take a screenshot"},
                {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"screenshot","input":{}}]},
                {"role":"user","content":[$singleImageResult]},
                {"role":"assistant","content":[{"type":"tool_use","id":"toolu_2","name":"Read","input":{"file_path":"shot.png"}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_2","content":"file contents"}]},
                {"role":"assistant","content":[{"type":"text","text":"done"}]}
            ]}""",
        )
        val messages = converted.messages()
        assertEquals(listOf("user", "assistant", "tool", "user", "assistant", "tool", "assistant"), messages.roles())
        // 图片单独成一条 user 消息，位置在第二条 assistant 之前，且不与任何 user 消息相邻。
        assertEquals(listOf("data:image/png;base64,$pngA"), messages[3].imageUrls())
        assertEquals("toolu_2", messages[4]["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("file contents", messages[5].text())
        assertTrue(
            messages.zipWithNext().none { (left, right) ->
                left["role"]!!.jsonPrimitive.content == "user" && right["role"]!!.jsonPrimitive.content == "user"
            },
        )
    }

    @Test
    fun `responses tool output image reaches a chat model`() {
        val converted = convert(
            GatewayProtocol.RESPONSES,
            GatewayProtocol.CHAT_COMPLETIONS,
            """{"model":"m","input":[
                {"role":"user","content":[{"type":"input_text","text":"look"}]},
                {"type":"function_call","call_id":"call_1","name":"view_image","arguments":"{\"path\":\"a.png\"}"},
                {"type":"function_call_output","call_id":"call_1","output":[
                    {"type":"input_text","text":"a.png"},
                    {"type":"input_image","image_url":"data:image/png;base64,$pngA"}
                ]}
            ]}""",
        )
        val messages = converted.messages()
        assertEquals(listOf("user", "assistant", "tool", "user"), messages.roles())
        assertTrue(messages[2].text().contains("a.png"))
        assertTrue(messages[2].text().contains("it follows in the next message"))
        assertEquals(listOf("data:image/png;base64,$pngA"), messages[3].imageUrls())
        assertTrue(
            messages[3].parts()[0]["text"]!!.jsonPrimitive.content.contains("view_image (tool call call_1)"),
        )
    }

    @Test
    fun `messages tool result image reaches a responses model`() {
        val converted = convert(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.RESPONSES,
            """{"model":"m","max_tokens":64,"messages":[
                {"role":"user","content":"look"},
                {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}]},
                {"role":"user","content":[$singleImageResult]}
            ]}""",
        )
        val output = converted["input"]!!.jsonArray.map { it.jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == "function_call_output" }
        val parts = output["output"]!!.jsonArray.map { it.jsonObject }
        assertEquals("input_text", parts[0]["type"]!!.jsonPrimitive.content)
        assertEquals("Read shot.png", parts[0]["text"]!!.jsonPrimitive.content)
        assertEquals("input_image", parts[1]["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,$pngA", parts[1]["image_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `messages tool result image reaches a gemini model`() {
        val converted = convert(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            """{"model":"m","max_tokens":64,"messages":[
                {"role":"user","content":"look"},
                {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}]},
                {"role":"user","content":[$singleImageResult]}
            ]}""",
        )
        val response = converted["contents"]!!.jsonArray.map { it.jsonObject }
            .map { it["parts"]!!.jsonArray.map { part -> part.jsonObject } }
            .flatten()
            .single { it["functionResponse"] != null }["functionResponse"]!!.jsonObject
        assertEquals("Read", response["name"]!!.jsonPrimitive.content)
        val image = response["parts"]!!.jsonArray.single().jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
        assertEquals(pngA, image["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini function response images return to messages and chat`() {
        val request = """{"contents":[
            {"role":"user","parts":[{"text":"look"}]},
            {"role":"model","parts":[{"functionCall":{"name":"Read","args":{}}}]},
            {"role":"user","parts":[{"functionResponse":{"name":"Read","response":{"content":"shot"},"parts":[
                {"inlineData":{"mimeType":"image/png","data":"$pngA"}}
            ]}}]}
        ]}"""
        val messages = convert(GatewayProtocol.GEMINI, GatewayProtocol.MESSAGES, request)
        val result = messages.messages().flatMap { it.blocks() }
            .single { it["type"]!!.jsonPrimitive.content == "tool_result" }
        val blocks = result["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("shot", blocks[0]["text"]!!.jsonPrimitive.content)
        assertEquals("image", blocks[1]["type"]!!.jsonPrimitive.content)
        assertEquals(pngA, blocks[1]["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)

        val chat = convert(GatewayProtocol.GEMINI, GatewayProtocol.CHAT_COMPLETIONS, request)
        val chatMessages = chat.messages()
        assertEquals(listOf("user", "assistant", "tool", "user"), chatMessages.roles())
        assertEquals("shot\n\n[The tool returned an image; it follows in the next message.]", chatMessages[2].text())
        assertEquals(listOf("data:image/png;base64,$pngA"), chatMessages[3].imageUrls())

        val responses = convert(GatewayProtocol.GEMINI, GatewayProtocol.RESPONSES, request)
        val output = responses["input"]!!.jsonArray.map { it.jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == "function_call_output" }["output"]!!.jsonArray
            .map { it.jsonObject }
        assertEquals("input_text", output[0]["type"]!!.jsonPrimitive.content)
        assertEquals("shot", output[0]["text"]!!.jsonPrimitive.content)
        assertEquals("input_image", output[1]["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,$pngA", output[1]["image_url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat tool content with an image converts to an anthropic tool result`() {
        val converted = convert(
            GatewayProtocol.CHAT_COMPLETIONS,
            GatewayProtocol.MESSAGES,
            """{"model":"m","messages":[
                {"role":"user","content":"look"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"Read","arguments":"{}"}}]},
                {"role":"tool","tool_call_id":"call_1","content":[
                    {"type":"text","text":"shot"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,$pngA"}}
                ]}
            ]}""",
        )
        val result = converted.messages().flatMap { it.blocks() }
            .single { it["type"]!!.jsonPrimitive.content == "tool_result" }
        val blocks = result["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("shot", blocks[0]["text"]!!.jsonPrimitive.content)
        assertEquals("image", blocks[1]["type"]!!.jsonPrimitive.content)
        assertEquals(pngA, blocks[1]["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat user image reaches a gemini model`() {
        val converted = convert(
            GatewayProtocol.CHAT_COMPLETIONS,
            GatewayProtocol.GEMINI,
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"text","text":"look"},
                {"type":"image_url","image_url":{"url":"https://example.com/a.png"}}
            ]}]}""",
        )
        val parts = converted["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("look", parts[0]["text"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/a.png", parts[1]["fileData"]!!.jsonObject["fileUri"]!!.jsonPrimitive.content)
    }
}
