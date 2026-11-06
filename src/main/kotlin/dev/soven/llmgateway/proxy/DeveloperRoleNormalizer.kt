package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.proxy.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 渠道级 developer 角色处理策略，随渠道或全局的请求覆盖配置保存。
 *
 * @author 文杰
 * @since 2026-11-06
 */
enum class DeveloperRolePolicy(val value: String) {
    /** 仅对 Chat Completions 上游归一化；Responses 保留 developer，避免依赖该角色的 Codex 中转被破坏。 */
    AUTO("auto"),

    /** 对 Chat Completions 与 Responses 上游都归一化，适用于拒绝 developer 的 OpenAI 兼容中转。 */
    ALWAYS("always"),

    /** 完全不改写。 */
    NEVER("never");

    companion object {
        fun fromValue(value: String): DeveloperRolePolicy? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 将上游不支持的 developer 角色归一化为 system。
 *
 * 背景：
 * developer 是 OpenAI 在 Responses / Chat Completions 中替代 system 的新角色，
 * 但部分 OpenAI 兼容上游只接受 system/user/assistant/tool，收到 developer 会直接返回 400/422
 * （例如自建 Responses 中转使用 serde 严格枚举解析 role）。
 *
 * 处理规则：
 * 1. 是否触发由渠道级 [DeveloperRolePolicy] 决定：
 *    - auto（默认）：仅 Chat Completions 上游归一化，与 magpie 的 developerAsSystem 一致；
 *      system 在各家 Chat 上游均被接受，而 Responses 的 developer 是 Codex 客户端与中转的既有形状，默认不动；
 *    - always：Chat Completions 与 Responses 上游都归一化，适用于拒绝 developer 的自建中转；
 *    - never：完全不改写。
 * 2. 同时处理 Chat Completions 的 messages[] 与 Responses 的 input[]：
 *    仅改写顶层消息项中 role="developer" 的对象，其余字段、顺序和其他角色保持不变；
 *    Responses 的 input 为字符串时不做处理。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object DeveloperRoleNormalizer {
    fun normalize(body: JsonObject, protocol: GatewayProtocol?, policy: DeveloperRolePolicy): JsonObject {
        val enabled = when (policy) {
            DeveloperRolePolicy.NEVER -> false
            DeveloperRolePolicy.AUTO -> protocol == GatewayProtocol.CHAT_COMPLETIONS
            DeveloperRolePolicy.ALWAYS ->
                protocol == GatewayProtocol.CHAT_COMPLETIONS || protocol == GatewayProtocol.RESPONSES
        }
        if (!enabled) return body

        val messages = rewriteDeveloperRole(body["messages"])
        val input = rewriteDeveloperRole(body["input"])
        if (messages == null && input == null) return body
        return buildJsonObject {
            body.forEach { (key, value) ->
                when (key) {
                    "messages" -> put(key, messages ?: value)
                    "input" -> put(key, input ?: value)
                    else -> put(key, value)
                }
            }
        }
    }

    /** 返回改写后的数组；没有 developer 项时返回 null，便于调用方直接复用原对象。 */
    private fun rewriteDeveloperRole(element: JsonElement?): JsonArray? {
        val array = element as? JsonArray ?: return null
        var modified = false
        val rewritten = buildJsonArray {
            array.forEach { item ->
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
        return if (modified) rewritten else null
    }
}
