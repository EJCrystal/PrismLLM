package dev.soven.prismllm.antigravity

import dev.soven.prismllm.api.antigravityAcpRoutes
import dev.soven.prismllm.config.AntigravityAcpConfig
import dev.soven.prismllm.proxy.RequestDrain
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.infra.ClientKeyQuotaService
import dev.soven.prismllm.quota.infra.RequestLimitService
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.toEnvelope
import dev.soven.prismllm.shared.error.unauthorized
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("component")
@Timeout(35)
class AntigravityAcpGatewayTest {
    @TempDir
    lateinit var fixture: Path

    @Test
    fun fixedCatalogAndInvalidPayloadsNeverStartAnAgent() = testApplication {
        application { installFixture(gateway()) }
        val catalog = client.get("/antigravity/v1/models") { header(HttpHeaders.Authorization, FIXTURE_AUTH) }
        assertEquals(HttpStatusCode.OK, catalog.status)
        assertEquals(listOf(JsonPrimitive(FIXTURE_MODEL)), Json.parseToJsonElement(catalog.bodyAsText())
            .jsonObject.getValue("data").jsonArray.map { it.jsonObject.getValue("id") })
        listOf(
            "{}",
            requestBody().replace(FIXTURE_MODEL, "another-model"),
            changedBody("temperature", JsonPrimitive(0)),
            changedBody("tools", buildJsonArray {}),
            changedBody("stream", JsonPrimitive("false")),
            changedBody("messages", Json.parseToJsonElement("""[{"role":"system","content":"hello"}]""")),
            changedBody("messages", Json.parseToJsonElement("""[{"role":"user","content":[{"type":"text","text":"hello"}]}]""")),
            requestBody().replace("hello", "x".repeat(32_769)),
        ).forEach { body ->
            val response = client.post("/antigravity/v1/chat/completions") {
                header(HttpHeaders.Authorization, FIXTURE_AUTH)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, body.take(100))
        }
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/chat/completions").status)
        assertFalse(Files.exists(fixture.resolve("startups")))
    }

