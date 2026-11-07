package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.WEB_SEARCH_PROVIDERS
import dev.soven.llmgateway.config.WebSearchConfig
import dev.soven.llmgateway.config.webSearchBaseUrlError
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.domain.effective
import kotlinx.serialization.Serializable

/**
 * 管理台写入 Web 搜索设置。apiKey 留空表示沿用已保存的密钥或环境变量默认值；
 * clearApiKey 为 true 时清除管理台保存的密钥并回退到环境变量。
 */
@Serializable
data class WebSearchSettingsRequest(
    val enabled: Boolean,
    val provider: String,
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
    val baseUrl: String? = null,
    val maxResults: Int = 6,
    val timeoutMs: Long = 15_000,
    val maxRounds: Int = 6,
    val maxPageChars: Int = 1_500,
)

/** 管理台展示的 Web 搜索设置；不包含密钥明文。 */
@Serializable
data class WebSearchSettingsResponse(
    val enabled: Boolean,
    val provider: String,
    val apiKeySet: Boolean,
    /** settings、environment 或 none。 */
    val apiKeySource: String,
    /** 管理台保存过密钥但当前无法解密，需要重新填写。 */
    val apiKeyUnreadable: Boolean,
    val baseUrl: String?,
    val maxResults: Int,
    val timeoutMs: Long,
    val maxRounds: Int,
    val maxPageChars: Int,
    /** settings 表示管理台保存过设置，environment 表示使用环境变量默认值。 */
    val source: String,
    val updatedAt: Long,
)

internal fun webSearchSettingsResponse(
    stored: WebSearchSettingsRecord?,
    defaults: WebSearchConfig,
): WebSearchSettingsResponse {
    val effective = stored.effective(defaults)
    return WebSearchSettingsResponse(
        enabled = effective.enabled,
        provider = effective.provider,
        apiKeySet = effective.apiKey != null,
        apiKeySource = when {
            effective.apiKey == null -> "none"
            stored?.apiKey != null -> "settings"
            else -> "environment"
        },
        apiKeyUnreadable = stored?.apiKeyUnreadable == true,
        baseUrl = effective.baseUrl,
        maxResults = effective.maxResults,
        timeoutMs = effective.timeoutMs,
        maxRounds = effective.maxRounds,
        maxPageChars = effective.maxPageChars,
        source = if (stored != null) "settings" else "environment",
        updatedAt = stored?.updatedAt ?: 0,
    )
}

/** 返回校验错误；null 表示可以保存。 */
internal fun webSearchSettingsError(config: WebSearchConfig): String? {
    val baseUrlError = webSearchBaseUrlError(
        config.provider,
        config.baseUrl,
        requireHttps = config.enabled && config.provider != "searxng",
    )
    return when {
        config.provider !in WEB_SEARCH_PROVIDERS ->
            "搜索后端必须是 ${WEB_SEARCH_PROVIDERS.joinToString("、")} 之一"
        config.maxResults !in 1..20 -> "每次搜索返回页面数必须在 1..20 之间"
        config.timeoutMs !in 1_000..120_000 -> "单次搜索超时必须在 1000..120000 毫秒之间"
        config.maxRounds !in 1..10 -> "搜索轮数必须在 1..10 之间"
        config.maxPageChars !in 100..10_000 -> "单页字符上限必须在 100..10000 之间"
        baseUrlError != null -> baseUrlError
        config.enabled && config.provider == "searxng" && config.baseUrl.isNullOrBlank() ->
            "SearXNG 需要配置搜索地址"
        config.enabled && config.provider != "searxng" && config.apiKey.isNullOrBlank() ->
            "启用搜索代理需要配置 API 密钥"
        else -> null
    }
}
