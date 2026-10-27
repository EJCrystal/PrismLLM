package dev.soven.llmgateway.resilience

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 候选冷却参数。瞬时错误按连续失败翻倍递增，429 遵从 Retry-After，401/403 长冷却。
 *
 * @author 文杰
 * @since 2026-10-25
 */
data class CooldownPolicy(
    val transientBaseMs: Long = 30_000,
    val transientMaxMs: Long = 600_000,
    val rateLimitDefaultMs: Long = 30_000,
    val rateLimitMaxMs: Long = 60_000,
    val authFailureMs: Long = 600_000,
    val ttftSlowThresholdMs: Long = 10_000,
    val ttftSlowStreak: Int = 3,
) {
    init {
        require(transientBaseMs > 0) { "transientBaseMs must be positive" }
        require(transientMaxMs >= transientBaseMs) { "transientMaxMs must be >= transientBaseMs" }
        require(rateLimitDefaultMs > 0) { "rateLimitDefaultMs must be positive" }
        require(rateLimitMaxMs >= rateLimitDefaultMs) { "rateLimitMaxMs must be >= rateLimitDefaultMs" }
        require(authFailureMs > 0) { "authFailureMs must be positive" }
        require(ttftSlowThresholdMs > 0) { "ttftSlowThresholdMs must be positive" }
        require(ttftSlowStreak > 0) { "ttftSlowStreak must be positive" }
    }

    fun transientDurationMs(consecutiveFailures: Int): Long {
        val escalation = min(consecutiveFailures.coerceAtLeast(1) - 1, 20)
        var duration = transientBaseMs
        repeat(escalation) {
            duration = min(transientMaxMs, duration * 2)
        }
        return duration
    }

    fun rateLimitDurationMs(retryAfterMs: Long?): Long =
        (retryAfterMs ?: rateLimitDefaultMs).coerceAtMost(rateLimitMaxMs).coerceAtLeast(1)
}

/**
 * 冷却状态的键。传输层健康按渠道记键；凭据失效按密钥记键；429 与首 token 过慢按密钥+模型记键。
 */
sealed interface CooldownKey {
    val id: String

    data class Channel(val channelId: String) : CooldownKey {
        override val id: String = "ch:$channelId"
    }

    data class Key(val channelId: String, val keyId: String) : CooldownKey {
        override val id: String = "key:$channelId:$keyId"
    }

    data class KeyCandidate(
        val channelId: String,
        val keyId: String,
        val upstreamModel: String,
    ) : CooldownKey {
        override val id: String = "kc:$channelId:$keyId:$upstreamModel"
    }
}

/**
 * 上游失败的粗分类，作为冷却分类器的输入。凭证类型扩展（如订阅账号）时在此之上覆写分类。
 */
enum class FailureKind {
    /** 连接失败、超时、空流、首 token 后流中断。 */
    TRANSPORT,

    /** 上游返回 5xx。 */
    SERVER_ERROR,

    /** 上游返回 429。 */
    RATE_LIMITED,

    /** 上游返回 401/403。 */
    AUTH_REJECTED,

    /** 上游返回 404，模型映射类错误，等时间没有用。 */
    MODEL_NOT_FOUND,
}

/**
 * 冷却触发判定结果。
 */
sealed interface CooldownAction {
    /** 瞬时错误：起步时长按连续失败翻倍，封顶 transientMaxMs。 */
    data class Transient(val key: CooldownKey) : CooldownAction

    /** 429：遵从 Retry-After，封顶 rateLimitMaxMs。 */
    data class RateLimited(val key: CooldownKey, val retryAfterMs: Long?) : CooldownAction

    /** 401/403：长冷却，修复靠人。 */
    data class AuthFailure(val key: CooldownKey) : CooldownAction

    /** 不冷却。 */
    data object None : CooldownAction
}

/**
 * 冷却分类器。把失败粗分类映射为冷却动作；订阅账号落地时按渠道凭证类型提供不同实现。
 */
interface CooldownClassifier {
    fun classify(
        kind: FailureKind,
        retryAfterMs: Long?,
        channelId: String,
        keyId: String,
        upstreamModel: String,
    ): CooldownAction
}

/**
 * 默认分类：传输层错误与 5xx 记渠道键，401/403 记密钥键，429 记密钥+模型键，404 不冷却。
 */
