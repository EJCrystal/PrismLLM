package dev.soven.prismllm.quota.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

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
object ClientKeyQuotaUsages : Table("client_key_quota_usage") {
    val clientKeyId = varchar("client_key_id", 36).references(ClientKeys.id, onDelete = ReferenceOption.CASCADE)
    val period = long("period")
    val tokens = long("tokens").default(0)
    val usdCost = decimal("usd_cost", 18, 8).default(java.math.BigDecimal.ZERO)

    override val primaryKey = PrimaryKey(clientKeyId, period)
}