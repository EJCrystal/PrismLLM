package dev.soven.llmgateway.proxy.search

import dev.soven.llmgateway.proxy.Usage
import dev.soven.llmgateway.proxy.UsageParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** 搜索代理一轮对话里按顺序产出的内容。 */
sealed interface ConversationPart {
    data class Text(val text: String) : ConversationPart
    data class Search(val query: String, val hits: List<SearchHit>) : ConversationPart
    data class ToolCall(val id: String, val name: String, val arguments: String) : ConversationPart
}

data class SearchConversationResult(
    val parts: List<ConversationPart>,
    val usage: Usage?,
    val finishReason: String?,
)

/** 一轮 Chat 上游调用。传完整 messages，返回完整的 Chat Completions JSON。 */
fun interface ChatCaller {
    suspend fun call(messages: List<JsonObject>): JsonObject
}

/**
 * 把客户端的内置 web_search 变成“合成函数工具 + 多轮搜索”。
 *
 * 与 magpie 的 searchRounds 一致：
 * - 模型调用合成工具时，不把调用发给客户端；
 * - 网关执行搜索，把结果作为 tool message 追加后再次请求；
 * - 模型改调客户端自己的工具时，立即结束并把客户端工具调用交还；
 * - 最多 [maxRounds] 轮，超过后告诉模型不能再搜，让它基于已有结果回答。
 */
