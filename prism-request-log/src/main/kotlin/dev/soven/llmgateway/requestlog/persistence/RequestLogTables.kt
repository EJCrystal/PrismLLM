package dev.soven.llmgateway.requestlog.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

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
