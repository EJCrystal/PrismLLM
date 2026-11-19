package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.domain.PluginInstallationRecord
import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.api.IqTestTargetResponse
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.domain.UpstreamHeaderProfile
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.ChannelKeyUpdate
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.oauth.infra.OAuthTokenResponse
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.plugin.host.PluginCatalogItem
import dev.soven.prismllm.plugin.host.PluginExecution
import dev.soven.prismllm.plugin.host.PluginRuntimeState
import dev.soven.prismllm.plugin.host.PluginUiContribution
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import dev.soven.prismllm.proxy.analyzePelicanStructure
import dev.soven.prismllm.proxy.fingerprint.FingerprintOutput
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.spi.iq.IqJudge
import dev.soven.prismllm.spi.iq.IqJudgeRequest
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real independently packaged JVM extension, PostgreSQL desired state, and mock-only upstreams. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
@Tag("channel")
@Timeout(60)
class ConnectivityPluginIntegrationTest : GatewayIntegrationTestBase() {
    @TempDir
    lateinit var auxiliaryDirectory: Path

    private val probes = ConcurrentLinkedQueue<ObservedRequest>()
    private val preparations = ConcurrentLinkedQueue<ObservedRequest>()
    private val unexpectedEndpoints = ConcurrentLinkedQueue<String>()
    private val probeHold = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val runningProbes = AtomicInteger()
    private val maximumRunningProbes = AtomicInteger()
    private val json = GatewayApplication.gatewayJson

    @BeforeEach
    fun isolateOutboundHttp() {
        val base = app.config
        app.close()
        probes.clear()
        preparations.clear()
        unexpectedEndpoints.clear()
        probeHold.set(null)
        runningProbes.set(0)
        maximumRunningProbes.set(0)
        app = mockedApplication(base.copy(
            maxAttempts = 1,
            oauth = base.oauth.copy(refreshIntervalMs = 3_600_000, quotaIntervalMs = 3_600_000),
        ))
    }

    @AfterEach
    fun verifyNetworkIsolation() {
        probeHold.getAndSet(null)?.complete(Unit)
        app.close()
        assertTrue(unexpectedEndpoints.isEmpty(), "Unmocked outbound endpoints: ${unexpectedEndpoints.joinToString()}")
        assertTrue(fake.requests.isEmpty(), "Probe traffic must not escape MockEngine")
        assertTrue(fake.modelRequests.isEmpty(), "Model discovery must not escape MockEngine")
        assertTrue(fake.tokenRequests.isEmpty(), "Rejected probes must not prepare OAuth tokens; success fixtures use cached tokens")
        assertTrue(fake.quotaRequests.isEmpty(), "Diagnostic probes must not query account quota")
        assertTrue(fake.manxueRequests.isEmpty(), "Connectivity must not call the independent IQ classifier")
    }

    @Test
    fun `trusted disk package cold loads its manifest ui plan and report without an implementation classpath dependency`() = runBlocking {
        val fixture = readPackageFixture()
        val providerResource = fixture.provider.replace('.', '/') + ".class"
        assertNull(GatewayApplication::class.java.classLoader.getResource(providerResource), "The implementation must not be on the app classpath")
        assertTrue(GatewayApplication::class.java.classLoader.getResources(DIAGNOSTIC_SERVICE).toList().isEmpty(),
            "Acceptance must load the package's service descriptor, not a classpath-injected provider")
        assertFalse(app.config.pluginCatalogDir == app.config.pluginInstallDir)
        assertFalse(app.config.pluginInstallDir.startsWith(app.config.pluginCatalogDir))
        assertFalse(app.config.pluginCatalogDir.startsWith(app.config.pluginInstallDir))
        assertEquals(pluginInstallationDirectory, app.config.pluginInstallDir)
        assertEquals(quotaPluginDirectory, app.config.quotaPluginDir)
        assertEquals(iqJudgePluginDirectory, app.config.iqJudgePluginDir)

        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val catalogResponse = client.api(HttpMethod.Get, "/admin/api/plugin-catalog", session)
            val catalog = catalogResponse.decoded<List<PluginCatalogItem>>().single { it.manifest.id == PLUGIN_ID }
            assertEquals("no-store", catalogResponse.headers[HttpHeaders.CacheControl])
            assertEquals(fixture.manifest, catalog.manifest)
            assertEquals(fixture.ui, catalog.ui)
            assertEquals(fixture.sha256, catalog.sha256)
            assertTrue(catalog.compatible, catalog.compatibilityReason)
            assertNull(catalog.compatibilityReason)
            assertTrue(catalog.source.isNotBlank())
            assertEquals(PluginApi.permissions, catalog.manifest.permissions.toSet())
            val promptField = catalog.manifest.configuration.single()
            assertEquals("defaultPrompt", promptField.key)
            assertEquals("textarea", promptField.kind)
            assertTrue(promptField.required)
            assertEquals(4_000, promptField.maxLength)
            assertEquals(JsonPrimitive(DEFAULT_PROMPT), promptField.defaultValue)

            val state = client.pluginState(session)
            assertActive(state)
            assertEquals(fixture.ui, state.activeUi)
            assertEquals(fixture.manifest.configuration, state.configurationFields)
            assertEquals(JsonPrimitive(DEFAULT_PROMPT), state.configuration["defaultPrompt"])
            assertEquals(state.configuration, state.activeConfiguration)
            val contributions = client.api(HttpMethod.Get, "/admin/api/plugins/ui", session).decoded<List<PluginUiContribution>>()
            assertEquals(fixture.ui.entries, contributions.map { it.entry })
            assertTrue(contributions.all { it.pluginId == PLUGIN_ID && it.activeVersion == VERSION })
            assertEquals(setOf("channel-tests", "oauth-account-tests", "plugin-page"), contributions.map { it.entry.placement }.toSet())
            assertTrue(contributions.all { it.entry.action.resource == "model-test-targets" && it.entry.action.resultKind == "probe-v1" })
            assertTrue(contributions.flatMap { it.entry.action.fields }.all { it.kind in FIELD_KINDS })
            val accountEntry = contributions.single { it.entry.placement == "oauth-account-tests" }.entry
            assertFalse(accountEntry.action.supportsBatch)
            assertTrue(accountEntry.action.fields.none { it.kind in setOf("credential", "scope") })
            assertTrue(contributions.filter { it.entry.placement != "oauth-account-tests" }.all { it.entry.action.supportsBatch })

            val channel = directChannel("disk-package", GatewayProtocol.CHAT_COMPLETIONS)
            val action = contributions.single { it.entry.placement == "channel-tests" }
            val response = client.invokePlugin(session, "channel", channel.id,
                values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, channel.keys.single().id, "  package planned prompt  "),
                actionId = action.entry.action.id, expectedVersion = action.activeVersion)
            val execution = response.decoded<PluginExecution>()
            assertEquals(PLUGIN_ID, execution.pluginId)
            assertEquals(VERSION, execution.activeVersion)
            assertEquals("channel", execution.target.kind)
            assertEquals(channel.id, execution.target.id)
            assertEquals(channel.keys.single().id, execution.target.credentialId)
            assertEquals("package planned prompt", execution.plan.prompt)
            assertEquals(128, execution.plan.maxOutputTokens)
            assertEquals(45_000L, execution.plan.timeoutMs)
            assertTrue(execution.outcome.success)
            assertEquals(200, execution.outcome.statusCode)
            assertEquals("OK", execution.outcome.responseText)
            assertEquals("reported-fixture-model", execution.outcome.upstreamReportedModel)
            assertEquals(JsonPrimitive(10L), execution.outcome.usage?.get("inputTokens"))
            assertTrue(execution.report.success)
            assertEquals("连通性检测成功", execution.report.title)
            assertEquals("OK", execution.report.text)
            val metrics = execution.report.metrics.associate { it.label to it.value }
            assertEquals("128", metrics["最大输出 Token"])
            assertEquals("45000 ms", metrics["超时上限"])
            assertEquals("10", metrics["输入 Token"])
            assertTrue(execution.report.notices.isNotEmpty())
            assertEquals(setOf("prompt", "maxOutputTokens", "timeoutMs"), response.objectPayload()["plan"]!!.jsonObject.keys,
                "A plugin plan cannot supply target selection, endpoints, or credentials")
            assertFalse(response.bodyAsText().contains(STATIC_TOKEN))
            assertProbeBody(probes.single(), GatewayProtocol.CHAT_COMPLETIONS, "package planned prompt")

