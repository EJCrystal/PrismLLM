package dev.soven.prismllm.requestlog.domain

import kotlinx.serialization.Serializable

data class RequestLogQuery(
    val from: Long? = null,
    val to: Long? = null,
    val keyword: String? = null,
    val channelId: String? = null,
    val model: String? = null,
    val httpStatus: String? = null,
    val outcome: String? = null,
)

@Serializable
data class RequestLogFilterOption(val value: String, val label: String)

@Serializable
data class RequestLogFilterOptions(
    val channels: List<RequestLogFilterOption>,
    val models: List<RequestLogFilterOption>,
    val statuses: List<RequestLogFilterOption>,
)

fun RequestLogRecord.requestOutcome(): String = when {
    statusCode == 499 || errorCode in setOf("client_disconnect", "client_cancelled") -> "cancelled"
    statusCode >= 400 || !errorCode.isNullOrEmpty() || !upstreamErrorCode.isNullOrEmpty() -> "failed"
    statusCode !in 200..299 -> "other"
    (upstreamAttempts ?: 0) > 1 || attempts.count { !it.skipped } > 1 || attempts.any {
        !it.skipped && (!it.errorCode.isNullOrEmpty() || (it.statusCode ?: 0) >= 400 || (it.upstreamErrorStatusCode ?: 0) >= 400)
    } -> "recovered"
    else -> "success"
}
