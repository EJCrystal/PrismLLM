package dev.soven.llmgateway.integration

import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.channel.domain.ChannelModelRecord
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.repo.CooldownStateRepository
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.resilience.CooldownStateSnapshot
import dev.soven.llmgateway.resilience.FailureKind
import io.ktor.client.request.header
import io.ktor.client.request.delete
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
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
@Tag("integration")
@Tag("shard-4")
@Tag("channel")
class RoutingFailoverIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `falls back to next candidate before first token`() = runBlocking {
        val primary = createChannel("primary", "500")
        val secondary = createChannel("secondary", "ok")
        exposeAsModel("fast-chat", primary to "m1", secondary to "m2")
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
            // 每次尝试都应保留渠道、密钥、上游模型、状态码、错误原因和耗时。
            assertEquals(listOf(1, 2), log.attempts.map { it.index })
            val failedAttempt = log.attempts.first()
            assertEquals(primary.id, failedAttempt.channelId)
            assertEquals("primary", failedAttempt.channelName)
            assertEquals(primary.keys.single().id, failedAttempt.channelKeyId)
            assertEquals("m1", failedAttempt.upstreamModel)
            assertEquals(500, failedAttempt.statusCode)
            assertEquals("upstream_error", failedAttempt.errorCode)
            assertTrue(failedAttempt.errorMessage!!.contains("上游服务不可用"), failedAttempt.errorMessage)
            assertTrue(failedAttempt.retryable)
            assertTrue(failedAttempt.elapsedMs >= 0, "attempt elapsed should be recorded")
            val succeededAttempt = log.attempts.last()
            assertEquals(secondary.id, succeededAttempt.channelId)
            assertEquals(200, succeededAttempt.statusCode)
            assertNull(succeededAttempt.errorCode)
            assertTrue(succeededAttempt.committed)
            assertNotNull(succeededAttempt.firstTokenMs)
            assertTrue(app.metrics.scrape().contains("gateway_upstream_retries_total"))
        }
    }

    @Test
    fun `upstream pseudo 400 triggers failover to next candidate and cools down failed channel`() = runBlocking {
        val primary = createChannel("pseudo-400-primary", "pseudo-400")
        val secondary = createChannel("pseudo-400-secondary", "ok")
        exposeAsModel("pseudo-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"pseudo-chat","stream":true,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
            assertEquals(2, log.upstreamAttempts)
            assertEquals(listOf(1, 2), log.attempts.map { it.index })

            val failedAttempt = log.attempts.first()
            assertEquals(primary.id, failedAttempt.channelId)
            assertEquals("pseudo-400-primary", failedAttempt.channelName)
            assertEquals(400, failedAttempt.statusCode)
            assertEquals("upstream_error", failedAttempt.errorCode)
            assertTrue(failedAttempt.errorMessage!!.contains("上游服务不可用"), failedAttempt.errorMessage)
            assertTrue(failedAttempt.retryable)

            val succeededAttempt = log.attempts.last()
            assertEquals(secondary.id, succeededAttempt.channelId)
            assertEquals(200, succeededAttempt.statusCode)
            assertNull(succeededAttempt.errorCode)
            assertTrue(succeededAttempt.committed)
        }
    }

    @Test
    fun `422 shape refusal fails over to next candidate without cooling down the channel`() = runBlocking {
        val primary = createChannel("shape-422-primary", "shape-422")
        val secondary = createChannel("shape-422-secondary", "ok")
        exposeAsModel("shape-422-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"shape-422-chat","stream":true,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(2, log.upstreamAttempts)

            val failedAttempt = log.attempts.first()
            assertEquals(primary.id, failedAttempt.channelId)
            assertEquals(422, failedAttempt.statusCode)
            assertEquals("upstream_error", failedAttempt.errorCode)
            assertTrue(failedAttempt.retryable)
            // 形状拒绝是请求与上游不匹配，不是渠道故障，不应让主渠道进入冷却。
            assertFalse(app.candidateCooldown.isBlocked(primary.id, primary.keys.single().id, "m1"))

            val succeededAttempt = log.attempts.last()
            assertEquals(secondary.id, succeededAttempt.channelId)
            assertEquals(200, succeededAttempt.statusCode)
            assertTrue(succeededAttempt.committed)
        }
    }

    @Test
    fun `all 422 candidates exhausted returns unprocessable entity`() = runBlocking {
        val primary = createChannel("all-shape-422", "shape-422")
        exposeAsModel("exhausted-shape-422-chat", primary to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"exhausted-shape-422-chat","stream":false,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(1, log.upstreamAttempts)
            val failedAttempt = log.attempts.single()
            assertEquals(422, failedAttempt.statusCode)
            assertTrue(failedAttempt.retryable)
        }
    }

    @Test
    fun `standard 400 does not retry and returns bad request immediately`() = runBlocking {
        val primary = createChannel("standard-400-primary", "standard-400")
        val secondary = createChannel("standard-400-secondary", "ok")
        exposeAsModel("standard-400-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"standard-400-chat","stream":false,"messages":[]}""")
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
    fun `all pseudo 400 candidates exhausted returns bad gateway 502`() = runBlocking {
        val primary = createChannel("all-pseudo-400", "pseudo-400")
        exposeAsModel("exhausted-pseudo-chat", primary to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"exhausted-pseudo-chat","stream":false,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(502, log.statusCode)
            val failedAttempt = log.attempts.single()
            assertEquals(400, failedAttempt.statusCode)
            assertTrue(failedAttempt.retryable)
        }
    }

    @Test
    fun `upstream pseudo 400 with blank code falls back to error type and triggers failover`() = runBlocking {
        val primary = createChannel("pseudo-blank-code-primary", "pseudo-400-blank-code")
        val secondary = createChannel("pseudo-blank-code-secondary", "ok")
        exposeAsModel("pseudo-blank-code-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"pseudo-blank-code-chat","stream":false,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
            assertEquals(2, log.upstreamAttempts)

            val failedAttempt = log.attempts.first()
            assertEquals(primary.id, failedAttempt.channelId)
            assertEquals(400, failedAttempt.statusCode)
            assertEquals("upstream_error", failedAttempt.upstreamErrorCode)
            assertTrue(failedAttempt.retryable)
        }
    }

    @Test
    fun `standard 400 with upstream error keyword in message does not trigger failover`() = runBlocking {
        val primary = createChannel("standard-msg-primary", "standard-400-with-upstream-error-message")
        val secondary = createChannel("standard-msg-secondary", "ok")
        exposeAsModel("standard-msg-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"standard-msg-chat","stream":false,"messages":[]}""")
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
    fun `structured 400 without message does not trigger failover even if other fields contain keyword`() = runBlocking {
        val primary = createChannel("structured-no-msg-primary", "structured-400-no-message-debug-keyword")
        val secondary = createChannel("structured-no-msg-secondary", "ok")
        exposeAsModel("structured-no-msg-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"structured-no-msg-chat","stream":false,"messages":[]}""")
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
    fun `unstructured plaintext 400 with failure keyword triggers failover`() = runBlocking {
        val primary = createChannel("unstructured-primary", "unstructured-400-pseudo")
        val secondary = createChannel("unstructured-secondary", "ok")
        exposeAsModel("unstructured-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"unstructured-chat","stream":false,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals(secondary.id, log.channelId)
            assertEquals(200, log.statusCode)
            assertEquals(2, log.upstreamAttempts)

            val failedAttempt = log.attempts.first()
            assertEquals(primary.id, failedAttempt.channelId)
            assertEquals(400, failedAttempt.statusCode)
            assertTrue(failedAttempt.retryable)
        }
    }

    @Test
    fun `cools down unhealthy channel and skips it on later requests`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val primary = createChannel("primary", "500")
        val secondary = createChannel("secondary", "ok")
        exposeAsModel("fast-chat", primary to "m1", secondary to "m2")
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
    fun `same channel keys fail over by key priority and record key name`() = runBlocking {
        app.channelRepository.create(
            name = "key-priority",
            baseUrl = "${fake.baseUrl}/key-route/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("key-primary"),
                    priority = 1,
                    models = listOf("gpt-4o-mini"),
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("key-backup"),
                    priority = 2,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
            priority = 1,
        )
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
            assertEquals(
                listOf("Bearer key-primary", "Bearer key-backup", "Bearer key-backup"),
                fake.requests.map { it.authorization },
            )
            awaitRequestLogCount(2)
            val logs = app.requestLogRepository.list(10)
            assertEquals(setOf("backup"), logs.map { it.channelKeyName }.toSet())
            assertEquals(listOf(1, 2), logs.mapNotNull { it.upstreamAttempts }.sorted())
        }
    }

    @Test
    fun `fail-open retries cooled candidates when all are blocked`() = runBlocking {
        app.close()
        app = GatewayApplication(config(cooldownTransientBaseMs = 60_000))
        val primary = createChannel("primary", "500")
        exposeAsModel("fast-chat", primary to "m1")
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
        exposeUnderCanonicalIdInOrder("fast-chat", Triple(primary, "m1", 0), Triple(secondary, "m2", 0))
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
            awaitCondition("candidate cooldown to expire", timeoutMs = 5_000) {
                app.candidateCooldown.cooldownState(channel.id, channel.keys.single().id, "alias-fail") == null
            }
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
        // 直连档位来自渠道优先级：慢渠道必须排在前面，等价于旧别名里 position 0/1 的主备顺序。
        val primary = createChannelWithBaseUrl("slow", "${fake.baseUrl}/slow/v1", priority = 0)
        val secondary = createChannelWithBaseUrl("secondary", "${fake.baseUrl}/ok/v1", priority = 10)
        exposeAsModel("fast-chat", primary to "m1", secondary to "m2")
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
        val primary = createChannelWithBaseUrl("primary", "${fake.baseUrl}/500/v1", priority = 0)
        val secondary = createChannelWithBaseUrl("secondary", "${fake.baseUrl}/500/v1", priority = 10)
        val tertiary = createChannelWithBaseUrl("tertiary", "${fake.baseUrl}/ok/v1", priority = 20)
        exposeAsModel("fast-chat", primary to "m1", secondary to "m2", tertiary to "deepseek-chat")
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

            // 两个渠道各自按渠道键冷却，secondary 的失败晚于 primary；必须等两者都到期，
            // 否则第二次请求合法地跳过仍在冷却的 secondary，尝试次数会少一次。
            awaitCondition("transient cooldowns to expire", timeoutMs = 5_000) {
                !app.candidateCooldown.isBlocked(primary.id, primary.keys.single().id, "m1") &&
                    !app.candidateCooldown.isBlocked(secondary.id, secondary.keys.single().id, "m2")
            }
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            assertEquals(3, app.requestLogRepository.list(10).first().upstreamAttempts)
            assertEquals(listOf("500", "500", "ok", "500", "500", "ok"), fake.requests.map { it.scenario })
        }
    }

    @Test
    fun `cooldown reason survives persistence round trip`() = runBlocking {
        val repository = CooldownStateRepository(app.databaseFactory.database)
        repository.upsert(
            CooldownStateSnapshot(
                stateKey = "ch:persisted",
                consecutiveFailures = 2,
                cooldownUntil = 123_456L,
                reason = "rate_limited",
            ),
        )
        try {
            val snapshot = repository.loadAll().single { it.stateKey == "ch:persisted" }
            assertEquals(2, snapshot.consecutiveFailures)
            assertEquals(123_456L, snapshot.cooldownUntil)
            assertEquals("rate_limited", snapshot.reason)
        } finally {
            repository.delete("ch:persisted")
        }
    }

    @Test
    fun `clear cooldown api clears scopes and audits the action`() = runBlocking {
        val channel = createChannel("cooldown-clear", "ok")
        val key = channel.keys.single()
        val other = createChannel("cooldown-other", "ok")
        val otherKey = other.keys.single()

        fun cool(channelId: String, keyId: String, model: String, kind: FailureKind) {
            val permit = assertNotNull(app.candidateCooldown.forceAcquire(channelId, keyId, model))
            app.candidateCooldown.recordFailure(permit, kind)
        }

        cool(channel.id, key.id, "m1", FailureKind.RATE_LIMITED)
        cool(other.id, otherKey.id, "m1", FailureKind.RATE_LIMITED)

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun clear(body: String) = client.post("/admin/api/channels/${channel.id}/cooldown/clear") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            assertEquals(HttpStatusCode.BadRequest, clear("""{"scope":"key"}""").status)
            assertEquals(HttpStatusCode.NotFound, clear("""{"scope":"key","keyId":"missing"}""").status)
            assertEquals(HttpStatusCode.BadRequest, clear("""{"scope":"candidate","keyId":"${key.id}"}""").status)
            assertEquals(HttpStatusCode.BadRequest, clear("""{"scope":"unknown"}""").status)

            assertEquals(
                HttpStatusCode.NoContent,
                clear("""{"scope":"candidate","keyId":"${key.id}","upstreamModel":"m1"}""").status,
            )
            assertFalse(app.candidateCooldown.isBlocked(channel.id, key.id, "m1"))
            assertTrue(app.candidateCooldown.isBlocked(other.id, otherKey.id, "m1"))

            // 密钥级冷却：清掉 key 本身及其候选
            cool(channel.id, key.id, "m1", FailureKind.AUTH_REJECTED)
            assertTrue(app.candidateCooldown.isBlocked(channel.id, key.id, "m1"))
            assertEquals(
                HttpStatusCode.NoContent,
                clear("""{"scope":"key","keyId":"${key.id}"}""").status,
            )
            assertFalse(app.candidateCooldown.isBlocked(channel.id, key.id, "m1"))

            // 渠道级冷却：先留一个候选冷却，再触发渠道键，最后一次性清掉
            cool(channel.id, key.id, "m2", FailureKind.RATE_LIMITED)
            cool(channel.id, key.id, "m1", FailureKind.TRANSPORT)
            assertTrue(app.candidateCooldown.isBlocked(channel.id, key.id, "m2"))
            assertEquals(HttpStatusCode.NoContent, clear("""{"scope":"channel"}""").status)
            assertFalse(app.candidateCooldown.isBlocked(channel.id, key.id, "m1"))
            assertFalse(app.candidateCooldown.isBlocked(channel.id, key.id, "m2"))
            assertTrue(app.candidateCooldown.isBlocked(other.id, otherKey.id, "m1"))
            val persisted = app.cooldownStateRepository.loadAll().map { it.stateKey }
            assertFalse(persisted.any { it == "ch:${channel.id}" || it.startsWith("key:${channel.id}:") || it.startsWith("kc:${channel.id}:") })

            val audit = app.adminAuditRepository.list(100).first {
                it.action == "channel.cooldown.clear" && it.result == "success" &&
                    it.details?.get("scope")?.jsonPrimitive?.content == "channel"
            }
            assertEquals(channel.id, audit.details?.get("channelId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `channel health exposes cooldown reason and candidate counts`() = runBlocking {
        val channel = createChannel("cooldown-health", "ok")
        val key = channel.keys.single()
        val permit = assertNotNull(app.candidateCooldown.acquire(channel.id, key.id, "m1"))
        app.candidateCooldown.recordFailure(permit, FailureKind.RATE_LIMITED, retryAfterMs = 60_000)
        val state = assertNotNull(app.candidateCooldown.cooldownState(channel.id, key.id, "m1"))

        testApplication {
            application { app.install(this) }
            val (cookie, _) = client.loginAdmin()

            val reportResponse = client.get("/admin/api/channel-health?window=24h") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, reportResponse.status)
            val row = Json.parseToJsonElement(reportResponse.bodyAsText()).jsonObject["channels"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["channelId"]!!.jsonPrimitive.content == channel.id }
            assertEquals("rate_limited", row["cooldownReason"]!!.jsonPrimitive.content)
            assertEquals("1", row["coolingCandidates"]!!.jsonPrimitive.content)
            assertEquals("4", row["totalCandidates"]!!.jsonPrimitive.content)
            assertEquals(state.until.toString(), row["cooldownUntil"]!!.jsonPrimitive.content)

            val detailResponse = client.get("/admin/api/channels/${channel.id}/health?window=24h") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val availability = Json.parseToJsonElement(detailResponse.bodyAsText()).jsonObject["availability"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["model"]!!.jsonPrimitive.content == "m1" }
            assertEquals("COOLDOWN", availability["state"]!!.jsonPrimitive.content)
            assertEquals("rate_limited", availability["reason"]!!.jsonPrimitive.content)
            assertEquals("candidate", availability["cooldownScope"]!!.jsonPrimitive.content)
            assertEquals(state.until.toString(), availability["until"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `falls back when upstream connection fails before first token`() = runBlocking {
        val primary = createChannelWithBaseUrl("unreachable", "http://127.0.0.1:1/v1")
        val secondary = createChannel("secondary", "ok")
        exposeAsModel("fast-chat", primary to "m1", secondary to "m2")
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
        exposeAsModel("fast-chat", primary to "m1", secondary to "m2")
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
        exposeAsModel("fast-chat", channel to "m1")
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
        exposeAsModel("fast-chat", channel to "m1")
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
    fun `returns gateway timeout when first token is too slow`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 300, idleTimeoutMs = 300, totalTimeoutMs = 2_000))
        val channel = createChannel("slow", "slow")
        exposeAsModel("fast-chat", channel to "m1")
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
        awaitCancellationWithin()
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
    fun `admin rejects retention update with missing field as 400`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("invalid_request"))
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
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val models = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            assertEquals(HttpStatusCode.OK, models.status)
            val body = models.bodyAsText()
            assertTrue(body.contains("qwen3.8-flash"), "BODY=[$body]")
            assertTrue(body.contains("gpt-4o-mini"), "BODY=[$body]")
            assertTrue(!body.contains("Qwen3.8-Flash"), "BODY=[$body]")

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

            // 同一上游模型只按 canonical ID 对外暴露一次。
            val duplicateNameRequest = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"custom-qwen","stream":false,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.NotFound, duplicateNameRequest.status)
        }
    }

    @Test
    fun `falls back to upstream model default when canonical default is missing`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "openai-canonical",
            "${fake.baseUrl}/ok/v1",
            modelMappings = listOf(ChannelModelRecord("gpt-4o-mini", "gpt-4o-mini-canonical")),
        )
        exposeAsModel("fallback-priced-chat", channel to "gpt-4o-mini")
        createDefaultPrice("gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fallback-priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000016")))
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("1")))
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
    fun `conversion failure falls back to native candidate instead of aborting`() = runBlocking {
        val conversion = createChannelWithBaseUrl(
            name = "conversion-rejects-image",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        val native = createChannelWithBaseUrl(
            name = "native-handles-image",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            modelMappings = listOf(ChannelModelRecord("gpt-4o-mini")),
        )
        exposeAsModel("conversion-fallback", conversion to "claude-sonnet-4-5", native to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"conversion-fallback","messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}}]}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(listOf("/ok/v1/chat/completions"), fake.requests.map { it.path })
            val log = awaitRequestLog()
            assertEquals(native.id, log.channelId)
            assertEquals(1, log.upstreamAttempts)
        }
    }
}
