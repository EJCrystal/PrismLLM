package dev.soven.prismllm.routing.domain.candidate

import dev.soven.prismllm.shared.domain.UpstreamCredential

/** 访问名单中的渠道凭据；身份由渠道、类型与凭据 ID 共同确定。 */
data class RoutingAllowedChannelKey(
    val channelId: String,
    val keyId: String,
    val isOAuth: Boolean = false,
)

/**
 * 路由上下文自己的只读访问范围，不依赖调用方密钥或配额上下文。
 *
 * 同维度的 allowed/denied 集合互斥，都为 `null` 表示不限制；不同维度取交集。
 * 空白名单拒绝全部，空黑名单不额外限制。模型精确匹配对外 ID。
 * 静态密钥白名单排除 OAuth，静态密钥黑名单只排除静态身份。
 *
 * @param allowedChannelIds 获准渠道 ID；`null` 表示不限制。
 * @param allowedChannelKeys 获准静态密钥身份；`null` 表示不限制。
 * @param allowedModelIds 获准对外模型 ID；`null` 表示不限制。
 * @param deniedChannelIds 禁止渠道 ID；`null` 表示未选择黑名单模式。
 * @param deniedChannelKeys 禁止静态密钥身份；不匹配 OAuth。
 * @param deniedModelIds 禁止对外模型 ID；精确且区分大小写。
 */
data class RoutingAccessScope(
    val allowedChannelIds: Set<String>? = null,
    val allowedChannelKeys: Set<RoutingAllowedChannelKey>? = null,
    val allowedModelIds: Set<String>? = null,
    val deniedChannelIds: Set<String>? = null,
    val deniedChannelKeys: Set<RoutingAllowedChannelKey>? = null,
    val deniedModelIds: Set<String>? = null,
) {
    /** 实际不增加授权限制时为真，包括空黑名单。 */
    val isUnrestricted: Boolean
        get() = allowedChannelIds == null && allowedChannelKeys == null && allowedModelIds == null &&
            deniedChannelIds.isNullOrEmpty() && deniedChannelKeys.isNullOrEmpty() && deniedModelIds.isNullOrEmpty()

    /** 判断对外模型 ID 是否获准，不改写大小写或匹配通配符。 */
    fun allowsModel(publicModelId: String): Boolean =
        (allowedModelIds?.contains(publicModelId) ?: true) && publicModelId !in deniedModelIds.orEmpty()

    /** 判断渠道是否在当前访问范围内。 */
    fun allowsChannel(channelId: String): Boolean =
        (allowedChannelIds?.contains(channelId) ?: true) && channelId !in deniedChannelIds.orEmpty()

    /** 判断渠道与凭据交集；OAuth 不匹配静态黑名单，但受渠道限制和静态白名单约束。 */
    fun allowsCredential(channelId: String, credential: UpstreamCredential): Boolean =
        allowsCredential(channelId, credential.id, credential.isOAuth)

    /** 身份授权不依赖凭据是否仍存在或启用，供已保存路由的不可用诊断使用。 */
    fun allowsCredential(channelId: String, credentialId: String, isOAuth: Boolean): Boolean {
        val identity = RoutingAllowedChannelKey(channelId, credentialId, isOAuth)
        return allowsChannel(channelId) && (allowedChannelKeys?.let { identity in it } ?: true) &&
            identity !in deniedChannelKeys.orEmpty()
    }
}
