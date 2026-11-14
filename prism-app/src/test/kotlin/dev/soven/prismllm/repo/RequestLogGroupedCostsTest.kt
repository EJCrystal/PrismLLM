package dev.soven.prismllm.repo

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import dev.soven.prismllm.requestlog.domain.UsageReportQuery
import dev.soven.prismllm.requestlog.persistence.RequestLogRepository
import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 分组费用聚合的回归测试：空串与 NULL 的分组 ID 是不同的 SQL 分组值，
 * 映射到同一个 unassigned 哨兵后必须累加，不能用 toMap 覆盖（PR #220 review 发现的回归）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("component")
class RequestLogGroupedCostsTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var factory: DatabaseFactory
    private lateinit var logs: RequestLogRepository
    private val now = 1_800_000_000_000L

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
        logs = RequestLogRepository(factory.database) { _, _ -> emptyMap() }
    }

    @AfterAll
    fun stop() {
        if (::factory.isInitialized) factory.close()
        if (::postgres.isInitialized) postgres.stop()
    }

    private fun log(clientKeyId: String?, cost: String) = RequestLogRecord(
        requestId = UUID.randomUUID().toString(),
        clientKeyId = clientKeyId,
        clientKeyName = clientKeyId?.takeIf { it.isNotBlank() },
        alias = "test-model",
        channelId = null,
        channelName = null,
        upstreamModel = "test-model",
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        stream = false,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 100,
        inputTokens = 10,
        outputTokens = 10,
        cachedTokens = null,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = 20,
        usageKnown = true,
        costAmount = BigDecimal(cost),
        costCurrency = "USD",
        inputPricePerMillion = null,
        outputPricePerMillion = null,
        cachedInputPricePerMillion = null,
        cachedInputWritePricePerMillion = null,
        cachedWriteTokens = null,
        errorCode = null,
        failureStage = null,
        errorMessage = null,
        upstreamErrorCode = null,
        createdAt = now - 1,
        upstreamAttempts = 1,
        attempts = emptyList(),
    )

    @Test
    fun `unassigned costs from null and blank group ids are summed not overwritten`() = runBlocking {
        logs.insert(log(null, "0.25"))
        logs.insert(log("", "0.75"))
        logs.insert(log("key-a", "1.00"))

        val report = logs.report(UsageReportQuery(from = now - 86_400_000L, to = now))

        val unassigned = report.byClientKey.single { it.id == "__unassigned_key__" }
        assertEquals(0, unassigned.costUsd?.compareTo(BigDecimal("1.00")), "null 与空串分组的费用必须累加")
        val assigned = report.byClientKey.single { it.id == "key-a" }
        assertEquals(0, assigned.costUsd?.compareTo(BigDecimal("1.00")))
        assertEquals(0, report.total.costUsd?.compareTo(BigDecimal("2.00")))
    }
}
