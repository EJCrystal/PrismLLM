package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ChannelKeyHealth
import dev.soven.llmgateway.db.ChannelKeys
import dev.soven.llmgateway.db.Channels
import dev.soven.llmgateway.db.KeyAutoDisableSettings
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.KeyAttemptOutcome
import dev.soven.llmgateway.resilience.KeyAttemptSignal
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
import dev.soven.llmgateway.resilience.KeyAutoDisableSettingsRecord
import dev.soven.llmgateway.resilience.KeyDisableAction
import dev.soven.llmgateway.resilience.KeyDisableDecision
import dev.soven.llmgateway.resilience.KeyDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableRule
import dev.soven.llmgateway.resilience.KeyHealthState
import dev.soven.llmgateway.resilience.KeyRuleCounter
import dev.soven.llmgateway.resilience.toKeyDisablePolicy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

private val keyDisableJson = Json { ignoreUnknownKeys = true }

data class KeyAutoDisableSettingsChange(
    val before: KeyAutoDisableSettingsRecord,
    val after: KeyAutoDisableSettingsRecord,
)

data class KeyAutoDisableSettingsAuditContext(
    val actor: String?,
    val requestId: String,
    val sourceIp: String?,
)

/** 后台清理恢复的密钥；调用方据此清理内存冷却。 */
data class RecoveredKey(val channelId: String, val keyId: String)

