package dev.soven.prismllm.proxy.search

import dev.soven.prismllm.protocol.responsesToChatRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("unit")
class SearchConversationTest {
    private val hit = SearchHit("Go downloads", "https://go.dev/dl/", "Go 1.27.1 is the latest release.")
    private val searchService = WebSearchService(listOf(
        object : WebSearchBackend {
            override val name = "fake"
            override suspend fun search(query: String, limit: Int, allowedDomains: List<String>) = SearchOutput(
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
    fun `mixed search and client calls run the search before returning the client call`() = runBlocking {
        var searches = 0
        val countingService = WebSearchService(listOf(
            object : WebSearchBackend {
                override val name = "counting"
                override suspend fun search(query: String, limit: Int, allowedDomains: List<String>): SearchOutput {
                    searches += 1
                    return SearchOutput("result for $query", listOf(hit))
                }
            },
        ))
        val result = SearchConversation("web_search", countingService).run(
            listOf(obj("""{"role":"user","content":"search and read"}""")),
            call = {
                chat(
                    content = "",
                    finish = "tool_calls",
                    input = 10,
                    output = 2,
                    calls = """[
                        {"id":"call_search","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"latest go\"}"}},
                        {"id":"call_read","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}
                    ]""",
                )
            },
        )

        assertEquals(1, searches)
        assertEquals(1, result.parts.filterIsInstance<ConversationPart.Search>().size)
        assertEquals(ConversationPart.ToolCall("call_read", "read_file", "{\"path\":\"README.md\"}"), result.parts.last())
    }

    @Test
    fun `caps the number of searches executed in one round`() = runBlocking {
        var searches = 0
        val countingService = WebSearchService(listOf(
            object : WebSearchBackend {
                override val name = "counting"
                override suspend fun search(query: String, limit: Int, allowedDomains: List<String>): SearchOutput {
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
    fun `refusal is preserved when the upstream declines`() = runBlocking {
        val result = SearchConversation("web_search", searchService).run(
            listOf(obj("""{"role":"user","content":"hi"}""")),
            call = {
                obj(
                    """{"choices":[{"message":{"role":"assistant","content":null,"refusal":"I can't help with that."},"finish_reason":"stop"}]}""",
                )
            },
        )

        assertEquals(listOf(ConversationPart.Refusal("I can't help with that.")), result.parts)
    }

    @Test
    fun `refusal in the final round is preserved`() = runBlocking {
        val result = SearchConversation("web_search", searchService, maxRounds = 0).run(
            listOf(obj("""{"role":"user","content":"hi"}""")),
            call = {
                chat(
                    content = "",
                    finish = "tool_calls",
                    input = 10,
                    output = 2,
                    calls = """[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"latest go\"}"}}]""",
                )
            },
            finalCall = {
                obj(
                    """{"choices":[{"message":{"role":"assistant","content":null,"refusal":"I can't help with that."},"finish_reason":"stop"}]}""",
                )
            },
        )

        assertEquals(ConversationPart.Refusal("I can't help with that."), result.parts.last())
    }

    @Test
    fun `parts are emitted while the conversation is still running`() = runBlocking {
        val events = mutableListOf<String>()
        val result = SearchConversation("web_search", searchService).run(
            listOf(obj("""{"role":"user","content":"latest go?"}""")),
            call = { messages ->
                events += "call:${messages.size}"
                if (messages.size == 1) {
                    chat(
                        content = "",
                        finish = "tool_calls",
                        input = 10,
                        output = 2,
                        calls = """[{"id":"c1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"latest go\"}"}}]""",
                    )
                } else {
                    chat(content = "Answer.", finish = "stop", input = 12, output = 3)
                }
            },
            onPart = { events += "part:${it::class.simpleName}" },
        )

        assertEquals(listOf("call:1", "part:Search", "call:3", "part:Text"), events)
        assertEquals(ConversationPart.Text("Answer."), result.parts.last())
    }

    @Test
    fun `reads allowed domains from the built-in search tool`() {
        val request = obj(
            """{"model":"m","tools":[{"type":"web_search","filters":{"allowed_domains":["example.com"," docs.example.com "]}}]}""",
        )

        assertEquals(listOf("example.com", "docs.example.com"), webSearchAllowedDomains(request))
        assertEquals(emptyList(), webSearchAllowedDomains(obj("""{"model":"m","tools":[{"type":"web_search"}]}""")))
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
    fun `absent tool choice forces one search on the first round`() {
        val request = obj(
            """{"model":"m","input":[{"type":"message","role":"user","content":"hi"}],"tools":[{"type":"web_search"}]}""",
        )

        val choice = prepareResponsesSearchRequest(request, "web_search")["tool_choice"]!!.jsonObject
        assertEquals("function", choice["type"]!!.toString().trim('"'))
        assertEquals("web_search", choice["name"]!!.toString().trim('"'))
    }

    @Test
    fun `explicit tool choice is kept`() {
        val request = obj(
            """{"model":"m","input":[{"type":"message","role":"user","content":"hi"}],"tools":[{"type":"web_search"}],"tool_choice":"none"}""",
        )

        assertEquals("none", prepareResponsesSearchRequest(request, "web_search")["tool_choice"]!!.toString().trim('"'))
    }

    @Test
    fun `renames the synthetic tool when the client already uses the name`() {
        val request = obj(
            """{"model":"m","tools":[{"type":"function","name":"web_search","parameters":{"type":"object"}}]}""",
        )

        assertEquals("magpie_web_search", syntheticWebSearchName(request))
    }

    @Test
    fun `codex lite shape with web search in additional tools is prepared`() {
        // Codex Responses Lite：web_search 藏在 additional_tools 输入项的 functions namespace 里。
        val request = obj(
            """{
              "model":"gpt-6",
              "input":[
                {"type":"additional_tools","role":"developer","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","parameters":{"type":"object"}},
                    {"type":"web_search","filters":{"allowed_domains":["example.com"]}}
                  ]}
                ]},
                {"type":"message","role":"developer","content":[{"type":"input_text","text":"plan first"}]},
                {"type":"message","role":"user","content":[{"type":"input_text","text":"latest go?"}]}
              ]
            }""",
        )

        assertTrue(hasBuiltInWebSearch(request))
        assertEquals(listOf("example.com"), webSearchAllowedDomains(request))
        // 宿主 web_search 没有 name，不占用函数名；shell 才是命名工具。
        assertEquals("web_search", syntheticWebSearchName(request))

        val prepared = prepareResponsesSearchRequest(request, "web_search")
        // 输入项只剩两条 message；工具只剩 shell 与合成函数，内置 web_search 已剥离。
        val input = prepared["input"]!!.jsonArray
        assertEquals(2, input.size)
        val tools = prepared["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("shell", tools[0].jsonObject["name"]!!.toString().trim('"'))
        assertEquals("web_search", tools[1].jsonObject["name"]!!.toString().trim('"'))
        // 未指定 tool_choice：首轮强制调用合成函数。
        val choice = prepared["tool_choice"]!!.jsonObject
        assertEquals("web_search", choice["name"]!!.toString().trim('"'))
        // 改写后的请求可以直接转成 Chat（工具都是普通 function）。
        val chat = responsesToChatRequest(prepared)
        assertEquals(2, chat["tools"]!!.jsonArray.size)
    }

    @Test
    fun `additional tools function names block the synthetic name too`() {        val request = obj(
            """{
              "model":"m",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"web_search","parameters":{"type":"object"}}
                  ]}
                ]},
                {"type":"message","role":"user","content":"hi"}
              ]
            }""",
        )

        assertEquals("magpie_web_search", syntheticWebSearchName(request))
    }

    @Test
    fun `additional tools merge dedupes by name keeping existing tools`() {
        // 搜索路径先移除 additional_tools 项，协议层的去重不再执行；抬升必须自带同规则去重。
        val request = obj(
            """{
              "model":"m",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","parameters":{"type":"object","properties":{"cmd":{}},"description":"client shell"}},
                    {"type":"function","name":"fetch","parameters":{"type":"object"}},
                    {"type":"web_search"}
                  ]}
                ]},
                {"type":"message","role":"user","content":"hi"}
              ],
              "tools":[{"type":"function","name":"shell","description":"request-level shell","parameters":{"type":"object","properties":{"c":{}}}}]
            }""",
        )

        val prepared = prepareResponsesSearchRequest(request, "web_search")
        val tools = prepared["tools"]!!.jsonArray
        // 同名 shell 保留请求级声明；fetch 与宿主 web_search（无 name）只出现一次，末位是合成函数。
        assertEquals(3, tools.size)
        assertEquals("shell", tools[0].jsonObject["name"]!!.toString().trim('"'))
        assertEquals("request-level shell", tools[0].jsonObject["description"]!!.toString().trim('"'))
        assertEquals("fetch", tools[1].jsonObject["name"]!!.toString().trim('"'))
        assertEquals("web_search", tools[2].jsonObject["name"]!!.toString().trim('"'))
    }

    @Test
    fun `history function calls lose the namespace marker on the search path`() {
        // 搜索路径删除 additional_tools 项后协议层不会再清理历史调用；抬升必须同步去掉 namespace 标记，
        // 与 liftAdditionalTools 的行为一致，否则转换后的调用项携带 Chat 无法表达的分组字段。
        val request = obj(
            """{
              "model":"m",
              "input":[
                {"type":"additional_tools","tools":[
                  {"type":"namespace","name":"functions","tools":[
                    {"type":"function","name":"shell","parameters":{"type":"object"}}
                  ]}
                ]},
                {"type":"function_call","call_id":"call_1","name":"shell","arguments":"{}","namespace":"functions"},
                {"type":"function_call_output","call_id":"call_1","output":"done"},
                {"type":"message","role":"user","content":"hi"}
              ]
            }""",
        )

        val prepared = prepareResponsesSearchRequest(request, "web_search")
        val input = prepared["input"]!!.jsonArray
        assertEquals(3, input.size)
        assertEquals("function_call", input[0].jsonObject["type"]!!.toString().trim('"'))
        assertEquals(null, input[0].jsonObject["namespace"])
        assertEquals("function_call_output", input[1].jsonObject["type"]!!.toString().trim('"'))
        // 改写后可直接转成 Chat：function_call 已是普通调用。
        val chat = responsesToChatRequest(prepared)
        assertEquals(1, chat["messages"]!!.jsonArray.filter { it.jsonObject["role"]!!.toString().contains("assistant") }.size)
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
