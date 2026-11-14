package dev.soven.prismllm.integration

import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.dailyQuotaPeriod
import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import dev.soven.prismllm.shared.domain.FailureStage
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真实 PostgreSQL 结算故障不能覆盖响应、重放上游或阻止日志与指标收尾。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
@Timeout(30)
class RequestFinalizationIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `JSON and streaming responses settle the same USD amount that is persisted in the request log`() = runBlocking {
        val channel = createChannel("finalization-priced", "ok")
        exposeAsModel(MODEL, channel to UPSTREAM_MODEL)
        createPrice(channel, UPSTREAM_MODEL, input = "1", output = "2")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val keyId = app.clientKeyRepository.list().single().id
        var loggedCost = BigDecimal.ZERO

        testApplication {
            application { app.install(this) }
            for ((index, stream) in listOf(false, true).withIndex()) {
                val requestId = UUID.randomUUID().toString()
                val response = client.send(key, requestId, stream)
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, body)
                assertTrue(body.contains(if (stream) "[DONE]" else "Hello!"), body)
                val log = awaitRequestLog()
                assertSuccessfulPricedLog(log, requestId)
                assertEquals(index + 1, fake.requests.size, "settlement must not replay the upstream")
                assertEquals(index + 1, scalarInt("select count(*) from request_logs"))

                loggedCost = loggedCost.add(assertNotNull(log.costAmount))
                val usage = app.clientKeyQuotaService.currentUsage(keyId)
                assertEquals(13L * (index + 1), usage.dailyTokens)
                assertEquals(0, usage.dailyUsdCost.compareTo(loggedCost), "quota USD must equal the sum of logged USD")
                assertEquals(0, usage.concurrentRequests)
                assertEquals(1, scalarInt("select count(*) from client_key_quota_usage where client_key_id = ?", keyId))
            }
            val metrics = client.get("/metrics").bodyAsText()
            assertTrue(metrics.contains("gateway_request_settlement_failures_total{stage=\"pricing\"} 0.0"), metrics)
            assertTrue(metrics.contains("gateway_request_settlement_failures_total{stage=\"quota\"} 0.0"), metrics)
        }
    }

    @Test
    fun `quota insert failure for a new key preserves the JSON response log and metrics and releases concurrency`() = runBlocking {
        assertQuotaFailure(stream = false, existingLedger = false)
    }

    @Test
    fun `quota update failure preserves the stream log and metrics without retrying the non idempotent write`() = runBlocking {
        assertQuotaFailure(stream = true, existingLedger = true)
    }

    @Test
    fun `quota lock timeout preserves the response log and metrics and releases concurrency while the lock remains held`() = runBlocking {
        val primary = createChannel("finalization-quota-lock", "key-health")
        val backup = createChannelWithBaseUrl("finalization-lock-backup", "${fake.baseUrl}/ok/v1", priority = 1)
        exposeAsModel(MODEL, primary to UPSTREAM_MODEL, backup to UPSTREAM_MODEL)
        createPrice(primary, UPSTREAM_MODEL, input = "1", output = "2")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val keyId = app.clientKeyRepository.list().single().id
        val period = dailyQuotaPeriod(System.currentTimeMillis())
        val initialUsd = BigDecimal("0.001")
        app.clientKeyQuotaRepository.addUsage(keyId, period, 7L, initialUsd)
        val requestId = UUID.randomUUID().toString()

        testApplication {
            application { app.install(this) }
            startApplication()
            // 仅锁本类克隆库的目标账本行；普通准入查询仍可读取已提交用量。
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { lock ->
                lock.autoCommit = false
                try {
                    lock.prepareStatement("select 1 from client_key_quota_usage where client_key_id = ? and period = ? for update").use { statement ->
                        statement.queryTimeout = 2
                        statement.setString(1, keyId)
                        statement.setLong(2, period)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()) }
                    }
                    coroutineScope {
                        val release = fake.holdChatRequests()
                        val pending = async { client.send(key, requestId, stream = false) }
                        try {
                            awaitCondition("upstream in flight before locked quota settlement") {
                                fake.requests.size == 1 && app.requestLimitService.clientKeyOccupancy()[keyId] == 1
                            }
                            app.beginShutdown()
                            release.complete(Unit)
                            // 锁尚未释放，停机 Drain 仍须完成，不能依赖关池打断结算才提交日志。
                            assertTrue(app.requestDrain.awaitDrained(5_000))
                            val response = withTimeout(10_000) { pending.await() }
                            val body = response.bodyAsText()
                            assertEquals(HttpStatusCode.OK, response.status, body)
                            assertTrue(body.contains("Hello!"), body)
                            val log = awaitRequestLog()
                            assertSuccessfulPricedLog(log, requestId)
                            assertEquals(primary.id, log.channelId)
                            assertEquals(1, fake.requests.size, "a quota timeout must not replay the upstream or send to the backup")
                            assertEquals(1, scalarInt("select count(*) from request_logs"))
                            assertQuotaFailureMetric(client)
                            val usage = app.clientKeyQuotaService.currentUsage(keyId)
                            assertEquals(7L, usage.dailyTokens)
                            assertEquals(0, initialUsd.compareTo(usage.dailyUsdCost))
                            assertEquals(0, usage.concurrentRequests)
                            assertTrue(app.requestLimitService.clientKeyOccupancy().isEmpty())
                        } finally {
                            // 回归失败也先解除 JDBC 锁，避免结构化并发等待非取消结算时卡住清理。
                            lock.rollback()
                            release.complete(Unit)
                            pending.cancel()
                            fake.clearChatHold()
                        }
                    }
                } finally {
                    lock.rollback()
                }
            }
        }
    }

    @Test
    fun `quota failure does not replace the original upstream stream error or replay it on a backup`() = runBlocking {
        val primary = createChannelWithBaseUrl(
            name = "finalization-original-error",
            baseUrl = "${fake.baseUrl}/sse-rate-limit-after-content/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord(UPSTREAM_MODEL)),
            authType = "anthropic",
        )
        val backup = createChannelWithBaseUrl(
            name = "finalization-error-backup",
            baseUrl = "${fake.baseUrl}/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord(UPSTREAM_MODEL)),
            priority = 1,
            authType = "anthropic",
        )
        exposeAsModel(MODEL, primary to UPSTREAM_MODEL, backup to UPSTREAM_MODEL)
        createPrice(primary, UPSTREAM_MODEL, input = "1", output = "2")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val keyId = app.clientKeyRepository.list().single().id
        val requestId = UUID.randomUUID().toString()

        withRejectedQuotaWrites {
            testApplication {
                application { app.install(this) }
                val response = client.post("/v1/messages") {
                    header("x-api-key", key)
                    header("X-Request-Id", requestId)
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"$MODEL","stream":true,"max_tokens":32,"messages":[{"role":"user","content":"hi"}]}""")
                }
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, body)
                assertTrue(body.contains("Hello!"), body)
                assertTrue(body.contains("event: error") && body.contains("rate limited"), body)
                assertTrue(!body.contains("internal_error"), body)

                val log = awaitRequestLog()
                assertEquals(requestId, log.requestId)
                assertEquals(200, log.statusCode)
                assertEquals("messages_failed", log.errorCode, "the quota exception must not replace the original stream failure")
                assertEquals(FailureStage.STREAMING, log.failureStage)
                val originalError = assertNotNull(log.upstreamErrorBody)
                assertTrue(originalError.contains("rate_limit_error") && originalError.contains("rate limited"), originalError)
                assertTrue(log.usageKnown, "the stream returns usage before its error, so settlement must really attempt a write")
                assertEquals(1, log.upstreamAttempts)
                assertEquals(primary.id, log.channelId)
                assertEquals(1, fake.requests.size)
                assertEquals("sse-rate-limit-after-content", fake.requests.single().scenario)
                assertEquals(1, scalarInt("select count(*) from request_logs"))
                assertSingleRejectedWrite()
                assertQuotaFailureMetric(client)
                assertEquals(0, app.clientKeyQuotaService.currentUsage(keyId).concurrentRequests)
                assertEquals(0, scalarInt("select count(*) from client_key_quota_usage where client_key_id = ?", keyId))
            }
        }
    }

    @Test
    fun `quota failure does not mask a client write exception after JSON usage has been received`() = runBlocking {
        val channel = createChannel("finalization-client-write-error", "ok")
        exposeAsModel(MODEL, channel to UPSTREAM_MODEL)
        createPrice(channel, UPSTREAM_MODEL, input = "1", output = "2")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val keyId = app.clientKeyRepository.list().single().id
        val requestId = UUID.randomUUID().toString()
        val failNextWrite = AtomicBoolean(true)

        withRejectedQuotaWrites {
            testApplication {
                application {
                    app.install(this)
                    install(createApplicationPlugin("finalization-client-write-failure") {
                        on(ResponseBodyReadyForSend) { _, _ ->
                            if (failNextWrite.compareAndSet(true, false)) {
                                throw ChannelWriteException("test original client write failure", IOException("test client disconnect"))
                            }
                        }
                    })
                }
                // JSON 路径已经解析用量，respondText 才失败；quota 写入与原始异常同时发生。
                val response = client.send(key, requestId, stream = false)
                val body = response.bodyAsText()
                assertEquals(499, response.status.value, body)
                assertTrue(body.contains("client_disconnect"), body)
                assertTrue(!body.contains("internal_error"), body)
                val log = awaitRequestLog()
                assertEquals(requestId, log.requestId)
                assertEquals(499, log.statusCode)
                assertEquals("client_disconnect", log.errorCode)
                assertEquals(FailureStage.CLIENT, log.failureStage)
                assertEquals(13L, log.totalTokens)
                assertTrue(log.usageKnown)
                assertEquals(1, log.upstreamAttempts)
                assertEquals(1, fake.requests.size)
                assertSingleRejectedWrite()
                assertQuotaFailureMetric(client)
                assertEquals(0, app.clientKeyQuotaService.currentUsage(keyId).concurrentRequests)
                assertEquals(0, scalarInt("select count(*) from client_key_quota_usage where client_key_id = ?", keyId))
            }
        }
    }

    @Test
    fun `price lookup failure still returns the response logs unpriced usage and settles tokens once`() = runBlocking {
        val channel = createChannel("finalization-price-failure", "ok")
        exposeAsModel(MODEL, channel to UPSTREAM_MODEL)
        createPrice(channel, UPSTREAM_MODEL, input = "1", output = "2")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))
        val keyId = app.clientKeyRepository.list().single().id
        val requestId = UUID.randomUUID().toString()

        testApplication {
            application { app.install(this) }
            // 先完成迁移/启动；仅本类克隆库的一列临时不可读，不破坏准入与日志表。
            startApplication()
            executeTestSql("alter table model_prices rename column input_price_per_million to finalization_unavailable_input_price")
            try {
                val response = client.send(key, requestId, stream = false)
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, body)
                assertTrue(body.contains("Hello!"), body)
                val log = awaitRequestLog()
                assertEquals(requestId, log.requestId)
                assertEquals(200, log.statusCode)
                assertNull(log.errorCode)
                assertNull(log.failureStage)
                assertNull(log.costAmount)
                assertNull(log.costCurrency)
                assertEquals(13L, log.totalTokens)
                assertEquals(1, log.upstreamAttempts)
                assertEquals(1, fake.requests.size)
                assertEquals(1, scalarInt("select count(*) from request_logs"))
                val usage = app.clientKeyQuotaService.currentUsage(keyId)
                assertEquals(13L, usage.dailyTokens)
                assertEquals(0, usage.dailyUsdCost.signum())
                assertEquals(0, usage.concurrentRequests)
                val metrics = client.get("/metrics").bodyAsText()
                assertTrue(metrics.contains("""gateway_request_settlement_failures_total{stage="pricing"} 1.0"""), metrics)
            } finally {
                executeTestSql("alter table model_prices rename column finalization_unavailable_input_price to input_price_per_million")
            }
        }
    }

    private suspend fun assertQuotaFailure(stream: Boolean, existingLedger: Boolean) {
        val primary = createChannel("finalization-quota-failure", "key-health")
        val backup = createChannelWithBaseUrl("finalization-quota-backup", "${fake.baseUrl}/ok/v1", priority = 1)
        exposeAsModel(MODEL, primary to UPSTREAM_MODEL, backup to UPSTREAM_MODEL)
        createPrice(primary, UPSTREAM_MODEL, input = "1", output = "2")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1, dailyTokens = 100, dailyUsdLimit = BigDecimal.ONE))
        val keyId = app.clientKeyRepository.list().single().id
        val initialTokens = if (existingLedger) 7L else 0L
        val initialUsd = if (existingLedger) BigDecimal("0.001") else BigDecimal.ZERO
        if (existingLedger) {
            app.clientKeyQuotaRepository.addUsage(keyId, dailyQuotaPeriod(System.currentTimeMillis()), initialTokens, initialUsd)
        }
        assertEquals(if (existingLedger) 1 else 0, scalarInt("select count(*) from client_key_quota_usage where client_key_id = ?", keyId))
        // 新 key 没有账本行时读额度必须合法；只让 INSERT/UPDATE 失败，不能在准入阶段就失败。
        val initialUsage = app.clientKeyQuotaService.currentUsage(keyId)
        assertEquals(initialTokens, initialUsage.dailyTokens)
        assertEquals(0, initialUsd.compareTo(initialUsage.dailyUsdCost))
        val requestId = UUID.randomUUID().toString()

        withRejectedQuotaWrites {
            testApplication {
                application { app.install(this) }
                coroutineScope {
                    val release = fake.holdChatRequests()
                    val pending = async { client.send(key, requestId, stream) }
                    try {
                        awaitCondition("upstream in flight with one client concurrency slot") {
                            fake.requests.size == 1 && app.clientKeyQuotaService.currentUsage(keyId).concurrentRequests == 1
                        }
                        release.complete(Unit)
                        val response = withTimeout(10_000) { pending.await() }
                        val body = response.bodyAsText()
                        assertEquals(HttpStatusCode.OK, response.status, body)
                        assertTrue(body.contains(if (stream) "[DONE]" else "Hello!"), body)
                        val log = awaitRequestLog()
                        assertSuccessfulPricedLog(log, requestId)
                        assertEquals(primary.id, log.channelId)
                        assertEquals(1, fake.requests.size, "a failed settlement must not send to the backup or replay the primary")
                        assertEquals(1, scalarInt("select count(*) from request_logs"))
                        assertSingleRejectedWrite()
                        assertQuotaFailureMetric(client)
                        val usage = app.clientKeyQuotaService.currentUsage(keyId)
                        assertEquals(initialTokens, usage.dailyTokens, "the failed transaction must not partially settle tokens")
                        assertEquals(0, initialUsd.compareTo(usage.dailyUsdCost), "the failed transaction must not partially settle USD")
                        assertEquals(0, usage.concurrentRequests)
                        assertTrue(app.requestLimitService.clientKeyOccupancy().isEmpty())
                    } finally {
                        release.complete(Unit)
                        pending.cancel()
                        fake.clearChatHold()
                    }
                }
            }
        }
    }

    private suspend fun HttpClient.send(key: String, requestId: String, stream: Boolean) = post("/v1/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer $key")
        header("X-Request-Id", requestId)
        contentType(ContentType.Application.Json)
        setBody("""{"model":"$MODEL","stream":$stream,"messages":[{"role":"user","content":"hi"}]}""")
    }

    private fun assertSuccessfulPricedLog(log: RequestLogRecord, requestId: String) {
        assertEquals(requestId, log.requestId)
        assertEquals(200, log.statusCode)
        assertNull(log.errorCode)
        assertNull(log.failureStage)
        assertNull(log.errorMessage)
        assertTrue(log.usageKnown)
        assertEquals(10L, log.inputTokens)
        assertEquals(3L, log.outputTokens)
        assertEquals(13L, log.totalTokens)
        assertEquals("USD", log.costCurrency)
        assertEquals(0, assertNotNull(log.costAmount).compareTo(BigDecimal("0.000016")))
        assertEquals(0, assertNotNull(log.inputPricePerMillion).compareTo(BigDecimal.ONE))
        assertEquals(0, assertNotNull(log.outputPricePerMillion).compareTo(BigDecimal("2")))
        assertEquals(1, log.upstreamAttempts)
        assertEquals(1, log.attempts.size)
    }

    private suspend fun assertQuotaFailureMetric(client: HttpClient) {
        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val metrics = response.bodyAsText()
        assertTrue(metrics.contains("""gateway_request_settlement_failures_total{stage="quota"} 1.0"""), metrics)
        assertTrue(metrics.contains("gateway_requests_total{"), "request metrics must also be finalized: $metrics")
    }

    private fun assertSingleRejectedWrite() {
        // nextval 不随失败事务回滚；40001 会诱发 Exposed 默认自动重试，因此能发现隐式重放。
        assertEquals(1, scalarInt("select case when is_called then last_value else 0 end from finalization_quota_write_attempts"))
    }

    private suspend fun withRejectedQuotaWrites(block: suspend () -> Unit) {
        try {
            executeTestSql(
                "create sequence finalization_quota_write_attempts",
                """
                    create function finalization_reject_quota_write() returns trigger language plpgsql as ${'$'}fault${'$'}
                    begin
                        -- 已有账本让 INSERT 进入 ON CONFLICT，实际在 UPDATE 阶段拒绝；新 key 则拒绝 INSERT。
                        if TG_OP = 'UPDATE' or not exists (
                            select 1 from client_key_quota_usage
                            where client_key_id = NEW.client_key_id and period = NEW.period
                        ) then
                            perform nextval('finalization_quota_write_attempts');
                            raise exception 'test quota settlement failure' using errcode = '40001';
                        end if;
                        return new;
                    end;
                    ${'$'}fault${'$'}
                """.trimIndent(),
                "create trigger finalization_reject_quota_write before insert or update on client_key_quota_usage for each row execute function finalization_reject_quota_write()",
            )
            block()
        } finally {
            executeTestSql(
                "drop trigger if exists finalization_reject_quota_write on client_key_quota_usage",
                "drop function if exists finalization_reject_quota_write()",
                "drop sequence if exists finalization_quota_write_attempts",
            )
        }
    }

    private fun executeTestSql(vararg statements: String) {
        // 不连容器管理库或模板库，也不接受环境里的真实数据库 URL。
        val databaseName = databaseUrl.substringBefore('?').substringAfterLast('/')
        require(databaseName.matches(Regex("prism_test_[0-9]+"))) { "fault injection requires this class's IntegrationPostgres clone" }
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 2
                statements.forEach { statement.execute(it) }
            }
        }
    }

    companion object {
        private const val MODEL = "finalization-chat"
        private const val UPSTREAM_MODEL = "gpt-4o-mini"
    }
}
