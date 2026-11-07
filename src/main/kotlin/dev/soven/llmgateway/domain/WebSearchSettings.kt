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
)

/**
 * 合并管理台设置与环境变量默认值。管理台未保存的密钥或地址回退到默认值，
 * 避免只改开关时把环境变量里已有的密钥清空。
 */
fun WebSearchSettingsRecord?.effective(defaults: WebSearchConfig): WebSearchConfig = this?.let { stored ->
    WebSearchConfig(
        enabled = stored.enabled,
        provider = stored.provider,
        apiKey = stored.apiKey ?: defaults.apiKey,
        baseUrl = stored.baseUrl ?: defaults.baseUrl,
        maxResults = stored.maxResults,
        timeoutMs = stored.timeoutMs,
        maxRounds = stored.maxRounds,
        maxPageChars = stored.maxPageChars,
    )
} ?: defaults
