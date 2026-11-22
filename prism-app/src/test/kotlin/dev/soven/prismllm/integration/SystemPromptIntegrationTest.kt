package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.domain.SystemPromptRule
import dev.soven.prismllm.admin.domain.SystemPromptScope
import dev.soven.prismllm.admin.domain.SystemPromptSettingsRecord
import dev.soven.prismllm.admin.domain.WebSearchSettingsRecord
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
@Tag("shard-1")
@Tag("channel")
@Tag("protocol")
class SystemPromptIntegrationTest : GatewayIntegrationTestBase() {
    private fun settings(prompt: String) = SystemPromptSettingsRecord(
        enabled = true,
        rules = listOf(SystemPromptRule("global", "全局", prompt = prompt)),
    )

    private fun promptOf(body: JsonObject, protocol: GatewayProtocol): String? = when (protocol) {
        GatewayProtocol.CHAT_COMPLETIONS -> body["messages"]!!.jsonArray.first().jsonObject["content"]?.jsonPrimitive?.content
        GatewayProtocol.MESSAGES -> body["system"]?.jsonPrimitive?.content
        GatewayProtocol.RESPONSES -> body["instructions"]?.jsonPrimitive?.content
        GatewayProtocol.GEMINI -> body["systemInstruction"]?.jsonObject?.get("parts")?.jsonArray
            ?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content
    }

