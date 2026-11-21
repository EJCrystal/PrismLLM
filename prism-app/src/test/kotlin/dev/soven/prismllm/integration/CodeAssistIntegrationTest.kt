package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.channel.domain.ChannelKind
import dev.soven.prismllm.channel.domain.UpstreamEnvelope
import dev.soven.prismllm.channel.domain.UpstreamHeaderProfile
import dev.soven.prismllm.channel.domain.upstreamEnvelope
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider-backed data-plane regressions; every outbound HTTP call stays in MockEngine. */
@Tag("shard-3")
@Tag("protocol")
@Tag("gemini")
@Timeout(30)
class CodeAssistIntegrationTest : GatewayIntegrationTestBase() {
    private val generations = ConcurrentLinkedQueue<ObservedGeneration>()
    private val endpoints = ConcurrentLinkedQueue<String>()
    private val unexpectedEndpoints = ConcurrentLinkedQueue<String>()

    @BeforeEach
    fun isolateOutboundHttp() {
        // Keep the base fixture's isolated database and master key, but never install its real client.
        val isolatedConfig = app.config.copy(maxAttempts = 1)
        app.close()
        generations.clear()
        endpoints.clear()
        unexpectedEndpoints.clear()
        val mockClient = HttpClient(MockEngine) {
            engine {
                reuseHandlers = true
                addHandler { request ->
                    val endpoint = (request.url.host + request.url.encodedPath)
                        .replace(Regex("[^A-Za-z0-9._~:/%-]"), "_")
                        .take(256)
                    endpoints.add(endpoint)
                    when {
                        request.url.protocol.name == "https" && request.method == HttpMethod.Get &&
                            endpoint == PRICE_ENDPOINT -> respond("{}", headers = JSON_HEADERS)
                        request.url.protocol.name == "https" && request.method == HttpMethod.Post &&
                            endpoint == LOAD_ENDPOINT -> respond(PROJECT_RESPONSE, headers = JSON_HEADERS)
                        request.url.protocol.name == "https" && request.method == HttpMethod.Post &&
                            endpoint == CATALOG_ENDPOINT -> respond(MODEL_CATALOG, headers = JSON_HEADERS)
                        request.url.protocol.name == "https" && request.method == HttpMethod.Post &&
                            endpoint == QUOTA_ENDPOINT -> respond(QUOTA_RESPONSE, headers = JSON_HEADERS)
                        request.url.protocol.name == "https" && request.method == HttpMethod.Post &&
                            endpoint in setOf(JSON_ENDPOINT, SSE_ENDPOINT) -> {
                            generations.add(observeGeneration(request))
                            if (endpoint == SSE_ENDPOINT) {
                                respond(SSE_RESPONSE, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                            } else {
                                respond(enveloped(JSON_RESPONSE), headers = JSON_HEADERS)
                            }
                        }
                        else -> {
                            // Preparation/startup failures can be swallowed by production fallbacks, so also
                            // retain this sanitized endpoint for the mandatory teardown assertion. No URL
                            // query, request headers, credentials, or request body appear in diagnostics.
                            unexpectedEndpoints.add(endpoint)
                            error("Unmocked outbound endpoint: $endpoint")
                        }
                    }
                }
            }
        }
        app = GatewayApplication(isolatedConfig, httpClientOverride = mockClient)
    }

    @AfterEach
    fun verifyNetworkIsolation() {
        app.close()
        assertTrue(unexpectedEndpoints.isEmpty(), "Unmocked outbound endpoints: ${unexpectedEndpoints.joinToString()}")
        assertTrue(fake.tokenRequests.isEmpty(), "The cached dummy access token must never be refreshed")
        assertTrue(fake.requests.isEmpty(), "Data-plane traffic must not escape MockEngine")
    }

    @Test
    fun `generateContent uses Code Assist identity and unwraps native JSON`() = runBlocking {
        assertNativeCodeAssistRoute(stream = false)
    }

    @Test
    fun `streamGenerateContent unwraps SSE and retains usage after the finish frame`() = runBlocking {
        assertNativeCodeAssistRoute(stream = true)
    }

    private suspend fun assertNativeCodeAssistRoute(stream: Boolean) {
        testApplication {
            application { app.install(this) }
            // Install/reconcile before provisioning, so startup cannot replace the fixture's mappings.
            startApplication()
            val fixture = createProviderFixture()
            val action = if (stream) "streamGenerateContent" else "generateContent"
            val response = client.post("/v1beta/models/$PUBLIC_MODEL:$action") {
                header(HttpHeaders.Authorization, "Bearer ${fixture.clientKey}")
                header(HttpHeaders.UserAgent, "synthetic-codeassist-client/1.0")
                header("X-Goog-Api-Client", "synthetic-client-identity")
                header("traceparent", INCOMING_TRACEPARENT)
                header("x-session-id", CLIENT_SESSION)
                contentType(ContentType.Application.Json)
                setBody(CLIENT_PAYLOAD.toString())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            if (stream) {
                assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
                val frames = text.lineSequence().filter { it.startsWith("data:") }
                    .map { Json.parseToJsonElement(it.removePrefix("data:").trim()).jsonObject }.toList()
                assertEquals(SSE_FRAMES, frames)
                assertTrue(frames.all { "response" !in it }, "SSE envelopes must be unwrapped")
                assertEquals("STOP", frames[1]["candidates"]!!.jsonArray.single().jsonObject["finishReason"]!!.jsonPrimitive.content)
                assertEquals(USAGE, frames.last()["usageMetadata"])
                assertFalse(text.contains("[DONE]"), "Native Gemini SSE must not gain an OpenAI terminal frame")
            } else {
                assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
                val body = Json.parseToJsonElement(text).jsonObject
                assertEquals(JSON_RESPONSE, body)
                assertFalse("response" in body, "The Code Assist JSON envelope must be unwrapped")
                assertEquals("STOP", body["candidates"]!!.jsonArray.single().jsonObject["finishReason"]!!.jsonPrimitive.content)
                assertEquals(USAGE, body["usageMetadata"])
            }

            val sent = generations.single()
            assertEquals(HttpMethod.Post, sent.method)
            assertEquals(DATA_HOST, sent.host)
            assertEquals("/v1internal:$action", sent.path)
            assertEquals(if (stream) "sse" else null, sent.alt)
            assertEquals(listOf(CURRENT_USER_AGENT), sent.userAgents, "Send exactly the current, full Antigravity UA")
            assertFalse(sent.googleApiClientPresent, "Code Assist must not send X-Goog-Api-Client")
            assertTrue(sent.dummyBearer, "Send only the cached dummy Bearer access token")
            // Ktor supplies Accept: */* even when the Code Assist routes do not set it.
            // Assert the effective header, not literal absence or an explicit SSE Accept.
            assertEquals(listOf("*/*"), sent.accept, "Observed upstream Accept: ${sent.accept}")
            assertFalse(sent.accept.orEmpty().any { it.contains("text/event-stream", ignoreCase = true) })
            val traceparent = sent.traceparents.single()
            val trace = assertNotNull(TRACEPARENT.matchEntire(traceparent), "A valid gateway traceparent is required")
            assertEquals(INCOMING_TRACE_ID, trace.groupValues[1])
            assertNotEquals(INCOMING_SPAN_ID, trace.groupValues[2], "Create a gateway span, not the incoming parent span")
            assertNotEquals("0".repeat(16), trace.groupValues[2])
            assertEquals("01", trace.groupValues[3])
            assertEquals(traceparent, response.headers["traceparent"])
            assertEquals(INCOMING_TRACE_ID, response.headers["X-Trace-Id"])

            val log = awaitRequestLog()
            assertEquals(PUBLIC_MODEL, log.alias)
            assertEquals(fixture.channelId, log.channelId)
            assertEquals(GatewayProtocol.GEMINI, log.protocol)
            assertEquals(GatewayProtocol.GEMINI, log.upstreamProtocol)
            assertEquals(stream, log.stream)
            assertEquals(1, log.upstreamAttempts)
            assertEquals(200, log.attempts.single().statusCode)
            assertEquals(7L, log.inputTokens)
            // Gemini bills generated thoughts as output in addition to candidate tokens.
            assertEquals(4L, log.outputTokens)
            assertEquals(1L, log.reasoningTokens)
            assertEquals(11L, log.totalTokens)
            assertTrue(log.usageKnown)
            assertNull(log.errorCode)
            if (stream) assertNotNull(log.firstTokenMs)
            assertRequestEnvelope(sent.body, fixture.channelId, assertNotNull(log.clientKeyId))

            // These hardcoded management/startup URLs must also use the injected engine.
            awaitCondition("mocked models.dev startup request") { PRICE_ENDPOINT in endpoints }
            assertEquals(1, endpoints.count { it == LOAD_ENDPOINT })
            assertEquals(1, endpoints.count { it == CATALOG_ENDPOINT })
            assertTrue(app.accountEgressClients.isEmpty(), "The dummy account must not create a proxy client")
        }
    }

    private suspend fun createProviderFixture(): ProviderFixture {
        val now = System.currentTimeMillis()
        // A synthetic current catalog version proves dispatch does not hardcode the bundled UA.
        app.upstreamHeaderCatalogRepository.saveRefresh(
            profiles = listOf(UpstreamHeaderProfile(
                id = "antigravity", name = "Synthetic Antigravity", version = "99.0.0",
                headers = mapOf(HttpHeaders.UserAgent to CURRENT_USER_AGENT), source = "test:codeassist",
            )),
            checkedAt = now, successAt = now, lastError = null,
        )
        app.upstreamHeaderCatalogService.initialize()
        assertEquals(CURRENT_USER_AGENT, app.currentAntigravityUserAgent())
        val channelId = assertNotNull(app.providerChannelProvisioner.ensure(OAuthProvider.ANTIGRAVITY))
        val account = app.oauthAccountRepository.create(NewOAuthAccount(
            name = "dummy-codeassist-account", provider = OAuthProvider.ANTIGRAVITY,
            accountEmail = "codeassist-regression@example.invalid",
            refreshToken = app.credentialCipher.encrypt("dummy-codeassist-refresh-token"),
            accessToken = app.credentialCipher.encrypt(DUMMY_ACCESS_TOKEN),
            accessTokenExpiresAt = now + 86_400_000,
            channelIds = listOf(channelId), models = listOf(FAMILY_MODEL),
        ))
        assertNull(account.proxyUrl)
        assertTrue(app.providerChannelProvisioner.syncModelCatalog(
            account.id, listOf(OAuthAccountModelCatalogEntry(FAMILY_MODEL, PUBLIC_MODEL)),
        ))
        val channel = assertNotNull(app.channelRepository.find(channelId))
        assertEquals(ChannelKind.PROVIDER, channel.kind)
        assertEquals(OAuthProvider.ANTIGRAVITY, channel.provider)
        assertEquals(UpstreamEnvelope.CODE_ASSIST, channel.upstreamEnvelope)
        assertTrue(channel.keys.isEmpty(), "Only one OAuth credential may serve this fixture")
        configureModelRoute(PUBLIC_MODEL, channel to account)
        return ProviderFixture(channelId, createClientKey())
    }

    private fun assertRequestEnvelope(body: JsonObject, channelId: String, clientKeyId: String) {
        assertEquals(setOf("model", "project", "userAgent", "requestType", "requestId", "request"), body.keys)
        assertEquals(JsonPrimitive(HIGH_MODEL), body["model"])
        assertEquals(JsonPrimitive(PROJECT), body["project"])
        assertEquals(JsonPrimitive("antigravity"), body["userAgent"])
        assertEquals(JsonPrimitive("agent"), body["requestType"])
        val scope = "$PUBLIC_MODEL\u0000$channelId\u0000$clientKeyId\u0000$CLIENT_SESSION"
        val cascade = UUID.nameUUIDFromBytes("cascade\u0000$scope".toByteArray()).toString()
        val trajectory = UUID.nameUUIDFromBytes("trajectory\u0000$scope".toByteArray()).toString()
        val requestId = body["requestId"]!!.jsonPrimitive.content
        assertTrue(Regex("agent/$cascade/[0-9]{13}/$trajectory/1").matches(requestId), "Use the slash-delimited agent requestId")
        val inner = body["request"]!!.jsonObject
        assertEquals(CLIENT_PAYLOAD.keys + "sessionId", inner.keys)
        CLIENT_PAYLOAD.filterKeys { it !in setOf("labels", "systemInstruction") }.forEach { (field, value) ->
            assertEquals(value, inner[field], "Preserve the synthetic client field $field")
        }
        assertEquals(JsonPrimitive(32), inner["generationConfig"]!!.jsonObject["maxOutputTokens"])
        assertEquals(
            JsonObject(CLIENT_PAYLOAD["systemInstruction"]!!.jsonObject + ("role" to JsonPrimitive("user"))),
            inner["systemInstruction"],
        )
        assertEquals(buildJsonObject {
            put("fixture", "bounded-codeassist")
            put("cascade_id", cascade)
            put("root_cascade_id", cascade)
            put("trajectory_id", trajectory)
            put("request_id", "$trajectory-0")
            put("last_step_index", "0")
            put("used_claude", "false")
            put("used_claude_conservative", "false")
            put("used_non_gemini_model", "false")
        }, inner["labels"])
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
        val session = ByteBuffer.wrap(digest, 0, 8).long and Long.MAX_VALUE
        assertEquals(JsonPrimitive("-$session"), inner["sessionId"])
    }

    private fun observeGeneration(request: HttpRequestData): ObservedGeneration {
        val content = request.body as? OutgoingContent.ByteArrayContent
            ?: error("Expected synthetic JSON request bytes")
        // Do not retain HttpRequestData or an auth/header map: only allowlisted identity fields,
        // a dummy-auth equality boolean, and the wholly synthetic request body are observable.
        return ObservedGeneration(
            host = request.url.host, path = request.url.encodedPath, method = request.method,
            alt = request.url.parameters["alt"],
            userAgents = request.headers.getAll(HttpHeaders.UserAgent).orEmpty(),
            googleApiClientPresent = request.headers.getAll("X-Goog-Api-Client") != null,
            traceparents = request.headers.getAll("traceparent").orEmpty(),
            dummyBearer = request.headers.getAll(HttpHeaders.Authorization) == listOf("Bearer $DUMMY_ACCESS_TOKEN"),
            accept = request.headers.getAll(HttpHeaders.Accept),
            body = Json.parseToJsonElement(content.bytes().decodeToString()).jsonObject,
        )
    }

    private data class ProviderFixture(val channelId: String, val clientKey: String)

    private data class ObservedGeneration(
        val host: String,
        val path: String,
        val method: HttpMethod,
        val alt: String?,
        val userAgents: List<String>,
        val googleApiClientPresent: Boolean,
        val traceparents: List<String>,
        val dummyBearer: Boolean,
        val accept: List<String>?,
        val body: JsonObject,
    )

    private companion object {
        const val DATA_HOST = "daily-cloudcode-pa.googleapis.com"
        const val PRICE_ENDPOINT = "models.dev/catalog.json"
        const val LOAD_ENDPOINT = "cloudcode-pa.googleapis.com/v1internal:loadCodeAssist"
        const val CATALOG_ENDPOINT = "$DATA_HOST/v1internal:fetchAvailableModels"
        const val QUOTA_ENDPOINT = "$DATA_HOST/v1internal:retrieveUserQuotaSummary"
        const val JSON_ENDPOINT = "$DATA_HOST/v1internal:generateContent"
        const val SSE_ENDPOINT = "$DATA_HOST/v1internal:streamGenerateContent"
        const val PUBLIC_MODEL = "bounded-codeassist"
        const val FAMILY_MODEL = "gemini-3.7-flash"
        const val HIGH_MODEL = "$FAMILY_MODEL-high"
        const val PROJECT = "dummy-codeassist-project"
        const val DUMMY_ACCESS_TOKEN = "dummy-codeassist-access-token"
        const val CLIENT_SESSION = "synthetic-codeassist-session"
        const val CURRENT_USER_AGENT = "antigravity/hub/99.0.0 (aidev_client; os_type=linux; arch=amd64; cl=1021296516)"
        const val INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val INCOMING_SPAN_ID = "00f067aa0ba902b7"
        const val INCOMING_TRACEPARENT = "00-$INCOMING_TRACE_ID-$INCOMING_SPAN_ID-01"
        val TRACEPARENT = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val PROJECT_RESPONSE = """{"cloudaicompanionProject":"$PROJECT","currentTier":{"id":"standard-tier"},"paidTier":{"id":"standard-tier"}}"""
        const val MODEL_CATALOG = """{"models":{"$HIGH_MODEL":{"displayName":"Synthetic Gemini (High)","maxTokens":8192,"maxOutputTokens":4096},"$FAMILY_MODEL-low":{"displayName":"Synthetic Gemini (Low)","maxTokens":8192,"maxOutputTokens":4096}}}"""
        const val QUOTA_RESPONSE = """{"groups":[{"displayName":"Gemini Models","buckets":[{"window":"5h","remainingFraction":1.0},{"window":"weekly","remainingFraction":1.0}]}]}"""
        val CLIENT_PAYLOAD = Json.parseToJsonElement("""{
            "contents":[{"role":"user","parts":[{"text":"Reply with the single word synthetic."}]}],
            "systemInstruction":{"parts":[{"text":"Synthetic Code Assist regression fixture."}]},
            "generationConfig":{"maxOutputTokens":32,"candidateCount":1,"temperature":0.25,"stopSequences":["<synthetic-stop>"],"thinkingConfig":{"thinkingLevel":"HIGH","includeThoughts":false}},
            "labels":{"fixture":"bounded-codeassist"},
            "safetySettings":[{"category":"HARM_CATEGORY_DANGEROUS_CONTENT","threshold":"BLOCK_ONLY_HIGH"}]
        }""").jsonObject
        val USAGE = Json.parseToJsonElement("""{"promptTokenCount":7,"candidatesTokenCount":3,"thoughtsTokenCount":1,"totalTokenCount":11}""").jsonObject
        val JSON_RESPONSE = Json.parseToJsonElement("""{"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"Synthetic answer."}]},"finishReason":"STOP"}],"usageMetadata":$USAGE,"modelVersion":"$HIGH_MODEL","responseId":"dummy-native-json"}""").jsonObject
        val SSE_FRAMES = listOf(
            Json.parseToJsonElement("""{"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"Synthetic "}]}}],"modelVersion":"$HIGH_MODEL"}""").jsonObject,
            Json.parseToJsonElement("""{"candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"answer."}]},"finishReason":"STOP"}],"modelVersion":"$HIGH_MODEL"}""").jsonObject,
            Json.parseToJsonElement("""{"usageMetadata":$USAGE,"modelVersion":"$HIGH_MODEL","responseId":"dummy-native-stream"}""").jsonObject,
        )
        val SSE_RESPONSE = SSE_FRAMES.joinToString("\n\n", postfix = "\n\n") { "data: ${enveloped(it)}" }

        fun enveloped(response: JsonObject): String = buildJsonObject {
            put("response", response)
            put("traceId", "dummy-wrapper-trace")
        }.toString()
    }
}
