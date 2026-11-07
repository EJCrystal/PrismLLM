package dev.soven.llmgateway.api

import dev.soven.llmgateway.proxy.Usage
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

/** 把搜索代理的多段输出渲染成 Responses SSE。 */
internal fun renderSearchSse(
    result: SearchConversationResult,
    model: String,
    responseId: String,
    createdAt: Long,
): List<String> {
    val lines = mutableListOf<String>()
    var sequence = 0
    var outputIndex = 0
    fun event(type: String, body: JsonObject) {
        lines += "event: $type"
        lines += "data: " + buildJsonObject {
            put("type", type)
            put("sequence_number", sequence++)
            body.forEach { (key, value) -> put(key, value) }
        }
        lines += ""
    }

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

    result.parts.forEach { part ->
        when (part) {
            is ConversationPart.Text -> {
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
                    put("delta", part.text)
                    put("logprobs", JsonArray(emptyList()))
                })
                event("response.output_text.done", buildJsonObject {
                    put("item_id", itemId)
                    put("output_index", index)
                    put("content_index", 0)
                    put("text", part.text)
                    put("logprobs", JsonArray(emptyList()))
                })
                event("response.content_part.done", buildJsonObject {
                    put("item_id", itemId)
                    put("output_index", index)
                    put("content_index", 0)
                    put("part", textPart(part.text))
                })
                event("response.output_item.done", buildJsonObject {
                    put("output_index", index)
                    put("item", textItem(responseId, index, part.text, itemId = itemId))
                })
            }
            is ConversationPart.Search -> {
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
            is ConversationPart.ToolCall -> {
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
    }

    val terminal = if (result.finishReason == "length" || result.finishReason == "content_filter") {
        "response.incomplete"
    } else {
        "response.completed"
    }
    event(terminal, buildJsonObject {
        put("response", renderSearchResponse(result, model, responseId, createdAt))
    })
    return lines
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
