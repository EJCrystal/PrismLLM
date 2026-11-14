package dev.soven.prismllm.routing.domain.candidate

import dev.soven.prismllm.shared.domain.UpstreamCredential

/** 获准使用的静态渠道密钥，身份由渠道 ID 与密钥 ID 共同确定。 */
data class RoutingAllowedChannelKey(val channelId: String, val keyId: String)

/**
 * 路由上下文自己的只读访问范围，不依赖调用方密钥或配额上下文。
 *
 * 每个维度的 `null` 表示不限制，空集合表示没有获准对象；同维度取并集，不同维度取交集。
 * 模型使用请求中的对外 ID 精确匹配，静态密钥白名单始终排除 OAuth 凭据。
 *
 * @param allowedChannelIds 获准渠道 ID；`null` 表示不限制。
 * @param allowedChannelKeys 获准静态密钥身份；`null` 表示不限制。
 * @param allowedModelIds 获准对外模型 ID；`null` 表示不限制。
 */
data class RoutingAccessScope(
    val allowedChannelIds: Set<String>? = null,
    val allowedChannelKeys: Set<RoutingAllowedChannelKey>? = null,
    val allowedModelIds: Set<String>? = null,
) {
    /** 仅三个维度均为不限制模式时为真。 */
    val isUnrestricted: Boolean
        get() = allowedChannelIds == null && allowedChannelKeys == null && allowedModelIds == null

    /** 判断对外模型 ID 是否获准，不改写大小写或匹配通配符。 */
    fun allowsModel(publicModelId: String): Boolean = allowedModelIds?.contains(publicModelId) ?: true

    /** 判断渠道是否在当前访问范围内。 */
    fun allowsChannel(channelId: String): Boolean = allowedChannelIds?.contains(channelId) ?: true

    /** 判断渠道与凭据的授权交集；静态密钥白名单不接受同 ID 的 OAuth 凭据。 */
    fun allowsCredential(channelId: String, credential: UpstreamCredential): Boolean =
        allowsChannel(channelId) && (allowedChannelKeys?.let { keys ->
            !credential.isOAuth && RoutingAllowedChannelKey(channelId, credential.id) in keys
        } ?: true)
}
