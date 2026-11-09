package dev.soven.llmgateway.integration

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelQuotaSettings
import dev.soven.llmgateway.domain.ChannelQuotaCredentialSource
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.domain.NewIqTestRun
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamProtocolOverrides
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.fake.FakeUpstream
import dev.soven.llmgateway.oauth.OAuthAccountDecision
import dev.soven.llmgateway.oauth.OAuthAccountDenyReason
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.ChannelKeyUpdate
import dev.soven.llmgateway.domain.IqVisionSettingsRecord
import dev.soven.llmgateway.domain.effective
import dev.soven.llmgateway.repo.IqVisionSettingsUpdate
import dev.soven.llmgateway.repo.KeyAutoDisableRepository
import dev.soven.llmgateway.repo.UpstreamRequestSettingsRepository
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import dev.soven.llmgateway.proxy.HeaderProfileVersionSource
import dev.soven.llmgateway.proxy.HtmlImageRenderer
import dev.soven.llmgateway.proxy.SvgRenderResult
import dev.soven.llmgateway.proxy.UpstreamHeaderCatalogService
import dev.soven.llmgateway.proxy.UpstreamHeaderProfiles
import dev.soven.llmgateway.resilience.KeyAttemptOutcome
import dev.soven.llmgateway.resilience.KeyAttemptSignal
import dev.soven.llmgateway.resilience.ChannelKeyDisableMode
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableAction
import dev.soven.llmgateway.resilience.KeyDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableRule
import dev.soven.llmgateway.resilience.ResolvedKeyDisablePolicy
import dev.soven.llmgateway.resilience.toKeyDisablePolicy
import dev.soven.llmgateway.shared.crypto.ClientKeyHasher
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.ChannelKeyRateLimitDecision
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.options
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.readUTF8Line
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.IOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-3")
class GeminiNativeIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `gemini 400 with INVALID_ARGUMENT does not trigger failover even if message has upstream error`() = runBlocking {
        val primary = createChannel("gemini-invalid-primary", "gemini-400-invalid-argument")
        val secondary = createChannel("gemini-invalid-secondary", "ok")
        exposeAsModel("gemini-invalid-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gemini-invalid-chat","stream":false,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(primary.id, log.channelId)
            assertEquals(400, log.statusCode)
            assertEquals(1, log.upstreamAttempts)

            val failedAttempt = log.attempts.single()
            assertEquals(primary.id, failedAttempt.channelId)
            assertEquals(400, failedAttempt.statusCode)
            assertFalse(failedAttempt.retryable)
        }
    }

    @Test
    @Tag("gemini")
    fun `Gemini channels accept converted chat requests`() = runBlocking {
        createChannelWithBaseUrl(
            "gemini-only",
            "${fake.baseUrl}/ok/v1beta",
            listOf(GatewayProtocol.GEMINI),
            mappingsOf("gemini-2.5-pro"),
            authType = "google",
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gemini-2.5-pro","stream":false,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Hello!", body["choices"]!!.jsonArray.single().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
            val upstream = fake.requests.single()
            assertEquals("/ok/v1beta/models/gemini-2.5-pro:generateContent", upstream.uri?.substringBefore('?'))
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertTrue(upstreamBody.containsKey("contents"))
            assertFalse(upstreamBody.containsKey("model"))
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.protocol)
            assertEquals(GatewayProtocol.GEMINI, log.upstreamProtocol)
        }
    }

    @Test
    @Tag("gemini")
    fun `Gemini requests convert to chat channels`() = runBlocking {
        createChannel("gemini-to-chat", "ok")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/gpt-4o-mini:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("STOP", body["candidates"]!!.jsonArray.single().jsonObject["finishReason"]!!.jsonPrimitive.content)
            assertEquals("Hello!", body["candidates"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            val upstream = fake.requests.single()
            assertEquals("/ok/v1/chat/completions", upstream.path)
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertEquals("gpt-4o-mini", upstreamBody["model"]!!.jsonPrimitive.content)
            assertTrue(upstreamBody["messages"]!!.jsonArray.isNotEmpty())
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.GEMINI, log.protocol)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.upstreamProtocol)
        }
    }

    @Test
    @Tag("gemini")
    fun `Gemini streaming request asks chat upstream to include usage`() = runBlocking {
        val channel = createChannel("gemini-to-chat-stream-usage", "ok")
        exposeAsModel("fast-gemini", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val sent = fake.requests.single()
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
            assertTrue(response.bodyAsText().contains("totalTokenCount"))
        }
    }

    @Test
    @Tag("gemini")
    fun geminiQueryCredentialsAreExcludedFromLogsAndUpstreamUrls() = runBlocking {
        createChannelWithBaseUrl(
            "gemini-query-logging", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI),
            modelMappings = mappingsOf("gemini-2.5-pro"), authType = "google",
        )
        val key = createClientKey()
        val rejectedKey = "query-client-secret-for-log-test"
        val queryTag = "private-query-tag"
        val events = ConcurrentLinkedQueue<ILoggingEvent>()
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                events.add(event)
            }
        }.apply { context = rootLogger.loggerContext; start() }
        rootLogger.addAppender(appender)
        try {
            testApplication {
                application { app.install(this) }
                for (action in listOf("generateContent", "streamGenerateContent")) {
                    val response = client.post("/v1beta/models/gemini-2.5-pro:$action?key=$key&tracking=$queryTag") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"contents":[]}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertFalse(response.bodyAsText().contains(key))
                }
                val rejected = client.get("/v1beta/models?key=$rejectedKey&tracking=$queryTag")
                assertEquals(HttpStatusCode.Unauthorized, rejected.status)
                assertFalse(rejected.bodyAsText().contains(rejectedKey))
                val malformed = client.post("/v1beta/models/gemini-2.5-pro:generateContent?key=$key&tracking=$queryTag") {
                    contentType(ContentType.Application.Json)
                    setBody("{")
                }
                assertEquals(HttpStatusCode.BadRequest, malformed.status)
                assertFalse(malformed.bodyAsText().contains(key))
                awaitRequestLogCount(2)
                val requestLogs = app.requestLogRepository.list(10).toString()
                assertFalse(requestLogs.contains(key))
                assertFalse(requestLogs.contains(queryTag))
            }
            val messages = events.map { it.formattedMessage }
            assertEquals(4, messages.count { it.contains(" - /v1beta/models") })
            val captured = events.joinToString("\n") { event ->
                event.formattedMessage + event.mdcPropertyMap.values.joinToString() +
                    (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
            }
            for (secret in listOf(key, rejectedKey, queryTag, "upstream-secret")) {
                assertFalse(captured.contains(secret), "日志不应包含凭据或查询参数")
            }
            assertEquals(2, fake.requests.size)
            fake.requests.forEach {
                val uri = assertNotNull(it.uri)
                assertFalse(uri.contains(key))
                assertFalse(uri.contains("key="))
                assertFalse(uri.contains(queryTag))
                assertEquals(listOf("upstream-secret"), it.requestHeaders["x-goog-api-key"])
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    @Tag("gemini")
    fun geminiNativeJsonUsesMappedUrlAndGoogleCredential() = runBlocking {
        createChannelWithBaseUrl(
            "gemini-native", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI),
            modelMappings = listOf(ChannelModelRecord("gemini-2.5-pro", "google/fast-gemini")), authType = "google",
            requestOverrides = UpstreamRequestOverrides(protocols = mapOf("gemini" to UpstreamProtocolOverrides(
                body = Json.parseToJsonElement("""{"generationConfig":{"temperature":0.3}}""").jsonObject,
            ))),
        )
        createChannel("chat-only", "ok")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val list = client.get("/v1beta/models") { header("x-goog-api-key", key) }
            val names = Json.parseToJsonElement(list.bodyAsText()).jsonObject["models"]!!.jsonArray
            assertEquals(1, names.size)
            assertEquals("models/google/fast-gemini", names.single().jsonObject["name"]!!.jsonPrimitive.content)
            val response = client.post("/v1beta/models/google/fast-gemini:generateContent?key=$key") {
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"generationConfig":{"thinkingConfig":{"thinkingLevel":"high"}}}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Hello!"))
            val sent = fake.requests.single()
            assertEquals("/ok/v1beta/models/gemini-2.5-pro:generateContent", sent.uri)
            assertEquals(listOf("upstream-secret"), sent.requestHeaders["x-goog-api-key"])
            assertNull(sent.authorization)
            assertTrue(sent.requestHeaders["user-agent"]!!.single().startsWith("google-genai-sdk/"))
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertFalse(body.containsKey("model"))
            assertFalse(body.containsKey("stream"))
            assertEquals("0.3", body["generationConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.GEMINI, log.protocol)
            assertEquals("google/fast-gemini", log.alias)
            assertEquals("gemini-2.5-pro", log.upstreamModel)
            assertEquals(10L, log.inputTokens)
            assertEquals(5L, log.outputTokens)
            assertEquals(2L, log.reasoningTokens)
            assertEquals(15L, log.totalTokens)
            assertEquals("high", log.reasoningEffort)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiSseKeepsNativeFramesAndUsageAfterFinishReason() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "gemini-sse", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI),
            modelMappings = mappingsOf("gemini-2.5-pro"),
            requestOverrides = UpstreamRequestOverrides(protocols = mapOf("gemini" to UpstreamProtocolOverrides(headerProfile = "antigravity"))),
        )
        exposeAsModel("fast-gemini", channel to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/models/fast-gemini:streamGenerateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("finishReason"))
            assertTrue(text.contains("totalTokenCount"))
            assertFalse(text.contains("[DONE]"))
            val sent = fake.requests.single()
            assertTrue(sent.uri!!.endsWith(":streamGenerateContent?alt=sse"))
            assertTrue(sent.requestHeaders["user-agent"]!!.single().startsWith("antigravity/"))
            assertNull(sent.requestHeaders["originator"])
            assertEquals("Bearer upstream-secret", sent.authorization)
            val log = awaitRequestLog()
            assertEquals(15L, log.totalTokens)
            assertEquals(5L, log.outputTokens)
            assertNotNull(log.firstTokenMs)
            assertNull(log.errorCode)
            assertTrue(log.stream == true)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiServerErrorBeforeContentCanFailOver() = runBlocking {
        val bad = createChannelWithBaseUrl("a-error", fake.baseUrl + "/sse-error/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        val good = createChannelWithBaseUrl("b-good", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        exposeAsModel("fast-gemini", bad to "gemini-2.5-pro", good to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("Hello!"))
            assertFalse(text.contains("upstream down"))
            assertEquals(2, fake.requests.size)
            assertEquals(2, awaitRequestLog().upstreamAttempts)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiErrorFrameAfterContentIsLoggedAsUpstreamFailureWithoutFailover() = runBlocking {
        val bad = createChannelWithBaseUrl(
            "a-error", fake.baseUrl + "/sse-error-after-content/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"),
        )
        val backup = createChannelWithBaseUrl("b-backup", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        exposeAsModel("fast-gemini", bad to "gemini-2.5-pro", backup to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Hello!"))
            assertTrue(body.contains("INTERNAL"))
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals("gemini_failed", log.errorCode)
            assertEquals("上游响应失败", log.errorMessage)
            assertEquals(FailureStage.STREAMING, log.failureStage)
            assertEquals(1, log.upstreamAttempts)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiUnexpectedEofAfterContentIsLoggedWithoutFailover() = runBlocking {
        val bad = createChannelWithBaseUrl("a-eof", fake.baseUrl + "/no-terminal/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        val backup = createChannelWithBaseUrl("b-backup", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        exposeAsModel("fast-gemini", bad to "gemini-2.5-pro", backup to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertTrue(response.bodyAsText().contains("Hello!"))
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals("stream_interrupted", log.errorCode)
            assertNotNull(log.firstTokenMs)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiNativeErrorsAndProtocolFilteringRemainNative() = runBlocking {
        createChannelWithBaseUrl("native-error", fake.baseUrl + "/429/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        createChannel("chat-channel", "ok")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val unauth = client.post("/v1beta/models/gemini-2.5-pro:generateContent") {
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, unauth.status)
            assertTrue(unauth.bodyAsText().contains("UNAUTHENTICATED"))
            val incompatible = client.post("/v1beta/models/m1:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, incompatible.status)
            val converted = Json.parseToJsonElement(incompatible.bodyAsText()).jsonObject
            assertTrue(converted.containsKey("candidates"), incompatible.bodyAsText())
            val rateLimited = client.post("/v1beta/models/gemini-2.5-pro:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, rateLimited.status)
            assertEquals("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"rate limited"}}""", rateLimited.bodyAsText())
            assertEquals(2, fake.requests.size)
        }
    }
}
