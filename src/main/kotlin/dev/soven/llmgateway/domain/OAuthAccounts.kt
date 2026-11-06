package dev.soven.llmgateway.domain

/**
 * 订阅账号的 OAuth 提供方。
 *
 * @author 文杰
 * @since 2026-11-06
 */
enum class OAuthProvider(val value: String) {
    CHATGPT("chatgpt"),
    ANTIGRAVITY("antigravity"),
    ;

    companion object {
        fun fromValue(value: String): OAuthProvider? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 订阅账号的刷新状态。REAUTH_REQUIRED 表示 refresh token 已失效，只能由管理员重新导入。
 *
 * @author 文杰
 * @since 2026-11-06
 */
enum class OAuthAuthState(val value: String) {
    ACTIVE("ACTIVE"),
    REAUTH_REQUIRED("REAUTH_REQUIRED"),
    ;

    companion object {
        fun fromValue(value: String): OAuthAuthState? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 订阅账号的 OAuth 状态。长期 refresh token 不在这里，仍保存在 channel_keys 的加密凭据列。
 * access token 只保存密文，读取时按需解密。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthAccountRecord(
    val keyId: String,
    val provider: OAuthProvider,
    val accountEmail: String? = null,
    val accountPlan: String? = null,
    val accessTokenCiphertext: String? = null,
    val accessTokenNonce: String? = null,
    val accessTokenKeyVersion: Int? = null,
    val accessTokenExpiresAt: Long? = null,
    val tokenType: String = "Bearer",
    val scope: String? = null,
    val authState: OAuthAuthState = OAuthAuthState.ACTIVE,
    val refreshFailureCount: Int = 0,
    val lastRefreshError: String? = null,
    val lastRefreshedAt: Long? = null,
    val proxyUrl: String? = null,
    /** ChatGPT 账号 id，来自 access token JWT，用于读取上游额度。 */
    val chatgptAccountId: String? = null,
    /** 最近一次上游额度读数；从未读取或读取失败时为 null。 */
    val quota: OAuthQuotaSnapshot? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val hasAccessToken: Boolean
        get() = accessTokenCiphertext != null && accessTokenNonce != null &&
            accessTokenKeyVersion != null && accessTokenExpiresAt != null

    /**
     * 下次应该刷新的时间。短寿命 token 把提前量压到寿命的一半，避免每次请求都触发刷新。
     */
    fun refreshDueAt(refreshAheadMs: Long): Long {
        val expiresAt = accessTokenExpiresAt ?: return Long.MIN_VALUE
        val issuedAt = lastRefreshedAt ?: return expiresAt - refreshAheadMs
        val lifetime = (expiresAt - issuedAt).coerceAtLeast(0)
        val ahead = minOf(refreshAheadMs, maxOf(60_000, lifetime / 2))
        return expiresAt - ahead
    }
}

/**
 * 上游额度窗口。`usedPercent` 是 0..100 的使用比例，`resetsAt` 是 epoch millis。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthQuotaWindow(
    val usedPercent: Double,
    val windowSeconds: Int? = null,
    val resetsAt: Long? = null,
) {
    val remainingPercent: Double
        get() = (100.0 - usedPercent).coerceIn(0.0, 100.0)

    /** 窗口已用尽，且重置时间还没到。 */
    fun isExhausted(now: Long): Boolean = usedPercent >= 100.0 && (resetsAt == null || resetsAt > now)
}

/**
 * 上游额度快照。ChatGPT 的 `/wham/usage` 给出 5 小时与每周两个窗口，
 * 以及账号是否仍被允许请求、credits 余额和可重置额度次数。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthQuotaSnapshot(
    val primary: OAuthQuotaWindow? = null,
    val secondary: OAuthQuotaWindow? = null,
    val allowed: Boolean? = null,
    val creditsBalance: Double? = null,
    val creditsUnlimited: Boolean = false,
    val resetCredits: Int? = null,
    val checkedAt: Long,
    val error: String? = null,
) {
    /** 额度用尽后仍可继续消耗 credits，不应按用尽处理。 */
    val hasCredits: Boolean
        get() = creditsUnlimited || (creditsBalance ?: 0.0) > 0.0

    /**
     * 账号被上游额度拦下时的最早可重试时间：取所有已用尽窗口中最晚的重置时间。
     * 没有可用读数或仍有 credits 时返回 null，表示不据此拦截。
     */
    /**
     * 额度用尽时预计恢复的时间。上游没有给出重置时间时，按 [fallbackMs] 保守拦截，
     * 至少不会在读数过期前继续打一个已知用尽的账号；[fallbackMs] 为 0 表示不拦截。
     */
    fun blockedUntil(now: Long, fallbackMs: Long = 0): Long? {
        if (hasCredits) return null
        val windows = listOfNotNull(primary, secondary)
        val exhausted = windows.filter { it.isExhausted(now) }
        if (exhausted.isEmpty()) return null
        val resets = exhausted.mapNotNull { it.resetsAt }
        if (resets.isNotEmpty()) return resets.maxOrNull()?.takeIf { it > now }
        return fallbackMs.takeIf { it > 0 }?.let { now + it }
    }

    /** 读数是否仍可作为调度依据；过期读数只展示，不拦截。 */
    fun isFresh(now: Long, maxAgeMs: Long): Boolean = checkedAt > 0 && now - checkedAt <= maxAgeMs
}

/**
 * 解密后的短期 access token。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthAccessToken(
    val accessToken: String,
    val expiresAt: Long,
    val tokenType: String = "Bearer",
)

/**
 * 订阅账号级调度策略。窗口按账号统计，不区分模型，与上游订阅配额的作用域一致。
 * [windowLimit] 为 null 表示不限制窗口，只保留并发与 429 冷却。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthAccountPolicy(
    val windowLimit: Int? = null,
    val windowSeconds: Int = 10_800,
    val maxConcurrentRequests: Int = 1,
) {
    init {
        require(windowLimit == null || windowLimit in 1..100_000) {
            "账号窗口请求数必须在 1..100000 之间"
        }
        require(windowSeconds in 60..604_800) {
            "账号窗口时长必须在 60..604800 秒之间"
        }
        require(maxConcurrentRequests in 1..1_000) {
            "账号最大并发请求数必须在 1..1000 之间"
        }
    }
}

/**
 * 账号级调度状态快照。窗口命中是准入时间戳（epoch millis），按时间升序。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthWindowSnapshot(
    val keyId: String,
    val hits: List<Long> = emptyList(),
    val cooldownUntil: Long? = null,
    val consecutiveRateLimits: Int = 0,
    val updatedAt: Long,
)

/** 从数据库恢复的账号级状态，同时带回恢复时使用的策略。 */
data class OAuthStoredWindowState(
    val snapshot: OAuthWindowSnapshot,
    val policy: OAuthAccountPolicy,
)

/**
 * 账号级调度的实时视图，只用于管理面看板，不参与路由判定。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthAccountRuntimeState(
    val windowUsed: Int = 0,
    val cooldownUntil: Long? = null,
    val consecutiveRateLimits: Int = 0,
    val activeRequests: Int = 0,
    /** 上游额度用尽时预计恢复的时间；只在读数未过期时给出。 */
    val quotaBlockedUntil: Long? = null,
)