/** 事务已算出需要停用，但调用方尚未取得密钥写锁；回滚计数后由写锁路径重放。 */
class KeyAutoDisablePendingException : RuntimeException(null, null, false, false)

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

    suspend fun saveSettings(
        policy: KeyDisablePolicy,
        auditContext: KeyAutoDisableSettingsAuditContext? = null,
    ): KeyAutoDisableSettingsChange = suspendTransaction(database) {
        ensureSettingsRow()
        val row = KeyAutoDisableSettings.selectAll()
            .where { KeyAutoDisableSettings.id eq SINGLETON_ID }.forUpdate().single()
        val before = migrateLegacyDefaultRules(row)
        if (before.policy == policy) return@suspendTransaction KeyAutoDisableSettingsChange(before, before)
        val after = KeyAutoDisableSettingsRecord(policy, before.revision + 1, nowMs())
        KeyAutoDisableSettings.update({ KeyAutoDisableSettings.id eq SINGLETON_ID }) {
            it[enabled] = policy.enabled
            it[rules] = keyDisableJson.encodeToString(policy.rules)
            // 旧字段保留给尚未升级的管理台读取，实际判定只使用 rules。
            it[authFailureThreshold] = policy.legacyAuthThreshold().coerceIn(1, 100)
            it[failureThreshold] = policy.legacyFailureThreshold()
            it[failureWindowMinutes] = policy.legacyFailureWindowMinutes()
            it[revision] = after.revision
            it[updatedAt] = after.updatedAt
        }
        auditContext?.let { context ->
            audit.recordInTransaction(
                actor = context.actor,
                action = "key_auto_disable_settings.update",
                method = "PUT",
                path = "/admin/api/key-auto-disable-settings",
                statusCode = 200,
                requestId = context.requestId,
                sourceIp = context.sourceIp,
                errorCode = null,
                details = buildJsonObject {
                    put("before", settingsAuditJson(before))
                    put("after", settingsAuditJson(after))
                },
            )
        }
        KeyAutoDisableSettingsChange(before, after)
    }

    /** 兼容旧管理 API 与旧测试：两个标量门槛翻译成两条规则后保存。 */
    suspend fun saveSettings(
        policy: KeyAutoDisablePolicy,
        auditContext: KeyAutoDisableSettingsAuditContext? = null,
    ): KeyAutoDisableSettingsChange = saveSettings(policy.toKeyDisablePolicy(), auditContext)

    /**
     * fail-open 只绕过冷却，不能绕过停用；每次实际发送前重新检查预加载的候选。
     * 临时停用到期即视为可用，不依赖后台清理任务；人工与永久停用永不放行。
     */
    suspend fun isAvailable(key: ChannelKeyRecord, now: Long = nowMs()): Boolean = suspendTransaction(database) {
        val row = ChannelKeys.select(ChannelKeys.state, ChannelKeys.autoDisableUntil, ChannelKeys.probeUntil)
            .where {
                (ChannelKeys.id eq key.id) and (ChannelKeys.stateVersion eq key.stateVersion)
            }
            .singleOrNull() ?: return@suspendTransaction false
        when (ChannelKeyState.fromValue(row[ChannelKeys.state])) {
            ChannelKeyState.ACTIVE -> true
            ChannelKeyState.AUTO_TEMPORARY ->
                row[ChannelKeys.autoDisableUntil]?.let { it <= now } == true &&
                    (row[ChannelKeys.probeUntil]?.let { it <= now } ?: true)
            ChannelKeyState.MANUAL_DISABLED, ChannelKeyState.AUTO_PERMANENT, null -> false
        }
    }

    /**
     * 原子抢占一次到期探测。只有临时停用、已到期且没有有效探测租约的密钥能抢到；
     * 同一密钥的并发请求只有一个会返回 true，其余应立即换候选。
     */
    suspend fun claimProbe(
        key: ChannelKeyRecord,
        leaseMs: Long = PROBE_LEASE_MS,
        now: Long = nowMs(),
    ): Boolean = suspendTransaction(database) {
        ChannelKeys.update({
            (ChannelKeys.id eq key.id) and
                (ChannelKeys.stateVersion eq key.stateVersion) and
                (ChannelKeys.state eq ChannelKeyState.AUTO_TEMPORARY.value) and
                (ChannelKeys.autoDisableUntil lessEq now) and
                ((ChannelKeys.probeUntil.isNull()) or (ChannelKeys.probeUntil lessEq now))
        }) {
            it[probeUntil] = now + leaseMs
        } == 1
    }

    /** 请求转换失败等未产生上游结果的情况：只释放探测租约，不改变停用状态。 */
    suspend fun releaseProbe(key: ChannelKeyRecord): Boolean = suspendTransaction(database) {
        ChannelKeys.update({
            (ChannelKeys.id eq key.id) and
                (ChannelKeys.stateVersion eq key.stateVersion) and
                (ChannelKeys.state eq ChannelKeyState.AUTO_TEMPORARY.value)
        }) {
            it[probeUntil] = null
        } == 1
    }

    /**
     * 后台收尾：把已到期且没有探测租约的临时停用写回 ACTIVE，并清理健康计数。
     * 返回恢复的密钥，供调用方清理内存冷却。
     */
    suspend fun recoverExpired(now: Long = nowMs()): List<RecoveredKey> = suspendTransaction(database) {
        val rows = ChannelKeys.selectAll()
            .where {
                (ChannelKeys.state eq ChannelKeyState.AUTO_TEMPORARY.value) and
                    (ChannelKeys.autoDisableUntil lessEq now) and
                    ((ChannelKeys.probeUntil.isNull()) or (ChannelKeys.probeUntil lessEq now))
            }
            .orderBy(ChannelKeys.id)
            .forUpdate()
            .toList()
        rows.mapNotNull { row ->
            val keyId = row[ChannelKeys.id]
            val updated = ChannelKeys.update({
                (ChannelKeys.id eq keyId) and
                    (ChannelKeys.stateVersion eq row[ChannelKeys.stateVersion]) and
                    (ChannelKeys.state eq ChannelKeyState.AUTO_TEMPORARY.value)
            }) {
                it[state] = ChannelKeyState.ACTIVE.value
                it[stateVersion] = row[ChannelKeys.stateVersion] + 1
                it[autoDisabledAt] = null
                it[autoDisableReason] = null
                it[autoDisableUntil] = null
                it[probeUntil] = null
                it[updatedAt] = now
            }
            if (updated == 0) return@mapNotNull null
            ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq keyId }
            auditRecovery(
                channelId = row[ChannelKeys.channelId],
                keyId = keyId,
                keyName = row[ChannelKeys.name],
                previousState = row[ChannelKeys.state],
                requestId = "cleanup-$now",
                reason = "expired",
            )
            RecoveredKey(row[ChannelKeys.channelId], keyId)
        }
    }

    /**
     * 管理员替换密钥凭据后重置健康计数。只清计数，不改启停状态：
     * 自动停用的密钥仍需管理员显式恢复，人工停用的密钥不应被重新授权悄悄启用。
     */
    suspend fun resetKeyHealth(keyId: String): Boolean = suspendTransaction(database) {
        ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq keyId } > 0
    }

    suspend fun recordOutcome(
        key: ChannelKeyRecord,
        signal: KeyAttemptSignal,
        requestId: String,
        allowDisable: Boolean = true,
        probeClaimed: Boolean = false,
    ): String? = suspendTransaction(database) {
        val row = ChannelKeys.selectAll().where { ChannelKeys.id eq key.id }.forUpdate().singleOrNull()
            ?: return@suspendTransaction null
        val currentState = ChannelKeyState.fromValue(row[ChannelKeys.state])
            ?: return@suspendTransaction null
        val now = nowMs()
        val probeLeaseActive = row[ChannelKeys.probeUntil]?.let { it > now } == true
        // 只有正常密钥和已到期的临时停用密钥可以继续记账；后者承担探测角色。
        val routable = currentState == ChannelKeyState.ACTIVE ||
            (currentState == ChannelKeyState.AUTO_TEMPORARY &&
                row[ChannelKeys.autoDisableUntil]?.let { it <= now } == true &&
                (!probeLeaseActive || probeClaimed))
        if (!routable || row[ChannelKeys.stateVersion] != key.stateVersion) return@suspendTransaction null

        val settings = settingsInTransaction()
        if (!settings.policy.enabled) {
            ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq key.id }
            return@suspendTransaction null
        }

        // 到期后的首次成功即完成恢复：回到 ACTIVE、清空自动停用元数据与计数。
        if (signal == KeyAttemptSignal.Success && currentState == ChannelKeyState.AUTO_TEMPORARY) {
            ChannelKeys.update({ ChannelKeys.id eq key.id }) {
                it[state] = ChannelKeyState.ACTIVE.value
                it[stateVersion] = row[ChannelKeys.stateVersion] + 1
                it[autoDisabledAt] = null
                it[autoDisableReason] = null
                it[autoDisableUntil] = null
                it[probeUntil] = null
                it[updatedAt] = now
            }
            ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq key.id }
            auditRecovery(
                channelId = key.channelId,
                keyId = key.id,
                keyName = row[ChannelKeys.name],
                previousState = row[ChannelKeys.state],
                requestId = requestId,
                reason = "probe_success",
            )
            return@suspendTransaction null
        }

        // 探测失败不等待常规阈值：命中哪条规则就按该规则的动作重新计时/永久停用。
        // 未命中规则的响应（如 400）视为探测不确定，只释放租约，让下一次请求再试。
        if (probeClaimed) {
            val rule = settings.policy.rules.firstOrNull { it.match(signal) != null }
            if (rule == null) {
                ChannelKeys.update({ ChannelKeys.id eq key.id }) { it[probeUntil] = null }
                return@suspendTransaction null
            }
            if (!allowDisable) throw KeyAutoDisablePendingException()
            val hit = rule.match(signal)
            val targetState = when (rule.action) {
                KeyDisableAction.TEMPORARY -> ChannelKeyState.AUTO_TEMPORARY
                KeyDisableAction.PERMANENT -> ChannelKeyState.AUTO_PERMANENT
            }
            val disableUntil = rule.disableMinutes?.let { now + it * 60_000L }
            val decision = KeyDisableDecision(
                ruleId = rule.id,
                ruleName = rule.name,
                matchedPattern = hit?.matchedPattern,
                count = 1,
                threshold = rule.threshold,
                action = rule.action,
                disableUntil = disableUntil,
            )
            ChannelKeys.update({ ChannelKeys.id eq key.id }) {
                it[state] = targetState.value
                it[stateVersion] = row[ChannelKeys.stateVersion] + 1
                it[autoDisabledAt] = now
                it[autoDisableReason] = "${rule.name} 探测失败".take(40)
                it[autoDisableUntil] = disableUntil
                it[probeUntil] = null
                it[updatedAt] = now
            }
            ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq key.id }
            auditDisable(key, row[ChannelKeys.name], targetState, decision, signal, requestId)
            return@suspendTransaction rule.id
        }

        val stored = ChannelKeyHealth.selectAll().where { ChannelKeyHealth.keyId eq key.id }.singleOrNull()
        val health = if (stored != null && stored[ChannelKeyHealth.policyRevision] == settings.revision) {
            decodeHealth(stored[ChannelKeyHealth.ruleCounters])
        } else KeyHealthState()
        val result = settings.policy.record(health, signal, now)
        val decision = result.decision
        if (decision == null) {
            if (stored == null && result.state.counters.isEmpty()) return@suspendTransaction null
            ChannelKeyHealth.upsert(ChannelKeyHealth.keyId) {
                it[keyId] = key.id
                it[policyRevision] = settings.revision
                it[authFailureStreak] = 0
                it[recentFailures] = "[]"
                it[ruleCounters] = keyDisableJson.encodeToString(result.state)
            }
            return@suspendTransaction null
        }

        if (!allowDisable) throw KeyAutoDisablePendingException()
        val targetState = when (decision.action) {
            KeyDisableAction.TEMPORARY -> ChannelKeyState.AUTO_TEMPORARY
            KeyDisableAction.PERMANENT -> ChannelKeyState.AUTO_PERMANENT
        }
        val message = "${decision.ruleName} ${decision.count} 次".take(40)
        ChannelKeys.update({ ChannelKeys.id eq key.id }) {
            it[state] = targetState.value
            it[stateVersion] = row[ChannelKeys.stateVersion] + 1
            it[autoDisabledAt] = now
            it[autoDisableReason] = message
            it[autoDisableUntil] = decision.disableUntil
            it[probeUntil] = null
            it[updatedAt] = now
        }
        // 停用后重新计数：到期探测或人工恢复都从空计数开始。
        ChannelKeyHealth.deleteWhere { ChannelKeyHealth.keyId eq key.id }
        auditDisable(key, row[ChannelKeys.name], targetState, decision, signal, requestId)
        decision.ruleId
    }

    /** 兼容旧调用方：把旧的分类结果映射成规则信号。 */
    suspend fun recordOutcome(
        key: ChannelKeyRecord,
        outcome: KeyAttemptOutcome,
        requestId: String,
        httpStatus: Int? = null,
        errorCode: String? = null,
        allowDisable: Boolean = true,
        probeClaimed: Boolean = false,
    ): String? = recordOutcome(key, outcome.toSignal(httpStatus, errorCode), requestId, allowDisable, probeClaimed)

    private fun auditDisable(
        key: ChannelKeyRecord,
        keyName: String,
        targetState: ChannelKeyState,
        decision: dev.soven.llmgateway.resilience.KeyDisableDecision,
        signal: KeyAttemptSignal,
        requestId: String,
    ) {
        val channelName = Channels.select(Channels.name).where { Channels.id eq key.channelId }
            .single()[Channels.name]
        val httpStatus = (signal as? KeyAttemptSignal.UpstreamResponse)?.statusCode
        val upstreamCode = (signal as? KeyAttemptSignal.UpstreamResponse)?.upstreamCode
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
                put("keyName", keyName)
                put("reason", decision.ruleId)
                put("message", "${decision.ruleName} ${decision.count} 次")
                put("ruleId", decision.ruleId)
                put("ruleName", decision.ruleName)
                put("matchedPattern", decision.matchedPattern)
                put("count", decision.count)
                put("threshold", decision.threshold)
                put("action", decision.action.name)
                put("state", targetState.value)
                decision.disableUntil?.let { put("disableUntil", it) }
                httpStatus?.let { put("httpStatus", it) }
                upstreamCode?.let { put("upstreamErrorCode", it) }
            },
        )
    }

    private fun auditRecovery(
        channelId: String,
        keyId: String,
        keyName: String,
        previousState: String,
        requestId: String,
        reason: String,
    ) {
        val channelName = Channels.select(Channels.name).where { Channels.id eq channelId }
            .single()[Channels.name]
        audit.recordInTransaction(
            actor = "system",
            action = "channel_key.auto_recover",
            method = "AUTO",
            path = "/admin/api/channels/$channelId/keys/$keyId",
            statusCode = 200,
            requestId = requestId.take(64),
            sourceIp = null,
            errorCode = null,
            details = buildJsonObject {
                put("channelId", channelId)
                put("channelName", channelName)
                put("keyId", keyId)
                put("keyName", keyName)
                put("previousState", previousState)
                put("state", ChannelKeyState.ACTIVE.value)
                put("reason", reason)
            },
        )
    }

    private fun ensureSettingsRow() {
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
    }

    private fun settingsInTransaction(): KeyAutoDisableSettingsRecord {
        val row = KeyAutoDisableSettings.selectAll()
            .where { KeyAutoDisableSettings.id eq SINGLETON_ID }.singleOrNull()
            ?: return KeyAutoDisableSettingsRecord()
        val storedRules = decodeStoredRules(row[KeyAutoDisableSettings.rules])
        if (storedRules != KeyDisablePolicy.LEGACY_DEFAULT_RULES) {
            return settingsRecord(row, storedRules)
        }
        val lockedRow = KeyAutoDisableSettings.selectAll()
            .where { KeyAutoDisableSettings.id eq SINGLETON_ID }.forUpdate().singleOrNull()
            ?: return KeyAutoDisableSettingsRecord()
        return migrateLegacyDefaultRules(lockedRow)
    }

    private fun migrateLegacyDefaultRules(
        row: ResultRow,
        storedRules: List<KeyDisableRule>? = decodeStoredRules(row[KeyAutoDisableSettings.rules]),
    ): KeyAutoDisableSettingsRecord {
        if (storedRules != KeyDisablePolicy.LEGACY_DEFAULT_RULES) {
            return settingsRecord(row, storedRules)
        }
        val revision = row[KeyAutoDisableSettings.revision] + 1
        val updatedAt = nowMs()
        KeyAutoDisableSettings.update({ KeyAutoDisableSettings.id eq SINGLETON_ID }) {
            it[rules] = keyDisableJson.encodeToString(KeyDisablePolicy.DEFAULT_RULES)
            it[KeyAutoDisableSettings.revision] = revision
            it[KeyAutoDisableSettings.updatedAt] = updatedAt
        }
        return KeyAutoDisableSettingsRecord(
            policy = KeyDisablePolicy(
                enabled = row[KeyAutoDisableSettings.enabled],
                rules = KeyDisablePolicy.DEFAULT_RULES,
            ),
            revision = revision,
            updatedAt = updatedAt,
        )
    }

    private fun settingsRecord(
        row: ResultRow,
        storedRules: List<KeyDisableRule>? = decodeStoredRules(row[KeyAutoDisableSettings.rules]),
    ) = KeyAutoDisableSettingsRecord(
        policy = KeyDisablePolicy(
            enabled = row[KeyAutoDisableSettings.enabled],
            rules = storedRules?.takeIf { it.isNotEmpty() }
                ?.let { if (it == KeyDisablePolicy.LEGACY_DEFAULT_RULES) KeyDisablePolicy.DEFAULT_RULES else it }
                ?: KeyDisablePolicy.DEFAULT_RULES,
        ),
        revision = row[KeyAutoDisableSettings.revision],
        updatedAt = row[KeyAutoDisableSettings.updatedAt],
    )

    private fun settingsAuditJson(settings: KeyAutoDisableSettingsRecord) = buildJsonObject {
        put("enabled", settings.policy.enabled)
        put("rules", keyDisableJson.encodeToJsonElement(ListSerializer(KeyDisableRule.serializer()), settings.policy.rules))
        put("updatedAt", settings.updatedAt)
    }

    private fun decodeStoredRules(value: String?): List<KeyDisableRule>? = value
        ?.let { runCatching { keyDisableJson.decodeFromString<List<KeyDisableRule>>(it) }.getOrNull() }

    private fun decodeHealth(value: String?): KeyHealthState = value
        ?.let { runCatching { keyDisableJson.decodeFromString<KeyHealthState>(it) }.getOrNull() }
        ?: KeyHealthState()

    companion object {
        private const val SINGLETON_ID = 1

        /** 探测租约时长；覆盖一次上游尝试的常见超时，同时避免取消后长期占用。 */
        const val PROBE_LEASE_MS = 60_000L
    }
}

