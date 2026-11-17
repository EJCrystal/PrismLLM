package dev.soven.prismllm.api

import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import dev.soven.prismllm.quota.domain.ClientKeyCredentialType
import dev.soven.prismllm.shared.error.badRequest

/**
 * 规范化并校验完整访问范围；停用的配置对象仍可作为授权目标。
 *
 * 校验全部成功后才把策略交给仓储写入，非法对象不会产生部分更新。
 * 更新允许保留已有模型黑名单 ID，避免删除映射后丢失同 ID 重新配置时的排除规则。
 * 新增模型 ID 必须已有模型路由；已有选择即使路由后来失效也保留，避免阻止其他维度修改。
 *
 * @param request 完整的三维访问策略。
 * @param app 用于读取配置目录的应用服务。
 * @param existing 更新前策略；创建时为空，新增模型 ID 必须存在于配置目录。
 */
suspend fun validateClientKeyAccessPolicy(
    request: ClientKeyAccessPolicyDto,
    app: GatewayApplication,
    existing: ClientKeyAccessPolicy? = null,
): ClientKeyAccessPolicy {
    validateMode(request.channels.mode, request.channels.ids.isEmpty())
    validateMode(request.channelKeys.mode, request.channelKeys.keys.isEmpty())
    validateMode(request.models.mode, request.models.ids.isEmpty())
    val policy = try {
        ClientKeyAccessPolicy(
            allowedChannelIds = if (request.channels.mode == "allowlist") request.channels.ids.toSet() else null,
            allowedChannelKeys = if (request.channelKeys.mode == "allowlist") request.channelKeys.keys
                .map { it.toDomain() }.toSet() else null,
            allowedModelIds = if (request.models.mode == "allowlist") request.models.ids.toSet() else null,
            deniedChannelIds = if (request.channels.mode == "denylist") request.channels.ids.toSet() else null,
            deniedChannelKeys = if (request.channelKeys.mode == "denylist") request.channelKeys.keys
                .map { it.toDomain() }.toSet() else null,
            deniedModelIds = if (request.models.mode == "denylist") request.models.ids.toSet() else null,
        ).normalized()
    } catch (cause: IllegalArgumentException) {
        throw badRequest("invalid_client_key_access_policy", cause.message ?: "访问范围 ID 无效")
    }
    if (policy.isUnrestricted) return policy

    val channels = app.channelRepository.list().associateBy { it.id }
    val oauthAccountsByChannel = app.oauthAccountRepository.listByChannel()
    val allowedChannels = policy.allowedChannelIds
    if ((policy.allowedChannelIds.orEmpty() + policy.deniedChannelIds.orEmpty()).any { it !in channels }) {
        throw badRequest("invalid_client_key_access_policy", "访问范围中的渠道不存在")
    }
    for (key in policy.allowedChannelKeys.orEmpty() + policy.deniedChannelKeys.orEmpty()) {
        val channel = channels[key.channelId]
            ?: throw badRequest("invalid_client_key_access_policy", "渠道凭据所属渠道不存在")
        val exists = when (key.credentialType) {
            ClientKeyCredentialType.STATIC -> channel.keys.any { it.id == key.keyId }
            ClientKeyCredentialType.OAUTH -> oauthAccountsByChannel[key.channelId].orEmpty().any { it.id == key.keyId }
        }
        if (!exists) {
            throw badRequest("invalid_client_key_access_policy", "渠道凭据不存在或不属于指定渠道")
        }
        if (key in policy.allowedChannelKeys.orEmpty() &&
            ((allowedChannels != null && key.channelId !in allowedChannels) || key.channelId in policy.deniedChannelIds.orEmpty())) {
            throw badRequest("invalid_client_key_access_policy", "渠道凭据必须属于允许的渠道")
        }
    }
    val routedModels = app.modelCredentialRouteRepository.list().mapTo(mutableSetOf()) { it.modelId }
    val newModelIds = (policy.allowedModelIds.orEmpty() - existing?.allowedModelIds.orEmpty()) +
        (policy.deniedModelIds.orEmpty() - existing?.deniedModelIds.orEmpty())
    if (newModelIds.any { it !in routedModels }) {
        throw badRequest("invalid_client_key_access_policy", "访问范围新增的对外模型 ID 没有已保存的模型路由")
    }
    return policy
}

/** 验证显式模式，以及不限制模式的空数组约束。 */
private fun ClientKeyAllowedChannelKeyDto.toDomain(): ClientKeyAllowedChannelKey {
    val type = ClientKeyCredentialType.fromValue(credentialType)
        ?: throw badRequest("invalid_client_key_access_policy", "渠道凭据类型必须为 static 或 oauth")
    return ClientKeyAllowedChannelKey(channelId, keyId, type)
}

private fun validateMode(mode: String, empty: Boolean) {
    if (mode != "unrestricted" && mode != "allowlist" && mode != "denylist") {
        throw badRequest("invalid_client_key_access_policy", "访问范围模式必须为 unrestricted、allowlist 或 denylist")
    }
    if (mode == "unrestricted" && !empty) {
        throw badRequest("invalid_client_key_access_policy", "不限制模式的名单必须为空")
    }
}
