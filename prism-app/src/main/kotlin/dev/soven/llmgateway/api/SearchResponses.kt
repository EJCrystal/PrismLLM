package dev.soven.llmgateway.api

import dev.soven.llmgateway.shared.error.GatewayError

import dev.soven.llmgateway.shared.domain.Usage
import dev.soven.llmgateway.proxy.search.ConversationPart
import dev.soven.llmgateway.proxy.search.SearchConversationResult
import dev.soven.llmgateway.proxy.search.SearchHit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 把搜索代理的多段输出渲染成 Responses 非流式响应。 */
internal fun renderSearchResponse(
    result: SearchConversationResult,
    model: String,
    responseId: String,
    createdAt: Long,
): JsonObject {
    val output = JsonArray(result.parts.mapIndexed { index, part ->
        when (part) {
            is ConversationPart.Text -> textItem(responseId, index, part.text)
            is ConversationPart.Refusal -> refusalItem(responseId, index, part.text)
            is ConversationPart.Search -> searchItem(responseId, index, part.query, part.hits)
            is ConversationPart.ToolCall -> toolItem(responseId, index, part)
        }
    })
    val incomplete = result.finishReason == "length" || result.finishReason == "content_filter"
    return buildJsonObject {
        put("id", responseId)
        put("object", "response")
        put("created_at", createdAt)
        put("status", if (incomplete) "incomplete" else "completed")
        if (incomplete) {
            putJsonObject("incomplete_details") {
                put("reason", if (result.finishReason == "content_filter") "content_filter" else "max_output_tokens")
            }
        }
        put("model", model)
        put("output", output)
        result.usage?.let { put("usage", usageJson(it)) }
    }
}

/**
 * 把搜索代理的多段输出增量渲染成 Responses SSE。
 * 先发 `response.created`，之后每产出一段内容立即发对应事件，最后用 [completed] 收尾。
 */
internal class SearchSseEmitter(
    private val model: String,
    private val responseId: String,
    private val createdAt: Long,
) {
    private var sequence = 0
    private var outputIndex = 0
    private val pending = mutableListOf<String>()

    private fun event(type: String, body: JsonObject) {
        pending += "event: $type"
        pending += "data: " + buildJsonObject {
            put("type", type)
            put("sequence_number", sequence++)
            body.forEach { (key, value) -> put(key, value) }
        }
        pending += ""
    }

    private fun drain(): List<String> {
        val lines = pending.toList()
        pending.clear()
        return lines
    }

    fun created(): List<String> {
        event("response.created", buildJsonObject {
            put("response", buildJsonObject {
                put("id", responseId)
                put("object", "response")
                put("created_at", createdAt)
                put("status", "in_progress")
                put("model", model)
                put("output", JsonArray(emptyList()))
            })
        })
        return drain()
    }

    fun part(part: ConversationPart): List<String> {
        when (part) {
            is ConversationPart.Text -> emitText(part.text)
            is ConversationPart.Refusal -> emitRefusal(part.text)
            is ConversationPart.Search -> emitSearch(part)
            is ConversationPart.ToolCall -> emitToolCall(part)
        }
        return drain()
    }

    fun completed(result: SearchConversationResult): List<String> {
        val terminal = if (result.finishReason == "length" || result.finishReason == "content_filter") {
            "response.incomplete"
        } else {
            "response.completed"
        }
        event(terminal, buildJsonObject {
            put("response", renderSearchResponse(result, model, responseId, createdAt))
        })
        return drain()
    }

    fun failed(error: GatewayError): List<String> {
        event("response.failed", buildJsonObject {
            put("response", buildJsonObject {
                put("id", responseId)
                put("object", "response")
                put("created_at", createdAt)
                put("status", "failed")
                put("model", model)
                putJsonObject("error") {
                    put("code", error.code)
                    put("message", error.message)
                }
            })
        })
        return drain()
    }

    private fun emitText(text: String) {
        val index = outputIndex++
        val itemId = "msg_${responseId}_$index"
        val inProgress = textItem(responseId, index, "", status = "in_progress", itemId = itemId)
        event("response.output_item.added", buildJsonObject {
            put("output_index", index)
            put("item", inProgress)
        })
        event("response.content_part.added", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("part", textPart(""))
        })
        event("response.output_text.delta", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("delta", text)
            put("logprobs", JsonArray(emptyList()))
        })
        event("response.output_text.done", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("text", text)
            put("logprobs", JsonArray(emptyList()))
        })
        event("response.content_part.done", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("part", textPart(text))
        })
        event("response.output_item.done", buildJsonObject {
            put("output_index", index)
            put("item", textItem(responseId, index, text, itemId = itemId))
        })
    }

    private fun emitRefusal(text: String) {
        val index = outputIndex++
        val itemId = "msg_${responseId}_$index"
        val inProgress = refusalItem(responseId, index, "", status = "in_progress", itemId = itemId)
        event("response.output_item.added", buildJsonObject {
            put("output_index", index)
            put("item", inProgress)
        })
        event("response.content_part.added", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("part", refusalPart(""))
        })
        event("response.refusal.delta", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("delta", text)
        })
        event("response.refusal.done", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("refusal", text)
        })
        event("response.content_part.done", buildJsonObject {
            put("item_id", itemId)
            put("output_index", index)
            put("content_index", 0)
            put("part", refusalPart(text))
        })
        event("response.output_item.done", buildJsonObject {
            put("output_index", index)
            put("item", refusalItem(responseId, index, text, itemId = itemId))
        })
    }

    private fun emitSearch(part: ConversationPart.Search) {
        val index = outputIndex++
        val item = searchItem(responseId, index, part.query, part.hits)
        event("response.output_item.added", buildJsonObject {
            put("output_index", index)
            put("item", item)
        })
        event("response.web_search_call.completed", buildJsonObject {
            put("output_index", index)
            put("item_id", item["id"] as JsonPrimitive)
        })
        event("response.output_item.done", buildJsonObject {
            put("output_index", index)
            put("item", item)
        })
    }

    private fun emitToolCall(part: ConversationPart.ToolCall) {
        val index = outputIndex++
        val arguments = part.arguments.ifBlank { "{}" }
        val inProgress = toolItem(responseId, index, part, status = "in_progress")
        event("response.output_item.added", buildJsonObject {
            put("output_index", index)
            put("item", inProgress)
        })
        event("response.function_call_arguments.delta", buildJsonObject {
            put("item_id", inProgress["id"] as JsonPrimitive)
            put("output_index", index)
            put("delta", arguments)
        })
        event("response.function_call_arguments.done", buildJsonObject {
            put("item_id", inProgress["id"] as JsonPrimitive)
            put("output_index", index)
            put("arguments", arguments)
        })
        event("response.output_item.done", buildJsonObject {
            put("output_index", index)
            put("item", toolItem(responseId, index, part))
        })
    }
}

