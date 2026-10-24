package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.RequestLogs
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.report.CleanupResult
import dev.soven.llmgateway.report.UsageGroup
import dev.soven.llmgateway.report.UsageReport
import dev.soven.llmgateway.report.UsageReportQuery
import dev.soven.llmgateway.report.UsageTotal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.math.BigDecimal

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
            it[team] = record.team
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
            it[costAmount] = record.costAmount
            it[costCurrency] = record.costCurrency
            it[inputPricePerMillion] = record.inputPricePerMillion
            it[outputPricePerMillion] = record.outputPricePerMillion
            it[cachedInputPricePerMillion] = record.cachedInputPricePerMillion
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

    suspend fun report(query: UsageReportQuery): UsageReport = suspendTransaction(database) {
        val conditions = buildList {
            add((RequestLogs.createdAt greaterEq query.from) and (RequestLogs.createdAt lessEq query.to))
            query.clientKeyId?.let { add(RequestLogs.clientKeyId eq it) }
            query.team?.let { add(RequestLogs.team eq it) }
        }
        val where = conditions.reduce { left, right -> left and right }
        val totals = RequestLogs.select(RequestLogs.statusCode, RequestLogs.inputTokens, RequestLogs.outputTokens, RequestLogs.totalTokens, RequestLogs.costAmount, RequestLogs.costCurrency)
            .where { where }
            .toList()
        val total = aggregate(totals)
        val byKey = groupedUsage(RequestLogs.clientKeyId, RequestLogs.clientKeyName, where)
        val byTeam = groupedUsage(RequestLogs.team, RequestLogs.team, where)
        UsageReport(query.from, query.to, total, byKey, byTeam)
    }

    suspend fun deleteOlderThan(cutoff: Long, batchSize: Int): CleanupResult = suspendTransaction(database) {
        val ids = RequestLogs.select(RequestLogs.id)
            .where { RequestLogs.createdAt less cutoff }
            .orderBy(RequestLogs.id)
            .limit(batchSize.coerceIn(100, 100_000))
            .map { it[RequestLogs.id] }
        if (ids.isEmpty()) return@suspendTransaction CleanupResult(0, cutoff)
        val deleted = RequestLogs.deleteWhere { RequestLogs.id inList ids }
        CleanupResult(deleted.toLong(), cutoff)
    }

    private fun toRecord(row: ResultRow) = RequestLogRecord(
        requestId = row[RequestLogs.requestId],
        clientKeyId = row[RequestLogs.clientKeyId],
        clientKeyName = row[RequestLogs.clientKeyName],
        team = row[RequestLogs.team],
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
        costAmount = row[RequestLogs.costAmount],
        costCurrency = row[RequestLogs.costCurrency],
        inputPricePerMillion = row[RequestLogs.inputPricePerMillion],
        outputPricePerMillion = row[RequestLogs.outputPricePerMillion],
        cachedInputPricePerMillion = row[RequestLogs.cachedInputPricePerMillion],
        errorCode = row[RequestLogs.errorCode],
        failureStage = FailureStage.fromValue(row[RequestLogs.failureStage]),
        errorMessage = row[RequestLogs.errorMessage],
        upstreamErrorCode = row[RequestLogs.upstreamErrorCode],
        createdAt = row[RequestLogs.createdAt],
    )

    private fun aggregate(rows: List<ResultRow>): UsageTotal {
        var requests = 0L
        var errors = 0L
        var input = 0L
        var output = 0L
        var totalTokens = 0L
        val costs = mutableMapOf<String, BigDecimal>()
        rows.forEach { row ->
            requests++
            if (row[RequestLogs.statusCode] >= 400) errors++
            input += row[RequestLogs.inputTokens] ?: 0
            output += row[RequestLogs.outputTokens] ?: 0
            totalTokens += row[RequestLogs.totalTokens] ?: 0
            val amount = row[RequestLogs.costAmount]
            val currency = row[RequestLogs.costCurrency]
            if (amount != null && currency != null) costs[currency] = (costs[currency] ?: BigDecimal.ZERO) + amount
        }
        return UsageTotal(requests, errors, input, output, totalTokens, costs)
    }

    private fun groupedUsage(
        idColumn: org.jetbrains.exposed.v1.core.Column<String?>,
        nameColumn: org.jetbrains.exposed.v1.core.Column<String?>,
        where: org.jetbrains.exposed.v1.core.Op<Boolean>,
    ): List<UsageGroup> {
        val rows = RequestLogs.select(
            idColumn,
            nameColumn,
            RequestLogs.statusCode,
            RequestLogs.inputTokens,
            RequestLogs.outputTokens,
            RequestLogs.totalTokens,
            RequestLogs.costAmount,
            RequestLogs.costCurrency,
        )
            .where { where }
            .toList()
            .groupBy { row -> row[idColumn]?.takeIf { it.isNotBlank() } ?: "unassigned" }
        return rows.map { (id, grouped) ->
            val aggregate = aggregate(grouped)
            UsageGroup(
                id = id,
                name = grouped.firstNotNullOfOrNull { it[nameColumn]?.takeIf { value -> value.isNotBlank() } },
                requests = aggregate.requests,
                errors = aggregate.errors,
                inputTokens = aggregate.inputTokens,
                outputTokens = aggregate.outputTokens,
                totalTokens = aggregate.totalTokens,
                costs = aggregate.costs,
            )
        }.sortedByDescending { it.totalTokens }
    }

}
