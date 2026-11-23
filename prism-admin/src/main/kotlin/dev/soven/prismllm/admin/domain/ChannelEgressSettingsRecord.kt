package dev.soven.prismllm.admin.domain

/**
 * 管理台保存的渠道出口代理主机白名单。只补充环境变量的部署默认值，
 * 不会移除 CHANNEL_PROXY_ALLOWED_HOSTS 已放行的主机。
 *
 * @author 文杰
 * @since 2026-11-23
 */
data class ChannelEgressSettingsRecord(
    val allowedHosts: List<String>,
    val updatedAt: Long,
)
