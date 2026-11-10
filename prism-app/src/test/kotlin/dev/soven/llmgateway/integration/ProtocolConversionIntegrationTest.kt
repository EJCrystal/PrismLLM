package dev.soven.llmgateway.integration

import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.channel.domain.ChannelModelRecord
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.shared.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.shared.domain.UpstreamProtocolOverrides
import dev.soven.llmgateway.admin.domain.WebSearchSettingsRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
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
@Tag("shard-2")
@Tag("protocol")
class ProtocolConversionIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `marks usage unknown when upstream omits it`() = runBlocking {
        val channel = createChannel("no-usage", "no-usage")
        exposeAsModel("fast-chat", channel to "m1")
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
    fun `admin partial channel update preserves anthropic authentication`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-partial-update",
            baseUrl = "${fake.baseUrl}/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
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
    fun `responses protocol streams and records usage`() = runBlocking {
        val channel = createChannelWithProtocols("responses", "ok", listOf(GatewayProtocol.RESPONSES))
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
    fun `responses web search streams search events before the final answer`() = runBlocking {
        val searchServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        searchServer.createContext("/search") { exchange ->
            val response =
                """{"results":[{"title":"Go downloads","url":"https://go.dev/dl/","content":"Go 1.27.1 is the latest release."}]}"""
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // 测试宿主会缓冲整个响应体，无法观察增量到达；这里用真实 Netty 宿主和流式客户端验证首事件时间。
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { app.install(this) }
        val http = HttpClient(CIO)
        try {
            searchServer.start()
            server.start(wait = false)
            val channel = createChannel("web-search", "web-search")
            exposeAsModel("fast-chat", channel to "gpt-4o-mini")
            val key = createClientKey()
            app.webSearchSettingsRepository.save(
                WebSearchSettingsRecord(
                    enabled = true,
                    provider = "tavily",
                    apiKey = "tvly-test",
                    baseUrl = "http://127.0.0.1:${searchServer.address.port}",
                    maxResults = 3,
                    timeoutMs = 2_000,
                    maxRounds = 2,
                    maxPageChars = 2_000,
                    updatedAt = 0,
                ),
            )
            val port = server.engine.resolvedConnectors().first().port

            http.preparePost("http://127.0.0.1:$port/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"fast-chat","stream":true,"input":"latest go?","tools":[{"type":"web_search"}]}""",
                )
            }.execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsChannel()
                val events = mutableListOf<String>()
                val raw = mutableListOf<String>()
                val started = System.currentTimeMillis()
                var searchAt = -1L
                var textAt = -1L
                while (true) {
                    val line = body.readUTF8Line() ?: break
                    raw += line
                    if (!line.startsWith("event: ")) continue
                    val event = line.removePrefix("event: ")
                    val elapsed = System.currentTimeMillis() - started
                    when (event) {
                        "response.web_search_call.completed" -> searchAt = elapsed
                        "response.output_text.delta" -> if (textAt < 0) textAt = elapsed
                    }
                    events += event
                }

                assertEquals("response.created", events.first())
                assertTrue(events.contains("response.web_search_call.completed"), "RAW=${raw.joinToString("\n")}")
                assertTrue(events.contains("response.completed"), "RAW=${raw.joinToString("\n")}")
                // 最终回答延迟 1 秒返回：搜索事件必须在那之前到达，而不是等回答完成后一起回放。
                assertTrue(searchAt >= 0, "searchAt=${searchAt}ms")
                assertTrue(textAt - searchAt >= 500, "searchAt=${searchAt}ms textAt=${textAt}ms")
            }
        } finally {
            http.close()
            // 启动阶段失败时 stop 可能抛出未启动异常，清理阶段忽略即可。
            runCatching { server.stop(0, 1_000) }
            runCatching { searchServer.stop(0) }
        }
    }

