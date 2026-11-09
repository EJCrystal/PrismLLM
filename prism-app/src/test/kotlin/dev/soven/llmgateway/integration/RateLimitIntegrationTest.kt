package dev.soven.llmgateway.integration

import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.ChannelKeyUpdate
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.ChannelKeyRateLimitDecision
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import kotlin.test.assertIs
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
@Tag("shard-1")
class RateLimitIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `channel key RPM is shared across models and returns retry after`() = runBlocking {
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1)
        val channel = app.channelRepository.create(
            name = "shared-key-rpm",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-a",
                    credential = app.credentialCipher.encrypt("shared-secret"),
                    models = listOf("m1", "m2"),
                    rateLimit = policy,
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1", "m2"),
        )
        exposeAsModel("limited-model-one", channel to "m1")
        exposeAsModel("limited-model-two", channel to "m2")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            val first = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"limited-model-one","messages":[]}""")
            }
            val second = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"limited-model-two","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            assertTrue(second.bodyAsText().contains("upstream_key_rate_limit_exceeded"))
            assertTrue(second.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it >= 1 } == true)
            awaitRequestLogCount(2)
            assertEquals(0, scalarInt("select count(*) from channel_key_health"))
        }

        assertEquals(1, fake.requests.size)
    }

    @Tag("channel-key-rate-limit")
    @Test
    fun `channel key RPM is shared across models and fails over to backup`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "rpm-shared-fallback",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("primary-secret"),
                    priority = 1,
                    models = listOf("m1", "m2"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1),
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("backup-secret"),
                    priority = 2,
                    models = listOf("m1", "m2"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1", "m2"),
        )
        exposeAsModel("rpm-shared-a", channel to "m1")
        exposeAsModel("rpm-shared-b", channel to "m2")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send(model: String) = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send("rpm-shared-a").status)
            assertEquals(HttpStatusCode.OK, send("rpm-shared-b").status)
            awaitRequestLogCount(2)

            val logs = app.requestLogRepository.list(10)
            val primaryLog = logs.single { it.channelKeyName == "primary" }
            val backupLog = logs.single { it.channelKeyName == "backup" }
            assertEquals("rpm-shared-a", primaryLog.alias)
            assertEquals("rpm-shared-b", backupLog.alias)
            assertEquals(1, primaryLog.upstreamAttempts)
            assertEquals(1, backupLog.upstreamAttempts)
            assertTrue(
                app.metrics.scrape().contains(
                    """gateway_upstream_key_rate_limit_skips_total{channel="rpm-shared-fallback",reason="requests_per_minute"} 1.0""",
                ),
            )
            assertEquals(0, scalarInt("select count(*) from channel_key_health"))
        }

        assertEquals(
            listOf("Bearer primary-secret", "Bearer backup-secret"),
            fake.requests.map { it.authorization },
        )
    }

    @Tag("channel-key-rate-limit")
    @Test
    fun `channel key concurrency fails over to backup and returns 429 when both keys are busy`() = runBlocking {
        app.close()
        app = GatewayApplication(config(totalTimeoutMs = 20_000))
        val channel = app.channelRepository.create(
            name = "concurrency-end-to-end",
            baseUrl = "${fake.baseUrl}/concurrency-hold/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("primary-secret"),
                    priority = 1,
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("backup-secret"),
                    priority = 2,
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("concurrency-end-to-end", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"concurrency-end-to-end","stream":true,"messages":[]}""")
            }

            val releaseHeldRequests = fake.holdChatRequests()
            val first = async { send() }
            try {
                awaitCondition("first request to reach the primary key") {
                    fake.requests.any { it.authorization == "Bearer primary-secret" }
                }
                val second = async { send() }
                try {
                    awaitCondition("second request to fail over to the backup key") {
                        fake.requests.any { it.authorization == "Bearer backup-secret" }
                    }

                    val rejected = send()
                    assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
                    assertTrue(rejected.bodyAsText().contains("upstream_key_rate_limit_exceeded"))
                    assertTrue(rejected.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it >= 1 } == true)

                    releaseHeldRequests.complete(Unit)
                    val firstResponse = first.await()
                    val secondResponse = second.await()
                    assertEquals(HttpStatusCode.OK, firstResponse.status)
                    assertEquals(HttpStatusCode.OK, secondResponse.status)
                    assertTrue(firstResponse.bodyAsText().contains("[DONE]"))
                    assertTrue(secondResponse.bodyAsText().contains("[DONE]"))
                } finally {
                    releaseHeldRequests.complete(Unit)
                    second.cancel()
                }
            } finally {
                releaseHeldRequests.complete(Unit)
                first.cancel()
                fake.clearChatHold()
            }

            awaitRequestLogCount(3)
            val logs = app.requestLogRepository.list(10)
            val successfulLogs = logs.filter { it.statusCode == HttpStatusCode.OK.value }
            assertEquals(setOf("primary", "backup"), successfulLogs.map { it.channelKeyName }.toSet())
            assertTrue(successfulLogs.all { it.upstreamAttempts == 1 })
            val rejectedLog = logs.single { it.statusCode == HttpStatusCode.TooManyRequests.value }
            assertEquals("upstream_key_rate_limit_exceeded", rejectedLog.errorCode)
            assertEquals(0, rejectedLog.upstreamAttempts)
            assertTrue(
                app.metrics.scrape().contains(
                    """gateway_upstream_key_rate_limit_skips_total{channel="concurrency-end-to-end",reason="concurrency"} 3.0""",
                ),
            )
            assertEquals(2, fake.requests.size)
            assertEquals(0, scalarInt("select count(*) from channel_key_health"))
        }
    }

    @Test
    fun `local rate limit denials inspect every candidate without consuming attempt budget`() = runBlocking {
        app.close()
        app = GatewayApplication(config(maxAttempts = 1))
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1)
        val channel = app.channelRepository.create(
            name = "all-keys-locally-limited",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("primary-secret"),
                    priority = 1,
                    models = listOf("m1"),
                    rateLimit = policy,
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("backup-secret"),
                    priority = 2,
                    models = listOf("m1"),
                    rateLimit = policy,
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("all-keys-locally-limited", channel to "m1")
        val clientKey = createClientKey()
        channel.keys.forEach { key -> app.channelKeyRateLimiter.acquire(key) }

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"all-keys-locally-limited","messages":[]}""")
            }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.bodyAsText().contains("upstream_key_rate_limit_exceeded"))
        }

        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `rate limited primary key falls back to an unlimited backup key`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "rate-limit-fallback",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("primary-secret"),
                    priority = 1,
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1),
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("backup-secret"),
                    priority = 2,
                    models = listOf("m1"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("rate-limit-fallback", channel to "m1")
        val clientKey = createClientKey()
        app.channelKeyRateLimiter.acquire(channel.keys.first { it.name == "primary" })

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"rate-limit-fallback","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals("backup", log.channelKeyName)
            assertEquals(1, log.upstreamAttempts)
            assertTrue(app.metrics.scrape().contains("gateway_upstream_key_rate_limit_skips_total"))
            assertEquals(0, scalarInt("select count(*) from channel_key_health"))
        }

        assertEquals("Bearer backup-secret", fake.requests.single().authorization)
    }

    @Test
    fun `partial local rate limit does not mask an unavailable candidate`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "partial-rate-limit",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("primary-secret"),
                    priority = 1,
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 1),
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("backup-secret"),
                    priority = 2,
                    models = listOf("m1"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("partial-rate-limit", channel to "m1")
        val clientKey = createClientKey()
        val primary = channel.keys.first { it.name == "primary" }
        val backup = channel.keys.first { it.name == "backup" }
        app.channelKeyRateLimiter.acquire(primary)

        val gateHeld = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gateWriter = async {
            app.keyAvailabilityGate.withWriter(backup.id) {
                gateHeld.complete(Unit)
                releaseGate.await()
            }
        }
        gateHeld.await()

        try {
            testApplication {
                application { app.install(this) }
                val pending = async {
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $clientKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"partial-rate-limit","messages":[]}""")
                    }
                }

                val metricDeadline = System.currentTimeMillis() + 3_000
                while (!app.metrics.scrape().contains(
                        """gateway_upstream_key_rate_limit_skips_total{channel="partial-rate-limit",reason="requests_per_minute"}""",
                    )
                ) {
                    check(System.currentTimeMillis() < metricDeadline) { "request did not reach the rate-limited candidate" }
                    delay(10)
                }

                app.channelRepository.update(
                    id = channel.id,
                    name = channel.name,
                    baseUrl = channel.baseUrl,
                    keys = channel.keys.map { key ->
                        ChannelKeyUpdate(
                            id = key.id,
                            name = key.name,
                            state = if (key.id != backup.id) ChannelKeyState.ACTIVE else ChannelKeyState.MANUAL_DISABLED,
                            priority = key.priority,
                            models = key.models,
                            stateVersion = key.stateVersion,
                        )
                    },
                    authType = channel.authType,
                    protocols = channel.protocols,
                    enabled = true,
                    modelMappings = channel.modelMappings,
                )
                releaseGate.complete(Unit)

                val response = pending.await()
                assertEquals(HttpStatusCode.BadGateway, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains("no_candidate"))
                assertFalse(body.contains("upstream_key_rate_limit_exceeded"))
            }
        } finally {
            releaseGate.complete(Unit)
            gateWriter.await()
        }
    }

    @Test
    fun `channel key concurrency lease is released after proxy success and failure`() = runBlocking {
        val policy = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1)
        val successChannel = app.channelRepository.create(
            name = "concurrency-success",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-success",
                    credential = app.credentialCipher.encrypt("success-secret"),
                    models = listOf("m1"),
                    rateLimit = policy,
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val failureChannel = app.channelRepository.create(
            name = "concurrency-failure",
            baseUrl = "${fake.baseUrl}/500/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-failure",
                    credential = app.credentialCipher.encrypt("failure-secret"),
                    models = listOf("m1"),
                    rateLimit = policy,
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("concurrency-success", successChannel to "m1")
        exposeAsModel("concurrency-failure", failureChannel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            val success = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"concurrency-success","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, success.status)
            val successLease = assertIs<ChannelKeyRateLimitDecision.Allowed>(
                app.channelKeyRateLimiter.acquire(successChannel.keys.single()),
            ).lease
            assertNotNull(successLease)
            app.channelKeyRateLimiter.release(successLease)

            val failure = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"concurrency-failure","messages":[]}""")
            }
            assertTrue(failure.status.value >= 500)
            val failureLease = assertIs<ChannelKeyRateLimitDecision.Allowed>(
                app.channelKeyRateLimiter.acquire(failureChannel.keys.single()),
            ).lease
            assertNotNull(failureLease)
            app.channelKeyRateLimiter.release(failureLease)
        }
    }

    @Test
    fun `channel key concurrency lease is released after proxy timeout`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 1_000, totalTimeoutMs = 100))
        val channel = app.channelRepository.create(
            name = "concurrency-timeout",
            baseUrl = "${fake.baseUrl}/slow/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-timeout",
                    credential = app.credentialCipher.encrypt("timeout-secret"),
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("concurrency-timeout", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"concurrency-timeout","stream":true,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertTrue(response.bodyAsText().contains("upstream_timeout"))

            val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(
                app.channelKeyRateLimiter.acquire(channel.keys.single()),
            ).lease
            assertNotNull(lease)
            app.channelKeyRateLimiter.release(lease)
            awaitCancellationWithin()
        }
    }

    @Test
    fun `channel key concurrency lease is released when timeout budget expires before sending`() = runBlocking {
        app.close()
        app = GatewayApplication(config(totalTimeoutMs = 0))
        val channel = app.channelRepository.create(
            name = "concurrency-expired-budget",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-expired-budget",
                    credential = app.credentialCipher.encrypt("expired-budget-secret"),
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("concurrency-expired-budget", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"concurrency-expired-budget","messages":[]}""")
            }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertTrue(response.bodyAsText().contains("upstream_timeout"))
            val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(
                app.channelKeyRateLimiter.acquire(channel.keys.single()),
            ).lease
            assertNotNull(lease)
            app.channelKeyRateLimiter.release(lease)
        }

        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `channel key concurrency lease is released after client disconnect`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "concurrency-client-disconnect",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-client-disconnect",
                    credential = app.credentialCipher.encrypt("disconnect-secret"),
                    models = listOf("m1"),
                    rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        exposeAsModel("concurrency-client-disconnect", channel to "m1")
        val clientKey = createClientKey()
        val failNextWrite = AtomicBoolean(false)

        testApplication {
            application {
                app.install(this)
                install(createApplicationPlugin("test-rate-limit-client-disconnect") {
                    on(ResponseBodyReadyForSend) { _, _ ->
                        if (failNextWrite.compareAndSet(true, false)) {
                            throw ChannelWriteException("test client disconnect", IOException("test"))
                        }
                    }
                })
            }

            failNextWrite.set(true)
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"concurrency-client-disconnect","stream":true,"messages":[]}""")
            }
            assertEquals(499, response.status.value)

            val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(
                app.channelKeyRateLimiter.acquire(channel.keys.single()),
            ).lease
            assertNotNull(lease)
            app.channelKeyRateLimiter.release(lease)
        }
    }

    @Test
    fun `retries next candidate on rate limit before first token`() = runBlocking {
        val primary = createChannel("limited", "429")
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
            assertNull(log.errorCode)
        }
    }

    @Test
    fun `rate limit cooldown honors Retry-After on next request`() = runBlocking {
        val primary = createChannel("limited", "429-retry-after")
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
    fun `maps upstream rate limit to client error`() = runBlocking {
        val channel = createChannel("limited", "429")
        exposeAsModel("fast-chat", channel to "m1")
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
            assertNotNull(log.upstreamErrorBody)
            assertTrue(log.upstreamErrorBody!!.contains("rate limited"))
            assertTrue(log.upstreamErrorBody!!.contains("\"code\":\"rate_limit_exceeded\""))
            assertTrue(app.metrics.scrape().contains("gateway_upstream_errors_total"))
        }
    }

    @Test
    fun `rejects next request after daily token limit is consumed`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
    fun `daily cost limit uses model default price when channel has no override`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("default-cost-chat", channel to "gpt-4o-mini")
        createDefaultPrice("gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey(
            ClientKeyQuotaLimits(dailyCosts = mapOf("USD" to BigDecimal("0.000016"))),
        )

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"default-cost-chat","messages":[]}""")
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
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
            assertEquals(
                1,
                app.clientKeyQuotaService.currentUsage(clientKeyId).concurrentRequests,
                "并发请求在 2000ms 内未占用配额槽位，无法验证超限拒绝",
            )

            val rejected = send()
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertTrue(rejected.bodyAsText().contains("concurrency_limit_exceeded"))
            assertEquals(HttpStatusCode.OK, first.await().status)
            assertEquals(HttpStatusCode.OK, send().status)
        }
    }

    @Test
    fun `occupancy endpoint reports in-flight channel key and client key requests`() = runBlocking {
        val channel = createChannel("openai", "slow")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        val rawKey = createClientKey()
        val clientKeyId = app.clientKeyRepository.list().single().id

        testApplication {
            application { app.install(this) }
            val (cookie, _) = client.loginAdmin()
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $rawKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":true,"messages":[]}""")
            }
            suspend fun occupancy() = Json.parseToJsonElement(
                client.get("/admin/api/occupancy") { header(HttpHeaders.Cookie, cookie) }.bodyAsText(),
            ).jsonObject

            val first = async(Dispatchers.Default) { send() }
            var ready = false
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && !ready) {
                val json = occupancy()
                ready = json["channels"]!!.jsonArray.any { row ->
                    row.jsonObject["channelId"]!!.jsonPrimitive.content == channel.id &&
                        row.jsonObject["keys"]!!.jsonArray.any { it.jsonObject["concurrentRequests"]!!.jsonPrimitive.content == "1" }
                } && json["clientKeys"]!!.jsonArray.any {
                    it.jsonObject["clientKeyId"]!!.jsonPrimitive.content == clientKeyId
                }
                if (!ready) delay(5)
            }
            assertTrue(ready, "在途请求未出现在占用快照中")

            val snapshot = occupancy()
            val channelRow = snapshot["channels"]!!.jsonArray.single {
                it.jsonObject["channelId"]!!.jsonPrimitive.content == channel.id
            }.jsonObject
            assertEquals("1", channelRow["concurrentRequests"]!!.jsonPrimitive.content)
            val keyRow = channelRow["keys"]!!.jsonArray.single().jsonObject
            assertEquals(channel.keys.single().id, keyRow["keyId"]!!.jsonPrimitive.content)
            assertEquals("1", keyRow["concurrentRequests"]!!.jsonPrimitive.content)
            val clientKeyRow = snapshot["clientKeys"]!!.jsonArray.single().jsonObject
            assertEquals(clientKeyId, clientKeyRow["clientKeyId"]!!.jsonPrimitive.content)
            assertEquals("1", clientKeyRow["concurrentRequests"]!!.jsonPrimitive.content)

            assertEquals(HttpStatusCode.OK, first.await().status)

            var idle = false
            val idleDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < idleDeadline && !idle) {
                val json = occupancy()
                idle = json["channels"]!!.jsonArray.isEmpty() && json["clientKeys"]!!.jsonArray.isEmpty()
                if (!idle) delay(5)
            }
            assertTrue(idle, "请求结束后占用计数未清零")
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
            exposeAsModel("fast-chat", channel to "gpt-4o-mini")
            assertEquals(HttpStatusCode.OK, send("fast-chat").status)
        }
    }

    @Test
    fun `channel key rate limits persist update and cascade on key deletion`() = runBlocking {
        val initialPolicy = ChannelKeyRateLimitPolicy(
            enabled = true,
            requestsPerMinute = 60,
            maxConcurrentRequests = 3,
        )
        val channel = app.channelRepository.create(
            name = "rate-limit-persistence",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "group-a",
                    credential = app.credentialCipher.encrypt("upstream-secret"),
                    models = listOf("m1"),
                    rateLimit = initialPolicy,
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        assertEquals(initialPolicy, channel.keys.single().rateLimit)

        val disabledPolicy = ChannelKeyRateLimitPolicy(
            enabled = false,
            requestsPerMinute = 120,
            maxConcurrentRequests = 5,
        )
        val updated = app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            keys = listOf(
                ChannelKeyUpdate(
                    id = channel.keys.single().id,
                    name = "group-a",
                    state = ChannelKeyState.ACTIVE,
                    priority = 1,
                    models = listOf("m1"),
                    rateLimit = disabledPolicy,
                ),
            ),
            authType = channel.authType,
            protocols = channel.protocols,
            enabled = true,
        )
        assertEquals(disabledPolicy, updated?.keys?.single()?.rateLimit)
        assertFalse(updated!!.keys.single().rateLimit.configured)

        val preserved = app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            keys = listOf(
                ChannelKeyUpdate(
                    id = channel.keys.single().id,
                    name = "group-a",
                    state = ChannelKeyState.ACTIVE,
                    priority = 1,
                    models = listOf("m1"),
                ),
            ),
            authType = channel.authType,
            protocols = channel.protocols,
            enabled = true,
        )
        assertEquals(disabledPolicy, preserved?.keys?.single()?.rateLimit)

        assertTrue(app.channelRepository.delete(channel.id))
        assertEquals(0, scalarInt("select count(*) from channel_key_rate_limits"))
    }

    @Test
    fun `legacy channel update preserves an existing key rate limit`() = runBlocking {
        val policy = ChannelKeyRateLimitPolicy(enabled = true, requestsPerMinute = 30)
        val channel = app.channelRepository.create(
            name = "legacy-rate-limit-update",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "默认密钥",
                    credential = app.credentialCipher.encrypt("old-secret"),
                    models = listOf("m1"),
                    rateLimit = policy,
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"legacy-rate-limit-update","baseUrl":"${fake.baseUrl}/ok/v1","credential":"new-secret","authType":"bearer","protocols":["chat_completions"],"enabled":true,"modelMappings":[{"upstreamModel":"m1"}]}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseRateLimit = Json.parseToJsonElement(response.bodyAsText())
                .jsonObject["keys"]!!.jsonArray.single().jsonObject["rateLimit"]!!.jsonObject
            assertTrue(responseRateLimit["enabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(30, responseRateLimit["requestsPerMinute"]!!.jsonPrimitive.content.toInt())
            assertEquals(policy, app.channelRepository.find(channel.id)?.keys?.single()?.rateLimit)
        }
    }
}
