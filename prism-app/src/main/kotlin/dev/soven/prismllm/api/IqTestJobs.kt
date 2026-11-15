package dev.soven.prismllm.api

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class IqTestJobStatus(val value: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
}

internal data class IqTestJobSnapshot(
    val jobId: String,
    val status: IqTestJobStatus,
    val runId: Long? = null,
    val error: String? = null,
)

/**
 * 管理台智商检测的后台任务登记表。
 *
 * 任务只在进程内保留一段时间供前端轮询；进程重启后未完成任务会丢失，
 * 已经写入的检测记录不受影响。
 */
internal class IqTestJobRegistry(
    private val maxAgeMs: Long = 30 * 60_000,
    private val maxEntries: Int = 200,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val createdAt: Long,
        val status: IqTestJobStatus,
        val runId: Long? = null,
        val error: String? = null,
    )

    private val jobs = ConcurrentHashMap<String, Entry>()

    fun create(): String {
        prune()
        val jobId = UUID.randomUUID().toString()
        jobs[jobId] = Entry(now(), IqTestJobStatus.RUNNING)
        return jobId
    }

    fun complete(jobId: String, runId: Long) {
        jobs.computeIfPresent(jobId) { _, entry ->
            entry.copy(status = IqTestJobStatus.COMPLETED, runId = runId, error = null)
        }
    }

    fun fail(jobId: String, error: String) {
        jobs.computeIfPresent(jobId) { _, entry ->
            entry.copy(status = IqTestJobStatus.FAILED, runId = null, error = error.take(300))
        }
    }

    fun find(jobId: String): IqTestJobSnapshot? {
        prune()
        val entry = jobs[jobId] ?: return null
        return IqTestJobSnapshot(jobId, entry.status, entry.runId, entry.error)
    }

    private fun prune() {
        val cutoff = now() - maxAgeMs
        jobs.entries.removeIf { it.value.createdAt < cutoff }
        val overflow = jobs.size - maxEntries
        if (overflow <= 0) return
        jobs.entries
            .filter { it.value.status != IqTestJobStatus.RUNNING }
            .sortedBy { it.value.createdAt }
            .take(overflow)
            .forEach { jobs.remove(it.key) }
    }
}

internal fun IqTestJobSnapshot.toResponse() = IqTestJobResponse(
    jobId = jobId,
    status = status.value,
    runId = runId,
    error = error,
)
