package dev.soven.llmgateway.repo

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.db.DatabaseFactory
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.UpstreamAttemptRecord
import dev.soven.llmgateway.report.HealthMetrics
import dev.soven.llmgateway.security.CredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        logs = RequestLogRepository(factory.database)
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
            keys = listOf(NewChannelKey("key", cipher.encrypt("test-secret"))),
            authType = "bearer", enabled = true,
        )
        val disabled = channels.create(
            name = "disabled", baseUrl = "https://example.invalid/v1",
            keys = listOf(NewChannelKey("key", cipher.encrypt("test-secret"))),
            authType = "bearer", enabled = false,
        )
        // Full channel loading rejects this JSON; the overview only needs identity and enabled state.
        sql("update channels set request_overrides = 'invalid-json'")
        val identities = channels.listHealthChannels()
        assertEquals(mapOf(active.id to true, disabled.id to false), identities)
        val report = health.report("24h", identities, 30, now)
        assertEquals("DISABLED", report.channels.first { it.channelId == disabled.id }.state)
        assertEquals("UNKNOWN", report.channels.first { it.channelId == active.id }.state)
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
        logs.insert(log(emptyList()))
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