    @Test
    fun authenticationAndAccessPolicyFailClosed() = testApplication {
        var key = clientKey()
        application { installFixture(gateway(), key = { key }) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/antigravity/v1/models").status)
        val rejected = listOf(
            clientKey().copy(id = "00000000-0000-0000-0000-000000000002"),
            clientKey().copy(quotas = ClientKeyQuotaLimits(dailyTokens = 100)),
            clientKey().copy(quotas = ClientKeyQuotaLimits(dailyUsdLimit = java.math.BigDecimal.ONE)),
            clientKey().copy(accessPolicy = ClientKeyAccessPolicy(allowedModelIds = emptySet())),
            clientKey().copy(accessPolicy = ClientKeyAccessPolicy(deniedModelIds = setOf(FIXTURE_MODEL))),
            clientKey().copy(accessPolicy = ClientKeyAccessPolicy(allowedChannelIds = emptySet())),
            clientKey().copy(accessPolicy = ClientKeyAccessPolicy(deniedChannelIds = setOf("other-channel"))),
        )
        rejected.forEach { denied ->
            key = denied
            val response = client.post("/antigravity/v1/chat/completions") {
                header(HttpHeaders.Authorization, FIXTURE_AUTH)
                contentType(ContentType.Application.Json)
                setBody(requestBody())
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        assertFalse(Files.exists(fixture.resolve("startups")))
    }

    @Test
    fun successiveRequestsReuseTheDedicatedHomeWithoutLoginOrModelChanges() = testApplication {
        val limits = limits()
        application { installFixture(gateway(), limits) }
        repeat(2) {
            val response = client.post("/antigravity/v1/chat/completions") {
                header(HttpHeaders.Authorization, FIXTURE_AUTH)
                contentType(ContentType.Application.Json)
                setBody(requestBody())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(FIXTURE_MODEL), result["model"])
            val choice = result.getValue("choices").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("第一段\n第二段🌿"), choice.getValue("message").jsonObject["content"])
            assertEquals(JsonPrimitive("stop"), choice["finish_reason"])
            assertFalse("usage" in result)
            assertTrue(limits.clientKeyOccupancy().isEmpty())
        }
        assertEquals("2", Files.readString(fixture.resolve("startups")))
        assertTrue(Files.exists(fixture.resolve("home-reused")))
        assertOnlyExpectedMethods()
    }

    @Test
    fun streamingEmitsOpenAiChunksWithoutInventingUsage() = testApplication {
        application { installFixture(gateway()) }
        val response = client.post("/antigravity/v1/chat/completions") {
            header(HttpHeaders.Authorization, FIXTURE_AUTH)
            contentType(ContentType.Application.Json)
            setBody(requestBody(stream = true))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val frames = body.lines().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", frames.last())
        val chunks = frames.dropLast(1).map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(chunks.all { it["object"] == JsonPrimitive("chat.completion.chunk") && "usage" !in it })
        assertEquals(JsonPrimitive("stop"), chunks.last().getValue("choices").jsonArray.single().jsonObject["finish_reason"])
        assertTrue(body.contains("第一段") && body.contains("第二段🌿"))
        assertFalse(body.contains("private-fixture") || body.contains("hidden-thought"))
        assertOnlyExpectedMethods()
    }

    @Test
    fun existingClientKeyRpmLimitStillRejectsTheNextRequestBeforeStartingAnAgent() = testApplication {
        val limits = limits()
        val key = clientKey().copy(quotas = ClientKeyQuotaLimits(requestsPerMinute = 1, maxConcurrentRequests = 1))
        application { installFixture(gateway(), limits, key = { key }) }
        repeat(2) { index ->
            val response = client.post("/antigravity/v1/chat/completions") {
                header(HttpHeaders.Authorization, FIXTURE_AUTH)
                contentType(ContentType.Application.Json)
                setBody(requestBody())
            }
            assertEquals(if (index == 0) HttpStatusCode.OK else HttpStatusCode.TooManyRequests, response.status)
            if (index == 1) assertTrue(response.bodyAsText().contains("key_rate_limit_exceeded"))
        }
        assertEquals("1", Files.readString(fixture.resolve("startups")))
        assertTrue(limits.clientKeyOccupancy().isEmpty())
    }

    @Test
    fun oversizedBodiesAreRejectedBeforeStartingAnAgent() = testApplication {
        application { installFixture(gateway()) }
        val response = client.post("/antigravity/v1/chat/completions") {
            header(HttpHeaders.Authorization, FIXTURE_AUTH)
            contentType(ContentType.Application.Json)
            setBody("x".repeat(262_145))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertFalse(Files.exists(fixture.resolve("startups")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["quota", "quota-text", "split-quota", "disabled", "model-mismatch", "model-update"])
    fun upstreamFailuresAreSanitizedAndNeverRetried(scenario: String) = testApplication {
        val limits = limits()
        application { installFixture(gateway(scenario), limits) }
        val response = client.post("/antigravity/v1/chat/completions") {
            header(HttpHeaders.Authorization, FIXTURE_AUTH)
            contentType(ContentType.Application.Json)
            setBody(requestBody())
        }
        assertEquals(when (scenario) {
            "disabled" -> HttpStatusCode.Forbidden
            "model-mismatch", "model-update" -> HttpStatusCode.Conflict
            else -> HttpStatusCode.TooManyRequests
        }, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("antigravity_acp_"))
        assertEquals("false", response.headers["x-should-retry"])
        assertFalse(body.contains("private-fixture") || body.contains("choices"))
        assertEquals("1", Files.readString(fixture.resolve("startups")))
        assertTrue(limits.clientKeyOccupancy().isEmpty())
        assertOnlyExpectedMethods()
        if (scenario == "model-mismatch") assertFalse(Files.exists(fixture.resolve("prompt-started")))
    }

    @Test
    fun streamedQuotaFailureIsAnErrorNotASuccessfulCompletion() = testApplication {
        application { installFixture(gateway("quota-text")) }
        val response = client.post("/antigravity/v1/chat/completions") {
            header(HttpHeaders.Authorization, FIXTURE_AUTH)
            contentType(ContentType.Application.Json)
            setBody(requestBody(stream = true))
        }
        val body = response.bodyAsText()
        assertTrue(body.contains("antigravity_acp_quota_exhausted"))
        assertFalse(body.contains("private-fixture") || body.contains(JsonPrimitive("stop").toString()))
        assertEquals("1", Files.readString(fixture.resolve("startups")))
    }

    @Test
    fun timeoutCancelsTheTurnAndReleasesConcurrency() = testApplication {
        val limits = limits()
        application { installFixture(gateway("thinking", timeoutMs = 1_000), limits) }
        val response = client.post("/antigravity/v1/chat/completions") {
            header(HttpHeaders.Authorization, FIXTURE_AUTH)
            contentType(ContentType.Application.Json)
            setBody(requestBody())
        }
        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        assertTrue(Files.exists(fixture.resolve("cancel-acknowledged")))
        assertTrue(limits.clientKeyOccupancy().isEmpty())
        assertOnlyExpectedMethods()
    }

    @ParameterizedTest
    @ValueSource(strings = ["nonstream-thinking", "stream-thinking", "nonstream-partial", "stream-partial"])
    fun realSocketDisconnectCancelsWhileThinkingAndLoginRemainsReusable(scenario: String) = runBlocking {
        val stream = scenario.startsWith("stream-")
        val partial = scenario.endsWith("partial")
        val limits = limits()
        val gateway = gateway(if (partial) "partial" else "thinking")
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { installFixture(gateway, limits) }.start(false)
        val port = server.engine.resolvedConnectors().single().port
        val socket = Socket("127.0.0.1", port)
        try {
            socket.sendRequest(port, requestBody(stream))
            awaitFile("prompt-started")
            if (partial) awaitFile("partial-returned")
            assertEquals(1, limits.clientKeyOccupancy()[FIXTURE_KEY_ID])
            HttpClient(CIO).use { client ->
                val busy = client.post("http://127.0.0.1:$port/antigravity/v1/chat/completions") {
                    header(HttpHeaders.Authorization, FIXTURE_AUTH)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody())
                }
                assertEquals(HttpStatusCode.TooManyRequests, busy.status)
                assertTrue(busy.bodyAsText().contains("antigravity_acp_busy"))
                assertEquals("1", Files.readString(fixture.resolve("startups")))
                socket.setSoLinger(true, 0)
                socket.close()
                awaitFile("cancel-acknowledged")
                withTimeout(5_000) { while (limits.clientKeyOccupancy().isNotEmpty()) delay(10) }
                val reused = client.post("http://127.0.0.1:$port/antigravity/v1/chat/completions") {
                    header(HttpHeaders.Authorization, FIXTURE_AUTH)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody())
                }
                assertEquals(HttpStatusCode.OK, reused.status)
                assertTrue(Files.exists(fixture.resolve("home-reused")))
                assertEquals("2", Files.readString(fixture.resolve("startups")))
            }
            assertOnlyExpectedMethods()
        } finally {
            socket.close()
            server.stop(0, 1_000)
        }
    }

    @Test
    fun shutdownCancelsTheActiveTurnAndRejectsNewWork() = runBlocking {
        val limits = limits()
        val drain = RequestDrain()
        val gateway = gateway("thinking")
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { installFixture(gateway, limits, drain) }.start(false)
        val port = server.engine.resolvedConnectors().single().port
        Socket("127.0.0.1", port).use { socket ->
            try {
                socket.sendRequest(port, requestBody())
                awaitFile("prompt-started")
                drain.beginShutdown()
                assertFalse(drain.awaitFinalized(50))
                drain.cancelRemaining()
                awaitFile("cancel-acknowledged")
                assertTrue(drain.awaitFinalized(5_000))
                assertTrue(limits.clientKeyOccupancy().isEmpty())
                HttpClient(CIO).use { client ->
                    val response = client.post("http://127.0.0.1:$port/antigravity/v1/chat/completions") {
                        header(HttpHeaders.Authorization, FIXTURE_AUTH)
                        setBody(requestBody())
                    }
                    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                }
            } finally {
                server.stop(0, 1_000)
            }
        }
        assertOnlyExpectedMethods()
    }

    private fun gateway(scenario: String = "success", timeoutMs: Long = 8_000): AntigravityAcpGateway {
        val classpath = (System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparatorChar).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().toString() }
        val config = AntigravityAcpConfig(script = fixture.resolve("unused.py"), stateDir = fixture,
            modelId = FIXTURE_MODEL, clientKeyIds = setOf(FIXTURE_KEY_ID), timeoutMs = timeoutMs)
        return AntigravityAcpGateway(config, AntigravityAcpPilot(
            command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-cp", classpath, FakeAcpGatewayAgent::class.java.name, fixture.toString(), scenario),
            workspace = fixture, agentWorkspace = Path.of("/workspace"), timeoutMs = timeoutMs,
            allowModelSelection = false,
        ))
    }

    private fun Application.installFixture(
        gateway: AntigravityAcpGateway,
        limits: RequestLimitService = limits(),
        drain: RequestDrain = RequestDrain(),
        key: () -> ClientKeyRecord = { clientKey() },
    ) {
        install(ContentNegotiation) { json() }
        install(StatusPages) { exception<GatewayError> { call, failure -> call.respond(failure.status, failure.toEnvelope()) } }
        routing {
            antigravityAcpRoutes(gateway, { call ->
                if (call.request.headers[HttpHeaders.Authorization] != FIXTURE_AUTH) throw unauthorized()
                key()
            }, limits, drain, 524_288)
        }
    }

    private fun limits() = RequestLimitService(ClientKeyQuotaService(
        usageReader = { _, _ -> ClientKeyQuotaUsage() },
        usageWriter = { _, _, _, _ -> error("ACP must not fabricate token or USD settlement") },
    ))

    private fun clientKey() = ClientKeyRecord(FIXTURE_KEY_ID, "fixture", "hash", "prefix", true,
        expiresAt = null, quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 4), createdAt = 0)

    private fun requestBody(stream: Boolean = false) = buildJsonObject {
        put("model", FIXTURE_MODEL)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "hello") })
        })
        put("stream", stream)
    }.toString()

    private fun changedBody(name: String, value: JsonElement) =
        JsonObject(Json.parseToJsonElement(requestBody()).jsonObject + (name to value)).toString()

    private fun Socket.sendRequest(port: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        getOutputStream().write(("POST /antigravity/v1/chat/completions HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\nAuthorization: $FIXTURE_AUTH\r\n" +
            "Content-Type: application/json\r\nContent-Length: "+ bytes.size + "\r\n\r\n").toByteArray() + bytes)
        getOutputStream().flush()
    }

    private suspend fun awaitFile(name: String) = withTimeout(8_000) {
        while (!Files.exists(fixture.resolve(name))) delay(10)
    }

    private fun assertOnlyExpectedMethods() {
        val methods = Files.readAllLines(fixture.resolve("methods"))
        assertTrue(methods.all { it in setOf("initialize", "session/new", "session/prompt", "session/cancel") }, methods.toString())
    }
}

