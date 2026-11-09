package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从调用方请求中提取推理强度。
 *
 * Chat Completions 只读取顶层 `reasoning_effort`；Responses API 只读取 `reasoning.effort`；
 * Messages 协议读取 `output_config.effort`、`thinking.effort`，或根据 `thinking.budget_tokens` 换算；
 * Gemini 协议读取 `generationConfig.thinkingConfig.thinkingLevel`。
 *
 * 字段缺失时返回 null，由界面显示为未设置，不推测上游默认值。
 *
 * @author 文杰
 * @since 2026-10-22
 */
object ReasoningEffort {
    private const val MAX_LENGTH = 40

    fun fromRequest(request: JsonObject, protocol: GatewayProtocol): String? {
        val raw = when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS -> request["reasoning_effort"]
            GatewayProtocol.RESPONSES -> (request["reasoning"] as? JsonObject)?.get("effort")
            GatewayProtocol.MESSAGES -> request.thinkingEffort()?.let { JsonPrimitive(it) }
            GatewayProtocol.GEMINI -> ((request["generationConfig"] as? JsonObject)
                ?.get("thinkingConfig") as? JsonObject)?.get("thinkingLevel")
        }
        return (raw as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.take(MAX_LENGTH)
            ?.takeIf { it.isNotEmpty() }
    }
}
