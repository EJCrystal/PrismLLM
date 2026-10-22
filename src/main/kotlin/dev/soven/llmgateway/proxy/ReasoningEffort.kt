package dev.soven.llmgateway.proxy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从调用方请求中提取推理强度。
 *
 * Chat Completions 使用顶层 `reasoning_effort`；Responses API 使用 `reasoning.effort`。
 * 两个字段都缺失时返回 null，由界面显示为未设置，不推测上游默认值。
 *
 * @author 文杰
 * @since 2026-10-22
 */
object ReasoningEffort {
    private const val MAX_LENGTH = 40

    fun fromRequest(request: JsonObject): String? {
        val direct = (request["reasoning_effort"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
        val nested = ((request["reasoning"] as? JsonObject)?.get("effort") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
        return (direct ?: nested)?.trim()?.take(MAX_LENGTH)?.takeIf { it.isNotEmpty() }
    }
}
