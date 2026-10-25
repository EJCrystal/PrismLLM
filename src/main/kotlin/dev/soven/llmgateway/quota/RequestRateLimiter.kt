package dev.soven.llmgateway.quota

import dev.soven.llmgateway.api.GatewayException
import dev.soven.llmgateway.domain.ClientKeyRecord
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun acquire(clientKey: ClientKeyRecord): RequestRateLease = mutex.withLock {
        val minute = currentMinute(now())
        pruneExpiredWindows(minute)
        val keyWindow = keyWindows.getOrPut(clientKey.id) { WindowCounter(minute) }
        clientKey.quotas.requestsPerMinute?.let { limit ->
            if (keyWindow.requests >= limit) {
                reject("key_rate_limit_exceeded", "密钥每分钟请求数已达上限")
            }
        }
        keyWindow.requests += 1
        RequestRateLease(clientKeyId = clientKey.id, minute = minute)
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

    private fun reject(code: String, message: String): Nothing =
        throw GatewayException(
            HttpStatusCode.TooManyRequests,
            code,
            message,
            "rate_limit_error",
        )

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
