package dev.soven.prismllm.api

import kotlinx.serialization.Serializable

/** 管理台放行的渠道出口代理主机；保存后与部署环境变量取并集。 */
@Serializable
data class ChannelEgressSettingsRequest(
    val hosts: List<String> = emptyList(),
)

@Serializable
data class ChannelEgressSettingsResponse(
    /** 管理台保存的主机，可在管理台增删。 */
    val hosts: List<String>,
    /** CHANNEL_PROXY_ALLOWED_HOSTS 提供的部署默认值，只读。 */
    val deploymentHosts: List<String>,
    /** 实际生效的并集，包含回环主机的隐式放行。 */
    val effectiveHosts: List<String>,
    val updatedAt: Long? = null,
)

fun ChannelProxyHostAllowlist.toResponse() = ChannelEgressSettingsResponse(
    hosts = storedHosts(),
    deploymentHosts = deploymentHosts(),
    effectiveHosts = effectiveHosts(),
    updatedAt = storedUpdatedAt(),
)
