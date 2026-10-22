package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.RequestLogs
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * 请求日志持久化。只写入元数据与用量，不写 prompt 和响应正文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class RequestLogRepository(private val database: Database) {
    suspend fun insert(record: RequestLogRecord) = suspendTransaction(database) {
        RequestLogs.insert {
            it[requestId] = record.requestId
            it[clientKeyId] = record.clientKeyId
            it[clientKeyName] = record.clientKeyName
            it[alias] = record.alias
            it[channelId] = record.channelId
            it[channelName] = record.channelName
            it[upstreamModel] = record.upstreamModel
            it[protocol] = record.protocol.value
            it[statusCode] = record.statusCode
            it[firstTokenMs] = record.firstTokenMs
            it[totalMs] = record.totalMs
            it[inputTokens] = record.inputTokens
            it[outputTokens] = record.outputTokens
            it[cachedTokens] = record.cachedTokens
            it[reasoningTokens] = record.reasoningTokens
            it[reasoningEffort] = record.reasoningEffort
            it[totalTokens] = record.totalTokens
            it[usageKnown] = record.usageKnown
            it[errorCode] = record.errorCode
            it[failureStage] = record.failureStage?.value
            it[errorMessage] = record.errorMessage
            it[upstreamErrorCode] = record.upstreamErrorCode
            it[createdAt] = record.createdAt
        }
    }

    suspend fun list(limit: Int, beforeId: Long? = null): List<RequestLogRecord> = suspendTransaction(database) {
        val query = RequestLogs.selectAll()
        val filtered = if (beforeId != null) query.where { RequestLogs.id less beforeId } else query
        filtered.orderBy(RequestLogs.id, org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(limit.coerceIn(1, 500))
            .map(::toRecord)
    }

    private fun toRecord(row: ResultRow) = RequestLogRecord(
        requestId = row[RequestLogs.requestId],
        clientKeyId = row[RequestLogs.clientKeyId],
        clientKeyName = row[RequestLogs.clientKeyName],
        alias = row[RequestLogs.alias],
        channelId = row[RequestLogs.channelId],
        channelName = row[RequestLogs.channelName],
        upstreamModel = row[RequestLogs.upstreamModel],
        protocol = GatewayProtocol.fromValue(row[RequestLogs.protocol]) ?: GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = row[RequestLogs.statusCode],
        firstTokenMs = row[RequestLogs.firstTokenMs],
        totalMs = row[RequestLogs.totalMs],
        inputTokens = row[RequestLogs.inputTokens],
        outputTokens = row[RequestLogs.outputTokens],
        cachedTokens = row[RequestLogs.cachedTokens],
        reasoningTokens = row[RequestLogs.reasoningTokens],
        reasoningEffort = row[RequestLogs.reasoningEffort],
        totalTokens = row[RequestLogs.totalTokens],
        usageKnown = row[RequestLogs.usageKnown],
        errorCode = row[RequestLogs.errorCode],
        failureStage = FailureStage.fromValue(row[RequestLogs.failureStage]),
        errorMessage = row[RequestLogs.errorMessage],
        upstreamErrorCode = row[RequestLogs.upstreamErrorCode],
        createdAt = row[RequestLogs.createdAt],
    )
}
