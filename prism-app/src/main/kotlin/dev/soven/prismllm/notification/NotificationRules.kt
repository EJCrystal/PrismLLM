package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationAlertState
import dev.soven.prismllm.admin.domain.NotificationAlertUpdate
import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationEvents
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.KeyStateChange
import dev.soven.prismllm.requestlog.domain.AttemptOutcome
import dev.soven.prismllm.requestlog.domain.ChannelHealthSummary
import dev.soven.prismllm.requestlog.domain.ChannelHealthThresholds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.UUID

internal data class NotificationAttempt(
    val channelId: String,
    val channelName: String?,
    val credentialId: String,
    val credentialName: String?,
    val upstreamModel: String,
    val outcome: AttemptOutcome,
    val occurredAt: Long,
) {
    val subject: String
        get() {
            val identity = listOf(channelId, credentialId, upstreamModel).joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
}

internal fun streakTransition(
    previous: NotificationAlertState?,
    attempt: NotificationAttempt,
    threshold: Int,
): NotificationAlertUpdate {
    val before = previous ?: NotificationAlertState(attempt.subject, "streak")
    if (attempt.outcome == AttemptOutcome.EXCLUDED) return NotificationAlertUpdate(before)
    val failed = attempt.outcome == AttemptOutcome.FAILURE
    val count = if (failed) (before.consecutiveFailures + 1).coerceAtMost(1_000_000) else 0
    val alert = failed && count >= threshold && !before.active
    val recovered = !failed && before.active
    val after = before.copy(
        active = if (failed) before.active || alert else false,
        consecutiveFailures = count,
        state = if (failed) "FAILING" else "HEALTHY",
        updatedAt = attempt.occurredAt,
    )
    val event = if (alert || recovered) NotificationEvent(
        eventId = UUID.randomUUID().toString(),
        eventType = if (alert) NotificationEvents.CONSECUTIVE_FAILURES else NotificationEvents.CONSECUTIVE_FAILURES_RECOVERED,
        occurredAt = attempt.occurredAt,
        severity = if (alert) "warning" else "info",
        channelId = attempt.channelId,
        channelName = attempt.channelName,
        credentialId = attempt.credentialId,
        credentialName = attempt.credentialName,
        upstreamModel = attempt.upstreamModel,
        previousState = before.state,
        state = after.state,
        reasonCode = if (alert) "consecutive_failures" else "successful_attempt",
        consecutiveFailures = count,
    ) else null
    return NotificationAlertUpdate(after, event)
}

internal fun healthTransition(
    previous: NotificationAlertState?,
    health: ChannelHealthSummary,
    channelName: String?,
    now: Long,
): NotificationAlertUpdate {
    val before = previous ?: NotificationAlertState(health.channelId, "health")
    if (health.state == "UNKNOWN" ||
        (health.state in setOf("HEALTHY", "WARNING", "CRITICAL") && health.metrics.attempts < ChannelHealthThresholds.MINIMUM_SAMPLES)
    ) return NotificationAlertUpdate(before.copy(updatedAt = now))
    if (health.state == "DISABLED") return NotificationAlertUpdate(before.copy(active = false, state = "DISABLED", updatedAt = now))
    fun rank(state: String?): Int = when (state) {
        "WARNING" -> 1
        "CRITICAL" -> 2
        "NO_CREDENTIALS" -> 3
        else -> 0
    }
    val stateRank = rank(health.state)
    val alert = stateRank > 0 && (!before.active || stateRank > rank(before.state))
    val recovered = health.state == "HEALTHY" && before.active
    // 持续异常时保留已通知的最高严重程度，避免临界值上下波动反复升级告警。
    val state = if (before.active && stateRank > 0 && !alert) before.state else health.state
    val after = before.copy(active = stateRank > 0, state = state, updatedAt = now)
    val event = if (alert || recovered) NotificationEvent(
        eventId = UUID.randomUUID().toString(),
        eventType = if (alert) NotificationEvents.CHANNEL_UNHEALTHY else NotificationEvents.CHANNEL_RECOVERED,
        occurredAt = now,
        severity = if (recovered) "info" else if (stateRank > 1) "critical" else "warning",
        channelId = health.channelId,
        channelName = channelName,
        previousState = before.state,
        state = health.state,
        reasonCode = when {
            recovered -> "healthy"
            health.state == "NO_CREDENTIALS" -> "no_credentials"
            health.metrics.attempts > 0 && health.metrics.failures.toDouble() / health.metrics.attempts >= ChannelHealthThresholds.WARNING_ERROR_RATE -> "failure_rate"
            else -> "slow_first_token"
        },
        metrics = Json.encodeToJsonElement(health.metrics).jsonObject,
    ) else null
    return NotificationAlertUpdate(after, event)
}

internal fun KeyStateChange.notificationEvent(): NotificationEvent = NotificationEvent(
    eventId = UUID.randomUUID().toString(),
    eventType = if (state == ChannelKeyState.ACTIVE) NotificationEvents.KEY_RECOVERED else NotificationEvents.KEY_DISABLED,
    occurredAt = occurredAt,
    severity = if (state == ChannelKeyState.ACTIVE) "info" else "warning",
    channelId = channelId,
    channelName = channelName,
    credentialId = keyId,
    credentialName = keyName,
    previousState = previousState.value,
    state = state.value,
    reasonCode = reasonCode,
)
