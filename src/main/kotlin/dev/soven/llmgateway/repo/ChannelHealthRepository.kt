package dev.soven.llmgateway.repo

import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.report.*
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.sql.ResultSet

/** Read-only health observations. Time windows use completion time and [from, to). */
class ChannelHealthRepository(private val database: Database, private val slowFirstTokenMs: Long) {
    suspend fun report(window: String, channels: Map<String, Boolean>, retentionDays: Int, now: Long = System.currentTimeMillis()): ChannelHealthReport =
        suspendTransaction(database) { buildReport(window, channels, retentionDays, now) }

    suspend fun detail(window: String, channel: ChannelRecord, retentionDays: Int, availability: List<HealthAvailability>, now: Long = System.currentTimeMillis()): ChannelHealthDetail =
        suspendTransaction(database) {
            val report = buildReport(window, mapOf(channel.id to channel.enabled), retentionDays, now, channel.id)
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
            ChannelHealthDetail(
                report, trend, breakdown("coalesce(upstream_model, '未知模型')"),
                breakdown("coalesce(upstream_protocol, '未知协议')"),
                breakdown("coalesce(channel_key_id, '未知密钥')", channel.keys.associate { it.id to it.name }), errors, availability,
            )
        }

    private fun JdbcTransaction.buildReport(window: String, channels: Map<String, Boolean>, retentionDays: Int, now: Long, channelId: String? = null): ChannelHealthReport {
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
                val (state, reason) = healthState(metrics, enabled, now, slowFirstTokenMs)
                ChannelHealthSummary(id, metrics, (requests[id] ?: HealthRequests()).copy(legacyRequests = legacy[id] ?: 0),
                    if (pendingChannels.contains(id) && enabled) "UNKNOWN" else state, if (pendingChannels.contains(id) && enabled) "历史数据补齐中" else reason)
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