    @Test
    fun `responses first token timeout ignores response created event`() = runBlocking {
        app.close()
        app = GatewayApplication(config(firstTokenTimeoutMs = 300, idleTimeoutMs = 300, totalTimeoutMs = 2_000))
        val channel = createChannelWithProtocols(
            "responses-created-stall",
            "responses-created-stall",
            listOf(GatewayProtocol.RESPONSES),
        )
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
            listOf(GatewayProtocol.RESPONSES),
        )
        val secondary = createChannelWithProtocols("responses-ok", "ok", listOf(GatewayProtocol.RESPONSES))
        exposeAsModel("fast-chat", primary to "gpt-4o-mini", secondary to "gpt-4o-mini")
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
            listOf(GatewayProtocol.RESPONSES),
        )
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
            listOf(GatewayProtocol.RESPONSES),
        )
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
            assertNotNull(log.upstreamErrorBody)
            assertTrue(log.upstreamErrorBody!!.contains("upstream failed"))
        }
    }

    @Test
    fun `records responses incomplete terminal event as streaming failure`() = runBlocking {
        val channel = createChannelWithProtocols(
            "responses-incomplete",
            "responses-incomplete",
            listOf(GatewayProtocol.RESPONSES),
        )
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
        val channel = createChannelWithProtocols("responses", "ok", listOf(GatewayProtocol.RESPONSES))
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
    fun `records reasoning effort from chat and responses requests`() = runBlocking {
        val channel = createChannelWithProtocols(
            "openai",
            "ok",
            listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
        )
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
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
            // awaitLogWithEffort 由 GatewayIntegrationTestBase 提供，轮询等待指定 effort 的请求日志。
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
    fun `responses request converts to chat-only channel`() = runBlocking {
        val channel = createChannel("chat-only", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":false,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("response", body["object"]!!.jsonPrimitive.content)
            assertEquals("completed", body["status"]!!.jsonPrimitive.content)
            val output = body["output"]!!.jsonArray.single().jsonObject
            assertEquals("message", output["type"]!!.jsonPrimitive.content)
            assertEquals(
                "Hello!",
                output["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
            )
            val upstream = fake.requests.single()
            assertEquals("/ok/v1/chat/completions", upstream.path)
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertEquals("gpt-4o-mini", upstreamBody["model"]!!.jsonPrimitive.content)
            assertTrue(upstreamBody["messages"]!!.jsonArray.isNotEmpty())
            assertFalse(upstreamBody.containsKey("input"))
            val log = awaitRequestLog()
            assertEquals(channel.id, log.channelId)
            assertEquals(GatewayProtocol.RESPONSES, log.protocol)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.upstreamProtocol)
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
    fun `model test ignores legacy reasoning effort for messages protocol`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "model-test-messages",
            "${fake.baseUrl}/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
        )

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"claude-sonnet-4-5","protocol":"messages","prompt":"hi","reasoningEffort":"high"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val upstream = fake.requests.last().body
            assertFalse(upstream.contains("reasoning_effort"), upstream)
            assertFalse(upstream.contains("\"reasoning\""), upstream)
        }
    }

    @Test
    fun `direct mode converts responses request to chat-only channel`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        createChannelWithBaseUrl(
            "chat-only",
            "${fake.baseUrl}/ok/v1",
            listOf(GatewayProtocol.CHAT_COMPLETIONS),
            requestOverrides = UpstreamRequestOverrides(
                userAgent = "converted-UA",
                protocols = mapOf("chat_completions" to UpstreamProtocolOverrides(
                    headers = mapOf("X-Target-Protocol" to "chat"),
                    body = Json.parseToJsonElement("""{"temperature":0.2}""").jsonObject,
                )),
            ),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","stream":false,"input":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("response", body["object"]!!.jsonPrimitive.content)
            val output = body["output"]!!.jsonArray.single().jsonObject
            assertEquals("Hello!", output["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            val upstream = fake.requests.single()
            assertEquals("/ok/v1/chat/completions", upstream.path)
            assertEquals(listOf("converted-UA"), upstream.requestHeaders["user-agent"])
            assertEquals(listOf("chat"), upstream.requestHeaders["x-target-protocol"])
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertTrue(upstreamBody["messages"]!!.jsonArray.isNotEmpty())
            assertFalse(upstreamBody.containsKey("input"))
            assertEquals("0.2", upstreamBody["temperature"]!!.jsonPrimitive.content)
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.RESPONSES, log.protocol)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.upstreamProtocol)
        }
    }

    @Test
    fun `admin channel response exposes protocols`() = runBlocking {
        createChannelWithProtocols(
            "both",
            "ok",
            listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES),
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
    fun `admin channel create and update preserve protocol priority order`() = runBlocking {
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val created = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"protocol-order","baseUrl":"${fake.baseUrl}/ok/v1","credential":"sk-test","authType":"bearer","protocolAuthTypes":{"messages":"anthropic","responses":"google"},"protocols":[" messages ","responses","messages","chat_completions"],"enabled":true}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = createdJson["id"]!!.jsonPrimitive.content
            val createdOrder = listOf("messages", "responses", "chat_completions")
            assertEquals(createdOrder, createdJson["protocols"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("anthropic", createdJson["protocolAuthTypes"]!!.jsonObject["messages"]!!.jsonPrimitive.content)
            assertEquals("google", createdJson["protocolAuthTypes"]!!.jsonObject["responses"]!!.jsonPrimitive.content)
            assertEquals(createdOrder, assertNotNull(app.channelRepository.find(id)).protocols.map { it.value })

            val updated = client.put("/admin/api/channels/$id") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"protocol-order","baseUrl":"${fake.baseUrl}/ok/v1","protocols":["responses","chat_completions","messages"],"enabled":true}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val updatedJson = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
            val updatedOrder = listOf("responses", "chat_completions", "messages")
            assertEquals(updatedOrder, updatedJson["protocols"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(updatedOrder, assertNotNull(app.channelRepository.find(id)).protocols.map { it.value })

            val listed = client.get("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
            val listedChannel = Json.parseToJsonElement(listed.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .single { it["id"]!!.jsonPrimitive.content == id }
            assertEquals(updatedOrder, listedChannel["protocols"]!!.jsonArray.map { it.jsonPrimitive.content })

            val partial = client.put("/admin/api/channels/$id") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"protocol-order","baseUrl":"${fake.baseUrl}/ok/v1","enabled":true,"priority":9}""")
            }
            assertEquals(HttpStatusCode.OK, partial.status, partial.bodyAsText())
            val partialJson = Json.parseToJsonElement(partial.bodyAsText()).jsonObject
            assertEquals(updatedOrder, partialJson["protocols"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("anthropic", partialJson["protocolAuthTypes"]!!.jsonObject["messages"]!!.jsonPrimitive.content)
            assertEquals("google", partialJson["protocolAuthTypes"]!!.jsonObject["responses"]!!.jsonPrimitive.content)
            assertEquals(updatedOrder, assertNotNull(app.channelRepository.find(id)).protocols.map { it.value })
        }
    }

    @Test
    fun `native protocol candidate wins over higher priority conversion candidate`() = runBlocking {
        val conversion = createChannelWithBaseUrl(
            name = "conversion-first",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        val native = createChannelWithBaseUrl(
            name = "native-second",
            baseUrl = fake.baseUrl + "/500/v1",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            modelMappings = listOf(ChannelModelRecord("gpt-4o-mini")),
        )
        // 转换候选放在 position 0，原生候选放在 position 1；原生协议仍应先尝试。
        exposeAsModel("native-priority", conversion to "claude-sonnet-4-5", native to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"native-priority","messages":[{"role":"user","content":"hi"}],"max_tokens":32}""")
            }
            val response = send()
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(
                listOf("/500/v1/chat/completions", "/ok/v1/messages"),
                fake.requests.map { it.path },
            )
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "Hello!",
                body["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content,
            )
            val log = awaitRequestLog()
            assertEquals(2, log.upstreamAttempts)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.protocol)
            assertEquals(GatewayProtocol.MESSAGES, log.upstreamProtocol)

            // 原生候选进入冷却后，后续请求直接走转换候选，不再产生一次无效尝试。
            val second = send()
            assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
            awaitRequestLogCount(2)
            val secondLog = app.requestLogRepository.list(10).first()
            assertEquals(1, secondLog.upstreamAttempts)
            assertEquals(GatewayProtocol.MESSAGES, secondLog.upstreamProtocol)
        }
    }

    @Test
    fun `chat request converts to messages-only channel`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "chat-to-messages",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("converted-claude", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"converted-claude","messages":[{"role":"user","content":"hi"}],"max_tokens":32}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Hello!", body["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
            val upstream = fake.requests.last()
            assertEquals("/ok/v1/messages", upstream.path)
            assertEquals("upstream-secret", upstream.xApiKey)
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertEquals("claude-sonnet-4-5", upstreamBody["model"]!!.jsonPrimitive.content)
            assertTrue(upstreamBody["messages"]!!.jsonArray.isNotEmpty())

            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.protocol)
            assertEquals(GatewayProtocol.MESSAGES, log.upstreamProtocol)
        }
    }

    @Test
    fun `chat streaming request converts to messages stream`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "chat-stream-to-messages",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("converted-claude-stream", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"converted-claude-stream","stream":true,"messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("chat.completion.chunk"), body)
            assertTrue(body.contains("Hello!"), body)
            assertTrue(body.contains("[DONE]"), body)
        }
    }

    @Test
    fun `protocol conversion rejects unsupported fields before upstream`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "chat-to-messages-unsupported",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("converted-unsupported", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"converted-unsupported","messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}}]}]}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("protocol_conversion_unsupported", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `protocol conversion response errors are reported as conversion failures`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "messages-unsupported-response",
            baseUrl = fake.baseUrl + "/unsupported-response/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("converted-response-unsupported", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"converted-response-unsupported","messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("protocol_conversion_unsupported", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun `messages request converts to chat-only channel`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "messages-to-chat",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
            modelMappings = listOf(ChannelModelRecord("gpt-4o-mini")),
        )
        exposeAsModel("converted-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"converted-chat","max_tokens":32,"messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Hello!", body["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("/ok/v1/chat/completions", fake.requests.last().path)
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.MESSAGES, log.protocol)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.upstreamProtocol)
        }
    }

    @Test
    fun `messages system turns convert to responses without an unsupported role`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "messages-to-responses-system",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.RESPONSES),
            modelMappings = listOf(ChannelModelRecord("gpt-4o-mini")),
        )
        exposeAsModel("converted-responses-system", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"converted-responses-system","max_tokens":32,"system":"You are Claude Code.","messages":[
                        {"role":"system","content":"env: working dir /home/me"},
                        {"role":"user","content":"hi"},
                        {"role":"assistant","content":"hello"},
                        {"role":"system","content":"the user switched rooms"}
                    ]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("/ok/v1/responses", fake.requests.last().path)
            val upstream = Json.parseToJsonElement(fake.requests.last().body).jsonObject
            assertEquals(
                "You are Claude Code.\n\nenv: working dir /home/me",
                upstream["instructions"]!!.jsonPrimitive.content,
            )
            val input = upstream["input"]!!.jsonArray
            assertTrue(input.none { it.jsonObject["role"]?.jsonPrimitive?.content in setOf("system", "developer") })
            val reminder = input.last().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(reminder.contains("the user switched rooms"))
            assertTrue(reminder.startsWith("<system-reminder>\n"))

            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.MESSAGES, log.protocol)
            assertEquals(GatewayProtocol.RESPONSES, log.upstreamProtocol)
        }
    }

    @Test
    fun `responses request converts to messages-only channel`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "responses-to-messages",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("converted-responses", channel to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"converted-responses","input":"hi","max_output_tokens":32}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Hello!", body["output"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("/ok/v1/messages", fake.requests.last().path)
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.RESPONSES, log.protocol)
            assertEquals(GatewayProtocol.MESSAGES, log.upstreamProtocol)
        }
    }

    @Test
    fun `responses with prompt_cache_key and client_metadata converts to messages and preserves affinity`() = runBlocking {
        val channel1 = createChannelWithBaseUrl(
            name = "responses-to-messages-ch1",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        val channel2 = createChannelWithBaseUrl(
            name = "responses-to-messages-ch2",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("affinity-model", channel1 to "claude-sonnet-4-5", channel2 to "claude-sonnet-4-5")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            // 发送带 prompt_cache_key 和 client_metadata 的请求
            val response1 = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{
                    "model": "affinity-model",
                    "input": "hi",
                    "max_output_tokens": 32,
                    "prompt_cache_key": "cindy-session-affinity-test",
                    "client_metadata": {"session_id": "cindy-123"}
                }""")
            }
            assertEquals(HttpStatusCode.OK, response1.status, response1.bodyAsText())
            val upstreamRequest = Json.parseToJsonElement(fake.requests.last().body).jsonObject
            assertFalse(upstreamRequest.containsKey("prompt_cache_key"), "上游 messages 请求中不应含有 prompt_cache_key")
            assertFalse(upstreamRequest.containsKey("client_metadata"), "上游 messages 请求中不应含有 client_metadata")
            val log1 = awaitRequestLog()
            val firstChannel = log1.channelName

            // 再次发送相同 prompt_cache_key 的请求，必须保持路由亲和性（命中同一 channel）
            val response2 = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{
                    "model": "affinity-model",
                    "input": "hi again",
                    "max_output_tokens": 32,
                    "prompt_cache_key": "cindy-session-affinity-test",
                    "client_metadata": {"session_id": "cindy-123"}
                }""")
            }
            assertEquals(HttpStatusCode.OK, response2.status, response2.bodyAsText())
            awaitRequestLogCount(2)
            val log2 = awaitRequestLog()
            assertEquals(firstChannel, log2.channelName, "相同 prompt_cache_key 请求应保持渠道路由亲和性")
        }
    }

    @Test
    fun `six explicit converters route non streaming requests and record actual protocols`() = runBlocking {
        // 六个转换器的公共断言集中在 GatewayIntegrationTestBase.assertSixConverterRoutes。
        assertSixConverterRoutes(stream = false)
    }

    @Test
    fun `six explicit converters route streaming requests and record actual protocols`() = runBlocking {
        assertSixConverterRoutes(stream = true)
    }

    @Test
    fun anthropicMessagesStreamIsPassedThroughAndRecordsUsage() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-fast", channel to "claude-sonnet-4-5")
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
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-fast", channel to "claude-sonnet-4-5")
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
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-fast", channel to "claude-sonnet-4-5")
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
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-fast", channel to "claude-sonnet-4-5")
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
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
            priority = 0,
        )
        val secondary = createChannelWithBaseUrl(
            name = "anthropic-backup",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
            priority = 10,
        )
        exposeAsModel("claude-fast", primary to "claude-sonnet-4-5", secondary to "claude-sonnet-4-5")
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
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-fast", channel to "claude-sonnet-4-5")
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

    @Test
    @Tag("upstream-request-overrides")
    fun modelDiscoveryTreatsEmptyProtocolsAsUnspecifiedAndValidatesNonemptyLists() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "discovery-protocols", fake.baseUrl + "/ok/v1", listOf(GatewayProtocol.CHAT_COMPLETIONS),
            requestOverrides = UpstreamRequestOverrides(protocols = mapOf(
                "chat_completions" to UpstreamProtocolOverrides(userAgent = "saved-chat-UA"),
                "responses" to UpstreamProtocolOverrides(userAgent = "draft-responses-UA"),
            )),
        )
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            for ((protocols, expectedUA) in listOf("[]" to "saved-chat-UA", "[\"responses\"]" to "draft-responses-UA")) {
                val response = client.post("/admin/api/channels/model-discovery") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","protocols":$protocols}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(listOf(expectedUA), fake.modelRequests.last().requestHeaders["user-agent"])
            }
            val unsaved = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","credential":"draft-secret","protocols":[],"requestOverrides":{"userAgent":"new-UA"}}""")
            }
            assertEquals(HttpStatusCode.OK, unsaved.status)
            assertEquals(listOf("new-UA"), fake.modelRequests.last().requestHeaders["user-agent"])
            val invalid = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","protocols":["invalid"]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("invalid_channel"))
            assertEquals(3, fake.modelRequests.size)
        }
    }
}
