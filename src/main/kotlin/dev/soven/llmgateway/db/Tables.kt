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

/**
 * 独立的订阅账号。凭据、刷新状态、调度策略与额度全部属于账号自身，
 * 渠道通过 [OAuthAccountChannels] 与账号建立多对多关联。
 */
object OAuthAccounts : Table("oauth_accounts") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val provider = varchar("provider", 32)
    val accountEmail = varchar("account_email", 320).nullable()
    val accountPlan = varchar("account_plan", 32).nullable()
    val refreshTokenCiphertext = text("refresh_token_ciphertext")
    val refreshTokenNonce = text("refresh_token_nonce")
    val refreshTokenKeyVersion = integer("refresh_token_key_version")
    val accessTokenCiphertext = text("access_token_ciphertext").nullable()
    val accessTokenNonce = text("access_token_nonce").nullable()
    val accessTokenKeyVersion = integer("access_token_key_version").nullable()
    val accessTokenExpiresAt = long("access_token_expires_at").nullable()
    val tokenType = varchar("token_type", 32).default("Bearer")
    val scope = text("scope").nullable()
    /** ACTIVE / REAUTH_REQUIRED。 */
    val authState = varchar("auth_state", 20).default("ACTIVE")
    /** 人工启停；停用后仍保留凭据与历史，重新启用即可恢复调度。 */
    val enabled = bool("enabled").default(true)
    val priority = integer("priority").default(1)
    val refreshFailureCount = integer("refresh_failure_count").default(0)
    val lastRefreshError = varchar("last_refresh_error", 200).nullable()
    val lastRefreshedAt = long("last_refreshed_at").nullable()
    /** 账号级出口代理；刷新 access token 与调用上游必须使用同一个代理。 */
    val proxyUrl = varchar("proxy_url", 500).nullable()
    /** 账号级滑动窗口上限；null 表示不限。窗口按账号统计，不区分模型。 */
    val windowLimit = integer("window_limit").nullable()
    val windowSeconds = integer("window_seconds").default(10_800)
    val maxConcurrentRequests = integer("max_concurrent_requests").default(1)
    /** ChatGPT 账号 id，来自 access token JWT，用于读取 /wham/usage。 */
    val chatgptAccountId = varchar("chatgpt_account_id", 128).nullable()
    /** 上游额度快照：5 小时窗口（primary）与每周窗口（secondary）。 */
    val quotaPrimaryUsedPercent = double("quota_primary_used_percent").nullable()
    val quotaPrimaryWindowSeconds = integer("quota_primary_window_seconds").nullable()
    val quotaPrimaryResetsAt = long("quota_primary_resets_at").nullable()
    val quotaSecondaryUsedPercent = double("quota_secondary_used_percent").nullable()
    val quotaSecondaryWindowSeconds = integer("quota_secondary_window_seconds").nullable()
    val quotaSecondaryResetsAt = long("quota_secondary_resets_at").nullable()
    val quotaAllowed = bool("quota_allowed").nullable()
    val quotaCreditsBalance = double("quota_credits_balance").nullable()
    val quotaCreditsUnlimited = bool("quota_credits_unlimited").default(false)
    val quotaResetCredits = integer("quota_reset_credits").nullable()
    val quotaCheckedAt = long("quota_checked_at").nullable()
    val quotaError = varchar("quota_error", 200).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** 订阅账号与渠道的多对多关联；同一账号可以被多个渠道复用。 */
