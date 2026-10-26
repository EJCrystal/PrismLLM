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
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.Query
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
            it[traceId] = record.traceId
            it[clientKeyId] = record.clientKeyId
            it[clientKeyName] = record.clientKeyName
            it[alias] = record.alias
            it[channelId] = record.channelId
            it[channelName] = record.channelName
            it[upstreamModel] = record.upstreamModel
            it[protocol] = record.protocol.value
            it[stream] = record.stream
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
            it[upstreamAttempts] = record.upstreamAttempts
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
        val where = reportWhere(query)
        val totals = totalQuery(where).singleOrNull()?.toUsageTotal() ?: UsageTotal()
        UsageReport(
            from = query.from,
            to = query.to,
            total = totals.copy(costs = costsByCurrency(where)),
            byClientKey = groupedQuery(RequestLogs.clientKeyId, RequestLogs.clientKeyName, where),
        )
    }

    private fun reportWhere(query: UsageReportQuery): Op<Boolean> {
        val conditions = buildList {
            add((RequestLogs.createdAt greaterEq query.from) and (RequestLogs.createdAt lessEq query.to))
            query.clientKeyId?.let { add(RequestLogs.clientKeyId eq it) }
        }
        return conditions.reduce { left, right -> left and right }
    }

    private fun totalQuery(where: Op<Boolean>): Query {
        val failure = (RequestLogs.statusCode greaterEq 400) or (RequestLogs.errorCode.isNotNull())
        val errorCount = Case()
            .When(failure, org.jetbrains.exposed.v1.core.longLiteral(1L))
            .Else(org.jetbrains.exposed.v1.core.longLiteral(0L))
            .sum()
        return RequestLogs.select(
            RequestLogs.id.count(),
            errorCount,
            RequestLogs.inputTokens.sum(),
            RequestLogs.outputTokens.sum(),
            RequestLogs.totalTokens.sum(),
        ).where { where }
    }

    private fun costsByCurrency(where: Op<Boolean>): Map<String, BigDecimal> {
        val cost = RequestLogs.costAmount.sum()
        return RequestLogs.select(RequestLogs.costCurrency, cost)
            .where { where }
            .groupBy(RequestLogs.costCurrency)
            .mapNotNull { row ->
                val currency = row[RequestLogs.costCurrency] ?: return@mapNotNull null
                val amount = row[cost] ?: return@mapNotNull null
                currency to amount
            }.toMap()
    }

    private fun ResultRow.toUsageTotal(): UsageTotal {
        val failure = (RequestLogs.statusCode greaterEq 400) or (RequestLogs.errorCode.isNotNull())
        val errorCount = Case()
            .When(failure, org.jetbrains.exposed.v1.core.longLiteral(1L))
            .Else(org.jetbrains.exposed.v1.core.longLiteral(0L))
            .sum()
        return UsageTotal(
            requests = this[RequestLogs.id.count()],
            errors = this[errorCount] ?: 0L,
            inputTokens = this[RequestLogs.inputTokens.sum()] ?: 0L,
            outputTokens = this[RequestLogs.outputTokens.sum()] ?: 0L,
            totalTokens = this[RequestLogs.totalTokens.sum()] ?: 0L,
            costs = emptyMap(),
        )
    }

    private fun groupedQuery(
        idColumn: org.jetbrains.exposed.v1.core.Column<String?>,
        nameColumn: org.jetbrains.exposed.v1.core.Column<String?>,
        where: Op<Boolean>,
        unassignedId: String = "__unassigned_key__",
    ): List<UsageGroup> {
        val count = RequestLogs.id.count()
        val failure = (RequestLogs.statusCode greaterEq 400) or (RequestLogs.errorCode.isNotNull())
        val errors = Case()
            .When(failure, org.jetbrains.exposed.v1.core.longLiteral(1L))
            .Else(org.jetbrains.exposed.v1.core.longLiteral(0L))
            .sum()
        val input = RequestLogs.inputTokens.sum()
        val output = RequestLogs.outputTokens.sum()
        val tokens = RequestLogs.totalTokens.sum()
        val cost = RequestLogs.costAmount.sum()
        val rows = RequestLogs.select(idColumn, nameColumn, count, errors, input, output, tokens, cost, RequestLogs.costCurrency)
            .where { where }
            .groupBy(idColumn, nameColumn, RequestLogs.costCurrency)
            .toList()
        return rows.groupBy { row ->
            row[idColumn]?.takeIf { it.isNotBlank() } ?: unassignedId
        }.map { (id, grouped) ->
            UsageGroup(
                id = id,
                name = grouped.firstNotNullOfOrNull { it[nameColumn]?.takeIf { value -> value.isNotBlank() } },
                requests = grouped.sumOf { it[count] },
                errors = grouped.sumOf { it[errors] ?: 0L },
                inputTokens = grouped.sumOf { it[input] ?: 0L },
                outputTokens = grouped.sumOf { it[output] ?: 0L },
                totalTokens = grouped.sumOf { it[tokens] ?: 0L },
                costs = grouped.mapNotNull { row ->
                    val currency = row[RequestLogs.costCurrency]
                    val amount = row[cost]
                    if (currency != null && amount != null) currency to amount else null
                }.groupingBy { it.first }.fold(BigDecimal.ZERO) { total, entry -> total + entry.second },
            )
        }.sortedByDescending { it.totalTokens }
    }

    suspend fun deleteOlderThan(cutoff: Long, batchSize: Int): CleanupResult = suspendTransaction(database) {
        val ids = RequestLogs.select(RequestLogs.id)
            .where { RequestLogs.createdAt less cutoff }
            .orderBy(RequestLogs.id)
            .limit(batchSize.coerceIn(100, 100_000))
            .map { it[RequestLogs.id] }
        if (ids.isEmpty()) return@suspendTransaction CleanupResult(0, cutoff)
        val deleted = ids.chunked(10_000).sumOf { chunk ->
            RequestLogs.deleteWhere { RequestLogs.id inList chunk }
        }
        CleanupResult(deleted.toLong(), cutoff)
    }

    private fun toRecord(row: ResultRow) = RequestLogRecord(
        requestId = row[RequestLogs.requestId],
        traceId = row[RequestLogs.traceId],
        clientKeyId = row[RequestLogs.clientKeyId],
        clientKeyName = row[RequestLogs.clientKeyName],
        alias = row[RequestLogs.alias],
        channelId = row[RequestLogs.channelId],
        channelName = row[RequestLogs.channelName],
        upstreamModel = row[RequestLogs.upstreamModel],
        protocol = GatewayProtocol.fromValue(row[RequestLogs.protocol]) ?: GatewayProtocol.CHAT_COMPLETIONS,
        stream = row[RequestLogs.stream],
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
        upstreamAttempts = row[RequestLogs.upstreamAttempts],
        id = row[RequestLogs.id],
    )

}
