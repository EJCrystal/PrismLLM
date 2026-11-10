package dev.soven.llmgateway.domain

import dev.soven.llmgateway.admin.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.config.WebSearchConfig

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
