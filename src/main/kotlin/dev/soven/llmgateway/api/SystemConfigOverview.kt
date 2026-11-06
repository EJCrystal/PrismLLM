package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.GatewayConfig
import java.net.URI

/**
 * 构建管理台系统配置总览。所有值来自启动期配置，敏感值只展示是否已配置。
 */
internal fun buildSystemConfigResponse(config: GatewayConfig): SystemConfigResponse {
    fun source(key: String, derived: Boolean = false): String = when {
        derived -> "derived"
        key in config.providedEnvKeys -> "environment"
        else -> "default"
    }

    fun item(
        key: String,
        label: String,
        value: Any?,
        kind: String,
        description: String? = null,
        derived: Boolean = false,
    ) = SystemConfigItem(
        key = key,
        label = label,
        value = value?.toString().orEmpty(),
        kind = kind,
        source = source(key, derived),
        restartRequired = true,
        description = description,
    )

    val runtime = SystemConfigSection(
        id = "runtime",
        title = "运行与访问",
        description = "进程启动时加载；修改环境变量后需要重启网关。",
        items = listOf(
            item("PORT", "监听端口", config.port, "text"),
            item("STATIC_DIR", "管理台静态目录", config.staticDir, "text"),
            item("COOKIE_SECURE", "管理会话 Cookie Secure", config.cookieSecure, "boolean"),
            item(
                "CORS_ORIGINS",
                "允许的跨域来源",
                config.corsOrigins.joinToString(","),
                "list",
                description = "为空时仅允许同源请求。",
            ),
            item("REQUEST_BODY_LIMIT_BYTES", "请求体上限", config.requestBodyLimitBytes, "bytes"),
            item(
                "TRUSTED_PROXY_CIDRS",
                "可信代理网段",
                config.trustedProxyNetworks.size,
                "count",
                description = if (config.trustedProxyNetworks.isEmpty()) {
                    "未配置；转发头不会被采信。"
                } else {
                    "已配置 ${config.trustedProxyNetworks.size} 个网段。"
                },
            ),
        ),
    )

    val timeouts = SystemConfigSection(
        id = "timeouts",
        title = "请求与超时",
        description = "数据面请求的尝试次数与各阶段超时。",
        items = listOf(
            item("CONNECT_TIMEOUT_MS", "连接上游超时", config.connectTimeoutMs, "duration"),
            item("FIRST_TOKEN_TIMEOUT_MS", "首 Token 超时", config.firstTokenTimeoutMs, "duration"),
            item("IDLE_TIMEOUT_MS", "流式空闲超时", config.idleTimeoutMs, "duration"),
            item("TOTAL_TIMEOUT_MS", "单请求总超时", config.totalTimeoutMs, "duration"),
            item(
                "MAX_ATTEMPTS",
                "最大尝试次数",
                config.maxAttempts,
                "number",
                description = "首 Token 返回前最多尝试的候选上游数量。",
            ),
            item("DRAIN_TIMEOUT_MS", "优雅停机 Drain 超时", config.drainTimeoutMs, "duration"),
        ),
    )

    val cooldown = SystemConfigSection(
        id = "cooldown",
        title = "冷却与恢复",
        description = "上游失败、429 与首 Token 过慢时的冷却策略。",
        items = listOf(
            item("COOLDOWN_TRANSIENT_BASE_MS", "瞬时错误冷却起步", config.cooldown.transientBaseMs, "duration"),
            item("COOLDOWN_TRANSIENT_MAX_MS", "瞬时错误冷却上限", config.cooldown.transientMaxMs, "duration"),
            item("COOLDOWN_RATE_LIMIT_DEFAULT_MS", "429 默认冷却", config.cooldown.rateLimitDefaultMs, "duration"),
            item("COOLDOWN_RATE_LIMIT_MAX_MS", "429 冷却上限", config.cooldown.rateLimitMaxMs, "duration"),
            item("COOLDOWN_AUTH_FAILURE_MS", "鉴权失败冷却", config.cooldown.authFailureMs, "duration"),
            item("TTFT_SLOW_THRESHOLD_MS", "首 Token 过慢阈值", config.cooldown.ttftSlowThresholdMs, "duration"),
            item("TTFT_SLOW_STREAK", "首 Token 过慢连续次数", config.cooldown.ttftSlowStreak, "number"),
            item("KEY_AUTO_RECOVERY_INTERVAL_MS", "临时停用恢复扫描间隔", config.keyAutoRecoveryIntervalMs, "duration"),
        ),
    )

    val upstream = SystemConfigSection(
        id = "upstream",
        title = "上游请求与插件",
        description = "请求头预设刷新与额度插件目录。",
        items = listOf(
            item("UPSTREAM_HEADER_REFRESH_ENABLED", "请求头预设自动刷新", config.upstreamHeaderRefreshEnabled, "boolean"),
            item("UPSTREAM_HEADER_REFRESH_INTERVAL_MS", "请求头预设刷新间隔", config.upstreamHeaderRefreshIntervalMs, "duration"),
            item(
                "UPSTREAM_HEADER_CATALOG_URL",
                "自定义请求头 Manifest",
                redactUrl(config.upstreamHeaderCatalogUrl),
                "text",
                description = "仅显示地址主体，查询参数与片段不会展示。",
            ),
            item("QUOTA_PLUGIN_DIR", "额度插件目录", config.quotaPluginDir, "text"),
        ),
    )

    val oauth = SystemConfigSection(
        id = "oauth",
        title = "订阅账号 OAuth",
        description = "订阅账号 token 刷新、额度扫描与账号级调度参数。",
        items = listOf(
            item("OAUTH_REFRESH_INTERVAL_MS", "Token 刷新扫描间隔", config.oauth.refreshIntervalMs, "duration"),
            item("OAUTH_REFRESH_AHEAD_MS", "Token 提前刷新窗口", config.oauth.refreshAheadMs, "duration"),
            item("OAUTH_REQUEST_TIMEOUT_MS", "OAuth 请求超时", config.oauth.requestTimeoutMs, "duration"),
            item("OAUTH_RATE_LIMIT_DEFAULT_MS", "账号 429 默认冷却", config.oauth.rateLimitDefaultMs, "duration"),
            item("OAUTH_RATE_LIMIT_MAX_MS", "账号 429 冷却上限", config.oauth.rateLimitMaxMs, "duration"),
            item("OAUTH_QUOTA_INTERVAL_MS", "额度扫描间隔", config.oauth.quotaIntervalMs, "duration"),
            item("OAUTH_QUOTA_MAX_AGE_MS", "额度读数有效期", config.oauth.quotaMaxAgeMs, "duration"),
            item(
                "OAUTH_ROUTING",
                "账号调度策略",
                config.oauth.routing.value,
                "text",
                description = "round_robin、drain、least_used 或 pace。",
            ),
            item(
                "OAUTH_PROXY_ALLOWED_HOSTS",
                "账号代理主机白名单",
                config.oauth.proxyAllowedHosts.sorted().joinToString(","),
                "list",
                description = "为空时仅允许回环地址作为账号代理。",
            ),
            item(
                "OAUTH_ANTIGRAVITY_CLIENT_ID",
                "Antigravity 提供方",
                if (config.oauth.antigravity != null) "已配置" else "未配置",
                "text",
                description = "仅展示提供方是否已配置，不展示客户端密钥。",
                derived = true,
            ),
        ),
    )

    return SystemConfigResponse(
        sections = listOf(runtime, timeouts, cooldown, upstream, oauth),
    )
}

/** 去掉查询参数、片段和用户信息，避免把 Manifest 凭据带到管理台。 */
private fun redactUrl(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val uri = runCatching { URI(raw) }.getOrNull() ?: return "已配置"
    val scheme = uri.scheme ?: return "已配置"
    val host = uri.host ?: return "已配置"
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "$scheme://$host$port${uri.path.orEmpty()}"
}
