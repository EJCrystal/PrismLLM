package dev.soven.llmgateway.plugin.iq

import dev.soven.llmgateway.spi.iq.IqJudgeRequest
import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 在有界线程池中执行阻塞式判分插件，并统一处理超时、取消与非法返回值。 */
class IqJudgeService(
    private val registry: IqJudgeRegistry,
    private val timeoutMs: Long = 60_000,
    private val executor: ThreadPoolExecutor = defaultExecutor(),
) : AutoCloseable {
    suspend fun judge(judgeId: String, request: IqJudgeRequest): IqJudgeResult {
        val judge = registry.find(judgeId)
            ?: return failure("判分器不存在：$judgeId")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val task = FutureTask {
                    val result = try {
                        normalize(judge.judge(request))
                    } catch (cause: Throwable) {
                        if (cause is VirtualMachineError) throw cause
                        failure("判分器执行失败：${cause::class.simpleName}")
                    }
                    if (!continuation.isCancelled) continuation.resume(result)
                }
                continuation.invokeOnCancellation { task.cancel(true) }
                try {
                    executor.execute(task)
                } catch (_: RejectedExecutionException) {
                    if (!continuation.isCancelled) continuation.resume(failure("判分器繁忙，请稍后重试"))
                }
            }
        } ?: failure("判分超时（${timeoutMs / 1000} 秒）")
    }

    private fun normalize(result: IqJudgeResult): IqJudgeResult {
        if (result.label.isBlank()) return failure("判分器返回了空标签")
        if (result.score != null && result.score !in 0.0..100.0) {
            return failure("判分器返回的评分不在 0–100 之间")
        }
        val details = result.details.entries
            .take(32)
            .associate { (key, value) -> key.take(64) to value.take(200) }
        return result.copy(
            label = result.label.take(120),
            reason = result.reason?.take(500),
            details = details,
        )
    }

    private fun failure(reason: String) = IqJudgeResult(
        verdict = IqJudgeVerdict.FAILED,
        label = "无法判定",
        reason = reason.take(500),
    )

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        fun defaultExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
            0,
            4,
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
        ) { work ->
            Thread(work, "iq-judge").apply { isDaemon = true }
        }
    }
}
