package dev.soven.prismllm.proxy.search

import dev.soven.prismllm.shared.domain.Usage
import dev.soven.prismllm.protocol.UsageParser
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
    data class Refusal(val text: String) : ConversationPart
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
    private val allowedDomains: List<String> = emptyList(),
) {
    private val logger = LoggerFactory.getLogger(SearchConversation::class.java)

    /**
     * @param followUpCall 第一轮之后的调用，调用方应把 `tool_choice=required` 降级为 auto，
     *   避免每一轮都被强制调用合成搜索工具。
     * @param finalCall 达到轮数上限后的收尾调用，调用方应移除合成搜索工具，
     *   避免 `tool_choice=required` 再次强制模型调用搜索。
     * @param onUsage 每轮拿到用量时立即回调，便于上游中途失败时仍能记录已产生的用量。
     * @param onPart 每产出一段内容立即回调，流式响应可以边搜边发事件。
     */
    suspend fun run(
        initialMessages: List<JsonObject>,
        call: ChatCaller,
        followUpCall: ChatCaller = call,
        finalCall: ChatCaller = call,
        onPart: suspend (ConversationPart) -> Unit = {},
        onUsage: (Usage) -> Unit = {},
    ): SearchConversationResult {
        val messages = initialMessages.toMutableList()
        val parts = mutableListOf<ConversationPart>()
        suspend fun add(part: ConversationPart) {
            parts += part
            onPart(part)
        }
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
            if (text.isNotBlank()) add(ConversationPart.Text(text))
            val refusal = messageRefusal(message)
            if (refusal.isNotBlank()) add(ConversationPart.Refusal(refusal))

            val calls = parseToolCalls(message)
            if (calls.isEmpty()) {
                return SearchConversationResult(parts, usage, finishReason)
            }
            val synthetic = calls.filter { it.name == syntheticToolName }
            val clientCalls = calls.filter { it.name != syntheticToolName }
            // 纯客户端调用，或已超出搜索轮数时，直接交还调用，不再执行搜索。
            if (clientCalls.isNotEmpty() && (synthetic.isEmpty() || round > maxRounds)) {
                clientCalls.forEach { add(ConversationPart.ToolCall(it.id, it.name, it.arguments)) }
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
                if (finalText.isNotBlank()) add(ConversationPart.Text(finalText))
                val finalRefusal = finalMessage?.let(::messageRefusal).orEmpty()
                if (finalRefusal.isNotBlank()) add(ConversationPart.Refusal(finalRefusal))
                finalMessage?.let(::parseToolCalls).orEmpty()
                    .filter { it.name != syntheticToolName }
                    .forEach { add(ConversationPart.ToolCall(it.id, it.name, it.arguments)) }
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
                    add(ConversationPart.Search(queryOf(call), output.hits))
                    messages += toolMessage(call.id, output.summary)
                } else {
                    logger.warn("web search failed: {}", result.exceptionOrNull()?.message)
                    messages += toolMessage(call.id, "search failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
                }
            }
            // 同一轮同时要求搜索和客户端工具时，搜索已经执行并保留结果，再把客户端调用交还。
            if (clientCalls.isNotEmpty()) {
                clientCalls.forEach { add(ConversationPart.ToolCall(it.id, it.name, it.arguments)) }
                return SearchConversationResult(parts, usage, finishReason)
            }
        }
    }

    private suspend fun searchResult(query: String): Result<SearchOutput> = try {
        Result.success(searchService.search(query, allowedDomains))
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

private fun messageRefusal(message: JsonObject): String =
    (message["refusal"] as? JsonPrimitive)?.contentOrNull.orEmpty()

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
    } || additionalToolsHostedTypes(request).any { it.startsWith("web_search") }

