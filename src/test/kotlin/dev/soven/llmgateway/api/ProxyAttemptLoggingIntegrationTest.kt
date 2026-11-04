package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.fake.FakeUpstream
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.report.ChannelHealthReport
import dev.soven.llmgateway.report.ChannelHealthDetail
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.security.ClientKeyHasher
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyAttemptLoggingIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var fake: FakeUpstream
    private lateinit var app: GatewayApplication
    private lateinit var clientKey: String

    @BeforeAll
    fun startInfrastructure() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        fake = FakeUpstream().apply { start() }
    }

    @AfterAll
    fun stopInfrastructure() {
        fake.stop()
        postgres.stop()
    }

    @BeforeEach
    fun setUp() = runBlocking {
        fake.requests.clear()
        val config = GatewayConfig.fromEnv(mapOf(
            "DATABASE_URL" to postgres.jdbcUrl,
            "DATABASE_USER" to postgres.username,
            "DATABASE_PASSWORD" to postgres.password,
            "GATEWAY_MASTER_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            "ADMIN_PASSWORD" to "test-password",
            "CONNECT_TIMEOUT_MS" to "1000",
            "TOTAL_TIMEOUT_MS" to "5000",
        )).copy(retention = RetentionPolicy(cleanupEnabled = false), upstreamHeaderRefreshEnabled = false)
        app = GatewayApplication(config)
        app.databaseFactory.migrate()
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create("attempt-log-test", generated.hash, generated.prefix, true, null)
        clientKey = generated.raw
    }

    @AfterEach
    fun tearDown() {
        if (::app.isInitialized) app.close()
    }

    @Test
    fun `credential decryption failures do not create upstream attempts`() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (stream in listOf(false, true)) {
                val modelId = "invalid-credential-$stream"
                createChannel(modelId, "${fake.baseUrl}/ok/v1", invalidCredential = true)
                val requestId = UUID.randomUUID().toString()
                val response = client.sendRequest(modelId, stream, requestId)
                assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
                val log = awaitRequestLog(requestId)
                assertEquals("upstream_credential_invalid", log.errorCode)
                assertEquals(0, log.upstreamAttempts)
                assertNull(log.upstreamProtocol)
                assertTrue(log.attempts.isEmpty())
            }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `connection failures still create upstream attempts without a response`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        testApplication {
            application { app.install(this) }
            for (stream in listOf(false, true)) {
                val modelId = "connection-failure-$stream"
                createChannel(modelId, "http://127.0.0.1:$port/v1")
                val requestId = UUID.randomUUID().toString()
                val response = client.sendRequest(modelId, stream, requestId)
                assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
                val log = awaitRequestLog(requestId)
                assertEquals(1, log.upstreamAttempts)
                val attempt = log.attempts.single()
                assertEquals("upstream_unreachable", attempt.errorCode)
                assertNull(attempt.statusCode)
            }
        }
    }

    @Test
    fun `records upstream reported model for non-stream and stream attempts`() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (stream in listOf(false, true)) {
                val modelId = "requested-model-$stream"
                createChannel(modelId, "${fake.baseUrl}/reported-model/v1")
                val requestId = UUID.randomUUID().toString()
                val response = client.sendRequest(modelId, stream, requestId)
                assertEquals(HttpStatusCode.OK, response.status)
                val attempt = awaitRequestLog(requestId).attempts.single()
                assertEquals(modelId, attempt.upstreamModel)
                assertEquals("reported-by-upstream", attempt.upstreamReportedModel)
                assertEquals("reported-by-upstream", awaitRequestLog(requestId).toResponse().attempts.single().upstreamReportedModel)
            }
        }
    }

    @Test
    fun `malformed stream metadata does not interrupt delivery or erase reported model`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val modelId = "reported-model-malformed"
            createChannel(modelId, "${fake.baseUrl}/reported-model-malformed/v1")
            val requestId = UUID.randomUUID().toString()
            val response = client.sendRequest(modelId, true, requestId)
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("{not-json}"))
            assertTrue(body.contains("data: []"))
            assertTrue(body.contains("[DONE]"))
            val log = awaitRequestLog(requestId)
            assertNull(log.errorCode)
            assertEquals("reported-by-upstream", log.attempts.single().upstreamReportedModel)
        }
    }

    @Test
    fun `stream errors retain HTTP and error status when retrying successfully`() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (protocol in listOf(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI)) {
                val modelId = "retry-error-${protocol.value}"
                val version = if (protocol == GatewayProtocol.GEMINI) "v1beta" else "v1"
                createChannel(modelId, "${fake.baseUrl}/sse-rate-limit/$version", protocol = protocol)
                createChannel(modelId, "${fake.baseUrl}/ok/$version", protocol = protocol, priority = 2)
                val requestId = UUID.randomUUID().toString()
                val response = client.sendStreamRequest(modelId, protocol, requestId)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val log = awaitRequestLog(requestId)
                assertEquals(2, log.upstreamAttempts)
                assertEquals(2, log.attempts.size)
                val failed = log.attempts.first()
                assertEquals(200, failed.statusCode)
                assertEquals(429, failed.upstreamErrorStatusCode)
                assertEquals(if (protocol == GatewayProtocol.MESSAGES) "rate_limit_error" else "429", failed.upstreamErrorCode)
                assertEquals("上游限流", failed.errorMessage)
                assertEquals(429, log.toResponse().attempts.first().upstreamErrorStatusCode)
                val succeeded = log.attempts.last()
                assertEquals(200, succeeded.statusCode)
                assertNull(succeeded.upstreamErrorStatusCode)
                assertNull(succeeded.errorCode)
            }
        }
    }

    @Test
    fun `stream errors after content retain their status without retrying`() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (protocol in listOf(GatewayProtocol.MESSAGES, GatewayProtocol.GEMINI)) {
                val modelId = "committed-error-${protocol.value}"
                val version = if (protocol == GatewayProtocol.GEMINI) "v1beta" else "v1"
                createChannel(modelId, "${fake.baseUrl}/sse-rate-limit-after-content/$version", protocol = protocol)
                createChannel(modelId, "${fake.baseUrl}/ok/$version", protocol = protocol, priority = 2)
                val requestId = UUID.randomUUID().toString()
                val requestCount = fake.requests.size
                val response = client.sendStreamRequest(modelId, protocol, requestId)
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("rate limited"))
                assertEquals(requestCount + 1, fake.requests.size)
                val log = awaitRequestLog(requestId)
                assertEquals(1, log.upstreamAttempts)
                val attempt = log.attempts.single()
                assertEquals(200, attempt.statusCode)
                assertEquals(429, attempt.upstreamErrorStatusCode)
                assertEquals(if (protocol == GatewayProtocol.MESSAGES) "messages_failed" else "gemini_failed", attempt.errorCode)
                assertEquals("上游限流", attempt.errorMessage)
                assertTrue(attempt.committed)
            }
        }
    }

    @Test
    fun `health endpoints enforce authentication validate windows and observe real proxy requests`() = runBlocking {
        val modelId = "health-live-request"
        createChannel(modelId, "${fake.baseUrl}/ok/v1")
        val channel = app.channelRepository.list().single { it.name == "1-$modelId" }
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/channel-health").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/channels/${channel.id}/health").status)
            suspend fun healthGet(path: String) = client.get(path) {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(HttpStatusCode.BadRequest, healthGet("/admin/api/channel-health?window=90d").status)
            assertEquals(HttpStatusCode.BadRequest, healthGet("/admin/api/channels/${channel.id}/health?window=90d").status)
            assertEquals(HttpStatusCode.NotFound, healthGet("/admin/api/channels/missing/health").status)
            val requestId = UUID.randomUUID().toString()
            val response = client.sendRequest(modelId, true, requestId)
            assertEquals(HttpStatusCode.OK, response.status)
            response.bodyAsText()
            awaitRequestLog(requestId)
            val overview = healthGet("/admin/api/channel-health?window=24h")
            assertEquals(HttpStatusCode.OK, overview.status)
            assertEquals("no-store", overview.headers["Cache-Control"])
            val report = Json.decodeFromString<ChannelHealthReport>(overview.bodyAsText())
            val summary = report.channels.single { it.channelId == channel.id }
            assertEquals(1, summary.metrics.attempts)
            assertEquals(1, summary.metrics.successes)
            assertEquals("UNKNOWN", summary.state)
            val detailResponse = healthGet("/admin/api/channels/${channel.id}/health?window=7d")
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            assertEquals("no-store", detailResponse.headers["Cache-Control"])
            val detail = Json.decodeFromString<ChannelHealthDetail>(detailResponse.bodyAsText())
            assertEquals(7, detail.trend.size)
            assertEquals(modelId, detail.byModel.single().id)
            assertEquals("ACTIVE", detail.availability.single().state)
            assertTrue(!detailResponse.bodyAsText().contains("upstream-secret"))
        }
    }

    @Test
    fun `channel health reports cooldown deadline instead of stale key disable deadline`() = runBlocking {
        val modelId = "health-cooldown-deadline"
        createChannel(modelId, "${fake.baseUrl}/ok/v1")
        val channel = app.channelRepository.list().single { it.name == "1-$modelId" }
        val key = channel.keys.single()
        val permit = checkNotNull(app.candidateCooldown.acquire(channel.id, key.id, modelId))
        app.candidateCooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_keys set state = 'AUTO_TEMPORARY', auto_disable_until = 1 where id = ?").use { statement ->
                statement.setString(1, key.id)
                statement.executeUpdate()
            }
        }
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels/${channel.id}/health") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val detail = Json.decodeFromString<ChannelHealthDetail>(response.bodyAsText())
            val availability = detail.availability.single()
            assertEquals("COOLDOWN", availability.state)
            assertTrue(availability.until != null && availability.until > System.currentTimeMillis())
            assertTrue(availability.until != 1L)
        }
    }

    @Test
    fun `channel health availability deduplicates repeated models on a key`() = runBlocking {
        val modelId = "health-duplicate-model"
        createChannel(modelId, "${fake.baseUrl}/ok/v1", models = listOf(modelId, modelId))
        val channel = app.channelRepository.list().single { it.name == "1-$modelId" }
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels/${channel.id}/health") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val detail = Json.decodeFromString<ChannelHealthDetail>(response.bodyAsText())
            assertEquals(listOf(modelId), detail.availability.map { it.model })
        }
    }

    @Test
    fun `cooldown skipped candidates are logged without health attempts`() = runBlocking {
        val modelId = "cooldown-skip-log"
        createChannel(modelId, "${fake.baseUrl}/ok/v1", priority = 1)
        createChannel(modelId, "${fake.baseUrl}/ok/v1", priority = 2)
        val cooling = app.channelRepository.list().single { it.name == "1-$modelId" }
        val key = cooling.keys.single()
        val permit = checkNotNull(app.candidateCooldown.acquire(cooling.id, key.id, modelId))
        app.candidateCooldown.recordFailure(permit, FailureKind.SERVER_ERROR)
        testApplication {
            application { app.install(this) }
            val requestId = UUID.randomUUID().toString()
            val response = client.sendRequest(modelId, false, requestId)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val log = awaitRequestLog(requestId)
            assertEquals(1, log.upstreamAttempts)
            assertEquals(2, log.attempts.size)
            val skipped = log.attempts.first()
            assertTrue(skipped.skipped)
            assertEquals("cooldown", skipped.skipReason)
            assertEquals(cooling.id, skipped.channelId)
            assertNull(skipped.statusCode)
            val attempted = log.attempts.last()
            assertFalse(attempted.skipped)
            assertEquals(200, attempted.statusCode)
            assertTrue(log.toResponse().attempts.first().skipped)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("select count(*) from request_attempts where log_id = ?").use { statement ->
                    statement.setLong(1, log.id)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `fail-open cooling candidate is attempted instead of logged as skipped`() = runBlocking {
        val modelId = "cooldown-fail-open"
        createChannel(modelId, "${fake.baseUrl}/ok/v1")
        val cooling = app.channelRepository.list().single { it.name == "1-$modelId" }
        val key = cooling.keys.single()
        val permit = checkNotNull(app.candidateCooldown.acquire(cooling.id, key.id, modelId))
        app.candidateCooldown.recordFailure(permit, FailureKind.SERVER_ERROR)
        testApplication {
            application { app.install(this) }
            val requestId = UUID.randomUUID().toString()
            val response = client.sendRequest(modelId, false, requestId)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val log = awaitRequestLog(requestId)
            assertEquals(1, log.upstreamAttempts)
            val attempt = log.attempts.single()
            assertFalse(attempt.skipped)
            assertEquals(cooling.id, attempt.channelId)
        }
    }

    @Test
    fun oversizedProtocolLinesBeforeContentAreNotRetried() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (inbound in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES)) {
                val modelId = "oversized-before-" + inbound.value
                val upstream = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) GatewayProtocol.RESPONSES else GatewayProtocol.CHAT_COMPLETIONS
                createChannel(modelId, fake.baseUrl + "/oversized-line-before-content/v1", protocol = upstream)
                createChannel(modelId, fake.baseUrl + "/ok/v1", protocol = upstream, priority = 2)
                val requestId = UUID.randomUUID().toString()
                val count = fake.requests.size
                val response = client.sendProtocolStreamRequest(modelId, inbound, requestId)
                assertEquals(HttpStatusCode.BadGateway, response.status)
                val body = response.bodyAsText()
                assertFalse(body.contains("private-upstream-payload"))
                assertEquals(count + 1, fake.requests.size)
                val log = awaitRequestLog(requestId)
                assertEquals("upstream_event_too_large", log.errorCode)
                val attempt = log.attempts.single()
                assertEquals(200, attempt.statusCode)
                assertEquals("upstream_event_too_large", attempt.errorCode)
                assertFalse(attempt.retryable)
                assertFalse(attempt.committed)
            }
        }
    }

    @Test
    fun oversizedProtocolLinesAfterContentEmitFailureFramesAndKeepAttemptDiagnostics() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (inbound in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES)) {
                val modelId = "oversized-after-" + inbound.value
                val upstream = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) GatewayProtocol.RESPONSES else GatewayProtocol.CHAT_COMPLETIONS
                createChannel(modelId, fake.baseUrl + "/oversized-line-after-content/v1", protocol = upstream)
                createChannel(modelId, fake.baseUrl + "/ok/v1", protocol = upstream, priority = 2)
                val requestId = UUID.randomUUID().toString()
                val count = fake.requests.size
                val response = client.sendProtocolStreamRequest(modelId, inbound, requestId)
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("Hel"), body)
                assertFalse(body.contains("private-upstream-payload"))
                assertFalse(body.contains("response.completed"))
                assertFalse(body.contains("message_stop"))
                val terminal = when (inbound) {
                    GatewayProtocol.RESPONSES -> "event: response.failed"
                    GatewayProtocol.MESSAGES -> "event: error"
                    else -> "data: [DONE]"
                }
                assertEquals(1, body.lineSequence().count { it == terminal }, body)
                assertEquals(count + 1, fake.requests.size)
                val log = awaitRequestLog(requestId)
                assertEquals("stream_interrupted", log.errorCode)
                val attempt = log.attempts.single()
                assertEquals(200, attempt.statusCode)
                assertEquals("upstream_event_too_large", attempt.errorCode)
                assertFalse(attempt.retryable)
                assertTrue(attempt.committed)
            }
        }
    }

    @Test
    fun converterFailuresAreNotReportedAsSuccessfulUpstreamAttempts() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (inbound in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES, GatewayProtocol.RESPONSES)) {
                for (scenario in listOf("malformed", "malformed-after-content", "malformed-at-eof")) {
                    val modelId = scenario + "-converted-" + inbound.value
                    val upstream = if (inbound == GatewayProtocol.CHAT_COMPLETIONS) GatewayProtocol.RESPONSES else GatewayProtocol.CHAT_COMPLETIONS
                    createChannel(modelId, fake.baseUrl + "/" + scenario + "/v1", protocol = upstream)
                    createChannel(modelId, fake.baseUrl + "/ok/v1", protocol = upstream, priority = 2)
                    val requestId = UUID.randomUUID().toString()
                    val count = fake.requests.size
                    val response = client.sendProtocolStreamRequest(modelId, inbound, requestId)
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsText()
                    assertFalse(body.contains("response.completed"), body)
                    assertFalse(body.contains("message_stop"), body)
                    val terminal = when (inbound) {
                        GatewayProtocol.RESPONSES -> "event: response.failed"
                        GatewayProtocol.MESSAGES -> "event: error"
                        else -> "data: [DONE]"
                    }
                    assertEquals(1, body.lineSequence().count { it == terminal }, body)
                    assertEquals(count + 1, fake.requests.size)
                    val log = awaitRequestLog(requestId)
                    assertEquals("protocol_conversion_failed", log.errorCode)
                    assertEquals("上游流无法转换为调用方协议", log.errorMessage)
                    val attempt = log.attempts.single()
                    assertEquals(200, attempt.statusCode)
                    assertEquals("protocol_conversion_failed", attempt.errorCode)
                    assertTrue(attempt.committed)
                    assertFalse(attempt.retryable)
                }
            }
        }
    }

    @Test
    fun convertedMessagesStreamErrorsRetainUpstreamDiagnostics() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (inbound in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES)) {
                for (scenario in listOf("sse-rate-limit", "sse-rate-limit-after-content")) {
                    val modelId = "converted-messages-error-${inbound.value}-$scenario"
                    createChannel(modelId, "${fake.baseUrl}/$scenario/v1", protocol = GatewayProtocol.MESSAGES)
                    if (scenario == "sse-rate-limit-after-content") {
                        createChannel(modelId, "${fake.baseUrl}/ok/v1", protocol = GatewayProtocol.MESSAGES, priority = 2)
                    }
                    val requestId = UUID.randomUUID().toString()
                    val requestCount = fake.requests.size
                    val response = client.sendProtocolStreamRequest(modelId, inbound, requestId)
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsText()
                    assertTrue(body.contains("rate limited"), body)
                    assertFalse(body.contains("response.completed"), body)
                    assertEquals(requestCount + 1, fake.requests.size)
                    val log = awaitRequestLog(requestId)
                    assertEquals("messages_failed", log.errorCode)
                    assertTrue(log.upstreamErrorBody?.contains("rate limited") == true)
                    val attempt = log.attempts.single()
                    assertEquals(200, attempt.statusCode)
                    assertEquals(429, attempt.upstreamErrorStatusCode)
                    assertEquals("messages_failed", attempt.errorCode)
                    assertEquals("rate_limit_error", attempt.upstreamErrorCode)
                    assertEquals("上游限流", attempt.errorMessage)
                    assertTrue(attempt.committed)
                    assertFalse(attempt.retryable)
                }
            }
        }
    }

    @Test
    fun convertedResponsesStreamErrorsRetainUpstreamDiagnostics() = runBlocking {
        testApplication {
            application { app.install(this) }
            for (inbound in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES)) {
                val modelId = "converted-responses-error-${inbound.value}"
                createChannel(modelId, "${fake.baseUrl}/responses-failed/v1", protocol = GatewayProtocol.RESPONSES)
                createChannel(modelId, "${fake.baseUrl}/ok/v1", protocol = GatewayProtocol.RESPONSES, priority = 2)
                val requestId = UUID.randomUUID().toString()
                val requestCount = fake.requests.size
                val response = client.sendProtocolStreamRequest(modelId, inbound, requestId)
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("upstream failed"), body)
                assertFalse(body.contains("message_stop"), body)
                assertEquals(requestCount + 1, fake.requests.size)
                val log = awaitRequestLog(requestId)
                assertEquals("responses_failed", log.errorCode)
                assertTrue(log.upstreamErrorBody?.contains("upstream failed") == true)
                val attempt = log.attempts.single()
                assertEquals(200, attempt.statusCode)
                assertEquals("responses_failed", attempt.errorCode)
                assertTrue(attempt.committed)
                assertFalse(attempt.retryable)
            }
        }
    }

    private suspend fun HttpClient.sendProtocolStreamRequest(modelId: String, protocol: GatewayProtocol, requestId: String) =
        post(when (protocol) {
            GatewayProtocol.RESPONSES -> "/v1/responses"
            GatewayProtocol.MESSAGES -> "/v1/messages"
            else -> "/v1/chat/completions"
        }) {
            header(HttpHeaders.Authorization, "Bearer " + clientKey)
            header("X-Request-Id", requestId)
            contentType(ContentType.Application.Json)
            val input = if (protocol == GatewayProtocol.RESPONSES) "input" else "messages"
            setBody("""{"model":"$modelId","$input":[],"stream":true}""")
        }

    private suspend fun createChannel(
        modelId: String,
        baseUrl: String,
        invalidCredential: Boolean = false,
        protocol: GatewayProtocol = GatewayProtocol.CHAT_COMPLETIONS,
        priority: Int = 1,
        models: List<String> = listOf(modelId),
    ) {
        val credential = app.credentialCipher.encrypt("upstream-secret")
        app.channelRepository.create(
            name = "$priority-$modelId",
            baseUrl = baseUrl,
            keys = listOf(NewChannelKey(
                "test-key",
                if (invalidCredential) credential.copy(ciphertext = "invalid-base64") else credential,
                models = models,
            )),
            authType = "bearer",
            protocols = listOf(protocol),
            enabled = true,
            modelMappings = listOf(ChannelModelRecord(modelId)),
            priority = priority,
        )
    }

    private suspend fun HttpClient.sendRequest(modelId: String, stream: Boolean, requestId: String) =
        post("/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $clientKey")
            header("X-Request-Id", requestId)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"$modelId","messages":[],"stream":$stream}""")
        }

    private suspend fun awaitRequestLog(requestId: String): RequestLogRecord {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            app.requestLogRepository.list(20).firstOrNull { it.requestId == requestId }?.let { return it }
            delay(50)
        }
        error("request log not written for $requestId")
    }

    private suspend fun HttpClient.sendStreamRequest(modelId: String, protocol: GatewayProtocol, requestId: String) =
        post(if (protocol == GatewayProtocol.GEMINI) "/v1beta/models/$modelId:streamGenerateContent" else "/v1/messages") {
            header(HttpHeaders.Authorization, "Bearer $clientKey")
            header("X-Request-Id", requestId)
            contentType(ContentType.Application.Json)
            setBody(if (protocol == GatewayProtocol.GEMINI) """{"contents":[]}""" else """{"model":"$modelId","messages":[],"max_tokens":8,"stream":true}""")
        }
}
