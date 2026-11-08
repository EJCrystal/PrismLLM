package dev.soven.llmgateway.api

import dev.soven.llmgateway.domain.IqVisionConfig
import dev.soven.llmgateway.domain.IqVisionSettingsRecord
import dev.soven.llmgateway.domain.effective
import kotlinx.serialization.Serializable

/**
 * 管理台写入 IQ 视觉判分配置。apiKey 留空表示沿用已保存的密钥或环境变量默认值；
 * clearApiKey 为 true 时清除管理台保存的密钥并回退到环境变量。
 */
@Serializable
data class IqVisionSettingsRequest(
    val enabled: Boolean,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
    val timeoutMs: Long = 60_000,
)

/** 管理台展示的 IQ 视觉判分配置；不包含密钥明文。 */
@Serializable
data class IqVisionSettingsResponse(
    val enabled: Boolean,
    val baseUrl: String,
    val model: String,
    val apiKeySet: Boolean,
    /** settings、environment 或 none。 */
    val apiKeySource: String,
    /** 管理台保存过密钥但当前无法解密，需要重新填写。 */
    val apiKeyUnreadable: Boolean,
    val timeoutMs: Long,
    /** 单帧浏览器渲染超时，来自部署配置，不能在管理台修改。 */
    val browserTimeoutMs: Long,
    /** 6 帧渲染加一次视觉调用的最坏耗时，用于提示 IQ_JUDGE_TIMEOUT_MS 是否够用。 */
    val worstCaseBudgetMs: Long,
    /** 判分总超时，来自 IQ_JUDGE_TIMEOUT_MS；小于最坏预算时视觉判分会提前失败。 */
    val judgeTimeoutMs: Long,
    /** settings 表示管理台保存过配置，environment 表示使用环境变量默认值。 */
    val source: String,
    val updatedAt: Long,
    /** 无头浏览器是否可用；不可用时视觉判分只会返回需人工查看。 */
    val browserAvailable: Boolean,
)

internal fun iqVisionSettingsResponse(
    stored: IqVisionSettingsRecord?,
    defaults: IqVisionConfig,
    browserAvailable: Boolean,
    browserTimeoutMs: Long,
    judgeTimeoutMs: Long,
): IqVisionSettingsResponse {
    val effective = stored.effective(defaults)
    return IqVisionSettingsResponse(
        enabled = effective.enabled,
        baseUrl = effective.baseUrl,
        model = effective.model,
        apiKeySet = effective.apiKey != null,
        apiKeySource = when {
            effective.apiKey == null -> "none"
            stored?.apiKey != null -> "settings"
            else -> "environment"
        },
        apiKeyUnreadable = stored?.apiKeyUnreadable == true,
        timeoutMs = effective.timeoutMs,
        browserTimeoutMs = browserTimeoutMs,
        worstCaseBudgetMs = 6 * browserTimeoutMs + effective.timeoutMs,
        judgeTimeoutMs = judgeTimeoutMs,
        source = if (stored != null) "settings" else "environment",
        updatedAt = stored?.updatedAt ?: 0,
        browserAvailable = browserAvailable,
    )
}

/**
 * 校验一组视觉判分配置；通过时返回 null。
 *
 * 列宽校验放在启用判断之前：关闭状态下也会写库，超长值会撞上 varchar 上限变成 500。
 */
internal fun iqVisionSettingsError(config: IqVisionConfig): String? {
    val baseUrl = config.baseUrl.trim()
    return when {
        config.timeoutMs !in 1_000..300_000 -> "视觉模型超时必须在 1000..300000 毫秒之间"
        baseUrl.length > 500 -> "模型端点长度不能超过 500 个字符"
        config.model.length > 200 -> "模型名长度不能超过 200 个字符"
        !config.enabled -> null
        baseUrl.isEmpty() -> "启用视觉判分需要配置模型端点"
        !config.usable ->
            "模型端点必须是带主机名的 http:// 或 https:// 地址，端口需在 1..65535 之间"
        config.model.isBlank() -> "启用视觉判分需要配置模型名"
        else -> null
    }
}
