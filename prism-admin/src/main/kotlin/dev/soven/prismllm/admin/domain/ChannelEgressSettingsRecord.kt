package dev.soven.prismllm.admin.domain

/**
 * 管理台保存的渠道出口代理主机放行名单。
 *
 * @author 文杰
 * @since 2026-11-23
 */
data class ChannelEgressSettingsRecord(
    val allowedHosts: List<String>,
    /** 每次保存递增；管理台用它做乐观并发校验，避免覆盖其他管理员的修改。 */
    val revision: Long,
    val updatedAt: Long,
)
