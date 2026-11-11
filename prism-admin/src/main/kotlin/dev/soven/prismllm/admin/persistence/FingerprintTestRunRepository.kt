package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.admin.domain.FingerprintTestRunRecord
import dev.soven.prismllm.admin.domain.NewFingerprintTestRun
import dev.soven.prismllm.shared.domain.Usage
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

/** 模型指纹检测历史持久化。完整排名与三条回答保存为 JSON 快照。 */
class FingerprintTestRunRepository(private val database: Database) {
    suspend fun insert(run: NewFingerprintTestRun): FingerprintTestRunRecord = suspendTransaction(database) {
        val id = FingerprintTestRuns.insertReturning(listOf(FingerprintTestRuns.id)) {
            it[channelId] = run.channelId
            it[channelName] = run.channelName
            it[channelKeyId] = run.channelKeyId
            it[channelKeyName] = run.channelKeyName
            it[model] = run.model
            it[protocol] = run.protocol
            it[success] = run.success
            it[statusCode] = run.statusCode
            it[latencyMs] = run.latencyMs
            it[decision] = run.decision
            it[prediction] = run.prediction
            it[predictionName] = run.predictionName
            it[probability] = run.probability
            it[usedOutputs] = run.usedOutputs
            it[totalOutputs] = run.totalOutputs
            it[analysis] = run.analysisJson
            it[samples] = run.samplesJson
            it[errorMessage] = run.errorMessage
            it[inputTokens] = run.usage?.input
            it[outputTokens] = run.usage?.output
            it[cachedTokens] = run.usage?.cached
            it[reasoningTokens] = run.usage?.reasoning
            it[totalTokens] = run.usage?.total
            it[createdAt] = run.createdAt
        }.single()[FingerprintTestRuns.id]
        pruneInTransaction()
        findInTransaction(id) ?: error("指纹检测记录写入后无法读取")
    }

    suspend fun list(limit: Int): List<FingerprintTestRunRecord> = suspendTransaction(database) {
        FingerprintTestRuns.selectAll()
            .orderBy(FingerprintTestRuns.id, SortOrder.DESC)
            .limit(limit.coerceIn(1, MAX_RUNS))
            .map(::toRecord)
    }

    suspend fun find(id: Long): FingerprintTestRunRecord? = suspendTransaction(database) {
        findInTransaction(id)
    }

    private fun findInTransaction(id: Long): FingerprintTestRunRecord? =
        FingerprintTestRuns.selectAll()
            .where { FingerprintTestRuns.id eq id }
            .singleOrNull()
            ?.let(::toRecord)

    private fun pruneInTransaction() {
        val keepIds = FingerprintTestRuns.select(FingerprintTestRuns.id)
            .orderBy(FingerprintTestRuns.id, SortOrder.DESC)
            .limit(MAX_RUNS)
            .map { it[FingerprintTestRuns.id] }
        val cutoff = keepIds.lastOrNull() ?: return
        FingerprintTestRuns.deleteWhere { FingerprintTestRuns.id less cutoff }
    }

    private fun toRecord(row: ResultRow) = FingerprintTestRunRecord(
        id = row[FingerprintTestRuns.id],
        channelId = row[FingerprintTestRuns.channelId],
        channelName = row[FingerprintTestRuns.channelName],
        channelKeyId = row[FingerprintTestRuns.channelKeyId],
        channelKeyName = row[FingerprintTestRuns.channelKeyName],
        model = row[FingerprintTestRuns.model],
        protocol = row[FingerprintTestRuns.protocol],
        success = row[FingerprintTestRuns.success],
        statusCode = row[FingerprintTestRuns.statusCode],
        latencyMs = row[FingerprintTestRuns.latencyMs],
        decision = row[FingerprintTestRuns.decision],
        prediction = row[FingerprintTestRuns.prediction],
        predictionName = row[FingerprintTestRuns.predictionName],
        probability = row[FingerprintTestRuns.probability],
        usedOutputs = row[FingerprintTestRuns.usedOutputs],
        totalOutputs = row[FingerprintTestRuns.totalOutputs],
        analysisJson = row[FingerprintTestRuns.analysis],
        samplesJson = row[FingerprintTestRuns.samples],
        errorMessage = row[FingerprintTestRuns.errorMessage],
        usage = usageFrom(row),
        createdAt = row[FingerprintTestRuns.createdAt],
    )

    private fun usageFrom(row: ResultRow): Usage? {
        val input = row[FingerprintTestRuns.inputTokens]
        val output = row[FingerprintTestRuns.outputTokens]
        val cached = row[FingerprintTestRuns.cachedTokens]
        val reasoning = row[FingerprintTestRuns.reasoningTokens]
        val total = row[FingerprintTestRuns.totalTokens]
        return if (input == null && output == null && cached == null && reasoning == null && total == null) {
            null
        } else {
            Usage(input = input, output = output, cached = cached, reasoning = reasoning, total = total)
        }
    }

    companion object {
        private const val MAX_RUNS = 100
    }
}