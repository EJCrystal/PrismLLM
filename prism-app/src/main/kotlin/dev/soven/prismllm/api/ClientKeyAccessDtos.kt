package dev.soven.prismllm.api

import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import kotlinx.serialization.Serializable

/** 调用方访问范围的完整管理接口表示，三个维度必须显式提供。 */
@Serializable
data class ClientKeyAccessPolicyDto(
    val channels: ClientKeyIdScopeDto,
    val channelKeys: ClientKeyChannelKeysScopeDto,
    val models: ClientKeyIdScopeDto,
)

/** 渠道或模型的授权维度；不限制模式必须提供空 ID 数组。 */
@Serializable
data class ClientKeyIdScopeDto(val mode: String, val ids: List<String>)

/** 静态渠道密钥的授权维度；不限制模式必须提供空密钥数组。 */
@Serializable
data class ClientKeyChannelKeysScopeDto(val mode: String, val keys: List<ClientKeyAllowedChannelKeyDto>)

/** 管理接口中的渠道密钥身份，不包含凭据明文。 */
@Serializable
data class ClientKeyAllowedChannelKeyDto(val channelId: String, val keyId: String)

/** 将领域策略转换为完整、排序稳定的管理响应。 */
fun ClientKeyAccessPolicy.toDto(): ClientKeyAccessPolicyDto = ClientKeyAccessPolicyDto(
    channels = ClientKeyIdScopeDto(if (allowedChannelIds == null) "unrestricted" else "allowlist", allowedChannelIds.orEmpty().sorted()),
    channelKeys = ClientKeyChannelKeysScopeDto(
        if (allowedChannelKeys == null) "unrestricted" else "allowlist",
        allowedChannelKeys.orEmpty().sortedWith(compareBy({ it.channelId }, { it.keyId }))
            .map { ClientKeyAllowedChannelKeyDto(it.channelId, it.keyId) },
    ),
    models = ClientKeyIdScopeDto(if (allowedModelIds == null) "unrestricted" else "allowlist", allowedModelIds.orEmpty().sorted()),
)
