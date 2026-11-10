package dev.soven.llmgateway.repo

import dev.soven.llmgateway.channel.persistence.ChannelRepository
import dev.soven.llmgateway.channel.persistence.NewChannelKey

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.channel.domain.ChannelModelRecord
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.requestlog.domain.RequestLogRecord
import dev.soven.llmgateway.requestlog.domain.UpstreamAttemptRecord
import dev.soven.llmgateway.requestlog.domain.HealthMetrics
import dev.soven.llmgateway.requestlog.persistence.RequestLogRepository
import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChannelHealthRepositoryTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var logs: RequestLogRepository
    private lateinit var health: ChannelHealthRepository
    private val now = 1_800_000_000_000L
    private val a = channel("a")
    private val b = channel("b")

    @BeforeAll
    fun start() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        factory = DatabaseFactory(GatewayConfig.fromEnv(mapOf(
            "DATABASE_URL" to postgres.jdbcUrl,
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASSWORD" to postgres.password,
            "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            "ADMIN_PASSWORD" to "test-password",
        )))
        factory.migrate()
        logs = RequestLogRepository(factory.database, RequestLogCurrentNames())
        health = ChannelHealthRepository(factory.database, 10_000)
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun clear() {
        sql("truncate table request_logs cascade")
        sql("truncate table channels cascade")
    }

    @Test
    fun overviewLoadsChannelIdentityAndEnabledStateWithoutDecodingFullRecords() = runBlocking {
        val cipher = CredentialCipher(ByteArray(32) { 7 })
        val channels = ChannelRepository(factory.database, cipher)
        val active = channels.create(
            name = "enabled", baseUrl = "https://example.invalid/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("test-secret"), models = listOf("m1"))),
            authType = "bearer", enabled = true,
            modelMappings = listOf(ChannelModelRecord("m1")),
        )
        val disabled = channels.create(
            name = "disabled", baseUrl = "https://example.invalid/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("test-secret"), models = listOf("m1"))),
            authType = "bearer", enabled = false,
            modelMappings = listOf(ChannelModelRecord("m1")),
        )
        // Full channel loading rejects this JSON; the overview only needs identity and enabled state.
        sql("update channels set request_overrides = 'invalid-json'")
        // 健康概览不需要额度设置，坏 JSON 不应让整个概览失败。
        sql(
            "insert into channel_key_quota_settings (key_id, settings, updated_at) " +
                "values ('${active.keys.single().id}', 'invalid-json', 0) " +
                "on conflict (key_id) do update set settings = 'invalid-json'",
        )
        val identities = channels.listHealthChannels()
        assertEquals(mapOf(active.id to true, disabled.id to false), identities)
        val overviews = health.listHealthChannelOverviews()
        assertEquals(setOf(active.id, disabled.id), overviews.map { it.id }.toSet())
        assertEquals(1, overviews.first { it.id == active.id }.keys.size)
        // 两个渠道的密钥都是 ACTIVE，但只有一个渠道启用；可路由凭据数与会话状态无关。
        assertEquals(1, overviews.first { it.id == active.id }.routableCredentials)
        val report = health.report(
            "24h", identities, 30, now,
            routableCredentials = overviews.associate { it.id to it.routableCredentials },
        )
        assertEquals("DISABLED", report.channels.first { it.channelId == disabled.id }.state)
        assertEquals("UNKNOWN", report.channels.first { it.channelId == active.id }.state)
    }

    @Test
    fun overviewMarksKeysWithoutAuthorizedModelsAndDisabledStatesAsUnroutable() = runBlocking {
        val cipher = CredentialCipher(ByteArray(32) { 7 })
        val channels = ChannelRepository(factory.database, cipher)
        val routable = channels.create(
            name = "routable", baseUrl = "https://example.invalid/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("test-secret"), models = listOf("m1"))),
            authType = "bearer", enabled = true, modelMappings = listOf(ChannelModelRecord("m1")),
        )
        val suspended = channels.create(
            name = "suspended", baseUrl = "https://example.invalid/v1",
            keys = listOf(
                NewChannelKey(
                    "manual", cipher.encrypt("test-secret"),
                    state = ChannelKeyState.MANUAL_DISABLED, models = listOf("m1"),
                ),
            ),
            authType = "bearer", enabled = true, modelMappings = listOf(ChannelModelRecord("m1")),
        )
        val overviews = health.listHealthChannelOverviews().associateBy { it.id }
        assertEquals(1, overviews.getValue(routable.id).routableCredentials)
        assertEquals(0, overviews.getValue(suspended.id).routableCredentials)
        assertTrue(overviews.getValue(routable.id).keys.single().routable)
        assertFalse(overviews.getValue(suspended.id).keys.single().routable)

        val report = health.report(
            "24h", mapOf(routable.id to true, suspended.id to true), 30, now,
            routableCredentials = overviews.mapValues { it.value.routableCredentials },
        )
        assertEquals("NO_CREDENTIALS", report.channels.first { it.channelId == suspended.id }.state)
        assertEquals("没有可用的上游凭据", report.channels.first { it.channelId == suspended.id }.reason)
        assertEquals("UNKNOWN", report.channels.first { it.channelId == routable.id }.state)
    }

    @Test
    fun overviewRequiresKeysToSupportTheCurrentChannelCatalog() = runBlocking {
        val cipher = CredentialCipher(ByteArray(32) { 7 })
        val channels = ChannelRepository(factory.database, cipher)
        val stale = channels.create(
            name = "stale-key-models", baseUrl = "https://example.invalid/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("test-secret"), models = listOf("m1"))),
            authType = "bearer", enabled = true, modelMappings = listOf(ChannelModelRecord("m1")),
        )
        // 模拟历史遗留数据：渠道目录已收缩到 m2，密钥仍保留指向 m1 的旧授权。
        assertTrue(channels.replaceModels(stale.id, listOf(ChannelModelRecord("m2"))))
        sql(
            "insert into channel_key_models (key_id, upstream_model, created_at) " +
                "values ('${stale.keys.single().id}', 'm1', 0)",
        )

        // 概览与详情必须同口径：授权模型与渠道目录无交集时都算 0。
        val overviews = health.listHealthChannelOverviews().associateBy { it.id }
        assertEquals(0, overviews.getValue(stale.id).routableCredentials)
        assertFalse(overviews.getValue(stale.id).keys.single().routable)
        assertEquals(0, assertNotNull(channels.find(stale.id)).routableCredentialCount())

        val report = health.report(
            "24h", mapOf(stale.id to true), 30, now,
            routableCredentials = overviews.mapValues { it.value.routableCredentials },
        )
        assertEquals("NO_CREDENTIALS", report.channels.single().state)
    }

    @Test
    fun failoverExclusionsAndStreamFailuresAreAggregatedIndependently() = runBlocking {
        logs.insert(log(listOf(
            attempt("a", 429).copy(index = 1, upstreamErrorCode = "rate_limit"),
            attempt("b").copy(index = 2),
        )))
        logs.insert(log(listOf(attempt("a").copy(elapsedMs = 3_000, firstTokenMs = 1_000)), outputTokens = 200))
        logs.insert(log(listOf(attempt("a").copy(errorCode = "client_cancelled")), failed = true))
        logs.insert(log(listOf(attempt("a").copy(upstreamErrorCode = "stream_error")), failed = true))

        val report = health.report("24h", mapOf("a" to true, "b" to true, "empty" to true), 30, now)
        val observedA = report.channels.first()
        assertFalse(report.indexing)
        assertEquals(3, observedA.metrics.attempts)
        assertEquals(1, observedA.metrics.successes)
        assertEquals(2, observedA.metrics.failures)
        assertEquals(1, observedA.metrics.excluded)
        assertEquals(1.0 / 3, observedA.metrics.successRate)
        assertEquals(3, observedA.requests.requests)
        assertEquals(2, observedA.requests.successes)
        assertEquals(1, observedA.requests.failoverRequests)
        assertEquals(1_000.0, observedA.metrics.p95FirstTokenMs)
        assertEquals(100.0, observedA.metrics.outputTokensPerSecond)
        assertEquals(1, report.channels[1].metrics.successes)
        assertEquals(HealthMetrics(), report.channels[2].metrics)
        assertEquals("UNKNOWN", observedA.state)

        val detail = health.detail("24h", a, 30, emptyList(), now)
        assertEquals(24, detail.trend.size)
        assertEquals(3, detail.trend.sumOf { it.metrics.attempts })
        assertEquals("model-a", detail.byModel.single().id)
        assertEquals("responses", detail.byProtocol.single().id)
        assertEquals("key-a", detail.byKey.single().id)
        assertEquals(setOf("rate_limit", "stream_error"), detail.errors.map { it.code }.toSet())
    }

    @Test
    fun timeWindowsAreHalfOpenAndNonStreamingDoesNotContributeTokenLatency() = runBlocking {
        val day = 86_400_000L
        for (offset in listOf(day + 1, day, 1L, 0L)) {
            logs.insert(log(listOf(attempt("a")), createdAt = now - offset, stream = false))
        }
        val report = health.report("24h", mapOf("a" to true), 30, now)
        val metrics = report.channels.single().metrics
        assertEquals(2, metrics.attempts)
        assertEquals(0, metrics.firstTokenSamples)
        assertNull(metrics.p95FirstTokenMs)
        assertNull(metrics.outputTokensPerSecond)
        assertEquals(1_000.0, metrics.p95TotalMs)
        for ((window, days) in listOf("7d" to 7, "15d" to 15, "30d" to 30)) {
            val detail = health.detail(window, a, 30, emptyList(), now)
            assertEquals(days, detail.trend.size)
            assertEquals(3, detail.report.channels.single().metrics.attempts)
            assertEquals(3, detail.trend.sumOf { it.metrics.attempts })
        }
    }

    @Test
    fun historicalIndexingIsBoundedIdempotentAndDeletedWithLogs() = runBlocking {
        logs.insert(log(listOf(attempt("a"))))
        logs.insert(log(emptyList()).copy(upstreamAttempts = null))
        sql("delete from request_attempts")
        sql("update request_logs set health_indexed = false")
        assertTrue(health.report("24h", mapOf("a" to true), 30, now).indexing)
        assertEquals(1, logs.indexHealthBatch(now - 86_400_000, 1))
        assertEquals(1, logs.indexHealthBatch(now - 86_400_000, 1))
        assertEquals(0, logs.indexHealthBatch(now - 86_400_000, 1))
        val report = health.report("24h", mapOf("a" to true), 30, now)
        assertFalse(report.indexing)
        assertEquals(1, report.channels.single().metrics.attempts)
        assertEquals(1, report.channels.single().requests.legacyRequests)
        sql("delete from request_logs")
        assertEquals(0, health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics.attempts)
    }

    @Test
    fun selectedBatchRemainsNonEmptyWhenAnotherInstanceIndexesItsRowsFirst() = runBlocking {
        val record = log(listOf(attempt("a")))
        logs.insert(record)
        sql("update request_logs set health_indexed = false")
        val id = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("select id from request_logs where request_id = ?").use { statement ->
                statement.setString(1, record.requestId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    result.getLong(1)
                }
            }
        }

        // Model two workers selecting the same pending ID: the first finishes
        // before the second begins processing its already selected batch.
        assertEquals(1, logs.indexHealthBatch(now - 86_400_000, 10))
        assertEquals(1, logs.indexSelectedHealthBatch(listOf(id)))
        assertEquals(1, health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics.attempts)
    }

    @Test
    fun historicalIndexingPrioritizesTheMostRecentPendingRequests() = runBlocking {
        logs.insert(log(listOf(attempt("a")), createdAt = now - 100))
        logs.insert(log(listOf(attempt("a")), createdAt = now - 10))
        sql("delete from request_attempts")
        sql("update request_logs set health_indexed = false")

        assertEquals(1, logs.indexHealthBatch(now - 86_400_000, 1))
        val indexedTimestamp = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select created_at from request_logs where health_indexed = true").use { result ->
                    assertTrue(result.next())
                    result.getLong(1)
                }
            }
        }
        assertEquals(now - 10, indexedTimestamp)
    }

    @Test
    fun historicalIndexingContinuesWhenPricingTiersAreCorrupted() = runBlocking {
        val brokenPrice = log(listOf(attempt("a")), createdAt = now - 1)
        val validPrice = log(listOf(attempt("a")), createdAt = now - 2)
        logs.insert(brokenPrice)
        logs.insert(validPrice)
        sql("update request_logs set price_tiers_json = 'not-json' where request_id = '${brokenPrice.requestId}'")
        sql("delete from request_attempts")
        sql("update request_logs set health_indexed = false")

        assertEquals(2, logs.indexHealthBatch(now - 86_400_000, 10))
        assertEquals(0, logs.indexHealthBatch(now - 86_400_000, 10))
        assertEquals(2, health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics.attempts)
    }

    @Test
    fun legacyRequestCountExcludesRequestsWithoutAnUpstreamAttempt() = runBlocking {
        logs.insert(log(emptyList()).copy(upstreamAttempts = null))
        logs.insert(log(emptyList(), failed = true).copy(upstreamAttempts = 0))

        val requests = health.report("24h", mapOf("a" to true), 30, now).channels.single().requests
        assertEquals(1, requests.legacyRequests, "Pre-migration logs have unknown attempt counts and remain eligible")
    }

    @Test
    fun historicalIndexingIsolatesSqlFailuresAndRetriesOnlyTheFailedRow() = runBlocking {
        logs.insert(log(listOf(attempt("a")), createdAt = now - 1))
        logs.insert(log(listOf(attempt("a")), createdAt = now - 2))
        sql("delete from request_attempts")
        sql("update request_logs set health_indexed = false")
        sql("alter table request_attempts add constraint reject_test_timestamp check (created_at <> ${now - 1})")
        try {
            assertFailsWith<org.jetbrains.exposed.v1.exceptions.ExposedSQLException> {
                logs.indexHealthBatch(now - 86_400_000, 10)
            }
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select created_at, health_indexed from request_logs order by created_at desc").use { result ->
                        assertTrue(result.next())
                        assertEquals(now - 1, result.getLong(1))
                        assertFalse(result.getBoolean(2), "Failed row must remain pending for retry")
                        assertTrue(result.next())
                        assertEquals(now - 2, result.getLong(1))
                        assertTrue(result.getBoolean(2), "Healthy batch peer must commit independently")
                    }
                }
            }
            assertEquals(1, health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics.attempts)
        } finally {
            sql("alter table request_attempts drop constraint reject_test_timestamp")
        }
        assertEquals(1, logs.indexHealthBatch(now - 86_400_000, 10))
        assertEquals(0, logs.indexHealthBatch(now - 86_400_000, 10))
        assertEquals(2, health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics.attempts)
    }

    @Test
    fun pendingLegacyIndexingOnlyMasksTheAffectedChannels() = runBlocking {
        logs.insert(log(listOf(attempt("a"))))
        logs.insert(log(listOf(attempt("b"))))
        sql("update request_logs set health_indexed = false where channel_id = 'b'")
        val report = health.report("24h", mapOf("a" to true, "b" to true), 30, now)
        assertEquals("UNKNOWN", report.channels.first { it.channelId == "b" }.state)
        assertFalse(report.channels.first { it.channelId == "a" }.reason.contains("补齐"))
        val detailA = health.detail("24h", a, 30, emptyList(), now)
        assertFalse(detailA.report.channels.single().reason.contains("补齐"))
    }

    @Test
    fun pendingLegacyIndexingIncludesChannelsSeenOnlyInAttempts() = runBlocking {
        logs.insert(log(listOf(attempt("a").copy(index = 1), attempt("b").copy(index = 2))))
        sql("delete from request_attempts")
        sql("update request_logs set health_indexed = false")

        val detailA = health.detail("24h", a, 30, emptyList(), now)
        val detailB = health.detail("24h", b, 30, emptyList(), now)
        val detailC = health.detail("24h", channel("c"), 30, emptyList(), now)

        assertTrue(detailA.report.indexing)
        assertTrue(detailA.report.channels.single().reason.contains("补齐"))
        assertTrue(detailB.report.indexing)
        assertTrue(detailB.report.channels.single().reason.contains("补齐"))
        assertFalse(detailC.report.indexing)
    }

    @Test
    fun transactionalAlterAndIndexKeepTheAccessExclusiveLockUntilCommit() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val table = "v45_lock_probe_$suffix"
        val index = "idx_v45_lock_probe_${suffix}_pending"
        sql("create table $table (id bigint primary key)")
        try {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { migration ->
                migration.autoCommit = false
                migration.createStatement().use { statement ->
                    statement.execute("alter table $table add column health_indexed boolean not null default false")
                    statement.execute("create index $index on $table (id) where not health_indexed")
                }
                try {
                    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { reader ->
                        reader.createStatement().use { statement ->
                            statement.execute("set lock_timeout = '100ms'")
                            val error = assertFailsWith<SQLException> { statement.executeQuery("select * from $table") }
                            assertEquals("55P03", error.sqlState)
                        }
                    }
                } finally {
                    migration.rollback()
                }
            }
        } finally {
            sql("drop table if exists $table")
        }
    }

    @Test
    fun percentilesAndLongUpstreamErrorCodesDoNotLoseRequestLogs() = runBlocking {
        for (index in 1..20) {
            logs.insert(log(listOf(attempt("a").copy(firstTokenMs = index * 10L))))
        }
        val metrics = health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics
        assertEquals(105.0, metrics.p50FirstTokenMs!!, 0.001)
        assertEquals(190.5, metrics.p95FirstTokenMs!!, 0.001)
        assertEquals("HEALTHY", health.report("24h", mapOf("a" to true), 30, now).channels.single().state)
        val longError = "upstream_" + "a".repeat(180)
        logs.insert(log(listOf(attempt("a", 503).copy(upstreamErrorCode = longError)), failed = true))
        assertEquals(21, health.report("24h", mapOf("a" to true), 30, now).channels.single().metrics.attempts)
        assertEquals(longError, health.detail("24h", a, 30, emptyList(), now).errors.single().code)
    }

    private fun channel(id: String) = ChannelRecord(id, id, "https://example.invalid/v1", "bearer", listOf(GatewayProtocol.RESPONSES), true, now, now)

    private fun attempt(channel: String, status: Int = 200) = UpstreamAttemptRecord(
        index = 1, channelId = channel, channelKeyId = "key-$channel", upstreamModel = "model-$channel",
        upstreamProtocol = GatewayProtocol.RESPONSES, statusCode = status, elapsedMs = 1_000, firstTokenMs = 100,
    )

    private fun log(attempts: List<UpstreamAttemptRecord>, outputTokens: Long = 90, createdAt: Long = now - 1, stream: Boolean = true, failed: Boolean = false) = RequestLogRecord(
        requestId = UUID.randomUUID().toString(), clientKeyId = null, clientKeyName = null, alias = "test-model",
        channelId = attempts.lastOrNull()?.channelId ?: a.id, channelName = "test-channel", upstreamModel = "test-model",
        protocol = GatewayProtocol.RESPONSES, stream = stream, statusCode = if (failed) 502 else 200,
        firstTokenMs = null, totalMs = 2_000, inputTokens = 10, outputTokens = outputTokens, cachedTokens = null,
        reasoningTokens = null, reasoningEffort = null, totalTokens = outputTokens + 10, usageKnown = true,
        costAmount = null, costCurrency = null, inputPricePerMillion = null, outputPricePerMillion = null,
        cachedInputPricePerMillion = null, errorCode = if (failed) "failed" else null,
        failureStage = if (failed) FailureStage.UPSTREAM else null, errorMessage = null, upstreamErrorCode = null,
        createdAt = createdAt, upstreamAttempts = attempts.size, attempts = attempts,
    )

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }
}
