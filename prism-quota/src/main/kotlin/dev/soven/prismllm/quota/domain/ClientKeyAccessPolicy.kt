package dev.soven.prismllm.quota.domain

/** 调用方凭据类型；旧 API/数据缺省为静态渠道密钥。 */
enum class ClientKeyCredentialType(val value: String) {
    STATIC("static"),
    OAUTH("oauth"),
    ;

    companion object {
        fun fromValue(value: String): ClientKeyCredentialType? = entries.firstOrNull { it.value == value }
    }
}

/** 访问名单中的渠道凭据；渠道、类型与凭据 ID 共同构成身份。 */
data class ClientKeyAllowedChannelKey(
    val channelId: String,
    val keyId: String,
    val credentialType: ClientKeyCredentialType = ClientKeyCredentialType.STATIC,
)

/**
 * 调用方密钥的访问范围，只保存对象 ID，不依赖渠道或路由类型。
 *
 * 同维度的 allowed/denied 集合互斥；都为 `null` 表示不限制。
 * 非 null 空集合保留已选模式：空白名单拒绝全部，空黑名单不额外限制。
 * 不同维度取交集；模型 ID 保持原有大小写。
 */
data class ClientKeyAccessPolicy(
    val allowedChannelIds: Set<String>? = null,
    val allowedChannelKeys: Set<ClientKeyAllowedChannelKey>? = null,
    val allowedModelIds: Set<String>? = null,
    val deniedChannelIds: Set<String>? = null,
    val deniedChannelKeys: Set<ClientKeyAllowedChannelKey>? = null,
    val deniedModelIds: Set<String>? = null,
) {
    /** 实际没有增加授权限制时为真；空黑名单与不限制拥有相同授权行为。 */
    val isUnrestricted: Boolean
        get() = allowedChannelIds == null && allowedChannelKeys == null && allowedModelIds == null &&
            deniedChannelIds.isNullOrEmpty() && deniedChannelKeys.isNullOrEmpty() && deniedModelIds.isNullOrEmpty()

    /**
     * 去除 ID 首尾空白、去重并验证持久化长度，保留各维度原有模式。
     *
     * @throws IllegalArgumentException 同维度模式冲突，或 ID 为空/超出数据库列的长度上限。
     */
    fun normalized(): ClientKeyAccessPolicy {
        require(allowedChannelIds == null || deniedChannelIds == null) { "渠道白名单与黑名单不能同时启用" }
        require(allowedChannelKeys == null || deniedChannelKeys == null) { "渠道密钥白名单与黑名单不能同时启用" }
        require(allowedModelIds == null || deniedModelIds == null) { "模型白名单与黑名单不能同时启用" }
        return copy(
            allowedChannelIds = allowedChannelIds?.map { normalizeId(it, 36) }?.toSet(),
            allowedChannelKeys = allowedChannelKeys?.map {
                ClientKeyAllowedChannelKey(normalizeId(it.channelId, 36), normalizeId(it.keyId, 36), it.credentialType)
            }?.toSet(),
            allowedModelIds = allowedModelIds?.map { normalizeId(it, 200) }?.toSet(),
            deniedChannelIds = deniedChannelIds?.map { normalizeId(it, 36) }?.toSet(),
            deniedChannelKeys = deniedChannelKeys?.map {
                ClientKeyAllowedChannelKey(normalizeId(it.channelId, 36), normalizeId(it.keyId, 36), it.credentialType)
            }?.toSet(),
            deniedModelIds = deniedModelIds?.map { normalizeId(it, 200) }?.toSet(),
        )
    }

    /** 将单个标识转为可持久化形式。 */
    private fun normalizeId(value: String, maxLength: Int): String = value.trim().also {
        require(it.length in 1..maxLength) { "访问范围 ID 长度必须为 1 至 $maxLength 个字符" }
    }
}
