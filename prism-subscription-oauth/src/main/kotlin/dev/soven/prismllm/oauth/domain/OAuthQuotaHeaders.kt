package dev.soven.prismllm.oauth.domain

import dev.soven.prismllm.shared.domain.saturatedAdd

/**
 * 上游响应头里的额度读数。ChatGPT 在每次回复上带 `x-codex-primary-*` 与
 * `x-codex-secondary-*`，把账号刚刚消耗到的比例直接告诉客户端，比每 5 分钟一次的
 * `/wham/usage` 轮询更及时。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthQuotaHeaderReading(
    val primary: OAuthQuotaWindow? = null,
    val secondary: OAuthQuotaWindow? = null,
)

/**
 * 解析 `x-codex-primary-used-percent`、`-window-minutes`、`-reset-at` /
 * `-reset-after-seconds`（secondary 同理）。缺少已用比例或窗口时长时忽略该窗口，
 * 因为无法与已有的窗口对齐。
 */
fun parseCodexQuotaHeaders(
    headers: (String) -> String?,
    nowMs: Long,
): OAuthQuotaHeaderReading? {
    val primary = parseHeaderWindow("primary", headers, nowMs)
    val secondary = parseHeaderWindow("secondary", headers, nowMs)
    if (primary == null && secondary == null) return null
    return OAuthQuotaHeaderReading(primary, secondary)
}

private fun parseHeaderWindow(
    which: String,
    headers: (String) -> String?,
    nowMs: Long,
): OAuthQuotaWindow? {
    val prefix = "x-codex-$which-"
    val used = headers(prefix + "used-percent")?.trim()?.toDoubleOrNull() ?: return null
    val minutes = headers(prefix + "window-minutes")?.trim()?.toLongOrNull() ?: return null
    if (minutes !in 1..(MAX_WINDOW_SECONDS / 60)) return null
    val resetsAt = parseEpochSeconds(headers(prefix + "reset-at"))
        ?: parseResetAfterSeconds(headers(prefix + "reset-after-seconds"), nowMs)
    return OAuthQuotaWindow(
        usedPercent = used.coerceIn(0.0, 100.0),
        windowSeconds = (minutes * 60).toInt(),
        resetsAt = resetsAt,
    )
}

private fun parseEpochSeconds(raw: String?): Long? {
    val seconds = raw?.trim()?.toLongOrNull()?.takeIf { it in 1..MAX_EPOCH_SECONDS } ?: return null
    return seconds * 1_000
}

private fun parseResetAfterSeconds(raw: String?, nowMs: Long): Long? {
    val seconds = raw?.trim()?.toLongOrNull()?.takeIf { it in 1..MAX_WINDOW_SECONDS } ?: return null
    return saturatedAdd(nowMs, seconds * 1_000)
}

/**
 * 把响应头读数合并进已有快照：按窗口时长对齐，保留 credits、allowed 与重置次数等
 * 轮询才有的事实；没有旧快照时按 primary / secondary 命名落位。
 */
fun OAuthQuotaSnapshot?.mergeQuotaHeaders(
    reading: OAuthQuotaHeaderReading,
    nowMs: Long,
): OAuthQuotaSnapshot {
    var primary = this?.primary
    var secondary = this?.secondary
    reading.primary?.let { incoming ->
        if (secondary != null && secondary?.windowSeconds == incoming.windowSeconds) {
            secondary = incoming
        } else {
            primary = incoming
        }
    }
    reading.secondary?.let { incoming ->
        if (primary != null && primary?.windowSeconds == incoming.windowSeconds) {
            primary = incoming
        } else {
            secondary = incoming
        }
    }
    return OAuthQuotaSnapshot(
        primary = primary,
        secondary = secondary,
        allowed = this?.allowed,
        creditsBalance = this?.creditsBalance,
        creditsUnlimited = this?.creditsUnlimited ?: false,
        resetCredits = this?.resetCredits,
        checkedAt = nowMs,
        // 新的读数已经到达，旧的读取错误不再成立。
        error = null,
    )
}
