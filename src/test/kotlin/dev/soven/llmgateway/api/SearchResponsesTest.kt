package dev.soven.llmgateway.api

import dev.soven.llmgateway.proxy.Usage
import dev.soven.llmgateway.proxy.search.ConversationPart
import dev.soven.llmgateway.proxy.search.SearchConversationResult
import dev.soven.llmgateway.proxy.search.SearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchResponsesTest {
    private val result = SearchConversationResult(
        parts = listOf(
            ConversationPart.Text("Let me check."),
            ConversationPart.Search(
                "latest go",
                listOf(SearchHit("Go downloads", "https://go.dev/dl/", "Go 1.27.1")),
            ),
            ConversationPart.Text("Go 1.27.1 is out."),
        ),
        usage = Usage(input = 70, output = 12, cached = null, reasoning = null, total = 82),
        finishReason = "stop",
    )

    @Test
    fun `renders web search items in order`() {
        val json = renderSearchResponse(result, "deepseek-flash", "resp_test", 1_700_000_000)
        val output = json["output"]!!.jsonArray
        assertEquals(listOf("message", "web_search_call", "message"), output.map { it.jsonObject["type"]!!.toString().trim('"') })
        val search = output[1].jsonObject
        assertEquals("latest go", search["action"]!!.jsonObject["query"]!!.toString().trim('"'))
        assertEquals(
            "https://go.dev/dl/",
            search["action"]!!.jsonObject["sources"]!!.jsonArray[0].jsonObject["url"]!!.toString().trim('"'),
        )
        assertEquals(70L, (json["usage"]!!.jsonObject["input_tokens"] as JsonPrimitive).long)
    }

    @Test
    fun `follow-up rounds downgrade a required tool choice`() {
        val required = Json.parseToJsonElement("""{"model":"m","tool_choice":"required"}""").jsonObject
        assertEquals("auto", withAutoToolChoice(required, "web_search")["tool_choice"]!!.jsonPrimitive.content)

        val auto = Json.parseToJsonElement("""{"model":"m","tool_choice":"auto"}""").jsonObject
        assertEquals("auto", withAutoToolChoice(auto, "web_search")["tool_choice"]!!.jsonPrimitive.content)
        assertNull(withAutoToolChoice(Json.parseToJsonElement("""{"model":"m"}""").jsonObject, "web_search")["tool_choice"])
    }

    @Test
    fun `follow-up rounds only downgrade the forced synthetic search tool`() {
        val forcedSearch = Json.parseToJsonElement(
            """{"model":"m","tool_choice":{"type":"function","function":{"name":"web_search"}}}""",
        ).jsonObject
        assertEquals("auto", withAutoToolChoice(forcedSearch, "web_search")["tool_choice"]!!.jsonPrimitive.content)

        val forcedClientTool = Json.parseToJsonElement(
            """{"model":"m","tool_choice":{"type":"function","function":{"name":"read_file"}}}""",
        ).jsonObject
        val kept = withAutoToolChoice(forcedClientTool, "web_search")["tool_choice"]!!.jsonObject
        assertEquals("read_file", kept["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams search completion before the final response`() {
        val lines = renderSearchSse(result, "deepseek-flash", "resp_test", 1_700_000_000)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("response.web_search_call.completed"))
        assertTrue(text.contains("response.output_text.delta"))
        assertTrue(text.contains("response.completed"))
        assertTrue(text.contains("\"sequence_number\":0"))
    }
}
