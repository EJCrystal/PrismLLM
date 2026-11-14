package dev.soven.prismllm.quota.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** 渠道授权关联；渠道外键由迁移定义，避免配额模块依赖渠道模块。 */
object ClientKeyAllowedChannels : Table("client_key_allowed_channels") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36)
    override val primaryKey = PrimaryKey(clientKeyId, channelId)
}

/** 静态渠道密钥授权关联；复合外键由迁移保证密钥的渠道归属。 */
object ClientKeyAllowedChannelKeys : Table("client_key_allowed_channel_keys") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36)
    val keyId = varchar("key_id", 36)
    override val primaryKey = PrimaryKey(clientKeyId, channelId, keyId)
}

/** 对外模型 ID 授权关联；删除模型映射不会隐式放开授权。 */
object ClientKeyAllowedModels : Table("client_key_allowed_models") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val modelId = varchar("model_id", 200)
    override val primaryKey = PrimaryKey(clientKeyId, modelId)
}
