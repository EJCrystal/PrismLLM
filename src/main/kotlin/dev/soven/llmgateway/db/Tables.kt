package dev.soven.llmgateway.db

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
    val protocols = varchar("protocols", 100)
    val enabled = bool("enabled")
    val priority = integer("priority").default(1)
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
    val enabled = bool("enabled")
    val stateVersion = long("state_version").default(0)
    val autoDisabledAt = long("auto_disabled_at").nullable()
    val autoDisableReason = varchar("auto_disable_reason", 40).nullable()
    val priority = integer("priority").default(1)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(channelId, name)
        index(false, channelId, priority)
    }
}

/** 渠道密钥显式授权的上游模型。 */
object ChannelKeyModels : Table("channel_key_models") {
    val keyId = varchar("key_id", 36).references(ChannelKeys.id, onDelete = ReferenceOption.CASCADE)
    val upstreamModel = varchar("upstream_model", 200)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(keyId, upstreamModel)
}

/** 自动禁用统计按密钥聚合，规则修改后通过 revision 开始新一轮计数。 */
object ChannelKeyHealth : Table("channel_key_health") {
    val keyId = varchar("key_id", 36).references(ChannelKeys.id, onDelete = ReferenceOption.CASCADE)
    val policyRevision = long("policy_revision")
    val authFailureStreak = integer("auth_failure_streak")
    val recentFailures = text("recent_failures")

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

    override val primaryKey = PrimaryKey(id)
}

/**
 * 模型别名表。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object ModelAliases : Table("model_aliases") {
    val id = varchar("id", 36)
    val alias = varchar("alias", 100).uniqueIndex()
    val enabled = bool("enabled")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 模型别名候选表。position 是优先级档位，同档候选分摊流量，后续档位在失败或冷却时启用。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object AliasCandidates : Table("alias_candidates") {
    val id = varchar("id", 36)
    val aliasId = varchar("alias_id", 36).references(ModelAliases.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36).references(Channels.id, onDelete = ReferenceOption.RESTRICT)
    val upstreamModel = varchar("upstream_model", 200)
    val position = integer("position")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, aliasId, position)
    }
}

/**
 * 候选冷却状态快照。内存为准，持久化只为重启恢复。
 *
 * @author 文杰
 * @since 2026-10-25
 */
object CooldownStates : Table("cooldown_state") {
    val stateKey = varchar("state_key", 300)
    val consecutiveFailures = integer("consecutive_failures")
    val cooldownUntil = long("cooldown_until")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(stateKey)
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

/**
 * 调用方密钥表。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object ClientKeys : Table("client_keys") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val keyHash = varchar("key_hash", 64).uniqueIndex()
    val keyPrefix = varchar("key_prefix", 20)
    val enabled = bool("enabled")
    val expiresAt = long("expires_at").nullable()
    val dailyTokenLimit = long("daily_token_limit").nullable()
    val dailyUsdLimit = decimal("daily_usd_limit", 18, 8).nullable()
    val dailyCnyLimit = decimal("daily_cny_limit", 18, 8).nullable()
    val dailyEurLimit = decimal("daily_eur_limit", 18, 8).nullable()
    val maxConcurrentRequests = integer("max_concurrent_requests").nullable()
    val requestsPerMinute = integer("requests_per_minute").nullable()
    val createdAt = long("created_at")
    val rotatedAt = long("rotated_at").nullable()
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * 调用方密钥的每日额度账本。period 为 UTC 自然日序号。
 */
object ClientKeyQuotaUsage : Table("client_key_quota_usage") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val period = long("period")
    val tokens = long("tokens").default(0)
    val usdCost = decimal("usd_cost", 18, 8).default(java.math.BigDecimal.ZERO)
    val cnyCost = decimal("cny_cost", 18, 8).default(java.math.BigDecimal.ZERO)
    val eurCost = decimal("eur_cost", 18, 8).default(java.math.BigDecimal.ZERO)

    override val primaryKey = PrimaryKey(clientKeyId, period)
}

/**
 * 渠道内上游模型的计费单价。单价按每百万 token 计算。
 *
 * @author 文杰
 * @since 2026-10-22
 */
object ModelPrices : Table("model_prices") {
    val id = varchar("id", 36)
    val channelId = varchar("channel_id", 36).references(Channels.id, onDelete = ReferenceOption.CASCADE)
    val upstreamModel = varchar("upstream_model", 200)
    val currency = varchar("currency", 8)
    val inputPricePerMillion = decimal("input_price_per_million", 18, 8)
    val outputPricePerMillion = decimal("output_price_per_million", 18, 8)
    val cachedInputPricePerMillion = decimal("cached_input_price_per_million", 18, 8).nullable()
    val cachedInputWritePricePerMillion = decimal("cached_input_write_price_per_million", 18, 8).nullable()
    val pricingMode = varchar("pricing_mode", 20).default("token")
    val perRequestPrice = decimal("per_request_price", 18, 8).nullable()
    val priceTiersJson = text("price_tiers_json").default("[]")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(channelId, upstreamModel)
    }
}

