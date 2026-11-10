package dev.soven.prismllm.admin.domain

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
