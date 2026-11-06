package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthAccountRuntimeState
import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthStoredWindowState
import dev.soven.llmgateway.domain.OAuthWindowSnapshot
import dev.soven.llmgateway.domain.saturatedAdd
import dev.soven.llmgateway.domain.saturatedSubtract
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 账号级准入被拒绝的原因，同时用作指标与请求日志的跳过原因。 */
enum class OAuthAccountDenyReason(val metricValue: String) {
    COOLDOWN("oauth_cooldown"),
    QUOTA("oauth_quota"),
    WINDOW("oauth_window"),
    CONCURRENCY("oauth_concurrency"),
}

/** 一次账号级准入结果。非订阅账号返回 [Allowed] 且 lease 为 null。 */
sealed interface OAuthAccountDecision {
    data class Allowed(val lease: OAuthAccountLease?) : OAuthAccountDecision

    data class Denied(
        val reason: OAuthAccountDenyReason,
        val retryAfterMs: Long,
    ) : OAuthAccountDecision
}

/** 并发额度租约；窗口名额在准入时消耗，不需要归还。 */
data class OAuthAccountLease(val accountId: String)

/** 选路所需的账号实时读数；只读内存，不消耗窗口或并发名额。 */
data class OAuthRoutingHint(
    val windowUsed: Int = 0,
    val windowOldestAt: Long? = null,
    val activeRequests: Int = 0,
    /** 未过期的上游额度读数；过期读数不参与排序。 */
    val quota: OAuthQuotaSnapshot? = null,
)

