package dev.soven.llmgateway.shared.domain

/**
 * 订阅账号的 OAuth 提供方。
 *
 * 渠道需要按 provider 标识系统置备渠道，订阅账号也用它区分端点与刷新实现，
 * 因此放在共享内核里，避免两个上下文互相引用。
 *
 * @author 文杰
 * @since 2026-11-06
 */
enum class OAuthProvider(val value: String) {
    CHATGPT("chatgpt"),
    ANTIGRAVITY("antigravity"),
    ;

    companion object {
        fun fromValue(value: String): OAuthProvider? = entries.firstOrNull { it.value == value }
    }
}
