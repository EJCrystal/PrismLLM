package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationDeliveryResult
import dev.soven.prismllm.admin.domain.NotificationSettings
import dev.soven.prismllm.admin.persistence.NotificationRepository
import dev.soven.prismllm.requestlog.domain.AttemptOutcome
import dev.soven.prismllm.requestlog.domain.ChannelHealthSummary
import dev.soven.prismllm.requestlog.domain.UpstreamAttemptRecord
import dev.soven.prismllm.requestlog.domain.attemptOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/** 请求线程只提交不含正文的观测；状态持久化与 HTTP 投递在各自的后台协程完成。 */
class NotificationRuntime(
    private val repository: NotificationRepository,
    private val transport: NotificationTransport,
    private val scope: CoroutineScope,
    private val healthSnapshot: suspend () -> List<ChannelHealthSummary>,
    private val channelNames: suspend () -> Map<String, String>,
    private val onDropped: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    private val onDelivery: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = 1024,
) {
    private val logger = LoggerFactory.getLogger(NotificationRuntime::class.java)
    private data class Observation(val generation: Long, val attempt: NotificationAttempt)
    private val observations = Channel<Observation>(queueCapacity)
    private val queueLock = Any()
    private val control = Mutex()
    private var generation = 0L
    private var processedGeneration = -1L
    private var accepting = false
    private var settings = NotificationSettings()
    private var observationJob: Job? = null
    private var healthJob: Job? = null
    private var deliveryJob: Job? = null
    private val deliveryMutex = Mutex()

    suspend fun start() = control.withLock {
        if (observationJob != null) return@withLock
        settings = repository.loadSettings()
        repository.resetStreakCounts()
        synchronized(queueLock) { accepting = settings.enabled }
        observationJob = scope.launch {
            for (observation in observations) {
                try {
                    control.withLock {
                        val current = synchronized(queueLock) { generation }
                        if (!settings.enabled || observation.generation != current) return@withLock
                        if (processedGeneration != current) {
                            repository.resetStreakCounts()
                            processedGeneration = current
                        }
                        repository.updateAlert(observation.attempt.subject, "streak") { previous ->
                            streakTransition(previous, observation.attempt, settings.consecutiveFailureThreshold)
                        }
                    }
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    // 任一未写入的观测都打断连续性，不能跨缺失的成功记录累计失败。
                    synchronized(queueLock) { generation++ }
                    failure("observation", cause)
                }
            }
        }
        healthJob = scope.launch {
            while (isActive) {
                try {
                    checkHealth()
                    repository.cleanup(nowMs() - 30L * 86_400_000)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    failure("health", cause)
                }
                delay(60_000)
            }
        }
        deliveryJob = scope.launch {
            while (isActive) {
                try {
                    deliverDue()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    failure("delivery", cause)
                }
                delay(1_000)
            }
        }
    }

    fun observe(attempt: UpstreamAttemptRecord) {
        val channelId = attempt.channelId ?: return
        val credentialId = attempt.channelKeyId ?: return
        val model = attempt.upstreamModel ?: return
        val outcome = attemptOutcome(attempt)
        if (outcome == AttemptOutcome.EXCLUDED) return
        synchronized(queueLock) {
            if (!accepting) return
            val observation = Observation(generation, NotificationAttempt(
                channelId, attempt.channelName, credentialId, attempt.channelKeyName, model, outcome, nowMs(),
            ))
            if (observations.trySend(observation).isFailure) {
                generation++
                onDropped()
            }
        }
    }

    suspend fun updateSettings(enabled: Boolean, threshold: Int): NotificationSettings = control.withLock {
        synchronized(queueLock) { accepting = false; generation++ }
        try {
            val saved = repository.saveSettings(enabled, threshold)
            settings = saved
            repository.resetStreakCounts()
            saved
        } finally {
            synchronized(queueLock) { accepting = settings.enabled }
        }
    }

    internal suspend fun checkHealth() {
        val baseline = synchronized(queueLock) { if (accepting) generation else null } ?: return
        val health = healthSnapshot()
        val names = channelNames()
        control.withLock {
            if (!settings.enabled || synchronized(queueLock) { generation != baseline }) return
            val now = nowMs()
            health.forEach { summary ->
                repository.updateAlert(summary.channelId, "health") { previous ->
                    healthTransition(previous, summary, names[summary.channelId], now)
                }
            }
        }
    }

    internal suspend fun deliverDue() = deliveryMutex.withLock {
        coroutineScope {
            repository.claimDue(limit = 4, leaseMs = 30_000).forEach { task ->
                launch {
                    try {
                        if (!repository.isTaskCurrent(task)) return@launch
                        val outcome = transport.send(task.target, task.event)
                        val retry = !outcome.success && outcome.retryable && task.delivery.attempts < 5
                        val status = if (outcome.success) "succeeded" else if (retry) "pending" else "failed"
                        val next = if (retry) nowMs() + retryDelay(task.delivery.attempts, outcome.retryAfterMs) else null
                        if (repository.complete(task, NotificationDeliveryResult(status, outcome.statusCode, outcome.errorCode, next))) {
                            onDelivery(status)
                        }
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (cause: Exception) {
                        // 未知结果保留租约，过期后用相同事件 ID 重试；异常文本可能包含凭据，不输出。
                        failure("delivery", cause)
                    }
                }
            }
        }
    }

    suspend fun closeAndDrain(timeoutMs: Long = 2_000) {
        synchronized(queueLock) { accepting = false; observations.close() }
        healthJob?.cancelAndJoin()
        if (withTimeoutOrNull(timeoutMs) { observationJob?.join(); true } != true) observationJob?.cancelAndJoin()
        deliveryJob?.cancelAndJoin()
    }

    private fun failure(stage: String, cause: Exception) {
        onFailure(stage)
        logger.warn("notification {} failed, exceptionType={}", stage, cause.javaClass.simpleName)
    }
}

internal fun retryDelay(attempts: Int, retryAfterMs: Long?): Long {
    val backoff = when (attempts) {
        1 -> 5_000L
        2 -> 30_000L
        3 -> 120_000L
        else -> 600_000L
    }
    return maxOf(backoff, (retryAfterMs ?: 0).coerceIn(0, 3_600_000))
}