/** `additional_tools` 输入项（Codex Responses Lite 形状）携带的宿主工具类型。 */
private fun additionalToolsHostedTypes(request: JsonObject): List<String> =
    (request["input"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "additional_tools" }
        .flatMap { item -> (item["tools"] as? JsonArray).orEmpty() }
        .flatMap { tool ->
            val obj = tool as? JsonObject
            when {
                obj == null -> emptyList()
                // 嵌套 namespace（Lite 把 Codex 自有函数归在 "functions" 里）逐层展开。
                (obj["type"] as? JsonPrimitive)?.contentOrNull == "namespace" ->
                    (obj["tools"] as? JsonArray).orEmpty()
                        .mapNotNull { nested -> (nested as? JsonObject)?.get("type") as? JsonPrimitive }
                        .mapNotNull { it.contentOrNull }
                else -> listOfNotNull((obj["type"] as? JsonPrimitive)?.contentOrNull)
            }
        }

/**
 * 读取内置搜索工具上的 `filters.allowed_domains`。
 * 内置工具会被剥离，限制必须由搜索后端或结果过滤来落实，不能静默丢弃。
 */
fun webSearchAllowedDomains(request: JsonObject): List<String> {
    val hosted = (request["tools"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { tool ->
            ((tool["type"] as? JsonPrimitive)?.contentOrNull)?.startsWith("web_search") == true
        }
        // additional_tools（Lite 形状）里的内置搜索工具同样读取 filters.allowed_domains。
        ?: run {
            (request["input"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "additional_tools" }
                .flatMap { item -> (item["tools"] as? JsonArray).orEmpty() }
                .mapNotNull { it as? JsonObject }
                .firstOrNull { tool ->
                    val type = (tool["type"] as? JsonPrimitive)?.contentOrNull
                    (type?.startsWith("web_search") == true) ||
                        (type == "namespace" && (tool["tools"] as? JsonArray).orEmpty().any { nested ->
                            (((nested as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull)
                                ?.startsWith("web_search") == true
                        })
                }
                // 域名过滤在内置工具对象上；namespace 包裹时取其中第一个 web_search 工具。
                ?.let { container ->
                    if ((container["type"] as? JsonPrimitive)?.contentOrNull == "namespace") {
                        (container["tools"] as? JsonArray).orEmpty()
                            .mapNotNull { it as? JsonObject }
                            .firstOrNull { ((it["type"] as? JsonPrimitive)?.contentOrNull)?.startsWith("web_search") == true }
                    } else {
                        container
                    }
                }
        }
    return hosted
        ?.let { tool -> (tool["filters"] as? JsonObject)?.get("allowed_domains") as? JsonArray }
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        ?.distinct()
        .orEmpty()
}

/** 客户端已有同名工具时，合成工具改名，避免覆盖。请求级与 additional_tools（Lite 形状）里的工具都算占用。 */
fun syntheticWebSearchName(request: JsonObject): String {
    val used = buildSet {
        (request["tools"] as? JsonArray).orEmpty().forEach { raw ->
            ((raw as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.let(::add)
        }
        (request["input"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "additional_tools" }
            .flatMap { item -> (item["tools"] as? JsonArray).orEmpty() }
            .forEach { tool ->
                val obj = tool as? JsonObject
                when {
                    obj == null -> Unit
                    (obj["type"] as? JsonPrimitive)?.contentOrNull == "namespace" ->
                        (obj["tools"] as? JsonArray).orEmpty().forEach { nested ->
                            ((nested as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.let(::add)
                        }
                    else -> (obj["name"] as? JsonPrimitive)?.contentOrNull?.let(::add)
                }
            }
    }
    var name = "web_search"
    while (name in used) name = "magpie_$name"
    return name
}

/**
 * 把 Responses 请求里无法转成 Chat 的内置 web_search 剥离，并追加一个普通函数工具。
 * 历史里的 web_search_call 也一并去掉，避免变成没有 tool 消息接住的孤立 tool_call。
 * Codex 的 Lite 形状把工具放在 additional_tools 输入项里：先抬升为请求级 tools，再统一剥离。
 */
fun prepareResponsesSearchRequest(request: JsonObject, syntheticName: String): JsonObject {
    val lifted = liftRequestTools(request)
    return buildJsonObject {
        lifted.forEach { (key, value) ->
            when (key) {
                "input", "tools", "tool_choice" -> Unit
                else -> put(key, value)
            }
        }
        val input = lifted["input"]
        if (input is JsonArray) {
            put("input", JsonArray(input.filter { raw ->
                val type = ((raw as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
                // web_search_call 无法转成 Chat；additional_tools 的工具已抬升为请求级 tools，项本身移除。
                type != "web_search_call" && type != "additional_tools"
            }))
        } else if (input != null) {
            put("input", input)
        }
        val tools = (lifted["tools"] as? JsonArray).orEmpty().filter { raw ->
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
        val choice = lifted["tool_choice"]
        val choiceType = ((choice as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        val forcedSearch = buildJsonObject {
            put("type", "function")
            put("name", syntheticName)
        }
        when {
            // 客户端强制内置搜索时，改写成强制调用合成函数，保留“必须搜索”的语义。
            choiceType?.startsWith("web_search") == true -> put("tool_choice", forcedSearch)
            // 未指定 tool_choice 时首轮强制搜索一次，保证配置后端后内置 web_search 一定被执行；
            // 后续轮次会降级为 auto，模型仍可直接作答。
            choice == null -> put("tool_choice", forcedSearch)
            else -> put("tool_choice", choice)
        }
    }
}

/**
 * 请求级 tools 与 additional_tools 输入项里的工具合并为请求级 tools；
 * 没有额外工具时原样返回。只用于搜索代理内部，转换层有完整的 [dev.soven.prismllm.protocol.AdditionalToolsKt] 版本。
 */
private fun liftRequestTools(request: JsonObject): JsonObject {
    val extra = additionalToolsHostedTools(request)
    if (extra.isEmpty()) return request
    val tools = (request["tools"] as? JsonArray).orEmpty().toMutableList()
    tools += extra
    return JsonObject(request + ("tools" to JsonArray(tools)))
}

/** additional_tools 输入项里的全部工具对象，namespace 平铺。 */
private fun additionalToolsHostedTools(request: JsonObject): List<JsonObject> =
    (request["input"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "additional_tools" }
        .flatMap { item -> (item["tools"] as? JsonArray).orEmpty() }
        .flatMap { tool ->
            val obj = tool as? JsonObject ?: return@flatMap emptyList()
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "namespace") {
                (obj["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            } else {
                listOf(obj)
            }
        }
