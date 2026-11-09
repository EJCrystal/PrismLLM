package dev.soven.llmgateway.requestlog.infra

import dev.soven.llmgateway.requestlog.domain.UpstreamAttemptRecord
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 把每次上游尝试或路由跳过的明细序列化进 request_logs.attempts_json。 */
object UpstreamAttemptCodec {
    @Serializable
    private data class StoredAttempt(
        val index: Int,
        val channelId: String? = null,
        val channelName: String? = null,
        val channelKeyId: String? = null,
        val channelKeyName: String? = null,
        val upstreamModel: String? = null,
        val upstreamReportedModel: String? = null,
        val upstreamProtocol: String? = null,
        val statusCode: Int? = null,
        val upstreamErrorStatusCode: Int? = null,
        val errorCode: String? = null,
        val upstreamErrorCode: String? = null,
        val errorMessage: String? = null,
        val elapsedMs: Long = 0,
        val firstTokenMs: Long? = null,
        val committed: Boolean = false,
        val retryable: Boolean = false,
        val skipped: Boolean = false,
        val skipReason: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredAttempt.serializer())

    fun encode(attempts: List<UpstreamAttemptRecord>): String = json.encodeToString(
        serializer,
        attempts.map { attempt ->
            StoredAttempt(
                index = attempt.index,
                channelId = attempt.channelId,
                channelName = attempt.channelName,
                channelKeyId = attempt.channelKeyId,
                channelKeyName = attempt.channelKeyName,
                upstreamModel = attempt.upstreamModel,
                upstreamReportedModel = attempt.upstreamReportedModel,
                upstreamProtocol = attempt.upstreamProtocol?.value,
                statusCode = attempt.statusCode,
                upstreamErrorStatusCode = attempt.upstreamErrorStatusCode,
                errorCode = attempt.errorCode,
                upstreamErrorCode = attempt.upstreamErrorCode,
                errorMessage = attempt.errorMessage,
                elapsedMs = attempt.elapsedMs,
                firstTokenMs = attempt.firstTokenMs,
                committed = attempt.committed,
                retryable = attempt.retryable,
                skipped = attempt.skipped,
                skipReason = attempt.skipReason,
            )
        },
    )

    /** 历史行为空或损坏时退化为空列表，不影响日志列表读取。 */
    fun decode(value: String?): List<UpstreamAttemptRecord> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, value) }
            .getOrDefault(emptyList())
            .map { attempt ->
                UpstreamAttemptRecord(
                    index = attempt.index,
                    channelId = attempt.channelId,
                    channelName = attempt.channelName,
                    channelKeyId = attempt.channelKeyId,
                    channelKeyName = attempt.channelKeyName,
                    upstreamModel = attempt.upstreamModel,
                    upstreamReportedModel = attempt.upstreamReportedModel,
                    upstreamProtocol = attempt.upstreamProtocol?.let(GatewayProtocol::fromValue),
                    statusCode = attempt.statusCode,
                    upstreamErrorStatusCode = attempt.upstreamErrorStatusCode,
                    errorCode = attempt.errorCode,
                    upstreamErrorCode = attempt.upstreamErrorCode,
                    errorMessage = attempt.errorMessage,
                    elapsedMs = attempt.elapsedMs,
                    firstTokenMs = attempt.firstTokenMs,
                    committed = attempt.committed,
                    retryable = attempt.retryable,
                    skipped = attempt.skipped,
                    skipReason = attempt.skipReason,
                )
            }
    }
}
