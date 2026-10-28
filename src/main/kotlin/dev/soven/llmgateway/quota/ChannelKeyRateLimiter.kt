package dev.soven.llmgateway.quota

import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** 本地限流拒绝原因，用于路由失败转移和指标。 */
enum class ChannelKeyRateLimitReason(val metricValue: String) {
    REQUESTS_PER_MINUTE("requests_per_minute"),
    CONCURRENCY("concurrency"),
}

/** 一次渠道密钥限流准入结果。 */
sealed interface ChannelKeyRateLimitDecision {
    data class Allowed(val lease: ChannelKeyRateLimitLease?) : ChannelKeyRateLimitDecision

    data class Denied(
        val reason: ChannelKeyRateLimitReason,
        val retryAfterMs: Long,
    ) : ChannelKeyRateLimitDecision
}

/** 并发额度租约；RPM 许可在请求尝试时消耗，不需要归还。 */
data class ChannelKeyRateLimitLease(
    val channelId: String,
    val keyId: String,
    val holdsConcurrency: Boolean,
)

/**
 * 渠道密钥对应的上游分组限流器。计数作用域固定为 channelId + keyId，
 * 同一密钥下的全部模型共享额度。
 *
 * RPM 使用令牌桶平滑放行，避免固定分钟窗口在边界瞬间放行两倍额度。
 */
class ChannelKeyRateLimiter(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val mutex = Mutex()
    private val states = mutableMapOf<StateKey, KeyState>()

    suspend fun acquire(key: ChannelKeyRecord): ChannelKeyRateLimitDecision = mutex.withLock {
        val now = nowNanos()
        val stateKey = StateKey(key.channelId, key.id)
        // 管理端 updatePolicy 是已加载状态的权威来源。这样在途请求携带的旧快照
        // 不会把刚保存的新规则回滚，也不会重复重置新规则的令牌桶。
        val policy = states[stateKey]?.policy ?: key.rateLimit
        if (!policy.configured) {
            if (states[stateKey]?.activeRequests == 0) states.remove(stateKey)
            return@withLock ChannelKeyRateLimitDecision.Allowed(null)
        }

        val state = states.getOrPut(stateKey) { KeyState(policy) }
        state.refill(now, policy.requestsPerMinute)

        val rpmRetryAfterMs = policy.requestsPerMinute
            ?.takeIf { state.requestTokens < 1.0 }
            ?.let { retryAfterMs(state.requestTokens, it) }
        val concurrencyBlocked = policy.maxConcurrentRequests?.let { state.activeRequests >= it } == true
        if (rpmRetryAfterMs != null || concurrencyBlocked) {
            val reason = if (rpmRetryAfterMs != null) {
                ChannelKeyRateLimitReason.REQUESTS_PER_MINUTE
            } else {
                ChannelKeyRateLimitReason.CONCURRENCY
            }
            return@withLock ChannelKeyRateLimitDecision.Denied(
                reason = reason,
                retryAfterMs = max(rpmRetryAfterMs ?: 0, if (concurrencyBlocked) 1_000 else 0),
            )
        }

        if (policy.requestsPerMinute != null) state.requestTokens -= 1.0
        val holdsConcurrency = policy.maxConcurrentRequests != null
        if (holdsConcurrency) state.activeRequests += 1
        ChannelKeyRateLimitDecision.Allowed(
            ChannelKeyRateLimitLease(key.channelId, key.id, holdsConcurrency),
        )
    }

    suspend fun release(lease: ChannelKeyRateLimitLease?) {
        if (lease?.holdsConcurrency != true) return
        withContext(NonCancellable) {
            mutex.withLock {
                states[StateKey(lease.channelId, lease.keyId)]?.let { state ->
                    if (state.activeRequests > 0) state.activeRequests -= 1
                }
            }
        }
    }

    /** 配置更新后立即重置 RPM 令牌，同时保留在途请求的并发计数。 */
    suspend fun updatePolicy(channelId: String, keyId: String, policy: ChannelKeyRateLimitPolicy) {
        mutex.withLock {
            val stateKey = StateKey(channelId, keyId)
            val state = states[stateKey]
            if (state == null) {
                if (policy.configured) states[stateKey] = KeyState(policy)
                return@withLock
            }
            state.applyPolicy(policy, nowNanos())
            if (!policy.configured && state.activeRequests == 0) states.remove(stateKey)
        }
    }

    suspend fun clearKey(channelId: String, keyId: String) {
        mutex.withLock { states.remove(StateKey(channelId, keyId)) }
    }

    private fun retryAfterMs(tokens: Double, requestsPerMinute: Int): Long {
        val nanos = ceil((1.0 - tokens) * NANOS_PER_MINUTE / requestsPerMinute).toLong()
        return ((nanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND).coerceAtLeast(1)
    }

    private data class StateKey(val channelId: String, val keyId: String)

    private class KeyState(policy: ChannelKeyRateLimitPolicy) {
        var policy: ChannelKeyRateLimitPolicy = policy
            private set
        var requestTokens: Double = policy.requestsPerMinute?.toDouble() ?: 0.0
        var lastRefillNanos: Long? = null
        var activeRequests: Int = 0

        fun applyPolicy(policy: ChannelKeyRateLimitPolicy, now: Long) {
            if (this.policy == policy) return
            this.policy = policy
            requestTokens = policy.requestsPerMinute?.toDouble() ?: 0.0
            lastRefillNanos = now
        }

        fun refill(now: Long, limit: Int?) {
            if (limit == null) return
            val previous = lastRefillNanos
            if (previous == null) {
                lastRefillNanos = now
                return
            }
            val elapsed = (now - previous).coerceAtLeast(0L)
            if (elapsed > 0L) {
                requestTokens = (requestTokens + elapsed.toDouble() * limit / NANOS_PER_MINUTE)
                    .coerceAtMost(limit.toDouble())
                lastRefillNanos = now
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_MINUTE = 60_000_000_000.0
    }
}
