package dev.soven.prismllm.admin.persistence

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class NewIqTestJob(
    val jobId: String,
    val requestJson: String,
    val prompt: String,
    val questionId: Long?,
    val questionTitle: String?,
    val answerType: String?,
    val expectedAnswerValue: String?,
    val judgeId: String?,
    val createdAt: Long = System.currentTimeMillis(),
)

data class IqTestJobRecord(
    val jobId: String,
    val status: String,
    val requestJson: String,
    val prompt: String,
    val questionId: Long?,
    val questionTitle: String?,
    val answerType: String?,
    val expectedAnswerValue: String?,
    val judgeId: String?,
    val runId: Long?,
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
)

/** 智商检测任务状态持久化；未完成任务可在进程重启后重新调度。 */
class IqTestJobRepository(private val database: Database) {
    suspend fun create(job: NewIqTestJob): IqTestJobRecord = suspendTransaction(database) {
        IqTestJobs.insertReturning(listOf(IqTestJobs.jobId)) {
            it[jobId] = job.jobId
            it[status] = "running"
            it[requestJson] = job.requestJson
            it[prompt] = job.prompt
            it[questionId] = job.questionId
            it[questionTitle] = job.questionTitle
            it[answerType] = job.answerType
            it[expectedAnswerValue] = job.expectedAnswerValue
            it[judgeId] = job.judgeId
            it[createdAt] = job.createdAt
        }.single()[IqTestJobs.jobId]
        pruneInTransaction()
        findInTransaction(job.jobId) ?: error("智商检测任务写入后无法读取")
    }

    suspend fun find(jobId: String): IqTestJobRecord? = suspendTransaction(database) {
        findInTransaction(jobId)
    }

    suspend fun running(): List<IqTestJobRecord> = suspendTransaction(database) {
        IqTestJobs.selectAll()
            .where { IqTestJobs.status eq "running" }
            .orderBy(IqTestJobs.createdAt, SortOrder.ASC)
            .map(::toRecord)
    }

    suspend fun complete(jobId: String, runId: Long, completedAt: Long = System.currentTimeMillis()) =
        suspendTransaction(database) {
            IqTestJobs.update({ IqTestJobs.jobId eq jobId }) {
                it[status] = "completed"
                it[IqTestJobs.runId] = runId
                it[error] = null
                it[IqTestJobs.completedAt] = completedAt
            }
        }

    suspend fun fail(jobId: String, error: String, completedAt: Long = System.currentTimeMillis()) =
        suspendTransaction(database) {
            val message = error.take(300).ifBlank { "检测任务失败" }
            IqTestJobs.update({ IqTestJobs.jobId eq jobId }) {
                it[status] = "failed"
                it[runId] = null
                it[IqTestJobs.error] = message
                it[IqTestJobs.completedAt] = completedAt
            }
        }

    private suspend fun pruneInTransaction() {
        val keepIds = IqTestJobs.select(IqTestJobs.jobId)
            .orderBy(IqTestJobs.createdAt, SortOrder.DESC)
            .limit(MAX_JOBS)
            .map { it[IqTestJobs.jobId] }
        if (keepIds.size < MAX_JOBS) return
        val cutoff = IqTestJobs.selectAll()
            .where { IqTestJobs.jobId eq keepIds.last() }
            .single()[IqTestJobs.createdAt]
        IqTestJobs.deleteWhere {
            (IqTestJobs.createdAt less cutoff) and (IqTestJobs.status neq "running")
        }
    }

    private fun findInTransaction(jobId: String): IqTestJobRecord? =
        IqTestJobs.selectAll()
            .where { IqTestJobs.jobId eq jobId }
            .singleOrNull()
            ?.let(::toRecord)

    private fun toRecord(row: ResultRow) = IqTestJobRecord(
        jobId = row[IqTestJobs.jobId],
        status = row[IqTestJobs.status],
        requestJson = row[IqTestJobs.requestJson],
        prompt = row[IqTestJobs.prompt],
        questionId = row[IqTestJobs.questionId],
        questionTitle = row[IqTestJobs.questionTitle],
        answerType = row[IqTestJobs.answerType],
        expectedAnswerValue = row[IqTestJobs.expectedAnswerValue],
        judgeId = row[IqTestJobs.judgeId],
        runId = row[IqTestJobs.runId],
        error = row[IqTestJobs.error],
        createdAt = row[IqTestJobs.createdAt],
        completedAt = row[IqTestJobs.completedAt],
    )

    private companion object {
        const val MAX_JOBS = 200
    }
}