/**
 * 按对外模型 ID 配置的默认计费单价。渠道内同上游模型的价格优先于默认价。
 */
object ModelDefaultPrices : Table("model_default_prices") {
    val id = varchar("id", 36)
    val modelId = varchar("model_id", 200).uniqueIndex()
    val currency = varchar("currency", 8)
    val inputPricePerMillion = decimal("input_price_per_million", 18, 8)
    val outputPricePerMillion = decimal("output_price_per_million", 18, 8)
    val cachedInputPricePerMillion = decimal("cached_input_price_per_million", 18, 8).nullable()
    val cachedInputWritePricePerMillion = decimal("cached_input_write_price_per_million", 18, 8).nullable()
    val pricingMode = varchar("pricing_mode", 20).default("token")
    val perRequestPrice = decimal("per_request_price", 18, 8).nullable()
    val priceTiersJson = text("price_tiers_json").default("[]")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 管理员账号表。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object AdminUsers : Table("admin_users") {
    val id = varchar("id", 36)
    val username = varchar("username", 100).uniqueIndex()
    val passwordHash = text("password_hash")
    val avatarDataUrl = text("avatar_data_url").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 管理面写操作审计表。只保存请求元数据，不保存请求正文或任何凭据。
 */
object AdminAuditLogs : Table("admin_audit_logs") {
    val id = long("id").autoIncrement()
    val actor = varchar("actor", 100).nullable()
    val action = varchar("action", 80)
    val method = varchar("method", 10)
    val path = varchar("path", 500)
    val statusCode = integer("status_code")
    val result = varchar("result", 16)
    val requestId = varchar("request_id", 64)
    val sourceIp = varchar("source_ip", 100).nullable()
    val errorCode = varchar("error_code", 100).nullable()
    val details = text("details").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, createdAt)
        index(false, actor, createdAt)
    }
}

/**
 * 请求日志保留策略的运行期设置。数据库中的值覆盖启动环境变量。
 */
object RetentionSettings : Table("retention_settings") {
    val id = integer("id")
    val cleanupEnabled = bool("cleanup_enabled")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** 渠道模型连通性测试的全局默认值。 */
object ModelTestSettings : Table("model_test_settings") {
    val id = integer("id")
    val defaultPrompt = text("default_prompt")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 请求日志表。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object RequestLogs : Table("request_logs") {
    val id = long("id").autoIncrement()
    val requestId = varchar("request_id", 64)
    val traceId = varchar("trace_id", 32).nullable()
    val clientKeyId = varchar("client_key_id", 36).nullable()
    val clientKeyName = varchar("client_key_name", 100).nullable()
    val alias = varchar("alias", 100)
    val channelId = varchar("channel_id", 36).nullable()
    val channelName = varchar("channel_name", 100).nullable()
    val channelKeyId = varchar("channel_key_id", 36).nullable()
    val channelKeyName = varchar("channel_key_name", 100).nullable()
    val upstreamModel = varchar("upstream_model", 200).nullable()
    val protocol = varchar("protocol", 40)
    val stream = bool("stream").nullable()
    val statusCode = integer("status_code")
    val firstTokenMs = long("first_token_ms").nullable()
    val totalMs = long("total_ms")
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val cachedTokens = long("cached_tokens").nullable()
    val cachedWriteTokens = long("cached_write_tokens").nullable()
    val reasoningTokens = long("reasoning_tokens").nullable()
    val reasoningEffort = varchar("reasoning_effort", 40).nullable()
    val totalTokens = long("total_tokens").nullable()
    val usageKnown = bool("usage_known")
    val costAmount = decimal("cost_amount", 18, 8).nullable()
    val costCurrency = varchar("cost_currency", 8).nullable()
    val inputPricePerMillion = decimal("input_price_per_million", 18, 8).nullable()
    val outputPricePerMillion = decimal("output_price_per_million", 18, 8).nullable()
    val cachedInputPricePerMillion = decimal("cached_input_price_per_million", 18, 8).nullable()
    val cachedInputWritePricePerMillion = decimal("cached_input_write_price_per_million", 18, 8).nullable()
    val pricingMode = varchar("pricing_mode", 20).default("token")
    val perRequestPrice = decimal("per_request_price", 18, 8).nullable()
    val priceTiersJson = text("price_tiers_json").default("[]")
    val errorCode = varchar("error_code", 100).nullable()
    val failureStage = varchar("failure_stage", 20).nullable()
    val errorMessage = text("error_message").nullable()
    val upstreamErrorCode = varchar("upstream_error_code", 200).nullable()
    val upstreamAttempts = integer("upstream_attempts").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, createdAt)
    }
}
