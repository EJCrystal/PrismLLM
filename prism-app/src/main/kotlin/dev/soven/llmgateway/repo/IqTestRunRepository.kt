package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.IqTestRuns
import dev.soven.llmgateway.domain.IqTestRunRecord
import dev.soven.llmgateway.domain.NewIqTestRun
import dev.soven.llmgateway.shared.domain.Usage
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/** 智商检测历史持久化。每次运行只保留必要字段，图表源码保存在 response_text。 */
class IqTestRunRepository(private val database: Database) {
    suspend fun insert(run: NewIqTestRun): IqTestRunRecord = suspendTransaction(database) {
        val id = IqTestRuns.insertReturning(listOf(IqTestRuns.id)) {
            it[benchmark] = run.benchmark
            it[channelId] = run.channelId
            it[channelName] = run.channelName
            it[channelKeyId] = run.channelKeyId
            it[channelKeyName] = run.channelKeyName
            it[model] = run.model
            it[upstreamModel] = run.upstreamModel
            it[protocol] = run.protocol
            it[success] = run.success
            it[statusCode] = run.statusCode
            it[latencyMs] = run.latencyMs
            it[verdict] = run.verdict
            it[prompt] = run.prompt
            it[responseText] = run.responseText
            it[expectedAnswer] = run.expectedAnswer
            it[matchedAnswer] = run.matchedAnswer
            it[errorMessage] = run.errorMessage
            it[inputTokens] = run.usage?.input
            it[outputTokens] = run.usage?.output
            it[cachedTokens] = run.usage?.cached
            it[reasoningTokens] = run.usage?.reasoning
            it[totalTokens] = run.usage?.total
            it[upstreamReportedModel] = run.upstreamReportedModel
            it[judgeId] = run.judgeId
            it[judgeLabel] = run.judgeLabel
            it[judgeVerdict] = run.judgeVerdict
            it[judgeResultLabel] = run.judgeResultLabel
            it[judgeReason] = run.judgeReason
            it[judgeScore] = run.judgeScore
            it[judgeDetails] = run.judgeDetails
            it[judgeCheckedAt] = run.judgeCheckedAt
            it[manualVerdict] = run.manualVerdict
            it[manualScore] = run.manualScore
            it[manualNote] = run.manualNote
            it[manualUpdatedAt] = run.manualUpdatedAt
            it[createdAt] = run.createdAt
        }.single()[IqTestRuns.id]
        pruneInTransaction()
        findInTransaction(id) ?: error("智商检测记录写入后无法读取")
    }

    suspend fun list(limit: Int): List<IqTestRunRecord> = suspendTransaction(database) {
        IqTestRuns.selectAll()
            .orderBy(IqTestRuns.id, SortOrder.DESC)
            .limit(limit.coerceIn(1, MAX_LIST_SIZE))
            .map(::toRecord)
    }

    suspend fun find(id: Long): IqTestRunRecord? = suspendTransaction(database) {
        findInTransaction(id)
    }

    suspend fun updateManualVerdict(
        id: Long,
        verdict: String,
        score: Int?,
        note: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ): IqTestRunRecord? = suspendTransaction(database) {
        val changed = IqTestRuns.update({ IqTestRuns.id eq id }) {
            it[IqTestRuns.verdict] = verdict
            it[manualVerdict] = verdict
            it[manualScore] = score
            it[manualNote] = note
            it[manualUpdatedAt] = updatedAt
        }
        if (changed == 0) null else findInTransaction(id)
    }

    private fun findInTransaction(id: Long): IqTestRunRecord? =
        IqTestRuns.selectAll()
            .where { IqTestRuns.id eq id }
            .singleOrNull()
            ?.let(::toRecord)

    /** 只保留最近 [MAX_RUNS] 次，避免长期使用后历史表无限增长。 */
    private fun pruneInTransaction() {
        val keepIds = IqTestRuns.select(IqTestRuns.id)
            .orderBy(IqTestRuns.id, SortOrder.DESC)
            .limit(MAX_RUNS)
            .map { it[IqTestRuns.id] }
        val cutoff = keepIds.lastOrNull() ?: return
        IqTestRuns.deleteWhere { IqTestRuns.id less cutoff }
    }

    private fun toRecord(row: ResultRow) = IqTestRunRecord(
        id = row[IqTestRuns.id],
        benchmark = row[IqTestRuns.benchmark],
        channelId = row[IqTestRuns.channelId],
        channelName = row[IqTestRuns.channelName],
        channelKeyId = row[IqTestRuns.channelKeyId],
        channelKeyName = row[IqTestRuns.channelKeyName],
        model = row[IqTestRuns.model],
        upstreamModel = row[IqTestRuns.upstreamModel],
        protocol = row[IqTestRuns.protocol],
        success = row[IqTestRuns.success],
        statusCode = row[IqTestRuns.statusCode],
        latencyMs = row[IqTestRuns.latencyMs],
        verdict = row[IqTestRuns.verdict],
        prompt = row[IqTestRuns.prompt],
        responseText = row[IqTestRuns.responseText],
        expectedAnswer = row[IqTestRuns.expectedAnswer],
        matchedAnswer = row[IqTestRuns.matchedAnswer],
        errorMessage = row[IqTestRuns.errorMessage],
        usage = usageFrom(row),
        upstreamReportedModel = row[IqTestRuns.upstreamReportedModel],
        judgeId = row[IqTestRuns.judgeId],
        judgeLabel = row[IqTestRuns.judgeLabel],
        judgeVerdict = row[IqTestRuns.judgeVerdict],
        judgeResultLabel = row[IqTestRuns.judgeResultLabel],
        judgeReason = row[IqTestRuns.judgeReason],
        judgeScore = row[IqTestRuns.judgeScore],
        judgeDetails = row[IqTestRuns.judgeDetails],
        judgeCheckedAt = row[IqTestRuns.judgeCheckedAt],
        manualVerdict = row[IqTestRuns.manualVerdict],
        manualScore = row[IqTestRuns.manualScore],
        manualNote = row[IqTestRuns.manualNote],
        manualUpdatedAt = row[IqTestRuns.manualUpdatedAt],
        createdAt = row[IqTestRuns.createdAt],
    )

    private fun usageFrom(row: ResultRow): Usage? {
        val input = row[IqTestRuns.inputTokens]
        val output = row[IqTestRuns.outputTokens]
        val cached = row[IqTestRuns.cachedTokens]
        val reasoning = row[IqTestRuns.reasoningTokens]
        val total = row[IqTestRuns.totalTokens]
        return if (input == null && output == null && cached == null && reasoning == null && total == null) {
            null
        } else {
            Usage(input = input, output = output, cached = cached, reasoning = reasoning, total = total)
        }
    }

    companion object {
        private const val MAX_RUNS = 200
        private const val MAX_LIST_SIZE = 100
    }
}