            val staged = pluginInstallationDirectory.resolve(PLUGIN_ID).resolve(VERSION)
            assertEquals(fixture.sha256, sha256(Files.readAllBytes(staged.resolve("package.prismplugin"))))
            assertTrue(fixture.jar.contentEquals(Files.readAllBytes(staged.resolve("plugin.jar"))))
            assertNull(GatewayApplication::class.java.classLoader.getResource(providerResource), "Cold loading must not modify the parent classpath")
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `catalogue state ui resources and every mutation require administrator session and csrf`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val channel = directChannel("admin-auth", GatewayProtocol.CHAT_COMPLETIONS)
            val reads = listOf("/plugin-catalog", "/plugins", "/plugins/ui", "/plugins/$PLUGIN_ID",
                "/plugins/$PLUGIN_ID/resources/model-test-targets?contextKind=channel&contextId=${channel.id}")
            reads.forEach { path ->
                assertEquals(HttpStatusCode.Unauthorized, client.api(HttpMethod.Get, "/admin/api$path").status, path)
            }
            val session = client.adminSession()
            reads.forEach { path ->
                assertEquals(HttpStatusCode.OK, client.api(HttpMethod.Get, "/admin/api$path", session, csrfToken = null).status, path)
            }
            val original = assertNotNull(app.pluginInstallationRepository.find(PLUGIN_ID))
            val mutations = listOf(
                Mutation(HttpMethod.Put, "/plugins/$PLUGIN_ID/installation", installationPayload()),
                Mutation(HttpMethod.Put, "/plugins/$PLUGIN_ID/enabled", """{"enabled":false}"""),
                Mutation(HttpMethod.Put, "/plugins/$PLUGIN_ID/configuration", configurationPayload("unauthorized change")),
                Mutation(HttpMethod.Delete, "/plugins/$PLUGIN_ID", null),
                Mutation(HttpMethod.Post, "/plugins/$PLUGIN_ID/invocations", invocationPayload("channel", channel.id,
                    values(MODEL, GatewayProtocol.CHAT_COMPLETIONS))),
            )
            mutations.forEach { mutation ->
                val path = "/admin/api${mutation.path}"
                assertEquals(HttpStatusCode.Unauthorized, client.api(mutation.method, path, payload = mutation.payload).status, path)
                assertEquals(HttpStatusCode.Forbidden, client.api(mutation.method, path, session, mutation.payload, csrfToken = null).status, path)
                assertEquals(HttpStatusCode.Forbidden, client.api(mutation.method, path, session, mutation.payload, csrfToken = "invalid-csrf").status, path)
            }
            assertEquals(original, app.pluginInstallationRepository.find(PLUGIN_ID), "Failed auth/CSRF cannot change desired state")
            assertActive(client.pluginState(session))
            assertEquals(HttpStatusCode.NotFound, client.api(HttpMethod.Get, "/admin/api/plugins/prism.missing", session).status)
            assertTrue(probes.isEmpty())
            assertTrue(preparations.isEmpty())
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `manual first install migrates legacy prompt only when no prior plugin record exists`() = runBlocking {
        val emptyCatalog = Files.createDirectory(auxiliaryDirectory.resolve("manual-install-catalog"))
        restartApplication(app.config.copy(pluginCatalogDir = emptyCatalog))
        app.modelTestSettingsRepository.save("legacy manual-install prompt")
        testApplication {
            application {
                app.install(this)
                Files.copy(connectivityPluginCatalogDirectory.resolve("catalog.json"), emptyCatalog.resolve("catalog.json"))
                Files.copy(connectivityPluginCatalogDirectory.resolve("prism-connectivity-1.0.0.prismplugin"), emptyCatalog.resolve("prism-connectivity-1.0.0.prismplugin"))
            }
            val session = client.adminSession()
            assertNull(app.pluginInstallationRepository.find(PLUGIN_ID))
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugin-catalog", session).decoded<List<PluginCatalogItem>>().any { it.manifest.id == PLUGIN_ID })
            val first = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session, installationPayload())
                .decoded<PluginRuntimeState>()
            assertTrue(first.installed)
            assertFalse(first.desiredEnabled, "Manual install remains disabled until explicitly enabled")
            assertEquals(JsonPrimitive("legacy manual-install prompt"), first.configuration["defaultPrompt"])
            val record = assertNotNull(app.pluginInstallationRepository.find(PLUGIN_ID))
            assertEquals(first.configuration, record.configuration)

            app.modelTestSettingsRepository.save("changed legacy prompt must not replace plugin state")
            val replay = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session, installationPayload())
                .decoded<PluginRuntimeState>()
            assertEquals(first, replay)
            assertEquals(record, app.pluginInstallationRepository.find(PLUGIN_ID), "A later install cannot overwrite the winning record")
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `legacy default prompt settings bridge plugin configuration immediately and invocation overrides never persist`() = runBlocking {
        app.modelTestSettingsRepository.save("legacy startup prompt")
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val channel = directChannel("settings-bridge", GatewayProtocol.CHAT_COMPLETIONS)
            assertEquals(JsonPrimitive("legacy startup prompt"), client.pluginState(session).activeConfiguration?.get("defaultPrompt"))

            val configured = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/configuration", session,
                configurationPayload("plugin configured prompt")).decoded<PluginRuntimeState>()
            assertActive(configured)
            assertEquals(configured.configuration, configured.activeConfiguration)
            val legacyRead = client.api(HttpMethod.Get, "/admin/api/model-test-settings", session).objectPayload()
            assertEquals("plugin configured prompt", legacyRead["defaultPrompt"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, legacyRead["defaultReasoningEffort"])
            val generic = client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS))
                .decoded<PluginExecution>()
            assertEquals("plugin configured prompt", generic.plan.prompt)
            val legacy = client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL, GatewayProtocol.CHAT_COMPLETIONS)
                .objectPayload()
            assertEquals("plugin configured prompt", legacy["prompt"]!!.jsonPrimitive.content)
            val override = client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, prompt = "  one invocation  "))
                .decoded<PluginExecution>()
            assertEquals("one invocation", override.plan.prompt)
            assertEquals(configured.configuration, client.pluginState(session).configuration)

            val legacyWrite = client.api(HttpMethod.Put, "/admin/api/model-test-settings", session,
                """{"defaultPrompt":"  legacy configured prompt  ","defaultReasoningEffort":"high"}""").objectPayload()
            assertEquals("legacy configured prompt", legacyWrite["defaultPrompt"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, legacyWrite["defaultReasoningEffort"])
            val current = client.pluginState(session)
            assertActive(current)
            assertEquals(JsonPrimitive("legacy configured prompt"), current.configuration["defaultPrompt"])
            assertEquals(current.configuration, current.activeConfiguration)
            val blank = client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, prompt = "   "))
                .decoded<PluginExecution>()
            assertEquals("legacy configured prompt", blank.plan.prompt)
            assertEquals("legacy configured prompt", client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL,
                GatewayProtocol.CHAT_COMPLETIONS).objectPayload()["prompt"]!!.jsonPrimitive.content)
            val paddedLegacy = client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL,
                GatewayProtocol.CHAT_COMPLETIONS, prompt = "  padded legacy prompt${" ".repeat(270_000)}  ").objectPayload()
            assertEquals("padded legacy prompt", paddedLegacy["prompt"]!!.jsonPrimitive.content)
            assertProbeBody(probes.last(), GatewayProtocol.CHAT_COMPLETIONS, "padded legacy prompt")

            val invalidConfigurations = listOf(
                buildJsonObject { put("defaultPrompt", 7) },
                buildJsonObject { put("defaultPrompt", " ") },
                buildJsonObject { put("defaultPrompt", "x".repeat(4_001)) },
                buildJsonObject { put("defaultPrompt", "valid"); put("endpoint", "http://127.0.0.1:1") },
            )
            invalidConfigurations.forEach { invalid ->
                client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/configuration", session,
                    buildJsonObject { put("values", invalid) }.toString()).assertError(HttpStatusCode.BadRequest, "PLUGIN_INVALID_CONFIGURATION")
                assertEquals(current.configuration, client.pluginState(session).configuration)
            }
            listOf(" ", "x".repeat(4_001)).forEach { prompt ->
                client.api(HttpMethod.Put, "/admin/api/model-test-settings", session,
                    buildJsonObject { put("defaultPrompt", prompt) }.toString()).assertError(HttpStatusCode.BadRequest, "invalid_model_test_settings")
            }
            assertEquals(6, probes.size)
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `generic and legacy channel APIs preserve protocol selected key and final failure json without diagnostic settlement`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            createClientKey()
            val channel = app.channelRepository.create(
                name = "protocol-and-key", baseUrl = "${fake.baseUrl}/ok/v1", authType = "bearer",
                protocols = GatewayProtocol.entries.toList(), enabled = true, modelMappings = mappingsOf(MODEL),
                keys = listOf(
                    NewChannelKey("not-selected", app.credentialCipher.encrypt("unselected-static-token"), models = listOf(MODEL)),
                    NewChannelKey("selected", app.credentialCipher.encrypt(STATIC_TOKEN), priority = 2, models = listOf(MODEL)),
                ),
            )
            val selected = channel.keys.single { it.name == "selected" }
            val other = directChannel("filtered-out", GatewayProtocol.CHAT_COMPLETIONS)
            val resources = client.api(HttpMethod.Get,
                "/admin/api/plugins/$PLUGIN_ID/resources/model-test-targets?contextKind=channel&contextId=${channel.id}", session)
                .decoded<List<IqTestTargetResponse>>()
            assertEquals(setOf(channel.id), resources.map { it.channelId }.toSet())
            assertTrue(resources.none { it.channelId == other.id })
            assertEquals(channel.keys.map { it.id }.toSet(), resources.single().credentials.map { it.id }.toSet())
            assertFalse(resources.toString().contains(STATIC_TOKEN))

            GatewayProtocol.entries.forEach { protocol ->
                val generic = client.invokePlugin(session, "channel", channel.id, values(MODEL, protocol, selected.id, "  direct probe  "))
                    .decoded<PluginExecution>()
                assertEquals(selected.id, generic.target.credentialId)
                assertEquals(selected.name, generic.target.credentialName)
                assertEquals(protocol.value, generic.target.protocol)
                assertTrue(generic.outcome.success)
                assertProbeBody(probes.last(), protocol, "direct probe")
                assertEquals("Bearer $STATIC_TOKEN", probes.last().authorization)
                val legacy = client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL, protocol,
                    selected.id, "  direct probe  ").objectPayload()
                assertLegacySuccess(legacy, MODEL, protocol, selected.id, "direct probe")
                assertProbeBody(probes.last(), protocol, "direct probe")
                assertEquals("Bearer $STATIC_TOKEN", probes.last().authorization)
                assertFalse(legacy.toString().contains(STATIC_TOKEN))
            }
            val failureChannel = directChannel("upstream-401", GatewayProtocol.CHAT_COMPLETIONS, scenario = "401")
            val failed = client.invokePlugin(session, "channel", failureChannel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS))
                .decoded<PluginExecution>()
            assertFalse(failed.outcome.success)
            assertEquals(401, failed.outcome.statusCode, "Upstream 401 belongs inside the HTTP 200 execution, not the admin session")
            assertEquals("upstream denied", failed.outcome.errorMessage)
            assertFalse(failed.report.success)
            assertEquals("连通性检测失败", failed.report.title)
            assertEquals("upstream denied", failed.report.text)
            val legacyFailure = client.legacyProbe(session, "/channels/${failureChannel.id}/model-test", MODEL,
                GatewayProtocol.CHAT_COMPLETIONS).objectPayload()
            assertEquals(JsonPrimitive(false), legacyFailure["success"])
            assertEquals(JsonPrimitive(401), legacyFailure["statusCode"])
            assertEquals(JsonPrimitive("upstream denied"), legacyFailure["errorMessage"])
            assertEquals(JsonNull, legacyFailure["reasoningEffort"])
            assertFalse("pluginId" in legacyFailure)
            assertEquals(10, probes.size, "Each invocation sends exactly once; diagnostic failures are never retried")
            assertEquals(0, scalarInt("select count(*) from channel_keys where state <> 'ACTIVE'"))
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `credential crossing the response preview boundary leaves no prefix in outcome or report`() = runBlocking {
        val base = app.config
        app.close()
        val prefix = "x".repeat(990)
        app = mockedApplication(base, probeResponse = { request ->
            val token = assertNotNull(request.headers[HttpHeaders.Authorization]).removePrefix("Bearer ")
            HttpStatusCode.OK to CHAT_RESPONSE.replace("\"OK\"", JsonPrimitive(prefix + token + " trailing").toString())
        })
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val channel = directChannel("boundary-echo", GatewayProtocol.CHAT_COMPLETIONS)
            val response = client.invokePlugin(session, "channel", channel.id,
                values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, channel.keys.single().id))
            val body = response.objectPayload()
            val outcome = body["outcome"]!!.jsonObject["responseText"]!!.jsonPrimitive.content
            val report = body["report"]!!.jsonObject["text"]!!.jsonPrimitive.content
            for (text in listOf(outcome, report)) {
                assertTrue(text.startsWith(prefix))
                assertTrue(text.endsWith("[REDACTED]"))
                assertFalse(text.contains(STATIC_TOKEN.take(8)), "a credential prefix must not survive preview truncation")
            }
        }
    }

    @Test
    fun `generic and legacy channel and native OAuth responses redact echoed credentials in outcomes and reports`() = runBlocking {
        val upstreamStatus = AtomicReference(HttpStatusCode.OK)
        val echoedTokens = ConcurrentLinkedQueue<String>()
        val base = app.config
        app.close()
        app = mockedApplication(base, probeResponse = { request ->
            val authorization = assertNotNull(request.headers[HttpHeaders.Authorization])
            assertTrue(authorization.startsWith("Bearer "))
            val token = authorization.removePrefix("Bearer ")
            assertTrue(token.isNotBlank())
            echoedTokens.add(token)
            val body = if (upstreamStatus.get() == HttpStatusCode.Unauthorized) {
                buildJsonObject {
                    put("error", buildJsonObject {
                        put("message", "upstream denied $token")
                        put("code", "invalid_api_key")
                    })
                }.toString()
            } else {
                val fixture = when {
                    request.url.host + request.url.encodedPath == NATIVE_GEMINI_ENDPOINT -> """{"response":$GEMINI_RESPONSE}"""
                    request.url.encodedPath.endsWith("/responses") -> RESPONSES_RESPONSE
                    else -> CHAT_RESPONSE
                }
                fixture.replace("\"OK\"", JsonPrimitive("OK echoed $token").toString())
                    .replace("\"reported-fixture-model\"", JsonPrimitive("reported-$token").toString())
            }
            upstreamStatus.get() to body
        })
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val secrets = listOf(STATIC_TOKEN) + OAuthProvider.entries.flatMap {
                listOf(accessToken(it), "refresh-${it.value}")
            }
            suspend fun verifyTarget(kind: String, id: String, model: String, protocol: GatewayProtocol, keyId: String? = null) {
                for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
                    upstreamStatus.set(status)
                    for (legacy in listOf(false, true)) {
                        val response = if (legacy) {
                            client.legacyProbe(session, if (kind == "channel") "/channels/$id/model-test" else "/oauth-accounts/$id/model-test",
                                model, protocol, keyId, "echo safeguard")
                        } else {
                            client.invokePlugin(session, kind, id, values(model, protocol, keyId, "echo safeguard"))
                        }
                        val text = response.bodyAsText()
                        val body = response.objectPayload()
                        val outcome = if (legacy) body else body["outcome"]!!.jsonObject
                        val report = if (legacy) null else body["report"]!!.jsonObject
                        secrets.forEach { secret ->
                            assertFalse(text.contains(secret), "$kind legacy=$legacy $status leaked a credential in the HTTP response")
                            assertFalse(outcome.toString().contains(secret), "Outcome leaked an echoed credential")
                            assertFalse(report?.toString()?.contains(secret) == true, "Plugin report leaked an echoed credential")
                        }
                        assertEquals(JsonPrimitive(status.value), outcome["statusCode"])
                        assertEquals(JsonPrimitive(status == HttpStatusCode.OK), outcome["success"])
                        report?.let {
                            assertEquals(outcome["success"], it["success"])
                            val prefix = if (status == HttpStatusCode.OK) "OK echoed" else "upstream denied"
                            assertTrue(it["text"]!!.jsonPrimitive.content.contains(prefix))
                            val metrics = it["metrics"]!!.jsonArray.associate { metric ->
                                metric.jsonObject["label"]!!.jsonPrimitive.content to metric.jsonObject["value"]!!.jsonPrimitive.content
                            }
                            assertEquals(status.value.toString(), metrics["HTTP 状态"])
                            if (status == HttpStatusCode.OK) assertEquals(listOf("10", "2", "12"),
                                listOf(metrics["输入 Token"], metrics["输出 Token"], metrics["总 Token"]))
                        }
                        if (status == HttpStatusCode.OK) {
                            assertTrue(outcome["responseText"]!!.jsonPrimitive.content.contains("OK echoed"))
                            assertTrue(outcome["upstreamReportedModel"]!!.jsonPrimitive.content.startsWith("reported-"))
                            assertEquals(JsonPrimitive(10L), outcome["usage"]!!.jsonObject["inputTokens"])
                            assertEquals(JsonPrimitive(2L), outcome["usage"]!!.jsonObject["outputTokens"])
                            assertEquals(JsonPrimitive(12L), outcome["usage"]!!.jsonObject["totalTokens"])
                        } else {
                            assertTrue(outcome["errorMessage"]!!.jsonPrimitive.content.contains("upstream denied"))
                        }
                    }
                }
            }
            val channel = directChannel("echo-channel", GatewayProtocol.CHAT_COMPLETIONS)
            verifyTarget("channel", channel.id, MODEL, GatewayProtocol.CHAT_COMPLETIONS, channel.keys.single().id)
            for (provider in OAuthProvider.entries) {
                val model = if (provider == OAuthProvider.CHATGPT) "gpt-6-luna" else "gemini-plugin-native"
                val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
                val account = oauthAccount("echo-${provider.value}", provider, listOf(model), listOf(channelId))
                assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, model))))
                verifyTarget("oauth-account", account.id, model, ProviderEndpoints.of(provider).protocols.single())
                assertEquals(4, echoedTokens.count { it == accessToken(provider) })
            }
            assertEquals(4, echoedTokens.count { it == STATIC_TOKEN })
            assertEquals(12, probes.size, "Each generic/legacy success or 401 probe must be sent exactly once")
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `corrupted saved channel credential retains gateway 502 for generic and legacy probes without transport or settlement`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val encrypted = app.credentialCipher.encrypt(STATIC_TOKEN)
            val corrupted = encrypted.copy(ciphertext = "invalid-base64")
            val channel = app.channelRepository.create(
                name = "corrupted-saved-credential", baseUrl = "${fake.baseUrl}/ok/v1", authType = "bearer",
                protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS), enabled = true, modelMappings = mappingsOf(MODEL),
                keys = listOf(
                    NewChannelKey("selected-corrupted", corrupted, models = listOf(MODEL)),
                    NewChannelKey("unused-valid", encrypted, priority = 2, models = listOf(MODEL)),
                ),
            )
            val original = assertNotNull(app.channelRepository.find(channel.id))
            val selected = original.keys.single { it.name == "selected-corrupted" }
            assertEquals(corrupted.ciphertext, selected.credentialCiphertext, "The invalid ciphertext must come from persisted state")
            val responses = listOf(
                client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, selected.id)),
                client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL, GatewayProtocol.CHAT_COMPLETIONS, selected.id),
            )
            responses.forEach { response ->
                response.assertError(HttpStatusCode.BadGateway, "upstream_credential_invalid")
                val text = response.bodyAsText()
                val body = Json.parseToJsonElement(text).jsonObject
                assertEquals(JsonPrimitive("api_error"), body["error"]!!.jsonObject["type"])
                assertFalse("outcome" in body, "Host credential preparation failure is not an HTTP 200 diagnostic outcome")
                assertFalse(text.contains(STATIC_TOKEN))
                assertFalse(text.contains(corrupted.ciphertext))
            }
            assertEquals(original, app.channelRepository.find(channel.id), "Diagnostic preparation cannot disable or rewrite either saved key")
            assertActive(client.pluginState(session))
            assertNoPreparation()
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `OAuth channel probes stay direct while generic and legacy account probes use native provider authorization`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            for (provider in OAuthProvider.entries) {
                val protocol = ProviderEndpoints.of(provider).protocols.single()
                val model = if (provider == OAuthProvider.CHATGPT) "gpt-6-luna" else "gemini-plugin-native"
                val direct = directChannel("oauth-direct-${provider.value}", protocol, model)
                val providerId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
                val account = oauthAccount("native-${provider.value}", provider, listOf(model), listOf(direct.id, providerId))
                assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, "public-${provider.value}"))))

                val directExecution = client.invokePlugin(session, "channel", direct.id, values(model, protocol, account.id, "direct account probe"))
                    .decoded<PluginExecution>()
                assertEquals("channel", directExecution.target.kind)
                assertEquals(direct.id, directExecution.target.id)
                assertEquals(account.id, directExecution.target.credentialId)
                assertTrue(directExecution.outcome.success)
                assertEquals("127.0.0.1", probes.last().host)
                assertFalse("project" in probes.last().body, "Channel mode must not acquire a provider envelope")
                assertProbeBody(probes.last(), protocol, "direct account probe")
                val directLegacy = client.legacyProbe(session, "/channels/${direct.id}/model-test", model, protocol,
                    account.id, "direct account probe").objectPayload()
                assertLegacySuccess(directLegacy, model, protocol, account.id, "direct account probe")
                assertEquals("127.0.0.1", probes.last().host)

                val resourceResponse = client.api(HttpMethod.Get,
                    "/admin/api/plugins/$PLUGIN_ID/resources/model-test-targets?contextKind=oauth-account&contextId=${account.id}", session)
                val resources = resourceResponse.decoded<List<IqTestTargetResponse>>()
                assertEquals(listOf(model), resources.map { it.model })
                assertTrue(resources.all { it.channelId == account.id && it.channelName == account.name })
                assertEquals(listOf(account.id), resources.single().credentials.map { it.id })
                assertEquals(listOf(protocol.value), resources.single().protocols)
                assertFalse(resourceResponse.bodyAsText().contains(accessToken(provider)))
                assertFalse(resourceResponse.bodyAsText().contains(direct.keys.single().id))
                val unlinked = oauthAccount("resource-only-${provider.value}", provider, listOf(model), emptyList())
                assertTrue(unlinked.channelIds.isEmpty())
                val beforeResources = probes.size
                val standaloneTargets = client.api(HttpMethod.Get,
                    "/admin/api/plugins/$PLUGIN_ID/resources/model-test-targets?contextKind=oauth-account&contextId=${unlinked.id}", session)
                    .decoded<List<IqTestTargetResponse>>()
                assertEquals(listOf(model), standaloneTargets.map { it.model }, "Native choices cannot require linked channel credentials")
                assertEquals(listOf(unlinked.id), standaloneTargets.single().credentials.map { it.id })
                assertEquals(unlinked.id, standaloneTargets.single().channelId)
                assertEquals(listOf(protocol.value), standaloneTargets.single().protocols)
                assertEquals(beforeResources, probes.size, "Reading native choices must not probe or prepare credentials")

                val native = client.invokePlugin(session, "oauth-account", account.id, values(model, protocol, prompt = "native account probe"))
                    .decoded<PluginExecution>()
                assertTrue(native.outcome.success)
                assertEquals("oauth-account", native.target.kind)
                assertEquals(account.id, native.target.id)
                assertEquals(account.id, native.target.credentialId)
                assertEquals(account.name, native.target.displayName)
                assertEquals(account.name, native.target.credentialName)
                assertNativeRequest(probes.last(), provider, model, "native account probe")
                // The compatibility body may still contain keyId, but account mode never selects it.
                val nativeLegacy = client.legacyProbe(session, "/oauth-accounts/${account.id}/model-test", model, protocol,
                    direct.keys.single().id, "native account probe").objectPayload()
                assertLegacySuccess(nativeLegacy, model, protocol, account.id, "native account probe")
                assertNativeRequest(probes.last(), provider, model, "native account probe")
                assertFalse(nativeLegacy.toString().contains(accessToken(provider)))
                assertFalse(nativeLegacy.toString().contains("refresh-${provider.value}"))
            }
            assertEquals(8, probes.size)
            assertEquals(1, preparations.size, "Only the native Antigravity call discovers a project; the second call reuses it")
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `native OAuth rechecks credentials model entitlement and endpoint settings after project discovery`() = runBlocking {
        val pendingDiscovery = AtomicReference<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>?>()
        val base = app.config
        app.close()
        app = mockedApplication(base, projectDelayMs = {
            val (entered, release) = checkNotNull(pendingDiscovery.get())
            entered.complete(Unit)
            release.await()
            0
        })
        val provider = OAuthProvider.ANTIGRAVITY
        val model = "gemini-plugin-native"
        val protocol = GatewayProtocol.GEMINI
        val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))

        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            for (mutation in listOf("access-token", "proxy", "models", "endpoint")) {
                val account = oauthAccount("snapshot-$mutation", provider, listOf(model), listOf(channelId))
                assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, model))))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                pendingDiscovery.set(entered to release)
                val before = preparations.size
                coroutineScope {
                    val response = async { client.invokePlugin(session, "oauth-account", account.id, values(model, protocol)) }
                    val expectedError = try {
                        withTimeout(5_000) { entered.await() }
                        assertEquals("Bearer ${accessToken(provider)}", preparations.last().authorization)
                        when (mutation) {
                            "access-token" -> {
                                val saved = assertNotNull(app.oauthAccountRepository.saveRefreshed(
                                    accountId = account.id,
                                    accessToken = app.credentialCipher.encrypt("rotated-during-diagnostic"),
                                    expiresAt = System.currentTimeMillis() + 86_400_000,
                                    tokenType = "Bearer", scope = null,
                                    expectedRefreshCiphertext = account.refreshTokenCiphertext,
                                ))
                                assertEquals(account.authorizedAt, saved.authorizedAt)
                                "oauth_account_changed"
                            }
                            "proxy" -> {
                                assertNotNull(app.oauthAccountRepository.updateSettings(account.id, proxyUrl = "http://127.0.0.1:9"))
                                "oauth_account_changed"
                            }
                            "models" -> {
                                assertNotNull(app.oauthAccountRepository.setModels(account.id, listOf("other-authorized-model")))
                                "model_not_authorized"
                            }
                            else -> {
                                val channel = assertNotNull(app.channelRepository.find(channelId))
                                assertNotNull(app.channelRepository.update(
                                    id = channel.id, name = channel.name, baseUrl = channel.baseUrl,
                                    authType = channel.authType, enabled = channel.enabled,
                                    requestOverrides = UpstreamRequestOverrides(userAgent = "changed-during-diagnostic"),
                                ))
                                "oauth_provider_channel_changed"
                            }
                        }
                    } finally {
                        release.complete(Unit)
                    }
                    withTimeout(5_000) { response.await() }.assertError(HttpStatusCode.BadRequest, expectedError)
                }
                assertEquals(before + 1, preparations.size, mutation)
                assertTrue(probes.isEmpty(), "$mutation must reject before model HTTP")
            }
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `disabled ACTIVE account remains manually testable but REAUTH_REQUIRED legacy probe cannot prepare`() = runBlocking {
        val provider = OAuthProvider.ANTIGRAVITY
        val model = "gemini-plugin-native"
        val protocol = GatewayProtocol.GEMINI
        val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
        val account = oauthAccount("manual-disabled-account", provider, listOf(model), listOf(channelId))
        assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, model))))
        assertFalse(assertNotNull(app.oauthAccountRepository.updateSettings(account.id, enabled = false)).enabled)

        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val execution = client.invokePlugin(session, "oauth-account", account.id, values(model, protocol)).decoded<PluginExecution>()
            assertTrue(execution.outcome.success)
            val path = "/oauth-accounts/${account.id}/model-test"
            val prompt = "disabled account manual probe"
            val legacy = client.legacyProbe(session, path, model, protocol, prompt = prompt).objectPayload()
            assertLegacySuccess(legacy, model, protocol, account.id, prompt)
            assertEquals(2, probes.size)
            assertEquals(1, preparations.size)

            assertEquals(OAuthAuthState.REAUTH_REQUIRED,
                assertNotNull(app.oauthAccountRepository.recordFailure(account.id, "invalid_grant", terminal = true)).authState)
            client.legacyProbe(session, path, model, protocol).assertError(HttpStatusCode.BadGateway, "oauth_credential_unavailable")
            assertEquals(2, probes.size, "An account requiring reauthorization must not send a model request")
            assertEquals(1, preparations.size, "An account requiring reauthorization must not discover a project")
            assertTrue(fake.tokenRequests.isEmpty())
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `native OAuth probe uses one header catalogue snapshot when a new version is published during preparation`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val provider = OAuthProvider.ANTIGRAVITY
            val model = "gemini-plugin-native"
            val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
            val account = oauthAccount("header-snapshot", provider, listOf(model), listOf(channelId))
            assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, model))))
            app.upstreamRequestSettingsRepository.save(UpstreamRequestOverrides(headerProfile = "antigravity"))
            val catalogue = app.upstreamHeaderCatalogService
            val defaults = catalogue.profiles()
            fun profiles(version: String): List<UpstreamHeaderProfile> = defaults.map { profile ->
                if (profile.id != "antigravity") profile else profile.copy(
                    version = version,
                    headers = mapOf(
                        HttpHeaders.UserAgent to "antigravity/hub/$version (snapshot-fixture)",
                        "X-Profile-Version" to version,
                    ),
                )
            }
            val oldProfiles = profiles("99.0.1")
            val newProfiles = profiles("99.0.2")
            val now = System.currentTimeMillis()
            val repository = app.upstreamHeaderCatalogRepository
            repository.saveRefresh(oldProfiles, checkedAt = now, successAt = now, lastError = null)
            catalogue.initialize()
            // Warm project discovery separately so the captured profiles belong only to the model request.
            app.codeAssistProjectResolver.context(account, accessToken(provider))
            preparations.clear()
            var captures = 0
            mockkObject(catalogue)
            try {
                every { catalogue.profiles() } answers {
                    captures += 1
                    // A refresh publishes a new immutable list immediately after this request reads the old one.
                    if (captures == 1) {
                        runBlocking {
                            repository.saveRefresh(newProfiles, checkedAt = now + 1, successAt = now + 1, lastError = null)
                            catalogue.initialize()
                        }
                        oldProfiles
                    } else newProfiles
                }
                val first = client.invokePlugin(session, "oauth-account", account.id,
                    values(model, GatewayProtocol.GEMINI, prompt = "old snapshot")).decoded<PluginExecution>()
                assertTrue(first.outcome.success)
                assertEquals(1, captures, "Identity headers and request policy must share the same catalogue read")
                assertEquals(listOf("antigravity/hub/99.0.1 (snapshot-fixture)"), probes.single().headers.getAll(HttpHeaders.UserAgent))
                assertEquals("99.0.1", probes.single().headers["X-Profile-Version"])
                assertNull(probes.single().headers["X-Goog-Api-Client"])
            } finally {
                unmockkObject(catalogue)
            }
            assertEquals("antigravity/hub/99.0.2 (snapshot-fixture)", app.currentAntigravityUserAgent())
            val next = client.invokePlugin(session, "oauth-account", account.id,
                values(model, GatewayProtocol.GEMINI, prompt = "new snapshot")).decoded<PluginExecution>()
            assertTrue(next.outcome.success)
            assertEquals(2, probes.size)
            assertEquals(listOf("antigravity/hub/99.0.2 (snapshot-fixture)"), probes.last().headers.getAll(HttpHeaders.UserAgent))
            assertEquals("99.0.2", probes.last().headers["X-Profile-Version"])
            assertTrue(preparations.isEmpty(), "The warmed project must not add unrelated catalogue reads")
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `initial OAuth account lookup preserves missing account and bounds a blocked query for both routes`() = runBlocking {
        val base = app.config
        app.close()
        app = mockedApplication(base, preparationTimeoutMs = 1_500)
        val provider = OAuthProvider.CHATGPT
        val model = "gpt-6-initial-lookup"
        val protocol = GatewayProtocol.RESPONSES

        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            suspend fun invoke(accountId: String, legacy: Boolean): HttpResponse = if (legacy) {
                client.legacyProbe(session, "/oauth-accounts/$accountId/model-test", model, protocol)
            } else {
                client.invokePlugin(session, "oauth-account", accountId, values(model, protocol))
            }
            for (legacy in listOf(false, true)) {
                invoke("missing-initial-account", legacy).assertError(HttpStatusCode.NotFound, "oauth_account_not_found")
            }
            val account = oauthAccount("initial-lookup-blocked", provider, listOf(model), emptyList())
            for (legacy in listOf(false, true)) {
                DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { lock ->
                    lock.autoCommit = false
                    val blocker = lock.createStatement().use { statement ->
                        statement.executeQuery("select pg_backend_pid()").use { rows ->
                            check(rows.next())
                            rows.getInt(1)
                        }
                    }
                    lock.createStatement().use { it.execute("lock table oauth_accounts in access exclusive mode") }
                    try {
                        coroutineScope {
                            val pending = async(Dispatchers.Default) { invoke(account.id, legacy) }
                            awaitCondition("initial account query is blocked by the table lock", timeoutMs = 5_000) {
                                DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { observer ->
                                    observer.createStatement().use { statement ->
                                        statement.executeQuery("select exists (select 1 from pg_stat_activity where $blocker = any(pg_blocking_pids(pid)))").use { rows ->
                                            check(rows.next())
                                            rows.getBoolean(1)
                                        }
                                    }
                                }
                            }
                            assertPreparationTimeout(withTimeout(5_000) { pending.await() }, provider)
                        }
                    } finally {
                        lock.rollback()
                    }
                }
            }
            assertNoPreparation()
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `OAuth native preparation shares one budget across token and project while HTTP keeps its plan timeout`() = runBlocking {
        assertEquals(20_000L, app.oauthDiagnosticPreparationTimeoutMs)
        val prepareBudgetMs = 20_000L
        val clock = AtomicLong()
        fun advance(milliseconds: Long) { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(milliseconds)) }
        val scheduledTimeouts = ConcurrentLinkedQueue<Long>()
        val executor = object : ScheduledThreadPoolExecutor(2) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                scheduledTimeouts.add(unit.toMillis(delay))
                return super.schedule(command, delay, unit)
            }
        }
        executor.asCoroutineDispatcher().use { prepareDispatcher ->
            val tokenElapsedMs = AtomicLong()
            val projectElapsedMs = AtomicLong()
            val tokenRefreshes = AtomicInteger()
            val base = app.config
            app.close()
            app = mockedApplication(base, projectDelayMs = {
                advance(projectElapsedMs.get())
                0
            }, beforeProbe = {
                assertTrue(45_000L in scheduledTimeouts, "HTTP must receive the unmodified plan timeout")
                // HTTP may outlive the entire preparation allowance after that scope has ended.
                advance(prepareBudgetMs + 1)
            }, preparationTimeoutMs = prepareBudgetMs, preparationNanoTime = clock::get)
            val provider = OAuthProvider.ANTIGRAVITY
            val tokens = assertNotNull(app.oauthTokenProviders.find(provider))
            mockkObject(tokens)
            try {
                coEvery { tokens.refresh(any(), any()) } coAnswers {
                    tokenRefreshes.incrementAndGet()
                    advance(tokenElapsedMs.get())
                    OAuthTokenResponse(accessToken(provider), refreshToken = "rotated-${provider.value}")
                }
                testApplication {
                    engine { dispatcher = prepareDispatcher }
                    application { app.install(this) }
                    val session = client.adminSession()
                    val model = "gemini-plugin-native"
                    val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
                    suspend fun account(name: String): OAuthAccountRecord {
                        val account = oauthAccount(name, provider, listOf(model), listOf(channelId), cached = false)
                        assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id,
                            listOf(OAuthAccountModelCatalogEntry(model, model))))
                        return account
                    }
                    val fast = account("prepare-within-budget")
                    val slowToken = account("prepare-slow-token")
                    val cumulative = account("prepare-cumulative")

                    val success = client.invokePlugin(session, "oauth-account", fast.id,
                        values(model, GatewayProtocol.GEMINI, prompt = "independent HTTP budget")).decoded<PluginExecution>()
                    assertTrue(success.outcome.success)
                    assertEquals(45_000L, success.plan.timeoutMs)
                    assertEquals(1, probes.size)
                    assertNativeRequest(probes.remove(), provider, model, "independent HTTP budget")
                    preparations.clear()

                    tokenElapsedMs.set(prepareBudgetMs + 1)
                    assertPreparationTimeout(
                        client.invokePlugin(session, "oauth-account", slowToken.id, values(model, GatewayProtocol.GEMINI)), provider,
                    )
                    withTimeout(5_000) {
                        while (app.oauthAccountRepository.findDetailed(slowToken.id)?.hasAccessToken != true) delay(10)
                    }
                    val saved = assertNotNull(app.oauthAccountRepository.findDetailed(slowToken.id))
                    assertTrue(saved.hasAccessToken,
                        "A provider response that may rotate refresh credentials must finish its CAS save after the caller times out")
                    assertTrue(preparations.isEmpty(), "A timed-out token cannot proceed to project discovery")

                    // Each phase fits alone. Advancing the same monotonic clock makes their sum decisive.
                    tokenElapsedMs.set(14_000)
                    projectElapsedMs.set(7_000)
                    assertPreparationTimeout(
                        client.invokePlugin(session, "oauth-account", cumulative.id, values(model, GatewayProtocol.GEMINI)), provider,
                    )
                    assertEquals(1, preparations.size, "The same deadline must also cover project discovery")
                    assertEquals(3, tokenRefreshes.get())
                }
            } finally {
                unmockkObject(tokens)
            }
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `OAuth refresh save survives diagnostic timeout while the token row is locked`() = runBlocking {
        val base = app.config
        app.close()
        app = mockedApplication(base, preparationTimeoutMs = 1_500)
        val provider = OAuthProvider.CHATGPT
        val tokens = assertNotNull(app.oauthTokenProviders.find(provider))
        val refreshes = AtomicInteger()
        val exchangeStarted = CompletableDeferred<Unit>()
        val finishExchange = CompletableDeferred<Unit>()
        mockkObject(tokens)
        try {
            coEvery { tokens.refresh(any(), any()) } coAnswers {
                refreshes.incrementAndGet()
                exchangeStarted.complete(Unit)
                finishExchange.await()
                OAuthTokenResponse(accessToken(provider), refreshToken = "rotated-refresh-${provider.value}")
            }
            testApplication {
                application { app.install(this) }
                val session = client.adminSession()
                val model = "gpt-6-plugin-native"
                val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
                val account = oauthAccount("prepare-blocked-token-write", provider, listOf(model), listOf(channelId), cached = false)
                assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, model))))
                val input = values(model, GatewayProtocol.RESPONSES, prompt = "probe after lock release")

                DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { lock ->
                    lock.autoCommit = false
                    coroutineScope {
                        val pending = async(Dispatchers.Default) { client.invokePlugin(session, "oauth-account", account.id, input) }
                        withTimeout(5_000) { exchangeStarted.await() }
                        lock.prepareStatement("select id from oauth_accounts where id = ? for update nowait").use { statement ->
                            statement.setString(1, account.id)
                            statement.executeQuery().use { assertTrue(it.next()) }
                        }
                        finishExchange.complete(Unit)
                        val response = try {
                            withTimeoutOrNull(5_000) { pending.await() }
                        } finally {
                            lock.rollback()
                        }
                        assertPreparationTimeout(assertNotNull(response, "the caller must stop waiting while CAS is blocked"), provider)
                    }
                }
                withTimeout(5_000) {
                    while (app.oauthAccountRepository.findDetailed(account.id)?.hasAccessToken != true) delay(10)
                }
                val saved = assertNotNull(app.oauthAccountRepository.findDetailed(account.id))
                assertTrue(saved.hasAccessToken, "The service-owned task must finish saving the exchanged access token")
                val refreshContext = assertNotNull(app.oauthAccountRepository.loadRefreshContext(account.id))
                assertEquals("rotated-refresh-${provider.value}",
                    app.credentialCipher.decrypt(refreshContext.refreshTokenCiphertext, refreshContext.refreshTokenNonce))
                assertTrue(preparations.isEmpty(), "A timed-out diagnostic cannot continue native project discovery")
                assertTrue(probes.isEmpty(), "A timed-out diagnostic cannot send the model request")

                val success = client.invokePlugin(session, "oauth-account", account.id, input).decoded<PluginExecution>()
                assertTrue(success.outcome.success)
                assertEquals(1, refreshes.get(), "The next diagnostic must reuse the access token saved by the first exchange")
                assertNativeRequest(probes.single(), provider, model, "probe after lock release")
            }
        } finally {
            finishExchange.complete(Unit)
            unmockkObject(tokens)
        }
        assertNoDiagnosticSettlement()
    }

    private suspend fun assertPreparationTimeout(response: HttpResponse, provider: OAuthProvider) {
        assertTrue(probes.isEmpty(), "Timed-out preparation must not enter model transport")
        response.assertError(HttpStatusCode.GatewayTimeout, "oauth_preparation_timeout")
        val text = response.bodyAsText()
        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive("upstream_error"), body["error"]!!.jsonObject["type"])
        assertTrue(body["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("超时"))
        assertFalse("outcome" in body, "Preparation timeout must remain an auditable gateway failure")
        val audit = app.adminAuditRepository.list(20).single { it.requestId == response.headers["X-Request-Id"] }
        assertEquals(504, audit.statusCode)
        assertEquals("failure", audit.result)
        assertEquals("oauth_preparation_timeout", audit.errorCode)
        listOf(accessToken(provider), "refresh-${provider.value}", PROJECT).forEach { secret ->
            assertFalse(text.contains(secret), "Preparation failure must sanitize credentials and project")
            assertFalse(audit.toString().contains(secret), "Audit must not contain preparation secrets")
        }
    }

    @Test
    fun `invalid inputs grants stale active version and oversized legacy prompt reject before transport or token preparation`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val direct = directOAuthChannel()
            val account = oauthAccount("unprepared", OAuthProvider.CHATGPT, listOf("gpt-6-luna"), listOf(direct.id), cached = false)
            val original = assertNotNull(app.oauthAccountRepository.findDetailed(account.id))
            val channelValues = values("gpt-6-luna", GatewayProtocol.RESPONSES, account.id)
            val accountValues = values("gpt-6-luna", GatewayProtocol.RESPONSES)
            listOf("channel" to direct.id, "oauth-account" to account.id).forEach { (kind, id) ->
                client.invokePlugin(session, kind, id, if (kind == "channel") channelValues else accountValues,
                    expectedVersion = "0.0.0").assertError(HttpStatusCode.Conflict, "PLUGIN_ACTIVE_VERSION_CONFLICT")
                // The app selects a declared UI action before host execution or credential preparation.
                client.invokePlugin(session, kind, id, if (kind == "channel") channelValues else accountValues,
                    actionId = "unknown-action").assertError(HttpStatusCode.BadRequest, "invalid_plugin_input")
                val tooLong = "x".repeat(4_001)
                client.invokePlugin(session, kind, id, if (kind == "channel") {
                    values("gpt-6-luna", GatewayProtocol.RESPONSES, account.id, tooLong)
                } else values("gpt-6-luna", GatewayProtocol.RESPONSES, prompt = tooLong))
                    .assertError(HttpStatusCode.BadRequest, "PLUGIN_INVALID_INPUT")
                client.legacyProbe(session, if (kind == "channel") "/channels/$id/model-test" else "/oauth-accounts/$id/model-test",
                    "gpt-6-luna", GatewayProtocol.RESPONSES, if (kind == "channel") account.id else null, "  $tooLong  ")
                    .assertError(HttpStatusCode.BadRequest, "invalid_model_test_prompt")
            }
            client.invokePlugin(session, "oauth-account", account.id, accountValues, actionId = "probe-channel")
                .assertError(HttpStatusCode.BadRequest, "invalid_plugin_input")
            client.invokePlugin(session, "channel", direct.id, JsonObject(channelValues + ("endpoint" to JsonPrimitive("http://127.0.0.1:1"))))
                .assertError(HttpStatusCode.BadRequest, "PLUGIN_INVALID_INPUT")
            client.invokePlugin(session, "oauth-account", account.id, JsonObject(accountValues + ("keyId" to JsonPrimitive(account.id))))
                .assertError(HttpStatusCode.BadRequest, "PLUGIN_INVALID_INPUT")
            client.invokePlugin(session, "channel", direct.id, values("not-authorized", GatewayProtocol.RESPONSES, account.id))
                .assertError(HttpStatusCode.BadRequest, "model_not_authorized")
            client.invokePlugin(session, "oauth-account", account.id, values("gpt-6-luna", GatewayProtocol.MESSAGES))
                .assertError(HttpStatusCode.BadRequest, "model_test_protocol_unsupported")
            client.invokePlugin(session, "endpoint", account.id, accountValues)
                .assertError(HttpStatusCode.BadRequest, "invalid_plugin_context")
            client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session, """{"version":"$VERSION","approvedPermissions":[]}""")
                .assertError(HttpStatusCode.Forbidden, "PLUGIN_PERMISSION_REQUIRED")
            client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/enabled", session, """{"enabled":true,"approvedPermissions":[]}""")
                .assertError(HttpStatusCode.Forbidden, "PLUGIN_PERMISSION_REQUIRED")
            client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session,
                """{"version":"$VERSION","approvedPermissions":["arbitrary.network"]}""")
                .assertError(HttpStatusCode.BadRequest, "PLUGIN_INVALID_GRANTS")
            assertActive(client.pluginState(session))
            assertEquals(original, app.oauthAccountRepository.findDetailed(account.id), "Rejected probes cannot refresh or mutate the account")
            assertNoPreparation()
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `queued channel probe rejects its disabled bound key even with a valid spare`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        probeHold.set(release)
        try {
            testApplication {
                application { app.install(this) }
                val session = client.adminSession()
                val protocol = GatewayProtocol.CHAT_COMPLETIONS
                val channel = app.channelRepository.create(
                    name = "queued-key-revocation", baseUrl = "${fake.baseUrl}/ok/v1",
                    keys = listOf(
                        NewChannelKey("selected", app.credentialCipher.encrypt(STATIC_TOKEN), models = listOf(MODEL)),
                        NewChannelKey("spare", app.credentialCipher.encrypt("unselected-static-token"), priority = 2, models = listOf(MODEL)),
                    ),
                    authType = "bearer", protocols = listOf(protocol), enabled = true, modelMappings = mappingsOf(MODEL),
                )
                val original = channel.keys.single { it.name == "selected" }
                // An execution permit is acquired only after the adapter binds the target, before waiting for a probe slot.
                val executions = app.pluginRuntime.javaClass.getDeclaredField("executions").let { field ->
                    field.isAccessible = true
                    field.get(app.pluginRuntime) as Semaphore
                }
                val idlePermits = executions.availablePermits
                coroutineScope {
                    val running = (1..2).map { index ->
                        async { client.invokePlugin(session, "channel", channel.id, values(MODEL, protocol, original.id, "occupied $index")) }
                    }
                    val queued = try {
                        awaitCondition("both upstream workers occupied", timeoutMs = 5_000) { runningProbes.get() == 2 }
                        val invocation = async { client.invokePlugin(session, "channel", channel.id, values(MODEL, protocol)) }
                        awaitCondition("queued invocation bound its target", timeoutMs = 5_000) { executions.availablePermits == idlePermits - 3 }
                        val changed = assertNotNull(app.channelRepository.update(
                            id = channel.id, name = channel.name, baseUrl = channel.baseUrl,
                            authType = channel.authType, enabled = channel.enabled,
                            keys = channel.keys.map { key ->
                                ChannelKeyUpdate(
                                    id = key.id, name = key.name, priority = key.priority, models = key.models,
                                    state = if (key.id == original.id) ChannelKeyState.MANUAL_DISABLED else key.state,
                                    stateVersion = key.stateVersion,
                                )
                            },
                        ))
                        assertEquals(ChannelKeyState.MANUAL_DISABLED, changed.keys.single { it.id == original.id }.state)
                        assertEquals(ChannelKeyState.ACTIVE, changed.keys.single { it.name == "spare" }.state)
                        invocation
                    } finally {
                        release.complete(Unit)
                    }
                    withTimeout(5_000) {
                        running.awaitAll().forEach { assertTrue(it.decoded<PluginExecution>().outcome.success) }
                        queued.await().assertError(HttpStatusCode.BadRequest, "channel_key_disabled")
                    }
                }
                assertEquals(2, probes.size, "The queued probe may neither use the original key nor switch to the spare")
                assertTrue(probes.all { it.authorization == "Bearer $STATIC_TOKEN" })
            }
        } finally {
            release.complete(Unit)
            probeHold.set(null)
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `queued native OAuth probe rechecks revoked model authorization before preparation`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        probeHold.set(release)
        try {
            testApplication {
                application { app.install(this) }
                val session = client.adminSession()
                val occupied = directChannel("queued-oauth-occupants", GatewayProtocol.CHAT_COMPLETIONS)
                val model = "gpt-6-queued-native"
                val provider = OAuthProvider.CHATGPT
                val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
                val account = oauthAccount("queued-model-revocation", provider, listOf(model), listOf(channelId))
                assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry(model, model))))
                val executions = app.pluginRuntime.javaClass.getDeclaredField("executions").let { field ->
                    field.isAccessible = true
                    field.get(app.pluginRuntime) as Semaphore
                }
                val idlePermits = executions.availablePermits
                coroutineScope {
                    val running = (1..2).map { index ->
                        async { client.invokePlugin(session, "channel", occupied.id,
                            values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, prompt = "occupied $index")) }
                    }
                    val queued = try {
                        awaitCondition("both workers occupied before native admission", timeoutMs = 5_000) { runningProbes.get() == 2 }
                        val invocation = async { client.invokePlugin(session, "oauth-account", account.id,
                            values(model, GatewayProtocol.RESPONSES)) }
                        awaitCondition("native target bound before probe admission", timeoutMs = 5_000) {
                            executions.availablePermits == idlePermits - 3
                        }
                        assertNotNull(app.oauthAccountRepository.setModels(account.id, listOf("other-authorized-model")))
                        invocation
                    } finally {
                        release.complete(Unit)
                    }
                    withTimeout(5_000) {
                        running.awaitAll().forEach { assertTrue(it.decoded<PluginExecution>().outcome.success) }
                        queued.await().assertError(HttpStatusCode.BadRequest, "model_not_authorized")
                    }
                }
                assertEquals(2, probes.size, "Revoked native authorization must not send model HTTP")
                assertTrue(preparations.isEmpty(), "Queued revocation must reject before project discovery")
            }
        } finally {
            release.complete(Unit)
            probeHold.set(null)
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `batch scope invocations use at most two upstream workers and return complete final reports`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        probeHold.set(release)
        try {
            testApplication {
                application { app.install(this) }
                val session = client.adminSession()
                val channel = directChannel("two-workers", GatewayProtocol.CHAT_COMPLETIONS)
                coroutineScope {
                    val results = (1..4).map { index ->
                        async {
                            val input = JsonObject(values(MODEL, GatewayProtocol.CHAT_COMPLETIONS, prompt = "batch $index") +
                                ("scope" to JsonPrimitive("all")))
                            client.invokePlugin(session, "channel", channel.id, input).decoded<PluginExecution>()
                        }
                    }
                    awaitCondition("two upstream workers waiting at the mock barrier") { runningProbes.get() == 2 }
                    assertEquals(2, probes.size, "Queued targets must not prepare/send a third probe")
                    release.complete(Unit)
                    val executions = results.awaitAll()
                    assertEquals(setOf("batch 1", "batch 2", "batch 3", "batch 4"), executions.map { it.plan.prompt }.toSet())
                    assertTrue(executions.all { it.outcome.success && it.report.success && it.report.text == "OK" })
                    assertEquals(4, probes.size)
                    assertEquals(2, maximumRunningProbes.get())
                }
            }
        } finally {
            release.complete(Unit)
            probeHold.set(null)
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `pending channel only upgrade preserves active OAuth grants until explicit disable`() = runBlocking {
        val catalog = Files.createDirectory(auxiliaryDirectory.resolve("pending-grant-catalog"))
        listOf("catalog.json", "prism-connectivity-1.0.0.prismplugin").forEach { name ->
            Files.copy(connectivityPluginCatalogDirectory.resolve(name), catalog.resolve(name))
        }
        restartApplication(app.config.copy(pluginCatalogDir = catalog))
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            assertActive(client.pluginState(session))
            val provider = OAuthProvider.CHATGPT
            val model = "gpt-6-luna"
            val channelId = assertNotNull(app.providerChannelProvisioner.ensure(provider))
            val account = oauthAccount("pending-upgrade-grant", provider, listOf(model), listOf(channelId))
            assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id,
                listOf(OAuthAccountModelCatalogEntry(model, model))))
            publishChannelOnlyUpgrade(catalog)
            val pending = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session,
                """{"version":"2.0.0","approvedPermissions":["${PluginApi.CHANNEL_PROBE}"]}""").decoded<PluginRuntimeState>()
            assertEquals("2.0.0", pending.installedVersion)
            assertEquals(VERSION, pending.activeVersion)
            assertEquals(listOf(PluginApi.CHANNEL_PROBE), pending.approvedPermissions)
            assertTrue(pending.admissionEnabled)
            assertTrue(pending.requiresRestart)
            assertTrue(pending.activeUi!!.entries.any { "oauth-account" in it.contexts })

            val input = values(model, GatewayProtocol.RESPONSES, prompt = "active V1 OAuth grant")
            val execution = client.invokePlugin(session, "oauth-account", account.id, input).decoded<PluginExecution>()
            assertEquals(VERSION, execution.activeVersion)
            assertTrue(execution.outcome.success)
            assertNativeRequest(probes.single(), provider, model, "active V1 OAuth grant")
            client.legacyProbe(session, "/oauth-accounts/${account.id}/model-test", model, GatewayProtocol.RESPONSES,
                prompt = "legacy active V1 grant").objectPayload().let {
                assertLegacySuccess(it, model, GatewayProtocol.RESPONSES, account.id, "legacy active V1 grant")
            }
            assertEquals(2, probes.size)
            client.invokePlugin(session, "oauth-account", account.id, input, expectedVersion = "2.0.0")
                .assertError(HttpStatusCode.Conflict, "PLUGIN_ACTIVE_VERSION_CONFLICT")
            client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/enabled", session, """{"enabled":false}""")
                .decoded<PluginRuntimeState>().let { assertFalse(it.admissionEnabled) }
            client.invokePlugin(session, "oauth-account", account.id, input)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            client.legacyProbe(session, "/oauth-accounts/${account.id}/model-test", model, GatewayProtocol.RESPONSES)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            assertEquals(2, probes.size)
            assertTrue(preparations.isEmpty())
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `disable and reenable leave desired active mismatch gated until a cold restart`() = runBlocking {
        lateinit var static: ChannelRecord
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            static = directChannel("after-restart", GatewayProtocol.CHAT_COMPLETIONS)
            val direct = directOAuthChannel()
            val account = oauthAccount("disabled-account", OAuthProvider.CHATGPT, listOf("gpt-6-luna"), listOf(direct.id), cached = false)
            val disabled = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/enabled", session, """{"enabled":false}""")
                .decoded<PluginRuntimeState>()
            assertFalse(disabled.desiredEnabled)
            assertFalse(disabled.admissionEnabled)
            assertEquals(VERSION, disabled.activeVersion, "Already loaded code may remain, but it is not executable")
            assertEquals("disabled", disabled.status)
            assertNull(disabled.activeUi)
            client.assertGated(session, direct.id, account.id)
            val enabled = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/enabled", session, enabledPayload()).decoded<PluginRuntimeState>()
            assertTrue(enabled.desiredEnabled)
            assertFalse(enabled.admissionEnabled)
            assertTrue(enabled.requiresRestart)
            assertEquals(VERSION, enabled.activeVersion)
            assertEquals("pending-restart", enabled.status)
            assertNull(enabled.activeUi)
            app.pluginRuntime.initialize(bootstrapId = PLUGIN_ID)
            assertFalse(client.pluginState(session).admissionEnabled, "Reinitializing this process cannot reopen the admission latch")
            client.assertGated(session, direct.id, account.id)
            assertNoPreparation()
        }
        restartApplication()
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            assertActive(client.pluginState(session))
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugins/ui", session).decoded<List<PluginUiContribution>>().isNotEmpty())
            assertTrue(client.invokePlugin(session, "channel", static.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS)).decoded<PluginExecution>().outcome.success)
            assertEquals(1, probes.size)
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `uninstall tombstone survives reinitialization and restart and idempotent reinstall loads only on the next enabled restart`() = runBlocking {
        lateinit var tombstone: PluginInstallationRecord
        lateinit var channel: ChannelRecord
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            channel = directChannel("restored-plugin", GatewayProtocol.CHAT_COMPLETIONS)
            client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/configuration", session, configurationPayload("retained prompt"))
                .decoded<PluginRuntimeState>()
            val removed = client.api(HttpMethod.Delete, "/admin/api/plugins/$PLUGIN_ID", session).decoded<PluginRuntimeState>()
            assertFalse(removed.installed)
            assertFalse(removed.desiredEnabled)
            assertFalse(removed.admissionEnabled)
            assertEquals("uninstalled", removed.status)
            assertEquals(VERSION, removed.installedVersion)
            assertNull(removed.activeUi)
            tombstone = assertNotNull(app.pluginInstallationRepository.find(PLUGIN_ID))
            assertFalse(tombstone.installed)
            assertEquals(JsonPrimitive("retained prompt"), tombstone.configuration["defaultPrompt"])
            app.pluginRuntime.initialize(bootstrapId = PLUGIN_ID)
            assertEquals(tombstone, app.pluginInstallationRepository.find(PLUGIN_ID))
            client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS))
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
        }
        restartApplication()
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            val absent = client.pluginState(session)
            assertFalse(absent.installed)
            assertFalse(absent.desiredEnabled)
            assertFalse(absent.admissionEnabled)
            assertNull(absent.activeVersion)
            assertEquals("uninstalled", absent.status)
            assertEquals(tombstone, app.pluginInstallationRepository.find(PLUGIN_ID), "Bootstrap must never erase an uninstall decision")
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugins/ui", session).decoded<List<PluginUiContribution>>().isEmpty())
            val restored = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session, installationPayload()).decoded<PluginRuntimeState>()
            assertTrue(restored.installed)
            assertFalse(restored.desiredEnabled, "Manual restoration is disabled until explicitly enabled")
            assertFalse(restored.admissionEnabled)
            assertNull(restored.activeVersion, "Installing immutable data must not execute/load the JAR")
            assertEquals(tombstone.configuration, restored.configuration)
            val persisted = assertNotNull(app.pluginInstallationRepository.find(PLUGIN_ID))
            val replay = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/installation", session, installationPayload()).decoded<PluginRuntimeState>()
            assertEquals(restored, replay)
            assertEquals(persisted, app.pluginInstallationRepository.find(PLUGIN_ID), "Same version/digest replay must not increment revision or reset config")
            val pending = client.api(HttpMethod.Put, "/admin/api/plugins/$PLUGIN_ID/enabled", session, enabledPayload()).decoded<PluginRuntimeState>()
            assertTrue(pending.desiredEnabled)
            assertFalse(pending.admissionEnabled)
            assertTrue(pending.requiresRestart)
            assertNull(pending.activeVersion)
            app.pluginRuntime.initialize(bootstrapId = PLUGIN_ID)
            assertFalse(client.pluginState(session).admissionEnabled)
            client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL, GatewayProtocol.CHAT_COMPLETIONS)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            assertTrue(probes.isEmpty())
            assertEquals(tombstone.sha256, sha256(Files.readAllBytes(pluginInstallationDirectory.resolve(PLUGIN_ID).resolve(VERSION).resolve("package.prismplugin"))))
        }
        val emptyCatalog = Files.createDirectory(auxiliaryDirectory.resolve("managed-only-restart"))
        restartApplication(app.config.copy(pluginCatalogDir = emptyCatalog))
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugin-catalog", session).decoded<List<PluginCatalogItem>>().isEmpty())
            val active = client.pluginState(session)
            assertActive(active)
            assertEquals(tombstone.configuration, active.activeConfiguration)
            val execution = client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS)).decoded<PluginExecution>()
            assertTrue(execution.outcome.success)
            assertEquals("retained prompt", execution.plan.prompt)
            assertEquals(1, probes.size)
        }
        assertNoDiagnosticSettlement()
    }

    @Test
    fun `absent connectivity leaves core forwarding legacy IQ jars and fingerprint facilities independent`() = runBlocking {
        val emptyCatalog = Files.createDirectory(auxiliaryDirectory.resolve("empty-catalog"))
        writeLegacyIqJar()
        restartApplication(app.config.copy(pluginCatalogDir = emptyCatalog))
        testApplication {
            application { app.install(this) }
            val session = client.adminSession()
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugin-catalog", session).decoded<List<PluginCatalogItem>>().isEmpty())
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugins", session).decoded<List<PluginRuntimeState>>().isEmpty())
            assertTrue(client.api(HttpMethod.Get, "/admin/api/plugins/ui", session).decoded<List<PluginUiContribution>>().isEmpty())
            assertNull(app.pluginInstallationRepository.find(PLUGIN_ID))
            assertEquals(HttpStatusCode.OK, client.api(HttpMethod.Get, "/health/ready").status)
            val channel = directChannel("core-without-connectivity", GatewayProtocol.CHAT_COMPLETIONS)
            exposeAsModel("without-connectivity", channel to MODEL)
            val key = createClientKey()
            val core = client.request("/v1/chat/completions") {
                method = HttpMethod.Post
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"without-connectivity","messages":[{"role":"user","content":"core is independent"}],"max_tokens":8}""")
            }
            assertEquals(HttpStatusCode.OK, core.status, core.bodyAsText())
            assertEquals("without-connectivity", awaitRequestLog().alias)
            val sent = probes.size
            client.legacyProbe(session, "/channels/${channel.id}/model-test", MODEL, GatewayProtocol.CHAT_COMPLETIONS)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            client.invokePlugin(session, "channel", channel.id, values(MODEL, GatewayProtocol.CHAT_COMPLETIONS))
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            val unlinked = oauthAccount("unlinked-account", OAuthProvider.CHATGPT, listOf("gpt-6-luna"), emptyList(), cached = false)
            client.legacyProbe(session, "/oauth-accounts/${unlinked.id}/model-test", "gpt-6-luna", GatewayProtocol.RESPONSES)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            client.invokePlugin(session, "oauth-account", unlinked.id, values("gpt-6-luna", GatewayProtocol.RESPONSES))
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            assertEquals(sent, probes.size)
            assertTrue(preparations.isEmpty())
            assertEquals(0, scalarInt("select count(*) from channels where kind = 'provider'"))

            val iqTargets = client.api(HttpMethod.Get, "/admin/api/iq-tests/targets", session).decoded<List<IqTestTargetResponse>>()
            assertTrue(iqTargets.any { it.channelId == channel.id && it.model == MODEL })
            val judges = client.api(HttpMethod.Get, "/admin/api/iq-tests/judges", session).objectPayload()
            assertTrue(judges["judges"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == LEGACY_JUDGE_ID })
            assertTrue(judges["failures"]!!.jsonArray.isEmpty())
            val legacy = assertNotNull(app.iqJudgeRegistry.find(LEGACY_JUDGE_ID))
            assertNotSame(GatewayApplication::class.java.classLoader, legacy.javaClass.classLoader)
            val html = "<html><body><svg></svg></body></html>"
            val judgeResult = app.iqJudgeService.judge(LEGACY_JUDGE_ID, IqJudgeRequest("pelican", html, MODEL, analyzePelicanStructure(html)))
            assertEquals(IqJudgeVerdict.REVIEW, judgeResult.verdict)
            assertEquals("Legacy disk judge", judgeResult.label)
            assertNotNull(app.iqJudgeRegistry.find("local"))
            assertEquals(quotaPluginDirectory, app.config.quotaPluginDir)
            assertEquals(iqJudgePluginDirectory, app.config.iqJudgePluginDir)
            assertTrue(app.channelQuotaProviderRegistry.loadFailures.isEmpty())

            val info = client.api(HttpMethod.Get, "/admin/api/model-tests/fingerprint/info", session).objectPayload()
            assertTrue(info["modelCount"]!!.jsonPrimitive.int > 0)
            assertEquals(64, info["detectorSha256"]!!.jsonPrimitive.content.length)
            val samples = assertNotNull(javaClass.getResourceAsStream("/fingerprint/real_samples.json"))
                .use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonArray }
            val outputs = samples.map { it.jsonObject }.map {
                FingerprintOutput(it["expected_count"]!!.jsonPrimitive.int, it["text"]!!.jsonPrimitive.content)
            }
            val analysis = assertNotNull(app.fingerprintDetector).analyze(outputs)
            assertEquals(3, analysis.usedOutputs)
            assertEquals("scored", analysis.decision)
            assertEquals("claude-sonnet-4.6", analysis.prediction)
            assertEquals(0, scalarInt("select count(*) from plugin_installations"))
        }
    }

    private fun mockedApplication(
        config: GatewayConfig,
        projectDelayMs: suspend () -> Long = { 0 },
        beforeProbe: suspend () -> Unit = {},
        probeResponse: ((HttpRequestData) -> Pair<HttpStatusCode, String>)? = null,
        preparationTimeoutMs: Long? = null,
        preparationNanoTime: () -> Long = System::nanoTime,
    ): GatewayApplication {
        val mock = HttpClient(MockEngine) {
            engine {
                reuseHandlers = true
                addHandler { request ->
                    val endpoint = request.url.host + request.url.encodedPath
                    when {
                        request.method == HttpMethod.Get && request.url.protocol.name == "https" && endpoint == "models.dev/catalog.json" ->
                            respond("{}", headers = JSON_HEADERS)
                        request.method == HttpMethod.Post && request.url.protocol.name == "https" && endpoint == LOAD_ENDPOINT -> {
                            preparations.add(observe(request))
                            delay(projectDelayMs())
                            respond("""{"cloudaicompanionProject":"$PROJECT","currentTier":{"id":"standard-tier"},"paidTier":{"id":"standard-tier"}}""", headers = JSON_HEADERS)
                        }
                        isProbe(request) -> {
                            probes.add(observe(request))
                            val active = runningProbes.incrementAndGet()
                            maximumRunningProbes.updateAndGet { maxOf(it, active) }
                            try {
                                probeHold.get()?.await()
                                beforeProbe()
                                if (probeResponse != null) {
                                    val (status, body) = probeResponse(request)
                                    respond(body, status, JSON_HEADERS)
                                } else if (request.url.encodedPath.startsWith("/401/")) {
                                    respond("""{"error":{"message":"upstream denied","code":"invalid_api_key"}}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                                } else {
                                    val response = when {
                                        endpoint == NATIVE_GEMINI_ENDPOINT -> """{"response":$GEMINI_RESPONSE}"""
                                        request.url.encodedPath.endsWith("/responses") -> RESPONSES_RESPONSE
                                        request.url.encodedPath.endsWith("/messages") -> MESSAGES_RESPONSE
                                        request.url.encodedPath.contains(":generateContent") -> GEMINI_RESPONSE
                                        else -> CHAT_RESPONSE
                                    }
                                    respond(response, headers = JSON_HEADERS)
                                }
                            } finally {
                                runningProbes.decrementAndGet()
                            }
                        }
                        else -> {
                            val safe = endpoint.replace(Regex("[^A-Za-z0-9._~:/%-]"), "_").take(256)
                            unexpectedEndpoints.add(safe)
                            error("Unmocked outbound endpoint: $safe")
                        }
                    }
                }
            }
        }
        return if (preparationTimeoutMs == null) {
            GatewayApplication(config, httpClientOverride = mock)
        } else {
            GatewayApplication(config, notificationTransport = null, httpClientOverride = mock,
                oauthDiagnosticPreparationTimeoutMs = preparationTimeoutMs, oauthDiagnosticNanoTime = preparationNanoTime)
        }
    }

    private fun isProbe(request: HttpRequestData): Boolean {
        if (request.method != HttpMethod.Post) return false
        val endpoint = request.url.host + request.url.encodedPath
        if (request.url.protocol.name == "https" && endpoint in setOf(NATIVE_RESPONSES_ENDPOINT, NATIVE_GEMINI_ENDPOINT)) return true
        if (request.url.protocol.name != "http" || request.url.host != "127.0.0.1" || request.url.port != URI.create(fake.baseUrl).port) return false
        return listOf("/ok/v1", "/401/v1").any { prefix ->
            request.url.encodedPath in setOf("$prefix/chat/completions", "$prefix/responses", "$prefix/messages") ||
                (request.url.encodedPath.startsWith("$prefix/models/") && request.url.encodedPath.endsWith(":generateContent"))
        }
    }

    private fun observe(request: HttpRequestData): ObservedRequest {
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.toString(Charsets.UTF_8)
            ?: error("Expected a bounded JSON request body")
        return ObservedRequest(request.url.host, request.url.encodedPath, request.headers[HttpHeaders.Authorization],
            Json.parseToJsonElement(body).jsonObject, request.headers)
    }

    private fun restartApplication(config: GatewayConfig = app.config) {
        app.close()
        app = mockedApplication(config)
    }

    private suspend fun directChannel(name: String, protocol: GatewayProtocol, model: String = MODEL, scenario: String = "ok") =
        createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1", listOf(protocol), mappingsOf(model))

    private suspend fun directOAuthChannel() = app.channelRepository.create(
        name = "oauth-needs-preparation", baseUrl = "${fake.baseUrl}/ok/v1", keys = emptyList(), authType = "bearer",
        protocols = listOf(GatewayProtocol.RESPONSES), enabled = true, modelMappings = mappingsOf("gpt-6-luna"),
    )

    private suspend fun oauthAccount(name: String, provider: OAuthProvider, models: List<String>, channelIds: List<String>, cached: Boolean = true): OAuthAccountRecord =
        app.oauthAccountRepository.create(NewOAuthAccount(
            name = name, provider = provider, models = models, channelIds = channelIds,
            refreshToken = app.credentialCipher.encrypt("refresh-${provider.value}"),
            accessToken = if (cached) app.credentialCipher.encrypt(accessToken(provider)) else null,
            accessTokenExpiresAt = if (cached) System.currentTimeMillis() + 86_400_000 else null,
        ))

    private fun accessToken(provider: OAuthProvider) = "connectivity-acceptance-access-${provider.value}"

    private suspend fun HttpClient.adminSession(): AdminSession = loginAdmin().let { AdminSession(it.first, it.second) }

    private suspend fun HttpClient.api(method: HttpMethod, path: String, session: AdminSession? = null,
        payload: String? = null, csrfToken: String? = session?.csrf): HttpResponse = request(path) {
        this.method = method
        session?.let { header(HttpHeaders.Cookie, it.cookie) }
        csrfToken?.let { header("X-CSRF-Token", it) }
        payload?.let { contentType(ContentType.Application.Json); setBody(it) }
    }

    private suspend fun HttpClient.pluginState(session: AdminSession): PluginRuntimeState =
        api(HttpMethod.Get, "/admin/api/plugins/$PLUGIN_ID", session).decoded()

    private suspend fun HttpClient.invokePlugin(session: AdminSession, kind: String, id: String, values: JsonObject,
        actionId: String = if (kind == "oauth-account") "probe-account" else "probe-channel", expectedVersion: String = VERSION): HttpResponse =
        api(HttpMethod.Post, "/admin/api/plugins/$PLUGIN_ID/invocations", session, invocationPayload(kind, id, values, actionId, expectedVersion))

    private fun invocationPayload(kind: String, id: String, values: JsonObject,
        actionId: String = if (kind == "oauth-account") "probe-account" else "probe-channel", expectedVersion: String = VERSION): String = buildJsonObject {
        put("actionId", actionId)
        put("context", buildJsonObject { put("kind", kind); put("id", id) })
        put("values", values)
        put("expectedActiveVersion", expectedVersion)
    }.toString()

    private fun values(model: String, protocol: GatewayProtocol, keyId: String? = null, prompt: String? = null): JsonObject = buildJsonObject {
        put("model", model)
        put("protocol", protocol.value)
        keyId?.let { put("keyId", it) }
        prompt?.let { put("prompt", it) }
    }

    private suspend fun HttpClient.legacyProbe(session: AdminSession, path: String, model: String, protocol: GatewayProtocol,
        keyId: String? = null, prompt: String? = null): HttpResponse = api(HttpMethod.Post, "/admin/api$path", session,
        JsonObject(values(model, protocol, keyId, prompt) + ("reasoningEffort" to JsonPrimitive("high"))).toString())

    private fun installationPayload(): String = """{"version":"$VERSION","approvedPermissions":["${PluginApi.CHANNEL_PROBE}","${PluginApi.OAUTH_ACCOUNT_PROBE}"]}"""

    private fun enabledPayload(): String = """{"enabled":true,"approvedPermissions":["${PluginApi.CHANNEL_PROBE}","${PluginApi.OAUTH_ACCOUNT_PROBE}"]}"""

    private fun configurationPayload(prompt: String): String = buildJsonObject {
        put("values", buildJsonObject { put("defaultPrompt", prompt) })
    }.toString()

    private suspend inline fun <reified T> HttpResponse.decoded(): T {
        val text = bodyAsText()
        assertEquals(HttpStatusCode.OK, status, text)
        return json.decodeFromString(text)
    }

    private suspend fun HttpResponse.objectPayload(): JsonObject {
        val text = bodyAsText()
        assertEquals(HttpStatusCode.OK, status, text)
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun HttpResponse.assertError(expectedStatus: HttpStatusCode, code: String) {
        val text = bodyAsText()
        assertEquals(expectedStatus, status, text)
        assertEquals(code, Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content, text)
    }

    private fun assertActive(state: PluginRuntimeState) {
        assertTrue(state.installed, "Real package bootstrap failed: ${state.status}: ${state.loadError}")
        assertTrue(state.desiredEnabled)
        assertTrue(state.admissionEnabled, "Real package cold load failed: ${state.status}: ${state.loadError}")
        assertEquals(VERSION, state.installedVersion)
        assertEquals(VERSION, state.activeVersion)
        assertEquals("active", state.status)
        assertFalse(state.requiresRestart)
        assertNull(state.loadError)
        assertNotNull(state.activeUi)
    }

    private fun assertLegacySuccess(body: JsonObject, model: String, protocol: GatewayProtocol, credentialId: String, prompt: String) {
        assertEquals(JsonPrimitive(true), body["success"])
        assertEquals(JsonPrimitive(model), body["model"])
        assertEquals(JsonPrimitive(protocol.value), body["protocol"])
        assertEquals(JsonPrimitive(credentialId), body["keyId"])
        assertEquals(JsonPrimitive(200), body["statusCode"])
        assertEquals(JsonPrimitive(prompt), body["prompt"])
        assertEquals(JsonNull, body["reasoningEffort"])
        assertEquals(JsonPrimitive("OK"), body["responseText"])
        assertEquals(JsonPrimitive(10L), body["usage"]!!.jsonObject["inputTokens"])
        assertTrue(setOf("success", "model", "protocol", "keyId", "keyName", "statusCode", "latencyMs", "prompt", "reasoningEffort", "responseText", "usage", "upstreamReportedModel").all { it in body })
        assertTrue(body.keys.all { it in LEGACY_RESULT_FIELDS }, "Compatibility adapters must return the existing final JSON shape")
    }

    private fun assertProbeBody(request: ObservedRequest, protocol: GatewayProtocol, prompt: String, maxTokens: Int = 128) {
        val body = request.body
        assertFalse("reasoning_effort" in body)
        assertFalse("reasoning" in body)
        when (protocol) {
            GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES -> {
                assertEquals(prompt, body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
                assertEquals(maxTokens, body["max_tokens"]!!.jsonPrimitive.int)
            }
            GatewayProtocol.RESPONSES -> {
                val content = body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject
                assertEquals(prompt, content["text"]!!.jsonPrimitive.content)
                assertEquals(maxTokens, body["max_output_tokens"]!!.jsonPrimitive.int)
                assertEquals(JsonPrimitive(false), body["store"])
            }
            GatewayProtocol.GEMINI -> {
                assertEquals(prompt, body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
                assertEquals(maxTokens, body["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
            }
        }
    }

    private fun assertNativeRequest(request: ObservedRequest, provider: OAuthProvider, model: String, prompt: String) {
        assertEquals("Bearer ${accessToken(provider)}", request.authorization)
        if (provider == OAuthProvider.CHATGPT) {
            assertEquals(NATIVE_RESPONSES_ENDPOINT, request.host + request.path)
            assertEquals(JsonPrimitive(model), request.body["model"])
            assertProbeBody(request, GatewayProtocol.RESPONSES, prompt)
        } else {
            assertEquals(NATIVE_GEMINI_ENDPOINT, request.host + request.path)
            assertEquals(JsonPrimitive(PROJECT), request.body["project"])
            assertEquals(JsonPrimitive(model), request.body["model"])
            val payload = request.body["request"]!!.jsonObject
            assertEquals(128, payload["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
            assertTrue(payload.toString().contains(prompt))
            assertEquals(app.currentAntigravityUserAgent(), request.headers[HttpHeaders.UserAgent])
            assertNull(request.headers["X-Goog-Api-Client"])
        }
    }

    private suspend fun HttpClient.assertGated(session: AdminSession, channelId: String, accountId: String) {
        assertTrue(api(HttpMethod.Get, "/admin/api/plugins/ui", session).decoded<List<PluginUiContribution>>().isEmpty())
        listOf("channel" to channelId, "oauth-account" to accountId).forEach { (kind, id) ->
            api(HttpMethod.Get, "/admin/api/plugins/$PLUGIN_ID/resources/model-test-targets?contextKind=$kind&contextId=$id", session)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            invokePlugin(session, kind, id, values("gpt-6-luna", GatewayProtocol.RESPONSES, if (kind == "channel") accountId else null))
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
            legacyProbe(session, if (kind == "channel") "/channels/$id/model-test" else "/oauth-accounts/$id/model-test",
                "gpt-6-luna", GatewayProtocol.RESPONSES, if (kind == "channel") accountId else null)
                .assertError(HttpStatusCode.ServiceUnavailable, "plugin_unavailable")
        }
    }

    private fun assertNoPreparation() {
        assertTrue(probes.isEmpty())
        assertTrue(preparations.isEmpty())
        assertTrue(fake.tokenRequests.isEmpty(), "Admission/version/schema gates must run before token refresh")
        assertEquals(0, scalarInt("select count(*) from channels where kind = 'provider'"), "Rejected native calls cannot provision provider endpoints")
    }

    private fun assertNoDiagnosticSettlement() {
        // Close/drain first so an accidentally queued request-log or cooldown write cannot evade the assertion.
        app.close()
        assertEquals(0, scalarInt("select count(*) from request_logs"))
        assertEquals(0, scalarInt("select count(*) from client_key_quota_usage"))
        assertEquals(0, scalarInt("select count(*) from cooldown_state"))
    }

    private fun readPackageFixture(): PackageFixture {
        val packagePath = connectivityPluginCatalogDirectory.resolve("prism-connectivity-1.0.0.prismplugin")
        ZipFile(packagePath.toFile()).use { archive ->
            assertEquals(setOf("manifest.json", "ui.json", "plugin.jar"), archive.entries().toList().map { it.name }.toSet())
            val manifest = archive.getInputStream(archive.getEntry("manifest.json")).use { json.decodeFromString<PluginManifest>(it.readBytes().toString(Charsets.UTF_8)) }
            val ui = archive.getInputStream(archive.getEntry("ui.json")).use { json.decodeFromString<PluginUiDescriptor>(it.readBytes().toString(Charsets.UTF_8)) }
            val jar = archive.getInputStream(archive.getEntry("plugin.jar")).use { it.readBytes() }
            var provider: String? = null
            JarInputStream(ByteArrayInputStream(jar)).use { entries ->
                while (true) {
                    val entry = entries.nextJarEntry ?: break
                    if (entry.name == DIAGNOSTIC_SERVICE) provider = entries.readBytes().toString(Charsets.UTF_8)
                        .lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.single()
                }
            }
            assertEquals(PLUGIN_ID, manifest.id)
            assertEquals(VERSION, manifest.version)
            return PackageFixture(manifest, ui, jar, assertNotNull(provider, "Verified package must carry its own SPI descriptor"), sha256(Files.readAllBytes(packagePath)))
        }
    }

    private fun publishChannelOnlyUpgrade(catalog: Path) {
        val previous = readPackageFixture()
        val manifest = previous.manifest.copy(version = "2.0.0", permissions = listOf(PluginApi.CHANNEL_PROBE))
        val ui = previous.ui.copy(entries = previous.ui.entries.filter { "oauth-account" !in it.contexts })
        val classes = Files.createDirectory(auxiliaryDirectory.resolve("upgrade-classes"))
        val source = auxiliaryDirectory.resolve("PendingConnectivityPlugin.java")
        Files.writeString(source, """
            package acceptance.upgrade;
            import dev.soven.prismllm.plugin.sdk.*;
            import java.util.Collections;
            public final class PendingConnectivityPlugin implements DiagnosticPlugin {
                public String getId() { return "$PLUGIN_ID"; }
                public String getVersion() { return "2.0.0"; }
                public int getApiVersion() { return 1; }
                public ProbePlan plan(DiagnosticInput input, DiagnosticContext context) {
                    return new ProbePlan("pending V2", 128, 45000L);
                }
                public DiagnosticReport report(ProbePlan plan, ProbeOutcome outcome) {
                    return new DiagnosticReport(outcome.getSuccess(), "pending V2", Collections.emptyList(), Collections.emptyList(), null);
                }
                public void close() {}
            }
        """.trimIndent())
        val diagnostics = ByteArrayOutputStream()
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, "--release", "21", "-classpath",
            System.getProperty("java.class.path"), "-d", classes.toString(), source.toString()), diagnostics.toString(Charsets.UTF_8))
        val jarBytes = ByteArrayOutputStream()
        JarOutputStream(jarBytes).use { jar ->
            mapOf(
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n\r\n".toByteArray(Charsets.UTF_8),
                "acceptance/upgrade/PendingConnectivityPlugin.class" to
                    Files.readAllBytes(classes.resolve("acceptance/upgrade/PendingConnectivityPlugin.class")),
                DIAGNOSTIC_SERVICE to "acceptance.upgrade.PendingConnectivityPlugin\n".toByteArray(Charsets.UTF_8),
            ).forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        val filename = "prism-connectivity-2.0.0.prismplugin"
        val archive = catalog.resolve(filename)
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            mapOf(
                "manifest.json" to json.encodeToString(manifest).toByteArray(Charsets.UTF_8),
                "ui.json" to json.encodeToString(ui).toByteArray(Charsets.UTF_8),
                "plugin.jar" to jarBytes.toByteArray(),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val catalogPath = catalog.resolve("catalog.json")
        val current = Json.parseToJsonElement(Files.readString(catalogPath)).jsonObject
        val replacement = buildJsonObject {
            put("id", PLUGIN_ID)
            put("version", "2.0.0")
            put("file", filename)
            put("sha256", sha256(Files.readAllBytes(archive)))
        }
        Files.writeString(catalogPath, JsonObject(current + ("packages" to
            kotlinx.serialization.json.JsonArray(current["packages"]!!.jsonArray + replacement))).toString())
    }

    /** Same JDK/JAR fixture technique as IqJudgeRegistryTest, for the independent legacy SPI only. */
    private fun writeLegacyIqJar() {
        val classes = Files.createDirectory(auxiliaryDirectory.resolve("legacy-classes"))
        val source = auxiliaryDirectory.resolve("LegacyDiskJudge.java")
        Files.writeString(source, """
            package acceptance.legacy;
            import dev.soven.prismllm.spi.iq.*;
            import java.util.Collections;
            public final class LegacyDiskJudge implements IqJudge {
                public String getId() { return "$LEGACY_JUDGE_ID"; }
                public String getDisplayName() { return "Legacy disk judge"; }
                public String getDescription() { return "Independent legacy IQ SPI"; }
                public int getApiVersion() { return 1; }
                public IqJudgeResult judge(IqJudgeRequest request) {
                    return new IqJudgeResult(IqJudgeVerdict.REVIEW, "Legacy disk judge", null, null, Collections.emptyMap());
                }
                public void close() {}
            }
        """.trimIndent())
        val diagnostics = ByteArrayOutputStream()
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler(), "The existing legacy JAR fixture requires a JDK")
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(), source.toString()), diagnostics.toString(Charsets.UTF_8))
        JarOutputStream(Files.newOutputStream(iqJudgePluginDirectory.resolve("legacy-acceptance.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("acceptance/legacy/LegacyDiskJudge.class"))
            jar.write(Files.readAllBytes(classes.resolve("acceptance/legacy/LegacyDiskJudge.class")))
            jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/services/" + IqJudge::class.java.name))
            jar.write("acceptance.legacy.LegacyDiskJudge\n".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class AdminSession(val cookie: String, val csrf: String)
    private data class Mutation(val method: HttpMethod, val path: String, val payload: String?)
    private data class ObservedRequest(val host: String, val path: String, val authorization: String?, val body: JsonObject, val headers: Headers)
    private data class PackageFixture(val manifest: PluginManifest, val ui: PluginUiDescriptor, val jar: ByteArray, val provider: String, val sha256: String)

    private companion object {
        const val PLUGIN_ID = "prism.connectivity"
        const val VERSION = "1.0.0"
        const val DEFAULT_PROMPT = "请只回复 OK"
        const val MODEL = "plugin-fixture-model"
        const val STATIC_TOKEN = "upstream-secret"
        const val PROJECT = "connectivity-acceptance-project"
        const val LEGACY_JUDGE_ID = "legacy.acceptance"
        const val DIAGNOSTIC_SERVICE = "META-INF/services/dev.soven.prismllm.plugin.sdk.DiagnosticPlugin"
        const val LOAD_ENDPOINT = "cloudcode-pa.googleapis.com/v1internal:loadCodeAssist"
        const val NATIVE_RESPONSES_ENDPOINT = "chatgpt.com/backend-api/codex/responses"
        const val NATIVE_GEMINI_ENDPOINT = "daily-cloudcode-pa.googleapis.com/v1internal:generateContent"
        val FIELD_KINDS = setOf("text", "textarea", "select", "boolean", "number", "model", "credential", "protocol", "scope")
        val LEGACY_RESULT_FIELDS = setOf("success", "model", "protocol", "keyId", "keyName", "statusCode", "latencyMs", "prompt", "reasoningEffort", "responseText", "errorMessage", "usage", "upstreamReportedModel")
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val CHAT_RESPONSE = """{"id":"mock-chat","object":"chat.completion","model":"reported-fixture-model","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""
        const val RESPONSES_RESPONSE = """{"id":"mock-response","object":"response","model":"reported-fixture-model","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}],"usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}"""
        const val MESSAGES_RESPONSE = """{"id":"mock-message","type":"message","role":"assistant","model":"reported-fixture-model","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":2}}"""
        const val GEMINI_RESPONSE = """{"modelVersion":"reported-fixture-model","candidates":[{"content":{"role":"model","parts":[{"text":"OK"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"totalTokenCount":12}}"""
    }
}