object FakeAcpGatewayAgent {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getenv("GEMINI_API_KEY") == null && System.getenv("PATH") == null)
        val root = Path.of(args[0])
        val scenario = args[1]
        val counter = root.resolve("startups")
        val startup = if (Files.exists(counter)) Files.readString(counter).toInt() + 1 else 1
        Files.writeString(counter, startup.toString())
        val home = root.resolve("fixture-login")
        if (Files.exists(home)) {
            check(Files.readString(home) == "synthetic-test-login")
            Files.writeString(root.resolve("home-reused"), "yes")
        } else {
            Files.writeString(home, "synthetic-test-login")
        }
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        fun read(): JsonObject? = input.readLine()?.let { Json.parseToJsonElement(it).jsonObject }?.also {
            val method = (it["method"] as? JsonPrimitive)?.content.orEmpty()
            Files.writeString(root.resolve("methods"), method + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        fun result(id: JsonElement, value: JsonObject) = println(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("result", value)
        })
        fun stop(id: JsonElement, reason: String) = result(id, buildJsonObject { put("stopReason", reason) })
        fun chunk(text: String, update: String = "agent_message_chunk") = println(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "session/update")
            put("params", buildJsonObject {
                put("sessionId", "gateway-fixture-session")
                put("update", buildJsonObject {
                    put("sessionUpdate", update)
                    put("content", buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        })
        val init = checkNotNull(read())
        check(init["method"] == JsonPrimitive("initialize"))
        result(init.getValue("id"), buildJsonObject { put("protocolVersion", 1) })
        val new = checkNotNull(read())
        check(new["method"] == JsonPrimitive("session/new"))
        check(new.getValue("params").jsonObject["cwd"] == JsonPrimitive("/workspace"))
        check(new.getValue("params").jsonObject.getValue("mcpServers").jsonArray.isEmpty())
        val current = if (scenario in setOf("model-mismatch", "http-model-mismatch")) "different-model" else FIXTURE_MODEL
        result(new.getValue("id"), buildJsonObject {
            put("sessionId", "gateway-fixture-session")
            put("models", buildJsonObject {
                put("currentModelId", current)
                put("availableModels", buildJsonArray {
                    add(buildJsonObject { put("modelId", FIXTURE_MODEL) })
                    add(buildJsonObject { put("modelId", "different-model") })
                })
            })
        })
        val prompt = read() ?: return
        check(prompt["method"] == JsonPrimitive("session/prompt"))
        Files.writeString(root.resolve("prompt-started"), "yes")
        val id = prompt.getValue("id")
        if (scenario.startsWith("http-")) {
            if (scenario == "http-refresh" && startup == 2) {
                Files.writeString(root.resolve("synthetic-cli-refreshed-expired-login"), "yes")
            }
            val quotaAt = scenario.removePrefix("http-quota-").toIntOrNull()
            if (startup == quotaAt || scenario == "http-disabled") {
                println(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    put("error", buildJsonObject {
                        put("code", -32000)
                        put("message", if (scenario == "http-disabled")
                            "This service has been disabled in this account for violation of Terms of Service private-fixture"
                        else "RESOURCE_EXHAUSTED private-fixture")
                    })
                })
            } else if (startup == 3) {
                chunk("1\n2\n")
                check(checkNotNull(read())["method"] == JsonPrimitive("session/cancel"))
                Files.writeString(root.resolve("cancel-acknowledged"), "yes")
                stop(id, if (scenario == "http-cancel-unacknowledged") "end_turn" else "cancelled")
            } else {
                val text = prompt.getValue("params").jsonObject.getValue("prompt").jsonArray.single()
                    .jsonObject.getValue("text").let { (it as JsonPrimitive).content }
                val marker = checkNotNull(Regex("PRISM-[a-f0-9-]+").find(text)).value
                chunk(marker.take(12))
                chunk(marker.drop(12))
                stop(id, "end_turn")
            }
            return
        }
        when (scenario) {
            "quota", "disabled" -> {
                val message = if (scenario == "quota") "RESOURCE_EXHAUSTED private-fixture" else
                    "This service has been disabled in this account for violation of Terms of Service private-fixture"
                println(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    put("error", buildJsonObject { put("code", -32000); put("message", message) })
                })
            }
            "quota-text" -> { chunk("RESOURCE_EXHAUSTED private-fixture"); stop(id, "end_turn") }
            "split-quota" -> {
                chunk("RESOURCE_EX")
                chunk("HAUSTED private-fixture")
                stop(id, "end_turn")
            }
            "model-update" -> {
                println(buildJsonObject {
                    put("jsonrpc", "2.0"); put("method", "session/update")
                    put("params", buildJsonObject {
                        put("sessionId", "gateway-fixture-session")
                        put("update", buildJsonObject {
                            put("sessionUpdate", "current_model_update"); put("currentModelId", "different-model")
                        })
                    })
                })
                check(checkNotNull(read())["method"] == JsonPrimitive("session/cancel"))
                stop(id, "cancelled")
            }
            "thinking", "partial" -> if (startup == 1) {
                if (scenario == "partial") {
                    chunk("partial")
                    Files.writeString(root.resolve("partial-returned"), "yes")
                }
                val cancel = checkNotNull(read())
                check(cancel["method"] == JsonPrimitive("session/cancel"))
                Files.writeString(root.resolve("cancel-acknowledged"), "yes")
                stop(id, "cancelled")
            } else {
                chunk("reused")
                stop(id, "end_turn")
            }
            else -> {
                chunk("hidden-thought", update = "agent_thought_chunk")
                chunk("第一段\n")
                chunk("第二段🌿")
                stop(id, "end_turn")
            }
        }
    }
}

private const val FIXTURE_KEY_ID = "00000000-0000-0000-0000-000000000001"
private const val FIXTURE_MODEL = "gemini-fixed"
private const val FIXTURE_AUTH = "Bearer synthetic-acp-gateway-key"
