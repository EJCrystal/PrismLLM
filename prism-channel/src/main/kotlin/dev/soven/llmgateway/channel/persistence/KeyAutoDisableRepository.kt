package dev.soven.llmgateway.channel.persistence

import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import dev.soven.llmgateway.channel.domain.key.ChannelKeyDisableMode
import dev.soven.llmgateway.channel.domain.key.ChannelKeyDisablePolicyCodec
import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.channel.domain.key.KeyAttemptOutcome
import dev.soven.llmgateway.channel.domain.key.KeyAttemptSignal
import dev.soven.llmgateway.channel.domain.key.KeyAutoDisablePolicy
import dev.soven.llmgateway.channel.domain.key.KeyAutoDisableSettingsRecord
import dev.soven.llmgateway.channel.domain.key.KeyDisableAction
import dev.soven.llmgateway.channel.domain.key.KeyDisableDecision
import dev.soven.llmgateway.channel.domain.key.KeyDisablePolicy
import dev.soven.llmgateway.channel.domain.key.KeyDisableRule
import dev.soven.llmgateway.channel.domain.key.KeyHealthState
import dev.soven.llmgateway.channel.domain.key.KeyRuleCounter
import dev.soven.llmgateway.channel.domain.key.ResolvedKeyDisablePolicy
import dev.soven.llmgateway.channel.domain.key.fallbackTo
import dev.soven.llmgateway.channel.domain.key.requireSavable
import dev.soven.llmgateway.channel.domain.key.toKeyDisablePolicy
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
import org.slf4j.LoggerFactory

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

/** 渠道覆盖策略的写入结果；[override] 为 null 表示已恢复为继承全局。 */
data class ChannelKeyDisablePolicyChange(
    val before: ResolvedKeyDisablePolicy,
    val after: ResolvedKeyDisablePolicy,
    val override: KeyDisablePolicy?,
)

/** 事务已算出需要停用，但调用方尚未取得密钥写锁；回滚计数后由写锁路径重放。 */
class KeyAutoDisablePendingException : RuntimeException(null, null, false, false)

/**
 * 对密钥行加锁串行记账，跨请求、模型和网关进程聚合失败。
 * 停用与系统审计在同一事务完成；旧凭据或重新启用前的在途请求不能改写新状态。
 */
