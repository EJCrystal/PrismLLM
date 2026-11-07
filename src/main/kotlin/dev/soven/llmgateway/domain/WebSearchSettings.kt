package dev.soven.llmgateway.domain

import dev.soven.llmgateway.config.WebSearchConfig

/**
 * 管理台保存的 Web 搜索设置。apiKey 为解密后的明文，只在服务端内存中流转，
 * 不会返回管理台。没有保存过设置时沿用环境变量默认值。
 *
 * @author 文杰
 * @since 2026-11-07
 */
data class WebSearchSettingsRecord(
    val enabled: Boolean,
    val provider: String,
    val apiKey: String?,
    val baseUrl: String?,
    val maxResults: Int,
    val timeoutMs: Long,
    val maxRounds: Int,
    val maxPageChars: Int,
    val updatedAt: Long,
    /** 密文存在但无法解密时为 true；此时 apiKey 为 null，管理员需要重新填写。 */
    val apiKeyUnreadable: Boolean = false,
)

/**
 * 合并管理台设置与环境变量默认值。只有管理台保存的后端与环境变量后端一致时，
 * 才回退到环境变量里的密钥或地址；切换后端后不能把旧后端的密钥发给新后端。
 */
fun WebSearchSettingsRecord?.effective(defaults: WebSearchConfig): WebSearchConfig = this?.let { stored ->
    val sameProvider = stored.provider.equals(defaults.provider, ignoreCase = true)
    WebSearchConfig(
        enabled = stored.enabled,
        provider = stored.provider,
        apiKey = stored.apiKey ?: defaults.apiKey.takeIf { sameProvider },
        baseUrl = stored.baseUrl ?: defaults.baseUrl.takeIf { sameProvider },
        maxResults = stored.maxResults,
        timeoutMs = stored.timeoutMs,
        maxRounds = stored.maxRounds,
        maxPageChars = stored.maxPageChars,
    )
} ?: defaults
