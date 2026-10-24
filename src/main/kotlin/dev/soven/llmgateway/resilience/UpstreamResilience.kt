package dev.soven.llmgateway.resilience

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * 上游故障转移的统一策略：指数退避、Retry-After 和渠道熔断参数。
 */
data class UpstreamResiliencePolicy(
    val backoffBaseMs: Long = 100,
    val backoffMaxMs: Long = 2_000,
    val jitterMs: Long = 50,
    val circuitFailureThreshold: Int = 3,
    val circuitOpenMs: Long = 30_000,
) {
    init {
        require(backoffBaseMs >= 0) { "backoffBaseMs must be non-negative" }
        require(backoffMaxMs >= backoffBaseMs) { "backoffMaxMs must be >= backoffBaseMs" }
        require(jitterMs >= 0) { "jitterMs must be non-negative" }
        require(circuitFailureThreshold > 0) { "circuitFailureThreshold must be positive" }
        require(circuitOpenMs > 0) { "circuitOpenMs must be positive" }
    }

    fun retryDelayMs(
        failedAttemptIndex: Int,
        retryAfterMs: Long? = null,
        random: Random = Random.Default,
    ): Long {
        var exponential = backoffBaseMs
        repeat(failedAttemptIndex.coerceAtLeast(0)) {
            exponential = min(backoffMaxMs, exponential * 2)
        }
        val jitter = if (jitterMs > 0) random.nextLong(jitterMs + 1) else 0
        return maxOf(exponential + jitter, retryAfterMs ?: 0)
    }
}

data class CircuitPermit(
    val channelId: String,
    val halfOpen: Boolean,
)

data class CircuitFailureResult(val openedNow: Boolean)

enum class CircuitStatus {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

/**
 * 按渠道维护连续失败次数。达到阈值后短暂拒绝请求，冷却结束后只放行一个半开探测。
 */
class UpstreamCircuitBreaker(
    private val policy: UpstreamResiliencePolicy,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val states = ConcurrentHashMap<String, State>()

    @Synchronized
    fun acquire(channelId: String): CircuitPermit? {
        val state = states.getOrPut(channelId) { State() }
        return when (state.status) {
            CircuitStatus.CLOSED -> CircuitPermit(channelId, halfOpen = false)
            CircuitStatus.OPEN -> {
                if (nowMs() < state.openUntil) {
                    null
                } else {
                    state.status = CircuitStatus.HALF_OPEN
                    state.halfOpenProbeInFlight = true
                    CircuitPermit(channelId, halfOpen = true)
                }
            }
            CircuitStatus.HALF_OPEN -> {
                if (state.halfOpenProbeInFlight) {
                    null
                } else {
                    state.halfOpenProbeInFlight = true
                    CircuitPermit(channelId, halfOpen = true)
                }
            }
        }
    }

    @Synchronized
    fun recordSuccess(permit: CircuitPermit) {
        val state = states.getOrPut(permit.channelId) { State() }
        if (!permit.halfOpen && state.status != CircuitStatus.CLOSED) return
        state.status = CircuitStatus.CLOSED
        state.consecutiveFailures = 0
        state.openUntil = 0
        state.halfOpenProbeInFlight = false
    }

    @Synchronized
    fun recordFailure(permit: CircuitPermit): CircuitFailureResult {
        val state = states.getOrPut(permit.channelId) { State() }
        return if (permit.halfOpen) {
            open(state)
            CircuitFailureResult(openedNow = true)
        } else if (state.status != CircuitStatus.CLOSED) {
            CircuitFailureResult(openedNow = false)
        } else if (++state.consecutiveFailures >= policy.circuitFailureThreshold) {
            open(state)
            CircuitFailureResult(openedNow = true)
        } else {
            CircuitFailureResult(openedNow = false)
        }
    }

    /**
     * 请求取消或收到不重试的错误时，归还半开探测资格但不把它当成健康成功。
     */
    @Synchronized
    fun release(permit: CircuitPermit) {
        if (!permit.halfOpen) return
        val state = states.getOrPut(permit.channelId) { State() }
        state.status = CircuitStatus.OPEN
        state.openUntil = nowMs() + policy.circuitOpenMs
        state.halfOpenProbeInFlight = false
    }

    @Synchronized
    fun status(channelId: String): CircuitStatus {
        val state = states[channelId] ?: return CircuitStatus.CLOSED
        return if (state.status == CircuitStatus.OPEN && nowMs() >= state.openUntil) {
            CircuitStatus.HALF_OPEN
        } else {
            state.status
        }
    }

    @Synchronized
    fun isAvailable(channelId: String): Boolean {
        val state = states[channelId] ?: return true
        return when (state.status) {
            CircuitStatus.CLOSED -> true
            CircuitStatus.OPEN -> nowMs() >= state.openUntil
            CircuitStatus.HALF_OPEN -> !state.halfOpenProbeInFlight
        }
    }

    private fun open(state: State) {
        state.status = CircuitStatus.OPEN
        state.consecutiveFailures = policy.circuitFailureThreshold
        state.openUntil = nowMs() + policy.circuitOpenMs
        state.halfOpenProbeInFlight = false
    }

    private data class State(
        var status: CircuitStatus = CircuitStatus.CLOSED,
        var consecutiveFailures: Int = 0,
        var openUntil: Long = 0,
        var halfOpenProbeInFlight: Boolean = false,
    )
}
