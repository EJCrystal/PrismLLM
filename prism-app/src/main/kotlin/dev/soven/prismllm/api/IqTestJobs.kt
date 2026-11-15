package dev.soven.prismllm.api

import dev.soven.prismllm.admin.persistence.IqTestJobRecord
import dev.soven.prismllm.admin.persistence.IqTestJobRepository
import dev.soven.prismllm.admin.persistence.NewIqTestJob
import java.util.UUID

internal enum class IqTestJobStatus(val value: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromValue(value: String): IqTestJobStatus =
            entries.firstOrNull { it.value == value } ?: FAILED
    }
}

internal data class IqTestJobSnapshot(
    val jobId: String,
    val status: IqTestJobStatus,
    val requestJson: String,
    val prompt: String,
    val questionId: Long?,
    val questionTitle: String?,
    val answerType: String?,
    val expectedAnswerValue: String?,
    val judgeId: String?,
    val runId: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun from(record: IqTestJobRecord) = IqTestJobSnapshot(
            jobId = record.jobId,
            status = IqTestJobStatus.fromValue(record.status),
            requestJson = record.requestJson,
            prompt = record.prompt,
            questionId = record.questionId,
            questionTitle = record.questionTitle,
            answerType = record.answerType,
            expectedAnswerValue = record.expectedAnswerValue,
            judgeId = record.judgeId,
            runId = record.runId,
            error = record.error,
        )
    }
}

/** 智商检测任务状态落库，重启后仍可查询并恢复未完成任务。 */
internal class IqTestJobRegistry(private val repository: IqTestJobRepository) {
    suspend fun create(newJob: NewIqTestJob): String {
        repository.create(newJob)
        return newJob.jobId
    }

    suspend fun complete(jobId: String, runId: Long) = repository.complete(jobId, runId)

    suspend fun fail(jobId: String, error: String) = repository.fail(jobId, error)

    suspend fun find(jobId: String): IqTestJobSnapshot? = repository.find(jobId)?.let(IqTestJobSnapshot::from)

    suspend fun running(): List<IqTestJobSnapshot> = repository.running().map(IqTestJobSnapshot::from)

    fun newJobId(): String = UUID.randomUUID().toString()
}

internal fun IqTestJobSnapshot.toResponse() = IqTestJobResponse(
    jobId = jobId,
    status = status.value,
    runId = runId,
    error = error,
)