class KeyAutoDisableRepository(
    private val database: Database,
    private val audit: KeyStateAudit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(KeyAutoDisableRepository::class.java)

    suspend fun loadSettings(): KeyAutoDisableSettingsRecord = suspendTransaction(database) {
        settingsInTransaction()
    }

    /**
     * 解析渠道实际生效的策略。渠道覆盖只提供自己的规则，全局规则作为未命中时的兜底；
     * 覆盖关闭自动禁用时，本渠道的渠道规则与全局规则都不执行。
     * 返回的指纹决定健康计数归属，有效策略内容变化后旧计数自动作废。
     */
    suspend fun resolvePolicy(channelId: String): ResolvedKeyDisablePolicy = suspendTransaction(database) {
        resolvePolicyInTransaction(channelId)
    }

    /** 渠道覆盖策略的原始内容；null 表示继承全局，渠道不存在时抛出 [IllegalArgumentException]。 */
    suspend fun loadChannelOverride(channelId: String): KeyDisablePolicy? = suspendTransaction(database) {
        val stored = Channels.select(Channels.keyDisablePolicy)
            .where { Channels.id eq channelId }
            .singleOrNull() ?: throw IllegalArgumentException("渠道不存在: $channelId")
        decodeChannelPolicy(stored[Channels.keyDisablePolicy])
    }

    /**
     * 保存渠道覆盖策略；[override] 为 null 时恢复继承全局。
     * 写入同时递增渠道策略版本，让该渠道下按旧策略累计的失败计数失效。
     *
     * 与全局规则同 id 是允许的：渠道版本接管这一条，全局版本不再参与兜底。
     * 这样管理员能在渠道里收紧全局规则的阈值，也不必给 V57 搬迁来的规则副本改名。
     */
    suspend fun saveChannelPolicy(
        channelId: String,
        override: KeyDisablePolicy?,
        auditContext: KeyAutoDisableSettingsAuditContext? = null,
    ): ChannelKeyDisablePolicyChange = suspendTransaction(database) {
        // 单份策略的上限在这里校验：合并后的有效策略可以到两倍，但保存进来的一份不能超。
        override?.requireSavable()
        val row = Channels.select(Channels.id, Channels.keyDisablePolicy, Channels.keyDisablePolicyRevision)
            .where { Channels.id eq channelId }
            .forUpdate()
            .singleOrNull() ?: throw IllegalArgumentException("渠道不存在: $channelId")
        val before = resolvePolicyInTransaction(channelId)
        if (decodeChannelPolicy(row[Channels.keyDisablePolicy]) == override) {
            // 覆盖内容未变化时不递增版本，保持计数有效。
            return@suspendTransaction ChannelKeyDisablePolicyChange(before, before, override)
        }
        val revision = row[Channels.keyDisablePolicyRevision] + 1
        Channels.update({ Channels.id eq channelId }) {
            it[keyDisablePolicy] = override?.let(ChannelKeyDisablePolicyCodec::encode)
            it[keyDisablePolicyRevision] = revision
        }
        val after = resolvePolicyInTransaction(channelId)
        auditContext?.let { context ->
            audit.record(
                actor = context.actor,
                action = "channel.key_disable_policy.update",
                method = "PUT",
                path = "/admin/api/channels/$channelId/key-disable-policy",
                statusCode = 200,
                requestId = context.requestId,
                sourceIp = context.sourceIp,
                errorCode = null,
                details = buildJsonObject {
                    put("channelId", channelId)
                    put("mode", ChannelKeyDisableMode.of(override).value)
                    put("before", keyDisablePolicyAuditJson(before))
                    put("after", keyDisablePolicyAuditJson(after))
                },
            )
        }
        ChannelKeyDisablePolicyChange(before, after, override)
    }

    suspend fun saveSettings(
        policy: KeyDisablePolicy,
        auditContext: KeyAutoDisableSettingsAuditContext? = null,
    ): KeyAutoDisableSettingsChange = suspendTransaction(database) {
        policy.requireSavable()
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
            audit.record(
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

        val resolved = resolvePolicyInTransaction(key.channelId)
        if (!resolved.policy.enabled) {
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
            val rule = resolved.policy.rules.firstOrNull { it.match(signal) != null }
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
        // 只有同一作用域、同一份有效策略累计的计数才可继续使用。渠道覆盖会叠加全局兜底，
        // 单比渠道版本号会漏掉「全局规则改了」的情况，所以这里存的是有效策略指纹。
        val health = if (
            stored != null &&
            stored[ChannelKeyHealth.policyScope] == resolved.scope &&
            stored[ChannelKeyHealth.policyRevision] == resolved.fingerprint
        ) {
            decodeHealth(stored[ChannelKeyHealth.ruleCounters])
        } else {
            KeyHealthState()
        }
        val result = resolved.policy.record(health, signal, now)
        val decision = result.decision
        if (decision == null) {
            if (stored == null && result.state.counters.isEmpty()) return@suspendTransaction null
            ChannelKeyHealth.upsert(ChannelKeyHealth.keyId) {
                it[keyId] = key.id
                it[policyRevision] = resolved.fingerprint
                it[policyScope] = resolved.scope
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
        decision: dev.soven.llmgateway.channel.domain.key.KeyDisableDecision,
        signal: KeyAttemptSignal,
        requestId: String,
    ) {
        val channelName = Channels.select(Channels.name).where { Channels.id eq key.channelId }
            .single()[Channels.name]
        val httpStatus = (signal as? KeyAttemptSignal.UpstreamResponse)?.statusCode
        val upstreamCode = (signal as? KeyAttemptSignal.UpstreamResponse)?.upstreamCode
        audit.record(
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
        audit.record(
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

    /**
     * 渠道覆盖优先、全局兜底：渠道规则排在前面，未命中时继续匹配全局规则；
     * 覆盖显式关闭自动禁用时，渠道规则与全局规则都不执行。
     * 渠道不存在或未配置覆盖时完全使用全局设置；
     * 覆盖策略为空串（例如手工清空列）同样按继承处理，不让坏数据静默停用规则。
     */
    private fun resolvePolicyInTransaction(channelId: String): ResolvedKeyDisablePolicy {
        val row = Channels.select(Channels.keyDisablePolicy, Channels.keyDisablePolicyRevision)
            .where { Channels.id eq channelId }
            .singleOrNull()
        val override = row?.let { decodeChannelPolicy(it[Channels.keyDisablePolicy]) }
        val settings = settingsInTransaction()
        if (row != null && override != null) {
            return ResolvedKeyDisablePolicy(
                scope = ResolvedKeyDisablePolicy.channelScope(channelId),
                revision = row[Channels.keyDisablePolicyRevision],
                policy = override.fallbackTo(settings.policy),
            )
        }
        return ResolvedKeyDisablePolicy(
            scope = ResolvedKeyDisablePolicy.GLOBAL_SCOPE,
            revision = settings.revision,
            policy = settings.policy,
        )
    }

    private fun decodeChannelPolicy(value: String?): KeyDisablePolicy? = ChannelKeyDisablePolicyCodec.decode(value)

    private fun keyDisablePolicyAuditJson(resolved: ResolvedKeyDisablePolicy) = buildJsonObject {
        put("scope", resolved.scope)
        put("revision", resolved.revision)
        put("fingerprint", resolved.fingerprint)
        put("enabled", resolved.policy.enabled)
        put("rules", keyDisableJson.encodeToJsonElement(ListSerializer(KeyDisableRule.serializer()), resolved.policy.rules))
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
            rules = savableOrDefault(storedRules),
        ),
        revision = row[KeyAutoDisableSettings.revision],
        updatedAt = row[KeyAutoDisableSettings.updatedAt],
    )

    /**
     * 库里读出的规则转成可直接构造的列表。超长只可能来自人工改库或手工改过的备份，
     * 正常保存路径写不出超过 [KeyDisablePolicy.MAX_RULES] 的列表。
     * 这里按顺序保留前 [KeyDisablePolicy.MAX_RULES] 条而不是抛异常：规则是「首条命中即止」，
     * 被丢掉的都是优先级最低的兜底规则；抛出去会让管理接口直接 500，连读都读不出来。
     */
    private fun savableOrDefault(storedRules: List<KeyDisableRule>?): List<KeyDisableRule> {
        val rules = storedRules?.takeIf { it.isNotEmpty() } ?: return KeyDisablePolicy.DEFAULT_RULES
        // 旧版默认规则要换成新默认：余额规则必须排在凭据规则之前，顺序不能沿用它。
        if (rules == KeyDisablePolicy.LEGACY_DEFAULT_RULES) return KeyDisablePolicy.DEFAULT_RULES
        if (rules.size <= KeyDisablePolicy.MAX_RULES) return rules
        logger.warn(
            "key_auto_disable_settings 保存了 {} 条规则，超过上限 {}，按顺序保留前 {} 条",
            rules.size, KeyDisablePolicy.MAX_RULES, KeyDisablePolicy.MAX_RULES,
        )
        return rules.take(KeyDisablePolicy.MAX_RULES)
    }

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