    private fun callerBody(protocol: GatewayProtocol, model: String, stream: Boolean): JsonObject {
        val source = when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS -> """{"messages":[{"role":"developer","content":"client-old-system"},{"role":"user","content":"hello"}]}"""
            GatewayProtocol.MESSAGES -> """{"system":"client-old-system","max_tokens":32,"messages":[{"role":"user","content":"hello"}]}"""
            GatewayProtocol.RESPONSES -> """{"instructions":"client-old-system","input":"hello"}"""
            GatewayProtocol.GEMINI -> """{"systemInstruction":{"parts":[{"text":"client-old-system"}]},"contents":[{"role":"user","parts":[{"text":"hello"}]}]}"""
        }
        return JsonObject(Json.parseToJsonElement(source).jsonObject + mapOf("model" to JsonPrimitive(model), "stream" to JsonPrimitive(stream)))
    }

    @Test
    fun adminSettingsRequireAuthenticationAndCsrfAndRejectInvalidTargetsWithoutChangingSavedSettings() = runBlocking {
        val session = app.sessions.create("admin")
        val configuration = settings("  exact system prompt\n  ")
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/system-prompt-settings").status)
            val defaults = client.get("/admin/api/system-prompt-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
            }
            assertFalse(Json.parseToJsonElement(defaults.bodyAsText()).jsonObject["enabled"]!!.jsonPrimitive.boolean)
            val missingCsrf = client.put("/admin/api/system-prompt-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(configuration))
            }
            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            val saved = client.put("/admin/api/system-prompt-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(configuration))
            }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertEquals(configuration.rules, app.systemPromptSettingsRepository.load().rules)
            assertEquals("no-store", saved.headers[HttpHeaders.CacheControl])
            val invalid = configuration.copy(rules = configuration.rules.map {
                it.copy(scope = SystemPromptScope.CHANNELS, channelIds = listOf("deleted-channel"))
            })
            val rejected = client.put("/admin/api/system-prompt-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(invalid))
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertTrue(rejected.bodyAsText().contains("invalid_system_prompt_settings"))
            assertEquals(configuration.rules, app.systemPromptSettingsRepository.load().rules)
        }
    }

    @Test
    fun nativeAndConvertedStreamingAndJsonRequestsMatchTheActualOutboundProtocol() = runBlocking {
        app.systemPromptSettingsRepository.save(SystemPromptSettingsRecord(true, GatewayProtocol.entries.map { protocol ->
            SystemPromptRule(protocol.value, protocol.value, scope = SystemPromptScope.PROTOCOLS, protocols = listOf(protocol.value), prompt = "prompt-for-" + protocol.value)
        }))
        for (protocol in GatewayProtocol.entries) {
            val model = "prompt-" + protocol.value
            val channel = createChannelWithBaseUrl(
                model, fake.baseUrl + if (protocol == GatewayProtocol.GEMINI) "/ok/v1beta" else "/ok/v1",
                protocols = listOf(protocol), modelMappings = mappingsOf(model),
            )
            exposeAsModel(model, channel to model)
        }
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            for (outbound in GatewayProtocol.entries) {
                val model = "prompt-" + outbound.value
                val convertedInbound = if (outbound == GatewayProtocol.MESSAGES) GatewayProtocol.CHAT_COMPLETIONS else GatewayProtocol.MESSAGES
                for (inbound in listOf(outbound, convertedInbound)) {
                    for (stream in listOf(false, true)) {
                        val path = if (inbound == GatewayProtocol.GEMINI) {
                            "/v1beta/models/" + model + if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
                        } else "/v1" + inbound.endpointPath
                        val response = client.post(path) {
                            header(HttpHeaders.Authorization, "Bearer $key")
                            contentType(ContentType.Application.Json)
                            setBody(callerBody(inbound, model, stream).toString())
                        }
                        val responseBody = response.bodyAsText()
                        assertEquals(HttpStatusCode.OK, response.status, inbound.value + " -> " + outbound.value + ": " + responseBody)
                        val sent = Json.parseToJsonElement(fake.requests.last().body).jsonObject
                        assertEquals("prompt-for-" + outbound.value, promptOf(sent, outbound))
                        assertFalse(sent.toString().contains("client-old-system"))
                    }
                }
            }
        }
    }

    @Test
    fun failoverReevaluatesChannelTargetsButKeepsTheOriginalSettingsSnapshot() = runBlocking {
        val bad = createChannelWithBaseUrl("prompt-bad", fake.baseUrl + "/hold-500/v1", modelMappings = mappingsOf("m1"))
        val good = createChannelWithBaseUrl("prompt-good", fake.baseUrl + "/ok/v1", modelMappings = mappingsOf("m2"))
        exposeAsModel("prompt-retry", bad to "m1", good to "m2")
        val initial = settings("old-global").copy(rules = listOf(
            settings("old-global").rules.single(),
            SystemPromptRule("bad", "坏渠道", scope = SystemPromptScope.CHANNELS, channelIds = listOf(bad.id), prompt = "bad-only"),
        ))
        app.systemPromptSettingsRepository.save(initial)
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val release = fake.holdChatRequests()
            val pending = async {
                client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody(callerBody(GatewayProtocol.CHAT_COMPLETIONS, "prompt-retry", false).toString())
                }
            }
            try {
                awaitCondition("first prompt attempt reaches the upstream") { fake.requests.size == 1 }
                app.systemPromptSettingsRepository.save(settings("new-global"))
                release.complete(Unit)
                val response = pending.await()
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals(listOf("bad-only", "old-global"), fake.requests.map {
                    promptOf(Json.parseToJsonElement(it.body).jsonObject, GatewayProtocol.CHAT_COMPLETIONS)
                })
                val next = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody(callerBody(GatewayProtocol.CHAT_COMPLETIONS, "prompt-retry", false).toString())
                }
                assertEquals(HttpStatusCode.OK, next.status, next.bodyAsText())
                assertEquals("new-global", promptOf(Json.parseToJsonElement(fake.requests.last().body).jsonObject, GatewayProtocol.CHAT_COMPLETIONS))
            } finally {
                release.complete(Unit)
                pending.cancel()
                fake.clearChatHold()
            }
        }
    }

    @Test
    fun connectivityUsesConfiguredPromptWhileProtocolProbesAndFingerprintTestsRemainIsolated() = runBlocking {
        app.systemPromptSettingsRepository.save(settings("connectivity-configured-system"))
        val channel = createChannel("prompt-connectivity", "ok")
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val tested = client.post("/admin/api/channels/" + channel.id + "/model-test") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1","protocol":"chat_completions","prompt":"connectivity-user-prompt"}""")
            }
            assertEquals(HttpStatusCode.OK, tested.status, tested.bodyAsText())
            val sent = Json.parseToJsonElement(fake.requests.last().body).jsonObject
            assertEquals("connectivity-configured-system", promptOf(sent, GatewayProtocol.CHAT_COMPLETIONS))
            assertTrue(sent.toString().contains("connectivity-user-prompt"))
            fake.requests.clear()
            val probed = client.post("/admin/api/channels/" + channel.id + "/probe-protocols") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1"}""")
            }
            assertEquals(HttpStatusCode.OK, probed.status, probed.bodyAsText())
            assertEquals(4, fake.requests.size)
            assertTrue(fake.requests.none { it.body.contains("connectivity-configured-system") })
            fake.requests.clear()
            val fingerprintBody = JsonObject(mapOf(
                "channelId" to JsonPrimitive(channel.id), "model" to JsonPrimitive("m1"), "protocol" to JsonPrimitive("chat_completions"),
            ))
            val fingerprint = client.post("/admin/api/model-tests/fingerprint") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(fingerprintBody.toString())
            }
            assertEquals(HttpStatusCode.OK, fingerprint.status, fingerprint.bodyAsText())
            assertEquals(3, fake.requests.size)
            assertTrue(fake.requests.none { it.body.contains("connectivity-configured-system") })
            fake.requests.clear()
            val iqRequest = JsonObject(mapOf(
                "channelId" to JsonPrimitive(channel.id), "model" to JsonPrimitive("m1"), "benchmark" to JsonPrimitive("candy"),
            ))
            val iq = client.runIqTestJob("gw_session=" + session.token, session.session.csrf, iqRequest.toString())
            assertEquals(HttpStatusCode.OK, iq.status, iq.bodyAsText())
            assertEquals(1, fake.requests.size)
            assertTrue(fake.requests.none { it.body.contains("connectivity-configured-system") })
        }
    }

    @Test
    fun searchFollowUpRoundsKeepTheSameOutboundPromptAndToolMessages() = runBlocking {
        val search = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        search.createContext("/search") { exchange ->
            val bytes = """{"results":[{"title":"Fixture result","url":"https://example.com/result","content":"Local fixture response"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        search.start()
        try {
            app.webSearchSettingsRepository.save(WebSearchSettingsRecord(
                enabled = true, provider = "tavily", apiKey = "fixture-key", baseUrl = "http://127.0.0.1:" + search.address.port,
                maxResults = 3, timeoutMs = 2_000, maxRounds = 2, maxPageChars = 2_000, updatedAt = 0,
            ))
            app.systemPromptSettingsRepository.save(SystemPromptSettingsRecord(true, listOf(
                SystemPromptRule("chat", "Chat 出口", scope = SystemPromptScope.PROTOCOLS, protocols = listOf("chat_completions"), prompt = "search-outbound-system"),
                SystemPromptRule("responses", "Responses 入口不应匹配", scope = SystemPromptScope.PROTOCOLS, protocols = listOf("responses"), prompt = "wrong-inbound-system"),
            )))
            val channel = createChannel("prompt-search", "web-search")
            exposeAsModel("prompt-search-model", channel to "gpt-4o-mini")
            val key = createClientKey()
            testApplication {
                application { app.install(this) }
                val result = client.post("/v1/responses") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"prompt-search-model","stream":false,"instructions":"client-original","input":"Search the fixture","tools":[{"type":"web_search"}]}""")
                }
                assertEquals(HttpStatusCode.OK, result.status, result.bodyAsText())
                assertEquals(2, fake.requests.size)
                for (sent in fake.requests) {
                    assertEquals("search-outbound-system", promptOf(Json.parseToJsonElement(sent.body).jsonObject, GatewayProtocol.CHAT_COMPLETIONS))
                    assertFalse(sent.body.contains("wrong-inbound-system"))
                    assertFalse(sent.body.contains("client-original"))
                }
                val followUp = Json.parseToJsonElement(fake.requests.last().body).jsonObject["messages"]!!.jsonArray
                assertTrue(followUp.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" })
            }
        } finally {
            search.stop(0)
        }
    }
}