class DefaultCooldownClassifier : CooldownClassifier {
    override fun classify(
        kind: FailureKind,
        retryAfterMs: Long?,
        channelId: String,
        keyId: String,
        upstreamModel: String,
    ): CooldownAction = when (kind) {
        FailureKind.TRANSPORT, FailureKind.SERVER_ERROR -> CooldownAction.Transient(CooldownKey.Channel(channelId))
        FailureKind.RATE_LIMITED -> CooldownAction.RateLimited(CooldownKey.KeyCandidate(channelId, keyId, upstreamModel), retryAfterMs)
        FailureKind.AUTH_REJECTED -> CooldownAction.AuthFailure(CooldownKey.Key(channelId, keyId))
        FailureKind.MODEL_NOT_FOUND -> CooldownAction.None
    }
}

/** 持久化的一行冷却状态，用于启动恢复。 */
data class CooldownStateSnapshot(
    val stateKey: String,
    val consecutiveFailures: Int,
    val cooldownUntil: Long,
)

/**
 * 一次上游尝试的冷却凭证。refs 携带走过的键与代际，迟到的结果因代际不匹配被忽略。
 */
data class CooldownPermit(
    val channelId: String,
    val keyId: String,
    val upstreamModel: String,
    val refs: List<Ref>,
) {
    data class Ref(val keyId: String, val generation: Long, val probing: Boolean)
}

/** recordFailure 触发冷却时返回，reason 用于指标。 */
data class CooldownTrigger(val reason: String)

/** 写透持久化的状态变更。 */
sealed interface CooldownChange {
    val stateKey: String

    data class Updated(override val stateKey: String, val consecutiveFailures: Int, val cooldownUntil: Long) : CooldownChange

    data class Removed(override val stateKey: String) : CooldownChange
}

/**
 * 候选冷却状态机（内存为准）。
 *
 * 冷却只后置不禁用：acquire 对冷却中的候选返回 null，由调用方跳过；
 * 冷却到期后放行单个探测请求，探测成功才完全恢复，失败则翻倍重新冷却；
 * 所有候选都在冷却中时调用方用 forceAcquire 照常尝试（fail-open）。
 *
 * @author 文杰
 * @since 2026-10-25
 */
