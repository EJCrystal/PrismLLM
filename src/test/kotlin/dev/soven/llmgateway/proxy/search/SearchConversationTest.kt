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
            call = { messages ->
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
            },
        )

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
    fun `uses the follow-up caller after the first round`() = runBlocking {
        var firstCalls = 0
        var followUpCalls = 0
        val result = SearchConversation("web_search", searchService).run(
            listOf(obj("""{"role":"user","content":"What is the latest Go?"}""")),
            call = {
                firstCalls += 1
                chat(
                    content = "",
                    finish = "tool_calls",
                    input = 10,
                    output = 2,
                    calls = """[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"latest go\"}"}}]""",
                )
            },
            followUpCall = {
                followUpCalls += 1
                chat(content = "Go 1.27.1 is out.", finish = "stop", input = 20, output = 3)
            },
        )

        assertEquals(1, firstCalls)
        assertEquals(1, followUpCalls)
        assertEquals(ConversationPart.Text("Go 1.27.1 is out."), result.parts.last())
    }

    @Test
    fun `a client tool call ends the search loop`() = runBlocking {
        var calls = 0
        val result = SearchConversation("web_search", searchService).run(
            listOf(obj("""{"role":"user","content":"Read the file"}""")),
            call = {
                calls += 1
                chat(
                    content = "",
                    finish = "tool_calls",
                    input = 10,
                    output = 2,
                    calls = """[{"id":"call_read","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]""",
                )
            },
        )

        assertEquals(1, calls)
        assertEquals(listOf(ConversationPart.ToolCall("call_read", "read_file", "{\"path\":\"README.md\"}")), result.parts)
    }

    @Test
    fun `caps the number of searches executed in one round`() = runBlocking {
        var searches = 0
        val countingService = WebSearchService(listOf(
            object : WebSearchBackend {
                override val name = "counting"
                override suspend fun search(query: String, limit: Int): SearchOutput {
                    searches += 1
                    return SearchOutput("result for $query", listOf(hit))
                }
            },
        ))
        val calls = (1..6).joinToString(prefix = "[", postfix = "]") { index ->
            """{"id":"call_$index","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"q$index\"}"}}"""
        }
        val seen = mutableListOf<List<JsonObject>>()
        val result = SearchConversation("web_search", countingService).run(
            listOf(obj("""{"role":"user","content":"search many things"}""")),
            call = { messages ->
                seen += messages
                if (seen.size == 1) {
                    chat(content = "", finish = "tool_calls", input = 10, output = 2, calls = calls)
                } else {
                    chat(content = "done", finish = "stop", input = 10, output = 2)
                }
            },
        )

        assertEquals(5, searches)
        assertEquals(5, result.parts.filterIsInstance<ConversationPart.Search>().size)
        val toolMessages = seen[1].filter { it["role"]?.toString()?.contains("tool") == true }
        assertEquals(6, toolMessages.size)
        assertTrue(toolMessages.any { it["content"]!!.toString().contains("search skipped") })
    }

    @Test
    fun `uses the final caller after the round limit and reports every round usage`() = runBlocking {
        var calls = 0
        var finalCalls = 0
        val usages = mutableListOf<Long?>()
        val result = SearchConversation("web_search", searchService, maxRounds = 0).run(
            listOf(obj("""{"role":"user","content":"What is the latest Go?"}""")),
            call = {
                calls += 1
                chat(
                    content = "",
                    finish = "tool_calls",
                    input = 10,
                    output = 2,
                    calls = """[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"latest go\"}"}}]""",
                )
            },
            finalCall = {
                finalCalls += 1
                chat(content = "Final answer.", finish = "stop", input = 20, output = 3)
            },
            onUsage = { usages += it.total },
        )

        assertEquals(1, calls)
        assertEquals(1, finalCalls)
        assertEquals(ConversationPart.Text("Final answer."), result.parts.last())
        assertEquals(listOf<Long?>(12L, 23L), usages)
        assertEquals(30L, result.usage?.input)
        assertEquals(5L, result.usage?.output)
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
    fun `forced built-in web search becomes a forced synthetic function call`() {
        val request = obj(
            """{"model":"m","input":[{"type":"message","role":"user","content":"hi"}],"tools":[{"type":"web_search"}],"tool_choice":{"type":"web_search_preview"}}""",
        )

        val prepared = prepareResponsesSearchRequest(request, "web_search")
        val choice = prepared["tool_choice"]!!.jsonObject
        assertEquals("function", choice["type"]!!.toString().trim('"'))
        assertEquals("web_search", choice["name"]!!.toString().trim('"'))

        val chat = responsesToChatRequest(prepared)
        val chatChoice = chat["tool_choice"]!!.jsonObject
        assertEquals("web_search", chatChoice["function"]!!.jsonObject["name"]!!.toString().trim('"'))
    }

    @Test
    fun `absent tool choice stays absent so the model decides when to search`() {
        val request = obj(
            """{"model":"m","input":[{"type":"message","role":"user","content":"hi"}],"tools":[{"type":"web_search"}]}""",
        )

        assertEquals(null, prepareResponsesSearchRequest(request, "web_search")["tool_choice"])
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
