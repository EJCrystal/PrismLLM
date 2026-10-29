package dev.soven.llmgateway.domain

/**
 * 网关入口与渠道上游支持的原生协议。渠道列表顺序同时作为协议转换优先级。
 *
 * @author 文杰
 * @since 2026-10-22
 */
enum class GatewayProtocol(val value: String, val path: String) {
    CHAT_COMPLETIONS("chat_completions", "/chat/completions"),
    RESPONSES("responses", "/responses"),
    MESSAGES("messages", "/messages"),
    ;

    companion object {
        val default: List<GatewayProtocol> = listOf(CHAT_COMPLETIONS)

        fun fromValue(value: String): GatewayProtocol? = entries.firstOrNull { it.value == value }

        fun parse(value: String): List<GatewayProtocol> =
            value.split(',')
                .mapNotNull { fromValue(it.trim()) }
                .distinct()
                .ifEmpty { default }
    }
}
