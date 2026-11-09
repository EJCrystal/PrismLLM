package dev.soven.llmgateway.repo

import dev.soven.llmgateway.channel.persistence.ChannelRepository

import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.channel.persistence.ChannelKeyModels
import dev.soven.llmgateway.channel.persistence.ChannelKeys
import dev.soven.llmgateway.channel.persistence.ChannelModels
import dev.soven.llmgateway.channel.persistence.Channels
import dev.soven.llmgateway.db.OAuthAccountChannels
import dev.soven.llmgateway.db.OAuthAccountModels
import dev.soven.llmgateway.db.OAuthAccounts
import dev.soven.llmgateway.requestlog.domain.*
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.ResultSet

/** 健康度列表只需要渠道身份、启用状态和密钥支持的模型，不解析完整渠道记录。 */
internal data class HealthChannelKey(val id: String, val models: List<String>, val routable: Boolean)

internal data class HealthChannelOverview(
    val id: String,
    val enabled: Boolean,
    val keys: List<HealthChannelKey>,
    /** 支持该渠道任一授权模型的可路由凭据总数（静态密钥 + 订阅账号）。 */
    val routableCredentials: Int = 0,
)

/** Read-only health observations. Time windows use completion time and [from, to). */
class ChannelHealthRepository(private val database: Database, private val slowFirstTokenMs: Long) {
    /**
     * 与渠道仓储的 `listHealthChannels` 一样跳过完整渠道解析，额外带上密钥支持的模型供冷却概览使用，
     * 并按 [ChannelKeyState.isRoutableAt] 标出每个密钥当前是否参与路由。
     *
     * 订阅账号属于 subscription-oauth 上下文，因此概览读取留在应用层，渠道仓储只暴露渠道自身的事实。
     */
    internal suspend fun listHealthChannelOverviews(now: Long = System.currentTimeMillis()): List<HealthChannelOverview> =
        suspendTransaction(database) {
            val rows = Channels.select(Channels.id, Channels.enabled).toList()
            val channelIds = rows.map { it[Channels.id] }
            if (channelIds.isEmpty()) return@suspendTransaction emptyList()
            // 只读健康概览需要的字段，不解析密钥额度/限流等无关 JSON。
            val keyRows = ChannelKeys
                .select(
                    ChannelKeys.id,
                    ChannelKeys.channelId,
                    ChannelKeys.state,
                    ChannelKeys.autoDisableUntil,
                    ChannelKeys.probeUntil,
                )
                .where { ChannelKeys.channelId inList channelIds }
                .toList()
            val modelsByKey = if (keyRows.isEmpty()) {
                emptyMap()
            } else {
                ChannelKeyModels
                    .select(ChannelKeyModels.keyId, ChannelKeyModels.upstreamModel)
                    .where { ChannelKeyModels.keyId inList keyRows.map { it[ChannelKeys.id] } }
                    .groupBy { it[ChannelKeyModels.keyId] }
                    .mapValues { (_, models) -> models.map { it[ChannelKeyModels.upstreamModel] }.sorted() }
            }
            // 渠道模型目录：订阅账号没有显式模型时继承这里，有显式模型时必须与它相交。
            val modelsByChannel = ChannelModels
                .select(ChannelModels.channelId, ChannelModels.upstreamModel)
                .where { ChannelModels.channelId inList channelIds }
                .groupBy { it[ChannelModels.channelId] }
                .mapValues { (_, models) -> models.map { it[ChannelModels.upstreamModel] }.toSet() }
            val keysByChannel = keyRows.groupBy { it[ChannelKeys.channelId] }.mapValues { (channelId, keys) ->
                val channelModels = modelsByChannel[channelId].orEmpty()
                keys.map { key ->
                    val models = modelsByKey[key[ChannelKeys.id]].orEmpty()
                    // 与详情页 routableCredentialCount 同口径：密钥必须支持渠道目录中的模型，
                    // 只有历史授权、渠道目录已移除这些模型时它接不到任何请求。
                    val authorized = models.any { it in channelModels }
                    val routable = authorized && ChannelKeyState.fromValue(key[ChannelKeys.state])
                        ?.isRoutableAt(key[ChannelKeys.autoDisableUntil], key[ChannelKeys.probeUntil], now) == true
                    HealthChannelKey(key[ChannelKeys.id], models, routable)
                }
            }
            // 订阅账号同样参与候选路由。账号可路由只取决于 enabled，
            // 等待重新授权的账号仍保留为候选，不能据此判定渠道不可用。
            val routableAccountsByChannel = routableAccountCounts(channelIds, modelsByChannel)
            rows.map { row ->
                val keys = keysByChannel[row[Channels.id]].orEmpty()
                HealthChannelOverview(
                    id = row[Channels.id],
                    enabled = row[Channels.enabled],
                    keys = keys,
                    routableCredentials = keys.count { it.routable } +
                        (routableAccountsByChannel[row[Channels.id]] ?: 0),
                )
            }
        }

