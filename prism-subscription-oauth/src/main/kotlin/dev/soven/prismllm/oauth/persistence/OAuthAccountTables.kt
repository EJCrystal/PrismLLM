package dev.soven.prismllm.oauth.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * 独立的订阅账号。凭据、刷新状态、调度策略与额度全部属于账号自身，
 * 渠道通过关联表与账号建立多对多关联。
 *
 * @author 文杰
 * @since 2026-10-21
 */
object OAuthAccounts : Table("oauth_accounts") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val provider = varchar("provider", 32)
    /** V54 之前创建的账号待改挂到系统渠道；搬迁成功的账号置为 false。 */
    val providerChannelPending = bool("provider_channel_pending").default(false)
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
    /** 该账号自己的上游模型目录（JSON）；null 表示尚未同步，沿用渠道目录。 */
    val modelCatalog = text("model_catalog").nullable()
    val modelCatalogSyncedAt = long("model_catalog_synced_at").nullable()
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
    /** Antigravity 按模型家族分组的额度快照（JSON）；ChatGPT 为空。 */
    val quotaGroups = text("quota_groups").nullable()
    val quotaCheckedAt = long("quota_checked_at").nullable()
    /** 最近一次额度读取尝试（成功或失败），只用于后台扫描排序，不代表读数新鲜度。 */
    val quotaAttemptedAt = long("quota_attempted_at").nullable()
    val quotaError = varchar("quota_error", 200).nullable()
    /** Antigravity 的 Code Assist cloud project 与 tier；由 loadCodeAssist 发现并缓存。 */
    val upstreamProject = varchar("upstream_project", 128).nullable()
    val upstreamTier = varchar("upstream_tier", 64).nullable()
    val upstreamTierCheckedAt = long("upstream_tier_checked_at").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    /** refresh token 的获取时间；重新授权时更新，日常 token 刷新不改变。 */
    val authorizedAt = long("authorized_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 订阅账号与渠道的多对多关联；同一账号可以被多个渠道复用。
 *
 * `channel_id` 的外键由 Flyway 迁移维护：本模块不能依赖渠道上下文，
 * 因此这里只声明列，不在 Exposed 里引用渠道表。
 */
object OAuthAccountChannels : Table("oauth_account_channels") {
    val accountId = varchar("account_id", 36).references(OAuthAccounts.id, onDelete = ReferenceOption.CASCADE)
    val channelId = varchar("channel_id", 36)
    /** 挂在系统 provider 渠道上；每个账号最多一条，由部分唯一索引保证。 */
    val managed = bool("managed").default(false)
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
 *
 * @author 文杰
 * @since 2026-10-25
 */
object OAuthAccountWindows : Table("oauth_account_windows") {
    val accountId = varchar("account_id", 36).references(OAuthAccounts.id, onDelete = ReferenceOption.CASCADE)
    val hits = text("hits").default("[]")
    val cooldownUntil = long("cooldown_until").nullable()
    val consecutiveRateLimits = integer("consecutive_rate_limits").default(0)
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(accountId)
}