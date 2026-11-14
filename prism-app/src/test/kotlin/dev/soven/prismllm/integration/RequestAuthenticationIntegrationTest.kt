package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.options
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
    fun `CORS exposes both access denial codes while preserving native protocol errors`() = runBlocking {
        app.close()
        app = GatewayApplication(config(corsOrigins = listOf("https://console.example.com")))
        val channel = createChannelWithBaseUrl("openai", "${fake.baseUrl}/ok/v1", protocols = GatewayProtocol.entries.toList())
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        val policies = listOf(
            ClientKeyAccessPolicy(allowedModelIds = emptySet()) to "model_access_denied",
            ClientKeyAccessPolicy(allowedChannelIds = emptySet()) to "route_access_denied",
        )
        val requests = listOf(
            "/v1/chat/completions" to """{"model":"fast-chat","messages":[]}""",
            "/v1/responses" to """{"model":"fast-chat","input":"hi"}""",
            "/v1/messages" to """{"model":"fast-chat","max_tokens":8,"messages":[]}""",
            "/v1beta/models/fast-chat:generateContent" to """{"contents":[]}""",
            "/v1/models/fast-chat:generateContent" to """{"contents":[]}""",
        )
        testApplication {
            application { app.install(this) }
            policies.forEach { (policy, code) ->
                val generated = ClientKeyHasher.generate()
                app.clientKeyRepository.create("cors-scoped", generated.hash, generated.prefix, true, null,
                    ClientKeyQuotaLimits(maxConcurrentRequests = 1), policy)
                requests.forEach { (path, body) ->
                    val response = client.post(path) {
                        header(HttpHeaders.Origin, "https://console.example.com")
                        header(HttpHeaders.Authorization, "Bearer ${generated.raw}")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
                    assertEquals("https://console.example.com", response.headers[HttpHeaders.AccessControlAllowOrigin])
                    assertEquals(code, response.headers["X-Prism-Error-Code"])
                    val exposed = response.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().split(',').map { it.trim() }
                    assertTrue(exposed.any { it.equals("X-Prism-Error-Code", ignoreCase = true) }, "未暴露授权错误头：$exposed")
                    val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val error = root["error"]!!.jsonObject
                    when {
                        path == "/v1/messages" -> {
                            assertEquals(setOf("type", "error"), root.keys)
                            assertEquals(setOf("type", "message"), error.keys)
                            assertEquals("error", root["type"]!!.jsonPrimitive.content)
                            assertEquals("permission_error", error["type"]!!.jsonPrimitive.content)
                        }
                        path.contains(":generateContent") -> {
                            assertEquals(setOf("error"), root.keys)
                            assertEquals(setOf("code", "message", "status"), error.keys)
                            assertEquals("403", error["code"]!!.jsonPrimitive.content)
                            assertEquals("PERMISSION_DENIED", error["status"]!!.jsonPrimitive.content)
                        }
                        else -> assertEquals(code, error["code"]!!.jsonPrimitive.content)
                    }
                }
            }
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
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
