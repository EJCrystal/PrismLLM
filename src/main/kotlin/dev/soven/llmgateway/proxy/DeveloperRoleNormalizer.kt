package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.proxy.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把发往 Chat Completions 上游的 developer 角色归一化为 system。
 *
 * 背景：
 * developer 是 OpenAI 在 Responses / Chat Completions 中替代 system 的新角色，
 * 但部分 OpenAI 兼容上游只接受 system/user/assistant/tool，收到 developer 会直接返回 400/422
 * （例如自建中转使用严格枚举解析 role）。system 在所有 Chat 上游都被接受，OpenAI 自己也接受，
 * 所以对 Chat Completions 无条件改写，与 magpie 的 `developerAsSystem` 一致。
 *
 * Responses 不改写：developer 是 Codex 客户端与中转的既有形状，OpenAI 的 ChatGPT 后端只认 developer；
 * 不支持该角色的自建 Responses 上游会返回 400，由 [ShapeRefusal] 归类为形状拒绝并换候选渠道。
 *
 * 改写只替换角色名，消息内容、字段顺序和其他角色保持不变。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object DeveloperRoleNormalizer {
    fun normalize(body: JsonObject, protocol: GatewayProtocol?): JsonObject {
        if (protocol != GatewayProtocol.CHAT_COMPLETIONS) return body

        val messages = body["messages"] as? JsonArray ?: return body
        var modified = false
        val rewritten = buildJsonArray {
            messages.forEach { item ->
                val message = item as? JsonObject
                if (message == null || message.string("role") != "developer") {
                    add(item)
                    return@forEach
                }
                modified = true
                add(buildJsonObject {
                    message.forEach { (key, value) ->
                        if (key == "role") put(key, "system") else put(key, value)
                    }
                })
            }
        }
        if (!modified) return body
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (key == "messages") put(key, rewritten) else put(key, value)
            }
        }
    }
}
