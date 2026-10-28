package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ChannelKeyHealth
import dev.soven.llmgateway.db.ChannelKeys
import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.db.KeyAutoDisableSettings
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.resilience.KeyAttemptOutcome
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
import dev.soven.llmgateway.resilience.KeyAutoDisableSettingsRecord
import dev.soven.llmgateway.resilience.KeyFailureState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

data class KeyAutoDisableSettingsChange(
    val before: KeyAutoDisableSettingsRecord,
    val after: KeyAutoDisableSettingsRecord,
)

/**
 * 对密钥行加锁串行记账，跨请求、模型和网关进程聚合失败。
 * 停用与系统审计在同一事务完成；旧凭据或重新启用前的在途请求不能改写新状态。
 */
class KeyAutoDisableRepository(
    private val database: Database,
    private val audit: AdminAuditRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun loadSettings(): KeyAutoDisableSettingsRecord = suspendTransaction(database) {
        settingsInTransaction()
    }

    suspend fun saveSettings(policy: KeyAutoDisablePolicy): KeyAutoDisableSettingsChange = suspendTransaction(database) {
        // 迁移会创建单例行；测试或手工清空后也允许首次并发保存。
        // 冲突时忽略插入，再锁定同一行，避免两个请求都看到空行后争抢主键。
        KeyAutoDisableSettings.insertIgnore {
            it[id] = SINGLETON_ID
            it[enabled] = true
            it[authFailureThreshold] = 3
            it[failureThreshold] = 5
            it[failureWindowMinutes] = 5
            it[revision] = 0
            it[updatedAt] = nowMs()
        }
        val row = KeyAutoDisableSettings.selectAll()
            .where { KeyAutoDisableSettings.id eq SINGLETON_ID }.forUpdate().single()
        val before = settingsRecord(row)
        if (before.policy == policy) return@suspendTransaction KeyAutoDisableSettingsChange(before, before)
        val after = KeyAutoDisableSettingsRecord(policy, before.revision + 1, nowMs())
        KeyAutoDisableSettings.update({ KeyAutoDisableSettings.id eq SINGLETON_ID }) {
            it[enabled] = policy.enabled
            it[authFailureThreshold] = policy.authFailureThreshold
            it[failureThreshold] = policy.failureThreshold
            it[failureWindowMinutes] = policy.failureWindowMinutes
            it[revision] = after.revision
            it[updatedAt] = after.updatedAt
        }
        KeyAutoDisableSettingsChange(before, after)
    }

    /** fail-open 只绕过冷却，不能绕过停用；每次实际发送前重新检查预加载的候选。 */
    suspend fun isAvailable(key: ChannelKeyRecord): Boolean = suspendTransaction(database) {
        ChannelKeys.select(ChannelKeys.id).where {
            (ChannelKeys.id eq key.id) and (ChannelKeys.enabled eq true) and
                (ChannelKeys.stateVersion eq key.stateVersion)
        }.empty().not()
    }

    suspend fun recordOutcome(
        key: ChannelKeyRecord,
        outcome: KeyAttemptOutcome,
        requestId: String,
        httpStatus: Int? = null,
        errorCode: String? = null,
    ): String? = suspendTransaction(database) {
        val row = ChannelKeys.selectAll().where { ChannelKeys.id eq key.id }.forUpdate().singleOrNull()
            ?: return@suspendTransaction null
        if (!row[ChannelKeys.enabled] || row[ChannelKeys.stateVersion] != key.stateVersion) {
            return@suspendTransaction null
        }
        val settings = settingsInTransaction()
        if (!settings.policy.enabled) {
            ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq key.id }
            return@suspendTransaction null
        }
        val stored = ChannelKeyHealth.selectAll().where { ChannelKeyHealth.keyId eq key.id }.singleOrNull()
        val state = if (stored != null && stored[ChannelKeyHealth.policyRevision] == settings.revision) {
            KeyFailureState(
                stored[ChannelKeyHealth.authFailureStreak],
                Json.parseToJsonElement(stored[ChannelKeyHealth.recentFailures]).jsonArray.map { it.jsonPrimitive.long },
            )
        } else KeyFailureState()
        val now = nowMs()
        val result = settings.policy.record(state, outcome, now)
        // 健康密钥没有失败历史时不创建统计行。
        if (stored == null && result.state == KeyFailureState()) return@suspendTransaction null
        ChannelKeyHealth.upsert(ChannelKeyHealth.keyId) {
            it[keyId] = key.id
            it[policyRevision] = settings.revision
            it[authFailureStreak] = result.state.authFailureStreak
            it[recentFailures] = result.state.recentFailures.joinToString(prefix = "[", postfix = "]")
        }
        val reason = result.reason ?: return@suspendTransaction null
        val message = when (reason) {
            KeyAutoDisablePolicy.REASON_AUTH_STREAK -> "连续 ${result.state.authFailureStreak} 次 401/403"
            else -> "${settings.policy.failureWindowMinutes} 分钟内失败 ${result.state.recentFailures.size} 次"
        }
        ChannelKeys.update({ ChannelKeys.id eq key.id }) {
            it[enabled] = false
            it[stateVersion] = row[ChannelKeys.stateVersion] + 1
            it[autoDisabledAt] = now
            it[autoDisableReason] = message
            it[updatedAt] = now
        }
        val channelName = Channels.select(Channels.name).where { Channels.id eq key.channelId }
            .single()[Channels.name]
        audit.recordInTransaction(
            actor = "system",
            action = "channel_key.auto_disable",
            method = "AUTO",
            path = "/admin/api/channels/${key.channelId}/keys/${key.id}",
            statusCode = 200,
            requestId = requestId.take(64),
            sourceIp = null,
            errorCode = null,
            details = buildJsonObject {
                put("channelId", key.channelId)
                put("channelName", channelName)
                put("keyId", key.id)
                put("keyName", row[ChannelKeys.name])
                put("reason", reason)
                put("message", message)
                put("authFailureStreak", result.state.authFailureStreak)
                put("failureCount", result.state.recentFailures.size)
                put("authFailureThreshold", settings.policy.authFailureThreshold)
                put("failureThreshold", settings.policy.failureThreshold)
                put("failureWindowMinutes", settings.policy.failureWindowMinutes)
                httpStatus?.let { put("httpStatus", it) }
                errorCode?.let { put("errorCode", it) }
            },
        )
        reason
    }

    private fun settingsInTransaction(): KeyAutoDisableSettingsRecord = KeyAutoDisableSettings.selectAll()
        .where { KeyAutoDisableSettings.id eq SINGLETON_ID }.singleOrNull()?.let(::settingsRecord)
        ?: KeyAutoDisableSettingsRecord()

    private fun settingsRecord(row: ResultRow) = KeyAutoDisableSettingsRecord(
        policy = KeyAutoDisablePolicy(
            enabled = row[KeyAutoDisableSettings.enabled],
            authFailureThreshold = row[KeyAutoDisableSettings.authFailureThreshold],
            failureThreshold = row[KeyAutoDisableSettings.failureThreshold],
            failureWindowMinutes = row[KeyAutoDisableSettings.failureWindowMinutes],
        ),
        revision = row[KeyAutoDisableSettings.revision],
        updatedAt = row[KeyAutoDisableSettings.updatedAt],
    )

    private companion object {
        const val SINGLETON_ID = 1
    }
}
