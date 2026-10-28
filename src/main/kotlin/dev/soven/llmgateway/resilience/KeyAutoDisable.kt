package dev.soven.llmgateway.resilience

/** 上游密钥自动禁用的全局策略，两个条件任一满足即停用。 */
data class KeyAutoDisablePolicy(
    val enabled: Boolean = true,
    val authFailureThreshold: Int = 3,
    val failureThreshold: Int = 5,
    val failureWindowMinutes: Int = 5,
) {
    init {
        require(authFailureThreshold in 1..100) { "连续 401/403 次数必须在 1..100 之间" }
        require(failureThreshold in 1..1000) { "窗口内失败次数必须在 1..1000 之间" }
        require(failureWindowMinutes in 1..1440) { "失败统计窗口必须在 1..1440 分钟之间" }
    }

    /** 成功仅清零连续鉴权失败，不抹去仍在滑动窗口内的失败。 */
    fun record(state: KeyFailureState, outcome: KeyAttemptOutcome, now: Long): KeyFailureUpdate {
        if (!enabled) return KeyFailureUpdate(KeyFailureState())
        val cutoff = now - failureWindowMinutes * 60_000L
        val failures = state.recentFailures.filter { it > cutoff }.toMutableList()
        if (outcome == KeyAttemptOutcome.AUTH_FAILURE || outcome == KeyAttemptOutcome.FAILURE) failures += now
        val authStreak = if (outcome == KeyAttemptOutcome.AUTH_FAILURE) {
            (state.authFailureStreak + 1).coerceAtMost(authFailureThreshold)
        } else 0
        val reason = when {
            authStreak >= authFailureThreshold -> REASON_AUTH_STREAK
            failures.size >= failureThreshold -> REASON_FAILURE_WINDOW
            else -> null
        }
        return KeyFailureUpdate(KeyFailureState(authStreak, failures), reason)
    }

    companion object {
        const val REASON_AUTH_STREAK = "auth_failure_streak"
        const val REASON_FAILURE_WINDOW = "failure_window"
    }
}

/** 客户端取消和网关本地错误不提交结果；其他 HTTP 响应打断鉴权连续失败。 */
enum class KeyAttemptOutcome { SUCCESS, AUTH_FAILURE, FAILURE, OTHER_RESPONSE }

data class KeyFailureState(
    val authFailureStreak: Int = 0,
    val recentFailures: List<Long> = emptyList(),
)

data class KeyFailureUpdate(val state: KeyFailureState, val reason: String? = null)

data class KeyAutoDisableSettingsRecord(
    val policy: KeyAutoDisablePolicy = KeyAutoDisablePolicy(),
    val revision: Long = 0,
    val updatedAt: Long = 0,
)
