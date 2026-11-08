package dev.soven.llmgateway.domain

/**
 * 管理台保存的 IQ 视觉判分配置。apiKey 为解密后的明文，只在服务端内存中流转，
 * 不会返回管理台。没有保存过配置时沿用环境变量默认值。
 *
 * @author 文杰
 * @since 2026-11-08
 */
data class IqVisionSettingsRecord(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val timeoutMs: Long,
    val updatedAt: Long,
    /** 密文存在但无法解密时为 true；此时 apiKey 为 null，管理员需要重新填写。 */
    val apiKeyUnreadable: Boolean = false,
)

/** 视觉判分实际使用的模型端点；由管理台设置覆盖部署默认值。 */
data class IqVisionConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val timeoutMs: Long,
) {
    /** 端点与模型都齐备时才可能调用成功。 */
    val usable: Boolean
        get() = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
}

/**
 * 合并管理台设置与环境变量默认值。
 *
 * 密钥与端点绑定：只有管理台端点与环境变量端点一致时才回退到环境变量里的密钥。
 * 否则改了端点却仍拿环境变量密钥去请求，就等于把凭据发给另一个服务。
 *
 * 保存过密钥但无法解密时同样不回退：管理员显式配置过的密钥坏了，应该提示重填，
 * 而不是静默换成另一个账号的凭据继续跑——那会在 401 时才暴露。
 */
fun IqVisionSettingsRecord?.effective(defaults: IqVisionConfig): IqVisionConfig = this?.let { stored ->
    val baseUrl = stored.baseUrl.ifBlank { defaults.baseUrl }
    val sameBaseUrl = baseUrl.trimEnd('/').equals(defaults.baseUrl.trimEnd('/'), ignoreCase = true)
    IqVisionConfig(
        enabled = stored.enabled,
        baseUrl = baseUrl,
        model = stored.model.ifBlank { defaults.model },
        apiKey = when {
            stored.apiKey != null -> stored.apiKey
            stored.apiKeyUnreadable -> null
            sameBaseUrl -> defaults.apiKey
            else -> null
        },
        timeoutMs = stored.timeoutMs,
    )
} ?: defaults
