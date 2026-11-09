package dev.soven.llmgateway.quota.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次分钟窗口限流准入结果。拒绝不消耗窗口名额。 */
sealed interface RequestRateLimitDecision {
    data class Allowed(val lease: RequestRateLease) : RequestRateLimitDecision

    data object Denied : RequestRateLimitDecision
}

/**
 * 按 UTC 固定分钟窗口限制密钥请求数。
 * 状态只在单实例内保存，分钟切换时旧窗口会被清理。
 */
class RequestRateLimiter(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val keyWindows = mutableMapOf<String, WindowCounter>()
    private var lastPrunedMinute: Long? = null

    suspend fun acquire(clientKeyId: String, requestsPerMinute: Int?): RequestRateLimitDecision = mutex.withLock {
        val minute = currentMinute(now())
        pruneExpiredWindows(minute)
        val keyWindow = keyWindows.getOrPut(clientKeyId) { WindowCounter(minute) }
        if (requestsPerMinute != null && keyWindow.requests >= requestsPerMinute) {
            return@withLock RequestRateLimitDecision.Denied
        }
        keyWindow.requests += 1
        RequestRateLimitDecision.Allowed(RequestRateLease(clientKeyId = clientKeyId, minute = minute))
    }

    suspend fun currentKeyUsage(clientKeyId: String): Int = mutex.withLock {
        val minute = currentMinute(now())
        pruneExpiredWindows(minute)
        keyWindows[clientKeyId]?.requests ?: 0
    }

    private fun pruneExpiredWindows(minute: Long) {
        if (lastPrunedMinute == minute) return
        keyWindows.entries.removeIf { it.value.minute != minute }
        lastPrunedMinute = minute
    }

    private data class WindowCounter(
        val minute: Long,
        var requests: Int = 0,
    )
}

data class RequestRateLease(
    val clientKeyId: String,
    val minute: Long,
)

fun currentMinute(now: Long): Long = now / 60_000L