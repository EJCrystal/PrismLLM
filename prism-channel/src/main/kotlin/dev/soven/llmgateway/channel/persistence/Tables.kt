package dev.soven.llmgateway.channel.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * 渠道表。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object Channels : Table("channels") {
    val id = varchar("id", 36)
    val name = varchar("name", 100).uniqueIndex()
    val baseUrl = varchar("base_url", 500)
    val authType = varchar("auth_type", 20)
    val protocolAuthTypes = text("protocol_auth_types").default("{}")
    val protocols = varchar("protocols", 100)
    val requestOverrides = text("request_overrides").default("{}")
    val quotaSettings = text("quota_settings").default("{}")
    val quotaCredentialCiphertext = text("quota_credential_ciphertext").default("")
    val quotaCredentialNonce = text("quota_credential_nonce").default("")
    val quotaCredentialKeyVersion = integer("quota_credential_key_version").default(1)
    val enabled = bool("enabled")
    val priority = integer("priority").default(1)
    /** custom: 用户自建；provider: 系统按订阅 provider 置备，端点由代码常量固定。 */
    val kind = varchar("kind", 16).default("custom")
    val provider = varchar("provider", 32).nullable()
    /** 渠道覆盖的密钥自动禁用策略 JSON；为空表示继承全局设置。 */
    val keyDisablePolicy = text("key_disable_policy").nullable()
    /** 渠道策略版本；每次写入递增，用于让该渠道下的旧计数失效。 */
    val keyDisablePolicyRevision = long("key_disable_policy_revision").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 渠道下的上游密钥。同一渠道的密钥共用基础地址、鉴权方式和协议。
 */
object ChannelKeys : Table("channel_keys") {
    val id = varchar("id", 36)
    val channelId = varchar("channel_id", 36).references(Channels.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val credentialCiphertext = text("credential_ciphertext")
    val credentialNonce = text("credential_nonce")
    val credentialKeyVersion = integer("credential_key_version")
    /** 四态：ACTIVE / MANUAL_DISABLED / AUTO_TEMPORARY / AUTO_PERMANENT。 */
    val state = varchar("state", 20)
    val stateVersion = long("state_version").default(0)
    val autoDisabledAt = long("auto_disabled_at").nullable()
    val autoDisableReason = varchar("auto_disable_reason", 40).nullable()
    val autoDisableUntil = long("auto_disable_until").nullable()
    /** 临时停用到期后的探测租约；同一密钥同时只允许一个探测请求。 */
    val probeUntil = long("probe_until").nullable()
    val priority = integer("priority").default(1)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(channelId, name)
        index(false, channelId, priority)
    }
}

/** 每个密钥独立的账户额度配置；无记录表示未配置，不继承其他账户凭据。 */
object ChannelKeyQuotaSettings : Table("channel_key_quota_settings") {
    val keyId = varchar("key_id", 36).references(ChannelKeys.id, onDelete = ReferenceOption.CASCADE)
    val settings = text("settings").default("{}")
    val credentialCiphertext = text("credential_ciphertext").default("")
    val credentialNonce = text("credential_nonce").default("")
    val credentialKeyVersion = integer("credential_key_version").default(1)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(keyId)
}

/** 渠道密钥显式授权的上游模型。 */
object ChannelKeyModels : Table("channel_key_models") {
    val keyId = varchar("key_id", 36).references(ChannelKeys.id, onDelete = ReferenceOption.CASCADE)
    val upstreamModel = varchar("upstream_model", 200)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(keyId, upstreamModel)
}

/** 渠道密钥对应上游分组的主动限流规则。未配置行表示不限制。 */
object ChannelKeyRateLimits : Table("channel_key_rate_limits") {
    val keyId = varchar("key_id", 36).references(ChannelKeys.id, onDelete = ReferenceOption.CASCADE)
    val enabled = bool("enabled")
    val requestsPerMinute = integer("requests_per_minute").nullable()
    val maxConcurrentRequests = integer("max_concurrent_requests").nullable()
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(keyId)
}

/** 自动禁用统计按密钥聚合，规则修改后通过 revision 开始新一轮计数。 */
object ChannelKeyHealth : Table("channel_key_health") {
    val keyId = varchar("key_id", 36).references(ChannelKeys.id, onDelete = ReferenceOption.CASCADE)
    val policyRevision = long("policy_revision")

    /** 计数所属策略作用域：`global` 或 `channel:<channelId>`；作用域变化即丢弃旧计数。 */
    val policyScope = varchar("policy_scope", 80).default("global")
    val authFailureStreak = integer("auth_failure_streak")
    val recentFailures = text("recent_failures")

    /** 按规则 id 分桶的失败时间戳；为空表示尚未累计。 */
    val ruleCounters = text("rule_counters").nullable()

    override val primaryKey = PrimaryKey(keyId)
}

object KeyAutoDisableSettings : Table("key_auto_disable_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val authFailureThreshold = integer("auth_failure_threshold")
    val failureThreshold = integer("failure_threshold")
    val failureWindowMinutes = integer("failure_window_minutes")
    val revision = long("revision")
    val updatedAt = long("updated_at")

    /** 规则列表 JSON；为空表示使用代码内置的默认规则。 */
    val rules = text("rules").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * 渠道显式授权的上游模型。只有这里的模型参与目录、直连路由和别名路由。
 */
object ChannelModels : Table("channel_models") {
    val channelId = varchar("channel_id", 36).references(Channels.id, onDelete = ReferenceOption.CASCADE)
    val upstreamModel = varchar("upstream_model", 200)
    val canonicalModelId = varchar("canonical_model_id", 200).nullable()
    val mappingSource = varchar("mapping_source", 32).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(channelId, upstreamModel)
}
