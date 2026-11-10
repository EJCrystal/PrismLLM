package dev.soven.prismllm.admin.persistence

import org.jetbrains.exposed.v1.core.Table

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
