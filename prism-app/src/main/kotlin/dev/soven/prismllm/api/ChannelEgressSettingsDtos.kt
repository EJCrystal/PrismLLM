package dev.soven.prismllm.api

import kotlinx.serialization.Serializable

/** 管理台管理的渠道出口代理主机放行名单。 */
@Serializable
data class ChannelEgressSettingsRequest(
    val hosts: List<String> = emptyList(),
    /** 读取时的版本；不一致说明其他管理员已修改，服务端返回冲突。 */
    val expectedRevision: Long? = null,
)

@Serializable
data class ChannelEgressSettingsResponse(
    /** 管理台保存的主机，可在管理台增删。 */
    val hosts: List<String>,
    /** 当前实际生效的放行主机。 */
    val effectiveHosts: List<String>,
    /** 白名单版本；下一次保存可回传它做乐观并发校验。 */
    val revision: Long,
    val updatedAt: Long? = null,
)

fun ChannelProxyHostAllowlist.toResponse(): ChannelEgressSettingsResponse {
    // 只读取一次不可变快照，避免四个字段来自不同版本。
    val state = snapshot()
    return ChannelEgressSettingsResponse(
        hosts = state.storedHosts,
        effectiveHosts = state.effectiveHosts,
        revision = state.revision,
        updatedAt = state.updatedAt.takeIf { it > 0 },
    )
}