    /**
     * 每渠道可路由的订阅账号数。账号 `models` 为空表示继承渠道全部模型；
     * 显式授权时必须与渠道目录有交集，否则该账号调不到任何模型。
     */
    private fun routableAccountCounts(
        channelIds: List<String>,
        modelsByChannel: Map<String, Set<String>>,
    ): Map<String, Int> {
        val links = OAuthAccountChannels
            .join(OAuthAccounts, JoinType.INNER, OAuthAccountChannels.accountId, OAuthAccounts.id)
            .select(OAuthAccountChannels.channelId, OAuthAccountChannels.accountId)
            .where { (OAuthAccountChannels.channelId inList channelIds) and (OAuthAccounts.enabled eq true) }
            .toList()
        if (links.isEmpty()) return emptyMap()
        val accountIds = links.map { it[OAuthAccountChannels.accountId] }.distinct()
        val explicitModels = OAuthAccountModels
            .select(OAuthAccountModels.accountId, OAuthAccountModels.upstreamModel)
            .where { OAuthAccountModels.accountId inList accountIds }
            .groupBy { it[OAuthAccountModels.accountId] }
            .mapValues { (_, rows) -> rows.map { it[OAuthAccountModels.upstreamModel] }.toSet() }
        return links.groupBy { it[OAuthAccountChannels.channelId] }.mapValues { (channelId, channelLinks) ->
            val channelModels = modelsByChannel[channelId].orEmpty()
            channelLinks.count { link ->
                val models = explicitModels[link[OAuthAccountChannels.accountId]]
                if (models.isNullOrEmpty()) channelModels.isNotEmpty() else models.any { it in channelModels }
            }
        }
    }

    /**
     * [channels] 为渠道 id 与启用状态的映射；[routableCredentials] 提供每个渠道当前可路由的
     * 凭据数，缺省时不参与「无可用凭据」判定，现有调用方与测试可继续只传启用状态。
     */
    suspend fun report(
        window: String,
        channels: Map<String, Boolean>,
        retentionDays: Int,
        now: Long = System.currentTimeMillis(),
        cooldowns: Map<String, ChannelCooldownSummary> = emptyMap(),
        routableCredentials: Map<String, Int> = emptyMap(),
    ): ChannelHealthReport =
        suspendTransaction(database) {
            buildReport(window, channels, retentionDays, now, cooldowns = cooldowns, routableCredentials = routableCredentials)
        }

    suspend fun detail(
        window: String,
        channel: ChannelRecord,
        retentionDays: Int,
        availability: List<HealthAvailability>,
        now: Long = System.currentTimeMillis(),
        cooldown: ChannelCooldownSummary? = null,
        routableCredentials: Int? = null,
    ): ChannelHealthDetail =
        suspendTransaction(database) {
            val report = buildReport(
                window, mapOf(channel.id to channel.enabled), retentionDays, now, channel.id,
                cooldowns = cooldown?.let { mapOf(channel.id to it) } ?: emptyMap(),
                routableCredentials = routableCredentials?.let { mapOf(channel.id to it) } ?: emptyMap(),
            )
            val width = if (window == "24h") 3_600_000L else 86_400_000L
            val metrics = groups(report.from, now, channel.id, "((created_at - ${report.from}) / $width)::text")
            val trend = generateSequence(report.from) { it + width }.takeWhile { it < now }.map { from ->
                HealthBucket(from, minOf(from + width, now), metrics[((from - report.from) / width).toString()] ?: HealthMetrics())
            }.toList()
            fun breakdown(column: String, names: Map<String, String> = emptyMap()): List<HealthBreakdown> =
                groups(report.from, now, channel.id, column).map { (id, metrics) ->
                    HealthBreakdown(id, names[id] ?: id, metrics)
                }.sortedWith(compareByDescending<HealthBreakdown> { it.metrics.attempts }.thenBy { it.id })
            val errors = exec("""
                select coalesce(error_code, 'HTTP ' || status_code::text, 'unknown') as code, count(*) as n, max(created_at) as last_at
                from request_attempts where created_at >= ? and created_at < ? and channel_id = ? and outcome = 'FAILURE'
                group by code order by n desc, code limit 10
            """.trimIndent(), args(report.from, now, channel.id)) { rs ->
                buildList { while (rs.next()) add(HealthError(rs.getString("code"), rs.getLong("n"), rs.getLong("last_at"))) }
            }.orEmpty()
            val oauthNames = OAuthAccountChannels
                .join(OAuthAccounts, JoinType.INNER, OAuthAccountChannels.accountId, OAuthAccounts.id)
                .selectAll()
                .where { OAuthAccountChannels.channelId eq channel.id }
                .associate { it[OAuthAccounts.id] to it[OAuthAccounts.name] }
            ChannelHealthDetail(
                report, trend, breakdown("coalesce(upstream_model, '未知模型')"),
                breakdown("coalesce(upstream_protocol, '未知协议')"),
                breakdown(
                    "coalesce(channel_key_id, '未知密钥')",
                    channel.keys.associate { it.id to it.name } + oauthNames,
                ), errors, availability,
            )
        }

