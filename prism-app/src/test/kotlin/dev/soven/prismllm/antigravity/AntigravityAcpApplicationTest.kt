package dev.soven.prismllm.antigravity

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.persistence.AgyProfileRegistration
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.config.AntigravityAcpConfig
import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.persistence.ClientKeyRepository
import dev.soven.prismllm.requestlog.domain.RetentionPolicy
import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.testsupport.db.SharedPostgres
import dev.soven.prismllm.testsupport.db.TestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("component")
@Timeout(60)
class AntigravityAcpApplicationTest {
    @TempDir
    lateinit var fixture: Path

    @Test
    fun defaultOffDoesNotMountAcpOrChangeTheOrdinaryRoute() = runBlocking {
        withGateway(enabled = false) {
            assertEquals(HttpStatusCode.NotFound, client.get("$base/antigravity/v1/models").status)
            assertEquals(HttpStatusCode.NotFound, post().status)
            assertOrdinaryRoute()
            assertFalse(Files.exists(fixture.resolve("startups")))
        }
    }

    @Test
    fun fullApplicationUsesDatabaseAuthorizationCorsAndStrictAcpBoundary() = runBlocking {
        withGateway {
            val preflight = client.options("$base$ACP_PATH") {
                header(HttpHeaders.Origin, ORIGIN)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization,content-type")
            }
            assertEquals(HttpStatusCode.OK, preflight.status)
            assertEquals(ORIGIN, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals(HttpStatusCode.Unauthorized, post(secret = null).status)
            assertEquals(HttpStatusCode.Unauthorized, post(secret = "invalid-test-key").status)
            assertEquals(HttpStatusCode.Forbidden, post(secret = deniedSecret).status)
            val catalog = client.get("$base/antigravity/v1/models") { header(HttpHeaders.Authorization, "Bearer $secret") }
            assertEquals(HttpStatusCode.OK, catalog.status)
            assertEquals("no-store", catalog.headers[HttpHeaders.CacheControl])
            assertEquals(listOf(JsonPrimitive(MODEL)), Json.parseToJsonElement(catalog.bodyAsText()).jsonObject
                .getValue("data").jsonArray.map { it.jsonObject["id"] })
            val invalid = post(body = requestBody().dropLast(1) + ",\"tools\":[]}")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("false", invalid.headers["x-should-retry"])
            assertEquals(ORIGIN, invalid.headers[HttpHeaders.AccessControlAllowOrigin])
            assertFalse(invalid.bodyAsText().contains(secret))
            assertFalse(Files.exists(fixture.resolve("startups")))
            assertOrdinaryRoute()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun databaseBackedNonstreamAndSseUseTheProductionCommandAndPreserveRoutes(stream: Boolean) = runBlocking {
        withGateway {
            val response = post(body = requestBody(stream))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("false", response.headers["x-should-retry"])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = response.bodyAsText()
            assertFalse(body.contains("\"usage\"") || body.contains("hidden-thought") || body.contains("private-fixture"))
            if (stream) {
                assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
                val frames = body.lineSequence().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }.toList()
                assertEquals("[DONE]", frames.last())
                val chunks = frames.dropLast(1).map { Json.parseToJsonElement(it).jsonObject }
                assertTrue(chunks.all { it["model"] == JsonPrimitive(MODEL) && it["object"] == JsonPrimitive("chat.completion.chunk") })
                assertEquals(JsonPrimitive("stop"), chunks.last().getValue("choices").jsonArray.single().jsonObject["finish_reason"])
                val text = chunks.joinToString("") {
                    (it.getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject["content"] as? JsonPrimitive)?.content.orEmpty()
                }
                assertEquals("第一段\n第二段🌿", text)
            } else {
                val result = Json.parseToJsonElement(body).jsonObject
                assertEquals(JsonPrimitive(MODEL), result["model"])
                assertEquals(JsonPrimitive("第一段\n第二段🌿"), result.getValue("choices").jsonArray.single()
                    .jsonObject.getValue("message").jsonObject["content"])
            }
            assertEquals("1", Files.readString(fixture.resolve("startups")))
            assertTrue(Files.exists(fixture.resolve("command-validated")))
            awaitReleased()
            assertOrdinaryRoute()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["nonstream-thinking", "stream-thinking", "nonstream-partial", "stream-partial"])
    fun databaseKeyRotationRevocationAndDisconnectKeepTheIsolatedLoginReusable(scenario: String) = runBlocking {
        withGateway(scenario.substringAfter('-')) {
            Socket("127.0.0.1", port).use { socket ->
                socket.sendRequest(port, secret, requestBody(scenario.startsWith("stream-")))
                awaitFile("prompt-started")
                if (scenario.endsWith("partial")) awaitFile("partial-returned")
                assertEquals(1, app.requestLimitService.clientKeyOccupancy()[keyId])
                val busy = post()
                assertEquals(HttpStatusCode.TooManyRequests, busy.status)
                assertTrue(busy.bodyAsText().contains("antigravity_acp_busy"))
                val rotated = ClientKeyHasher.generate()
                assertNotNull(keys.rotate(keyId, rotated.hash, rotated.prefix))
                assertEquals(HttpStatusCode.Unauthorized, post().status)
                secret = rotated.raw
                assertEquals(HttpStatusCode.OK, client.get("$base/antigravity/v1/models") {
                    header(HttpHeaders.Authorization, "Bearer $secret")
                }.status)
                socket.setSoLinger(true, 0)
                socket.close()
                awaitFile("cancel-acknowledged")
                awaitReleased()
            }
            val reused = post()
            assertEquals(HttpStatusCode.OK, reused.status)
            assertTrue(reused.bodyAsText().contains("reused"))
            assertTrue(Files.exists(fixture.resolve("home-reused")))
            assertEquals("2", Files.readString(fixture.resolve("startups")))
            assertNotNull(keys.revoke(keyId))
            assertEquals(HttpStatusCode.Unauthorized, post().status)
            assertEquals("2", Files.readString(fixture.resolve("startups")))
            awaitReleased()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["quota", "quota-text", "stream-quota-text", "disabled", "model-mismatch", "model-update"])
    fun fullApplicationStopsOnAcpFailureWithoutUsingTheExistingOrdinaryRoute(scenario: String) = runBlocking {
        withGateway(scenario.removePrefix("stream-")) {
            val response = post(body = requestBody(scenario.startsWith("stream-")))
            val body = response.bodyAsText()
            val code = when (scenario) {
                "disabled" -> "antigravity_acp_service_disabled"
                "model-mismatch", "model-update" -> "antigravity_acp_model_mismatch"
                else -> "antigravity_acp_quota_exhausted"
            }
            if (!scenario.startsWith("stream-")) assertTrue(response.status.value >= 400, body)
            assertTrue(body.contains(code), body)
            assertFalse(body.contains("private-fixture") || body.contains("\"finish_reason\":\"stop\""))
            assertEquals("false", response.headers["x-should-retry"])
            assertEquals("1", Files.readString(fixture.resolve("startups")))
            assertTrue(ordinaryRequests.isEmpty(), "ACP must not fall back to the configured ordinary route")
            awaitReleased()
            assertOrdinaryRoute()
        }
    }

    @Test
    fun fullApplicationDrainCancelsActiveAcpAndRejectsNewWork() = runBlocking {
        withGateway("thinking") {
            Socket("127.0.0.1", port).use { socket ->
                socket.sendRequest(port, secret, requestBody(stream = true))
                awaitFile("prompt-started")
                app.beginShutdown()
                app.drainRequests(50)
                awaitFile("cancel-acknowledged")
                awaitReleased()
                val rejected = post()
                assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
                assertTrue(rejected.bodyAsText().contains("gateway_shutting_down"))
                assertEquals("1", Files.readString(fixture.resolve("startups")))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun ordinaryDataPlaneRoutesMigratedAgyProfileForNonstreamAndSse(stream: Boolean) = runBlocking {
        withGateway(agyScenario = "agy-success", assertRoutesUnchanged = false) {
            val profileRoot = configureAgyRoute()
            val routeAfterMigration = app.modelCredentialRouteRepository.find(MODEL)

            val response = post(path = "/v1/chat/completions", body = requestBody(stream))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertFalse(body.contains("hidden-thought") || body.contains("private-fixture"))
            if (stream) {
                val frames = body.lineSequence().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }.toList()
                assertEquals("[DONE]", frames.last())
                val chunks = frames.dropLast(1).map { Json.parseToJsonElement(it).jsonObject }
                val text = chunks.joinToString("") {
                    (it.getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject["content"] as? JsonPrimitive)
                        ?.content.orEmpty()
                }
                assertEquals(listOf("第一段", "第二段🌿").joinToString(10.toChar().toString()), text)
            } else {
                val result = Json.parseToJsonElement(body).jsonObject
                assertEquals(JsonPrimitive(MODEL), result["model"])
                assertEquals(JsonPrimitive(listOf("第一段", "第二段🌿").joinToString(10.toChar().toString())),
                    result.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject["content"])
            }
            assertEquals("1", Files.readString(profileRoot.resolve("startups")))
            assertEquals(routeAfterMigration, app.modelCredentialRouteRepository.find(MODEL))
            assertTrue(ordinaryRequests.isEmpty(), "A successful AGY request must not call the next configured candidate")
            awaitReleased()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun ordinaryAgyResponsesRequestsReturnTheResponsesProtocol(stream: Boolean) = runBlocking {
        withGateway(agyScenario = "agy-success", assertRoutesUnchanged = false) {
            val profileRoot = configureAgyRoute()
            val routeAfterMigration = app.modelCredentialRouteRepository.find(MODEL)
            val response = post(path = "/v1/responses", body = responsesRequestBody(stream))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = response.bodyAsText()
            assertFalse(body.contains("hidden-thought") || body.contains("private-fixture"))
            if (stream) {
                assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
                assertTrue(body.contains("response.output_text.delta"), body)
                assertTrue(body.contains("第一段") && body.contains("第二段🌿"), body)
                assertTrue(body.contains("response.completed"), body)
            } else {
                val result = Json.parseToJsonElement(body).jsonObject
                assertEquals(JsonPrimitive("response"), result["object"])
                assertEquals(JsonPrimitive(MODEL), result["model"])
                assertFalse(result.containsKey("choices"), body)
                val outputText = result.getValue("output").jsonArray.flatMap { it.jsonObject.getValue("content").jsonArray }
                    .single().jsonObject.getValue("text").let { (it as JsonPrimitive).content }
                assertEquals(listOf("第一段", "第二段🌿").joinToString(10.toChar().toString()), outputText)
            }
            assertEquals("1", Files.readString(profileRoot.resolve("startups")))
            assertEquals(routeAfterMigration, app.modelCredentialRouteRepository.find(MODEL))
            assertTrue(ordinaryRequests.isEmpty(), "A successful AGY Responses request must not call the next configured candidate")
            awaitReleased()
        }
    }

    @Test
    fun ordinaryAgyQuotaExhaustionStopsWithoutFailingOverToTheNextCandidate() = runBlocking {
        withGateway(agyScenario = "agy-quota", assertRoutesUnchanged = false) {
            val profileRoot = configureAgyRoute()
            val routeAfterMigration = app.modelCredentialRouteRepository.find(MODEL)
            val response = post(path = "/v1/chat/completions")

            assertEquals(HttpStatusCode.TooManyRequests, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("antigravity_acp_quota_exhausted"))
            assertEquals("1", Files.readString(profileRoot.resolve("startups")))
            assertTrue(ordinaryRequests.isEmpty(), "Quota exhaustion must not fall through to a paid/static candidate")
            assertEquals(routeAfterMigration, app.modelCredentialRouteRepository.find(MODEL))
            awaitReleased()
        }
    }

    @Test
    fun ordinaryAgyStreamCancellationIsAcknowledgedAndTheMigratedLoginCanBeReused() = runBlocking {
        withGateway(agyScenario = "agy-thinking", assertRoutesUnchanged = false) {
            val profileRoot = configureAgyRoute()
            val routeAfterMigration = app.modelCredentialRouteRepository.find(MODEL)
            Socket("127.0.0.1", port).use { socket ->
                socket.sendRequest(port, secret, requestBody(stream = true), path = "/v1/chat/completions")
                awaitFile(profileRoot.resolve("prompt-started"))
                socket.awaitResponseContains("waiting for client cancellation")
                socket.setSoLinger(true, 0)
                socket.close()
            }
            awaitFile(profileRoot.resolve("cancel-acknowledged"))
            awaitReleased()

            val reused = post(path = "/v1/chat/completions")
            assertEquals(HttpStatusCode.OK, reused.status, reused.bodyAsText())
            assertTrue(reused.bodyAsText().contains("reused"))
            assertEquals("2", Files.readString(profileRoot.resolve("startups")))
            assertTrue(Files.readAllLines(profileRoot.resolve("methods")).contains("session/cancel"))
            assertTrue(ordinaryRequests.isEmpty())
            assertEquals(routeAfterMigration, app.modelCredentialRouteRepository.find(MODEL))
            awaitReleased()
        }
    }

    @Test
    fun ordinaryAgyStreamingQuotaExhaustionEmitsValidErrorWithoutFailingOver() = runBlocking {
        withGateway(agyScenario = "agy-quota", assertRoutesUnchanged = false) {
            val profileRoot = configureAgyRoute()
            val routeAfterMigration = app.modelCredentialRouteRepository.find(MODEL)
            val response = post(path = "/v1/chat/completions", body = requestBody(stream = true))
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status, body)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
            val frames = body.lineSequence().filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }.toList()
            val errorFrames = frames.filter { Json.parseToJsonElement(it).jsonObject.containsKey("error") }
            assertEquals(1, errorFrames.size, body)
            val error = Json.parseToJsonElement(errorFrames.single()).jsonObject.getValue("error").jsonObject
            assertEquals("antigravity_acp_quota_exhausted", error.getValue("code").jsonPrimitive.content)
            assertEquals("1", Files.readString(profileRoot.resolve("startups")))
            assertTrue(ordinaryRequests.isEmpty(), "Quota exhaustion must not fall through to a paid/static candidate")
            assertEquals(routeAfterMigration, app.modelCredentialRouteRepository.find(MODEL))
            awaitReleased()
        }
    }

    private suspend fun withGateway(
        scenario: String = "success",
        enabled: Boolean = true,
        agyScenario: String? = null,
        assertRoutesUnchanged: Boolean = true,
        verify: suspend ApplicationFixture.() -> Unit,
    ) {
        val postgres = SharedPostgres.container
        val databaseUrl = SharedPostgres.newDatabase()
        TestDatabase(databaseUrl, postgres.username, postgres.password).use { database ->
            val keys = ClientKeyRepository(database.database)
            val generated = ClientKeyHasher.generate()
            val key = keys.create("isolated-acp", generated.hash, generated.prefix, true, null,
                ClientKeyQuotaLimits(maxConcurrentRequests = 2), ClientKeyAccessPolicy(allowedModelIds = setOf(MODEL)))
            val denied = ClientKeyHasher.generate()
            val sentinel = keys.create("unrelated-key", denied.hash, denied.prefix, true, null)
            val ordinaryRequests = ConcurrentLinkedQueue<String>()
            val unexpected = ConcurrentLinkedQueue<String>()
            val httpClient = HttpClient(MockEngine { request ->
                val body = when (request.url.toString()) {
                    "https://models.dev/catalog.json" -> "{}"
                    "http://ordinary-upstream.invalid/v1/chat/completions" -> {
                        ordinaryRequests.add(request.url.encodedPath)
                        assertEquals("Bearer synthetic-upstream-secret", request.headers[HttpHeaders.Authorization])
                        """{"id":"ordinary-test","object":"chat.completion","model":"ordinary-upstream-model","choices":[{"index":0,"message":{"role":"assistant","content":"ordinary-route"},"finish_reason":"stop"}]}"""
                    }
                    else -> {
                        unexpected.add(request.url.host + request.url.encodedPath)
                        error("Unexpected egress in isolated ACP test")
                    }
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val acp = AntigravityAcpConfig(script = writeAgentLauncher(scenario), stateDir = fixture,
                modelId = MODEL, clientKeyIds = setOf(key.id), timeoutMs = 15_000)
            val agy = agyScenario?.let(::writeAgyGatewayAgentLauncher)
            val app = GatewayApplication(configuration(databaseUrl, postgres.username, postgres.password,
                acp.takeIf { enabled }, agy), httpClientOverride = httpClient)
            val channel = app.channelRepository.create(name = "ordinary-fixture",
                baseUrl = "http://ordinary-upstream.invalid/v1",
                keys = listOf(NewChannelKey("fixture", app.credentialCipher.encrypt("synthetic-upstream-secret"),
                    models = listOf("ordinary-upstream-model"))), authType = "bearer",
                protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS), enabled = true,
                modelMappings = listOf(ChannelModelRecord("ordinary-upstream-model", canonicalModelId = MODEL)))
            app.modelCredentialRouteRepository.save(MODEL, 0,
                listOf(CredentialRouteIdentity(channel.id, CredentialRouteType.STATIC, channel.keys.single().id)))
            val routesBefore = app.modelCredentialRouteRepository.list()
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { app.install(this) }
            var port = 0
            try {
                server.start(false)
                port = server.engine.resolvedConnectors().single().port
                HttpClient(CIO).use { client ->
                    ApplicationFixture(app, keys, key.id, generated.raw, denied.raw, port, client, ordinaryRequests, agy).verify()
                    assertEquals(sentinel, keys.find(sentinel.id))
                    if (assertRoutesUnchanged) assertEquals(routesBefore, app.modelCredentialRouteRepository.list())
                    assertTrue(unexpected.isEmpty(), unexpected.toString())
                    if (Files.exists(fixture.resolve("methods"))) {
                        assertTrue(Files.readAllLines(fixture.resolve("methods")).all {
                            it in setOf("initialize", "session/new", "session/prompt", "session/cancel")
                        }, "No login, model selection, tool execution or retry RPC is permitted")
                    }
                }
            } finally {
                app.beginShutdown()
                app.drainRequests(1_000)
                server.stop(0, 3_000)
                app.close()
            }
            assertTrue(app.requestLimitService.clientKeyOccupancy().isEmpty())
            assertTrue(app.metrics.registry.isClosed, "Owned metrics registry must be closed with the application")
            assertTrue(app.requestDrain.awaitFinalized(1_000))
            assertTrue(port != 0 && runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            }.isFailure, "Owned loopback listener must be stopped")
        }
    }

    private inner class ApplicationFixture(
        val app: GatewayApplication,
        val keys: ClientKeyRepository,
        val keyId: String,
        var secret: String,
        val deniedSecret: String,
        val port: Int,
        val client: HttpClient,
        val ordinaryRequests: ConcurrentLinkedQueue<String>,
        val agy: AntigravityAgyConfig?,
    ) {
        val base = "http://127.0.0.1:$port"

        suspend fun post(path: String = ACP_PATH, body: String = requestBody(), secret: String? = this.secret): HttpResponse =
            client.post(base + path) {
                secret?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                header(HttpHeaders.Origin, ORIGIN)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        suspend fun assertOrdinaryRoute() {
            val response = post(path = "/v1/chat/completions", secret = deniedSecret)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("ordinary-route"))
            assertEquals(1, ordinaryRequests.size)
        }

        suspend fun configureAgyRoute(): Path {
            val agyConfig = requireNotNull(agy)
            val profileId = "00000000-0000-0000-0000-000000000292"
            val profileRoot = agyConfig.stateDir.resolve(profileId)
            Files.createDirectories(profileRoot)
            val providerChannelId = requireNotNull(app.providerChannelProvisioner.ensure(OAuthProvider.ANTIGRAVITY))
            val legacy = app.oauthAccountRepository.create(NewOAuthAccount(
                name = "synthetic-legacy-antigravity",
                provider = OAuthProvider.ANTIGRAVITY,
                accountEmail = "legacy-fixture@example.invalid",
                accountPlan = "PRO",
                refreshToken = app.credentialCipher.encrypt("synthetic-legacy-refresh-token"),
                channelIds = listOf(providerChannelId),
                models = listOf(MODEL),
            ))
            val route = requireNotNull(app.modelCredentialRouteRepository.find(MODEL))
            val migrated = app.oauthAccountRepository.registerAgyProfile(AgyProfileRegistration(
                profileId = profileId,
                sourceAccountId = legacy.id,
                name = "synthetic-migrated-agy",
                subscriptionTier = "PRO",
                currentModelId = MODEL,
            ))
            app.providerChannelProvisioner.syncModelUnion(OAuthProvider.ANTIGRAVITY)
            app.modelCredentialRouteRepository.save(
                MODEL,
                route.revision,
                listOf(CredentialRouteIdentity(providerChannelId, CredentialRouteType.OAUTH, migrated.id)) + route.entries,
            )
            assertEquals(legacy.id, migrated.id)
            assertEquals(null, migrated.refreshTokenCiphertext)
            return profileRoot
        }

        suspend fun awaitReleased() = withTimeout(5_000) {
            while (app.requestLimitService.clientKeyOccupancy().isNotEmpty()) delay(10)
        }
    }

    private fun writeAgentLauncher(scenario: String): Path {
        val classpath = (System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparatorChar).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().toString() }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(java, "-Dstdout.encoding=UTF-8", "-cp", classpath,
            FakeAcpGatewayAgent::class.java.name, fixture.toString(), scenario)
        val expected = listOf("--state", fixture.toString(), "--proxy", "http://127.0.0.1:7897", "gateway-agent", "--model", MODEL)
        return Files.writeString(fixture.resolve("fixture-agent.py"), """
            import os, sys
            assert sys.argv[1:] == ${expected.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }}
            with open(${JsonPrimitive(fixture.resolve("command-validated").toString())}, "w") as receipt:
                receipt.write("yes")
            os.execv(${JsonPrimitive(java)}, ${command.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }})
        """.trimIndent())
    }

    private fun writeAgyGatewayAgentLauncher(scenario: String): AntigravityAgyConfig {
        val stateDir = fixture.resolve("agy-profiles")
        Files.createDirectories(stateDir)
        val classpath = (System.getProperty("surefire.test.class.path") ?: System.getProperty("java.class.path"))
            .split(File.pathSeparatorChar).joinToString(File.pathSeparator) { Path.of(it).toAbsolutePath().toString() }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val prefix = listOf(java, "-Dstdout.encoding=UTF-8", "-cp", classpath, FakeAcpGatewayAgent::class.java.name)
        val encodedJava = JsonPrimitive(java).toString()
        val encodedPrefix = prefix.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
        val encodedScenario = JsonPrimitive(scenario).toString()
        val pilotScript = fixture.resolve("antigravity_acp_pilot.py")
        Files.writeString(pilotScript, """
            import os, sys
            assert sys.argv[1] == "--state"
            state = sys.argv[2]
            assert sys.argv[3:] == ["--proxy", "http://127.0.0.1:7897", "gateway-agent", "--model", "$MODEL"]
            with open(os.path.join(state, "command-validated"), "w") as receipt:
                receipt.write("yes")
            os.execv($encodedJava, $encodedPrefix + [state, $encodedScenario])
        """.trimIndent())
        val adminScript = fixture.resolve("antigravity_agy_admin.py")
        Files.writeString(adminScript, "# isolated test fixture\n")
        return AntigravityAgyConfig(script = adminScript, stateDir = stateDir, python = Path.of("/usr/bin/python3"))
    }

    private fun configuration(
        url: String,
        username: String,
        password: String,
        acp: AntigravityAcpConfig?,
        agy: AntigravityAgyConfig? = null,
    ) = GatewayConfig(
        port = 0, databaseUrl = url, databaseUser = username, databasePassword = password,
        masterKey = SecureRandom().generateSeed(32), adminUsername = "admin", adminPassword = "isolated-test-password",
        corsOrigins = listOf(ORIGIN), requestBodyLimitBytes = 262_144, connectTimeoutMs = 1_000,
        firstTokenTimeoutMs = 2_000, idleTimeoutMs = 2_000, totalTimeoutMs = 20_000,
        maxAttempts = 1, drainTimeoutMs = 1_000, staticDir = fixture.resolve("absent-web"), cookieSecure = false,
        retention = RetentionPolicy(cleanupEnabled = false), upstreamHeaderRefreshEnabled = false,
        quotaPluginDir = fixture.resolve("absent-quota-plugins"), iqJudgePluginDir = fixture.resolve("absent-iq-plugins"),
        manxueEnabled = false, antigravityAcp = acp, antigravityAgy = agy,
    )

    private fun requestBody(stream: Boolean = false) =
        """{"model":"$MODEL","messages":[{"role":"user","content":"hello"}],"stream":$stream}"""

    private fun responsesRequestBody(stream: Boolean = false) =
        """{"model":"$MODEL","input":"hello","stream":$stream}"""

    private fun Socket.sendRequest(port: Int, secret: String, body: String, path: String = ACP_PATH) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        getOutputStream().write(("POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer $secret\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes)
        getOutputStream().flush()
    }

    private suspend fun Socket.awaitResponseContains(marker: String) = withContext(Dispatchers.IO) {
        soTimeout = 10_000
        val received = StringBuilder()
        val buffer = ByteArray(2_048)
        while (!received.contains(marker)) {
            val count = getInputStream().read(buffer)
            check(count > 0) { "connection closed before the expected stream content arrived" }
            received.append(String(buffer, 0, count, Charsets.UTF_8))
        }
    }

    private suspend fun awaitFile(name: String) = withTimeout(10_000) {
        awaitPath(fixture.resolve(name))
    }

    private suspend fun awaitFile(path: Path) = withTimeout(10_000) {
        awaitPath(path)
    }

    private suspend fun awaitPath(path: Path) {
        while (!Files.exists(path)) delay(10)
    }

    private companion object {
        const val MODEL = "gemini-fixed"
        const val ACP_PATH = "/antigravity/v1/chat/completions"
        const val ORIGIN = "https://isolated-console.example.com"
    }
}
