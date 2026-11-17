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

/** 订阅账号授权关联；复合外键由迁移保证账号与渠道的关联关系。 */
object ClientKeyAllowedOAuthAccounts : Table("client_key_allowed_oauth_accounts") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val accountId = varchar("account_id", 36)
    val channelId = varchar("channel_id", 36)
    override val primaryKey = PrimaryKey(clientKeyId, accountId, channelId)
}

/** 对外模型 ID 授权关联；删除模型映射不会隐式放开授权。 */
object ClientKeyAllowedModels : Table("client_key_allowed_models") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val modelId = varchar("model_id", 200)
    override val primaryKey = PrimaryKey(clientKeyId, modelId)
}

/** 渠道黑名单关联；删除失效对象不改变调用方已选模式。 */
object ClientKeyDeniedChannels : Table("client_key_denied_channels") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36)
    override val primaryKey = PrimaryKey(clientKeyId, channelId)
}

/** 静态密钥黑名单关联；复合外键保证渠道归属，不包含 OAuth 身份。 */
object ClientKeyDeniedChannelKeys : Table("client_key_denied_channel_keys") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36)
    val keyId = varchar("key_id", 36)
    override val primaryKey = PrimaryKey(clientKeyId, channelId, keyId)
}

/** 订阅账号黑名单关联；复合外键保证账号与渠道关联，不影响静态密钥身份。 */
object ClientKeyDeniedOAuthAccounts : Table("client_key_denied_oauth_accounts") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val accountId = varchar("account_id", 36)
    val channelId = varchar("channel_id", 36)
    override val primaryKey = PrimaryKey(clientKeyId, accountId, channelId)
}

/** 对外模型黑名单关联；模型映射删除后保留 ID，防止同 ID 重新配置绕过禁止。 */
object ClientKeyDeniedModels : Table("client_key_denied_models") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val modelId = varchar("model_id", 200)
    override val primaryKey = PrimaryKey(clientKeyId, modelId)
}