/**
 * 订阅账号级调度：账号级滑动窗口、单号并发上限与 429 冷却。
 *
 * 约束：
 * - 窗口按账号统计，不区分模型，与上游订阅配额的作用域一致。
 * - 内存态是权威来源，快照通过 [onSnapshot] 异步写透，只用于重启恢复。
 * - 窗口名额在准入时消耗；本地中止的尝试也会占用名额，宁可少用不超用。
 * - 429 优先使用上游给出的重置时间，没有提示时按连续次数指数退避。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthAccountScheduler(
    private val defaultCooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxCooldownMs: Long = MAX_COOLDOWN_MS,
    /** 上游额度读数的有效期；过期读数只展示，不参与准入拦截。 */
    private val quotaMaxAgeMs: Long = DEFAULT_QUOTA_MAX_AGE_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSnapshot: (OAuthWindowSnapshot) -> Unit = {},
) {
    init {
        require(defaultCooldownMs >= MIN_RETRY_AFTER_MS) { "默认冷却时间不能小于 1000ms" }
        require(maxCooldownMs >= defaultCooldownMs) { "最大冷却时间不能小于默认冷却时间" }
    }

    private val mutex = Mutex()
    private val states = mutableMapOf<String, AccountState>()

    /** 预检查：只判断是否可准入，不消耗窗口名额或并发额度。 */
    suspend fun check(account: OAuthAccountRecord): OAuthAccountDecision.Denied? {
        val policy = account.oauthPolicy ?: return null
        return mutex.withLock {
            val nowMs = now()
            val state = states[account.id] ?: return@withLock null
            state.prune(nowMs, policy)
            denial(state, policy, nowMs)
        }
    }

    suspend fun acquire(account: OAuthAccountRecord): OAuthAccountDecision {
        val policy = account.oauthPolicy ?: return OAuthAccountDecision.Allowed(null)
        return mutex.withLock {
            val nowMs = now()
            val state = states.getOrPut(account.id) { AccountState() }
            state.prune(nowMs, policy)
            if (state.cooldownUntil?.let { it <= nowMs } == true) state.cooldownUntil = null
            denial(state, policy, nowMs)?.let { return@withLock it }

            if (policy.windowLimit != null) state.hits.addLast(nowMs)
            state.activeRequests += 1
            publishIfDue(account.id, state, nowMs)
            OAuthAccountDecision.Allowed(OAuthAccountLease(account.id))
        }
    }

    /** 归还并发额度。请求取消或异常时也必须调用。 */
    suspend fun release(lease: OAuthAccountLease?) {
        if (lease == null) return
        withContext(NonCancellable) {
            mutex.withLock {
                val state = states[lease.accountId] ?: return@withLock
                if (state.activeRequests > 0) state.activeRequests -= 1
            }
        }
    }

    /**
     * 记录一次上游 429。[retryAfterMs] 来自 Retry-After 或响应体中的重置提示；
     * 没有提示时按连续 429 次数指数退避。
     */
    suspend fun recordRateLimited(
        account: OAuthAccountRecord,
        retryAfterMs: Long?,
        nowMs: Long = now(),
    ) {
        val policy = account.oauthPolicy ?: return
        mutex.withLock {
            val state = states.getOrPut(account.id) { AccountState() }
            state.prune(nowMs, policy)
            state.consecutiveRateLimits =
                (state.consecutiveRateLimits + 1).coerceAtMost(MAX_CONSECUTIVE_RATE_LIMITS)
            // 默认 5 分钟起，连续 429 最多退避到 16 倍（80 分钟）；上游给出的重置时间优先。
            val fallback = defaultCooldownMs * (1L shl (state.consecutiveRateLimits - 1).coerceAtMost(4))
            val cooldown = (retryAfterMs?.takeIf { it > 0 } ?: fallback)
                .coerceIn(MIN_RETRY_AFTER_MS, maxCooldownMs)
            // 饱和加法：异常时钟不会把冷却截止时间溢出成负数而让 429 冷却立即失效。
            state.cooldownUntil = maxOf(state.cooldownUntil ?: 0L, saturatedAdd(nowMs, cooldown))
            publishIfDue(account.id, state, nowMs, force = true)
        }
    }

    /**
     * 记录一次上游额度读数。额度用尽时立即生效，不必等到下一次 429：
     * 读数过期或仍有 credits 时不做拦截，交给窗口与 429 冷却兜底。
     */
    suspend fun noteQuota(accountId: String, quota: OAuthQuotaSnapshot) {
        mutex.withLock {
            states.getOrPut(accountId) { AccountState() }.quota = quota
        }
    }

    /**
     * 记录一次回复头里的额度读数。比轮询更及时，能让刚打到 100% 的账号在下一次请求
     * 就被拦下；只更新内存态，下一次额度扫描仍会以 `/wham/usage` 的完整读数覆盖。
     */
    internal suspend fun noteQuotaHeaders(
        account: OAuthAccountRecord,
        reading: OAuthQuotaHeaderReading?,
        nowMs: Long = now(),
    ) {
        if (reading == null || account.oauthPolicy == null) return
        mutex.withLock {
            val state = states.getOrPut(account.id) { AccountState() }
            state.quota = state.quota.mergeQuotaHeaders(reading, nowMs)
        }
    }

    /** 上游成功响应后清零连续 429 计数；已生效的冷却不会被提前解除。 */
    suspend fun recordSuccess(accountId: String, nowMs: Long = now()) {
        mutex.withLock {
            val state = states[accountId] ?: return@withLock
            if (state.consecutiveRateLimits == 0) return@withLock
            state.consecutiveRateLimits = 0
            publishIfDue(accountId, state, nowMs, force = true)
        }
    }

    /** 管理面看板读取实时状态；从未使用过的账号返回 null，由调用方展示为零值。 */
    suspend fun runtimeState(
        accountId: String,
        policy: OAuthAccountPolicy,
        nowMs: Long = now(),
    ): OAuthAccountRuntimeState? = mutex.withLock {
        val state = states[accountId] ?: return@withLock null
        state.prune(nowMs, policy)
        if (state.cooldownUntil?.let { it <= nowMs } == true) state.cooldownUntil = null
        OAuthAccountRuntimeState(
            windowUsed = state.hits.size,
            cooldownUntil = state.cooldownUntil,
            consecutiveRateLimits = state.consecutiveRateLimits,
            activeRequests = state.activeRequests,
            quotaBlockedUntil = state.quota
                ?.takeIf { it.isFresh(nowMs, quotaMaxAgeMs) }
                ?.blockedUntil(nowMs, quotaMaxAgeMs),
        )
    }

    /**
     * 批量读取选路所需的账号状态。只返回订阅账号；从未使用过的账号不出现在结果中，
     * 由调用方按零值处理。不创建状态，也不消耗窗口名额。
     */
    suspend fun routingHints(
        accounts: Collection<OAuthAccountRecord>,
        nowMs: Long = now(),
    ): Map<String, OAuthRoutingHint> = mutex.withLock {
        val hints = HashMap<String, OAuthRoutingHint>(accounts.size)
        for (account in accounts) {
            val policy = account.oauthPolicy ?: continue
            val state = states[account.id] ?: continue
            state.prune(nowMs, policy)
            hints[account.id] = OAuthRoutingHint(
                windowUsed = state.hits.size,
                windowOldestAt = state.hits.firstOrNull(),
                activeRequests = state.activeRequests,
                quota = state.quota?.takeIf { it.isFresh(nowMs, quotaMaxAgeMs) },
            )
        }
        hints
    }

    /** 管理员重新导入凭据后解除账号冷却，并立即刷新持久化快照。 */
    suspend fun clearCooldown(accountId: String, nowMs: Long = now()): Boolean = mutex.withLock {
        val state = states[accountId] ?: return@withLock false
        state.cooldownUntil = null
        state.consecutiveRateLimits = 0
        publishIfDue(accountId, state, nowMs, force = true)
        true
    }

    /** 启动时恢复快照，并按当前策略裁剪已过窗口的命中。 */
    suspend fun restore(states: Collection<OAuthStoredWindowState>, nowMs: Long = now()) {
        mutex.withLock {
            for (stored in states) {
                val state = AccountState()
                // 丢弃损坏或来自未来时钟的命中，避免异常时间戳把窗口截止时间推到溢出。
                state.hits.addAll(
                    stored.snapshot.hits.asSequence().filter { it in 1..nowMs }.sorted().toList(),
                )
                state.prune(nowMs, stored.policy)
                state.cooldownUntil = stored.snapshot.cooldownUntil?.takeIf { it > nowMs }
                state.consecutiveRateLimits = stored.snapshot.consecutiveRateLimits.coerceAtLeast(0)
                state.lastPublishedAt = nowMs
                this.states[stored.snapshot.accountId] = state
            }
        }
    }

    /** 关闭前把内存态全部提交给写透队列，避免限速发布丢掉最后一次变更。 */
    suspend fun flushSnapshots(nowMs: Long = now()) {
        val snapshots = mutex.withLock {
            states.map { (accountId, state) -> state.snapshot(accountId, nowMs) }
        }
        for (snapshot in snapshots) {
            try {
                onSnapshot(snapshot)
            } catch (_: Exception) {
                // 写透队列已关闭时丢弃快照；内存态仍是权威来源。
            }
        }
    }

    private fun denial(
        state: AccountState,
        policy: OAuthAccountPolicy,
        nowMs: Long,
    ): OAuthAccountDecision.Denied? {
        state.cooldownUntil?.takeIf { it > nowMs }?.let { until ->
            return OAuthAccountDecision.Denied(
                OAuthAccountDenyReason.COOLDOWN,
                saturatedSubtract(until, nowMs).coerceAtLeast(1),
            )
        }
        state.quota
            ?.takeIf { it.isFresh(nowMs, quotaMaxAgeMs) }
            ?.blockedUntil(nowMs, quotaMaxAgeMs)
            ?.let { until ->
                return OAuthAccountDecision.Denied(OAuthAccountDenyReason.QUOTA, until - nowMs)
            }
        val limit = policy.windowLimit
        if (limit != null && state.hits.size >= limit) {
            val oldest = state.hits.first()
            val deadline = saturatedAdd(oldest, windowSpanMs(policy.windowSeconds))
            val retryAfterMs = saturatedSubtract(deadline, nowMs).coerceAtLeast(1)
            return OAuthAccountDecision.Denied(OAuthAccountDenyReason.WINDOW, retryAfterMs)
        }
        if (state.activeRequests >= policy.maxConcurrentRequests) {
            return OAuthAccountDecision.Denied(OAuthAccountDenyReason.CONCURRENCY, CONCURRENCY_RETRY_MS)
        }
        return null
    }

    private fun publishIfDue(
        accountId: String,
        state: AccountState,
        nowMs: Long,
        force: Boolean = false,
    ) {
        val last = state.lastPublishedAt
        if (!force && last != null && nowMs - last < SNAPSHOT_INTERVAL_MS) return
        state.lastPublishedAt = nowMs
        onSnapshot(state.snapshot(accountId, nowMs))
    }

    private class AccountState {
        val hits = ArrayDeque<Long>()
        var activeRequests: Int = 0
        var cooldownUntil: Long? = null
        var consecutiveRateLimits: Int = 0
        var lastPublishedAt: Long? = null
        var quota: OAuthQuotaSnapshot? = null

        fun prune(nowMs: Long, policy: OAuthAccountPolicy) {
            val limit = policy.windowLimit
            if (limit == null) {
                // 不限窗口时命中不参与准入判断，也不应长期占用内存。
                hits.clear()
                return
            }
            val cutoff = saturatedSubtract(nowMs, windowSpanMs(policy.windowSeconds))
            while (hits.isNotEmpty() && hits.first() <= cutoff) hits.removeFirst()
            while (hits.size > limit) hits.removeFirst()
        }

        fun snapshot(accountId: String, updatedAt: Long) = OAuthWindowSnapshot(
            accountId = accountId,
            hits = hits.toList(),
            cooldownUntil = cooldownUntil,
            consecutiveRateLimits = consecutiveRateLimits,
            updatedAt = updatedAt,
        )
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 300_000L
        const val MAX_COOLDOWN_MS = 21_600_000L
        const val MIN_RETRY_AFTER_MS = 1_000L
        const val CONCURRENCY_RETRY_MS = 1_000L
        /** 高频请求下窗口命中会持续变化，快照最多每秒发布一次。 */
        const val SNAPSHOT_INTERVAL_MS = 1_000L
        const val MAX_CONSECUTIVE_RATE_LIMITS = 16
        const val DEFAULT_QUOTA_MAX_AGE_MS = 900_000L
    }
}

/** 窗口时长换算为毫秒；策略已限制在 60..604800 秒，这里只防御异常构造。 */
private fun windowSpanMs(windowSeconds: Int): Long = windowSeconds.toLong() * 1_000L
