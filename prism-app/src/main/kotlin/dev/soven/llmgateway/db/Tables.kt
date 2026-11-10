package dev.soven.llmgateway.db

import org.jetbrains.exposed.v1.core.Table

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

/** 管理台可编辑的 Web 搜索设置；没有行时沿用环境变量默认值。 */
object WebSearchSettings : Table("web_search_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val provider = varchar("provider", 20)
    val apiKeyCiphertext = text("api_key_ciphertext").default("")
    val apiKeyNonce = text("api_key_nonce").default("")
    val apiKeyKeyVersion = integer("api_key_key_version").default(1)
    val baseUrl = varchar("base_url", 500).nullable()
    val maxResults = integer("max_results")
    val timeoutMs = long("timeout_ms")
    val maxRounds = integer("max_rounds")
    val maxPageChars = integer("max_page_chars")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * 管理台可编辑的 IQ 视觉判分配置；没有行时沿用环境变量默认值。
 *
 * 只包含可热切换的字段：渲染宽度与浏览器路径属于进程级绑定，仍由部署环境变量决定。
 */
object IqVisionSettings : Table("iq_vision_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val baseUrl = varchar("base_url", 500).default("")
    val model = varchar("model", 200).default("")
    val apiKeyCiphertext = text("api_key_ciphertext").default("")
    val apiKeyNonce = text("api_key_nonce").default("")
    val apiKeyKeyVersion = integer("api_key_key_version").default(1)
    val visionTimeoutMs = long("vision_timeout_ms")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** 智商检测历史；只保留最近若干次，供管理台对比模型表现。 */
object IqTestRuns : Table("iq_test_runs") {
    val id = long("id").autoIncrement()
    val benchmark = varchar("benchmark", 20)
    val channelId = varchar("channel_id", 36)
    val channelName = varchar("channel_name", 100)
    val channelKeyId = varchar("channel_key_id", 36).nullable()
    val channelKeyName = varchar("channel_key_name", 100).nullable()
    val model = varchar("model", 200)
    val upstreamModel = varchar("upstream_model", 200)
    val protocol = varchar("protocol", 32)
    val success = bool("success")
    val statusCode = integer("status_code").nullable()
    val latencyMs = long("latency_ms")
    val verdict = varchar("verdict", 20)
    val prompt = text("prompt")
    val responseText = text("response_text").nullable()
    val expectedAnswer = integer("expected_answer").nullable()
    val matchedAnswer = integer("matched_answer").nullable()
    val errorMessage = text("error_message").nullable()
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val cachedTokens = long("cached_tokens").nullable()
    val reasoningTokens = long("reasoning_tokens").nullable()
    val totalTokens = long("total_tokens").nullable()
    val upstreamReportedModel = varchar("upstream_reported_model", 200).nullable()
    val judgeId = varchar("judge_id", 64).nullable()
    val judgeLabel = varchar("judge_label", 120).nullable()
    val judgeVerdict = varchar("judge_verdict", 20).nullable()
    val judgeResultLabel = varchar("judge_result_label", 120).nullable()
    val judgeReason = text("judge_reason").nullable()
    val judgeScore = double("judge_score").nullable()
    val judgeDetails = text("judge_details").nullable()
    val judgeCheckedAt = long("judge_checked_at").nullable()
    val manualVerdict = varchar("manual_verdict", 20).nullable()
    val manualScore = integer("manual_score").nullable()
    val manualNote = text("manual_note").nullable()
    val manualUpdatedAt = long("manual_updated_at").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, createdAt)
    }
}
