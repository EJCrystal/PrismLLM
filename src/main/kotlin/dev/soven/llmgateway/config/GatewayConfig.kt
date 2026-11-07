package dev.soven.llmgateway.config

import java.nio.file.Path
import java.util.Base64
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy
import dev.soven.llmgateway.spi.iq.IqJudge

/**
 * 网关运行配置。所有值来自环境变量，启动期完成校验，运行期不再读取环境。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class GatewayConfig(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val masterKey: ByteArray,
    val adminUsername: String,
    val adminPassword: String,
    val corsOrigins: List<String>,
    val requestBodyLimitBytes: Long,
    val connectTimeoutMs: Long,
    val firstTokenTimeoutMs: Long,
    val idleTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val maxAttempts: Int,
    val drainTimeoutMs: Long,
    val staticDir: Path,
    val cookieSecure: Boolean,
    val retention: RetentionPolicy,
    val cooldown: CooldownPolicy = CooldownPolicy(),
    val keyAutoRecoveryIntervalMs: Long = 30_000,
    val upstreamHeaderRefreshIntervalMs: Long = 3_600_000,
    val upstreamHeaderCatalogUrl: String? = null,
    val upstreamHeaderRefreshEnabled: Boolean = true,
    /** 可信额度插件 JAR 所在目录；不存在时视为空目录。 */
    val quotaPluginDir: Path = Path.of("plugins"),
    /** 智商检测的 HTML 作品是否交给满血AI 分类。关闭时 manxue 判分器不注册。 */
    val manxueEnabled: Boolean = true,
    val manxueApiBaseUrl: String = "https://manxue.ai",
    val manxueTimeoutMs: Long = 60_000,
    val iqDefaultJudge: String = "local",
    val iqJudgeTimeoutMs: Long = 60_000,
    val iqJudgePluginDir: Path = Path.of("plugins/iq-judges"),
    /** 内置视觉判分器；启用时必须配置 OpenAI 兼容的多模态端点与模型。 */
    val iqVisionEnabled: Boolean = false,
    val iqVisionBaseUrl: String = "",
    val iqVisionApiKey: String = "",
    val iqVisionModel: String = "",
    val iqVisionTimeoutMs: Long = 60_000,
    val iqVisionRenderWidth: Int = 1024,
    val iqVisionMaxImageBytes: Int = 4_000_000,
    /** Chrome/Chromium 可执行文件；为空时自动探测常见路径。 */
    val iqVisionBrowserPath: String = "",
    val iqVisionBrowserTimeoutMs: Long = 15_000,
    val trustedProxyNetworks: List<TrustedProxyNetwork> = emptyList(),
    /** 订阅账号 OAuth 的刷新参数与各 Provider 客户端配置。 */
    val oauth: OAuthConfig = OAuthConfig(),
    /** 启动时显式提供的环境变量键，仅用于管理台展示配置来源。 */
    val providedEnvKeys: Set<String> = emptySet(),
    /** Responses 内置 web_search 的网关代执行配置。 */
    val webSearch: WebSearchConfig = WebSearchConfig(),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): GatewayConfig {
            fun value(name: String, default: String? = null): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: default
                    ?: error("缺少必填环境变量 $name")

            fun longValue(name: String, default: Long): Long =
                value(name, default.toString()).toLongOrNull()
                    ?: error("环境变量 $name 必须是整数")

            val masterKeyText = value("GATEWAY_MASTER_KEY")
            val masterKey = runCatching { Base64.getDecoder().decode(masterKeyText) }
                .getOrElse { error("GATEWAY_MASTER_KEY 必须是 Base64 编码的 32 字节密钥") }
            require(masterKey.size == 32) { "GATEWAY_MASTER_KEY 必须是 Base64 编码的 32 字节密钥" }

            val maxAttempts = longValue("MAX_ATTEMPTS", 2).toInt()
            require(maxAttempts in 1..10) { "MAX_ATTEMPTS 必须在 1..10 之间" }
            val cooldownTransientBaseMs = longValue("COOLDOWN_TRANSIENT_BASE_MS", 30_000)
            val cooldownTransientMaxMs = longValue("COOLDOWN_TRANSIENT_MAX_MS", 600_000)
            val cooldownRateLimitDefaultMs = longValue("COOLDOWN_RATE_LIMIT_DEFAULT_MS", 30_000)
            val cooldownRateLimitMaxMs = longValue("COOLDOWN_RATE_LIMIT_MAX_MS", 60_000)
            val cooldownAuthFailureMs = longValue("COOLDOWN_AUTH_FAILURE_MS", 600_000)
            val ttftSlowThresholdMs = longValue("TTFT_SLOW_THRESHOLD_MS", 10_000)
            val ttftSlowStreak = longValue("TTFT_SLOW_STREAK", 3).toInt()
            val keyAutoRecoveryIntervalMs = longValue("KEY_AUTO_RECOVERY_INTERVAL_MS", 30_000)
            val upstreamHeaderRefreshIntervalMs = longValue("UPSTREAM_HEADER_REFRESH_INTERVAL_MS", 3_600_000)
            val manxueTimeoutMs = longValue("MANXUE_TIMEOUT_MS", 60_000)
            val iqJudgeTimeoutMs = longValue("IQ_JUDGE_TIMEOUT_MS", 60_000)
            require(iqJudgeTimeoutMs in 1_000..300_000) { "IQ_JUDGE_TIMEOUT_MS 必须在 1000..300000 之间" }
            val iqDefaultJudge = value("IQ_DEFAULT_JUDGE", "local")
            require(Regex(IqJudge.ID_PATTERN).matches(iqDefaultJudge)) {
                "IQ_DEFAULT_JUDGE 必须是合法的判分器 ID"
            }
            val iqVisionEnabled = value("IQ_VISION_ENABLED", "false").toBooleanStrictOrNull()
                ?: error("IQ_VISION_ENABLED 必须是 true 或 false")
            val iqVisionBaseUrl = value("IQ_VISION_BASE_URL", "").trim()
            val iqVisionModel = value("IQ_VISION_MODEL", "").trim()
            if (iqVisionEnabled) {
                require(iqVisionBaseUrl.startsWith("http://") || iqVisionBaseUrl.startsWith("https://")) {
                    "启用 IQ_VISION_ENABLED 时，IQ_VISION_BASE_URL 必须以 http:// 或 https:// 开头"
                }
                require(iqVisionModel.isNotBlank()) {
                    "启用 IQ_VISION_ENABLED 时必须配置 IQ_VISION_MODEL"
                }
            }
            val iqVisionTimeoutMs = longValue("IQ_VISION_TIMEOUT_MS", 60_000)
            require(iqVisionTimeoutMs in 1_000..300_000) { "IQ_VISION_TIMEOUT_MS 必须在 1000..300000 之间" }
            val iqVisionRenderWidth = longValue("IQ_VISION_RENDER_WIDTH", 1024).toInt()
            require(iqVisionRenderWidth in 256..2048) { "IQ_VISION_RENDER_WIDTH 必须在 256..2048 之间" }
            val iqVisionMaxImageBytes = longValue("IQ_VISION_MAX_IMAGE_BYTES", 4_000_000).toInt()
            require(iqVisionMaxImageBytes in 100_000..20_000_000) {
                "IQ_VISION_MAX_IMAGE_BYTES 必须在 100000..20000000 之间"
            }
            val iqVisionBrowserPath = value("IQ_VISION_BROWSER_PATH", "").trim()
            val iqVisionBrowserTimeoutMs = longValue("IQ_VISION_BROWSER_TIMEOUT_MS", 15_000)
            require(iqVisionBrowserTimeoutMs in 1_000..60_000) {
                "IQ_VISION_BROWSER_TIMEOUT_MS 必须在 1000..60000 之间"
            }
            require(cooldownTransientBaseMs in 1_000..3_600_000) { "COOLDOWN_TRANSIENT_BASE_MS 必须在 1000..3600000 之间" }
            require(cooldownTransientMaxMs in cooldownTransientBaseMs..86_400_000) {
                "COOLDOWN_TRANSIENT_MAX_MS 必须在 COOLDOWN_TRANSIENT_BASE_MS..86400000 之间"
            }
            require(cooldownRateLimitDefaultMs in 1_000..600_000) { "COOLDOWN_RATE_LIMIT_DEFAULT_MS 必须在 1000..600000 之间" }
            require(cooldownRateLimitMaxMs in cooldownRateLimitDefaultMs..3_600_000) {
                "COOLDOWN_RATE_LIMIT_MAX_MS 必须在 COOLDOWN_RATE_LIMIT_DEFAULT_MS..3600000 之间"
            }
            require(cooldownAuthFailureMs in 10_000..86_400_000) { "COOLDOWN_AUTH_FAILURE_MS 必须在 10000..86400000 之间" }
            require(ttftSlowThresholdMs in 100..600_000) { "TTFT_SLOW_THRESHOLD_MS 必须在 100..600000 之间" }
            require(ttftSlowStreak in 1..100) { "TTFT_SLOW_STREAK 必须在 1..100 之间" }
            require(keyAutoRecoveryIntervalMs in 1_000..3_600_000) {
                "KEY_AUTO_RECOVERY_INTERVAL_MS 必须在 1000..3600000 之间"
            }
            require(upstreamHeaderRefreshIntervalMs in 60_000..604_800_000) {
                "UPSTREAM_HEADER_REFRESH_INTERVAL_MS 必须在 60000..604800000 之间"
            }
            require(manxueTimeoutMs in 1_000..300_000) { "MANXUE_TIMEOUT_MS 必须在 1000..300000 之间" }
            val manxueApiBaseUrl = value("MANXUE_API_BASE_URL", "https://manxue.ai")
            require(manxueApiBaseUrl.startsWith("http://") || manxueApiBaseUrl.startsWith("https://")) {
                "MANXUE_API_BASE_URL 必须以 http:// 或 https:// 开头"
            }

            val webSearchProvider = value("WEB_SEARCH_PROVIDER", "tavily").trim().lowercase()
            val webSearchApiKey = env["WEB_SEARCH_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            val webSearchBaseUrl = env["WEB_SEARCH_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() }
            val webSearchDefaultEnabled = webSearchApiKey != null ||
                webSearchProvider == "searxng" && webSearchBaseUrl != null
            val webSearchEnabled = value("WEB_SEARCH_ENABLED", webSearchDefaultEnabled.toString())
                .toBooleanStrictOrNull()
                ?: error("WEB_SEARCH_ENABLED 必须是 true 或 false")
            val webSearchMaxResults = longValue("WEB_SEARCH_MAX_RESULTS", 6).toInt()
            val webSearchTimeoutMs = longValue("WEB_SEARCH_TIMEOUT_MS", 15_000)
            val webSearchMaxRounds = longValue("WEB_SEARCH_MAX_ROUNDS", 6).toInt()
            val webSearchMaxPageChars = longValue("WEB_SEARCH_MAX_PAGE_CHARS", 1_500).toInt()
            require(webSearchMaxResults in 1..20) { "WEB_SEARCH_MAX_RESULTS 必须在 1..20 之间" }
            require(webSearchTimeoutMs in 1_000..120_000) { "WEB_SEARCH_TIMEOUT_MS 必须在 1000..120000 之间" }
            require(webSearchMaxRounds in 1..10) { "WEB_SEARCH_MAX_ROUNDS 必须在 1..10 之间" }
            require(webSearchMaxPageChars in 100..10_000) { "WEB_SEARCH_MAX_PAGE_CHARS 必须在 100..10000 之间" }
            if (webSearchEnabled) {
                require(webSearchProvider in setOf("tavily", "brave", "exa", "deepseek", "searxng")) {
                    "WEB_SEARCH_PROVIDER 必须是 tavily、brave、exa、deepseek 或 searxng"
                }
                if (webSearchProvider == "searxng") {
                    require(webSearchBaseUrl != null) { "SearXNG 需要配置 WEB_SEARCH_BASE_URL" }
                } else {
                    require(webSearchApiKey != null) { "WEB_SEARCH_API_KEY 不能为空" }
                }
                webSearchBaseUrlError(
                    webSearchProvider,
                    webSearchBaseUrl,
                    requireHttps = webSearchProvider != "searxng",
                )?.let { throw IllegalArgumentException("WEB_SEARCH_BASE_URL: $it") }
            }

            return GatewayConfig(
                port = longValue("PORT", 8080).toInt(),
                databaseUrl = value("DATABASE_URL", "jdbc:postgresql://localhost:5432/llm_gateway"),
                databaseUser = value("DATABASE_USER", "llm_gateway"),
                databasePassword = value("DATABASE_PASSWORD", "llm_gateway"),
                masterKey = masterKey,
                adminUsername = value("ADMIN_USERNAME", "admin"),
                adminPassword = value("ADMIN_PASSWORD"),
                corsOrigins = value("CORS_ORIGINS", "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                requestBodyLimitBytes = longValue("REQUEST_BODY_LIMIT_BYTES", 10L * 1024 * 1024),
                connectTimeoutMs = longValue("CONNECT_TIMEOUT_MS", 30_000),
                firstTokenTimeoutMs = longValue("FIRST_TOKEN_TIMEOUT_MS", 120_000),
                idleTimeoutMs = longValue("IDLE_TIMEOUT_MS", 60_000),
                totalTimeoutMs = longValue("TOTAL_TIMEOUT_MS", 600_000),
                maxAttempts = maxAttempts,
                drainTimeoutMs = longValue("DRAIN_TIMEOUT_MS", 30_000),
                staticDir = Path.of(value("STATIC_DIR", "web/dist")),
                cookieSecure = value("COOKIE_SECURE", "false").toBooleanStrictOrNull()
                    ?: error("COOKIE_SECURE 必须是 true 或 false"),
                retention = RetentionPolicy(
                    retentionDays = longValue("REQUEST_LOG_RETENTION_DAYS", 30).toInt(),
                    batchSize = longValue("REQUEST_LOG_CLEANUP_BATCH_SIZE", 5_000).toInt(),
                    cleanupEnabled = value("REQUEST_LOG_CLEANUP_ENABLED", "true").toBooleanStrictOrNull()
                        ?: error("REQUEST_LOG_CLEANUP_ENABLED 必须是 true 或 false"),
                    cleanupIntervalMs = longValue("REQUEST_LOG_CLEANUP_INTERVAL_MS", 3_600_000),
                ),
                cooldown = CooldownPolicy(
                    transientBaseMs = cooldownTransientBaseMs,
                    transientMaxMs = cooldownTransientMaxMs,
                    rateLimitDefaultMs = cooldownRateLimitDefaultMs,
                    rateLimitMaxMs = cooldownRateLimitMaxMs,
                    authFailureMs = cooldownAuthFailureMs,
                    ttftSlowThresholdMs = ttftSlowThresholdMs,
                    ttftSlowStreak = ttftSlowStreak,
                ),
                keyAutoRecoveryIntervalMs = keyAutoRecoveryIntervalMs,
                upstreamHeaderRefreshIntervalMs = upstreamHeaderRefreshIntervalMs,
                upstreamHeaderCatalogUrl = env["UPSTREAM_HEADER_CATALOG_URL"]?.takeIf { it.isNotBlank() },
                upstreamHeaderRefreshEnabled = value("UPSTREAM_HEADER_REFRESH_ENABLED", "true")
                    .toBooleanStrictOrNull()
                    ?: error("UPSTREAM_HEADER_REFRESH_ENABLED 必须是 true 或 false"),
                quotaPluginDir = Path.of(value("QUOTA_PLUGIN_DIR", "plugins")),
                manxueEnabled = value("MANXUE_ENABLED", "true").toBooleanStrictOrNull()
                    ?: error("MANXUE_ENABLED 必须是 true 或 false"),
                manxueApiBaseUrl = manxueApiBaseUrl,
                manxueTimeoutMs = manxueTimeoutMs,
                iqDefaultJudge = iqDefaultJudge,
                iqJudgeTimeoutMs = iqJudgeTimeoutMs,
                iqJudgePluginDir = Path.of(value("IQ_JUDGE_PLUGIN_DIR", "plugins/iq-judges")),
                iqVisionEnabled = iqVisionEnabled,
                iqVisionBaseUrl = iqVisionBaseUrl,
                iqVisionApiKey = value("IQ_VISION_API_KEY", ""),
                iqVisionModel = iqVisionModel,
                iqVisionTimeoutMs = iqVisionTimeoutMs,
                iqVisionRenderWidth = iqVisionRenderWidth,
                iqVisionMaxImageBytes = iqVisionMaxImageBytes,
                iqVisionBrowserPath = iqVisionBrowserPath,
                iqVisionBrowserTimeoutMs = iqVisionBrowserTimeoutMs,
                trustedProxyNetworks = runCatching { TrustedProxyNetwork.parseList(value("TRUSTED_PROXY_CIDRS", "")) }
                    .getOrElse { cause -> error("TRUSTED_PROXY_CIDRS 配置无效: ${cause.message}") },
                oauth = OAuthConfig.fromEnv(env),
                providedEnvKeys = env.filterValues { it.isNotBlank() }.keys,
                webSearch = WebSearchConfig(
                    enabled = webSearchEnabled,
                    provider = webSearchProvider,
                    apiKey = webSearchApiKey,
                    baseUrl = webSearchBaseUrl,
                    maxResults = webSearchMaxResults,
                    timeoutMs = webSearchTimeoutMs,
                    maxRounds = webSearchMaxRounds,
                    maxPageChars = webSearchMaxPageChars,
                ),
            )
        }
    }
}
