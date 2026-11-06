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
    /** 上游额度查询地址；为空表示该提供方不支持额度观测。 */
    val usageUrl: String? = null,
    /** 交互式登录授权地址；为空表示该提供方不支持从网关发起登录。 */
    val authorizeUrl: String? = null,
    /** 交互式登录回调地址，必须与 OAuth 客户端登记的一致。 */
    val redirectUri: String? = null,
    /** 交互式登录申请的 scope；为空时由提供方决定。 */
    val loginScopes: String? = null,
) {
    val supportsLogin: Boolean get() = authorizeUrl != null && redirectUri != null
}

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
    /** 后台额度扫描间隔。 */
    val quotaIntervalMs: Long = 300_000,
    /** 额度读数的有效期；过期读数只展示，不参与准入拦截。 */
    val quotaMaxAgeMs: Long = 900_000,
    val chatGpt: OAuthProviderConfig = OAuthProviderConfig(
        clientId = DEFAULT_CHATGPT_CLIENT_ID,
        tokenEndpoint = "https://auth.openai.com/oauth/token",
        usageUrl = DEFAULT_CHATGPT_USAGE_URL,
        authorizeUrl = DEFAULT_CHATGPT_AUTHORIZE_URL,
        redirectUri = DEFAULT_CHATGPT_REDIRECT_URI,
        loginScopes = DEFAULT_CHATGPT_LOGIN_SCOPES,
    ),
    val antigravity: OAuthProviderConfig? = null,
) {
    companion object {
        /** Codex CLI 的公开 OAuth 客户端 ID；可用 OAUTH_CHATGPT_CLIENT_ID 覆盖。 */
        const val DEFAULT_CHATGPT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

        /** ChatGPT 的额度查询地址；可用 OAUTH_CHATGPT_USAGE_URL 覆盖。 */
        const val DEFAULT_CHATGPT_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

        /** ChatGPT 的交互式登录授权地址；可用 OAUTH_CHATGPT_AUTHORIZE_URL 覆盖。 */
        const val DEFAULT_CHATGPT_AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"

        /** Codex 客户端登记的回调地址，端口不可更改；可用 OAUTH_CHATGPT_REDIRECT_URI 覆盖。 */
        const val DEFAULT_CHATGPT_REDIRECT_URI = "http://localhost:1455/auth/callback"

        /** 与 Codex CLI 一致的登录 scope。 */
        const val DEFAULT_CHATGPT_LOGIN_SCOPES =
            "openid profile email offline_access api.connectors.read api.connectors.invoke"

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
            val quotaIntervalMs = longValue("OAUTH_QUOTA_INTERVAL_MS", 300_000)
            val quotaMaxAgeMs = longValue("OAUTH_QUOTA_MAX_AGE_MS", 900_000)
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
            require(quotaIntervalMs in 60_000..86_400_000) {
                "OAUTH_QUOTA_INTERVAL_MS 必须在 60000..86400000 之间"
            }
            require(quotaMaxAgeMs in quotaIntervalMs..86_400_000) {
                "OAUTH_QUOTA_MAX_AGE_MS 必须在扫描间隔..86400000 之间"
            }

            val chatGpt = OAuthProviderConfig(
                clientId = value("OAUTH_CHATGPT_CLIENT_ID", DEFAULT_CHATGPT_CLIENT_ID)!!,
                tokenEndpoint = value("OAUTH_CHATGPT_TOKEN_ENDPOINT", "https://auth.openai.com/oauth/token")!!,
                usageUrl = value("OAUTH_CHATGPT_USAGE_URL", DEFAULT_CHATGPT_USAGE_URL),
                authorizeUrl = value("OAUTH_CHATGPT_AUTHORIZE_URL", DEFAULT_CHATGPT_AUTHORIZE_URL),
                redirectUri = value("OAUTH_CHATGPT_REDIRECT_URI", DEFAULT_CHATGPT_REDIRECT_URI),
                loginScopes = value("OAUTH_CHATGPT_LOGIN_SCOPES", DEFAULT_CHATGPT_LOGIN_SCOPES),
            )
            validateEndpoint("OAUTH_CHATGPT_TOKEN_ENDPOINT", chatGpt.tokenEndpoint)
            chatGpt.usageUrl?.let { validateEndpoint("OAUTH_CHATGPT_USAGE_URL", it) }
            chatGpt.authorizeUrl?.let { validateEndpoint("OAUTH_CHATGPT_AUTHORIZE_URL", it) }
            chatGpt.redirectUri?.let { validateRedirectUri("OAUTH_CHATGPT_REDIRECT_URI", it) }
            require(chatGpt.loginScopes == null || chatGpt.loginScopes.length <= 512) {
                "OAUTH_CHATGPT_LOGIN_SCOPES 长度不能超过 512"
            }

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
                quotaIntervalMs = quotaIntervalMs,
                quotaMaxAgeMs = quotaMaxAgeMs,
                chatGpt = chatGpt,
                antigravity = antigravity,
            )
        }

        /** 回调地址允许本机 http（Codex 固定走 localhost:1455），其余必须是 https。 */
        private fun validateRedirectUri(name: String, value: String) {
            val uri = runCatching { URI(value) }.getOrElse { error("$name 不是合法 URL") }
            require(
                uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null,
            ) { "$name 必须是完整 URL，且不能包含账号密码、查询参数或片段" }
            require(uri.scheme == "https" || (uri.scheme == "http" && isLoopbackHost(uri.host))) {
                "$name 必须使用 https（仅本机回环地址允许 http）"
            }
        }

        /** 只允许 https；本机回环地址允许 http，便于集成测试和本地代理调试。 */
        private fun validateEndpoint(name: String, value: String) {
            val uri = runCatching { URI(value) }.getOrElse { error("$name 不是合法 URL") }
            require(
                uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null,
            ) { "$name 必须是完整 URL，且不能包含账号密码、查询参数或片段" }
            require(
                uri.scheme == "https" || (uri.scheme == "http" && isLoopbackHost(uri.host)),
            ) { "$name 必须使用 https（仅本机回环地址允许 http）" }
        }

        /** Java 的 URI 把 IPv6 字面量保留为 `[::1]`，比较前去掉方括号。 */
        private fun isLoopbackHost(host: String?): Boolean =
            host != null && host.removePrefix("[").removeSuffix("]") in LOOPBACK_HOSTS

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}
