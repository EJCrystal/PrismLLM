package dev.soven.prismllm.oauth.domain

import dev.soven.prismllm.shared.domain.saturatedAdd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** `/wham/usage` 解析后的额度。 */
data class CodexUsage(
    val planType: String? = null,
    val allowed: Boolean? = null,
    val primary: OAuthQuotaWindow? = null,
    val secondary: OAuthQuotaWindow? = null,
    val creditsBalance: Double? = null,
    val creditsUnlimited: Boolean = false,
    val resetCredits: Int? = null,
)

/**
 * 解析 ChatGPT 的额度响应。字段名与 Codex 客户端一致：
 * `rate_limit.primary_window` / `secondary_window`、`credits`、`rate_limit_reset_credits`。
 * 响应不是 JSON 对象时返回 null。
 */
fun parseChatGptUsage(body: String, now: Long): CodexUsage? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val rateLimit = root["rate_limit"] as? JsonObject
    val credits = root["credits"] as? JsonObject
    val balance = (credits?.get("balance") as? JsonPrimitive)?.let { primitive ->
        primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
    }
    val resetCredits = ((root["rate_limit_reset_credits"] as? JsonObject)?.get("available_count") as? JsonPrimitive)
        ?.intOrNull
        ?.takeIf { it >= 0 }
    return CodexUsage(
        planType = (root["plan_type"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        allowed = (rateLimit?.get("allowed") as? JsonPrimitive)?.booleanOrNull,
        primary = parseQuotaWindow(rateLimit?.get("primary_window") as? JsonObject, now),
        secondary = parseQuotaWindow(rateLimit?.get("secondary_window") as? JsonObject, now),
        creditsBalance = balance?.takeIf { it >= 0 },
        creditsUnlimited = (credits?.get("unlimited") as? JsonPrimitive)?.booleanOrNull == true,
        resetCredits = resetCredits,
    )
}

private fun parseQuotaWindow(window: JsonObject?, now: Long): OAuthQuotaWindow? {
    if (window == null) return null
    val used = (window["used_percent"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val seconds = (window["limit_window_seconds"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 1..MAX_WINDOW_SECONDS }
        ?.toInt()
    val resetAt = (window["reset_at"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 1..MAX_EPOCH_SECONDS }
        ?.let { it * 1_000 }
    val resetAfter = (window["reset_after_seconds"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 1..MAX_WINDOW_SECONDS }
        ?.let { saturatedAdd(now, it * 1_000) }
    return OAuthQuotaWindow(
        usedPercent = used.coerceIn(0.0, 100.0),
        windowSeconds = seconds,
        resetsAt = resetAt ?: resetAfter,
    )
}

/** 一年，避免上游返回异常大的窗口时长。 */
internal const val MAX_WINDOW_SECONDS = 31_536_000L

/** 2100 年，避免 reset_at 溢出或误把毫秒当秒。 */
internal const val MAX_EPOCH_SECONDS = 4_102_444_800L