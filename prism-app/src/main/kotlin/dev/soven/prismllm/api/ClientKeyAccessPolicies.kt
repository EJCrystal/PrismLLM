package dev.soven.prismllm.api

import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import dev.soven.prismllm.shared.error.badRequest

/**
 * 规范化并校验完整访问范围；停用的配置对象仍可作为授权目标。
 *
 * 校验全部成功后才把策略交给仓储写入，非法对象不会产生部分更新。
 */
suspend fun validateClientKeyAccessPolicy(request: ClientKeyAccessPolicyDto, app: GatewayApplication): ClientKeyAccessPolicy {
    validateMode(request.channels.mode, request.channels.ids.isEmpty())
    validateMode(request.channelKeys.mode, request.channelKeys.keys.isEmpty())
    validateMode(request.models.mode, request.models.ids.isEmpty())
    val policy = try {
        ClientKeyAccessPolicy(
            allowedChannelIds = if (request.channels.mode == "allowlist") request.channels.ids.toSet() else null,
            allowedChannelKeys = if (request.channelKeys.mode == "allowlist") request.channelKeys.keys
                .map { ClientKeyAllowedChannelKey(it.channelId, it.keyId) }.toSet() else null,
            allowedModelIds = if (request.models.mode == "allowlist") request.models.ids.toSet() else null,
        ).normalized()
    } catch (cause: IllegalArgumentException) {
        throw badRequest("invalid_client_key_access_policy", cause.message ?: "访问范围 ID 无效")
    }
    if (policy.isUnrestricted) return policy

    val channels = app.channelRepository.list().associateBy { it.id }
    val allowedChannels = policy.allowedChannelIds
    if (policy.allowedChannelIds.orEmpty().any { it !in channels }) {
        throw badRequest("invalid_client_key_access_policy", "访问范围中的渠道不存在")
    }
    for (key in policy.allowedChannelKeys.orEmpty()) {
        val channel = channels[key.channelId]
            ?: throw badRequest("invalid_client_key_access_policy", "渠道密钥所属渠道不存在")
        if (channel.keys.none { it.id == key.keyId }) {
            throw badRequest("invalid_client_key_access_policy", "渠道密钥不存在或不属于指定渠道")
        }
        if (allowedChannels != null && key.channelId !in allowedChannels) {
            throw badRequest("invalid_client_key_access_policy", "渠道密钥必须属于允许的渠道")
        }
    }
    val configuredModels = channels.values.flatMap { channel -> channel.modelMappings.map { it.publicModelId } }.toSet()
    if (policy.allowedModelIds.orEmpty().any { it !in configuredModels }) {
        throw badRequest("invalid_client_key_access_policy", "访问范围中的对外模型 ID 不存在")
    }
    return policy
}

/** 验证显式模式，以及不限制模式的空数组约束。 */
private fun validateMode(mode: String, empty: Boolean) {
    if (mode != "unrestricted" && mode != "allowlist") {
        throw badRequest("invalid_client_key_access_policy", "访问范围模式必须为 unrestricted 或 allowlist")
    }
    if (mode == "unrestricted" && !empty) {
        throw badRequest("invalid_client_key_access_policy", "不限制模式的白名单必须为空")
    }
}
