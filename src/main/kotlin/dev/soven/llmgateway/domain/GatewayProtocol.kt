package dev.soven.llmgateway.domain

/**
 * 渠道支持的上游协议。v1 只覆盖 OpenAI 的两种请求形状。
 *
 * @author 文杰
 * @since 2026-10-22
 */
enum class GatewayProtocol(val value: String, val path: String) {
    CHAT_COMPLETIONS("chat_completions", "/chat/completions"),
    RESPONSES("responses", "/responses"),
    ;

    companion object {
        val default: Set<GatewayProtocol> = setOf(CHAT_COMPLETIONS)

        fun fromValue(value: String): GatewayProtocol? = entries.firstOrNull { it.value == value }

        fun parse(value: String): Set<GatewayProtocol> =
            value.split(',')
                .mapNotNull { fromValue(it.trim()) }
                .toSet()
                .ifEmpty { default }
    }
}
