package dev.soven.llmgateway

import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.fake.FakeUpstream
import dev.soven.llmgateway.repo.AliasWithCandidates
import dev.soven.llmgateway.repo.NewCandidate
import dev.soven.llmgateway.security.ClientKeyHasher
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.options
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var fake: FakeUpstream
    private lateinit var app: GatewayApplication

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
    fun setUp() {
        fake.requests.clear()
        fake.cancellations.set(0)
        fake.failingModels.clear()
        app = GatewayApplication(config())
        app.databaseFactory.migrate()
        truncateAll()
    }

    @AfterEach
    fun tearDown() {
        if (::app.isInitialized) app.close()
    }

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
                header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type,traceparent")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://console.example.com", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertTrue(response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().contains("traceparent"))

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
        createAlias("fast-chat", channel to "gpt-4o-mini")
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
        createAlias("fast-chat", channel to "gpt-4o-mini")
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

    @Test
    fun `falls back to next candidate before first token`() = runBlocking {
        val primary = createChannel("primary", "500")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("[DONE]"))
            assertEquals(2, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
            assertEquals(2, log.upstreamAttempts)
            assertTrue(app.metrics.scrape().contains("gateway_upstream_retries_total"))
        }
    }

    @Test
    fun `cools down unhealthy channel and skips it on later requests`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val primary = createChannel("primary", "500")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            val firstLog = awaitRequestLog()
            assertEquals(2, firstLog.upstreamAttempts)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)

            val scenarios = fake.requests.map { it.scenario }
            assertEquals(listOf("500", "ok", "ok"), scenarios)
            assertEquals(1, app.requestLogRepository.list(10).first().upstreamAttempts)
            val metrics = app.metrics.scrape()
            assertTrue(metrics.contains("gateway_upstream_cooldown_started_total"))
            assertTrue(metrics.contains("gateway_upstream_cooldown_skips_total"))
        }
    }

    @Test
    fun `direct routing prefers channel priority and skips cooled high priority`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val preferred = createChannelWithBaseUrl("preferred", "${fake.baseUrl}/500/v1", priority = 1)
        val backup = createChannelWithBaseUrl("backup", "${fake.baseUrl}/ok/v1", priority = 2)
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }

            // 高优先级渠道 500：当次请求内故障转移到低优先级渠道，高优先级进入冷却
            assertEquals(HttpStatusCode.OK, send().status)
            val firstLog = awaitRequestLog()
            assertEquals(backup.id, firstLog.channelId)
            assertEquals(2, firstLog.upstreamAttempts)

            // 后续请求预分拣直接跳过冷却中的高优先级渠道
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            val secondLog = app.requestLogRepository.list(10).first()
            assertEquals(backup.id, secondLog.channelId)
            assertEquals(1, secondLog.upstreamAttempts)
            assertEquals(listOf("500", "ok", "ok"), fake.requests.map { it.scenario })
        }
    }

    @Test
    fun `direct routing spreads same-priority channels`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val first = createChannelWithBaseUrl("first", "${fake.baseUrl}/ok/v1")
        val second = createChannelWithBaseUrl("second", "${fake.baseUrl}/ok/v1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            val logs = app.requestLogRepository.list(10)
            assertEquals(2, logs.size)
            assertEquals(setOf(first.id, second.id), logs.map { it.channelId }.toSet())
            logs.forEach { assertEquals(1, it.upstreamAttempts) }
        }
    }

    @Test
    fun `channel priority defaults to one and validates one to one thousand`() = runBlocking {
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val created = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"prio-default","baseUrl":"${fake.baseUrl}/ok/v1","credential":"sk-test"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            assertTrue(created.bodyAsText().contains("\"priority\":1"), created.bodyAsText())

            val upper = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"prio-upper","baseUrl":"${fake.baseUrl}/ok/v1","credential":"sk-test","priority":1000}""")
            }
            assertEquals(HttpStatusCode.Created, upper.status)

            val rejected = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"prio-invalid","baseUrl":"${fake.baseUrl}/ok/v1","credential":"sk-test","priority":0}""")
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertTrue(rejected.bodyAsText().contains("invalid_channel"))
        }
    }

    @Test
    fun `fail-open retries cooled candidates when all are blocked`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val primary = createChannel("primary", "500")
        createAlias("fast-chat", primary to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.BadGateway, send().status)
            assertEquals(1, awaitRequestLog().upstreamAttempts)

            // 唯一候选已冷却：fail-open 照常尝试，返回真实上游错误而不是 503
            val second = send()
            assertEquals(HttpStatusCode.BadGateway, second.status)
            assertTrue(!second.bodyAsText().contains("upstream_circuit_open"))
            assertEquals(2, fake.requests.size)

            awaitRequestLogCount(2)
            assertEquals(1, app.requestLogRepository.list(10).first().upstreamAttempts)
        }
    }

    @Test
    fun `spreads requests across same-tier candidates`() = runBlocking {
        val primary = createChannel("primary", "ok")
        val secondary = createChannel("secondary", "ok")
        createTieredAlias("fast-chat", Triple(primary, "m1", 0), Triple(secondary, "m2", 0))
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            val logs = app.requestLogRepository.list(10)
            assertEquals(2, logs.size)
            // 同档两个候选各承接一次请求
            assertEquals(setOf(primary.id, secondary.id), logs.map { it.channelId }.toSet())
            logs.forEach { assertEquals(1, it.upstreamAttempts) }
        }
    }

    @Test
    fun `shared public id falls back across aliases and recovers after candidate cooldown`() = runBlocking {
        app.close()
        app = GatewayApplication(
            config(
                maxAttempts = 2,
                cooldownRateLimitDefaultMs = 300,
                cooldownRateLimitMaxMs = 300,
            ),
        )
        val channel = createChannelWithBaseUrl(
            name = "shared-alias-channel",
            baseUrl = "${fake.baseUrl}/model-route/v1",
            modelMappings = listOf(
                ChannelModelRecord("alias-fail", "shared-model", "manual"),
                ChannelModelRecord("alias-ok", "shared-model", "manual"),
            ),
            priority = 1,
        )
        fake.failingModels.add("alias-fail")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"shared-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            val firstLog = awaitRequestLog()
            assertEquals(channel.id, firstLog.channelId)
            assertEquals("alias-ok", firstLog.upstreamModel)
            assertEquals(2, firstLog.upstreamAttempts)

            // 失败别名仍在冷却，下一次请求直接使用同渠道的可用别名
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            val secondLog = app.requestLogRepository.list(10).first()
            assertEquals("alias-ok", secondLog.upstreamModel)
            assertEquals(1, secondLog.upstreamAttempts)

            // 冷却到期后主别名重新参与，并可在恢复后独立承接请求
            fake.failingModels.remove("alias-fail")
            delay(350)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(3)
            val thirdLog = app.requestLogRepository.list(10).first()
            assertEquals("alias-fail", thirdLog.upstreamModel)
            assertEquals(1, thirdLog.upstreamAttempts)

            val requestedModels = fake.requests.map {
                Json.parseToJsonElement(it.body).jsonObject["model"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("alias-fail", "alias-ok", "alias-ok", "alias-fail"), requestedModels)
            assertEquals(1, app.modelCatalog.listEnabledModels().count { it == "shared-model" })
        }
    }

    @Test
    fun `shared public id falls back to a sibling alias after channel level failure`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val channel = createChannelWithBaseUrl(
            name = "shared-alias-500-channel",
            baseUrl = "${fake.baseUrl}/model-route-500/v1",
            modelMappings = listOf(
                ChannelModelRecord("alias-fail", "shared-500-model", "manual"),
                ChannelModelRecord("alias-ok", "shared-500-model", "manual"),
            ),
            priority = 1,
        )
        fake.failingModels.add("alias-fail")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"shared-500-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(channel.id, log.channelId)
            assertEquals("alias-ok", log.upstreamModel)
            assertEquals(2, log.upstreamAttempts)
            val requestedModels = fake.requests.map {
                Json.parseToJsonElement(it.body).jsonObject["model"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("alias-fail", "alias-ok"), requestedModels)
        }
    }

    @Test
    fun `shared public id rotates across channels without duplicating the public model`() = runBlocking {
        val first = createChannelWithBaseUrl(
            name = "shared-channel-a",
            baseUrl = "${fake.baseUrl}/ok/v1",
            modelMappings = listOf(ChannelModelRecord("upstream-a", "shared-channel-model", "manual")),
            priority = 1,
        )
        val second = createChannelWithBaseUrl(
            name = "shared-channel-b",
            baseUrl = "${fake.baseUrl}/ok/v1",
            modelMappings = listOf(ChannelModelRecord("upstream-b", "shared-channel-model", "manual")),
            priority = 1,
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"shared-channel-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)

            val logs = app.requestLogRepository.list(10)
            assertEquals(setOf(first.id, second.id), logs.map { it.channelId }.toSet())
            logs.forEach { assertEquals(1, it.upstreamAttempts) }
            assertEquals(1, app.modelCatalog.listEnabledModels().count { it == "shared-channel-model" })
        }
    }

    @Test
    fun `slow first token stream cools candidate after streak`() = runBlocking {
        app.close()
        app = GatewayApplication(
            config(
                firstTokenTimeoutMs = 5_000,
                idleTimeoutMs = 5_000,
                totalTimeoutMs = 15_000,
                ttftSlowThresholdMs = 1_000,
                ttftSlowStreak = 2,
            ),
        )
        val primary = createChannel("slow", "slow")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)

            // 连续两次首 token 超过软阈值后，第三个请求应绕开慢渠道
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(3)
            val third = app.requestLogRepository.list(10).first()
            assertEquals(secondary.id, third.channelId)
            assertEquals(1, third.upstreamAttempts)
            assertEquals(listOf("slow", "slow", "ok"), fake.requests.map { it.scenario })
        }
    }

    @Test
    fun `retries cooled down candidates after cooldown expires`() = runBlocking {
        app.close()
        app = GatewayApplication(
            config(
                maxAttempts = 3,
                cooldownTransientBaseMs = 300,
            ),
        )
        val primary = createChannel("primary", "500")
        val secondary = createChannel("secondary", "500")
        val tertiary = createChannel("tertiary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2", tertiary to "deepseek-chat")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(3, awaitRequestLog().upstreamAttempts)

            delay(350)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            assertEquals(3, app.requestLogRepository.list(10).first().upstreamAttempts)
            assertEquals(listOf("500", "500", "ok", "500", "500", "ok"), fake.requests.map { it.scenario })
        }
    }

    @Test
    fun `retries next candidate on rate limit before first token`() = runBlocking {
        val primary = createChannel("limited", "429")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("[DONE]"))
            assertEquals(2, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
            assertNull(log.errorCode)
        }
    }

    @Test
    fun `rate limit cooldown honors Retry-After on next request`() = runBlocking {
        val primary = createChannel("limited", "429-retry-after")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(2, awaitRequestLog().upstreamAttempts)

            // 立即重发：候选键按 Retry-After（1s）冷却，直接跳过限流渠道
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            val secondLog = app.requestLogRepository.list(10).first()
            assertEquals(secondary.id, secondLog.channelId)
            assertEquals(1, secondLog.upstreamAttempts)
            assertEquals(listOf("429-retry-after", "ok", "ok"), fake.requests.map { it.scenario })
        }
    }

    @Test
    fun `falls back when upstream connection fails before first token`() = runBlocking {
        val primary = createChannelWithBaseUrl("unreachable", "http://127.0.0.1:1/v1")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("[DONE]"))
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
        }
    }

    @Test
    fun `does not switch candidate after first token`() = runBlocking {
        val primary = createChannel("primary", "disconnect")
        val secondary = createChannel("secondary", "ok")
        createAlias("fast-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(primary.id, log.channelId)
            assertEquals("stream_interrupted", log.errorCode)
            assertEquals(FailureStage.STREAMING, log.failureStage)
            assertEquals("上游连接中断", log.errorMessage)
            assertEquals(200, log.statusCode)
        }
    }

    @Test
    fun `stream interruption cools channel but fail-open still attempts`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val channel = createChannel("primary", "disconnect")
        createAlias("fast-chat", channel to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals("stream_interrupted", awaitRequestLog().errorCode)

            // 渠道已冷却：fail-open 照常尝试，仍返回真实流结果而不是 503
            assertEquals(HttpStatusCode.OK, send().status)
            assertEquals(2, fake.requests.size)

            awaitRequestLogCount(2)
            assertEquals(1, app.requestLogRepository.list(10).first().upstreamAttempts)
        }
    }

    @Test
    fun `client disconnect after first token does not cool channel`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val channel = createChannel("primary", "ok")
        createAlias("fast-chat", channel to "m1")
        val key = createClientKey()
        val failNextWrite = AtomicBoolean(false)

        testApplication {
            application {
                app.install(this)
                install(createApplicationPlugin("test-client-disconnect") {
                    on(ResponseBodyReadyForSend) { _, _ ->
                        if (failNextWrite.compareAndSet(true, false)) {
                            throw ChannelWriteException("test client disconnect", IOException("test"))
                        }
                    }
                })
            }

            failNextWrite.set(true)
            val first = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }
            assertEquals(499, first.status.value)

            val log = awaitRequestLog()
            assertEquals("client_disconnect", log.errorCode)
            assertEquals(FailureStage.CLIENT, log.failureStage)
            assertEquals(499, log.statusCode)
            assertEquals(1, fake.requests.size)

            val second = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, second.status)
            assertTrue(second.bodyAsText().contains("[DONE]"))
            assertEquals(2, fake.requests.size)
        }
    }

    @Test
    fun `maps upstream rate limit to client error`() = runBlocking {
        val channel = createChannel("limited", "429")
        createAlias("fast-chat", channel to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(body.contains("upstream_error"))
            val log = awaitRequestLog()
            assertEquals(429, log.statusCode)
            assertEquals("upstream_error", log.errorCode)
            assertEquals(FailureStage.UPSTREAM, log.failureStage)
            assertEquals("上游限流", log.errorMessage)
            assertEquals("rate_limit_exceeded", log.upstreamErrorCode)
            assertTrue(app.metrics.scrape().contains("gateway_upstream_errors_total"))
        }
    }

    @Test
    fun `returns gateway timeout when first token is too slow`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 300, idleTimeoutMs = 300, totalTimeoutMs = 2_000))
        val channel = createChannel("slow", "slow")
        createAlias("fast-chat", channel to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            val log = awaitRequestLog()
            assertEquals(504, log.statusCode)
            assertEquals("upstream_timeout", log.errorCode)
            assertEquals(FailureStage.UPSTREAM, log.failureStage)
            assertEquals("上游响应超时", log.errorMessage)
        }
        awaitCancellation()
    }

    @Test
    fun `marks usage unknown when upstream omits it`() = runBlocking {
        val channel = createChannel("no-usage", "no-usage")
        createAlias("fast-chat", channel to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(false, log.usageKnown)
            assertNull(log.totalTokens)
        }
    }

    @Test
    fun `rejects invalid model and stream types`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createAlias("fast-chat", channel to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val invalidModel = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":{},"messages":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidModel.status)
            assertTrue(invalidModel.bodyAsText().contains("invalid_model"))

            val invalidStream = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":"yes","messages":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidStream.status)
            assertTrue(invalidStream.bodyAsText().contains("invalid_stream"))
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `models excludes aliases without enabled channel`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createAlias("fast-chat", channel to "m1")
        app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            credential = null,
            authType = channel.authType,
            enabled = false,
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!response.bodyAsText().contains("fast-chat"))
        }
    }

    @Test
    fun `admin rejects malformed json with 400`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val response = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("{not-json}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("invalid_request"))
        }
    }

    @Test
    fun `exposes and routes upstream models without aliases when enabled`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannel("openai", "ok")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            assertTrue(models.bodyAsText().contains("gpt-4o-mini"))

            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Hello!"))

            val log = awaitRequestLog()
            assertEquals(channel.id, log.channelId)
            assertEquals("gpt-4o-mini", log.upstreamModel)
        }
    }

    @Test
    fun `canonical model mapping controls exposure and direct routing`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannelWithBaseUrl(
            "canonical",
            "${fake.baseUrl}/ok/v1",
            modelMappings = listOf(
                ChannelModelRecord("Qwen3.8-Flash", "qwen3.8-flash", "models_dev"),
                ChannelModelRecord("gpt-4o-mini"),
            ),
        )
        createAlias("custom-qwen", channel to "Qwen3.8-Flash")
        val key = createClientKey()
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            val body = models.bodyAsText()
            assertTrue(body.contains("qwen3.8-flash"), "BODY=[$body]")
            assertTrue(body.contains("custom-qwen"), "BODY=[$body]")
            assertTrue(body.contains("gpt-4o-mini"), "BODY=[$body]")
            assertTrue(!body.contains("Qwen3.8-Flash"), "BODY=[$body]")

            val adminModels = client.get("/admin/api/exposed-models") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            val adminBody = adminModels.bodyAsText()
            assertTrue(adminBody.contains("\"source\":\"canonical\""), "BODY=[$adminBody]")
            assertTrue(adminBody.contains("Qwen3.8-Flash"), "BODY=[$adminBody]")

            val canonicalRequest = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3.8-flash","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, canonicalRequest.status)
            assertTrue(fake.requests.last().body.contains("\"model\":\"Qwen3.8-Flash\""))

            val rawUpstreamRequest = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"Qwen3.8-Flash","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.NotFound, rawUpstreamRequest.status)
            assertTrue(rawUpstreamRequest.bodyAsText().contains("model_not_found"))

            val aliasRequest = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"custom-qwen","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, aliasRequest.status)
            assertTrue(fake.requests.last().body.contains("\"model\":\"Qwen3.8-Flash\""))
        }
    }

    @Test
    fun `admin channel model mappings persist and allow shared canonical ids`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "mapping-api",
            "${fake.baseUrl}/ok/v1",
            modelMappings = mappingsOf("gpt-4o-mini", "deepseek-chat"),
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val updated = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[{"upstreamModel":"gpt-4o-mini","canonicalModelId":"gpt-4o-mini-official","mappingSource":"manual"}]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedBody = updated.bodyAsText()
            assertTrue(updatedBody.contains("gpt-4o-mini-official"), "BODY=[$updatedBody]")
            assertTrue(updatedBody.contains("\"mappingSource\":\"manual\""), "BODY=[$updatedBody]")

            val duplicate = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[
                        {"upstreamModel":"gpt-4o-mini","canonicalModelId":"same-id","mappingSource":"manual"},
                        {"upstreamModel":"deepseek-chat","canonicalModelId":"same-id","mappingSource":"manual"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, duplicate.status)
            val duplicateBody = duplicate.bodyAsText()
            assertTrue(duplicateBody.contains("gpt-4o-mini"), "BODY=[$duplicateBody]")
            assertTrue(duplicateBody.contains("deepseek-chat"), "BODY=[$duplicateBody]")
            val sharedCandidates = app.modelCatalog.findCandidates("same-id", GatewayProtocol.CHAT_COMPLETIONS)
            assertEquals(
                listOf("deepseek-chat", "gpt-4o-mini"),
                sharedCandidates.map { it.upstreamModel }.sorted(),
            )
            assertEquals(1, app.modelCatalog.listEnabledModels().count { it == "same-id" })

            val exposed = client.get("/admin/api/exposed-models") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(HttpStatusCode.OK, exposed.status)
            val exposedModel = Json.parseToJsonElement(exposed.bodyAsText())
                .jsonObject["data"]!!
                .jsonArray
                .single { it.jsonObject["id"]!!.jsonPrimitive.content == "same-id" }
                .jsonObject
            assertEquals(
                listOf("deepseek-chat", "gpt-4o-mini"),
                exposedModel["upstreamModels"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
            )

            val canonicalConflictsWithUnmapped = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[
                        {"upstreamModel":"Qwen-Fast","canonicalModelId":"gpt-4o-mini"},
                        {"upstreamModel":"gpt-4o-mini"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, canonicalConflictsWithUnmapped.status)

            val caseDistinctUnmapped = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mapping-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[
                        {"upstreamModel":"model-A"},
                        {"upstreamModel":"MODEL-a"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, caseDistinctUnmapped.status)
        }
    }

    @Test
    fun `admin partial channel update preserves anthropic authentication`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-partial-update",
            baseUrl = "${fake.baseUrl}/ok/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"anthropic-partial-update",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "protocols":["messages"],
                      "enabled":true,
                      "priority":7
                    }
                    """.trimIndent(),
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"authType\":\"anthropic\""))
            assertEquals("anthropic", app.channelRepository.find(channel.id)?.authType)
            assertEquals(7, app.channelRepository.find(channel.id)?.priority)
        }
    }

    @Test
    fun `lists models from an upstream channel`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels/${channel.id}/models") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("gpt-4o-mini"), "BODY=[$body]")
            assertTrue(body.contains("deepseek-chat"), "BODY=[$body]")
        }
    }

    @Test
    fun `admin exposed models matches data plane output`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannel("openai", "ok")
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val publicResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            val adminResponse = client.get("/admin/api/exposed-models") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }

            assertEquals(HttpStatusCode.OK, publicResponse.status)
            assertEquals(HttpStatusCode.OK, adminResponse.status)
            val publicIds = Json.parseToJsonElement(publicResponse.bodyAsText()).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            val adminData = Json.parseToJsonElement(adminResponse.bodyAsText()).jsonObject["data"]!!.jsonArray
                .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }

            assertEquals(publicIds, adminData.keys.sorted())
            assertEquals(
                setOf("fast-chat", "gpt-4o-mini", "deepseek-chat", "m1", "m2"),
                publicIds.toSet(),
            )
            assertEquals("alias", adminData.getValue("fast-chat").jsonObject["source"]!!.jsonPrimitive.content)
            assertEquals("upstream", adminData.getValue("gpt-4o-mini").jsonObject["source"]!!.jsonPrimitive.content)
            assertEquals(
                "llm-gateway",
                adminData.getValue("fast-chat").jsonObject["ownedBy"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `responses protocol streams and records usage`() = runBlocking {
        val channel = createChannelWithProtocols("responses", "ok", setOf(GatewayProtocol.RESPONSES))
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"input":"hi"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: response.output_text.delta"), "BODY=[$body]")
            assertTrue(body.contains("event: response.completed"), "BODY=[$body]")
            assertTrue(!body.contains("data: [DONE]"), "BODY=[$body]")

            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.RESPONSES, log.protocol)
            assertEquals(10L, log.inputTokens)
            assertEquals(3L, log.outputTokens)
            assertEquals(2L, log.cachedTokens)
            assertEquals(0L, log.reasoningTokens)
            assertEquals(13L, log.totalTokens)
            assertNotNull(log.firstTokenMs)
        }
    }

    @Test
    fun `responses first token timeout ignores response created event`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 300, idleTimeoutMs = 300, totalTimeoutMs = 2_000))
        val channel = createChannelWithProtocols(
            "responses-created-stall",
            "responses-created-stall",
            setOf(GatewayProtocol.RESPONSES),
        )
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            val log = awaitRequestLog()
            assertEquals(504, log.statusCode)
            assertEquals("upstream_timeout", log.errorCode)
            assertEquals(FailureStage.UPSTREAM, log.failureStage)
        }
    }

    @Test
    fun `falls back when responses upstream stalls after created event`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 300, idleTimeoutMs = 300, totalTimeoutMs = 2_000))
        val primary = createChannelWithProtocols(
            "responses-stall",
            "responses-created-stall",
            setOf(GatewayProtocol.RESPONSES),
        )
        val secondary = createChannelWithProtocols("responses-ok", "ok", setOf(GatewayProtocol.RESPONSES))
        createAlias("fast-chat", primary to "gpt-4o-mini", secondary to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"input":"hi"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: response.completed"), "BODY=[$body]")
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
        }
    }

    @Test
    fun `responses function call delta counts as first output`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 300, idleTimeoutMs = 2_000, totalTimeoutMs = 5_000))
        val channel = createChannelWithProtocols(
            "responses-function-call",
            "responses-function-call",
            setOf(GatewayProtocol.RESPONSES),
        )
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"input":"hi"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: response.function_call_arguments.delta"), "BODY=[$body]")
            assertTrue(body.contains("event: response.completed"), "BODY=[$body]")
            val log = awaitRequestLog()
            assertEquals(200, log.statusCode)
            assertNotNull(log.firstTokenMs)
        }
    }

    @Test
    fun `records responses failed terminal event as streaming failure`() = runBlocking {
        val channel = createChannelWithProtocols(
            "responses-failed",
            "responses-failed",
            setOf(GatewayProtocol.RESPONSES),
        )
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"input":"hi"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: response.failed"), "BODY=[$body]")
            val log = awaitRequestLog()
            assertEquals(200, log.statusCode)
            assertEquals("responses_failed", log.errorCode)
            assertEquals(FailureStage.STREAMING, log.failureStage)
            assertEquals("上游响应失败", log.errorMessage)
        }
    }

    @Test
    fun `records responses incomplete terminal event as streaming failure`() = runBlocking {
        val channel = createChannelWithProtocols(
            "responses-incomplete",
            "responses-incomplete",
            setOf(GatewayProtocol.RESPONSES),
        )
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"input":"hi"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: response.incomplete"), "BODY=[$body]")
            val log = awaitRequestLog()
            assertEquals(200, log.statusCode)
            assertEquals("responses_incomplete", log.errorCode)
            assertEquals(FailureStage.STREAMING, log.failureStage)
            assertEquals("上游响应未完成", log.errorMessage)
        }
    }

    @Test
    fun `responses protocol records usage for non-streaming`() = runBlocking {
        val channel = createChannelWithProtocols("responses", "ok", setOf(GatewayProtocol.RESPONSES))
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":false,"input":"hi"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("\"object\":\"response\""), "BODY=[$body]")
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.RESPONSES, log.protocol)
            assertEquals(10L, log.inputTokens)
            assertEquals(3L, log.outputTokens)
            assertEquals(2L, log.cachedTokens)
            assertEquals(13L, log.totalTokens)
        }
    }

    @Test
    fun `records request cost from configured model price`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createAlias("priced-chat", channel to "gpt-4o-mini")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000016")))
            assertEquals("USD", log.costCurrency)
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("1")))
            assertEquals(0, log.outputPricePerMillion?.compareTo(BigDecimal("2")))
        }
    }

    @Test
    fun `rejects next request after daily token limit is consumed`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey(ClientKeyQuotaLimits(dailyTokens = 13))

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            val rejected = send()

            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertTrue(rejected.bodyAsText().contains("daily_token_quota_exceeded"))
            assertEquals(1, fake.requests.size)
            assertEquals(13, app.clientKeyQuotaService.currentUsage(app.clientKeyRepository.list().single().id).dailyTokens)
            assertTrue(app.metrics.scrape().contains("gateway_quota_rejections_total"))
        }
    }

    @Test
    fun `rejects next request after daily cost limit is consumed`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createAlias("fast-chat", channel to "gpt-4o-mini")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey(
            ClientKeyQuotaLimits(dailyCosts = mapOf("USD" to BigDecimal("0.000016"))),
        )

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            val rejected = send()
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertTrue(rejected.bodyAsText().contains("daily_cost_quota_exceeded"))
        }
    }

    @Test
    fun `rejects concurrent request beyond limit and releases slot afterward`() = runBlocking {
        val channel = createChannel("openai", "slow")
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }

            val first = async(Dispatchers.Default) { send() }
            val clientKeyId = app.clientKeyRepository.list().single().id
            val heldDeadline = System.currentTimeMillis() + 2_000
            while (app.clientKeyQuotaService.currentUsage(clientKeyId).concurrentRequests == 0 && System.currentTimeMillis() < heldDeadline) {
                delay(5)
            }
            assertEquals(1, app.clientKeyQuotaService.currentUsage(clientKeyId).concurrentRequests)

            val rejected = send()
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertTrue(rejected.bodyAsText().contains("concurrency_limit_exceeded"))
            assertEquals(HttpStatusCode.OK, first.await().status)
            assertEquals(HttpStatusCode.OK, send().status)
        }
    }

    @Test
    fun `releases concurrency slot when request is rejected by routing`() = runBlocking {
        val key = createClientKey(ClientKeyQuotaLimits(maxConcurrentRequests = 1))

        testApplication {
            application { app.install(this) }
            suspend fun send(model: String) = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.NotFound, send("missing-model").status)
            val metrics = app.metrics.scrape()
            assertTrue(metrics.contains("alias=\"unresolved\""))
            assertTrue(!metrics.contains("alias=\"missing-model\""))
            val channel = createChannel("openai", "ok")
            createAlias("fast-chat", channel to "gpt-4o-mini")
            assertEquals(HttpStatusCode.OK, send("fast-chat").status)
        }
    }

    @Test
    fun `persists quota limits through client key repository`() = runBlocking {
        val generated = ClientKeyHasher.generate()
        val created = app.clientKeyRepository.create(
            name = "bounded",
            keyHash = generated.hash,
            keyPrefix = generated.prefix,
            enabled = true,
            expiresAt = null,
            quotas = ClientKeyQuotaLimits(
                dailyTokens = 1000,
                dailyCosts = mapOf("USD" to BigDecimal("5.50"), "CNY" to BigDecimal("40")),
                maxConcurrentRequests = 4,
                requestsPerMinute = 120,
            ),
        )

        val loaded = app.clientKeyRepository.find(created.id)!!
        assertEquals(1000, loaded.quotas.dailyTokens)
        assertEquals(0, loaded.quotas.dailyCostLimit("USD")?.compareTo(BigDecimal("5.50")))
        assertEquals(0, loaded.quotas.dailyCostLimit("CNY")?.compareTo(BigDecimal("40")))
        assertEquals(null, loaded.quotas.dailyCostLimit("EUR"))
        assertEquals(4, loaded.quotas.maxConcurrentRequests)
        assertEquals(120, loaded.quotas.requestsPerMinute)
    }

    @Test
    fun `records reasoning effort from chat and responses requests`() = runBlocking {
        val channel = createChannelWithProtocols(
            "openai",
            "ok",
            setOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
        )
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val chat = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"fast-chat","reasoning_effort":"high","reasoning":{"effort":"low"},"messages":[]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, chat.status)
            assertEquals("high", awaitLogWithEffort("high").reasoningEffort)

            val responses = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"fast-chat","stream":false,"reasoning_effort":"high","reasoning":{"effort":"low"},"input":"hi"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, responses.status)
            assertEquals("low", awaitLogWithEffort("low").reasoningEffort)
        }
    }

    @Test
    fun `rejects responses request when channel only supports chat completions`() = runBlocking {
        val channel = createChannel("chat-only", "ok")
        createAlias("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":false,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("protocol_not_supported"))
            assertTrue(fake.requests.isEmpty())
            val log = awaitRequestLog()
            assertEquals(FailureStage.ROUTING, log.failureStage)
            assertEquals("protocol_not_supported", log.errorCode)
            assertTrue(log.errorMessage!!.contains("没有支持 responses"))
            assertNull(log.channelId)
            assertNull(log.channelName)
        }
    }

    @Test
    fun `probes upstream protocols`() = runBlocking {
        val channel = createChannel("chat-only", "chat-only")
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.post("/admin/api/channels/${channel.id}/probe-protocols") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini"}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("\"protocol\":\"chat_completions\""), "BODY=[$body]")
            assertTrue(body.contains("\"protocol\":\"responses\""), "BODY=[$body]")
            assertTrue(body.contains("\"protocol\":\"messages\""), "BODY=[$body]")
            assertTrue(body.contains("\"supported\":true"), "BODY=[$body]")
            assertTrue(body.contains("\"supported\":false"), "BODY=[$body]")
        }
    }

    @Test
    fun `direct mode reports protocol mismatch`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        createChannel("chat-only", "ok")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","stream":false,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("protocol_not_supported"))
        }
    }

    @Test
    fun `admin channel response exposes protocols`() = runBlocking {
        createChannelWithProtocols(
            "both",
            "ok",
            setOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("chat_completions"), "BODY=[$body]")
            assertTrue(body.contains("responses"), "BODY=[$body]")
        }
    }

    @Test
    fun `admin login requires csrf and created key can call models`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val login = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val setCookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            val sessionCookie = setCookies.first { it.startsWith("gw_session=") }.substringBefore(";")
            val csrf = setCookies.first { it.startsWith("gw_csrf=") }.substringAfter("=").substringBefore(";")

            val channelResponse = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, sessionCookie)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"openai","baseUrl":"${fake.baseUrl}/ok/v1","credential":"upstream-secret"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, channelResponse.status)

            val createdChannel = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"openai","baseUrl":"${fake.baseUrl}/ok/v1","credential":"upstream-secret","models":["gpt-4o-mini"]}""")
            }
            assertEquals(HttpStatusCode.Created, createdChannel.status)
            val channelId = Json.parseToJsonElement(createdChannel.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

            val createdAlias = client.post("/admin/api/aliases") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"alias":"fast-chat","candidates":[{"channelId":"$channelId","upstreamModel":" gpt-4o-mini "}]}""")
            }
            assertEquals(HttpStatusCode.Created, createdAlias.status)
            val createdAliasJson = Json.parseToJsonElement(createdAlias.bodyAsText()).jsonObject
            val aliasId = createdAliasJson["id"]!!.jsonPrimitive.content
            val createdModel = createdAliasJson["candidates"]!!.jsonArray[0].jsonObject["upstreamModel"]!!.jsonPrimitive.content
            assertEquals("gpt-4o-mini", createdModel)

            val updatedAlias = client.put("/admin/api/aliases/$aliasId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"alias":"fast-chat","candidates":[{"channelId":"$channelId","upstreamModel":" gpt-4o-mini "}]}""")
            }
            assertEquals(HttpStatusCode.OK, updatedAlias.status)
            val updatedAliasJson = Json.parseToJsonElement(updatedAlias.bodyAsText()).jsonObject
            val updatedModel = updatedAliasJson["candidates"]!!.jsonArray[0].jsonObject["upstreamModel"]!!.jsonPrimitive.content
            assertEquals("gpt-4o-mini", updatedModel)

            val createdKey = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"test-key"}""")
            }
            assertEquals(HttpStatusCode.Created, createdKey.status)
            val key = Json.parseToJsonElement(createdKey.bodyAsText()).jsonObject["key"]!!.jsonPrimitive.content

            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            assertTrue(models.bodyAsText().contains("fast-chat"))
        }
    }

    @Test
    fun `channel authorization persists and restricts direct routing`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannelWithBaseUrl(
            "authorized-only",
            "${fake.baseUrl}/ok/v1",
        ).let { created ->
            app.channelRepository.update(
                id = created.id,
                name = created.name,
                baseUrl = created.baseUrl,
                credential = null,
                authType = created.authType,
                protocols = created.protocols,
                enabled = true,
                modelMappings = mappingsOf("gpt-4o-mini"),
            )!!
        }
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            val body = models.bodyAsText()
            assertTrue(body.contains("gpt-4o-mini"), "BODY=[$body]")
            assertTrue(!body.contains("deepseek-chat"), "BODY=[$body]")

            val unauthorized = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"deepseek-chat","stream":false,"messages":[]}""")
            }
            assertTrue(unauthorized.status.value >= 400)
        }
        assertEquals(listOf("gpt-4o-mini"), channel.models)
    }

    @Test
    fun `usage report aggregates requests tokens and costs by key`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createAlias("fast-chat", channel to "gpt-4o-mini")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            repeat(2) {
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"fast-chat","messages":[]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                awaitRequestLogCount(it + 1)
            }
            app.requestLogRepository.insert(
                requestLog("renamed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "renamed-key",
                    costAmount = java.math.BigDecimal("0.00000500"),
                    costCurrency = "USD",
                ),
            )
            app.requestLogRepository.insert(
                requestLog("failed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    statusCode = 500,
                ),
            )
            app.requestLogRepository.insert(
                requestLog("stream-failed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    statusCode = 200,
                    errorCode = "stream_interrupted",
                ),
            )
            val login = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            val cookie = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .first { it.startsWith("gw_session=") }.substringBefore(";")
            val report = client.get("/admin/api/usage-report?from=${System.currentTimeMillis() - 86_400_000L}&to=${System.currentTimeMillis() + 60_000L}") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
            val json = Json.parseToJsonElement(report.bodyAsText()).jsonObject
            val total = json["total"]!!.jsonObject
            assertEquals("5", total["requests"]!!.jsonPrimitive.content)
            assertEquals("2", total["errors"]!!.jsonPrimitive.content)
            assertEquals("32", total["totalTokens"]!!.jsonPrimitive.content)
            assertEquals("0.000037", total["costs"]!!.jsonObject["USD"]!!.jsonPrimitive.content)
            val byKey = json["byClientKey"]!!.jsonArray.single().jsonObject
            assertEquals(app.clientKeyRepository.list().single().id, byKey["id"]!!.jsonPrimitive.content)
            assertTrue(byKey["name"]!!.jsonPrimitive.content in setOf("test-key", "renamed-key"))
        }
    }

    @Test
    fun `request log cleanup deletes only expired rows`() = runBlocking {
        val now = System.currentTimeMillis()
        val old = requestLog("old", createdAt = now - 31 * 86_400_000L)
        val recent = requestLog("recent", createdAt = now)
        app.requestLogRepository.insert(old)
        app.requestLogRepository.insert(recent)
        val retired = app.logRetentionService.cleanExpired()

        assertEquals(1, retired.deleted)
        assertEquals(listOf("recent"), app.requestLogRepository.list(10).map { it.requestId })
    }

    @Test
    fun `admin client key API validates and returns quota settings`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val login = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            val setCookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            val sessionCookie = setCookies.first { it.startsWith("gw_session=") }.substringBefore(";")
            val csrf = setCookies.first { it.startsWith("gw_csrf=") }.substringAfter("=").substringBefore(";")

            suspend fun create(body: String) = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val created = create(
                """{"name":"bounded","dailyTokenLimit":100,"dailyUsdLimit":"1.50","dailyCnyLimit":"10","maxConcurrentRequests":2,"requestsPerMinute":60}""",
            )
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals("100", createdJson["dailyTokenLimit"]!!.jsonPrimitive.content)
            assertEquals("1.5", createdJson["dailyUsdLimit"]!!.jsonPrimitive.content)
            assertEquals("2", createdJson["maxConcurrentRequests"]!!.jsonPrimitive.content)
            assertEquals("60", createdJson["requestsPerMinute"]!!.jsonPrimitive.content)
            assertNull(createdJson["dailyEurLimit"])

            val listed = client.get("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, listed.status)
            val listedKey = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject
            val usage = listedKey["quotaUsage"]!!.jsonObject
            assertEquals("0", usage["dailyTokens"]!!.jsonPrimitive.content)
            assertEquals("0", usage["concurrentRequests"]!!.jsonPrimitive.content)
            assertEquals("0", usage["currentMinuteRequests"]!!.jsonPrimitive.content)

            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"negative","dailyTokenLimit":-1}""").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"bad-cost","dailyUsdLimit":"-0.01"}""").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"bad-concurrency","maxConcurrentRequests":0}""").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"bad-rate","requestsPerMinute":-1}""").status,
            )
        }
    }

    @Test
    fun `client key lifecycle and admin audit enforce permission boundaries`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()

            val failedLogin = client.post("/admin/api/login") {
                header("X-Request-Id", "failed-login-request")
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"wrong-password"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, failedLogin.status)

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/admin/api/audit-logs").status,
            )
            val unmatchedWrite = client.post("/admin/api/not-a-route") {
                header("X-Request-Id", "unmatched-no-session")
            }
            assertEquals(HttpStatusCode.NotFound, unmatchedWrite.status)

            val (sessionCookie, csrf) = client.loginAdmin()
            val created = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "create-key-request")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"search-service","dailyTokenLimit":100,"dailyUsdLimit":"1.50","dailyCnyLimit":"10","maxConcurrentRequests":2,"requestsPerMinute":30}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertEquals("no-store", created.headers["Cache-Control"])
            assertEquals("create-key-request", created.headers["X-Request-Id"])
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val keyId = createdJson["id"]!!.jsonPrimitive.content
            val oldKey = createdJson["key"]!!.jsonPrimitive.content

            val rotateWithoutSession = client.post("/admin/api/client-keys/$keyId/rotate") {
                header("X-Request-Id", "rotate-no-session")
            }
            assertEquals(HttpStatusCode.Unauthorized, rotateWithoutSession.status)
            val revokeWithoutSession = client.post("/admin/api/client-keys/$keyId/revoke") {
                header("X-Request-Id", "revoke-no-session")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokeWithoutSession.status)

            val bearerCannotReadAudit = client.get("/admin/api/audit-logs") {
                header(HttpHeaders.Authorization, "Bearer $oldKey")
            }
            assertEquals(HttpStatusCode.Unauthorized, bearerCannotReadAudit.status)

            val missingCsrf = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-Request-Id", "rotate-missing-csrf")
            }
            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)

            val invalidCsrf = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", "wrong-token")
                header("X-Request-Id", "rotate-invalid-csrf")
            }
            assertEquals(HttpStatusCode.Forbidden, invalidCsrf.status)

            val rotated = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "rotate-key-request")
            }
            assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
            assertEquals("no-store", rotated.headers["Cache-Control"])
            assertEquals("rotate-key-request", rotated.headers["X-Request-Id"])
            val rotatedJson = Json.parseToJsonElement(rotated.bodyAsText()).jsonObject
            val newKey = rotatedJson["key"]!!.jsonPrimitive.content
            assertNotEquals(oldKey, newKey)
            assertEquals("100", rotatedJson["dailyTokenLimit"]!!.jsonPrimitive.content)
            assertEquals("1.5", rotatedJson["dailyUsdLimit"]!!.jsonPrimitive.content)
            assertEquals("2", rotatedJson["maxConcurrentRequests"]!!.jsonPrimitive.content)
            assertEquals("30", rotatedJson["requestsPerMinute"]!!.jsonPrimitive.content)
            assertNotNull(rotatedJson["rotatedAt"])
            assertNull(rotatedJson["revokedAt"])

            val oldKeyResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $oldKey")
            }
            assertEquals(HttpStatusCode.Unauthorized, oldKeyResponse.status)
            val newKeyResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $newKey")
            }
            assertEquals(HttpStatusCode.OK, newKeyResponse.status)

            val revoked = client.post("/admin/api/client-keys/$keyId/revoke") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "revoke-key-request")
            }
            assertEquals(HttpStatusCode.OK, revoked.status, revoked.bodyAsText())
            val revokedJson = Json.parseToJsonElement(revoked.bodyAsText()).jsonObject
            assertEquals("false", revokedJson["enabled"]!!.jsonPrimitive.content)
            assertNotNull(revokedJson["revokedAt"])

            val revokedKeyResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $newKey")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokedKeyResponse.status)
            assertTrue(revokedKeyResponse.bodyAsText().contains("撤销"))

            val rotateRevoked = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "rotate-revoked-request")
            }
            assertEquals(HttpStatusCode.Conflict, rotateRevoked.status)

            val reenableRevoked = client.put("/admin/api/client-keys/$keyId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "reenable-revoked-request")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"search-service","enabled":true,"expiresAt":null,"dailyTokenLimit":100,"dailyUsdLimit":"1.50","dailyCnyLimit":"10","maxConcurrentRequests":2,"requestsPerMinute":30}""",
                )
            }
            assertEquals(HttpStatusCode.Conflict, reenableRevoked.status)

            val auditReadOnly = client.delete("/admin/api/audit-logs") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "delete-audit-request")
            }
            assertEquals(HttpStatusCode.MethodNotAllowed, auditReadOnly.status)

            val auditResponse = client.get("/admin/api/audit-logs?limit=100") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, auditResponse.status, auditResponse.bodyAsText())
            assertEquals("no-store", auditResponse.headers["Cache-Control"])
            val audits = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
            val rotateAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "rotate-key-request"
            }
            assertEquals("admin", rotateAudit["actor"]!!.jsonPrimitive.content)
            assertEquals("client_key.rotate", rotateAudit["action"]!!.jsonPrimitive.content)
            assertEquals("success", rotateAudit["result"]!!.jsonPrimitive.content, rotateAudit.toString())
            assertEquals("200", rotateAudit["statusCode"]!!.jsonPrimitive.content, rotateAudit.toString())

            val missingCsrfAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "rotate-missing-csrf"
            }
            assertEquals("failure", missingCsrfAudit["result"]!!.jsonPrimitive.content)
            assertEquals("csrf_missing", missingCsrfAudit["errorCode"]!!.jsonPrimitive.content)
            assertEquals("admin", missingCsrfAudit["actor"]!!.jsonPrimitive.content)

            val invalidCsrfAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "rotate-invalid-csrf"
            }
            assertEquals("csrf_invalid", invalidCsrfAudit["errorCode"]!!.jsonPrimitive.content)
            assertTrue(
                audits.any {
                    it["action"]!!.jsonPrimitive.content == "client_key.revoke" &&
                        it["requestId"]!!.jsonPrimitive.content == "revoke-key-request"
                },
            )
            assertTrue(
                audits.any {
                    it["action"]!!.jsonPrimitive.content == "client_key.create" &&
                        it["requestId"]!!.jsonPrimitive.content == "create-key-request"
                },
            )
            assertTrue(
                audits.any {
                    it["action"]!!.jsonPrimitive.content == "admin.login" &&
                        it["actor"]!!.jsonPrimitive.content == "admin"
                },
            )
            assertTrue(
                audits.none {
                    it["requestId"]!!.jsonPrimitive.content == "unmatched-no-session"
                },
            )
            val failedLoginAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "failed-login-request"
            }
            assertNull(failedLoginAudit["actor"])
            assertEquals("failure", failedLoginAudit["result"]!!.jsonPrimitive.content)
            assertEquals("invalid_credentials", failedLoginAudit["errorCode"]!!.jsonPrimitive.content)
            listOf("rotate-no-session", "revoke-no-session").forEach { requestId ->
                val unauthenticatedAudit = audits.single {
                    it["requestId"]!!.jsonPrimitive.content == requestId
                }
                assertNull(unauthenticatedAudit["actor"])
                assertEquals("failure", unauthenticatedAudit["result"]!!.jsonPrimitive.content)
                assertEquals("admin_session_missing", unauthenticatedAudit["errorCode"]!!.jsonPrimitive.content)
            }

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select concat_ws('|', actor, action, method, path, request_id, source_ip, error_code) from admin_audit_logs",
                    ).use { result ->
                        while (result.next()) {
                            val row = result.getString(1)
                            assertTrue(!row.contains(oldKey), "audit row leaked old key: $row")
                            assertTrue(!row.contains(newKey), "audit row leaked rotated key: $row")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `alias rejects un-authorized channel model`() = runBlocking {
        val channel = createChannelWithBaseUrl("alias-auth", "${fake.baseUrl}/ok/v1").let { created ->
            app.channelRepository.update(
                id = created.id,
                name = created.name,
                baseUrl = created.baseUrl,
                credential = null,
                authType = created.authType,
                protocols = created.protocols,
                enabled = true,
                modelMappings = mappingsOf("gpt-4o-mini"),
            )!!
        }
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.post("/admin/api/aliases") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"alias":"unauthorized-route","candidates":[{"channelId":"${channel.id}","upstreamModel":"deepseek-chat"}]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("model_not_authorized"))
        }
    }

    @Test
    fun `admin discovers upstream models before saving a channel`() = runBlocking {
        val channel = createChannelWithBaseUrl("discover", "${fake.baseUrl}/ok/v1").let { created ->
            app.channelRepository.update(
                id = created.id,
                name = created.name,
                baseUrl = created.baseUrl,
                credential = null,
                authType = created.authType,
                protocols = created.protocols,
                enabled = true,
                modelMappings = mappingsOf("gpt-4o-mini"),
            )!!
        }
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val beforeSave = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${fake.baseUrl}/ok/v1","credential":"upstream-secret"}""")
            }
            assertEquals(HttpStatusCode.OK, beforeSave.status)
            val beforeSaveBody = beforeSave.bodyAsText()
            assertTrue(beforeSaveBody.contains("gpt-4o-mini"), "BODY=[$beforeSaveBody]")
            assertTrue(beforeSaveBody.contains("\"authorized\":false"), "BODY=[$beforeSaveBody]")

            val withSavedCredential = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}"}""")
            }
            assertEquals(HttpStatusCode.OK, withSavedCredential.status)
            val savedBody = withSavedCredential.bodyAsText()
            val savedModels = Json.parseToJsonElement(savedBody).jsonObject["data"]!!.jsonArray
                .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }
            assertEquals("true", savedModels.getValue("gpt-4o-mini")["authorized"]!!.jsonPrimitive.content)
            assertEquals("false", savedModels.getValue("deepseek-chat")["authorized"]!!.jsonPrimitive.content)

            val changedBaseUrl = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${fake.baseUrl}/500/v1","channelId":"${channel.id}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, changedBaseUrl.status)
            assertTrue(changedBaseUrl.bodyAsText().contains("请重新输入上游凭据"))
        }
    }

    @Test
    fun `admin cannot revoke model authorization used by alias`() = runBlocking {
        val channel = createChannelWithBaseUrl("guarded", "${fake.baseUrl}/ok/v1").let { created ->
            app.channelRepository.update(
                id = created.id,
                name = created.name,
                baseUrl = created.baseUrl,
                credential = null,
                authType = created.authType,
                protocols = created.protocols,
                enabled = true,
                modelMappings = mappingsOf("gpt-4o-mini", "deepseek-chat"),
            )!!
        }
        createAlias("guarded-alias", channel to "deepseek-chat")
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"guarded","baseUrl":"${fake.baseUrl}/ok/v1","protocols":["chat_completions"],"enabled":true,"models":["gpt-4o-mini"]}""",
                )
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("channel_model_in_use"))
        }
    }

    @Test
    fun `channel update without models preserves authorization`() = runBlocking {
        val channel = createChannelWithBaseUrl("preserve", "${fake.baseUrl}/ok/v1")
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"preserve-renamed","baseUrl":"${fake.baseUrl}/ok/v1","protocols":["chat_completions"],"enabled":true}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val updatedModels = Json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]!!.jsonArray
                .map { it.jsonPrimitive.content }
            assertEquals(channel.models.sorted(), updatedModels.sorted())
        }
    }

    @Test
    fun `migrations tolerate duplicate rows`() {
        val databaseName = "migration_latest_${System.nanoTime()}"
        val migrationUrl = postgres.jdbcUrl.substringBefore("?").substringBeforeLast("/") + "/$databaseName"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("create database \"$databaseName\"") }
        }
        try {
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, credential_ciphertext, credential_nonce, credential_key_version, auth_type, enabled, created_at, updated_at)
                        values ('migration-channel', 'migration-channel', 'https://example.com/v1', 'cipher', 'nonce', 1, 'bearer', true, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "insert into model_aliases (id, alias, enabled, created_at, updated_at) values ('migration-alias', 'migration-alias', true, 1, 1)",
                    )
                    statement.execute(
                        "insert into alias_candidates (id, alias_id, channel_id, upstream_model, position) values ('migration-candidate', 'migration-alias', 'migration-channel', 'duplicate-model', 0)",
                    )
                    statement.execute(
                        """
                        insert into model_prices (id, channel_id, upstream_model, currency, input_price_per_million, output_price_per_million, cached_input_price_per_million, created_at, updated_at)
                        values ('migration-price', 'migration-channel', 'duplicate-model', 'USD', 1, 2, null, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "insert into request_logs (request_id, alias, status_code, total_ms, created_at) values ('legacy-request-log', 'legacy', 200, 1, 1)",
                    )
                }
            }

            // 先迁到 V19，构造一个关闭直连的渠道，验证 V20 会把它转为停用
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("19"))
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, credential_ciphertext, credential_nonce, credential_key_version, auth_type, enabled, expose_upstream_models, created_at, updated_at)
                        values ('alias-only-conversion', 'alias-only-conversion', 'https://example.com/v1', 'cipher', 'nonce', 1, 'bearer', true, false, 1, 1)
                        """.trimIndent(),
                    )
                }
            }

            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select enabled from channels where id = 'alias-only-conversion'").use { result ->
                        assertTrue(result.next())
                        assertFalse(result.getBoolean(1), "原关闭直连的渠道应被转为停用")
                    }
                    statement.executeQuery("select enabled from channels where id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1), "默认直连的渠道应保持启用")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'channels' and column_name = 'expose_upstream_models'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "expose_upstream_models 列应已删除")
                    }
                    statement.executeQuery("select count(*) from channel_models").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1").use { result ->
                        assertTrue(result.next())
                        assertEquals("23", result.getString(1))
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'client_keys' and column_name = 'requests_per_minute'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'request_logs' and column_name = 'upstream_attempts'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery(
                        "select is_nullable from information_schema.columns where table_name = 'request_logs' and column_name = 'stream'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("YES", result.getString(1))
                    }
                    statement.executeQuery("select stream from request_logs where request_id = 'legacy-request-log'").use { result ->
                        assertTrue(result.next())
                        assertNull(result.getObject(1))
                    }
                    statement.executeQuery("select canonical_model_id, mapping_source from channel_models where channel_id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertNull(result.getString(1))
                        assertNull(result.getString(2))
                    }
                }
            }
        } finally {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use {
                    it.execute("drop database if exists \"$databaseName\" with (force)")
                }
            }
        }
    }

    @Test
    fun `admin can update and clear avatar`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val avatar = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

            val updated = client.put("/admin/api/account/avatar") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"avatarDataUrl":"$avatar"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertTrue(updated.bodyAsText().contains(avatar))

            val me = client.get("/admin/api/me") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals(
                avatar,
                Json.parseToJsonElement(me.bodyAsText()).jsonObject["avatarDataUrl"]?.jsonPrimitive?.content,
            )

            val invalid = client.put("/admin/api/account/avatar") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"avatarDataUrl":"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("invalid_avatar"))

            val cleared = client.put("/admin/api/account/avatar") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"avatarDataUrl":null}""")
            }
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertTrue(!cleared.bodyAsText().contains("avatarDataUrl"))
        }
    }

    @Test
    fun `admin password change revokes sessions and uses new password`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val wrongCurrent = client.put("/admin/api/account/password") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"currentPassword":"wrong-password","newPassword":"new-password-123"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, wrongCurrent.status)
            assertTrue(wrongCurrent.bodyAsText().contains("invalid_current_password"))

            val changed = client.put("/admin/api/account/password") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"currentPassword":"test-password","newPassword":"new-password-123"}""")
            }
            assertEquals(HttpStatusCode.NoContent, changed.status)

            val oldSession = client.get("/admin/api/me") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.Unauthorized, oldSession.status)

            val oldPassword = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, oldPassword.status)

            val newPassword = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"new-password-123"}""")
            }
            assertEquals(HttpStatusCode.OK, newPassword.status)
        }
    }

    @Test
    fun `admin can toggle request log cleanup and persists the setting`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val initial = client.get("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertFalse(
                Json.parseToJsonElement(initial.bodyAsText()).jsonObject["cleanupEnabled"]!!.jsonPrimitive.boolean,
            )
            assertFalse(app.logRetentionService.scheduledCleanupRunning)

            val enabled = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":true}""")
            }
            assertEquals(HttpStatusCode.OK, enabled.status)
            assertTrue(
                Json.parseToJsonElement(enabled.bodyAsText()).jsonObject["cleanupEnabled"]!!.jsonPrimitive.boolean,
            )
            assertTrue(app.logRetentionService.scheduledCleanupRunning)

            val disabled = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":false}""")
            }
            assertEquals(HttpStatusCode.OK, disabled.status)
            assertFalse(
                Json.parseToJsonElement(disabled.bodyAsText()).jsonObject["cleanupEnabled"]!!.jsonPrimitive.boolean,
            )
            assertFalse(app.logRetentionService.scheduledCleanupRunning)
            assertEquals(false, app.retentionSettingsRepository.loadCleanupEnabled())

            app.requestLogRepository.insert(
                requestLog(
                    requestId = "manual-cleanup-while-disabled",
                    createdAt = System.currentTimeMillis() - 31 * 86_400_000L,
                ),
            )
            val manualCleanup = client.post("/admin/api/request-log-retention/cleanup") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, manualCleanup.status)
            assertEquals(
                "1",
                Json.parseToJsonElement(manualCleanup.bodyAsText())
                    .jsonObject["deleted"]!!
                    .jsonPrimitive
                    .content,
            )
            assertFalse(app.logRetentionService.scheduledCleanupRunning)
        }
    }

    @Test
    fun `overlapping retention updates keep runtime and persisted state aligned`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            suspend fun setCleanup(enabled: Boolean) = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":$enabled}""")
            }

            repeat(10) { index ->
                val enabled = index % 2 == 0
                val result = async(Dispatchers.Default) { setCleanup(enabled) }
                assertEquals(HttpStatusCode.OK, result.await().status)
            }

            val persisted = app.retentionSettingsRepository.loadCleanupEnabled()
            assertEquals(app.logRetentionService.currentPolicy().cleanupEnabled, persisted)
            assertEquals(app.logRetentionService.scheduledCleanupRunning, persisted)
        }
    }

    @Test
    fun `admin request logs expose id cursors for loading older pages`() = runBlocking {
        repeat(3) { index ->
            app.requestLogRepository.insert(
                requestLog(
                    requestId = "cursor-page-$index",
                    createdAt = 1_000L + index,
                ),
            )
        }

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, _) = client.loginAdmin()

            val firstPage = client.get("/admin/api/request-logs?limit=2") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, firstPage.status)
            val firstIds = Json.parseToJsonElement(firstPage.bodyAsText())
                .jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
            assertEquals(2, firstIds.size)
            assertEquals(firstIds.sortedDescending(), firstIds)

            val secondPage = client.get("/admin/api/request-logs?limit=2&beforeId=${firstIds.last()}") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, secondPage.status)
            val secondIds = Json.parseToJsonElement(secondPage.bodyAsText())
                .jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
            assertEquals(1, secondIds.size)
            assertTrue(secondIds.single() < firstIds.last())
        }
    }

    @Test
    fun `looks up reference price for model`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val login = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val setCookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            val sessionCookie = setCookies.first { it.startsWith("gw_session=") }.substringBefore(";")
            val csrf = setCookies.first { it.startsWith("gw_csrf=") }.substringAfter("=").substringBefore(";")

            val response = client.get("/admin/api/model-prices/reference?model=deepseek-chat") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("0.27", json["inputPricePerMillion"]?.jsonPrimitive?.content)
            assertEquals("1.1", json["outputPricePerMillion"]?.jsonPrimitive?.content)
            assertEquals("0.07", json["cachedInputPricePerMillion"]?.jsonPrimitive?.content)
            assertEquals("官方基准价", json["source"]?.jsonPrimitive?.content)
        }
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json() }
    }

    private suspend fun HttpClient.loginAdmin(
        username: String = "admin",
        password: String = "test-password",
    ): Pair<String, String> {
        val login = post("/admin/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val setCookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val sessionCookie = setCookies.first { it.startsWith("gw_session=") }.substringBefore(";")
        val csrf = setCookies.first { it.startsWith("gw_csrf=") }.substringAfter("=").substringBefore(";")
        return sessionCookie to csrf
    }

    private suspend fun HttpClient.assertAdminRequestLogStream(requestId: String, expected: Boolean) {
        val (sessionCookie, csrf) = loginAdmin()
        val response = get("/admin/api/request-logs") {
            header(HttpHeaders.Cookie, sessionCookie)
            header("X-CSRF-Token", csrf)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val loggedStream = Json.parseToJsonElement(response.bodyAsText())
            .jsonArray
            .first { it.jsonObject["requestId"]?.jsonPrimitive?.content == requestId }
            .jsonObject["stream"]
            ?.jsonPrimitive
            ?.content
        assertEquals(expected.toString(), loggedStream)
    }

    private fun config(
        firstTokenTimeoutMs: Long = 2_000,
        idleTimeoutMs: Long = 2_000,
        totalTimeoutMs: Long = 5_000,
        corsOrigins: List<String> = emptyList(),
        maxAttempts: Int = 2,
        cooldownTransientBaseMs: Long = 3_600_000,
        cooldownRateLimitDefaultMs: Long = 60_000,
        cooldownRateLimitMaxMs: Long = 60_000,
        ttftSlowThresholdMs: Long = 600_000,
        ttftSlowStreak: Int = 100,
    ): GatewayConfig = GatewayConfig(
        port = 0,
        databaseUrl = postgres.jdbcUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        masterKey = ByteArray(32) { 7 },
        adminUsername = "admin",
        adminPassword = "test-password",
        corsOrigins = corsOrigins,
        requestBodyLimitBytes = 1024 * 1024,
        connectTimeoutMs = 1_000,
        firstTokenTimeoutMs = firstTokenTimeoutMs,
        idleTimeoutMs = idleTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        maxAttempts = maxAttempts,
        drainTimeoutMs = 1_000,
        staticDir = Path.of("web/dist"),
        cookieSecure = false,
        retention = RetentionPolicy(cleanupEnabled = false),
        cooldown = CooldownPolicy(
            transientBaseMs = cooldownTransientBaseMs,
            transientMaxMs = 3_600_000,
            rateLimitDefaultMs = cooldownRateLimitDefaultMs,
            rateLimitMaxMs = cooldownRateLimitMaxMs,
            authFailureMs = 600_000,
            ttftSlowThresholdMs = ttftSlowThresholdMs,
            ttftSlowStreak = ttftSlowStreak,
        ),
    )

    private fun requestLog(requestId: String, createdAt: Long) = RequestLogRecord(
        requestId = requestId,
        clientKeyId = null,
        clientKeyName = null,
        alias = "fast-chat",
        channelId = null,
        channelName = null,
        upstreamModel = null,
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 1,
        inputTokens = 1,
        outputTokens = 1,
        cachedTokens = null,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = 2,
        usageKnown = true,
        costAmount = null,
        costCurrency = null,
        inputPricePerMillion = null,
        outputPricePerMillion = null,
        cachedInputPricePerMillion = null,
        errorCode = null,
        failureStage = null,
        errorMessage = null,
        upstreamErrorCode = null,
        createdAt = createdAt,
    )

    private suspend fun awaitRequestLogCount(count: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (app.requestLogRepository.list(10).size >= count) return
            delay(50)
        }
        error("expected $count request logs within ${timeoutMs}ms")
    }

    @Test
    fun anthropicMessagesStreamIsPassedThroughAndRecordsUsage() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        createAlias("claude-fast", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
                header("anthropic-beta", "prompt-caching-2024-07-31")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: message_start"), "BODY=[$body]")
            assertTrue(body.contains("event: content_block_delta"), "BODY=[$body]")
            assertTrue(body.contains("event: message_stop"), "BODY=[$body]")
            assertTrue(!body.contains("[DONE]"), "BODY=[$body]")

            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.MESSAGES, log.protocol)
            assertEquals(true, log.stream)
            assertEquals(15L, log.inputTokens)
            assertEquals(4L, log.outputTokens)
            assertEquals(3L, log.cachedTokens)
            assertEquals(19L, log.totalTokens)
            assertTrue(log.usageKnown)
            assertNotNull(log.firstTokenMs)
            assertEquals(19L, app.clientKeyQuotaService.currentUsage(app.clientKeyRepository.list().single().id).dailyTokens)
            client.assertAdminRequestLogStream(log.requestId, expected = true)

            val upstreamRequest = fake.requests.single()
            val upstreamJson = Json.parseToJsonElement(upstreamRequest.body).jsonObject
            assertEquals("claude-sonnet-4-5", upstreamJson["model"]!!.jsonPrimitive.content)
            assertEquals("hi", upstreamJson["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("upstream-secret", upstreamRequest.xApiKey)
            assertNull(upstreamRequest.authorization)
            assertEquals("2023-06-01", upstreamRequest.anthropicVersion)
            assertEquals("prompt-caching-2024-07-31", upstreamRequest.anthropicBeta)
        }
    }

    @Test
    fun anthropicMessagesNonStreamingAcceptsBearerClientKey() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        createAlias("claude-fast", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("msg_fake"))
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.MESSAGES, log.protocol)
            assertEquals(false, log.stream)
            assertEquals(15L, log.inputTokens)
            assertEquals(4L, log.outputTokens)
            assertEquals(19L, log.totalTokens)
            client.assertAdminRequestLogStream(log.requestId, expected = false)
        }
    }

    @Test
    fun anthropicMessagesPassesThroughStreamErrors() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-error",
            baseUrl = fake.baseUrl + "/sse-error/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        createAlias("claude-fast", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: error"), "BODY=[$body]")
            assertTrue(body.contains("\"type\":\"error\""), "BODY=[$body]")
            val log = awaitRequestLog()
            assertEquals("messages_failed", log.errorCode)
            assertNull(log.firstTokenMs)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun anthropicMessagesAcceptsEmptyCompletedStreamWithoutFirstToken() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-empty-completion",
            baseUrl = fake.baseUrl + "/empty-message/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        createAlias("claude-fast", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: message_start"), "BODY=[$body]")
            assertTrue(body.contains("event: message_delta"), "BODY=[$body]")
            assertTrue(body.contains("event: message_stop"), "BODY=[$body]")
            assertTrue(!body.contains("event: error"), "BODY=[$body]")
            assertEquals(1, fake.requests.size)

            val log = awaitRequestLog()
            assertEquals(HttpStatusCode.OK.value, log.statusCode)
            assertNull(log.errorCode)
            assertNull(log.firstTokenMs)
            assertEquals(15L, log.inputTokens)
            assertEquals(4L, log.outputTokens)
            assertTrue(log.usageKnown)
        }
    }

    @Test
    fun anthropicMessagesRetriesNativeOverloadedStreamErrorBeforeContent() = runBlocking {
        val primary = createChannelWithBaseUrl(
            name = "anthropic-overloaded",
            baseUrl = fake.baseUrl + "/sse-error/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        val secondary = createChannelWithBaseUrl(
            name = "anthropic-backup",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        createAlias("claude-fast", primary to "claude-sonnet-4-5", secondary to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("event: message_start"), "BODY=[$body]")
            assertTrue(body.contains("event: message_stop"), "BODY=[$body]")
            assertTrue(!body.contains("event: error"), "BODY=[$body]")
            assertEquals(listOf("sse-error", "ok"), fake.requests.map { it.scenario })

            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(2, log.upstreamAttempts)
            assertEquals(HttpStatusCode.OK.value, log.statusCode)
            assertNull(log.errorCode)
            assertEquals(15L, log.inputTokens)
            assertEquals(4L, log.outputTokens)
            assertTrue(log.usageKnown)
        }
    }

    @Test
    fun anthropicMessagesUsesNativeErrorEnvelopeForGatewayAuthenticationFailures() = runBlocking {
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"messages":[]}""")
            }
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("error", body["type"]!!.jsonPrimitive.content)
            assertEquals("authentication_error", body["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun anthropicMessagesPreservesNativeUpstreamErrorBodyAndStatus() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-error",
            baseUrl = fake.baseUrl + "/500/v1",
            protocols = setOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        createAlias("claude-fast", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-fast","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(
                """{"type":"error","error":{"type":"api_error","message":"upstream down"}}""",
                body,
            )
            val log = awaitRequestLog()
            assertEquals(500, log.statusCode)
            assertEquals("api_error", log.upstreamErrorCode)
        }
    }

    private suspend fun createChannel(name: String, scenario: String): ChannelRecord {
        return createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1")
    }

    private suspend fun createChannelWithBaseUrl(
        name: String,
        baseUrl: String,
        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        modelMappings: List<ChannelModelRecord> = mappingsOf("gpt-4o-mini", "deepseek-chat", "m1", "m2"),
        priority: Int = 0,
        authType: String = "bearer",
    ): ChannelRecord {
        val encrypted = app.credentialCipher.encrypt("upstream-secret")
        return app.channelRepository.create(
            name = name,
            baseUrl = baseUrl,
            credential = encrypted,
            authType = authType,
            protocols = protocols,
            enabled = true,
            modelMappings = modelMappings,
            priority = priority,
        )
    }

    private fun mappingsOf(vararg models: String): List<ChannelModelRecord> =
        models.map { ChannelModelRecord(upstreamModel = it) }

    private suspend fun createChannelWithProtocols(
        name: String,
        scenario: String,
        protocols: Set<GatewayProtocol>,
    ): ChannelRecord = createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1", protocols)

    private suspend fun createAlias(alias: String, vararg candidates: Pair<ChannelRecord, String>): AliasWithCandidates =
        app.aliasRepository.create(
            alias = alias,
            enabled = true,
            candidates = candidates.map { NewCandidate(it.first.id, it.second) },
        )

    private suspend fun createTieredAlias(
        alias: String,
        vararg candidates: Triple<ChannelRecord, String, Int>,
    ): AliasWithCandidates = app.aliasRepository.create(
        alias = alias,
        enabled = true,
        candidates = candidates.map { NewCandidate(it.first.id, it.second, it.third) },
    )

    private suspend fun createClientKey(
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",
            keyHash = generated.hash,
            keyPrefix = generated.prefix,
            enabled = true,
            expiresAt = null,
            quotas = quotas,
        )
        return generated.raw
    }

    private suspend fun createPrice(
        channel: ChannelRecord,
        upstreamModel: String,
        input: String,
        output: String,
        cached: String? = null,
    ) = app.modelPriceRepository.create(
        channelId = channel.id,
        upstreamModel = upstreamModel,
        currency = "USD",
        inputPricePerMillion = BigDecimal(input),
        outputPricePerMillion = BigDecimal(output),
        cachedInputPricePerMillion = cached?.let(::BigDecimal),
    )

    private suspend fun awaitRequestLog(timeoutMs: Long = 3_000): RequestLogRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val logs = app.requestLogRepository.list(10)
            if (logs.isNotEmpty()) return logs.first()
            delay(50)
        }
        error("request log not written within ${timeoutMs}ms")
    }

    private suspend fun awaitLogWithEffort(effort: String, timeoutMs: Long = 3_000): RequestLogRecord {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val log = app.requestLogRepository.list(10).firstOrNull { it.reasoningEffort == effort }
            if (log != null) return log
            delay(50)
        }
        error("request log with reasoning effort $effort not written within ${timeoutMs}ms")
    }

    private suspend fun awaitCancellation(timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (fake.cancellations.get() > 0) return
            delay(50)
        }
        error("upstream request was not cancelled within ${timeoutMs}ms")
    }

    private fun truncateAll() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use {
                it.execute(
                    "truncate table admin_audit_logs, retention_settings, request_logs, alias_candidates, model_aliases, client_key_quota_usage, client_keys, channels, admin_users restart identity cascade",
                )
            }
        }
    }
}
