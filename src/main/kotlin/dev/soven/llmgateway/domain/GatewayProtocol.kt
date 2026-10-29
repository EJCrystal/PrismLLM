package dev.soven.llmgateway.domain

/**
 * 网关入口与渠道上游支持的原生协议。不同协议之间不自动转换。
 *
 * @author 文杰
 * @since 2026-10-22
 */
enum class GatewayProtocol(val value: String, val path: String) {
    CHAT_COMPLETIONS("chat_completions", "/chat/completions"),
    RESPONSES("responses", "/responses"),
    MESSAGES("messages", "/messages"),
    GEMINI("gemini", "/models"),
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