class SearchConversation(
    private val syntheticToolName: String,
    private val searchService: WebSearchService,
    private val maxRounds: Int = 6,
) {
    private val logger = LoggerFactory.getLogger(SearchConversation::class.java)

    /**
     * @param followUpCall 第一轮之后的调用，调用方应把 `tool_choice=required` 降级为 auto，
     *   避免每一轮都被强制调用合成搜索工具。
     * @param finalCall 达到轮数上限后的收尾调用，调用方应移除合成搜索工具，
     *   避免 `tool_choice=required` 再次强制模型调用搜索。
     * @param onUsage 每轮拿到用量时立即回调，便于上游中途失败时仍能记录已产生的用量。
     */
    suspend fun run(
        initialMessages: List<JsonObject>,
        call: ChatCaller,
        followUpCall: ChatCaller = call,
        finalCall: ChatCaller = call,
        onUsage: (Usage) -> Unit = {},
    ): SearchConversationResult {
        val messages = initialMessages.toMutableList()
        val parts = mutableListOf<ConversationPart>()
        var usage: Usage? = null
        var finishReason: String? = null
        var round = 0
        while (true) {
            round += 1
            val response = (if (round == 1) call else followUpCall).call(messages.toList())
            val roundUsage = UsageParser.fromJson(response)
            usage = addUsage(usage, roundUsage)
            roundUsage?.let(onUsage)
            val choice = (response["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw WebSearchException("上游没有返回 choices")
            val message = choice["message"] as? JsonObject
                ?: throw WebSearchException("上游没有返回 message")
            finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull

            val text = messageText(message)
            if (text.isNotBlank()) parts += ConversationPart.Text(text)

            val calls = parseToolCalls(message)
            if (calls.isEmpty()) {
                return SearchConversationResult(parts, usage, finishReason)
            }
            val synthetic = calls.filter { it.name == syntheticToolName }
            val clientCalls = calls.filter { it.name != syntheticToolName }
            if (clientCalls.isNotEmpty()) {
                clientCalls.forEach { parts += ConversationPart.ToolCall(it.id, it.name, it.arguments) }
                return SearchConversationResult(parts, usage, finishReason)
            }

            if (round > maxRounds) {
                messages += assistantMessage(text, synthetic)
                messages += synthetic.map { tool ->
                    toolMessage(tool.id, "No more searches: answer with what was found.")
                }
                val final = finalCall.call(messages.toList())
                val finalUsage = UsageParser.fromJson(final)
                usage = addUsage(usage, finalUsage)
                finalUsage?.let(onUsage)
                val finalChoice = (final["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val finalMessage = finalChoice?.get("message") as? JsonObject
                val finalText = finalMessage?.let(::messageText).orEmpty()
                if (finalText.isNotBlank()) parts += ConversationPart.Text(finalText)
                finalMessage?.let(::parseToolCalls).orEmpty()
                    .filter { it.name != syntheticToolName }
                    .forEach { parts += ConversationPart.ToolCall(it.id, it.name, it.arguments) }
                finishReason = (finalChoice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                    ?: finishReason
                return SearchConversationResult(parts, usage, finishReason)
            }

            // 单轮最多执行固定数量的搜索：模型一次返回大量调用时不能全部打到供应商。
            val executed = synthetic.take(MAX_SEARCHES_PER_ROUND)
            val results = coroutineScope {
                executed.map { call ->
                    async {
                        val query = queryOf(call)
                        if (query.isBlank()) {
                            call to Result.failure(WebSearchException("query is empty"))
                        } else {
                            call to searchResult(query)
                        }
                    }
                }.awaitAll()
            }
            messages += assistantMessage(text, synthetic)
            synthetic.forEachIndexed { index, tool ->
                if (index >= executed.size) {
                    messages += toolMessage(tool.id, "search skipped: too many searches in one round")
                    return@forEachIndexed
                }
                val (call, result) = results[index]
                val output = result.getOrNull()
                if (output != null) {
                    parts += ConversationPart.Search(queryOf(call), output.hits)
                    messages += toolMessage(call.id, output.summary)
                } else {
                    logger.warn("web search failed: {}", result.exceptionOrNull()?.message)
                    messages += toolMessage(call.id, "search failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
                }
            }
        }
    }

    private suspend fun searchResult(query: String): Result<SearchOutput> = try {
        Result.success(searchService.search(query))
    } catch (e: TimeoutCancellationException) {
        Result.failure(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private data class ParsedCall(val id: String, val name: String, val arguments: String)

private fun parseToolCalls(message: JsonObject): List<ParsedCall> =
    (message["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { index, raw ->
        val call = raw as? JsonObject ?: return@mapIndexedNotNull null
        val function = call["function"] as? JsonObject ?: return@mapIndexedNotNull null
        ParsedCall(
            id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: "call_search_$index",
            name = (function["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

private fun queryOf(call: ParsedCall): String = runCatching {
    (Json.parseToJsonElement(call.arguments) as? JsonObject)
        ?.get("query")
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()
}.getOrDefault("")

private fun messageText(message: JsonObject): String = when (val content = message["content"]) {
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.mapNotNull { part ->
        (part as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.joinToString("")
    else -> ""
}

private fun assistantMessage(text: String, calls: List<ParsedCall>): JsonObject = buildJsonObject {
    put("role", "assistant")
    if (text.isBlank()) put("content", JsonNull) else put("content", text)
    put("tool_calls", buildJsonArray {
        calls.forEach { call ->
            add(buildJsonObject {
                put("id", call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.arguments.ifBlank { "{}" })
                }
            })
        }
    })
}

private fun toolMessage(id: String, content: String): JsonObject = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", id)
    put("content", content)
}

/** 模型单轮最多触发的搜索次数，避免一次并行调用打爆搜索 API 或放大费用。 */
private const val MAX_SEARCHES_PER_ROUND = 5

internal fun addUsage(left: Usage?, right: Usage?): Usage? {
    if (left == null) return right
    if (right == null) return left
    fun sum(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> a + b
    }
    return Usage(
        input = sum(left.input, right.input),
        output = sum(left.output, right.output),
        cached = sum(left.cached, right.cached),
        cacheWrite = sum(left.cacheWrite, right.cacheWrite),
        reasoning = sum(left.reasoning, right.reasoning),
        total = sum(left.total, right.total),
    )
}

const val WEB_SEARCH_TOOL_DESCRIPTION =
    "Search the web for current information: news, releases, documentation, prices, anything after your training or that you are unsure of. Returns what the pages found say, with their URLs. Cite the URLs you use."

fun hasBuiltInWebSearch(request: JsonObject): Boolean =
    (request["tools"] as? JsonArray).orEmpty().any { raw ->
        val type = ((raw as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        type?.startsWith("web_search") == true
    }

/** 客户端已有同名工具时，合成工具改名，避免覆盖。 */
fun syntheticWebSearchName(request: JsonObject): String {
    val used = (request["tools"] as? JsonArray).orEmpty().mapNotNull { raw ->
        ((raw as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
    }.toSet()
    var name = "web_search"
    while (name in used) name = "magpie_$name"
    return name
}

/**
 * 把 Responses 请求里无法转成 Chat 的内置 web_search 剥离，并追加一个普通函数工具。
 * 历史里的 web_search_call 也一并去掉，避免变成没有 tool 消息接住的孤立 tool_call。
 */
fun prepareResponsesSearchRequest(request: JsonObject, syntheticName: String): JsonObject = buildJsonObject {
    request.forEach { (key, value) ->
        when (key) {
            "input", "tools", "tool_choice" -> Unit
            else -> put(key, value)
        }
    }
    val input = request["input"]
    if (input is JsonArray) {
        put("input", JsonArray(input.filter { raw ->
            val type = ((raw as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            type != "web_search_call"
        }))
    } else if (input != null) {
        put("input", input)
    }
    val tools = (request["tools"] as? JsonArray).orEmpty().filter { raw ->
        val type = ((raw as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        type?.startsWith("web_search") != true
    }.toMutableList()
    tools += buildJsonObject {
        put("type", "function")
        put("name", syntheticName)
        put("description", WEB_SEARCH_TOOL_DESCRIPTION)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to search for")
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
    }
    put("tools", JsonArray(tools))
    // 客户端未指定 tool_choice 时保持不强制：auto 语义下由模型决定是否搜索，与内置搜索一致。
    val choice = request["tool_choice"]
    val choiceType = ((choice as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
    if (choiceType?.startsWith("web_search") == true) {
        // 客户端强制内置搜索时，改写成强制调用合成函数，保留“必须搜索”的语义。
        put("tool_choice", buildJsonObject {
            put("type", "function")
            put("name", syntheticName)
        })
    } else if (choice != null) {
        put("tool_choice", choice)
    }
}
