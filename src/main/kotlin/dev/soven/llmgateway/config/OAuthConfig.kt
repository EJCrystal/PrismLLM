package dev.soven.llmgateway.config

import java.net.URI

/**
 * 单个 OAuth 提供方的公开客户端参数。client secret 只从环境变量读取，不落库、不写日志。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthProviderConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val tokenEndpoint: String,
)

/**
 * 订阅账号 OAuth 的运行配置。Antigravity 只有显式配置 client id 后才启用。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthConfig(
    /** 后台扫描间隔。 */
    val refreshIntervalMs: Long = 300_000,
    /** 提前刷新窗口；短寿命 token 会按寿命的一半再压缩。 */
    val refreshAheadMs: Long = 900_000,
    val requestTimeoutMs: Long = 30_000,
    /** 429 没有明确重置时间时的默认冷却；连续 429 会指数退避。 */
    val rateLimitDefaultMs: Long = 300_000,
    /** 单次 429 冷却上限，避免上游给出异常长的重置时间。 */
    val rateLimitMaxMs: Long = 21_600_000,
    val chatGpt: OAuthProviderConfig = OAuthProviderConfig(
        clientId = DEFAULT_CHATGPT_CLIENT_ID,
        tokenEndpoint = "https://auth.openai.com/oauth/token",
    ),
    val antigravity: OAuthProviderConfig? = null,
) {
    companion object {
        /** Codex CLI 的公开 OAuth 客户端 ID；可用 OAUTH_CHATGPT_CLIENT_ID 覆盖。 */
        const val DEFAULT_CHATGPT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

        fun fromEnv(env: Map<String, String>): OAuthConfig {
            fun value(name: String, default: String? = null): String? =
                env[name]?.takeIf { it.isNotBlank() } ?: default

            fun longValue(name: String, default: Long): Long =
                value(name)?.let { it.toLongOrNull() ?: error("环境变量 $name 必须是整数") } ?: default

            val refreshIntervalMs = longValue("OAUTH_REFRESH_INTERVAL_MS", 300_000)
            val refreshAheadMs = longValue("OAUTH_REFRESH_AHEAD_MS", 900_000)
            val requestTimeoutMs = longValue("OAUTH_REQUEST_TIMEOUT_MS", 30_000)
            val rateLimitDefaultMs = longValue("OAUTH_RATE_LIMIT_DEFAULT_MS", 300_000)
            val rateLimitMaxMs = longValue("OAUTH_RATE_LIMIT_MAX_MS", 21_600_000)
            require(refreshIntervalMs in 60_000..86_400_000) {
                "OAUTH_REFRESH_INTERVAL_MS 必须在 60000..86400000 之间"
            }
            require(refreshAheadMs in 60_000..86_400_000) {
                "OAUTH_REFRESH_AHEAD_MS 必须在 60000..86400000 之间"
            }
            require(requestTimeoutMs in 5_000..120_000) {
                "OAUTH_REQUEST_TIMEOUT_MS 必须在 5000..120000 之间"
            }
            require(rateLimitDefaultMs in 1_000..86_400_000) {
                "OAUTH_RATE_LIMIT_DEFAULT_MS 必须在 1000..86400000 之间"
            }
            require(rateLimitMaxMs in rateLimitDefaultMs..604_800_000) {
                "OAUTH_RATE_LIMIT_MAX_MS 必须在默认冷却时间..604800000 之间"
            }

            val chatGpt = OAuthProviderConfig(
                clientId = value("OAUTH_CHATGPT_CLIENT_ID", DEFAULT_CHATGPT_CLIENT_ID)!!,
                tokenEndpoint = value("OAUTH_CHATGPT_TOKEN_ENDPOINT", "https://auth.openai.com/oauth/token")!!,
            )
            validateEndpoint("OAUTH_CHATGPT_TOKEN_ENDPOINT", chatGpt.tokenEndpoint)

            val antigravityClientId = value("OAUTH_ANTIGRAVITY_CLIENT_ID")
            val antigravity = antigravityClientId?.let { clientId ->
                OAuthProviderConfig(
                    clientId = clientId,
                    clientSecret = value("OAUTH_ANTIGRAVITY_CLIENT_SECRET"),
                    tokenEndpoint = value(
                        "OAUTH_ANTIGRAVITY_TOKEN_ENDPOINT",
                        "https://oauth2.googleapis.com/token",
                    )!!,
                ).also { validateEndpoint("OAUTH_ANTIGRAVITY_TOKEN_ENDPOINT", it.tokenEndpoint) }
            }

            return OAuthConfig(
                refreshIntervalMs = refreshIntervalMs,
                refreshAheadMs = refreshAheadMs,
                requestTimeoutMs = requestTimeoutMs,
                rateLimitDefaultMs = rateLimitDefaultMs,
                rateLimitMaxMs = rateLimitMaxMs,
                chatGpt = chatGpt,
                antigravity = antigravity,
            )
        }

        /** 只允许 https；本机回环地址允许 http，便于集成测试和本地代理调试。 */
        private fun validateEndpoint(name: String, value: String) {
            val uri = runCatching { URI(value) }.getOrElse { error("$name 不是合法 URL") }
            require(
                uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null,
            ) { "$name 必须是完整 URL，且不能包含账号密码、查询参数或片段" }
            require(
                uri.scheme == "https" ||
                    (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")),
            ) { "$name 必须使用 https（仅本机回环地址允许 http）" }
        }
    }
}
