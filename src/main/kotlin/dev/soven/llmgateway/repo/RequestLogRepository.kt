package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.RequestLogs
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
            it[alias] = record.alias
            it[channelId] = record.channelId
            it[upstreamModel] = record.upstreamModel
            it[statusCode] = record.statusCode
            it[firstTokenMs] = record.firstTokenMs
            it[totalMs] = record.totalMs
            it[inputTokens] = record.inputTokens
            it[outputTokens] = record.outputTokens
            it[cachedTokens] = record.cachedTokens
            it[totalTokens] = record.totalTokens
            it[usageKnown] = record.usageKnown
            it[errorCode] = record.errorCode
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
        alias = row[RequestLogs.alias],
        channelId = row[RequestLogs.channelId],
        upstreamModel = row[RequestLogs.upstreamModel],
        statusCode = row[RequestLogs.statusCode],
        firstTokenMs = row[RequestLogs.firstTokenMs],
        totalMs = row[RequestLogs.totalMs],
        inputTokens = row[RequestLogs.inputTokens],
        outputTokens = row[RequestLogs.outputTokens],
        cachedTokens = row[RequestLogs.cachedTokens],
        totalTokens = row[RequestLogs.totalTokens],
        usageKnown = row[RequestLogs.usageKnown],
        errorCode = row[RequestLogs.errorCode],
        createdAt = row[RequestLogs.createdAt],
    )
}
