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
@Tag("shard-1")
class RequestAuthenticationIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `rejects request without client key`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("invalid_api_key"))
        }
    }

    @Test
    fun xApiKeyAuthenticationIsRestrictedToAnthropicMessages() = runBlocking {
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `CORS exposes trace correlation headers`() = runBlocking {
        app.close()
        app = GatewayApplication(config(corsOrigins = listOf("https://console.example.com")))
        testApplication {
            application { app.install(this) }
            val response = client.options("/v1/chat/completions") {
                header(HttpHeaders.Origin, "https://console.example.com")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type,traceparent,x-prompt-cache-key")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://console.example.com", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertTrue(response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().contains("traceparent"))
            assertTrue(response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().contains("x-prompt-cache-key", ignoreCase = true))

            val actual = client.post("/v1/chat/completions") {
                header(HttpHeaders.Origin, "https://console.example.com")
                header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"missing","messages":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, actual.status)
            assertEquals("https://console.example.com", actual.headers[HttpHeaders.AccessControlAllowOrigin])
            val exposed = actual.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty()
            assertTrue(exposed.contains("traceparent"))
            assertTrue(exposed.contains("X-Trace-Id"))
        }
    }

    @Test
    fun `propagates and records W3C trace context`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeUnderCanonicalId("fast-chat", listOf(channel.id to "gpt-4o-mini"))
        val key = createClientKey()
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val incomingParent = "00f067aa0ba902b7"

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                header("traceparent", "00-$traceId-$incomingParent-01")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(traceId, response.headers["X-Trace-Id"])
            assertTrue(response.headers["traceparent"]?.startsWith("00-$traceId-") == true)
            val log = awaitRequestLog()
            assertEquals(traceId, log.traceId)
            val upstream = fake.requests.single()
            assertTrue(upstream.traceparent?.startsWith("00-$traceId-") == true)
            assertNotEquals(incomingParent, upstream.traceparent?.split("-")?.getOrNull(2))
        }
    }

    @Test
    fun `streams chunks and records usage`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("Hel"))
            assertTrue(body.contains("lo"), "BODY=[$body]")
            assertTrue(body.contains("[DONE]"))
            assertTrue(body.endsWith("data: [DONE]\n\n"), "BODY=[$body]")

            val log = awaitRequestLog()
            assertEquals(10L, log.inputTokens)
            assertEquals(3L, log.outputTokens)
            assertEquals(13L, log.totalTokens)
            assertTrue(log.usageKnown)
            assertNotNull(log.firstTokenMs)
            assertEquals("gpt-4o-mini", log.upstreamModel)
            assertEquals(channel.id, log.channelId)
            assertEquals("openai", log.channelName)
            assertEquals("test-key", log.clientKeyName)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.protocol)
        }

        val upstreamRequest = fake.requests.single()
        assertTrue(upstreamRequest.body.contains("\"model\":\"gpt-4o-mini\""))
        assertEquals("Bearer upstream-secret", upstreamRequest.authorization)
    }
}
