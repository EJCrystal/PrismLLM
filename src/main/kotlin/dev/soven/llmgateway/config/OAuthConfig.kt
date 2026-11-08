package dev.soven.llmgateway.config

import dev.soven.llmgateway.domain.OAuthRoutingStrategy
import java.net.URI

/**
 * 单个 OAuth 提供方的公开客户端参数。client secret 只从内置常量或环境变量读取，
 * 不落库、不写日志；安装型客户端的 secret 是公开值，不是用户凭据。
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
 * 订阅账号 OAuth 的运行配置。两个提供方都内置公开客户端参数，可用环境变量覆盖。
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
    /** 同一渠道、同一密钥优先级内订阅账号的排序策略；不影响静态密钥。 */
    val routing: OAuthRoutingStrategy = OAuthRoutingStrategy.ROUND_ROBIN,
    /**
     * 允许作为账号代理出口的主机。回环地址（127.0.0.1、localhost、::1）始终允许，
     * 其余主机必须显式列入 `OAUTH_PROXY_ALLOWED_HOSTS`，避免管理面输入变成任意内网探测入口。
     */
    val proxyAllowedHosts: Set<String> = emptySet(),
    val chatGpt: OAuthProviderConfig = OAuthProviderConfig(
        clientId = DEFAULT_CHATGPT_CLIENT_ID,
        tokenEndpoint = "https://auth.openai.com/oauth/token",
        usageUrl = DEFAULT_CHATGPT_USAGE_URL,
        authorizeUrl = DEFAULT_CHATGPT_AUTHORIZE_URL,
        redirectUri = DEFAULT_CHATGPT_REDIRECT_URI,
        loginScopes = DEFAULT_CHATGPT_LOGIN_SCOPES,
    ),
    /**
     * Antigravity 与 ChatGPT 一样内置公开客户端参数，并同样支持浏览器登录：
     * Google 授权后把回调地址粘回管理面即可换取 refresh token。
     */
    val antigravity: OAuthProviderConfig = OAuthProviderConfig(
        clientId = DEFAULT_ANTIGRAVITY_CLIENT_ID,
        clientSecret = DEFAULT_ANTIGRAVITY_CLIENT_SECRET,
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        authorizeUrl = DEFAULT_ANTIGRAVITY_AUTHORIZE_URL,
        redirectUri = DEFAULT_ANTIGRAVITY_REDIRECT_URI,
        loginScopes = DEFAULT_ANTIGRAVITY_LOGIN_SCOPES,
    ),
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

        /**
         * Antigravity 桌面客户端的公开 OAuth 客户端 ID；可用 OAUTH_ANTIGRAVITY_CLIENT_ID 覆盖。
         * 这是客户端内置的安装型凭据，用户不需要自己申请。
         */
        const val DEFAULT_ANTIGRAVITY_CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"

        /**
         * Antigravity 桌面客户端的公开 OAuth 客户端密钥；可用 OAUTH_ANTIGRAVITY_CLIENT_SECRET 覆盖。
         * 安装型客户端的 secret 随客户端分发，公开可见，不构成额外的用户凭据。
         */
        const val DEFAULT_ANTIGRAVITY_CLIENT_SECRET = "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"

        /** Google 的授权地址；可用 OAUTH_ANTIGRAVITY_AUTHORIZE_URL 覆盖。 */
        const val DEFAULT_ANTIGRAVITY_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"

        /** 与 Antigravity 客户端登记的一致；可用 OAUTH_ANTIGRAVITY_REDIRECT_URI 覆盖。 */
        const val DEFAULT_ANTIGRAVITY_REDIRECT_URI = "http://localhost:8085/callback"

        /**
         * Antigravity 客户端申请的 scope：Code Assist 需要 cloud-platform，
         * cclog 与 experimentsandconfigs 是它读取项目配置所需。
         */
        const val DEFAULT_ANTIGRAVITY_LOGIN_SCOPES =
            "https://www.googleapis.com/auth/cloud-platform " +
                "https://www.googleapis.com/auth/userinfo.email " +
                "https://www.googleapis.com/auth/userinfo.profile " +
                "https://www.googleapis.com/auth/cclog " +
                "https://www.googleapis.com/auth/experimentsandconfigs"

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
            val routing = value("OAUTH_ROUTING", OAuthRoutingStrategy.ROUND_ROBIN.value)
                ?.let { raw ->
                    OAuthRoutingStrategy.fromValue(raw)
                        ?: error("OAUTH_ROUTING 只支持 round_robin、drain、least_used 或 pace")
                }!!

            val proxyAllowedHosts = parseProxyAllowedHosts(value("OAUTH_PROXY_ALLOWED_HOSTS"))

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

            // 与 ChatGPT 一致：内置客户端参数，环境变量只做覆盖；未配置时不再禁用该提供方。
            val antigravity = OAuthProviderConfig(
                clientId = value("OAUTH_ANTIGRAVITY_CLIENT_ID", DEFAULT_ANTIGRAVITY_CLIENT_ID)!!,
                clientSecret = value("OAUTH_ANTIGRAVITY_CLIENT_SECRET", DEFAULT_ANTIGRAVITY_CLIENT_SECRET),
                tokenEndpoint = value(
                    "OAUTH_ANTIGRAVITY_TOKEN_ENDPOINT",
                    "https://oauth2.googleapis.com/token",
                )!!,
                authorizeUrl = value("OAUTH_ANTIGRAVITY_AUTHORIZE_URL", DEFAULT_ANTIGRAVITY_AUTHORIZE_URL),
                redirectUri = value("OAUTH_ANTIGRAVITY_REDIRECT_URI", DEFAULT_ANTIGRAVITY_REDIRECT_URI),
                loginScopes = value("OAUTH_ANTIGRAVITY_LOGIN_SCOPES", DEFAULT_ANTIGRAVITY_LOGIN_SCOPES),
            )
            validateEndpoint("OAUTH_ANTIGRAVITY_TOKEN_ENDPOINT", antigravity.tokenEndpoint)
            antigravity.authorizeUrl?.let { validateEndpoint("OAUTH_ANTIGRAVITY_AUTHORIZE_URL", it) }
            antigravity.redirectUri?.let { validateRedirectUri("OAUTH_ANTIGRAVITY_REDIRECT_URI", it) }
            require(antigravity.loginScopes == null || antigravity.loginScopes.length <= 512) {
                "OAUTH_ANTIGRAVITY_LOGIN_SCOPES 长度不能超过 512"
            }

            return OAuthConfig(
                refreshIntervalMs = refreshIntervalMs,
                refreshAheadMs = refreshAheadMs,
                requestTimeoutMs = requestTimeoutMs,
                rateLimitDefaultMs = rateLimitDefaultMs,
                rateLimitMaxMs = rateLimitMaxMs,
                quotaIntervalMs = quotaIntervalMs,
                quotaMaxAgeMs = quotaMaxAgeMs,
                routing = routing,
                proxyAllowedHosts = proxyAllowedHosts,
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

        /** 账号代理主机白名单；条目只接受主机名或 IPv4，避免把协议、端口或凭据混进来。 */
        private fun parseProxyAllowedHosts(raw: String?): Set<String> {
            if (raw == null) return emptySet()
            val hosts = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            require(hosts.size <= MAX_PROXY_ALLOWED_HOSTS) {
                "OAUTH_PROXY_ALLOWED_HOSTS 最多允许 $MAX_PROXY_ALLOWED_HOSTS 个主机"
            }
            hosts.forEach { host ->
                require(host.length <= 253 && PROXY_HOST_PATTERN.matches(host)) {
                    "OAUTH_PROXY_ALLOWED_HOSTS 只接受主机名或 IPv4，不能包含协议、端口、路径或凭据：$host"
                }
            }
            return hosts.toSet()
        }

        /** Java 的 URI 把 IPv6 字面量保留为 `[::1]`，比较前去掉方括号。 */
        private fun isLoopbackHost(host: String?): Boolean =
            host != null && host.removePrefix("[").removeSuffix("]") in LOOPBACK_HOSTS

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        private const val MAX_PROXY_ALLOWED_HOSTS = 32
        private val PROXY_HOST_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
