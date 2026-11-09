package dev.soven.llmgateway.repo

import dev.soven.llmgateway.channel.persistence.ChannelKeys
import dev.soven.llmgateway.channel.persistence.Channels
import dev.soven.llmgateway.db.ClientKeys
import dev.soven.llmgateway.db.RequestLogs
import dev.soven.llmgateway.db.RequestAttempts
import dev.soven.llmgateway.report.attemptOutcome
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.UpstreamAttemptRecord
import dev.soven.llmgateway.domain.PricingMode
import dev.soven.llmgateway.report.CleanupResult
import dev.soven.llmgateway.report.UsageGroup
import dev.soven.llmgateway.report.UsageReport
import dev.soven.llmgateway.report.UsageReportQuery
import dev.soven.llmgateway.report.UsageTotal
import dev.soven.llmgateway.report.UsageTrendPoint
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.avg
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * 请求日志持久化。只写入元数据与用量，不写 prompt 和响应正文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class RequestLogRepository(private val database: Database) {
    private val logger = LoggerFactory.getLogger(RequestLogRepository::class.java)
    private val failoverAggregate: org.jetbrains.exposed.v1.core.Expression<Long?> = run {
        val attempts = coalesce(RequestLogs.upstreamAttempts, org.jetbrains.exposed.v1.core.intLiteral(1))
            .castTo(LongColumnType())
        val normalized = Case()
            .When(attempts less org.jetbrains.exposed.v1.core.longLiteral(1L), org.jetbrains.exposed.v1.core.longLiteral(1L))
            .Else(attempts)
        normalized.sum() - RequestLogs.id.count()
    }

    private companion object {
        const val UNASSIGNED_CLIENT_KEY = "__unassigned_key__"
        const val UNASSIGNED_CHANNEL = "__unrouted__"
        const val UNASSIGNED_MODEL = "__unresolved_model__"
        const val UNASSIGNED_CHANNEL_KEY = "__unassigned_channel_key__"
        const val UNASSIGNED_PROTOCOL = "__unknown_protocol__"
    }

    suspend fun insert(record: RequestLogRecord) = suspendTransaction(database) {
        val logId = RequestLogs.insert {
            it[requestId] = record.requestId
            it[traceId] = record.traceId
            it[clientKeyId] = record.clientKeyId
            it[clientKeyName] = record.clientKeyName
            it[clientIp] = record.clientIp
            it[clientUserAgent] = record.clientUserAgent
            it[alias] = record.alias
            it[channelId] = record.channelId
            it[channelName] = record.channelName
            it[channelKeyId] = record.channelKeyId
            it[channelKeyName] = record.channelKeyName
            it[upstreamModel] = record.upstreamModel
            it[protocol] = record.protocol.value
            it[upstreamProtocol] = record.upstreamProtocol?.value
            it[stream] = record.stream
            it[statusCode] = record.statusCode
            it[firstTokenMs] = record.firstTokenMs
            it[totalMs] = record.totalMs
            it[inputTokens] = record.inputTokens
            it[outputTokens] = record.outputTokens
            it[cachedTokens] = record.cachedTokens
            it[cachedWriteTokens] = record.cachedWriteTokens
            it[reasoningTokens] = record.reasoningTokens
            it[reasoningEffort] = record.reasoningEffort
            it[totalTokens] = record.totalTokens
            it[usageKnown] = record.usageKnown
            it[costAmount] = record.costAmount
            it[costCurrency] = record.costCurrency
            it[inputPricePerMillion] = record.inputPricePerMillion
            it[outputPricePerMillion] = record.outputPricePerMillion
            it[cachedInputPricePerMillion] = record.cachedInputPricePerMillion
            it[cachedInputWritePricePerMillion] = record.cachedInputWritePricePerMillion
            it[pricingMode] = record.pricingMode.value
            it[perRequestPrice] = record.perRequestPrice
            it[priceTiersJson] = PricingTierCodec.encode(record.priceTiers)
            it[errorCode] = record.errorCode
            it[failureStage] = record.failureStage?.value
            it[errorMessage] = record.errorMessage
            it[upstreamErrorCode] = record.upstreamErrorCode
            it[upstreamErrorBody] = record.upstreamErrorBody
            it[upstreamAttempts] = record.upstreamAttempts
            it[attemptsJson] = UpstreamAttemptCodec.encode(record.attempts)
            it[createdAt] = record.createdAt
            it[healthIndexed] = true
        }[RequestLogs.id]
        indexAttempts(logId, record)
    }

    /** Bounded, restart-safe historical indexing; live writes are indexed atomically above. */
    suspend fun indexHealthBatch(cutoff: Long, limit: Int = 250): Int {
        // Health indexing does not need pricing metadata. Read only the health
        // projection so damaged historical price tiers cannot block the batch.
        val ids = suspendTransaction(database) {
            RequestLogs.select(RequestLogs.id)
                .where { (RequestLogs.healthIndexed eq false) and (RequestLogs.createdAt greaterEq cutoff) }
                .orderBy(RequestLogs.createdAt to SortOrder.DESC, RequestLogs.id to SortOrder.DESC)
                .limit(limit).map { it[RequestLogs.id] }
        }
        return indexSelectedHealthBatch(ids)
    }

    /** Process IDs selected by one batch, even if another gateway indexes them first. */
    internal suspend fun indexSelectedHealthBatch(ids: List<Long>): Int {
        var firstFailure: Exception? = null
        for (id in ids) {
            try {
                indexHealthRecord(id)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                // A failed row transaction must not roll back or block its batch peers.
                logger.warn("Could not index channel health for request log {}", id, cause)
                if (firstFailure == null) firstFailure = cause
            }
        }
        firstFailure?.let { throw it }
        // A row may already have been indexed by another instance after this
        // batch selected it. The batch is still non-empty and the caller must
        // continue scanning rather than treating it as the end of the backlog.
        return ids.size
    }

    private suspend fun indexHealthRecord(id: Long): Boolean = suspendTransaction(database) {
        val row = RequestLogs.select(
            RequestLogs.id, RequestLogs.attemptsJson, RequestLogs.stream,
            RequestLogs.outputTokens, RequestLogs.usageKnown, RequestLogs.createdAt, RequestLogs.healthIndexed,
        ).where { RequestLogs.id eq id }.singleOrNull() ?: return@suspendTransaction false
        if (row[RequestLogs.healthIndexed]) return@suspendTransaction false
        indexAttempts(
            id, UpstreamAttemptCodec.decode(row[RequestLogs.attemptsJson]),
            row[RequestLogs.stream], row[RequestLogs.outputTokens],
            row[RequestLogs.usageKnown], row[RequestLogs.createdAt],
        )
        RequestLogs.update({ RequestLogs.id eq id }) { it[healthIndexed] = true }
        true
    }

    private fun indexAttempts(logId: Long, record: RequestLogRecord) = indexAttempts(
        logId, record.attempts, record.stream, record.outputTokens, record.usageKnown, record.createdAt,
    )

    private fun indexAttempts(
        logId: Long, attempts: List<UpstreamAttemptRecord>, stream: Boolean?,
        outputTokens: Long?, usageKnown: Boolean, createdAt: Long,
    ) {
        val indexed = attempts.filterNot { it.skipped }
        indexed.forEachIndexed { ordinal, attempt ->
            val channel = attempt.channelId ?: return@forEachIndexed
            RequestAttempts.insertIgnore {
                it[this.logId] = logId
                it[attemptIndex] = ordinal + 1
                it[channelId] = channel
                it[channelKeyId] = attempt.channelKeyId
                it[upstreamModel] = attempt.upstreamModel
                it[upstreamProtocol] = attempt.upstreamProtocol?.value
                it[this.stream] = stream
                it[outcome] = attemptOutcome(attempt).name
                it[errorCode] = attempt.upstreamErrorCode ?: attempt.errorCode
                it[statusCode] = attempt.upstreamErrorStatusCode ?: attempt.statusCode
                it[firstTokenMs] = attempt.firstTokenMs
                it[elapsedMs] = attempt.elapsedMs
                it[this.outputTokens] = outputTokens?.takeIf { ordinal == indexed.lastIndex && usageKnown }
                it[this.createdAt] = createdAt
            }
        }
    }

    suspend fun list(limit: Int, beforeId: Long? = null): List<RequestLogRecord> = suspendTransaction(database) {
        logQuery(beforeId)
            .orderBy(RequestLogs.id, org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(limit.coerceIn(1, 500))
            .map(::toRecord)
    }

    suspend fun listForDisplay(limit: Int, beforeId: Long? = null): List<RequestLogRecord> = suspendTransaction(database) {
        val records = logQuery(beforeId)
            .orderBy(RequestLogs.id, org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .limit(limit.coerceIn(1, 500))
            .map(::toRecord)
        val clientKeyNames = currentNames(ClientKeys.id, ClientKeys.name, records.mapNotNull { it.clientKeyId })
        val channelNames = currentNames(Channels.id, Channels.name, records.mapNotNull { it.channelId })
        val channelKeyNames = currentNames(ChannelKeys.id, ChannelKeys.name, records.mapNotNull { it.channelKeyId })
        records.map { record ->
            record.copy(
                clientKeyName = record.clientKeyId?.let { clientKeyNames[it] }?.takeIf { it.isNotBlank() }
                    ?: record.clientKeyName,
                channelName = record.channelId?.let { channelNames[it] }?.takeIf { it.isNotBlank() }
                    ?: record.channelName,
                channelKeyName = record.channelKeyId?.let { channelKeyNames[it] }?.takeIf { it.isNotBlank() }
                    ?: record.channelKeyName,
            )
        }
    }

    private fun logQuery(beforeId: Long?): Query {
        val query = RequestLogs.selectAll()
        return if (beforeId != null) query.where { RequestLogs.id less beforeId } else query
    }

    suspend fun report(query: UsageReportQuery): UsageReport = suspendTransaction(database) {
        val where = reportWhere(query)
        val totals = totalQuery(where).singleOrNull()?.toUsageTotal() ?: UsageTotal()
        UsageReport(
            from = query.from,
            to = query.to,
            granularity = reportGranularity(query.from, query.to),
            total = totals.copy(costs = costsByCurrency(where)),
            trend = trendQuery(this, query),
            byClientKey = groupedQuery(
                RequestLogs.clientKeyId,
                RequestLogs.clientKeyName,
                where,
                unassignedId = UNASSIGNED_CLIENT_KEY,
                resolveCurrentNames = { ids -> currentNames(ClientKeys.id, ClientKeys.name, ids) },
            ),
            byChannel = groupedQuery(
                RequestLogs.channelId,
                RequestLogs.channelName,
                where,
                unassignedId = UNASSIGNED_CHANNEL,
                resolveCurrentNames = { ids -> currentNames(Channels.id, Channels.name, ids) },
            ),
            byModel = groupedQuery(RequestLogs.upstreamModel, RequestLogs.upstreamModel, where, unassignedId = UNASSIGNED_MODEL),
            byChannelKey = groupedQuery(
                RequestLogs.channelKeyId,
                RequestLogs.channelKeyName,
                where,
                unassignedId = UNASSIGNED_CHANNEL_KEY,
                resolveCurrentNames = { ids -> currentNames(ChannelKeys.id, ChannelKeys.name, ids) },
            ),
            byProtocol = groupedQuery(RequestLogs.protocol, RequestLogs.protocol, where, unassignedId = UNASSIGNED_PROTOCOL),
            byErrorCode = errorQuery(this, query),
        )
    }

    private fun reportWhere(query: UsageReportQuery): Op<Boolean> {
        val conditions = buildList {
            add((RequestLogs.createdAt greaterEq query.from) and (RequestLogs.createdAt lessEq query.to))
            query.clientKeyId?.let { add(RequestLogs.clientKeyId eq it) }
            query.channelId?.let { add(RequestLogs.channelId eq it) }
            query.model?.let { add(RequestLogs.upstreamModel eq it) }
            query.protocol?.let { add(RequestLogs.protocol eq it) }
        }
        return conditions.reduce { left, right -> left and right }
    }

    private fun reportGranularity(from: Long, to: Long): String {
        val range = (to - from).coerceAtLeast(0)
        return when {
            range <= 2 * 86_400_000L -> "hour"
            range <= 62 * 86_400_000L -> "day"
            else -> "week"
        }
    }

    private fun totalQuery(where: Op<Boolean>): Query {
        val failure = (RequestLogs.statusCode greaterEq 400) or (RequestLogs.errorCode.isNotNull())
        val errorCount = Case()
            .When(failure, org.jetbrains.exposed.v1.core.longLiteral(1L))
            .Else(org.jetbrains.exposed.v1.core.longLiteral(0L))
            .sum()
        val failovers = failoverAggregate
        return RequestLogs.select(
            RequestLogs.id.count(),
            errorCount,
            RequestLogs.inputTokens.sum(),
            RequestLogs.outputTokens.sum(),
            RequestLogs.cachedTokens.sum(),
            RequestLogs.cachedWriteTokens.sum(),
            RequestLogs.reasoningTokens.sum(),
            RequestLogs.totalTokens.sum(),
            RequestLogs.clientKeyId.countDistinct(),
            failovers,
            RequestLogs.firstTokenMs.avg(),
            RequestLogs.totalMs.avg(),
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
            cachedTokens = this[RequestLogs.cachedTokens.sum()] ?: 0L,
            cachedWriteTokens = this[RequestLogs.cachedWriteTokens.sum()] ?: 0L,
            reasoningTokens = this[RequestLogs.reasoningTokens.sum()] ?: 0L,
            totalTokens = this[RequestLogs.totalTokens.sum()] ?: 0L,
            activeClientKeys = this[RequestLogs.clientKeyId.countDistinct()],
            failovers = (this[failoverAggregate]?.toLong() ?: 0L).coerceAtLeast(0L),
            avgFirstTokenMs = this[RequestLogs.firstTokenMs.avg()]?.toLong(),
            avgTotalMs = this[RequestLogs.totalMs.avg()]?.toLong(),
            costs = emptyMap(),
        )
    }

    private fun <T : String?> groupedQuery(
        idColumn: Column<T>,
        nameColumn: Column<T>,
        where: Op<Boolean>,
        unassignedId: String = UNASSIGNED_CLIENT_KEY,
        resolveCurrentNames: (Set<String>) -> Map<String, String> = { emptyMap() },
    ): List<UsageGroup> {
        val count = RequestLogs.id.count()
        val failure = (RequestLogs.statusCode greaterEq 400) or (RequestLogs.errorCode.isNotNull())
        val errors = Case()
            .When(failure, org.jetbrains.exposed.v1.core.longLiteral(1L))
            .Else(org.jetbrains.exposed.v1.core.longLiteral(0L))
            .sum()
        val input = RequestLogs.inputTokens.sum()
        val output = RequestLogs.outputTokens.sum()
        val cached = RequestLogs.cachedTokens.sum()
        val cachedWrite = RequestLogs.cachedWriteTokens.sum()
        val reasoning = RequestLogs.reasoningTokens.sum()
        val tokens = RequestLogs.totalTokens.sum()
        val failovers = failoverAggregate
        val firstTokenSum = RequestLogs.firstTokenMs.sum()
        val firstTokenCount = RequestLogs.firstTokenMs.count()
        val totalSum = RequestLogs.totalMs.sum()
        val totalCount = RequestLogs.totalMs.count()
        val rows = RequestLogs.select(
            idColumn,
            nameColumn,
            count,
            errors,
            input,
            output,
            cached,
            cachedWrite,
            reasoning,
            tokens,
            failovers,
            firstTokenSum,
            firstTokenCount,
            totalSum,
            totalCount,
        )
            .where { where }
            .groupBy(idColumn, nameColumn)
            .toList()
        val costs = groupedCosts(idColumn, where, unassignedId)
        val groups = rows.groupBy { row ->
            row[idColumn]?.takeIf { it.isNotBlank() } ?: unassignedId
        }
        // 只查询当前分组实际出现的实体，避免每次报表读取整张配置表。
        val currentNames = resolveCurrentNames(groups.keys - unassignedId)
        return groups.map { (id, grouped) ->
            val groupRequests = grouped.sumOf { it[count] }
            UsageGroup(
                id = id,
                name = currentNames[id]?.takeIf { it.isNotBlank() }
                    ?: grouped.firstNotNullOfOrNull { it[nameColumn]?.takeIf { value -> value.isNotBlank() } },
                requests = groupRequests,
                errors = grouped.sumOf { it[errors] ?: 0L },
                inputTokens = grouped.sumOf { it[input] ?: 0L },
                outputTokens = grouped.sumOf { it[output] ?: 0L },
                cachedTokens = grouped.sumOf { it[cached] ?: 0L },
                cachedWriteTokens = grouped.sumOf { it[cachedWrite] ?: 0L },
                reasoningTokens = grouped.sumOf { it[reasoning] ?: 0L },
                totalTokens = grouped.sumOf { it[tokens] ?: 0L },
                failovers = grouped.sumOf { it[failovers]?.toLong() ?: 0L }.coerceAtLeast(0L),
                avgFirstTokenMs = grouped.sumOf { it[firstTokenCount] }.takeIf { it > 0 }
                    ?.let { count -> grouped.sumOf { row -> row[firstTokenSum] ?: 0L } / count },
                avgTotalMs = grouped.sumOf { it[totalCount] }.takeIf { it > 0 }
                    ?.let { count -> grouped.sumOf { row -> row[totalSum] ?: 0L } / count },
                costs = costs[id].orEmpty(),
            )
        }.sortedByDescending { it.totalTokens }
    }

    private fun currentNames(
        idColumn: Column<String>,
        nameColumn: Column<String>,
        ids: Collection<String>,
    ): Map<String, String> {
        val distinctIds = ids.asSequence().filter { it.isNotBlank() }.distinct().toList()
        if (distinctIds.isEmpty()) return emptyMap()
        return idColumn.table.select(idColumn, nameColumn)
            .where { idColumn inList distinctIds }
            .mapNotNull { row ->
                val id = row[idColumn].takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = row[nameColumn].takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }
            .toMap()
    }

    private fun <T : String?> groupedCosts(
        idColumn: org.jetbrains.exposed.v1.core.Column<T>,
        where: Op<Boolean>,
        unassignedId: String,
    ): Map<String, Map<String, BigDecimal>> {
        val cost = RequestLogs.costAmount.sum()
        return RequestLogs.select(idColumn, RequestLogs.costCurrency, cost)
            .where { where }
            .groupBy(idColumn, RequestLogs.costCurrency)
            .toList()
            .groupBy { it[idColumn]?.takeIf { value -> value.isNotBlank() } ?: unassignedId }
            .mapValues { (_, rows) ->
                rows.mapNotNull { row ->
                    val currency = row[RequestLogs.costCurrency] ?: return@mapNotNull null
                    val amount = row[cost] ?: return@mapNotNull null
                    currency to amount
                }.groupingBy { it.first }.fold(BigDecimal.ZERO) { total, entry -> total + entry.second }
            }
    }

    private fun trendQuery(transaction: JdbcTransaction, query: UsageReportQuery): List<UsageTrendPoint> {
        val granularity = reportGranularity(query.from, query.to)
        val width = when (granularity) {
            "hour" -> 3_600_000L
            "week" -> 7 * 86_400_000L
            else -> 86_400_000L
        }
        val offset = query.timezoneOffsetMinutes * 60_000L
        val firstBucket = Math.floorDiv(query.from + offset, width)
        val filter = rawFilter(query)
        val sql = """
            select ((created_at + ?) / ? - ?) as bucket,
                   count(*) as requests,
                   sum(case when status_code >= 400 or error_code is not null then 1 else 0 end) as errors,
                   coalesce(sum(input_tokens), 0) as input_tokens,
                   coalesce(sum(output_tokens), 0) as output_tokens,
                   coalesce(sum(cached_tokens), 0) as cached_tokens,
                   coalesce(sum(cached_write_tokens), 0) as cached_write_tokens,
                   coalesce(sum(reasoning_tokens), 0) as reasoning_tokens,
                   coalesce(sum(total_tokens), 0) as total_tokens,
                   sum(cost_amount) as cost_amount,
                   cost_currency
            from request_logs
            where ${filter.sql}
            group by bucket, cost_currency
            order by bucket
        """.trimIndent()
        val args = listOf(
            LongColumnType() to offset,
            LongColumnType() to width,
            LongColumnType() to firstBucket,
        ) + filter.args
        val rows = transaction.exec(sql, args) { result ->
            buildList {
                while (result.next()) {
                    add(
                        TrendRow(
                            bucket = result.getLong("bucket"),
                            requests = result.getLong("requests"),
                            errors = result.getLong("errors"),
                            inputTokens = result.getLong("input_tokens"),
                            outputTokens = result.getLong("output_tokens"),
                            cachedTokens = result.getLong("cached_tokens"),
                            cachedWriteTokens = result.getLong("cached_write_tokens"),
                            reasoningTokens = result.getLong("reasoning_tokens"),
                            totalTokens = result.getLong("total_tokens"),
                            currency = result.getString("cost_currency"),
                            cost = result.getBigDecimal("cost_amount"),
                        ),
                    )
                }
            }
        }.orEmpty()
        return rows.groupBy { it.bucket }
            .map { (bucket, grouped) ->
                UsageTrendPoint(
                    bucketStart = (firstBucket + bucket) * width - offset,
                    requests = grouped.sumOf { it.requests },
                    errors = grouped.sumOf { it.errors },
                    inputTokens = grouped.sumOf { it.inputTokens },
                    outputTokens = grouped.sumOf { it.outputTokens },
                    cachedTokens = grouped.sumOf { it.cachedTokens },
                    cachedWriteTokens = grouped.sumOf { it.cachedWriteTokens },
                    reasoningTokens = grouped.sumOf { it.reasoningTokens },
                    totalTokens = grouped.sumOf { it.totalTokens },
                    costs = grouped.mapNotNull { row ->
                        if (row.currency != null && row.cost != null) row.currency to row.cost else null
                    }.groupingBy { it.first }.fold(BigDecimal.ZERO) { total, entry -> total + entry.second },
                )
            }
            .sortedBy { it.bucketStart }
    }

    private fun errorQuery(transaction: JdbcTransaction, query: UsageReportQuery): List<UsageGroup> {
        val filter = rawFilter(query)
        val sql = """
            select coalesce(error_code, status_code::text) as code, count(*) as requests
            from request_logs
            where ${filter.sql} and (status_code >= 400 or error_code is not null)
            group by code
            order by requests desc
        """.trimIndent()
        val rows = transaction.exec(sql, filter.args) { result ->
            buildList {
                while (result.next()) {
                    add(result.getString("code") to result.getLong("requests"))
                }
            }
        }.orEmpty()
        return rows.map { (code, requests) ->
            val label = code?.takeIf { it.isNotBlank() } ?: "unknown_error"
            UsageGroup(
                id = label,
                name = label,
                requests = requests,
                errors = requests,
                inputTokens = 0,
                outputTokens = 0,
                totalTokens = 0,
                costs = emptyMap(),
            )
        }
    }

    private fun rawFilter(query: UsageReportQuery): RawFilter {
        val conditions = mutableListOf("created_at >= ?", "created_at <= ?")
        val args = mutableListOf<Pair<org.jetbrains.exposed.v1.core.IColumnType<*>, Any?>>(
            LongColumnType() to query.from,
            LongColumnType() to query.to,
        )
        query.clientKeyId?.let {
            conditions += "client_key_id = ?"
            args += TextColumnType() to it
        }
        query.channelId?.let {
            conditions += "channel_id = ?"
            args += TextColumnType() to it
        }
        query.model?.let {
            conditions += "upstream_model = ?"
            args += TextColumnType() to it
        }
        query.protocol?.let {
            conditions += "protocol = ?"
            args += TextColumnType() to it
        }
        return RawFilter(conditions.joinToString(" and "), args)
    }

    private data class RawFilter(
        val sql: String,
        val args: List<Pair<org.jetbrains.exposed.v1.core.IColumnType<*>, Any?>>,
    )

    private data class TrendRow(
        val bucket: Long,
        val requests: Long,
        val errors: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedTokens: Long,
        val cachedWriteTokens: Long,
        val reasoningTokens: Long,
        val totalTokens: Long,
        val currency: String?,
        val cost: BigDecimal?,
    )

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
        clientIp = row[RequestLogs.clientIp],
        clientUserAgent = row[RequestLogs.clientUserAgent],
        alias = row[RequestLogs.alias],
        channelId = row[RequestLogs.channelId],
        channelName = row[RequestLogs.channelName],
        channelKeyId = row[RequestLogs.channelKeyId],
        channelKeyName = row[RequestLogs.channelKeyName],
        upstreamModel = row[RequestLogs.upstreamModel],
        protocol = GatewayProtocol.fromValue(row[RequestLogs.protocol]) ?: GatewayProtocol.CHAT_COMPLETIONS,
        upstreamProtocol = row[RequestLogs.upstreamProtocol]?.let(GatewayProtocol::fromValue),
        stream = row[RequestLogs.stream],
        statusCode = row[RequestLogs.statusCode],
        firstTokenMs = row[RequestLogs.firstTokenMs],
        totalMs = row[RequestLogs.totalMs],
        inputTokens = row[RequestLogs.inputTokens],
        outputTokens = row[RequestLogs.outputTokens],
        cachedTokens = row[RequestLogs.cachedTokens],
        cachedWriteTokens = row[RequestLogs.cachedWriteTokens],
        reasoningTokens = row[RequestLogs.reasoningTokens],
        reasoningEffort = row[RequestLogs.reasoningEffort],
        totalTokens = row[RequestLogs.totalTokens],
        usageKnown = row[RequestLogs.usageKnown],
        costAmount = row[RequestLogs.costAmount],
        costCurrency = row[RequestLogs.costCurrency],
        inputPricePerMillion = row[RequestLogs.inputPricePerMillion],
        outputPricePerMillion = row[RequestLogs.outputPricePerMillion],
        cachedInputPricePerMillion = row[RequestLogs.cachedInputPricePerMillion],
        cachedInputWritePricePerMillion = row[RequestLogs.cachedInputWritePricePerMillion],
        pricingMode = PricingMode.fromValue(row[RequestLogs.pricingMode]),
        perRequestPrice = row[RequestLogs.perRequestPrice],
        priceTiers = PricingTierCodec.decode(row[RequestLogs.priceTiersJson]),
        errorCode = row[RequestLogs.errorCode],
        failureStage = FailureStage.fromValue(row[RequestLogs.failureStage]),
        errorMessage = row[RequestLogs.errorMessage],
        upstreamErrorCode = row[RequestLogs.upstreamErrorCode],
        upstreamErrorBody = row[RequestLogs.upstreamErrorBody],
        createdAt = row[RequestLogs.createdAt],
        upstreamAttempts = row[RequestLogs.upstreamAttempts],
        attempts = UpstreamAttemptCodec.decode(row[RequestLogs.attemptsJson]),
        id = row[RequestLogs.id],
    )

}