object OAuthAccountChannels : Table("oauth_account_channels") {
    val accountId = varchar("account_id", 36).references(OAuthAccounts.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36).references(Channels.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(accountId, channelId)
}

/** 账号显式授权的上游模型；没有记录表示继承所关联渠道的全部模型。 */
object OAuthAccountModels : Table("oauth_account_models") {
    val accountId = varchar("account_id", 36).references(OAuthAccounts.id, onDelete = ReferenceOption.CASCADE)
    val upstreamModel = varchar("upstream_model", 200)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(accountId, upstreamModel)
}

/**
 * 订阅账号级调度状态快照。内存态是权威来源，这里只用于重启恢复。
 * 窗口命中按时间升序保存为 JSON 数组，窗口上限决定行内最多保留多少条。
 */
object OAuthAccountWindows : Table("oauth_account_windows") {
    val accountId = varchar("account_id", 36).references(OAuthAccounts.id, onDelete = ReferenceOption.CASCADE)
    val hits = text("hits").default("[]")
    val cooldownUntil = long("cooldown_until").nullable()
    val consecutiveRateLimits = integer("consecutive_rate_limits").default(0)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(accountId)
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
 * 候选冷却状态快照。内存为准，持久化只为重启恢复。
 *
 * @author 文杰
 * @since 2026-10-25
 */
object CooldownStates : Table("cooldown_state") {
    val stateKey = varchar("state_key", 300)
    val consecutiveFailures = integer("consecutive_failures")
    val cooldownUntil = long("cooldown_until")
    val reason = varchar("reason", 40).nullable()
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

/** Singleton global upstream header and JSON merge-patch settings. */
object UpstreamRequestSettings : Table("upstream_request_settings") {
    val id = integer("id")
    val overrides = text("overrides")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Latest scheduled refresh result for the dynamic upstream header catalog. */
object UpstreamHeaderCatalogState : Table("upstream_header_catalog_state") {
    val id = integer("id")
    val checkedAt = long("checked_at")
    val successAt = long("success_at").nullable()
    val lastError = text("last_error").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Persisted versions available for pinning and rollback. */
object UpstreamHeaderProfileVersions : Table("upstream_header_profile_versions") {
    val profileId = varchar("profile_id", 40)
    val version = varchar("version", 100)
    val name = varchar("name", 100)
    val headers = text("headers")
    val sourceLabel = varchar("source", 500)
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(profileId, version)
}

/** A profile pin selects one persisted version instead of the latest snapshot. */
object UpstreamHeaderProfilePins : Table("upstream_header_profile_pins") {
    val profileId = varchar("profile_id", 40)
    val version = varchar("version", 100)
    val pinnedAt = long("pinned_at")
    override val primaryKey = PrimaryKey(profileId)
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
    val clientIp = varchar("client_ip", 64).nullable()
    val clientUserAgent = varchar("client_user_agent", 300).nullable()
    val alias = varchar("alias", 100)
    val channelId = varchar("channel_id", 36).nullable()
    val channelName = varchar("channel_name", 100).nullable()
    val channelKeyId = varchar("channel_key_id", 36).nullable()
    val channelKeyName = varchar("channel_key_name", 100).nullable()
    val upstreamModel = varchar("upstream_model", 200).nullable()
    val protocol = varchar("protocol", 40)
    val upstreamProtocol = varchar("upstream_protocol", 40).nullable()
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
    val upstreamErrorBody = text("upstream_error_body").nullable()
    val upstreamAttempts = integer("upstream_attempts").nullable()
    val attemptsJson = text("attempts_json")
    val healthIndexed = bool("health_indexed").default(false)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, createdAt)
    }
}

/** Normalized observations; identifiers survive channel/key deletion, requests own retention. */
object RequestAttempts : Table("request_attempts") {
    val logId = long("log_id").references(RequestLogs.id, onDelete = ReferenceOption.CASCADE)
    val attemptIndex = integer("attempt_index")
    val channelId = varchar("channel_id", 36)
    val channelKeyId = varchar("channel_key_id", 36).nullable()
    val upstreamModel = varchar("upstream_model", 200).nullable()
    val upstreamProtocol = varchar("upstream_protocol", 40).nullable()
    val stream = bool("stream").nullable()
    val outcome = varchar("outcome", 12)
    val errorCode = text("error_code").nullable()
    val statusCode = integer("status_code").nullable()
    val firstTokenMs = long("first_token_ms").nullable()
    val elapsedMs = long("elapsed_ms")
    val outputTokens = long("output_tokens").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(logId, attemptIndex)
}