/** 一次性渲染完整 SSE 序列，供测试与非增量场景使用。 */
internal fun renderSearchSse(
    result: SearchConversationResult,
    model: String,
    responseId: String,
    createdAt: Long,
): List<String> {
    val emitter = SearchSseEmitter(model, responseId, createdAt)
    return emitter.created() + result.parts.flatMap { emitter.part(it) } + emitter.completed(result)
}

private fun textItem(
    responseId: String,
    index: Int,
    text: String,
    status: String = "completed",
    itemId: String = "msg_${responseId}_$index",
): JsonObject = buildJsonObject {
    put("id", itemId)
    put("type", "message")
    put("role", "assistant")
    put("status", status)
    put("content", if (status == "in_progress") JsonArray(emptyList()) else buildJsonArray { add(textPart(text)) })
}

private fun textPart(text: String): JsonObject = buildJsonObject {
    put("type", "output_text")
    put("text", text)
    put("annotations", JsonArray(emptyList()))
    put("logprobs", JsonArray(emptyList()))
}

private fun refusalItem(
    responseId: String,
    index: Int,
    text: String,
    status: String = "completed",
    itemId: String = "msg_${responseId}_$index",
): JsonObject = buildJsonObject {
    put("id", itemId)
    put("type", "message")
    put("role", "assistant")
    put("status", status)
    put("content", if (status == "in_progress") JsonArray(emptyList()) else buildJsonArray { add(refusalPart(text)) })
}

private fun refusalPart(text: String): JsonObject = buildJsonObject {
    put("type", "refusal")
    put("refusal", text)
}

private fun searchItem(
    responseId: String,
    index: Int,
    query: String,
    hits: List<SearchHit>,
): JsonObject = buildJsonObject {
    put("id", "ws_${responseId}_$index")
    put("type", "web_search_call")
    put("status", "completed")
    putJsonObject("action") {
        put("type", "search")
        put("query", query)
        put("queries", buildJsonArray { add(JsonPrimitive(query)) })
        put("sources", buildJsonArray {
            hits.filter { it.url.isNotBlank() }.forEach { hit ->
                add(buildJsonObject {
                    put("type", "url")
                    put("url", hit.url)
                })
            }
        })
    }
}

private fun toolItem(
    responseId: String,
    index: Int,
    part: ConversationPart.ToolCall,
    status: String = "completed",
): JsonObject = buildJsonObject {
    put("id", "fc_${responseId}_$index")
    put("type", "function_call")
    put("call_id", part.id)
    put("name", part.name)
    put("arguments", part.arguments.ifBlank { "{}" })
    put("status", status)
}

private fun usageJson(usage: Usage): JsonObject = buildJsonObject {
    usage.input?.let { put("input_tokens", it) }
    usage.output?.let { put("output_tokens", it) }
    (usage.total ?: listOfNotNull(usage.input, usage.output).sum()).let { put("total_tokens", it) }
    val cached = usage.cached
    val cacheWrite = usage.cacheWrite
    if (cached != null || cacheWrite != null) {
        putJsonObject("input_tokens_details") {
            cached?.let { put("cached_tokens", it) }
            cacheWrite?.let { put("cache_write_tokens", it) }
        }
    }
    usage.reasoning?.let {
        putJsonObject("output_tokens_details") { put("reasoning_tokens", it) }
    }
}
