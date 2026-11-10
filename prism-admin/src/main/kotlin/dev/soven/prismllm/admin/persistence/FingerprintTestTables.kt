package dev.soven.prismllm.admin.persistence

import org.jetbrains.exposed.v1.core.Table

/** 模型指纹检测历史；只保留最近若干次，供管理台对比渠道。 */
object FingerprintTestRuns : Table("fingerprint_test_runs") {
    val id = long("id").autoIncrement()
    val channelId = varchar("channel_id", 36)
    val channelName = varchar("channel_name", 100)
    val channelKeyId = varchar("channel_key_id", 36).nullable()
    val channelKeyName = varchar("channel_key_name", 100).nullable()
    val model = varchar("model", 200)
    val protocol = varchar("protocol", 32)
    val success = bool("success")
    val statusCode = integer("status_code").nullable()
    val latencyMs = long("latency_ms")
    val decision = varchar("decision", 32)
    val prediction = varchar("prediction", 120).nullable()
    val predictionName = varchar("prediction_name", 120).nullable()
    val probability = double("probability").nullable()
    val usedOutputs = integer("used_outputs")
    val totalOutputs = integer("total_outputs")
    val analysis = text("analysis")
    val samples = text("samples")
    val errorMessage = text("error_message").nullable()
    val inputTokens = long("input_tokens").nullable()
    val outputTokens = long("output_tokens").nullable()
    val cachedTokens = long("cached_tokens").nullable()
    val reasoningTokens = long("reasoning_tokens").nullable()
    val totalTokens = long("total_tokens").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, createdAt)
    }
}