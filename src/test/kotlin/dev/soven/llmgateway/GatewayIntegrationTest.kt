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
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
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
        app = GatewayApplication(config(exposeUpstreamModels = true))
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
    fun `alias only channel hides upstream model and disables direct routing`() = runBlocking {
        app.close()
        app = GatewayApplication(config(exposeUpstreamModels = true))
        val channel = createChannelWithBaseUrl(
            "alias-only",
            "${fake.baseUrl}/ok/v1",
            exposeUpstreamModels = false,
        )
        createAlias("qwen3.8-flash", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            val body = models.bodyAsText()
            assertTrue(body.contains("qwen3.8-flash"), "BODY=[$body]")
            assertTrue(!body.contains("gpt-4o-mini"), "BODY=[$body]")

            val direct = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.NotFound, direct.status)
            assertTrue(direct.bodyAsText().contains("model_not_found"))

            val viaAlias = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3.8-flash","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, viaAlias.status)
            assertTrue(viaAlias.bodyAsText().contains("Hello!"))
        }
    }

    @Test
    fun `canonical model mapping controls exposure and direct routing`() = runBlocking {
        app.close()
        app = GatewayApplication(config(exposeUpstreamModels = true))
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
    fun `admin channel model mappings persist and reject duplicate canonical ids`() = runBlocking {
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
                        {"upstreamModel":"deepseek-chat","canonicalModelId":"SAME-ID","mappingSource":"manual"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, duplicate.status)
            assertTrue(duplicate.bodyAsText().contains("规范 ID 不能重复"))
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
        app = GatewayApplication(config(exposeUpstreamModels = true))
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
            assertTrue(body.contains("\"supported\":true"), "BODY=[$body]")
            assertTrue(body.contains("\"supported\":false"), "BODY=[$body]")
        }
    }

    @Test
    fun `direct mode reports protocol mismatch`() = runBlocking {
        app.close()
        app = GatewayApplication(config(exposeUpstreamModels = true))
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
        app = GatewayApplication(config(exposeUpstreamModels = true))
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
    fun `migrations tolerate duplicate rows and add channel exposure default`() {
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
                }
            }

            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from channel_models").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1").use { result ->
                        assertTrue(result.next())
                        assertEquals("10", result.getString(1))
                    }
                    statement.executeQuery("select expose_upstream_models from channels where id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1))
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

    private fun config(
        firstTokenTimeoutMs: Long = 2_000,
        idleTimeoutMs: Long = 2_000,
        totalTimeoutMs: Long = 5_000,
        exposeUpstreamModels: Boolean = false,
    ): GatewayConfig = GatewayConfig(
        port = 0,
        databaseUrl = postgres.jdbcUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        masterKey = ByteArray(32) { 7 },
        adminUsername = "admin",
        adminPassword = "test-password",
        corsOrigins = emptyList(),
        requestBodyLimitBytes = 1024 * 1024,
        connectTimeoutMs = 1_000,
        firstTokenTimeoutMs = firstTokenTimeoutMs,
        idleTimeoutMs = idleTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        maxAttempts = 2,
        drainTimeoutMs = 1_000,
        staticDir = Path.of("web/dist"),
        cookieSecure = false,
        exposeUpstreamModels = exposeUpstreamModels,
    )

    private suspend fun createChannel(name: String, scenario: String): ChannelRecord {
        return createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1")
    }

    private suspend fun createChannelWithBaseUrl(
        name: String,
        baseUrl: String,
        protocols: Set<GatewayProtocol> = GatewayProtocol.default,
        exposeUpstreamModels: Boolean = true,
        modelMappings: List<ChannelModelRecord> = mappingsOf("gpt-4o-mini", "deepseek-chat", "m1", "m2"),
    ): ChannelRecord {
        val encrypted = app.credentialCipher.encrypt("upstream-secret")
        return app.channelRepository.create(
            name = name,
            baseUrl = baseUrl,
            credential = encrypted,
            authType = "bearer",
            protocols = protocols,
            enabled = true,
            modelMappings = modelMappings,
            exposeUpstreamModels = exposeUpstreamModels,
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

    private suspend fun createClientKey(): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",
            keyHash = generated.hash,
            keyPrefix = generated.prefix,
            enabled = true,
            expiresAt = null,
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
                    "truncate table request_logs, alias_candidates, model_aliases, client_keys, channels, admin_users restart identity cascade",
                )
            }
        }
    }
}