private fun KeyAttemptOutcome.toSignal(httpStatus: Int?, errorCode: String?): KeyAttemptSignal = when (this) {
    KeyAttemptOutcome.SUCCESS -> KeyAttemptSignal.Success
    KeyAttemptOutcome.AUTH_FAILURE -> KeyAttemptSignal.UpstreamResponse(httpStatus ?: 401, "", errorCode)
    KeyAttemptOutcome.FAILURE -> httpStatus
        ?.let { KeyAttemptSignal.UpstreamResponse(it, "", errorCode) }
        ?: KeyAttemptSignal.TransportFailure(errorCode.orEmpty())
    KeyAttemptOutcome.OTHER_RESPONSE -> KeyAttemptSignal.UpstreamResponse(httpStatus ?: 400, "", errorCode)
}

private fun KeyDisablePolicy.legacyAuthThreshold(): Int =
    rules.firstOrNull { rule -> rule.id != "insufficient_balance" && 401 in rule.statusCodes && rule.streak }
        ?.threshold ?: 3

private fun KeyDisablePolicy.legacyFailureThreshold(): Int =
    rules.firstOrNull { rule -> !rule.streak }?.threshold ?: 5

private fun KeyDisablePolicy.legacyFailureWindowMinutes(): Int =
    rules.firstOrNull { rule -> !rule.streak }?.windowMinutes ?: 5
