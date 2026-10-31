package dev.soven.llmgateway.api

import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.fake.FakeUpstream
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.security.ClientKeyHasher
import io.ktor.client.HttpClient
import io.ktor.client.request.header
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private suspend fun createChannel(
        modelId: String,
        baseUrl: String,
        invalidCredential: Boolean = false,
        protocol: GatewayProtocol = GatewayProtocol.CHAT_COMPLETIONS,
        priority: Int = 1,
    ) {
        val credential = app.credentialCipher.encrypt("upstream-secret")
        app.channelRepository.create(
            name = "$priority-$modelId",
            baseUrl = baseUrl,
            keys = listOf(NewChannelKey(
                "test-key",
                if (invalidCredential) credential.copy(ciphertext = "invalid-base64") else credential,
                models = listOf(modelId),
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
