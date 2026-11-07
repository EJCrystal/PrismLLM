package dev.soven.llmgateway.proxy.search

import dev.soven.llmgateway.proxy.protocol.responsesToChatRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchConversationTest {
    private val hit = SearchHit("Go downloads", "https://go.dev/dl/", "Go 1.27.1 is the latest release.")
    private val searchService = WebSearchService(listOf(
        object : WebSearchBackend {
            override val name = "fake"
            override suspend fun search(query: String, limit: Int) = SearchOutput(
                summary = "1. Go downloads — https://go.dev/dl/\nGo 1.27.1 is the latest release.",
                hits = listOf(hit),
            )
        },
    ))

    @Test
    fun `runs a search round and returns the final answer`() = runBlocking {
        val seen = mutableListOf<List<JsonObject>>()
        val result = SearchConversation("web_search", searchService).run(
            listOf(obj("""{"role":"user","content":"What is the latest Go?"}""")),
        ) { messages ->
            seen += messages
            if (seen.size == 1) {
                chat(
                    content = "Let me check.",
                    finish = "tool_calls",
                    input = 30,
                    output = 7,
                    calls = """[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"latest go\"}"}}]""",
                )
            } else {
                chat(content = "Go 1.27.1 is out.", finish = "stop", input = 40, output = 5)
            }
        }

        assertEquals(3, result.parts.size)
        assertEquals(ConversationPart.Text("Let me check."), result.parts[0])
        assertTrue(result.parts[1] is ConversationPart.Search)
        assertEquals("latest go", (result.parts[1] as ConversationPart.Search).query)
        assertEquals("https://go.dev/dl/", (result.parts[1] as ConversationPart.Search).hits.single().url)
        assertEquals(ConversationPart.Text("Go 1.27.1 is out."), result.parts[2])
        assertEquals(70L, result.usage?.input)
        assertEquals(12L, result.usage?.output)

        val secondRound = seen[1]
        val assistant = secondRound.first { it["role"]?.toString()?.contains("assistant") == true }
        assertEquals("web_search", assistant["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.toString().trim('"'))
        val tool = secondRound.last()
        assertEquals("tool", tool["role"]!!.toString().trim('"'))
        assertTrue(tool["content"]!!.toString().contains("Go 1.27.1"))
    }

    @Test
    fun `a client tool call ends the search loop`() = runBlocking {
        var calls = 0
        val result = SearchConversation("web_search", searchService).run(
            listOf(obj("""{"role":"user","content":"Read the file"}""")),
        ) {
            calls += 1
            chat(
                content = "",
                finish = "tool_calls",
                input = 10,
                output = 2,
                calls = """[{"id":"call_read","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]""",
            )
        }

        assertEquals(1, calls)
        assertEquals(listOf(ConversationPart.ToolCall("call_read", "read_file", "{\"path\":\"README.md\"}")), result.parts)
    }

    @Test
    fun `strips built-in web search and adds a plain function tool`() {
        val request = obj(
            """{"model":"m","input":[{"type":"message","role":"user","content":"hi"},{"type":"web_search_call","id":"ws_1"}],"tools":[{"type":"web_search"},{"type":"function","name":"read_file","parameters":{"type":"object"}}]}""",
        )

        val prepared = prepareResponsesSearchRequest(request, "web_search")

        assertEquals(1, prepared["input"]!!.jsonArray.size)
        val tools = prepared["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].jsonObject["name"]!!.toString().trim('"'))
        assertEquals("web_search", tools[1].jsonObject["name"]!!.toString().trim('"'))
        assertEquals("function", tools[1].jsonObject["type"]!!.toString().trim('"'))
    }

    @Test
    fun `renames the synthetic tool when the client already uses the name`() {
        val request = obj(
            """{"model":"m","tools":[{"type":"function","name":"web_search","parameters":{"type":"object"}}]}""",
        )

        assertEquals("magpie_web_search", syntheticWebSearchName(request))
    }

    @Test
    fun `prepared request converts to chat after dropping history search items`() {
        val request = obj(
            """{"model":"m","input":[{"type":"message","role":"user","content":"hi"},{"type":"web_search_call","id":"ws_1"}],"tools":[{"type":"web_search"}]}""",
        )

        val chat = responsesToChatRequest(prepareResponsesSearchRequest(request, "web_search"))

        assertEquals(1, (chat["messages"] as JsonArray).size)
        assertEquals(1, (chat["tools"] as JsonArray).size)
    }

    private fun chat(content: String, finish: String, input: Int, output: Int, calls: String? = null): JsonObject {
        val toolCalls = calls?.let { ",\"tool_calls\":$it" }.orEmpty()
        return obj(
            """{"id":"chat_1","choices":[{"index":0,"message":{"role":"assistant","content":"$content"$toolCalls},"finish_reason":"$finish"}],"usage":{"prompt_tokens":$input,"completion_tokens":$output,"total_tokens":${input + output}}}""",
        )
    }

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}
