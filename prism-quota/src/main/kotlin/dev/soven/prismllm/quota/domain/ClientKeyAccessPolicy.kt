package dev.soven.prismllm.quota.domain

/** 获准使用的静态渠道密钥；渠道与密钥 ID 共同构成身份。 */
data class ClientKeyAllowedChannelKey(val channelId: String, val keyId: String)

/**
 * 调用方密钥的访问范围，只保存对象 ID，不依赖渠道或路由类型。
 *
 * 每个维度的 `null` 表示不限制，空集合表示白名单模式且无授权对象。
 * 同维度取并集，不同维度取交集；模型 ID 保持原有大小写。
 */
data class ClientKeyAccessPolicy(
    val allowedChannelIds: Set<String>? = null,
    val allowedChannelKeys: Set<ClientKeyAllowedChannelKey>? = null,
    val allowedModelIds: Set<String>? = null,
) {
    /** 仅三个维度均未限制时为真。 */
    val isUnrestricted: Boolean
        get() = allowedChannelIds == null && allowedChannelKeys == null && allowedModelIds == null

    /**
     * 去除 ID 首尾空白、去重并验证持久化长度，保留各维度原有模式。
     *
     * @throws IllegalArgumentException ID 为空或超出数据库列的长度上限。
     */
    fun normalized(): ClientKeyAccessPolicy = copy(
        allowedChannelIds = allowedChannelIds?.map { normalizeId(it, 36) }?.toSet(),
        allowedChannelKeys = allowedChannelKeys?.map {
            ClientKeyAllowedChannelKey(normalizeId(it.channelId, 36), normalizeId(it.keyId, 36))
        }?.toSet(),
        allowedModelIds = allowedModelIds?.map { normalizeId(it, 200) }?.toSet(),
    )

    /** 将单个标识转为可持久化形式。 */
    private fun normalizeId(value: String, maxLength: Int): String = value.trim().also {
        require(it.length in 1..maxLength) { "访问范围 ID 长度必须为 1 至 $maxLength 个字符" }
    }
}
