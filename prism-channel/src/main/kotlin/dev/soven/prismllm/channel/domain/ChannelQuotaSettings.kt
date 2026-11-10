package dev.soven.prismllm.channel.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 空 providerId 表示未启用。config 只保存非敏感参数，独立凭据另行加密存储。 */
@Serializable
data class ChannelQuotaSettings(
    val providerId: String = "",
    val config: JsonObject = JsonObject(emptyMap()),
    val credentialSource: ChannelQuotaCredentialSource = ChannelQuotaCredentialSource.CHANNEL_KEY,
)

@Serializable
enum class ChannelQuotaCredentialSource { CHANNEL_KEY, DEDICATED }