    private fun JdbcTransaction.buildReport(
        window: String,
        channels: Map<String, Boolean>,
        retentionDays: Int,
        now: Long,
        channelId: String? = null,
        cooldowns: Map<String, ChannelCooldownSummary> = emptyMap(),
        routableCredentials: Map<String, Int> = emptyMap(),
    ): ChannelHealthReport {
        val from = now - healthWindows.getValue(window)
        val observations = groups(from, now, channelId, "channel_id")
        val pendingChannels = exec(
            "select channel_id, attempts_json from request_logs where not health_indexed and created_at >= ? and created_at < ?",
            args(from, now),
        ) { rs ->
            buildSet {
                while (rs.next()) {
                    val finalChannelId = rs.getString("channel_id")
                    if (channelId == null || finalChannelId == channelId) finalChannelId?.let(::add)
                    UpstreamAttemptCodec.decode(rs.getString("attempts_json"))
                        .asSequence()
                        .filterNot { it.skipped }
                        .mapNotNull { it.channelId }
                        .filter { channelId == null || it == channelId }
                        .forEach(::add)
                }
            }
        }.orEmpty()
        val pending = pendingChannels.isNotEmpty()
        val requests = requests(from, now, channelId)
        val legacy = exec("""
            select channel_id, count(*) as n from request_logs
            where created_at >= ? and created_at < ? and health_indexed and channel_id is not null
              and (upstream_attempts is null or upstream_attempts > 0)
              and not exists(select 1 from request_attempts a where a.log_id = request_logs.id)
              ${if (channelId != null) "and channel_id = ?" else ""}
            group by channel_id
        """.trimIndent(), args(from, now, channelId)) { rs ->
            buildMap { while (rs.next()) put(rs.getString("channel_id"), rs.getLong("n")) }
        }.orEmpty()
        return ChannelHealthReport(
            window, from, now, retentionDays, pending, slowFirstTokenMs = slowFirstTokenMs,
            channels = channels.map { (id, enabled) ->
                val metrics = observations[id] ?: HealthMetrics()
                val (state, reason) = healthState(metrics, enabled, now, slowFirstTokenMs, routableCredentials[id])
                val cooldown = cooldowns[id]
                // 无可用凭据是比「历史数据补齐中」更确定的事实，不能被索引进度覆盖。
                val pendingApplies = pendingChannels.contains(id) && enabled && state != "NO_CREDENTIALS"
                ChannelHealthSummary(
                    id, metrics, (requests[id] ?: HealthRequests()).copy(legacyRequests = legacy[id] ?: 0),
                    if (pendingApplies) "UNKNOWN" else state,
                    if (pendingApplies) "历史数据补齐中" else reason,
                    cooldownUntil = cooldown?.until,
                    cooldownReason = cooldown?.reason,
                    coolingCandidates = cooldown?.coolingCandidates ?: 0,
                    totalCandidates = cooldown?.totalCandidates ?: 0,
                )
            },
        )
    }

