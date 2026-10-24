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
    val credentialCiphertext = text("credential_ciphertext")
    val credentialNonce = text("credential_nonce")
    val credentialKeyVersion = integer("credential_key_version")
    val authType = varchar("auth_type", 20)
    val protocols = varchar("protocols", 100)
    val enabled = bool("enabled")
    val exposeUpstreamModels = bool("expose_upstream_models").default(true)
    val createdAt = long("created_at")
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
 * 模型别名候选表。
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
        uniqueIndex(aliasId, position)
    }
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
    val team = varchar("team", 100).nullable()
    val keyHash = varchar("key_hash", 64).uniqueIndex()
    val keyPrefix = varchar("key_prefix", 20)
    val enabled = bool("enabled")
    val expiresAt = long("expires_at").nullable()
    val dailyTokenLimit = long("daily_token_limit").nullable()
    val dailyUsdLimit = decimal("daily_usd_limit", 18, 8).nullable()
    val dailyCnyLimit = decimal("daily_cny_limit", 18, 8).nullable()
    val dailyEurLimit = decimal("daily_eur_limit", 18, 8).nullable()
    val maxConcurrentRequests = integer("max_concurrent_requests").nullable()
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
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(channelId, upstreamModel)
    }
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
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, createdAt)
        index(false, actor, createdAt)
    }
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
    val team = varchar("team", 100).nullable()
    val alias = varchar("alias", 100)
    val channelId = varchar("channel_id", 36).nullable()
    val channelName = varchar("channel_name", 100).nullable()
    val upstreamModel = varchar("upstream_model", 200).nullable()
    val protocol = varchar("protocol", 40)
    val statusCode = integer("status_code")
    val firstTokenMs = long("first_token_ms").nullable()
    val totalMs = long("total_ms")
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val cachedTokens = long("cached_tokens").nullable()
    val reasoningTokens = long("reasoning_tokens").nullable()
    val reasoningEffort = varchar("reasoning_effort", 40).nullable()
    val totalTokens = long("total_tokens").nullable()
    val usageKnown = bool("usage_known")
    val costAmount = decimal("cost_amount", 18, 8).nullable()
    val costCurrency = varchar("cost_currency", 8).nullable()
    val inputPricePerMillion = decimal("input_price_per_million", 18, 8).nullable()
    val outputPricePerMillion = decimal("output_price_per_million", 18, 8).nullable()
    val cachedInputPricePerMillion = decimal("cached_input_price_per_million", 18, 8).nullable()
    val errorCode = varchar("error_code", 100).nullable()
    val failureStage = varchar("failure_stage", 20).nullable()
    val errorMessage = text("error_message").nullable()
    val upstreamErrorCode = varchar("upstream_error_code", 200).nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, createdAt)
    }
}