class CandidateCooldown(
    val policy: CooldownPolicy,
    private val classifier: CooldownClassifier = DefaultCooldownClassifier(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onChange: (CooldownChange) -> Unit = {},
) {
    private val states = ConcurrentHashMap<String, KeyState>()
    private val ttftStreaks = ConcurrentHashMap<String, Int>()

    /**
     * 获取一次尝试的许可。渠道、密钥或密钥+模型被冷却且未到探测时机时返回 null。
     */
    @Synchronized
    fun acquire(channelId: String, keyId: String, upstreamModel: String): CooldownPermit? {
        val channelKeyId = CooldownKey.Channel(channelId).id
        val keyStateId = CooldownKey.Key(channelId, keyId).id
        val candidateKeyId = CooldownKey.KeyCandidate(channelId, keyId, upstreamModel).id
        val channelState = states.getOrPut(channelKeyId) { KeyState(channelKeyId) }
        val keyState = states.getOrPut(keyStateId) { KeyState(keyStateId) }
        val candidateState = states.getOrPut(candidateKeyId) { KeyState(candidateKeyId) }
        if (blocked(channelState) || blocked(keyState) || blocked(candidateState)) return null
        // 始终携带三个键的引用：失败动作可能落在渠道、密钥或密钥+模型上。
        val refs = listOf(
            CooldownPermit.Ref(channelKeyId, channelState.generation, channelState.cooldownUntil > 0),
            CooldownPermit.Ref(keyStateId, keyState.generation, keyState.cooldownUntil > 0),
            CooldownPermit.Ref(candidateKeyId, candidateState.generation, candidateState.cooldownUntil > 0),
        )
        if (channelState.cooldownUntil > 0) {
            channelState.probeInFlight = true
        }
        if (keyState.cooldownUntil > 0) {
            keyState.probeInFlight = true
        }
        if (candidateState.cooldownUntil > 0) {
            candidateState.probeInFlight = true
        }
        return CooldownPermit(channelId, keyId, upstreamModel, refs)
    }

    /**
     * fail-open 许可：不检查也不占用冷却状态，供所有候选都被冷却时照常尝试。
     */
    @Synchronized
    fun forceAcquire(channelId: String, keyId: String, upstreamModel: String): CooldownPermit {
        val channelKeyId = CooldownKey.Channel(channelId).id
        val keyStateId = CooldownKey.Key(channelId, keyId).id
        val candidateKeyId = CooldownKey.KeyCandidate(channelId, keyId, upstreamModel).id
        val channelState = states.getOrPut(channelKeyId) { KeyState(channelKeyId) }
        val keyState = states.getOrPut(keyStateId) { KeyState(keyStateId) }
        val candidateState = states.getOrPut(candidateKeyId) { KeyState(candidateKeyId) }
        return CooldownPermit(
            channelId,
            keyId,
            upstreamModel,
            listOf(
                CooldownPermit.Ref(channelKeyId, channelState.generation, probing = false),
                CooldownPermit.Ref(keyStateId, keyState.generation, probing = false),
                CooldownPermit.Ref(candidateKeyId, candidateState.generation, probing = false),
            ),
        )
    }

    @Synchronized
    fun recordSuccess(permit: CooldownPermit) {
        for (ref in permit.refs) {
            val state = states[ref.keyId] ?: continue
            if (ref.generation != state.generation) continue
            reset(state, ref.keyId)
        }
    }

    /**
     * 记录一次失败并返回是否触发（或升级）了冷却。迟到的结果因代际不匹配被忽略。
     */
    @Synchronized
    fun recordFailure(
        permit: CooldownPermit,
        kind: FailureKind,
        retryAfterMs: Long? = null,
    ): CooldownTrigger? {
        val action = classifier.classify(kind, retryAfterMs, permit.channelId, permit.keyId, permit.upstreamModel)
        val targetKeyId = when (action) {
            is CooldownAction.Transient -> action.key.id
            is CooldownAction.RateLimited -> action.key.id
            is CooldownAction.AuthFailure -> action.key.id
            CooldownAction.None -> null
        }
        var trigger: CooldownTrigger? = null
        for (ref in permit.refs) {
            val state = states[ref.keyId] ?: continue
            if (ref.generation != state.generation) continue
            if (ref.keyId == targetKeyId) {
                val duration = when (action) {
                    is CooldownAction.Transient -> {
                        state.consecutiveFailures += 1
                        trigger = CooldownTrigger(REASON_TRANSIENT)
                        policy.transientDurationMs(state.consecutiveFailures)
                    }
                    is CooldownAction.RateLimited -> {
                        state.consecutiveFailures += 1
                        trigger = CooldownTrigger(REASON_RATE_LIMITED)
                        policy.rateLimitDurationMs(action.retryAfterMs)
                    }
                    is CooldownAction.AuthFailure -> {
                        state.consecutiveFailures += 1
                        trigger = CooldownTrigger(REASON_AUTH)
                        policy.authFailureMs
                    }
                    CooldownAction.None -> continue
                }
                state.cooldownUntil = nowMs() + duration
                state.probeInFlight = false
                state.generation += 1
                onChange(CooldownChange.Updated(ref.keyId, state.consecutiveFailures, state.cooldownUntil))
            } else if (kind == FailureKind.TRANSPORT) {
                // 传输层失败对其他键的探测 inconclusive：归还探测资格，等待下一次探测。
                releaseProbe(state)
            } else {
                // 上游给出了 HTTP 响应，说明传输层与渠道本身可用，其他键直接恢复。
                reset(state, ref.keyId)
            }
        }
        return trigger
    }

    /**
     * 请求取消、客户端断开或不可重试错误时归还许可：探测 inconclusive，不奖不罚。
     */
    @Synchronized
    fun release(permit: CooldownPermit) {
        for (ref in permit.refs) {
            val state = states[ref.keyId] ?: continue
            if (!ref.probing) continue
            if (ref.generation != state.generation) continue
            releaseProbe(state)
        }
    }

    /**
     * 记录一次流式请求的首 token 耗时。连续超过软阈值达到次数后触发候选级瞬时冷却；
     * 被计数的请求本身照常完成。返回是否触发。
     */
    @Synchronized
    fun recordFirstToken(channelId: String, keyId: String, upstreamModel: String, firstTokenMs: Long): Boolean {
        if (firstTokenMs <= policy.ttftSlowThresholdMs) {
            ttftStreaks.remove(CooldownKey.KeyCandidate(channelId, keyId, upstreamModel).id)
            return false
        }
        val stateKey = CooldownKey.KeyCandidate(channelId, keyId, upstreamModel).id
        val streak = (ttftStreaks[stateKey] ?: 0) + 1
        if (streak < policy.ttftSlowStreak) {
            ttftStreaks[stateKey] = streak
            return false
        }
        ttftStreaks.remove(stateKey)
        states.getOrPut(stateKey) { KeyState(stateKey) }.let { state ->
            state.consecutiveFailures += 1
            state.cooldownUntil = nowMs() + policy.transientDurationMs(state.consecutiveFailures)
            state.probeInFlight = false
            state.generation += 1
            onChange(CooldownChange.Updated(stateKey, state.consecutiveFailures, state.cooldownUntil))
        }
        return true
    }

    /** 启动时从持久化恢复冷却状态；已过期的行自然进入探测就绪。 */
    @Synchronized
    fun restore(snapshots: List<CooldownStateSnapshot>) {
        for (snapshot in snapshots) {
            states[snapshot.stateKey] = KeyState(
                keyId = snapshot.stateKey,
                consecutiveFailures = snapshot.consecutiveFailures,
                cooldownUntil = snapshot.cooldownUntil,
            )
        }
    }

    /**
     * 无副作用地查看候选当前是否被冷却阻塞，供分摊排序在轮询前分拣可尝试候选。
     * 与 acquire 之间存在竞态窗口，acquire 会再次校验。
     */
    @Synchronized
    fun isBlocked(channelId: String, keyId: String, upstreamModel: String): Boolean {
        // 三个键独立判断：渠道键无状态不代表密钥或候选键不在冷却。
        states[CooldownKey.Channel(channelId).id]?.let { if (blocked(it)) return true }
        states[CooldownKey.Key(channelId, keyId).id]?.let { if (blocked(it)) return true }
        states[CooldownKey.KeyCandidate(channelId, keyId, upstreamModel).id]?.let { if (blocked(it)) return true }
        return false
    }

    @Synchronized
    fun cooldownUntil(channelId: String, keyId: String, upstreamModel: String): Long? {
        var until: Long? = null
        for (key in listOf(
            CooldownKey.Channel(channelId),
            CooldownKey.Key(channelId, keyId),
            CooldownKey.KeyCandidate(channelId, keyId, upstreamModel),
        )) {
            val state = states[key.id] ?: continue
            if (state.cooldownUntil > nowMs() && (until == null || state.cooldownUntil > until)) {
                until = state.cooldownUntil
            }
        }
        return until
    }

    private fun blocked(state: KeyState): Boolean =
        state.cooldownUntil > nowMs() || (state.cooldownUntil > 0 && state.probeInFlight)

    private fun reset(state: KeyState, keyId: String) {
        val wasCooling = state.cooldownUntil > 0 || state.consecutiveFailures > 0
        state.consecutiveFailures = 0
        state.cooldownUntil = 0
        state.probeInFlight = false
        // 只有真正离开冷却态才推进代际：健康态的成功结果不应作废并发请求的许可，
        // 否则并发中的另一请求随后失败会被代际守卫忽略，漏记冷却。
        if (wasCooling) {
            state.generation += 1
            onChange(CooldownChange.Removed(keyId))
        }
    }

    private fun releaseProbe(state: KeyState) {
        state.probeInFlight = false
        state.generation += 1
    }

    private data class KeyState(
        val keyId: String = "",
        var consecutiveFailures: Int = 0,
        var cooldownUntil: Long = 0,
        var probeInFlight: Boolean = false,
        var generation: Long = 0,
    )

    companion object {
        const val REASON_TRANSIENT = "transient"
        const val REASON_RATE_LIMITED = "rate_limited"
        const val REASON_AUTH = "auth_failure"

        /** recordFirstToken 触发时使用的理由，由调用方拼进指标。 */
        const val REASON_TTFT_SLOW = "ttft_slow"
    }
}