    private fun JdbcTransaction.requests(from: Long, to: Long, channelId: String?): Map<String, HealthRequests> = exec("""
        select i.channel_id, count(*) as requests,
          count(*) filter (where l.status_code between 200 and 299 and l.error_code is null and l.failure_stage is null) as successes,
          count(*) filter (where l.upstream_attempts > 1) as failovers
        from (
            select distinct channel_id, log_id from request_attempts
            where created_at >= ? and created_at < ? and outcome <> 'EXCLUDED' ${if (channelId != null) "and channel_id = ?" else ""}
        ) i join request_logs l on l.id = i.log_id group by i.channel_id
    """.trimIndent(), args(from, to, channelId)) { rs ->
        buildMap { while (rs.next()) {
            val count = rs.getLong("requests")
            val successes = rs.getLong("successes")
            val failovers = rs.getLong("failovers")
            put(rs.getString("channel_id"), HealthRequests(count, successes, ratio(successes, count), failovers, ratio(failovers, count)))
        } }
    }.orEmpty()

    /** Expressions are internal constants, all external filters are bound parameters. */
    private fun JdbcTransaction.groups(from: Long, to: Long, channelId: String?, dimension: String): Map<String, HealthMetrics> = exec("""
        select $dimension as dimension,
          count(*) filter(where outcome <> 'EXCLUDED') as attempts,
          count(*) filter(where outcome = 'SUCCESS') as successes,
          count(*) filter(where outcome = 'FAILURE') as failures,
          count(*) filter(where outcome = 'EXCLUDED') as excluded,
          count(*) filter(where outcome = 'SUCCESS' and stream and first_token_ms is not null) as ttft_samples,
          percentile_cont(0.5) within group(order by first_token_ms) filter(where outcome = 'SUCCESS' and stream) as ttft_p50,
          percentile_cont(0.95) within group(order by first_token_ms) filter(where outcome = 'SUCCESS' and stream) as ttft_p95,
          percentile_cont(0.5) within group(order by elapsed_ms) filter(where outcome = 'SUCCESS') as total_p50,
          percentile_cont(0.95) within group(order by elapsed_ms) filter(where outcome = 'SUCCESS') as total_p95,
          count(*) filter(where outcome = 'SUCCESS' and stream and output_tokens > 0 and first_token_ms is not null and elapsed_ms > first_token_ms) as tps_samples,
          percentile_cont(0.5) within group(order by output_tokens * 1000.0 / nullif(elapsed_ms - first_token_ms, 0))
            filter(where outcome = 'SUCCESS' and stream and output_tokens > 0 and first_token_ms is not null and elapsed_ms > first_token_ms) as tps,
          max(created_at) filter(where outcome = 'SUCCESS') as last_success,
          max(created_at) filter(where outcome = 'FAILURE') as last_failure,
          max(created_at) filter(where outcome <> 'EXCLUDED') as last_observed
        from request_attempts where created_at >= ? and created_at < ? ${if (channelId != null) "and channel_id = ?" else ""}
        group by dimension
    """.trimIndent(), args(from, to, channelId)) { rs ->
        buildMap { while (rs.next()) {
            val count = rs.getLong("attempts")
            val successes = rs.getLong("successes")
            put(rs.getString("dimension"), HealthMetrics(
                attempts = count, successes = successes, failures = rs.getLong("failures"), excluded = rs.getLong("excluded"),
                successRate = ratio(successes, count), firstTokenSamples = rs.getLong("ttft_samples"),
                p50FirstTokenMs = rs.nullDouble("ttft_p50"), p95FirstTokenMs = rs.nullDouble("ttft_p95"),
                p50TotalMs = rs.nullDouble("total_p50"), p95TotalMs = rs.nullDouble("total_p95"),
                outputTokensPerSecond = rs.nullDouble("tps"), throughputSamples = rs.getLong("tps_samples"),
                lastSuccessAt = rs.nullLong("last_success"), lastFailureAt = rs.nullLong("last_failure"), lastObservedAt = rs.nullLong("last_observed"),
            ))
        } }
    }.orEmpty()

    private fun args(from: Long, to: Long, channelId: String? = null) =
        listOf(LongColumnType() to from, LongColumnType() to to) +
            if (channelId != null) listOf(TextColumnType() to channelId) else emptyList()

    private fun ratio(numerator: Long, denominator: Long): Double? = if (denominator > 0) numerator.toDouble() / denominator else null
    private fun ResultSet.nullDouble(column: String): Double? = getDouble(column).let { if (wasNull()) null else it }
    private fun ResultSet.nullLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }
}
