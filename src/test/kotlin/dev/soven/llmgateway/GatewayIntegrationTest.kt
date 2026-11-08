package dev.soven.llmgateway

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.config.GatewayConfig
import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.domain.ChannelRecord
import dev.soven.llmgateway.domain.ChannelQuotaSettings
import dev.soven.llmgateway.domain.ChannelQuotaCredentialSource
import dev.soven.llmgateway.domain.ChannelKeyRecord
import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.domain.ChannelKeyRateLimitPolicy
import dev.soven.llmgateway.domain.FailureStage
import dev.soven.llmgateway.domain.GatewayProtocol
import dev.soven.llmgateway.domain.NewIqTestRun
import dev.soven.llmgateway.domain.OAuthAccountPolicy
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.domain.UpstreamRequestOverrides
import dev.soven.llmgateway.domain.UpstreamProtocolOverrides
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.domain.WebSearchSettingsRecord
import dev.soven.llmgateway.fake.FakeUpstream
import dev.soven.llmgateway.oauth.OAuthAccountDecision
import dev.soven.llmgateway.oauth.OAuthAccountDenyReason
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.ChannelKeyUpdate
import dev.soven.llmgateway.repo.KeyAutoDisableRepository
import dev.soven.llmgateway.repo.UpstreamRequestSettingsRepository
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import dev.soven.llmgateway.proxy.HeaderProfileVersionSource
import dev.soven.llmgateway.proxy.HtmlImageRenderer
import dev.soven.llmgateway.proxy.SvgRenderResult
import dev.soven.llmgateway.proxy.UpstreamHeaderCatalogService
import dev.soven.llmgateway.proxy.UpstreamHeaderProfiles
import dev.soven.llmgateway.resilience.KeyAttemptOutcome
import dev.soven.llmgateway.resilience.KeyAttemptSignal
import dev.soven.llmgateway.resilience.ChannelKeyDisableMode
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableAction
import dev.soven.llmgateway.resilience.KeyDisablePolicy
import dev.soven.llmgateway.resilience.KeyDisableRule
import dev.soven.llmgateway.resilience.ResolvedKeyDisablePolicy
import dev.soven.llmgateway.resilience.toKeyDisablePolicy
import dev.soven.llmgateway.security.ClientKeyHasher
import dev.soven.llmgateway.quota.ClientKeyQuotaLimits
import dev.soven.llmgateway.quota.ChannelKeyRateLimitDecision
import dev.soven.llmgateway.report.RetentionPolicy
import dev.soven.llmgateway.resilience.CooldownPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.options
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.readUTF8Line
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.io.IOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayIntegrationTest {
    @TempDir
    lateinit var quotaPluginDirectory: Path
    @TempDir
    lateinit var iqJudgePluginDirectory: Path
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var fake: FakeUpstream
    private lateinit var app: GatewayApplication

    private class TestVisionRenderer : HtmlImageRenderer {
        override val label: String = "测试渲染器"

        override fun render(html: String): SvgRenderResult =
            SvgRenderResult.Success(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), 512, 320)

        override fun renderFrames(html: String, times: List<Float>): List<SvgRenderResult> = times.mapIndexed { index, _ ->
            SvgRenderResult.Success(
                byteArrayOf(
                    0x89.toByte(),
                    'P'.code.toByte(),
                    'N'.code.toByte(),
                    'G'.code.toByte(),
                    index.toByte(),
                ),
                512,
                320,
            )
        }
    }

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
        fake.manxueRequests.clear()
        fake.modelRequests.clear()
        fake.cancellations.set(0)
        fake.failingModels.clear()
        fake.keyStatuses.clear()
        fake.keyErrorBodies.clear()
        fake.resetQuotaUsage()
        fake.resetOAuthToken()
        fake.clearChatHold()
        app = GatewayApplication(config(), visionRendererOverride = TestVisionRenderer())
        app.databaseFactory.migrate()
        truncateAll()
    }

    @AfterEach
    fun tearDown() {
        if (::app.isInitialized) app.close()
    }

    @Test
    fun channelKeyQuotaAccountsAreIndependentEncryptedAndPreservedByKeyEdits() = runBlocking {
        val channel = app.channelRepository.create(
            name = "multi-account-quota", baseUrl = fake.baseUrl + "/ok/v1", authType = "bearer", enabled = true,
            keys = listOf(
                NewChannelKey("账户 A", app.credentialCipher.encrypt("model-a"), models = listOf("m1")),
                NewChannelKey("账户 B", app.credentialCipher.encrypt("model-b"), models = listOf("m1")),
            ), modelMappings = mappingsOf("m1"),
            quota = ChannelQuotaSettings("new-api", credentialSource = ChannelQuotaCredentialSource.DEDICATED),
            quotaCredential = app.credentialCipher.encrypt("legacy-system"),
        )
        val first = channel.keys.first()
        val second = channel.keys.last()
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val path = "/admin/api/channels/" + channel.id
            suspend fun save(keyId: String, userId: String, extra: String = "") = client.put("$path/keys/$keyId/quota-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"providerId":"new-api","credentialSource":"DEDICATED","config":{"authMode":"system","userId":"$userId"}$extra}""")
            }
            val aResponse = save(first.id, "101", ""","credential":"account-a-system"""")
            assertEquals(HttpStatusCode.OK, aResponse.status)
            val responseText = aResponse.bodyAsText()
            assertFalse(responseText.contains("account-a-system"))
            assertFalse(responseText.contains("ciphertext"))
            assertEquals(HttpStatusCode.OK, save(second.id, "202", ""","credential":"account-b-system"""").status)
            val stored = app.channelRepository.find(channel.id)!!
            val aCredential = assertNotNull(stored.keys.first().quotaCredential)
            val bCredential = assertNotNull(stored.keys.last().quotaCredential)
            assertNotEquals("account-a-system", aCredential.ciphertext)
            assertEquals("account-a-system", app.credentialCipher.decrypt(aCredential.ciphertext, aCredential.nonce))
            assertEquals("account-b-system", app.credentialCipher.decrypt(bCredential.ciphertext, bCredential.nonce))
            assertEquals("101", stored.keys.first().quota!!.config["userId"]!!.jsonPrimitive.content)
            assertEquals("202", stored.keys.last().quota!!.config["userId"]!!.jsonPrimitive.content)
            assertEquals(channel.quotaCredential, stored.quotaCredential)
            assertEquals(channel.keys.map { it.credentialCiphertext }, stored.keys.map { it.credentialCiphertext })
            assertEquals(HttpStatusCode.OK, save(first.id, "101").status)
            assertEquals(aCredential, app.channelRepository.find(channel.id)!!.keys.first().quotaCredential)
            val update = client.put(path) {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"multi-account-quota","baseUrl":"${channel.baseUrl}","keys":[{"id":"${first.id}","name":"账户 A","models":["m1"]},{"id":"${second.id}","name":"账户 B","models":["m1"]}]}""")
            }
            assertEquals(HttpStatusCode.OK, update.status)
            assertEquals(stored.keys.map { it.quota }, app.channelRepository.find(channel.id)!!.keys.map { it.quota })
            assertEquals(aCredential, app.channelRepository.find(channel.id)!!.keys.first().quotaCredential)
            val query = client.post("$path/keys/${first.id}/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.OK, query.status)
            assertEquals("no-store", query.headers["Cache-Control"])
            val snapshot = Json.parseToJsonElement(query.bodyAsText()).jsonObject
            assertEquals("KEY", snapshot["scope"]!!.jsonPrimitive.content)
            assertEquals(first.id, snapshot["targetKeyId"]!!.jsonPrimitive.content)
            assertEquals("UNSUPPORTED", snapshot["status"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, save(first.id, "101", ""","credential":"account-a-rotated"""").status)
            val rotated = app.channelRepository.find(channel.id)!!.keys.first().quotaCredential!!
            assertEquals("account-a-rotated", app.credentialCipher.decrypt(rotated.ciphertext, rotated.nonce))
            assertEquals(HttpStatusCode.OK, save(first.id, "101", ""","clearCredential":true""").status)
            val cleared = app.channelRepository.find(channel.id)!!
            assertNull(cleared.keys.first().quotaCredential)
            assertEquals(bCredential, cleared.keys.last().quotaCredential)
            assertEquals(channel.quotaCredential, cleared.quotaCredential)
            val listing = client.get("/admin/api/channels") { header(HttpHeaders.Cookie, "gw_session=" + session.token) }
            assertFalse(listing.bodyAsText().contains("account-b-system"))
            app.channelRepository.update(channel.id, channel.name, channel.baseUrl, keys = listOf(
                ChannelKeyUpdate(id = second.id, name = second.name, models = listOf("m1")),
            ), authType = null, enabled = true)
            assertEquals(1, scalarInt("select count(*) from channel_key_quota_settings"))
            assertEquals(second.id, app.channelRepository.find(channel.id)!!.keys.single().id)
        }
    }

    @Test
    fun channelKeyQuotaUpdatesReturnFreshSettingsAndPreserveOrClearCredentials() = runBlocking {
        val channel = createChannelWithBaseUrl("quota-readback", fake.baseUrl + "/ok/v1")
        val keyId = channel.keys.single().id
        val settings = ChannelQuotaSettings("new-api")
        val initial = assertNotNull(app.channelRepository.updateKeyQuota(channel.id, keyId, settings))
        assertEquals(settings, initial.keys.single().quota)
        assertNull(initial.keys.single().quotaCredential)
        assertEquals(initial, app.channelRepository.find(channel.id))

        val credential = app.credentialCipher.encrypt("readback-system-secret")
        val dedicated = settings.copy(
            credentialSource = ChannelQuotaCredentialSource.DEDICATED,
            config = Json.parseToJsonElement("""{"authMode":"system","userId":"101"}""").jsonObject,
        )
        val saved = assertNotNull(app.channelRepository.updateKeyQuota(channel.id, keyId, dedicated, credential))
        assertEquals(dedicated, saved.keys.single().quota)
        assertEquals(credential, saved.keys.single().quotaCredential)
        assertEquals(saved, app.channelRepository.find(channel.id))

        val changed = dedicated.copy(config = Json.parseToJsonElement("""{"authMode":"system","userId":"202"}""").jsonObject)
        val preserved = assertNotNull(app.channelRepository.updateKeyQuota(channel.id, keyId, changed))
        assertEquals(changed, preserved.keys.single().quota)
        assertEquals(credential, preserved.keys.single().quotaCredential)
        assertEquals(preserved, app.channelRepository.find(channel.id))

        val cleared = assertNotNull(app.channelRepository.updateKeyQuota(channel.id, keyId, changed, clearCredential = true))
        assertEquals(changed, cleared.keys.single().quota)
        assertNull(cleared.keys.single().quotaCredential)
        assertEquals(cleared, app.channelRepository.find(channel.id))
    }

    @Test
    fun channelKeyQuotaEndpointsValidateSessionCsrfSettingsAndKeyOwnership() = runBlocking {
        val channel = createChannel("key-quota-validation", "ok")
        val other = createChannel("other-key-quota-validation", "ok")
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val path = "/admin/api/channels/${channel.id}/keys/${channel.keys.single().id}"
            assertEquals(HttpStatusCode.Unauthorized, client.post("$path/quota").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("$path/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.put("$path/quota-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status)
            for (body in listOf(
                """{"providerId":"INVALID ID"}""",
                """{"providerId":"test","config":[]}""",
                """{"providerId":"test","credential":"secret","clearCredential":true}""",
                """{"providerId":"test","credential":"bad\r\nheader"}""",
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.put("$path/quota-settings") {
                    header(HttpHeaders.Cookie, "gw_session=" + session.token)
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status)
            }
            val wrongPath = "/admin/api/channels/${channel.id}/keys/${other.keys.single().id}"
            assertEquals(HttpStatusCode.NotFound, client.put("$wrongPath/quota-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"providerId":"new-api"}""")
            }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("$wrongPath/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }.status)
            val disabled = client.post("$path/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals("DISABLED", Json.parseToJsonElement(disabled.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
            assertNull(app.channelRepository.find(channel.id)!!.keys.single().quota)
            assertNull(app.channelRepository.find(other.id)!!.keys.single().quota)
        }
    }

    @Test
    fun channelQuotaSettingsAreOptionalPersistentAndPreservedByLegacyUpdates() = runBlocking {
        val channel = createChannel("quota", "ok")
        val session = app.sessions.create("admin")
        val baseUrl = channel.baseUrl
        testApplication {
            application { app.install(this) }
            val path = "/admin/api/channels/" + channel.id
            val providers = client.get("/admin/api/quota-providers") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
            }
            assertEquals(HttpStatusCode.OK, providers.status)
            assertTrue(Json.parseToJsonElement(providers.bodyAsText()).jsonArray.isEmpty())
            val disabled = client.post(path + "/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.OK, disabled.status)
            assertEquals("no-store", disabled.headers[HttpHeaders.CacheControl])
            val disabledBody = Json.parseToJsonElement(disabled.bodyAsText()).jsonObject
            assertEquals("DISABLED", disabledBody["status"]!!.jsonPrimitive.content)
            assertEquals("CHANNEL", disabledBody["scope"]!!.jsonPrimitive.content)
            val save = client.put(path + "/quota-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"providerId":"custom-provider","config":{"baseUrl":"https://upstream.example/site"}}""")
            }
            assertEquals(HttpStatusCode.OK, save.status)
            val stored = app.channelRepository.find(channel.id)!!.quota
            assertEquals("custom-provider", stored.providerId)
            assertEquals("https://upstream.example/site", stored.config["baseUrl"]!!.jsonPrimitive.content)
            val update = client.put(path) {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"renamed","baseUrl":"$baseUrl"}""")
            }
            assertEquals(HttpStatusCode.OK, update.status)
            assertEquals(stored, app.channelRepository.find(channel.id)!!.quota)
            val missingPlugin = client.post(path + "/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.OK, missingPlugin.status)
            val text = missingPlugin.bodyAsText()
            assertEquals("UNSUPPORTED", Json.parseToJsonElement(text).jsonObject["status"]!!.jsonPrimitive.content)
            assertFalse(text.contains("upstream-secret"))
            assertEquals(channel.keys.map { it.state }, app.channelRepository.find(channel.id)!!.keys.map { it.state })
        }
    }

    @Test
    fun channelQuotaEndpointsRequireAdminCsrfAndValidConfiguration() = runBlocking {
        val channel = createChannel("quota-validation", "ok")
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val path = "/admin/api/channels/" + channel.id
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/quota-providers").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post(path + "/quota").status)
            assertEquals(HttpStatusCode.Forbidden, client.post(path + "/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.put(path + "/quota-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.status)
            val bodies = listOf("""{"providerId":"INVALID ID"}""",
                """{"providerId":"test","config":[]}""",
                """{"providerId":"test","credentialSource":"UNKNOWN"}""",
                """{"providerId":"test","credential":"secret","clearCredential":true}""",
                """{"providerId":"test","credential":"bad\r\nheader"}""",
                "{\"providerId\":\"test\",\"credential\":\"" + "s".repeat(4097) + "\"}",
                "{\"providerId\":\"test\",\"config\":{\"large\":\"" + "界".repeat(3000) + "\"}}")
            for (body in bodies) {
                val invalid = client.put(path + "/quota-settings") {
                    header(HttpHeaders.Cookie, "gw_session=" + session.token)
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
            }
            assertEquals("", app.channelRepository.find(channel.id)!!.quota.providerId)
            assertEquals(HttpStatusCode.NotFound, client.post("/admin/api/channels/missing/quota") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }.status)
        }
    }

    @Test
    fun channelQuotaDedicatedCredentialsAreEncryptedWriteOnlyAndExplicitlyCleared() = runBlocking {
        val channel = createChannel("quota-system", "ok")
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val path = "/admin/api/channels/" + channel.id
            suspend fun save(extra: String) = client.put(path + "/quota-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"providerId":"new-api","credentialSource":"DEDICATED","config":{"authMode":"system","userId":"123"}$extra}""")
            }
            val response = save(""","credential":"system-one-secret"""")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertFalse(text.contains("system-one-secret"))
            assertFalse(text.contains("ciphertext"))
            val returned = Json.parseToJsonElement(text).jsonObject["quota"]!!.jsonObject
            assertTrue(returned["credentialSet"]!!.jsonPrimitive.boolean)
            assertEquals("DEDICATED", returned["credentialSource"]!!.jsonPrimitive.content)
            val first = app.channelRepository.find(channel.id)!!
            val encrypted = assertNotNull(first.quotaCredential)
            assertNotEquals("system-one-secret", encrypted.ciphertext)
            assertEquals("system-one-secret", app.credentialCipher.decrypt(encrypted.ciphertext, encrypted.nonce))
            assertFalse(first.quota.toString().contains("system-one-secret"))
            assertEquals(channel.keys, first.keys)
            assertEquals(HttpStatusCode.OK, save(""","credential":""""").status)
            assertEquals(encrypted, app.channelRepository.find(channel.id)!!.quotaCredential)
            val legacyUpdate = client.put(path) {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"quota-system-renamed","baseUrl":"${channel.baseUrl}"}""")
            }
            assertEquals(HttpStatusCode.OK, legacyUpdate.status)
            assertEquals(encrypted, app.channelRepository.find(channel.id)!!.quotaCredential)
            val listing = client.get("/admin/api/channels") { header(HttpHeaders.Cookie, "gw_session=" + session.token) }
            assertFalse(listing.bodyAsText().contains("system-one-secret"))
            assertEquals(HttpStatusCode.OK, save(""","credential":"system-two-secret"""").status)
            val replaced = assertNotNull(app.channelRepository.find(channel.id)!!.quotaCredential)
            assertNotEquals(encrypted, replaced)
            assertEquals("system-two-secret", app.credentialCipher.decrypt(replaced.ciphertext, replaced.nonce))
            val cleared = save(""","clearCredential":true""")
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertNull(app.channelRepository.find(channel.id)!!.quotaCredential)
            assertFalse(Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["quota"]!!.jsonObject["credentialSet"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun channelQuotaDedicatedCredentialsCanBeSetDuringChannelCreationAndUpdate() = runBlocking {
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val created = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"quota-created","baseUrl":"https://upstream.example/v1","credential":"model-secret","quota":{"providerId":"new-api","credentialSource":"DEDICATED","config":{"authMode":"system","userId":123},"credential":"system-secret"}}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val body = created.bodyAsText()
            assertFalse(body.contains("system-secret"))
            assertFalse(body.contains("model-secret"))
            val id = Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content
            val record = app.channelRepository.find(id)!!
            val system = assertNotNull(record.quotaCredential)
            assertEquals("system-secret", app.credentialCipher.decrypt(system.ciphertext, system.nonce))
            val key = record.keys.single()
            assertEquals("model-secret", app.credentialCipher.decrypt(key.credentialCiphertext, key.credentialNonce))
            val updated = client.put("/admin/api/channels/$id") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"quota-created","baseUrl":"https://upstream.example/v1","quota":{"providerId":"new-api","credentialSource":"DEDICATED","config":{"authMode":"system","userId":123},"credential":"updated-system-secret"}}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val after = app.channelRepository.find(id)!!
            assertEquals(record.keys, after.keys)
            val replaced = assertNotNull(after.quotaCredential)
            assertEquals("updated-system-secret", app.credentialCipher.decrypt(replaced.ciphertext, replaced.nonce))
            assertFalse(updated.bodyAsText().contains("updated-system-secret"))
        }
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
    fun `gemini 400 with INVALID_ARGUMENT does not trigger failover even if message has upstream error`() = runBlocking {
        val primary = createChannel("gemini-invalid-primary", "gemini-400-invalid-argument")
        val secondary = createChannel("gemini-invalid-secondary", "ok")
        exposeAsModel("gemini-invalid-chat", primary to "m1", secondary to "m2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gemini-invalid-chat","stream":false,"messages":[]}""")
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

    @Test
    fun `subscription account window skips the candidate without calling upstream`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "oauth-window",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "unused-static-key",
                    credential = app.credentialCipher.encrypt("unused-static-secret"),
                    models = emptyList(),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val account = createOAuthAccount(channel, "window@example.com")
        app.oauthAccountRepository.saveRefreshed(
            accountId = account.id,
            accessToken = app.credentialCipher.encrypt("oauth-access-token"),
            expiresAt = System.currentTimeMillis() + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
        )
        app.oauthAccountRepository.updatePolicy(
            account.id,
            OAuthAccountPolicy(windowLimit = 1, windowSeconds = 3_600, maxConcurrentRequests = 1),
        )
        exposeAsModel("oauth-window-model", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send() = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"oauth-window-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send().status)
            val denied = send()
            assertEquals(HttpStatusCode.TooManyRequests, denied.status)
            assertTrue(denied.bodyAsText().contains("subscription_account_rate_limited"))
            assertTrue(denied.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it >= 1 } == true)
            awaitRequestLogCount(2)
        }

        assertEquals(1, fake.requests.size)
        assertEquals("Bearer oauth-access-token", fake.requests.single().authorization)
    }

    @Test
    fun `subscription account requiring reauthorization never sends the refresh token upstream`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "oauth-reauth",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "unused-static-key",
                    credential = app.credentialCipher.encrypt("unused-static-secret"),
                    models = emptyList(),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val account = createOAuthAccount(channel, "reauth@example.com")
        app.oauthAccountRepository.recordFailure(account.id, "invalid_grant", terminal = true)
        exposeAsModel("oauth-reauth-model", channel to "m1")
        val clientKey = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $clientKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"oauth-reauth-model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(response.bodyAsText().contains("subscription_account_unavailable"))
            awaitRequestLogCount(1)
        }

        assertEquals(0, fake.requests.size)
    }

    @Test
    fun `admin model listing resolves the subscription access token`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "models@example.com")
        app.oauthAccountRepository.saveRefreshed(
            accountId = account.id,
            accessToken = app.credentialCipher.encrypt("oauth-models-token"),
            expiresAt = System.currentTimeMillis() + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels/${channel.id}/models?keyId=${account.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals("Bearer oauth-models-token", fake.modelRequests.single().authorization)
    }

    @Test
    fun `admin oauth import binds the account and lists it on the dashboard`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val expiresAt = System.currentTimeMillis() + 3_600_000

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"provider":"chatgpt","refreshToken":"refresh-token-value","accountEmail":"free@example.com",""" +
                        """"accountPlan":"free","proxyUrl":"http://127.0.0.1:8080","accessToken":"access-token-value",""" +
                        """"accessTokenExpiresAt":$expiresAt,"channelIds":["${channel.id}"]}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("refresh-token-value"))
            assertFalse(body.contains("access-token-value"))
            val account = Json.parseToJsonElement(body).jsonObject
            val accountId = account["id"]!!.jsonPrimitive.content
            assertTrue(accountId.isNotBlank())
            // 订阅账号只服务官方端点：请求里的 channelIds 被忽略，改挂系统置备渠道。
            val providerChannelId = assertNotNull(app.channelRepository.findProviderChannel(OAuthProvider.CHATGPT)).id
            assertEquals(providerChannelId, account["channelIds"]!!.jsonArray.single().jsonPrimitive.content)
            assertTrue(account["channelIds"]!!.jsonArray.none { it.jsonPrimitive.content == channel.id })
            assertEquals("chatgpt", account["provider"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", account["authState"]!!.jsonPrimitive.content)
            assertEquals("free@example.com", account["accountEmail"]!!.jsonPrimitive.content)
            assertEquals(expiresAt, account["accessTokenExpiresAt"]!!.jsonPrimitive.long)
            assertEquals("http://127.0.0.1:8080", account["proxyUrl"]!!.jsonPrimitive.content)
            val policy = account["policy"]!!.jsonObject
            assertEquals(10_800, policy["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(1, policy["maxConcurrentRequests"]!!.jsonPrimitive.int)
            assertEquals(0, account["runtime"]!!.jsonObject["windowUsed"]!!.jsonPrimitive.int)

            val listing = client.get("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, listing.status)
            assertEquals("no-store", listing.headers["Cache-Control"])
            assertFalse(listing.bodyAsText().contains("refresh-token-value"))
            val entries = Json.parseToJsonElement(listing.bodyAsText()).jsonArray
            assertTrue(entries.any { it.jsonObject["id"]!!.jsonPrimitive.content == accountId })

            val auditResponse = client.get("/admin/api/audit-logs?limit=20") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, auditResponse.status)
            val importAudit = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .single { it["action"]!!.jsonPrimitive.content == "oauth_account.import" }
            assertEquals("chatgpt", importAudit["details"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
            assertEquals(
                "http://127.0.0.1:8080",
                importAudit["details"]!!.jsonObject["proxyUrl"]!!.jsonPrimitive.content,
            )

            val providers = client.get("/admin/api/oauth-providers") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, providers.status)
            val byProvider = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["configured"]!!.jsonPrimitive.boolean }
            assertEquals(mapOf("chatgpt" to true, "antigravity" to false), byProvider)
            val managed = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate {
                    it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["managedChannel"]!!.jsonPrimitive.boolean
                }
            assertEquals(mapOf("chatgpt" to true, "antigravity" to false), managed)

            val stored = assertNotNull(app.oauthAccountRepository.find(accountId))
            assertNotEquals("refresh-token-value", stored.refreshTokenCiphertext)
            assertEquals(
                "refresh-token-value",
                app.credentialCipher.decrypt(stored.refreshTokenCiphertext, stored.refreshTokenNonce),
            )
            assertEquals(10_800, stored.policy.windowSeconds)
            assertEquals("free@example.com", stored.accountEmail)
        }
    }

    @Test
    fun `admin oauth import rejects unconfigured providers and invalid input`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun import(body: String) = client.post("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val unconfigured = import("""{"provider":"antigravity","refreshToken":"rt"}""")
            assertEquals(HttpStatusCode.BadRequest, unconfigured.status)
            assertTrue(unconfigured.bodyAsText().contains("oauth_provider_not_configured"))

            val unknownProvider = import("""{"provider":"gemini","refreshToken":"rt"}""")
            assertEquals(HttpStatusCode.BadRequest, unknownProvider.status)
            assertTrue(unknownProvider.bodyAsText().contains("invalid_oauth_provider"))

            val blankToken = import("""{"provider":"chatgpt","refreshToken":"   "}""")
            assertEquals(HttpStatusCode.BadRequest, blankToken.status)
            assertTrue(blankToken.bodyAsText().contains("invalid_oauth_refresh_token"))

            val proxy = import(
                """{"provider":"chatgpt","refreshToken":"rt","proxyUrl":"socks5://127.0.0.1:1080"}""",
            )
            assertEquals(HttpStatusCode.BadRequest, proxy.status)
            assertTrue(proxy.bodyAsText().contains("invalid_oauth_proxy"))

            // 远程代理主机默认不放行，避免管理面输入被用来访问任意内网主机。
            val remoteProxy = import(
                """{"provider":"chatgpt","refreshToken":"rt","proxyUrl":"http://proxy.example.com:8080"}""",
            )
            assertEquals(HttpStatusCode.BadRequest, remoteProxy.status)
            assertTrue(remoteProxy.bodyAsText().contains("OAUTH_PROXY_ALLOWED_HOSTS"))

            val missingExpiry = import("""{"provider":"chatgpt","refreshToken":"rt","accessToken":"at"}""")
            assertEquals(HttpStatusCode.BadRequest, missingExpiry.status)
            assertTrue(missingExpiry.bodyAsText().contains("invalid_oauth_access_token"))

            val expiredAccess = import(
                """{"provider":"chatgpt","refreshToken":"rt","accessToken":"at","accessTokenExpiresAt":${System.currentTimeMillis() - 1}}""",
            )
            assertEquals(HttpStatusCode.BadRequest, expiredAccess.status)
            assertTrue(expiredAccess.bodyAsText().contains("invalid_oauth_access_token"))

            val missingChannel = import(
                """{"provider":"chatgpt","refreshToken":"rt","channelIds":["missing"]}""",
            )
            assertEquals(HttpStatusCode.BadRequest, missingChannel.status)
            assertTrue(missingChannel.bodyAsText().contains("invalid_oauth_channels"))

            assertEquals(
                0,
                scalarInt("select count(*) from oauth_account_channels where channel_id = '${channel.id}'"),
            )
        }
    }

    @Test
    fun `admin oauth provider models rejects invalid body before provisioning`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val before = scalarInt("select count(*) from channels where kind = 'provider'")
            val response = client.put("/admin/api/oauth-providers/chatgpt/models") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"models":123}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                before,
                scalarInt("select count(*) from channels where kind = 'provider'"),
                "请求体非法时不应置备系统渠道",
            )
        }
    }

    @Test
    fun `admin oauth import rejects the same account email on another key`() = runBlocking {
        val first = createChannel("openai-a", "ok")
        val second = createChannel("openai-b", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun import(channel: ChannelRecord, refreshToken: String) =
                client.post("/admin/api/oauth-accounts") {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"provider":"chatgpt","refreshToken":"$refreshToken","accountEmail":"same@example.com","channelIds":["${channel.id}"]}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, import(first, "rt-a").status)
            val duplicated = import(second, "rt-b")
            assertEquals(HttpStatusCode.Conflict, duplicated.status)
            assertTrue(duplicated.bodyAsText().contains("oauth_account_email_exists"))

            // 冲突时整个事务回滚，不会留下第二个账号或渠道关联。
            assertEquals(1, scalarInt("select count(*) from oauth_accounts where account_email = 'same@example.com'"))
            assertEquals(
                1,
                scalarInt(
                    "select count(*) from oauth_account_channels c join oauth_accounts a on a.id = c.account_id " +
                        "where a.account_email = 'same@example.com'",
                ),
            )
        }
    }

    @Test
    fun `admin oauth policy update validates and persists limits`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "policy@example.com")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun update(keyId: String, body: String) = client.put("/admin/api/oauth-accounts/$keyId/policy") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = update(account.id, """{"windowLimit":10,"windowSeconds":3600,"maxConcurrentRequests":2}""")
            assertEquals(HttpStatusCode.OK, ok.status)
            val policy = Json.parseToJsonElement(ok.bodyAsText()).jsonObject["policy"]!!.jsonObject
            assertEquals(10, policy["windowLimit"]!!.jsonPrimitive.int)
            assertEquals(3_600, policy["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(2, policy["maxConcurrentRequests"]!!.jsonPrimitive.int)

            val invalid = update(account.id, """{"windowLimit":0}""")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("invalid_oauth_policy"))

            val missing = update("missing", """{"windowLimit":5}""")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("oauth_account_not_found"))

            val stored = assertNotNull(app.oauthAccountRepository.find(account.id)).policy
            assertEquals(10, stored.windowLimit)
            assertEquals(3_600, stored.windowSeconds)
            assertEquals(2, stored.maxConcurrentRequests)
        }
    }

    @Test
    fun `admin oauth cooldown clear releases account and key cooldown`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "cooldown@example.com")
        app.oauthAccountScheduler.recordRateLimited(account, 600_000)
        val permit = assertNotNull(app.candidateCooldown.forceAcquire(channel.id, account.id, "m1"))
        app.candidateCooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)
        assertNotNull(app.oauthAccountScheduler.runtimeState(account.id, account.policy)?.cooldownUntil)
        assertNotNull(app.candidateCooldown.cooldownState(channel.id, account.id, "m1"))

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/oauth-accounts/${account.id}/cooldown/clear") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NoContent, response.status)

            val missing = client.post("/admin/api/oauth-accounts/missing/cooldown/clear") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)

            assertNull(app.oauthAccountScheduler.runtimeState(account.id, account.policy)!!.cooldownUntil)
            assertNull(app.candidateCooldown.cooldownState(channel.id, account.id, "m1"))
        }
    }

    @Test
    fun `admin oauth manual refresh reports missing accounts and refresh failures`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun refresh(keyId: String) = client.post("/admin/api/oauth-accounts/$keyId/refresh") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }

            val notOAuth = refresh("missing")
            assertEquals(HttpStatusCode.NotFound, notOAuth.status)
            assertTrue(notOAuth.bodyAsText().contains("oauth_account_not_found"))

            // 代理指向本机未监听端口，刷新请求快速失败，避免测试访问真实 OAuth 端点。
            val account = app.oauthAccountRepository.create(
                NewOAuthAccount(
                    name = "refresh@example.com",
                    provider = OAuthProvider.CHATGPT,
                    accountEmail = "refresh@example.com",
                    refreshToken = app.credentialCipher.encrypt("oauth-refresh-token"),
                    proxyUrl = "http://127.0.0.1:1",
                    channelIds = listOf(channel.id),
                ),
            )
            val failed = refresh(account.id)
            assertEquals(HttpStatusCode.BadGateway, failed.status)
            assertTrue(failed.bodyAsText().contains("oauth_refresh_failed"))
            assertEquals("network_error", app.oauthAccountRepository.find(account.id)!!.lastRefreshError)
        }
    }

    @Test
    fun `admin oauth quota refresh reads upstream usage and persists the snapshot`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "quota@example.com")
        app.oauthAccountRepository.updateChatgptAccountId(account.id, "acc-quota")
        app.oauthAccountRepository.saveRefreshed(
            account.id,
            accessToken = app.credentialCipher.encrypt("quota-access-token"),
            expiresAt = System.currentTimeMillis() + 3_600_000,
            tokenType = "Bearer",
            scope = null,
            rotatedRefreshToken = null,
            expectedRefreshCiphertext = account.refreshTokenCiphertext,
        )

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun refreshQuota(keyId: String) = client.post("/admin/api/oauth-accounts/$keyId/quota/refresh") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }

            val missing = refreshQuota("missing")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("oauth_account_not_found"))

            val response = refreshQuota(account.id)
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("plus", body["accountPlan"]!!.jsonPrimitive.content)
            val quota = body["quota"]!!.jsonObject
            assertEquals(100.0, quota["primary"]!!.jsonObject["usedPercent"]!!.jsonPrimitive.double)
            assertEquals(18_000, quota["primary"]!!.jsonObject["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(42.5, quota["secondary"]!!.jsonObject["usedPercent"]!!.jsonPrimitive.double)
            assertFalse(quota["allowed"]!!.jsonPrimitive.boolean)
            assertEquals(1, quota["resetCredits"]!!.jsonPrimitive.int)
            // 上游只给了 reset_after_seconds，接口返回换算后的绝对恢复时间。
            val blockedUntil = assertNotNull(body["runtime"]!!.jsonObject["quotaBlockedUntil"]?.jsonPrimitive?.long)
            assertTrue(blockedUntil > System.currentTimeMillis())

            val upstreamRequest = fake.quotaRequests.single()
            assertEquals("Bearer quota-access-token", upstreamRequest.authorization)
            assertEquals("acc-quota", upstreamRequest.requestHeaders["chatgpt-account-id"]?.single())
            assertEquals("codex_cli_rs", upstreamRequest.requestHeaders["originator"]?.single())

            val auditResponse = client.get("/admin/api/audit-logs?limit=20") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, auditResponse.status)
            val quotaAudits = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .filter { it["action"]!!.jsonPrimitive.content == "oauth_account.quota.refresh" }
            // 成功与 404 失败各一条，失败记录保留错误码便于追溯。
            assertEquals(2, quotaAudits.size)
            val successAudit = quotaAudits.first {
                it["details"]!!.jsonObject["result"]!!.jsonPrimitive.content == "success"
            }
            assertEquals(account.id, successAudit["details"]!!.jsonObject["accountId"]!!.jsonPrimitive.content)

            val stored = assertNotNull(app.oauthAccountRepository.find(account.id))
            assertEquals(100.0, assertNotNull(stored.quota).primary!!.usedPercent)
            assertEquals("acc-quota", stored.chatgptAccountId)
            // 额度用尽立即参与准入拦截，不必等下一次 429。
            val decision = app.oauthAccountScheduler.acquire(account)
            assertIs<OAuthAccountDecision.Denied>(decision)
            assertEquals(OAuthAccountDenyReason.QUOTA, decision.reason)

            // 上游读取失败时保留上一次成功读数，只记录错误码。
            fake.quotaUsageStatus = 503
            app.oauthQuotaService.refreshNow(account.id)
            val degraded = assertNotNull(app.oauthAccountRepository.find(account.id))
            assertEquals("http_503", assertNotNull(degraded.quota).error)
            assertEquals(100.0, assertNotNull(degraded.quota!!.primary).usedPercent)
        }
    }

    @Test
    fun `admin oauth interactive login exchanges the callback and binds the account`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun start(body: String) = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val started = start(
                """{"provider":"chatgpt","name":"login account","channelIds":["${channel.id}"]}""",
            )
            assertEquals(HttpStatusCode.OK, started.status)
            assertEquals("no-store", started.headers["Cache-Control"])
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val authorizeUrl = startedBody["authorizeUrl"]!!.jsonPrimitive.content
            assertTrue(authorizeUrl.startsWith("https://auth.openai.com/oauth/authorize?"))
            assertTrue(authorizeUrl.contains("code_challenge_method=S256"))
            assertEquals("http://localhost:1455/auth/callback", startedBody["redirectUri"]!!.jsonPrimitive.content)
            val state = authorizeUrl.substringAfter("state=").substringBefore("&")

            suspend fun complete(callback: String, id: String = loginId) = client.post("/admin/api/oauth-login/$id/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"$callback"}""")
            }

            // state 不匹配时拒绝，且不消耗会话、不调用 token 端点。
            val wrongState = complete("http://localhost:1455/auth/callback?code=auth-code&state=wrong")
            assertEquals(HttpStatusCode.BadRequest, wrongState.status)
            assertTrue(wrongState.bodyAsText().contains("oauth_login_invalid_callback"))
            assertEquals(0, fake.tokenRequests.size)

            val completed = complete("http://localhost:1455/auth/callback?code=auth-code&state=$state")
            assertEquals(HttpStatusCode.OK, completed.status)
            val account = Json.parseToJsonElement(completed.bodyAsText()).jsonObject
            val accountId = account["id"]!!.jsonPrimitive.content
            assertEquals("login@example.com", account["accountEmail"]!!.jsonPrimitive.content)
            assertEquals("plus", account["accountPlan"]!!.jsonPrimitive.content)
            assertEquals("acc-login", account["chatgptAccountId"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", account["authState"]!!.jsonPrimitive.content)

            val tokenRequest = fake.tokenRequests.single()
            assertTrue(tokenRequest.body.contains("grant_type=authorization_code"))
            assertTrue(tokenRequest.body.contains("code=auth-code"))
            assertTrue(tokenRequest.body.contains("code_verifier="))
            assertFalse(tokenRequest.body.contains("state="))
            assertEquals("codex_cli_rs", tokenRequest.requestHeaders["originator"]?.single())

            // 会话单次使用：重复提交同一个回调只会得到过期，不会再次换取 token。
            val replay = complete("http://localhost:1455/auth/callback?code=auth-code&state=$state")
            assertEquals(HttpStatusCode.BadRequest, replay.status)
            assertTrue(replay.bodyAsText().contains("oauth_login_expired"))
            assertEquals(1, fake.tokenRequests.size)

            // refresh token 与 access token 都加密落库，响应不回显。
            val storedAccount = assertNotNull(app.oauthAccountRepository.find(accountId))
            assertEquals(
                "login-refresh-token",
                app.credentialCipher.decrypt(storedAccount.refreshTokenCiphertext, storedAccount.refreshTokenNonce),
            )
            assertEquals(
                "login-access-token",
                app.credentialCipher.decrypt(
                    assertNotNull(storedAccount.accessTokenCiphertext),
                    assertNotNull(storedAccount.accessTokenNonce),
                ),
            )

            val auditResponse = client.get("/admin/api/audit-logs?limit=20") {
                header(HttpHeaders.Cookie, cookie)
            }
            val loginAudits = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .filter { it["action"]!!.jsonPrimitive.content == "oauth_account.login" }
            // state 不匹配、成功、会话重放各一条。
            assertEquals(3, loginAudits.size)
            assertTrue(
                loginAudits.any {
                    it["details"]?.jsonObject?.get("result")?.jsonPrimitive?.content == "success"
                },
            )
        }
    }

    @Test
    fun `admin oauth interactive login rejects an account deleted during login`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "stale@example.com")

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val started = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"chatgpt","accountId":"${account.id}"}""")
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val state = startedBody["authorizeUrl"]!!.jsonPrimitive.content
                .substringAfter("state=").substringBefore("&")

            // 登录期间目标被删除：完成回调必须拒绝绑定，且不消耗授权码。
            app.oauthAccountRepository.delete(account.id)
            val completed = client.post("/admin/api/oauth-login/$loginId/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"http://localhost:1455/auth/callback?code=auth-code&state=$state"}""")
            }
            assertEquals(HttpStatusCode.Conflict, completed.status)
            assertTrue(completed.bodyAsText().contains("oauth_login_stale"))
            assertEquals(0, fake.tokenRequests.size)
        }
    }

    @Test
    fun `admin oauth interactive login retries binding after an email conflict`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val conflicting = createChannel("openai-conflict", "ok")
        app.oauthAccountRepository.listAll()
            .filter { it.accountEmail == "login@example.com" }
            .forEach { app.oauthAccountRepository.delete(it.id) }
        val conflictingAccount = createOAuthAccount(
            conflicting,
            "login@example.com",
            refreshToken = "conflict-refresh-token",
        )

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val started = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"chatgpt","channelIds":["${channel.id}"]}""")
            }
            assertEquals(HttpStatusCode.OK, started.status)
            val startedBody = Json.parseToJsonElement(started.bodyAsText()).jsonObject
            val loginId = startedBody["loginId"]!!.jsonPrimitive.content
            val state = startedBody["authorizeUrl"]!!.jsonPrimitive.content
                .substringAfter("state=").substringBefore("&")
            suspend fun complete() = client.post("/admin/api/oauth-login/$loginId/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"http://localhost:1455/auth/callback?code=auth-code&state=$state"}""")
            }

            val conflicted = complete()
            assertEquals(HttpStatusCode.Conflict, conflicted.status)
            assertTrue(conflicted.bodyAsText().contains("oauth_account_email_exists"))
            assertEquals(1, fake.tokenRequests.size)

            // 解除原绑定后用同一登录会话重试：不再交换授权码，直接完成绑定。
            app.oauthAccountRepository.delete(conflictingAccount.id)
            val retried = complete()
            assertEquals(HttpStatusCode.OK, retried.status)
            val account = Json.parseToJsonElement(retried.bodyAsText()).jsonObject
            assertEquals("login@example.com", account["accountEmail"]!!.jsonPrimitive.content)
            assertEquals(1, fake.tokenRequests.size)
        }
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
            awaitCancellation()
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
    fun `higher priority key wins when canonical id maps to a different upstream model name`() = runBlocking {
        app.channelRepository.create(
            name = "canonical-priority",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "welfare",
                    credential = app.credentialCipher.encrypt("key-welfare"),
                    priority = 10,
                    models = listOf("deepseek-v4.1-flash"),
                ),
                NewChannelKey(
                    name = "guomo",
                    credential = app.credentialCipher.encrypt("key-guomo"),
                    priority = 100,
                    models = listOf("deepseek-flash"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = listOf(
                ChannelModelRecord(upstreamModel = "deepseek-v4.1-flash", canonicalModelId = "deepseek-flash"),
                ChannelModelRecord(upstreamModel = "deepseek-flash"),
            ),
            priority = 1,
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"deepseek-flash","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(listOf("Bearer key-welfare"), fake.requests.map { it.authorization })

            val log = awaitRequestLog()
            assertEquals("welfare", log.channelKeyName)
            assertEquals("deepseek-v4.1-flash", log.upstreamModel)
            assertEquals(1, log.upstreamAttempts)
        }
    }

    @Test
    fun keyAutoDisableDefaultsFailOverAndPersistAudit() = runBlocking {
        app.close()
        app = GatewayApplication(config().let { it.copy(cooldown = it.cooldown.copy(authFailureMs = 1)) })
        val channel = createAutoDisablePool()
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            repeat(3) { index ->
                fake.keyStatuses["Bearer key-primary"] = if (index == 2) 403 else 401
                assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key, requestId = "auto-auth-$index").status)
                val current = assertNotNull(app.channelRepository.find(channel.id))
                assertEquals(index < 2, current.keys.first { it.name == "primary" }.state == ChannelKeyState.ACTIVE)
                assertTrue(current.keys.first { it.name == "backup" }.state == ChannelKeyState.ACTIVE)
                delay(5)
            }
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key).status)
            assertEquals(3, fake.requests.count { it.authorization == "Bearer key-primary" })
            assertEquals(4, fake.requests.count { it.authorization == "Bearer key-backup" })
            awaitRequestLogCount(4)
            assertEquals(listOf(1, 2, 2, 2), app.requestLogRepository.list(10).mapNotNull { it.upstreamAttempts }.sorted())
            val audits = app.adminAuditRepository.list(100).filter { it.action == "channel_key.auto_disable" }
            val audit = audits.single()
            assertEquals("system", audit.actor)
            assertEquals("auto-auth-2", audit.requestId)
            assertEquals("403", audit.details?.get("httpStatus")?.jsonPrimitive?.content)
            assertEquals("3", audit.details?.get("count")?.jsonPrimitive?.content)
            assertEquals("auth_rejected", audit.details?.get("ruleId")?.jsonPrimitive?.content)
            assertEquals("TEMPORARY", audit.details?.get("action")?.jsonPrimitive?.content)
            assertEquals("AUTO_TEMPORARY", audit.details?.get("state")?.jsonPrimitive?.content)
            assertNotNull(audit.details?.get("disableUntil")?.jsonPrimitive?.content)
            val text = audit.details.toString()
            assertFalse(text.contains("key-primary"))
            assertFalse(text.contains("private-test-prompt"))
        }
        app = GatewayApplication(config())
        val restored = assertNotNull(app.channelRepository.find(channel.id)).keys.first { it.name == "primary" }
        assertEquals(ChannelKeyState.AUTO_TEMPORARY, restored.state)
        assertNotNull(restored.autoDisabledAt)
        assertNotNull(restored.autoDisableUntil)
        assertEquals("鉴权被拒 3 次", restored.autoDisableReason)
    }

    @Test
    fun keyAutoDisableMatchesUpstreamErrorBodyBeforeStatusFallback() = runBlocking {
        val channel = app.channelRepository.create(
            name = "auto-disable-body",
            baseUrl = "${fake.baseUrl}/key-route/v1",
            keys = listOf(NewChannelKey("primary", app.credentialCipher.encrypt("key-primary"), models = listOf("m1"))),
            authType = "bearer",
            protocols = GatewayProtocol.entries.toList(),
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            // key-route 的 401 错误体带 invalid_api_key：首条永久规则命中，而不是兜底的临时规则。
            val firstResponse = client.sendAutoDisableRequest(key)
            assertEquals(HttpStatusCode.BadGateway, firstResponse.status)
            val firstBody = firstResponse.bodyAsText()
            assertFalse(firstBody.contains("invalid key"), firstBody)
            assertFalse(firstBody.contains("authentication_error"), firstBody)
            assertEquals(
                ChannelKeyState.ACTIVE,
                assertNotNull(app.channelRepository.find(channel.id)).keys.single().state,
            )
            assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status)
            val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_PERMANENT, disabled.state)
            assertNull(disabled.autoDisableUntil)
            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
            assertEquals("credential_invalid", audit.details?.get("ruleId")?.jsonPrimitive?.content)
            assertEquals("PERMANENT", audit.details?.get("action")?.jsonPrimitive?.content)
            assertEquals("AUTO_PERMANENT", audit.details?.get("state")?.jsonPrimitive?.content)
            assertEquals("2", audit.details?.get("count")?.jsonPrimitive?.content)
            assertEquals(
                "invalid[\\s_-]*(x-)?api[\\s_-]?key",
                audit.details?.get("matchedPattern")?.jsonPrimitive?.content,
            )
            assertNull(audit.details?.get("disableUntil"))
            assertFalse(audit.details.toString().contains("key-primary"))
        }
    }

    @Test
    fun keyAutoDisablePermanentlyDisablesInsufficientBalanceOn402() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        val key = createClientKey()
        val requestId = "insufficient-balance-402"
        fake.keyStatuses["Bearer key-primary"] = 402
        fake.keyErrorBodies["Bearer key-primary"] =
            """{"error":{"type":"authentication_error","code":"INSUFFICIENT_BALANCE","message":"Insufficient account balance"}}"""
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key, requestId = requestId).status)

            val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_PERMANENT, disabled.state)
            assertNull(disabled.autoDisableUntil)
            assertEquals(1, fake.requests.size)

            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
            assertEquals(requestId, audit.requestId)
            assertEquals("402", audit.details?.get("httpStatus")?.jsonPrimitive?.content)
            assertEquals("insufficient_balance", audit.details?.get("ruleId")?.jsonPrimitive?.content)
            assertEquals("PERMANENT", audit.details?.get("action")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableDefaultUpstreamUnstableRuleUsesTemporaryState() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        val key = createClientKey()
        fake.keyStatuses["Bearer key-primary"] = 500
        testApplication {
            application { app.install(this) }
            repeat(8) { assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status) }
            val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_TEMPORARY, disabled.state)
            assertNotNull(disabled.autoDisableUntil)
            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
            assertEquals("upstream_unstable", audit.details?.get("ruleId")?.jsonPrimitive?.content)
            assertEquals("TEMPORARY", audit.details?.get("action")?.jsonPrimitive?.content)
            assertEquals("AUTO_TEMPORARY", audit.details?.get("state")?.jsonPrimitive?.content)
            assertEquals("8", audit.details?.get("count")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableTemporaryRecoveryClearsKeyCooldown() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        app.keyAutoDisableRepository.saveSettings(
            KeyDisablePolicy(
                rules = listOf(
                    KeyDisableRule(
                        id = "temp_auth",
                        name = "临时鉴权失败",
                        statusCodes = listOf(401, 403),
                        threshold = 1,
                        action = KeyDisableAction.TEMPORARY,
                        disableMinutes = 1,
                    ),
                ),
            ),
        )
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        val key = createClientKey()
        fake.keyStatuses["Bearer key-primary"] = 401
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status)
            val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_TEMPORARY, disabled.state)
            assertTrue(app.candidateCooldown.isBlocked(channel.id, primary.id, "m1"))

            // 只把密钥停用到期时间拨到过去，认证冷却仍在有效期内。
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("update channel_keys set auto_disable_until = ? where id = ?").use { statement ->
                    statement.setLong(1, System.currentTimeMillis() - 1)
                    statement.setString(2, primary.id)
                    statement.executeUpdate()
                }
            }
            fake.keyStatuses["Bearer key-primary"] = 200
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key).status)
            val recovered = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.ACTIVE, recovered.state)
            assertNull(recovered.autoDisableUntil)
            assertNull(recovered.probeUntil)
            assertFalse(app.candidateCooldown.isBlocked(channel.id, primary.id, "m1"))
            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_recover" }
            assertEquals("probe_success", audit.details?.get("reason")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableExpiredTemporaryFailureRearmsDisableWindow() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        app.keyAutoDisableRepository.saveSettings(
            KeyDisablePolicy(
                rules = listOf(
                    KeyDisableRule(
                        id = "temp_auth",
                        name = "临时鉴权失败",
                        statusCodes = listOf(401),
                        threshold = 1,
                        action = KeyDisableAction.TEMPORARY,
                        disableMinutes = 1,
                    ),
                ),
            ),
        )
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        val key = createClientKey()
        fake.keyStatuses["Bearer key-primary"] = 401
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status)
            val firstUntil = assertNotNull(
                assertNotNull(app.channelRepository.find(channel.id)).keys.single().autoDisableUntil,
            )

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("update channel_keys set auto_disable_until = ? where id = ?").use { statement ->
                    statement.setLong(1, System.currentTimeMillis() - 1)
                    statement.setString(2, primary.id)
                    statement.executeUpdate()
                }
            }
            assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status)
            val rearmed = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_TEMPORARY, rearmed.state)
            val secondUntil = assertNotNull(rearmed.autoDisableUntil)
            assertTrue(secondUntil > firstUntil)
            assertNull(rearmed.probeUntil)
            assertEquals("临时鉴权失败 探测失败", rearmed.autoDisableReason)
        }
    }

    @Test
    fun keyAutoDisableProbeLeaseAllowsOnlyOneClaim() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        val now = System.currentTimeMillis()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "update channel_keys set state = 'AUTO_TEMPORARY', auto_disabled_at = ?, auto_disable_until = ? where id = ?",
            ).use { statement ->
                statement.setLong(1, now - 2)
                statement.setLong(2, now - 1)
                statement.setString(3, primary.id)
                statement.executeUpdate()
            }
        }
        val expired = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
        assertTrue(app.keyAutoDisableRepository.isAvailable(expired))
        assertTrue(app.keyAutoDisableRepository.claimProbe(expired))
        assertFalse(app.keyAutoDisableRepository.claimProbe(expired))
        val claimed = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
        assertFalse(app.keyAutoDisableRepository.isAvailable(claimed))
        assertTrue(
            app.keyAutoDisableRepository.isAvailable(
                claimed,
                now = assertNotNull(claimed.probeUntil),
            ),
        )
    }

    @Test
    fun keyAutoDisableExpiredProbeAllowsOnlyOneConcurrentRequest() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        app.keyAutoDisableRepository.saveSettings(
            KeyDisablePolicy(
                rules = listOf(
                    KeyDisableRule(
                        id = "temp_auth",
                        name = "临时鉴权失败",
                        statusCodes = listOf(401),
                        threshold = 1,
                        action = KeyDisableAction.TEMPORARY,
                        disableMinutes = 1,
                    ),
                ),
            ),
        )
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        val key = createClientKey()
        fake.keyStatuses["Bearer key-primary"] = 401
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("update channel_keys set auto_disable_until = ? where id = ?").use { statement ->
                    statement.setLong(1, System.currentTimeMillis() - 1)
                    statement.setString(2, primary.id)
                    statement.executeUpdate()
                }
            }
            fake.keyStatuses["Bearer key-primary"] = 200
            val release = fake.holdChatRequests()
            val before = fake.requests.size
            val probe = async { client.sendAutoDisableRequest(key) }
            try {
                awaitCondition("probe request reaches upstream") { fake.requests.size == before + 1 }
                val concurrent = client.sendAutoDisableRequest(key)
                assertNotEquals(HttpStatusCode.OK, concurrent.status)
                assertEquals(before + 1, fake.requests.size, "探测租约期间不得再打上游")

                release.complete(Unit)
                assertEquals(HttpStatusCode.OK, probe.await().status)
            } finally {
                release.complete(Unit)
                probe.cancel()
                fake.clearChatHold()
            }
            awaitCondition("probe success recovers the key") {
                app.channelRepository.find(channel.id)?.keys?.single()?.state == ChannelKeyState.ACTIVE
            }
            val recovered = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertNull(recovered.probeUntil)
        }
    }

    @Test
    fun keyAutoDisableCleanupRecoversOnlyExpiredUnprobedKeys() = runBlocking {
        val channel = app.channelRepository.create(
            name = "cleanup-keys",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey("expired", app.credentialCipher.encrypt("k-expired"), models = listOf("m1")),
                NewChannelKey("future", app.credentialCipher.encrypt("k-future"), models = listOf("m1")),
                NewChannelKey(
                    "manual",
                    app.credentialCipher.encrypt("k-manual"),
                    state = ChannelKeyState.MANUAL_DISABLED,
                    models = listOf("m1"),
                ),
                NewChannelKey(
                    "permanent",
                    app.credentialCipher.encrypt("k-permanent"),
                    state = ChannelKeyState.AUTO_PERMANENT,
                    models = listOf("m1"),
                ),
                NewChannelKey("probing", app.credentialCipher.encrypt("k-probing"), models = listOf("m1")),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val now = System.currentTimeMillis()
        val keys = channel.keys.associateBy { it.name }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "update channel_keys set state = 'AUTO_TEMPORARY', auto_disabled_at = ?, auto_disable_until = ?, probe_until = ? where id = ?",
            ).use { statement ->
                fun expire(key: ChannelKeyRecord, until: Long, probeUntil: Long?) {
                    statement.setLong(1, now - 2)
                    statement.setLong(2, until)
                    if (probeUntil == null) statement.setNull(3, java.sql.Types.BIGINT) else statement.setLong(3, probeUntil)
                    statement.setString(4, key.id)
                    statement.executeUpdate()
                }
                expire(keys.getValue("expired"), now - 1, null)
                expire(keys.getValue("future"), now + 600_000, null)
                expire(keys.getValue("probing"), now - 1, now + 60_000)
            }
            connection.prepareStatement(
                "insert into channel_key_health (key_id, policy_revision, auth_failure_streak, recent_failures, rule_counters) values (?, 0, 0, '[]', '{}')",
            ).use { statement ->
                statement.setString(1, keys.getValue("expired").id)
                statement.executeUpdate()
            }
        }

        val recovered = app.keyAutoDisableRepository.recoverExpired(now)
        assertEquals(listOf(keys.getValue("expired").id), recovered.map { it.keyId })
        val after = assertNotNull(app.channelRepository.find(channel.id)).keys.associateBy { it.name }
        assertEquals(ChannelKeyState.ACTIVE, after.getValue("expired").state)
        assertNull(after.getValue("expired").autoDisabledAt)
        assertNull(after.getValue("expired").autoDisableUntil)
        assertNull(after.getValue("expired").probeUntil)
        assertEquals(ChannelKeyState.AUTO_TEMPORARY, after.getValue("future").state)
        assertEquals(ChannelKeyState.AUTO_TEMPORARY, after.getValue("probing").state)
        assertEquals(ChannelKeyState.MANUAL_DISABLED, after.getValue("manual").state)
        assertEquals(ChannelKeyState.AUTO_PERMANENT, after.getValue("permanent").state)
        val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_recover" }
        assertEquals(keys.getValue("expired").id, audit.details?.get("keyId")?.jsonPrimitive?.content)
        assertEquals("expired", audit.details?.get("reason")?.jsonPrimitive?.content)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("select count(*) from channel_key_health where key_id = ?").use { statement ->
                statement.setString(1, keys.getValue("expired").id)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun keyAutoDisableCleanupJobRecoversExpiredKeyAndClearsCooldown() = runBlocking {
        app.close()
        app = GatewayApplication(config(keyAutoRecoveryIntervalMs = 1_000))
        testApplication {
            application { app.install(this) }
            // 触发测试宿主启动，后台任务才会随 install 一起启动。
            assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            val channel = createAutoDisablePool(withBackup = false)
            val primary = channel.keys.single()
            val now = System.currentTimeMillis()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement(
                    "update channel_keys set state = 'AUTO_TEMPORARY', auto_disabled_at = ?, auto_disable_until = ? where id = ?",
                ).use { statement ->
                    statement.setLong(1, now - 2)
                    statement.setLong(2, now - 1)
                    statement.setString(3, primary.id)
                    statement.executeUpdate()
                }
            }
            val permit = assertNotNull(app.candidateCooldown.acquire(channel.id, primary.id, "m1"))
            app.candidateCooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)
            assertTrue(app.candidateCooldown.isBlocked(channel.id, primary.id, "m1"))

            awaitCondition("cleanup job recovers the expired key", timeoutMs = 5_000) {
                app.channelRepository.find(channel.id)?.keys?.single()?.state == ChannelKeyState.ACTIVE
            }
            assertFalse(app.candidateCooldown.isBlocked(channel.id, primary.id, "m1"))
            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_recover" }
            assertEquals("expired", audit.details?.get("reason")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableWindowKeepsFailuresAcrossSuccessAndStopsFailOpen() = runBlocking {
        app.keyAutoDisableRepository.saveSettings(
            KeyAutoDisablePolicy(authFailureThreshold = 100, failureThreshold = 5),
        )
        val channel = createAutoDisablePool(withBackup = false)
        val key = createClientKey()
        fake.keyStatuses["Bearer key-primary"] = 500
        testApplication {
            application { app.install(this) }
            repeat(2) { assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status) }
            fake.keyStatuses["Bearer key-primary"] = 200
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key).status)
            fake.keyStatuses["Bearer key-primary"] = 500
            repeat(3) { assertEquals(HttpStatusCode.BadGateway, client.sendAutoDisableRequest(key).status) }
            val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_PERMANENT, disabled.state)
            assertEquals("时间窗口内多次失败 5 次", disabled.autoDisableReason)
            // 唯一密钥被永久停用后模型仍然配置着，只是没有可用凭据：用 503 提醒客户端重试，而不是 404。
            val unavailable = client.sendAutoDisableRequest(key)
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            assertTrue(unavailable.bodyAsText().contains("model_credentials_unavailable"))
            assertEquals(6, fake.requests.size)
            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
            assertEquals("legacy_failure_window", audit.details?.get("ruleId")?.jsonPrimitive?.content)
            assertEquals("5", audit.details?.get("count")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `model with only disabled keys reports unavailable instead of not found`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "all-keys-disabled",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "manual",
                    credential = app.credentialCipher.encrypt("key-manual"),
                    state = ChannelKeyState.MANUAL_DISABLED,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        val key = createClientKey()
        testApplication {
            application { app.install(this) }

            val unavailable = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            val body = unavailable.bodyAsText()
            assertTrue(body.contains("model_credentials_unavailable"), "BODY=[$body]")
            // 人工停用不会自动恢复，不应给出 Retry-After 暗示客户端等待。
            assertNull(unavailable.headers[HttpHeaders.RetryAfter])
            assertEquals(0, fake.requests.size)

            // 恢复密钥后同一请求立即可用，证明 503 只反映凭据状态。
            app.channelRepository.update(
                id = channel.id,
                name = channel.name,
                baseUrl = channel.baseUrl,
                keys = channel.keys.map { existing ->
                    ChannelKeyUpdate(
                        id = existing.id,
                        name = existing.name,
                        state = ChannelKeyState.ACTIVE,
                        priority = existing.priority,
                        models = existing.models,
                        stateVersion = existing.stateVersion,
                    )
                },
                authType = channel.authType,
                protocols = channel.protocols,
                enabled = true,
                modelMappings = channel.modelMappings,
            )
            val restored = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, restored.status)
        }
    }

    @Test
    fun `temporarily disabled key model reports retry after`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "temporary-only",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "temporary",
                    credential = app.credentialCipher.encrypt("key-temporary"),
                    state = ChannelKeyState.AUTO_TEMPORARY,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        val until = System.currentTimeMillis() + 120_000
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_keys set auto_disable_until = ? where id = ?").use { statement ->
                statement.setLong(1, until)
                statement.setString(2, channel.keys.single().id)
                statement.executeUpdate()
            }
        }
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("model_credentials_unavailable"))
            // 临时停用会自动恢复，Retry-After 应指向到期时间而不是固定值。
            val retryAfter = assertNotNull(response.headers[HttpHeaders.RetryAfter])
            assertTrue(retryAfter.toLong() in 1..180, "RETRY-AFTER=[$retryAfter]")
        }
    }

    @Test
    fun `temporary key blocked by probe lease reports the later recovery time`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "probe-lease-only",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "temporary",
                    credential = app.credentialCipher.encrypt("key-temporary"),
                    state = ChannelKeyState.AUTO_TEMPORARY,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        // 停用已到期，但探测租约仍在生效：密钥此刻不可路由，恢复时间取较晚的租约到期时间。
        val expired = System.currentTimeMillis() - 60_000
        val leaseUntil = System.currentTimeMillis() + 90_000
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_keys set auto_disable_until = ?, probe_until = ? where id = ?")
                .use { statement ->
                    statement.setLong(1, expired)
                    statement.setLong(2, leaseUntil)
                    statement.setString(3, channel.keys.single().id)
                    statement.executeUpdate()
                }
        }
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("model_credentials_unavailable"))
            // 只看停用到期时间会算出 -60 秒并被丢弃，从而错误地不给出重试提示。
            val retryAfter = assertNotNull(response.headers[HttpHeaders.RetryAfter])
            assertTrue(retryAfter.toLong() in 60..120, "RETRY-AFTER=[$retryAfter]")
        }
    }

    @Test
    fun `admin channel payload exposes probe lease so console matches backend routing`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "probe-lease-visible",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "temporary",
                    credential = app.credentialCipher.encrypt("key-lease"),
                    state = ChannelKeyState.AUTO_TEMPORARY,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        val expired = System.currentTimeMillis() - 60_000
        val leaseUntil = System.currentTimeMillis() + 90_000
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_keys set auto_disable_until = ?, probe_until = ? where id = ?")
                .use { statement ->
                    statement.setLong(1, expired)
                    statement.setLong(2, leaseUntil)
                    statement.setString(3, channel.keys.single().id)
                    statement.executeUpdate()
                }
        }
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.get("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val listed = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                .first { it.jsonObject["id"]!!.jsonPrimitive.content == channel.id }
            val key = listed.jsonObject["keys"]!!.jsonArray.single().jsonObject
            // 管理台要靠 probeUntil 复现后端 isRoutableAt：只看停用到期会把已被租约占用的密钥误判为可用。
            assertEquals(leaseUntil, key["probeUntil"]!!.jsonPrimitive.long)
            assertEquals(expired, key["autoDisableUntil"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun `canonical mapping keeps raw upstream name not found when credentials are unavailable`() = runBlocking {
        app.channelRepository.create(
            name = "canonical-disabled",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "manual",
                    credential = app.credentialCipher.encrypt("key-manual"),
                    state = ChannelKeyState.MANUAL_DISABLED,
                    models = listOf("Qwen3.8-Flash"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = listOf(ChannelModelRecord("Qwen3.8-Flash", "qwen3.8-flash", "manual")),
        )
        val key = createClientKey()
        testApplication {
            application { app.install(this) }

            // 对外只暴露 canonical ID：凭据全部停用时它是 503，而不是「模型不存在」。
            val canonical = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"qwen3.8-flash","messages":[]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, canonical.status)
            assertTrue(canonical.bodyAsText().contains("model_credentials_unavailable"))

            // 上游名从未对外暴露，凭据是否可用都不该改变这一点，仍按 404 处理。
            val rawUpstream = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"Qwen3.8-Flash","messages":[]}""")
            }
            assertEquals(HttpStatusCode.NotFound, rawUpstream.status)
            assertTrue(rawUpstream.bodyAsText().contains("model_not_found"))
        }
    }

    @Test
    fun `recovered temporary key routes instead of reporting model missing`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "recovery-race",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "temporary",
                    credential = app.credentialCipher.encrypt("key-race"),
                    state = ChannelKeyState.AUTO_TEMPORARY,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        // 停用已到期且没有探测租约：密钥可路由，正常路由不会进入诊断分支。
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_keys set auto_disable_until = ? where id = ?").use { statement ->
                statement.setLong(1, System.currentTimeMillis() - 60_000)
                statement.setString(2, channel.keys.single().id)
                statement.executeUpdate()
            }
        }
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            // 恢复后的模型必须能正常路由，不能被误报成 404 或 503。
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    @Test
    fun keyAutoDisableSkipsAlreadyDisabledKeyInPreloadedModelCandidates() = runBlocking {
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(authFailureThreshold = 1))
        val channel = app.channelRepository.create(
            name = "auto-disable-shared", baseUrl = "${fake.baseUrl}/key-health/v1", authType = "bearer", enabled = true,
            keys = listOf(
                NewChannelKey("primary", app.credentialCipher.encrypt("key-primary"), models = listOf("m1", "m2")),
                NewChannelKey("backup", app.credentialCipher.encrypt("key-backup"), priority = 2, models = listOf("m2")),
            ),
            modelMappings = listOf(ChannelModelRecord("m1", "shared"), ChannelModelRecord("m2", "shared")),
        )
        fake.keyStatuses["Bearer key-primary"] = 401
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key, model = "shared").status)
            assertEquals(listOf("Bearer key-primary", "Bearer key-backup"), fake.requests.map { it.authorization })
            assertEquals(2, awaitRequestLog().upstreamAttempts)
            assertFalse(assertNotNull(app.channelRepository.find(channel.id)).keys.first { it.name == "primary" }.state == ChannelKeyState.ACTIVE)
        }
    }

    @Test
    fun keyAutoDisableIsSharedAcrossProtocolsAndIgnoresRequestErrors() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        app.keyAutoDisableRepository.saveSettings(
            KeyAutoDisablePolicy(authFailureThreshold = 3, failureThreshold = 100),
        )
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            // 请求侧错误既不计数也不打断连续鉴权失败。
            for (neutral in listOf(400, 404, 422)) {
                fake.keyStatuses["Bearer key-primary"] = neutral
                client.sendAutoDisableRequest(key).bodyAsText()
                assertTrue(assertNotNull(app.channelRepository.find(channel.id)).keys.single().state == ChannelKeyState.ACTIVE)
            }
            for ((index, path) in listOf("/v1/chat/completions", "/v1/responses", "/v1/messages").withIndex()) {
                fake.keyStatuses["Bearer key-primary"] = if (index == 1) 403 else 401
                client.sendAutoDisableRequest(key, path = path).bodyAsText()
            }
            assertFalse(assertNotNull(app.channelRepository.find(channel.id)).keys.single().state == ChannelKeyState.ACTIVE)
            val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
            assertEquals("legacy_auth_streak", audit.details?.get("ruleId")?.jsonPrimitive?.content)
            assertEquals("3", audit.details?.get("count")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableSwitchAndPolicyChangesStartNewCounts() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(enabled = false))
        repeat(8) { app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "off-$it", 401) }
        assertTrue(assertNotNull(app.channelRepository.find(channel.id)).keys.single().state == ChannelKeyState.ACTIVE)
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(failureThreshold = 100))
        repeat(2) { app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "before-$it", 401) }
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(authFailureThreshold = 2, failureThreshold = 100))
        assertNull(app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "after-1", 403))
        assertTrue(assertNotNull(app.channelRepository.find(channel.id)).keys.single().state == ChannelKeyState.ACTIVE)
        assertNotNull(app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "after-2", 401))
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(enabled = false))
        assertFalse(assertNotNull(app.channelRepository.find(channel.id)).keys.single().state == ChannelKeyState.ACTIVE)
    }

    @Test
    fun keyAutoDisableCountsSurviveRestartAndWindowExpires() = runBlocking {
        app.keyAutoDisableRepository.saveSettings(
            KeyAutoDisablePolicy(authFailureThreshold = 3, failureThreshold = 5),
        )
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        var now = 0L
        var repository = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository) { now }
        repeat(2) { assertNull(repository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "auth-$it", 401)) }
        repository = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository) { now }
        assertNotNull(repository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "auth-3", 403))
        val restored = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
        assertEquals(ChannelKeyState.AUTO_PERMANENT, restored.state)
        val reset = assertNotNull(app.channelRepository.update(
            id = channel.id, name = channel.name, baseUrl = channel.baseUrl, authType = channel.authType,
            enabled = true, keys = listOf(ChannelKeyUpdate(
                id = restored.id, name = restored.name, credential = app.credentialCipher.encrypt("repaired-key"),
                state = ChannelKeyState.ACTIVE, models = restored.models, stateVersion = restored.stateVersion,
            )),
        )).keys.single()
        assertEquals(ChannelKeyState.ACTIVE, reset.state)
        assertNull(reset.autoDisabledAt)
        repeat(4) { assertNull(repository.recordOutcome(reset, KeyAttemptOutcome.FAILURE, "window-$it", 500)) }
        now = 300_000
        repository = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository) { now }
        assertNull(repository.recordOutcome(reset, KeyAttemptOutcome.FAILURE, "expired", 429))
        repeat(3) { assertNull(repository.recordOutcome(reset, KeyAttemptOutcome.FAILURE, "new-$it", 429)) }
        assertNotNull(repository.recordOutcome(reset, KeyAttemptOutcome.FAILURE, "new-4", 500))
        assertEquals(2, app.adminAuditRepository.list(100).count { it.action == "channel_key.auto_disable" })
    }

    @Test
    fun keyAutoDisableConcurrentFailuresCreateOneAuditAndIgnoreOldCredentials() = runBlocking {
        val channel = createAutoDisablePool()
        val primary = channel.keys.first { it.name == "primary" }
        val outcomes = coroutineScope {
            (1..20).map { async(Dispatchers.IO) {
                app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.FAILURE, "parallel-$it", 500)
            } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it != null })
        val current = assertNotNull(app.channelRepository.find(channel.id))
        assertFalse(current.keys.first { it.name == "primary" }.state == ChannelKeyState.ACTIVE)
        assertTrue(current.keys.first { it.name == "backup" }.state == ChannelKeyState.ACTIVE)
        assertEquals(1, app.adminAuditRepository.list(100).count { it.action == "channel_key.auto_disable" })
        val updated = assertNotNull(app.channelRepository.update(
            id = channel.id, name = channel.name, baseUrl = channel.baseUrl, authType = channel.authType, enabled = true,
            keys = current.keys.map { ChannelKeyUpdate(
                id = it.id, name = it.name, credential = if (it.id == primary.id) app.credentialCipher.encrypt("repaired-key") else null,
                state = ChannelKeyState.ACTIVE, priority = it.priority, models = it.models, stateVersion = it.stateVersion,
            ) },
        )).keys.first { it.name == "primary" }
        assertFalse(app.keyAutoDisableRepository.isAvailable(primary))
        assertTrue(app.keyAutoDisableRepository.isAvailable(updated))
        repeat(5) { assertNull(app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "late-$it", 401)) }
        repeat(2) { assertNull(app.keyAutoDisableRepository.recordOutcome(updated, KeyAttemptOutcome.AUTH_FAILURE, "new-$it", 403)) }
        assertTrue(assertNotNull(app.channelRepository.find(channel.id)).keys.first { it.name == "primary" }.state == ChannelKeyState.ACTIVE)
    }

    @Test
    fun keyAutoDisableAuditFailureRollsBackDisableAndCounters() = runBlocking {
        val primary = createAutoDisablePool(withBackup = false).keys.single()
        repeat(2) { app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "before-$it", 401) }
        fun constraint(sql: String) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }
        constraint("alter table admin_audit_logs add constraint test_auto_disable_audit_failure check (action <> 'channel_key.auto_disable')")
        try {
            assertFailsWith<Exception> {
                app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "audit-failure", 403)
            }
            assertTrue(assertNotNull(app.channelRepository.find(primary.channelId)).keys.single().state == ChannelKeyState.ACTIVE)
            assertTrue(app.adminAuditRepository.list(100).none { it.action == "channel_key.auto_disable" })
        } finally {
            constraint("alter table admin_audit_logs drop constraint test_auto_disable_audit_failure")
        }
        assertNotNull(app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "retry", 403))
        val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
        assertEquals("3", audit.details?.get("count")?.jsonPrimitive?.content)
    }

    @Test
    fun keyAutoDisableIgnoresNonStreamClientWriteFailures() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        val key = createClientKey()
        val failNextWrite = AtomicBoolean(false)
        testApplication {
            application {
                app.install(this)
                install(createApplicationPlugin("test-json-client-disconnect") {
                    on(ResponseBodyReadyForSend) { _, _ ->
                        if (failNextWrite.compareAndSet(true, false)) {
                            throw ChannelWriteException("test client disconnect", IOException("test"))
                        }
                    }
                })
            }
            repeat(5) {
                failNextWrite.set(true)
                val response = client.sendAutoDisableRequest(key)
                assertEquals(499, response.status.value)
                assertTrue(assertNotNull(app.channelRepository.find(channel.id)).keys.single().state == ChannelKeyState.ACTIVE)
            }
            assertEquals(5, fake.requests.size)
            assertTrue(app.adminAuditRepository.list(100).none { it.action == "channel_key.auto_disable" })
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key).status)
        }
    }

    @Test
    fun keyAutoDisableRecoversCorruptRuleCounters() = runBlocking {
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.first()
        app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "corrupt-1", 401)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_key_health set rule_counters = ? where key_id = ?").use { statement ->
                statement.setString(1, "{not-json")
                statement.setString(2, primary.id)
                statement.executeUpdate()
            }
        }
        repeat(3) { index ->
            app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "corrupt-${index + 2}", 401)
        }
        assertFalse(assertNotNull(app.channelRepository.find(channel.id)).keys.first { it.id == primary.id }.state == ChannelKeyState.ACTIVE)
        val audit = app.adminAuditRepository.list(100).single { it.action == "channel_key.auto_disable" }
        assertEquals("auth_rejected", audit.details?.get("ruleId")?.jsonPrimitive?.content)
        assertEquals("3", audit.details?.get("count")?.jsonPrimitive?.content)
    }

    @Test
    fun keyAutoDisableSettingsConcurrentFirstSaveDoesNotRace() = runBlocking {
        val settings = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository)
        val revisions = coroutineScope {
            (4..15).map { index -> async(Dispatchers.IO) {
                settings.saveSettings(KeyAutoDisablePolicy(authFailureThreshold = index)).after.revision
            } }.awaitAll()
        }
        assertEquals((1L..12L).toSet(), revisions.toSet())
        assertEquals(12L, settings.loadSettings().revision)
    }

    @Test
    fun keyAutoDisableSettingsMigratesLegacyDefaultsWithoutOverwritingCustomRules() = runBlocking {
        val settings = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository)
        val initial = settings.saveSettings(KeyDisablePolicy(enabled = false)).after
        fun overwriteRules(rules: List<KeyDisableRule>) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("update key_auto_disable_settings set rules = ? where id = 1").use { statement ->
                    statement.setString(1, Json.encodeToString(rules))
                    statement.executeUpdate()
                }
            }
        }

        overwriteRules(KeyDisablePolicy.LEGACY_DEFAULT_RULES)
        val migrated = settings.loadSettings()
        assertFalse(migrated.policy.enabled)
        assertEquals(KeyDisablePolicy.DEFAULT_RULES, migrated.policy.rules)
        assertEquals(initial.revision + 1, migrated.revision)
        val persistedRules = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("select rules from key_auto_disable_settings where id = 1").use { statement ->
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    Json.decodeFromString<List<KeyDisableRule>>(result.getString(1))
                }
            }
        }
        assertEquals(KeyDisablePolicy.DEFAULT_RULES, persistedRules)

        val customRules = KeyDisablePolicy.LEGACY_DEFAULT_RULES + KeyDisableRule(
            id = "custom_balance",
            name = "自定义余额规则",
            statusCodes = listOf(402),
            bodyPatterns = listOf("ACCOUNT_CREDIT_LOW"),
            threshold = 2,
            action = KeyDisableAction.PERMANENT,
        )
        overwriteRules(customRules)
        val custom = settings.loadSettings()
        assertEquals(customRules, custom.policy.rules)
        assertEquals(migrated.revision, custom.revision)
    }

    @Test
    fun keyAutoDisableSettingsTruncatesOversizedStoredRules() = runBlocking {
        val settings = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository)
        settings.saveSettings(KeyDisablePolicy(enabled = false))
        // 正常保存路径写不出超过 50 条的列表；这里模拟人工改库或手工改过的备份。
        // 条数要超过 MAX_EFFECTIVE_RULES（构造器上限，100）才能证明问题真的被截断挡住：
        // 只写 70 条时构造器本来就接受，测不出「以前会 500」这条旧行为。
        // 读设置不能因此 500，也不能让策略解析在整个渠道上失败。
        val oversized = (1..KeyDisablePolicy.MAX_EFFECTIVE_RULES + 20).map { index ->
            KeyDisableRule(
                id = "bulk_$index",
                name = "批量规则 $index",
                statusCodes = listOf(401),
                threshold = 1,
                action = KeyDisableAction.PERMANENT,
            )
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update key_auto_disable_settings set rules = ? where id = 1").use { statement ->
                statement.setString(1, Json.encodeToString(oversized))
                statement.executeUpdate()
            }
        }

        val loaded = settings.loadSettings()
        // 规则是「首条命中即止」，截断保留前 MAX_RULES 条，丢掉的都是优先级最低的兜底规则。
        assertEquals(KeyDisablePolicy.MAX_RULES, loaded.policy.rules.size)
        assertEquals(oversized.take(KeyDisablePolicy.MAX_RULES), loaded.policy.rules)

        // 渠道解析同样不抛：继承全局时直接用截断后的全局规则。
        val channel = createChannelWithBaseUrl(name = "oversized-global", baseUrl = "${fake.baseUrl}/ok/v1")
        val resolved = settings.resolvePolicy(channel.id)
        assertEquals(KeyDisablePolicy.MAX_RULES, resolved.policy.rules.size)
        assertEquals("bulk_1", resolved.policy.rules.first().id)

        // 管理接口读的是同一份数据：以前规则数超过 MAX_EFFECTIVE_RULES 时读取会 500，
        // 现在照常返回截断后的列表。
        testApplication {
            application { app.install(this) }
            val (cookie, _) = client.loginAdmin()
            val settingsResponse = client.get("/admin/api/key-auto-disable-settings") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, settingsResponse.status, settingsResponse.bodyAsText())
            val policyResponse = client.get("/admin/api/channels/${channel.id}/key-disable-policy") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, policyResponse.status, policyResponse.bodyAsText())
            assertEquals(
                KeyDisablePolicy.MAX_RULES,
                Json.parseToJsonElement(policyResponse.bodyAsText()).jsonObject["effective"]
                    ?.jsonObject?.get("rules")?.jsonArray?.size,
            )
        }
    }

    @Test
    fun channelKeyDisablePolicyOverridesGlobalForItsOwnChannelOnly() = runBlocking {
        val grox = createChannelWithBaseUrl(name = "grox-like", baseUrl = "${fake.baseUrl}/key-health/v1")
        val other = createChannelWithBaseUrl(name = "other", baseUrl = "${fake.baseUrl}/ok/v1")
        val groxKey = grox.keys.single()
        val otherKey = other.keys.single()
        // 活动规则：命中即永久停用，用来验证渠道覆盖只对本渠道生效。
        val activityRule = KeyDisableRule(
            id = "activity_disabled",
            name = "活动已关闭或Key失效",
            statusCodes = listOf(403),
            bodyPatterns = listOf("DEEPSEEK_ACTIVITY_DISABLED"),
            threshold = 1,
            action = KeyDisableAction.PERMANENT,
        )
        val repository = app.keyAutoDisableRepository

        // 全局没有这条规则时，两个渠道都不该停用。
        val activity = KeyAttemptSignal.UpstreamResponse(403, """{"code":"DEEPSEEK_ACTIVITY_DISABLED"}""")
        assertNull(repository.recordOutcome(groxKey, activity, "before-override"))
        assertNull(repository.recordOutcome(otherKey, activity, "before-override-other"))
        assertEquals(ChannelKeyState.ACTIVE, assertNotNull(app.channelRepository.find(grox.id)).keys.single().state)

        val change = repository.saveChannelPolicy(grox.id, KeyDisablePolicy(rules = listOf(activityRule)))
        assertEquals(ResolvedKeyDisablePolicy.channelScope(grox.id), change.after.scope)
        assertEquals(ResolvedKeyDisablePolicy.GLOBAL_SCOPE, repository.resolvePolicy(other.id).scope)
        assertEquals(KeyDisablePolicy(rules = listOf(activityRule)), repository.loadChannelOverride(grox.id))
        assertNull(repository.loadChannelOverride(other.id))
        // 有效策略 = 渠道规则在前 + 全局规则兜底。
        assertEquals(
            listOf("activity_disabled") + repository.loadSettings().policy.rules.map { it.id },
            change.after.policy.rules.map { it.id },
        )

        // 只有配置了覆盖的渠道会命中；另一渠道继续走全局规则，不误伤。
        assertEquals("activity_disabled", repository.recordOutcome(groxKey, activity, "override-hit"))
        assertEquals(
            ChannelKeyState.AUTO_PERMANENT,
            assertNotNull(app.channelRepository.find(grox.id)).keys.single().state,
        )
        assertNull(repository.recordOutcome(otherKey, activity, "global-miss"))
        assertEquals(ChannelKeyState.ACTIVE, assertNotNull(app.channelRepository.find(other.id)).keys.single().state)

        // 渠道规则未命中时回落全局规则：全局的 insufficient_balance 仍会停用该渠道的密钥。
        val fallback = createChannelWithBaseUrl(name = "fallback", baseUrl = "${fake.baseUrl}/ok/v1")
        val fallbackKey = fallback.keys.single()
        repository.saveChannelPolicy(fallback.id, KeyDisablePolicy(rules = listOf(activityRule)))
        val balance = KeyAttemptSignal.UpstreamResponse(402, "INSUFFICIENT_BALANCE")
        assertEquals("insufficient_balance", repository.recordOutcome(fallbackKey, balance, "fallback-hit"))
        assertEquals(
            ChannelKeyState.AUTO_PERMANENT,
            assertNotNull(app.channelRepository.find(fallback.id)).keys.single().state,
        )

        // 渠道策略变更让该渠道已累计的计数失效，但不影响全局作用域下的计数。
        val temporaryRule = activityRule.copy(action = KeyDisableAction.TEMPORARY, disableMinutes = 30, threshold = 2)
        val temporary = createChannelWithBaseUrl(name = "temporary", baseUrl = "${fake.baseUrl}/ok/v1")
        val temporaryKey = temporary.keys.single()
        repository.saveChannelPolicy(temporary.id, KeyDisablePolicy(rules = listOf(temporaryRule)))
        val signal = KeyAttemptSignal.UpstreamResponse(403, "DEEPSEEK_ACTIVITY_DISABLED")
        assertNull(repository.recordOutcome(temporaryKey, signal, "count-1"))
        repository.saveChannelPolicy(
            temporary.id,
            KeyDisablePolicy(rules = listOf(temporaryRule.copy(name = "活动规则收紧"))),
        )
        // 计数随指纹失效，一次失败不足以触发，说明旧计数没有被复用。
        assertNull(repository.recordOutcome(temporaryKey, signal, "count-after-change"))
        assertEquals(ChannelKeyState.ACTIVE, assertNotNull(app.channelRepository.find(temporary.id)).keys.single().state)

        // 恢复继承全局后，覆盖规则不再生效。
        repository.saveChannelPolicy(temporary.id, null)
        assertNull(repository.loadChannelOverride(temporary.id))
        assertEquals(ResolvedKeyDisablePolicy.GLOBAL_SCOPE, repository.resolvePolicy(temporary.id).scope)
    }

    @Test
    fun channelKeyDisablePolicyFallsBackToGlobalAndCanBeDisabled() = runBlocking {
        val channel = createChannelWithBaseUrl(name = "fallback", baseUrl = "${fake.baseUrl}/ok/v1")
        val key = channel.keys.single()
        val repository = app.keyAutoDisableRepository
        // 渠道规则只覆盖 401：403 的余额错误必须继续由全局规则兜底。
        val channelRule = KeyDisableRule(
            id = "channel_auth",
            name = "渠道鉴权失败",
            statusCodes = listOf(401),
            threshold = 1,
            action = KeyDisableAction.PERMANENT,
        )
        repository.saveChannelPolicy(channel.id, KeyDisablePolicy(rules = listOf(channelRule)))

        assertEquals("channel_auth", repository.resolvePolicy(channel.id).policy.rules.first().id)
        val balance = KeyAttemptSignal.UpstreamResponse(403, "INSUFFICIENT_BALANCE")
        assertEquals("insufficient_balance", repository.recordOutcome(key, balance, "fallback"))
        assertEquals(
            ChannelKeyState.AUTO_PERMANENT,
            assertNotNull(app.channelRepository.find(channel.id)).keys.single().state,
        )

        // 关闭形态：渠道规则和全局规则都不执行，坏密钥不会被自动摘除。
        val disabled = createChannelWithBaseUrl(name = "disabled", baseUrl = "${fake.baseUrl}/ok/v1")
        val disabledKey = disabled.keys.single()
        val saved = repository.saveChannelPolicy(disabled.id, KeyDisablePolicy(enabled = false, rules = emptyList()))
        assertFalse(saved.after.policy.enabled)
        assertEquals(ChannelKeyDisableMode.DISABLED, ChannelKeyDisableMode.of(saved.override))
        // 账本也应被清空，避免关闭期间残留旧计数。
        assertNull(repository.recordOutcome(disabledKey, balance, "disabled-balance"))
        assertNull(repository.recordOutcome(disabledKey, KeyAttemptSignal.UpstreamResponse(401, "invalid_api_key"), "disabled-auth"))
        assertEquals(ChannelKeyState.ACTIVE, assertNotNull(app.channelRepository.find(disabled.id)).keys.single().state)

        // 重新开启全局兜底后，同样的信号会再次命中。
        repository.saveChannelPolicy(disabled.id, null)
        assertEquals("insufficient_balance", repository.recordOutcome(disabledKey, balance, "reenabled"))
    }

    @Test
    fun channelKeyDisablePolicyTakesOverSameIdGlobalRule() = runBlocking {
        val channel = createChannelWithBaseUrl(name = "id-takeover", baseUrl = "${fake.baseUrl}/ok/v1")
        val key = channel.keys.single()
        val repository = app.keyAutoDisableRepository
        // 与全局同 id：渠道版本接管这一条（阈值从 2 收紧到 1），全局版本不再兜底。
        val tightened = KeyDisablePolicy.DEFAULT_RULES.single { it.id == "credential_invalid" }.copy(threshold = 1)
        repository.saveChannelPolicy(channel.id, KeyDisablePolicy(rules = listOf(tightened)))

        val resolved = repository.resolvePolicy(channel.id)
        assertEquals(1, resolved.policy.rules.count { it.id == "credential_invalid" }, "同 id 的全局版本应被剔除")
        assertEquals(1, resolved.policy.rules.single { it.id == "credential_invalid" }.threshold)
        // 其它全局规则仍然兜底，规则条数 = 渠道 1 条 + 全局去掉同 id 的其余规则。
        assertEquals(
            repository.loadSettings().policy.rules.size,
            resolved.policy.rules.size,
            "渠道接管一条全局规则后总条数不变",
        )

        assertEquals(
            "credential_invalid",
            repository.recordOutcome(key, KeyAttemptSignal.UpstreamResponse(401, "invalid_api_key"), "tightened"),
            "渠道版本阈值 1，一次命中即停用",
        )
        assertEquals(
            ChannelKeyState.AUTO_PERMANENT,
            assertNotNull(app.channelRepository.find(channel.id)).keys.single().state,
        )
    }

    @Test
    fun channelKeyDisablePolicyInvalidatesCountersWhenGlobalRulesChange() = runBlocking {
        val channel = createChannelWithBaseUrl(name = "global-change", baseUrl = "${fake.baseUrl}/ok/v1")
        val key = channel.keys.single()
        val repository = app.keyAutoDisableRepository
        val channelRule = KeyDisableRule(
            id = "channel_auth",
            name = "渠道鉴权失败",
            statusCodes = listOf(401),
            threshold = 2,
            action = KeyDisableAction.PERMANENT,
        )
        repository.saveChannelPolicy(channel.id, KeyDisablePolicy(rules = listOf(channelRule)))

        // 全局兜底规则阈值设为 2，才能观察「一次失败 + 改全局规则 + 一次失败」是否误触发。
        val globalRule = KeyDisableRule(
            id = "global_balance",
            name = "全局余额不足",
            statusCodes = listOf(403),
            bodyPatterns = listOf("INSUFFICIENT_BALANCE"),
            threshold = 2,
            action = KeyDisableAction.PERMANENT,
        )
        repository.saveSettings(KeyDisablePolicy(rules = listOf(globalRule)))

        val signal = KeyAttemptSignal.UpstreamResponse(403, "INSUFFICIENT_BALANCE")
        assertNull(repository.recordOutcome(key, signal, "before-global-change"))
        // 只改名字，匹配条件不变：有效策略指纹仍然变化，旧计数必须作废。
        repository.saveSettings(KeyDisablePolicy(rules = listOf(globalRule.copy(name = "全局余额不足（改名）"))))
        assertNull(
            repository.recordOutcome(key, signal, "after-global-change"),
            "全局规则变更后旧计数应失效，不能靠一次新失败触发停用",
        )
        assertEquals(
            ChannelKeyState.ACTIVE,
            assertNotNull(app.channelRepository.find(channel.id)).keys.single().state,
        )
        // 新策略下再从零累计两次，才会停用。
        assertEquals("global_balance", repository.recordOutcome(key, signal, "after-global-change-2"))
        assertEquals(
            ChannelKeyState.AUTO_PERMANENT,
            assertNotNull(app.channelRepository.find(channel.id)).keys.single().state,
        )
    }

    @Test
    fun channelKeyDisablePolicyApiValidatesPersistsAndAudits() = runBlocking {
        val channel = createChannelWithBaseUrl(name = "override-api", baseUrl = "${fake.baseUrl}/ok/v1")
        testApplication {
            application { app.install(this) }
            val path = "/admin/api/channels/${channel.id}/key-disable-policy"
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            val (cookie, csrf) = client.loginAdmin()
            val initial = client.get(path) { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, initial.status)
            val initialJson = Json.parseToJsonElement(initial.bodyAsText()).jsonObject
            assertEquals("inherit", initialJson["mode"]?.jsonPrimitive?.content)
            assertNull(initialJson["override"])
            assertNotNull(initialJson["globalPolicy"]?.jsonObject)
            assertNotNull(initialJson["effective"]?.jsonObject)

            suspend fun put(body: String, csrfToken: String? = csrf) = client.put(path) {
                header(HttpHeaders.Cookie, cookie)
                csrfToken?.let { header("X-CSRF-Token", it) }
                header("X-Request-Id", "channel-policy-change")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            suspend fun update(body: String) = client.put(path) {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val invalid = """{"override":{"enabled":true,"rules":[{"id":"bad-id","name":"x","statusCodes":[],"threshold":1,"action":"PERMANENT"}]}}"""
            assertEquals(HttpStatusCode.Forbidden, put(invalid, csrfToken = null).status)
            assertEquals(HttpStatusCode.BadRequest, update(invalid).status)
            assertEquals(
                HttpStatusCode.BadRequest,
                update("""{"override":{"enabled":true,"rules":[]}}""").status,
            )
            // 单份策略仍限 50 条：超限是 400，不是合并上限放宽后的 500。
            val tooManyRules = (1..KeyDisablePolicy.MAX_RULES + 1).joinToString(",") { index ->
                """{"id":"bulk_$index","name":"批量规则 $index","statusCodes":[401],"threshold":1,"action":"PERMANENT"}"""
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                update("""{"override":{"enabled":true,"rules":[$tooManyRules]}}""").status,
            )

            val override = """{"override":{"enabled":true,"rules":[{"id":"activity_disabled","name":"活动已关闭或Key失效","statusCodes":[403],"bodyPatterns":["DEEPSEEK_ACTIVITY_DISABLED"],"threshold":1,"action":"PERMANENT"}]}}"""
            val saved = put(override)
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val savedJson = Json.parseToJsonElement(saved.bodyAsText()).jsonObject
            assertEquals("custom", savedJson["mode"]?.jsonPrimitive?.content)
            assertEquals(
                "activity_disabled",
                savedJson["override"]?.jsonObject?.get("rules")?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content,
            )
            // 写响应与随后的读响应一致，渠道列表也标记为渠道策略形态。
            val reloaded = Json.parseToJsonElement(client.get(path) { header(HttpHeaders.Cookie, cookie) }.bodyAsText()).jsonObject
            assertEquals("custom", reloaded["mode"]?.jsonPrimitive?.content)
            assertEquals(savedJson["effective"], reloaded["effective"])
            val listing = client.get("/admin/api/channels") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
            val listed = Json.parseToJsonElement(listing).jsonArray.single { it.jsonObject["id"]?.jsonPrimitive?.content == channel.id }
            assertEquals("custom", listed.jsonObject["keyDisableMode"]?.jsonPrimitive?.content)

            val audit = app.adminAuditRepository.list(100)
                .first { it.action == "channel.key_disable_policy.update" && it.result == "success" }
            assertEquals("channel-policy-change", audit.requestId)
            assertEquals(channel.id, audit.details?.get("channelId")?.jsonPrimitive?.content)
            assertEquals("custom", audit.details?.get("mode")?.jsonPrimitive?.content)
            assertEquals("global", audit.details?.get("before")?.jsonObject?.get("scope")?.jsonPrimitive?.content)

            // 关闭形态允许空规则，响应标记为 disabled 且有效策略不再启用。
            val disabled = update("""{"override":{"enabled":false,"rules":[]}}""")
            assertEquals(HttpStatusCode.OK, disabled.status, disabled.bodyAsText())
            val disabledJson = Json.parseToJsonElement(disabled.bodyAsText()).jsonObject
            assertEquals("disabled", disabledJson["mode"]?.jsonPrimitive?.content)
            assertEquals("false", disabledJson["effective"]?.jsonObject?.get("enabled")?.jsonPrimitive?.content)

            // 恢复继承：mode 回到 inherit，覆盖内容清空。
            val cleared = update("""{"override":null}""")
            assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
            val clearedJson = Json.parseToJsonElement(cleared.bodyAsText()).jsonObject
            assertEquals("inherit", clearedJson["mode"]?.jsonPrimitive?.content)
            assertNull(clearedJson["override"])
            assertEquals("inherit", Json.parseToJsonElement(
                client.get("/admin/api/channels") { header(HttpHeaders.Cookie, cookie) }.bodyAsText(),
            ).jsonArray.single { it.jsonObject["id"]?.jsonPrimitive?.content == channel.id }
                .jsonObject["keyDisableMode"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableSettingsApiValidatesCsrfPersistsAndAudits() = runBlocking {
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/key-auto-disable-settings").status)
            val (cookie, csrf) = client.loginAdmin()
            val initial = client.get("/admin/api/key-auto-disable-settings") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, initial.status)
            val initialJson = Json.parseToJsonElement(initial.bodyAsText()).jsonObject
            assertEquals("true", initialJson["enabled"]?.jsonPrimitive?.content)
            assertEquals("2", initialJson["authFailureThreshold"]?.jsonPrimitive?.content)
            assertEquals("8", initialJson["failureThreshold"]?.jsonPrimitive?.content)
            assertEquals("5", initialJson["failureWindowMinutes"]?.jsonPrimitive?.content)
            suspend fun put(body: String, csrfToken: String? = csrf) = client.put("/admin/api/key-auto-disable-settings") {
                header(HttpHeaders.Cookie, cookie)
                csrfToken?.let { header("X-CSRF-Token", it) }
                header("X-Request-Id", "settings-change")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val valid = """{"enabled":false,"authFailureThreshold":4,"failureThreshold":10,"failureWindowMinutes":15}"""
            assertEquals(HttpStatusCode.Forbidden, put(valid, csrfToken = null).status)
            for (body in listOf(
                valid.replace("Threshold\":4", "Threshold\":0"),
                valid.replace("Threshold\":10", "Threshold\":1001"),
                valid.replace("Minutes\":15", "Minutes\":1441"),
                valid.replace("Minutes\":15", "Minutes\":1.5"),
                "{}",
            )) assertEquals(HttpStatusCode.BadRequest, put(body).status, body)
            assertEquals(HttpStatusCode.OK, put(valid).status)
            val persisted = KeyAutoDisableRepository(app.databaseFactory.database, app.adminAuditRepository).loadSettings()
            assertEquals(KeyAutoDisablePolicy(false, 4, 10, 15).toKeyDisablePolicy(), persisted.policy)
            val audit = app.adminAuditRepository.list(100).first { it.action == "key_auto_disable_settings.update" && it.result == "success" }
            assertEquals("settings-change", audit.requestId)
            assertEquals("true", audit.details?.get("before")?.jsonObject?.get("enabled")?.jsonPrimitive?.content)
            assertEquals("false", audit.details?.get("after")?.jsonObject?.get("enabled")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun keyAutoDisableRejectsStaleEditsAndManualReenableClearsCooldown() = runBlocking {
        app.keyAutoDisableRepository.saveSettings(
            KeyAutoDisablePolicy(authFailureThreshold = 3, failureThreshold = 100),
        )
        val channel = createAutoDisablePool()
        val primary = channel.keys.first { it.name == "primary" }
        val failedPermit = assertNotNull(app.candidateCooldown.acquire(channel.id, primary.id, "m1"))
        app.candidateCooldown.recordFailure(failedPermit, dev.soven.llmgateway.resilience.FailureKind.AUTH_REJECTED)
        repeat(3) { app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "auth-$it", 401) }
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun update(
                source: ChannelRecord,
                keyState: String? = "ACTIVE",
            ) = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                val stateField = keyState?.let { ",\"state\":\"$it\"" }.orEmpty()
                setBody("""{"name":"${channel.name}","baseUrl":"${channel.baseUrl}","enabled":true,"keys":[${
                    source.keys.joinToString { """{"id":"${it.id}","name":"${it.name}"$stateField,"stateVersion":${it.stateVersion},"priority":${it.priority},"models":["m1","m2"]}""" }
                }]}""")
            }
            val stale = update(channel)
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue(stale.bodyAsText().contains("channel_key_state_changed"))
            val disabled = assertNotNull(app.channelRepository.find(channel.id))
            assertEquals(ChannelKeyState.AUTO_PERMANENT, disabled.keys.first { it.id == primary.id }.state)
            assertNotNull(disabled.keys.first { it.id == primary.id }.autoDisabledAt)
            assertEquals(HttpStatusCode.OK, update(disabled, keyState = null).status)
            val preserved = assertNotNull(app.channelRepository.find(channel.id))
            assertEquals(ChannelKeyState.AUTO_PERMANENT, preserved.keys.first { it.id == primary.id }.state)
            assertEquals(
                disabled.keys.first { it.id == primary.id }.stateVersion,
                preserved.keys.first { it.id == primary.id }.stateVersion,
            )
            assertNotNull(preserved.keys.first { it.id == primary.id }.autoDisabledAt)
            assertEquals(HttpStatusCode.OK, update(preserved).status)
            val enabled = assertNotNull(app.channelRepository.find(channel.id)).keys.first { it.id == primary.id }
            assertEquals(ChannelKeyState.ACTIVE, enabled.state)
            assertNull(enabled.autoDisabledAt)
            assertNull(enabled.autoDisableReason)
            assertNull(enabled.autoDisableUntil)
            assertFalse(app.candidateCooldown.isBlocked(channel.id, primary.id, "m1"))
            val key = createClientKey()
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key).status)
            assertEquals("Bearer key-primary", fake.requests.last().authorization)
        }
    }

    @Test
    fun `permanent disable can be manually reenabled without credential rotation`() = runBlocking {
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(authFailureThreshold = 1))
        val channel = createAutoDisablePool(withBackup = false)
        val primary = channel.keys.single()
        assertNotNull(app.keyAutoDisableRepository.recordOutcome(primary, KeyAttemptOutcome.AUTH_FAILURE, "perm", 401))
        val disabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
        assertEquals(ChannelKeyState.AUTO_PERMANENT, disabled.state)

        val enabled = assertNotNull(app.channelRepository.update(
            id = channel.id, name = channel.name, baseUrl = channel.baseUrl, authType = channel.authType, enabled = true,
            keys = listOf(ChannelKeyUpdate(
                id = disabled.id, name = disabled.name, state = ChannelKeyState.ACTIVE,
                priority = disabled.priority, models = disabled.models, stateVersion = disabled.stateVersion,
            )),
        )).keys.single()
        assertEquals(ChannelKeyState.ACTIVE, enabled.state)
        assertTrue(enabled.stateVersion > disabled.stateVersion)
        assertNull(enabled.autoDisabledAt)
        assertNull(enabled.autoDisableReason)
        assertNull(enabled.autoDisableUntil)
    }

    @Test
    fun permanentKeyRecoveryCanReplaceTheUpstreamCredentialThroughTheAdminApi() = runBlocking {
        app.keyAutoDisableRepository.saveSettings(KeyAutoDisablePolicy(authFailureThreshold = 1))
        val channel = createAutoDisablePool(withBackup = false)
        val disabled = channel.keys.single()
        val permit = assertNotNull(app.candidateCooldown.acquire(channel.id, disabled.id, "m1"))
        app.candidateCooldown.recordFailure(permit, FailureKind.AUTH_REJECTED)
        assertNotNull(app.keyAutoDisableRepository.recordOutcome(disabled, KeyAttemptOutcome.AUTH_FAILURE, "perm", 401))
        val autoDisabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val replacedWithoutState = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"${channel.name}","baseUrl":"${channel.baseUrl}","enabled":true,"keys":[{"id":"${autoDisabled.id}","name":"${autoDisabled.name}","credential":"repaired-key","stateVersion":${autoDisabled.stateVersion},"priority":${autoDisabled.priority},"models":["m1","m2"]}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, replacedWithoutState.status, replacedWithoutState.bodyAsText())

            val stillDisabled = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_PERMANENT, stillDisabled.state)
            assertTrue(stillDisabled.stateVersion > autoDisabled.stateVersion)
            assertNotNull(stillDisabled.autoDisabledAt)
            assertNotNull(stillDisabled.autoDisableReason)

            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"${channel.name}","baseUrl":"${channel.baseUrl}","enabled":true,"keys":[{"id":"${stillDisabled.id}","name":"${stillDisabled.name}","state":"ACTIVE","stateVersion":${stillDisabled.stateVersion},"priority":${stillDisabled.priority},"models":["m1","m2"]}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            val recovered = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.ACTIVE, recovered.state)
            assertTrue(recovered.stateVersion > stillDisabled.stateVersion)
            assertNull(recovered.autoDisabledAt)
            assertNull(recovered.autoDisableReason)
            assertNull(recovered.autoDisableUntil)
            assertFalse(app.candidateCooldown.isBlocked(channel.id, disabled.id, "m1"))

            val addKey = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"${channel.name}","baseUrl":"${channel.baseUrl}","enabled":true,"keys":[{"id":"${recovered.id}","name":"${recovered.name}","credential":"repaired-key","state":"ACTIVE","stateVersion":${recovered.stateVersion},"priority":${recovered.priority},"models":["m1","m2"]},{"name":"secondary","credential":"new-key","priority":2,"models":["m1","m2"]}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, addKey.status, addKey.bodyAsText())
            val added = assertNotNull(app.channelRepository.find(channel.id)).keys.single { it.name == "secondary" }
            assertEquals(ChannelKeyState.ACTIVE, added.state)

            val clientKey = createClientKey()
            assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(clientKey).status)
            assertEquals("Bearer repaired-key", fake.requests.last().authorization)
        }
    }

    @Test
    fun `channel keys expose model union but route only to supporting keys`() = runBlocking {
        app.channelRepository.create(
            name = "key-models",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("key-primary"),
                    models = listOf("gpt-4o-mini"),
                ),
                NewChannelKey(
                    name = "backup",
                    credential = app.credentialCipher.encrypt("key-backup"),
                    models = listOf("deepseek-chat"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini", "deepseek-chat"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            suspend fun send(model: String) = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, send("gpt-4o-mini").status)
            assertEquals(HttpStatusCode.OK, send("deepseek-chat").status)
            assertEquals(listOf("Bearer key-primary", "Bearer key-backup"), fake.requests.map { it.authorization })

            val models = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
            val body = models.bodyAsText()
            assertTrue(body.contains("gpt-4o-mini"), body)
            assertTrue(body.contains("deepseek-chat"), body)
        }
    }

    @Test
    fun `admin creates and updates a channel key pool`() = runBlocking {
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val created = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"key-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "authType":"bearer",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[{"upstreamModel":"gpt-4o-mini"},{"upstreamModel":"deepseek-chat"}],
                      "keys":[
                        {"name":"primary","credential":"key-primary","priority":1,"models":["gpt-4o-mini"]},
                        {"name":"backup","credential":"key-backup","priority":2,"models":["deepseek-chat"]}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val createdBody = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val channelId = createdBody["id"]!!.jsonPrimitive.content
            val createdKeys = createdBody["keys"]!!.jsonArray
            assertEquals(2, createdKeys.size)
            assertEquals(listOf("primary", "backup"), createdKeys.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            val backupId = createdKeys.first { it.jsonObject["name"]!!.jsonPrimitive.content == "backup" }
                .jsonObject["id"]!!.jsonPrimitive.content

            val tested = client.post("/admin/api/channels/$channelId/model-test") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"deepseek-chat","protocol":"chat_completions","keyId":"$backupId","prompt":"ping"}""")
            }
            assertEquals(HttpStatusCode.OK, tested.status, tested.bodyAsText())
            val testBody = Json.parseToJsonElement(tested.bodyAsText()).jsonObject
            assertEquals("backup", testBody["keyName"]!!.jsonPrimitive.content)
            assertEquals("Bearer key-backup", fake.requests.last().authorization)

            val updated = client.put("/admin/api/channels/$channelId") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"key-api",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "authType":"bearer",
                      "protocols":["chat_completions"],
                      "enabled":true,
                      "modelMappings":[{"upstreamModel":"gpt-4o-mini"},{"upstreamModel":"deepseek-chat"}],
                      "keys":[
                        {"id":"${createdKeys.first().jsonObject["id"]!!.jsonPrimitive.content}","name":"primary","priority":1,"models":["gpt-4o-mini","deepseek-chat"]},
                        {"name":"third","credential":"key-third","priority":3,"models":["gpt-4o-mini"]}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            val updatedKeys = Json.parseToJsonElement(updated.bodyAsText()).jsonObject["keys"]!!.jsonArray
            assertEquals(listOf("primary", "third"), updatedKeys.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            assertEquals(
                setOf("gpt-4o-mini", "deepseek-chat"),
                updatedKeys.first().jsonObject["models"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
        }
    }

    @Test
    fun `legacy model update keeps the default key authorization in sync`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "legacy-models",
            "${fake.baseUrl}/ok/v1",
            modelMappings = mappingsOf("gpt-4o-mini", "deepseek-chat"),
        )
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"legacy-models","baseUrl":"${fake.baseUrl}/ok/v1","protocols":["chat_completions"],"enabled":true,"models":["gpt-4o-mini"]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val updated = assertNotNull(app.channelRepository.find(channel.id))
            assertEquals(setOf("gpt-4o-mini"), updated.models.toSet())
            assertEquals(listOf("gpt-4o-mini"), updated.keys.single().models)
        }
    }

    @Test
    fun `admin rejects disabled keys for discovery and model tests`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "disabled-key",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("key-primary"),
                    models = listOf("gpt-4o-mini"),
                ),
                NewChannelKey(
                    name = "disabled",
                    credential = app.credentialCipher.encrypt("key-disabled"),
                    state = ChannelKeyState.MANUAL_DISABLED,
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        val disabledKey = channel.keys.single { it.name == "disabled" }
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val discovery = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${disabledKey.id}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, discovery.status)
            assertTrue(discovery.bodyAsText().contains("channel_key_disabled"))

            val tested = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","protocol":"chat_completions","keyId":"${disabledKey.id}","prompt":"ping"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, tested.status)
            assertTrue(tested.bodyAsText().contains("channel_key_disabled"))
        }
    }

    @Test
    fun `admin rejects temporarily disabled keys with specific error code`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "temp-disabled-key",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "primary",
                    credential = app.credentialCipher.encrypt("key-primary"),
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        val key = channel.keys.single()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("update channel_keys set state = 'AUTO_TEMPORARY', auto_disable_until = ${System.currentTimeMillis() + 600_000} where id = '${key.id}'")
            }
        }
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val discovery = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${key.id}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, discovery.status)
            assertTrue(discovery.bodyAsText().contains("channel_key_temporarily_disabled"))

            val tested = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","protocol":"chat_completions","keyId":"${key.id}","prompt":"ping"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, tested.status)
            assertTrue(tested.bodyAsText().contains("channel_key_temporarily_disabled"))

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "update channel_keys set auto_disable_until = ?, probe_until = ? where id = ?",
                ).use { statement ->
                    val now = System.currentTimeMillis()
                    statement.setLong(1, now - 1)
                    statement.setLong(2, now + 60_000)
                    statement.setString(3, key.id)
                    statement.executeUpdate()
                }
            }
            val probing = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${key.id}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, probing.status)
            assertTrue(probing.bodyAsText().contains("正在探测恢复"))
        }
    }

    @Test
    fun `admin rejects client-submitted auto disable states`() = runBlocking {
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            for (autoState in listOf("AUTO_TEMPORARY", "AUTO_PERMANENT")) {
                val response = client.post("/admin/api/channels") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "name":"auto-state-$autoState",
                          "baseUrl":"${fake.baseUrl}/ok/v1",
                          "keys":[{"name":"primary","credential":"secret","state":"$autoState","models":["m1"]}],
                          "modelMappings":[{"upstreamModel":"m1"}],
                          "enabled":true
                        }
                        """.trimIndent(),
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("invalid_channel"))
            }
        }
    }

    @Test
    fun `reenabling manual disabled or temporary key does not require changing credential`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "reenable-test",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "manual-key",
                    credential = app.credentialCipher.encrypt("secret-1"),
                    state = ChannelKeyState.MANUAL_DISABLED,
                    models = listOf("m1"),
                ),
            ),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = mappingsOf("m1"),
        )
        val manualKey = channel.keys.single()
        val reenabled = assertNotNull(app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            authType = channel.authType,
            enabled = true,
            keys = listOf(
                ChannelKeyUpdate(
                    id = manualKey.id,
                    name = manualKey.name,
                    state = ChannelKeyState.ACTIVE,
                    models = manualKey.models,
                    stateVersion = manualKey.stateVersion,
                ),
            ),
        )).keys.single()
        assertEquals(ChannelKeyState.ACTIVE, reenabled.state)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("update channel_keys set state = 'AUTO_TEMPORARY', auto_disable_until = 1000 where id = '${manualKey.id}'")
            }
        }
        val tempKey = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
        assertEquals(ChannelKeyState.AUTO_TEMPORARY, tempKey.state)
        assertTrue(app.keyAutoDisableRepository.isAvailable(tempKey, now = 1000))
        assertFalse(app.keyAutoDisableRepository.isAvailable(tempKey, now = 999))

        val updatedCredentialTemp = assertNotNull(app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            authType = channel.authType,
            enabled = true,
            keys = listOf(
                ChannelKeyUpdate(
                    id = tempKey.id,
                    name = tempKey.name,
                    credential = app.credentialCipher.encrypt("secret-2"),
                    models = tempKey.models,
                    stateVersion = tempKey.stateVersion,
                ),
            ),
        )).keys.single()
        assertEquals(ChannelKeyState.AUTO_TEMPORARY, updatedCredentialTemp.state)
        assertTrue(updatedCredentialTemp.stateVersion > tempKey.stateVersion)
        assertEquals(1000L, updatedCredentialTemp.autoDisableUntil)

        val reenabledTemp = assertNotNull(app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
            authType = channel.authType,
            enabled = true,
            keys = listOf(
                ChannelKeyUpdate(
                    id = updatedCredentialTemp.id,
                    name = updatedCredentialTemp.name,
                    state = ChannelKeyState.ACTIVE,
                    models = updatedCredentialTemp.models,
                    stateVersion = updatedCredentialTemp.stateVersion,
                ),
            ),
        )).keys.single()
        assertEquals(ChannelKeyState.ACTIVE, reenabledTemp.state)
        assertNull(reenabledTemp.autoDisableUntil)
    }

    @Test
    fun `admin rejects legacy credential together with keys`() = runBlocking {
        val session = app.sessions.create("admin")

        testApplication {
            application { app.install(this) }
            val response = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"mixed-credential",
                      "baseUrl":"${fake.baseUrl}/ok/v1",
                      "credential":"legacy",
                      "modelMappings":[{"upstreamModel":"gpt-4o-mini"}],
                      "keys":[{"name":"primary","credential":"key-primary","models":["gpt-4o-mini"]}]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("keys 与 credential 不能同时提交"))
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
            delay(350)
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

            delay(350)
            assertEquals(HttpStatusCode.OK, send().status)
            awaitRequestLogCount(2)
            assertEquals(3, app.requestLogRepository.list(10).first().upstreamAttempts)
            assertEquals(listOf("500", "500", "ok", "500", "500", "ok"), fake.requests.map { it.scenario })
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
    fun `cooldown reason survives persistence round trip`() = runBlocking {
        val repository = dev.soven.llmgateway.repo.CooldownStateRepository(app.databaseFactory.database)
        repository.upsert(
            dev.soven.llmgateway.resilience.CooldownStateSnapshot(
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
        awaitCancellation()
    }

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
    fun `rejects invalid model and stream types`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "m1")
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
    fun `models excludes canonical ids without enabled channel`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeUnderCanonicalId("fast-chat", listOf(channel.id to "m1"))
        app.channelRepository.update(
            id = channel.id,
            name = channel.name,
            baseUrl = channel.baseUrl,
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
    fun `exposes and routes upstream models without aliases when enabled`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
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
    fun `admin channel model mappings persist and allow shared canonical ids`() = runBlocking {
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
                        {"upstreamModel":"deepseek-chat","canonicalModelId":"same-id","mappingSource":"manual"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, duplicate.status)
            val duplicateBody = duplicate.bodyAsText()
            assertTrue(duplicateBody.contains("gpt-4o-mini"), "BODY=[$duplicateBody]")
            assertTrue(duplicateBody.contains("deepseek-chat"), "BODY=[$duplicateBody]")
            val sharedCandidates = app.modelCatalog.findCandidates("same-id", GatewayProtocol.CHAT_COMPLETIONS)
            assertEquals(
                listOf("deepseek-chat", "gpt-4o-mini"),
                sharedCandidates.map { it.upstreamModel }.sorted(),
            )
            assertEquals(1, app.modelCatalog.listEnabledModels().count { it == "same-id" })

            val publicKey = createClientKey()
            val exposed = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $publicKey")
            }
            assertEquals(HttpStatusCode.OK, exposed.status)
            val exposedIds = Json.parseToJsonElement(exposed.bodyAsText()).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf("same-id"), exposedIds)

            val canonicalConflictsWithUnmapped = client.put("/admin/api/channels/${channel.id}") {
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
                        {"upstreamModel":"Qwen-Fast","canonicalModelId":"gpt-4o-mini"},
                        {"upstreamModel":"gpt-4o-mini"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, canonicalConflictsWithUnmapped.status)

            val caseDistinctUnmapped = client.put("/admin/api/channels/${channel.id}") {
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
                        {"upstreamModel":"model-A"},
                        {"upstreamModel":"MODEL-a"}
                      ]
                    }
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, caseDistinctUnmapped.status)
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
    fun `public models lists canonical and unmapped upstream ids`() = runBlocking {
        app.close()
        app = GatewayApplication(config())
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $key")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray
            val ids = data.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            // 已映射的上游模型只以 canonical ID 对外暴露。
            assertEquals(setOf("fast-chat", "deepseek-chat", "m1", "m2"), ids.toSet())
            assertEquals(ids.sorted(), ids)
            assertTrue(data.all { it.jsonObject["owned_by"]!!.jsonPrimitive.content == "PrismLLM" })
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
        searchServer.start()
        // 测试宿主会缓冲整个响应体，无法观察增量到达；这里用真实 Netty 宿主和流式客户端验证首事件时间。
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { app.install(this) }
        server.start(wait = false)
        val http = HttpClient(CIO)
        try {
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
            server.stop(0, 1_000)
            searchServer.stop(0)
        }
    }

    @Test
    fun `search proxy rejects an oversized model response`() = runBlocking {
        assertOversizedSearchResponseRejected("oversized-search-response")
    }

    @Test
    fun `search proxy rejects an oversized model error response`() = runBlocking {
        assertOversizedSearchResponseRejected("oversized-search-error")
    }

    private suspend fun assertOversizedSearchResponseRejected(scenario: String) {
        app.webSearchSettingsRepository.save(
            WebSearchSettingsRecord(
                enabled = true,
                provider = "tavily",
                apiKey = "tvly-test",
                baseUrl = "http://127.0.0.1:1",
                maxResults = 3,
                timeoutMs = 2_000,
                maxRounds = 2,
                maxPageChars = 2_000,
                updatedAt = 0,
            ),
        )
        val channel = createChannel(scenario, scenario)
        exposeAsModel(scenario, channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"$scenario","stream":false,"input":"hi","tools":[{"type":"web_search"}]}""")
            }
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.BadGateway, response.status, body)
            assertTrue(body.contains("upstream_invalid_response"), "BODY=[$body]")
            assertTrue(body.contains("上限"), "BODY=[$body]")
        }
    }

    @Test
    fun `protocol authentication overrides apply per outbound protocol and legacy auth remains fallback`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "protocol-auth-overrides",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(
                NewChannelKey(
                    name = "默认密钥",
                    credential = app.credentialCipher.encrypt("upstream-secret"),
                    models = listOf("gpt-4o-mini"),
                ),
            ),
            authType = "bearer",
            protocolAuthTypes = mapOf(
                GatewayProtocol.RESPONSES.value to "anthropic",
                GatewayProtocol.MESSAGES.value to "google",
            ),
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES),
            enabled = true,
            modelMappings = mappingsOf("gpt-4o-mini"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val chat = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","messages":[],"stream":false}""")
            }
            assertEquals(HttpStatusCode.OK, chat.status, chat.bodyAsText())
            val chatRequest = fake.requests.last()
            assertEquals("Bearer upstream-secret", chatRequest.authorization)
            assertNull(chatRequest.requestHeaders["x-api-key"])
            assertNull(chatRequest.requestHeaders["x-goog-api-key"])

            val responses = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","input":"hi","stream":false}""")
            }
            assertEquals(HttpStatusCode.OK, responses.status, responses.bodyAsText())
            val responsesRequest = fake.requests.last()
            assertNull(responsesRequest.authorization)
            assertEquals(listOf("upstream-secret"), responsesRequest.requestHeaders["x-api-key"])
            assertEquals(listOf("2023-06-01"), responsesRequest.requestHeaders["anthropic-version"])
            assertNull(responsesRequest.requestHeaders["x-goog-api-key"])

            val messages = client.post("/v1/messages") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","max_tokens":8,"messages":[],"stream":false}""")
            }
            assertEquals(HttpStatusCode.OK, messages.status, messages.bodyAsText())
            val messagesRequest = fake.requests.last()
            assertNull(messagesRequest.authorization)
            assertNull(messagesRequest.requestHeaders["x-api-key"])
            assertEquals(listOf("upstream-secret"), messagesRequest.requestHeaders["x-goog-api-key"])

            assertEquals("bearer", channel.authType)
            assertEquals("anthropic", channel.protocolAuthTypes[GatewayProtocol.RESPONSES.value])
            assertEquals("google", channel.protocolAuthTypes[GatewayProtocol.MESSAGES.value])
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
    fun `records request cost from configured model price`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("priced-chat", channel to "gpt-4o-mini")
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
    fun `records request cost from model default price when channel has no override`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("default-priced-chat", channel to "gpt-4o-mini")
        createDefaultPrice("gpt-4o-mini", input = "3", output = "4")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"default-priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000042")))
            assertEquals("USD", log.costCurrency)
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("3")))
            assertEquals(0, log.outputPricePerMillion?.compareTo(BigDecimal("4")))
        }
    }

    @Test
    fun `channel price overrides model default price`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("override-priced-chat", channel to "gpt-4o-mini")
        createDefaultPrice("gpt-4o-mini", input = "3", output = "4")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"override-priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000016")))
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("1")))
            assertEquals(0, log.outputPricePerMillion?.compareTo(BigDecimal("2")))
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
    fun `admin batch upserts model prices for multiple upstream models`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createPrice(channel, "gpt-4o-mini", input = "9", output = "9")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/model-prices/batch") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","upstreamModels":["gpt-4o-mini","deepseek-chat"],"currency":"CNY","inputPricePerMillion":"7","outputPricePerMillion":"14"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val prices = app.modelPriceRepository.list().filter { it.channelId == channel.id }
            assertEquals(2, prices.size)
            assertTrue(prices.all { it.currency == "CNY" })
            assertTrue(prices.all { it.inputPricePerMillion.compareTo(BigDecimal("7")) == 0 })
            assertTrue(prices.all { it.outputPricePerMillion.compareTo(BigDecimal("14")) == 0 })
        }
    }

    @Test
    fun `admin batch rejects more than 200 raw model entries before normalization`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            val oversizedLists = listOf(
                List(201) { "duplicate-model" },
                List(201) { "" },
            )

            for (models in oversizedLists) {
                val serializedModels = models.joinToString(",") { model -> "\"$model\"" }
                val response = client.post("/admin/api/model-prices/batch") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"channelId":"${channel.id}","upstreamModels":[$serializedModels],"inputPricePerMillion":"1","outputPricePerMillion":"2"}""",
                    )
                }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains("invalid_model_price"))
            }
        }
    }

    @Test
    fun `admin deletes one channel price override without affecting other models`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val target = createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        createPrice(channel, "deepseek-chat", input = "3", output = "4")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.delete("/admin/api/model-prices/${target.id}") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(app.modelPriceRepository.find(channel.id, "gpt-4o-mini"))
            assertNotNull(app.modelPriceRepository.find(channel.id, "deepseek-chat"))
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
            assertEquals(1, app.clientKeyQuotaService.currentUsage(clientKeyId).concurrentRequests)

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
    fun `persists quota limits through client key repository`() = runBlocking {
        val generated = ClientKeyHasher.generate()
        val created = app.clientKeyRepository.create(
            name = "bounded",
            keyHash = generated.hash,
            keyPrefix = generated.prefix,
            enabled = true,
            expiresAt = null,
            quotas = ClientKeyQuotaLimits(
                dailyTokens = 1000,
                dailyCosts = mapOf("USD" to BigDecimal("5.50"), "CNY" to BigDecimal("40")),
                maxConcurrentRequests = 4,
                requestsPerMinute = 120,
            ),
        )

        val loaded = app.clientKeyRepository.find(created.id)!!
        assertEquals(1000, loaded.quotas.dailyTokens)
        assertEquals(0, loaded.quotas.dailyCostLimit("USD")?.compareTo(BigDecimal("5.50")))
        assertEquals(0, loaded.quotas.dailyCostLimit("CNY")?.compareTo(BigDecimal("40")))
        assertEquals(null, loaded.quotas.dailyCostLimit("EUR"))
        assertEquals(4, loaded.quotas.maxConcurrentRequests)
        assertEquals(120, loaded.quotas.requestsPerMinute)
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
    fun `admin updates model test settings`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val initial = client.get("/admin/api/model-test-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertTrue(initial.bodyAsText().contains("请只回复 OK"))

            val updated = client.put("/admin/api/model-test-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"defaultPrompt":"  custom prompt  ","defaultReasoningEffort":"high"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val json = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
            assertEquals("custom prompt", json["defaultPrompt"]?.jsonPrimitive?.content)
            assertTrue("defaultReasoningEffort" in json)
            assertEquals(JsonNull, json["defaultReasoningEffort"])
        }
    }

    @Test
    fun `admin web search settings round trip without exposing the api key`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()

            val unauthorized = client.get("/admin/api/web-search-settings")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val (sessionCookie, csrf) = client.loginAdmin()
            val initial = client.get("/admin/api/web-search-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertEquals(
                "environment",
                Json.parseToJsonElement(initial.bodyAsText()).jsonObject["source"]?.jsonPrimitive?.content,
            )

            val updated = client.put("/admin/api/web-search-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"provider":"tavily","apiKey":"tvly-secret","baseUrl":null,"maxResults":8,"timeoutMs":10000,"maxRounds":4,"maxPageChars":1200}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val body = updated.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(true, json["enabled"]?.jsonPrimitive?.boolean)
            assertEquals("settings", json["source"]?.jsonPrimitive?.content)
            assertEquals("settings", json["apiKeySource"]?.jsonPrimitive?.content)
            assertEquals(true, json["apiKeySet"]?.jsonPrimitive?.boolean)
            assertEquals(8, json["maxResults"]?.jsonPrimitive?.int)
            assertEquals(1200, json["maxPageChars"]?.jsonPrimitive?.int)
            assertFalse(body.contains("tvly-secret"), "BODY=[$body]")

            // 切换后端时不能沿用旧后端的密钥。
            val switched = client.put("/admin/api/web-search-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"provider":"brave","baseUrl":null,"maxResults":6,"timeoutMs":15000,"maxRounds":6,"maxPageChars":1500}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, switched.status)

            // 地址长度在保存前校验，避免数据库列宽错误变成 500。
            val tooLong = client.put("/admin/api/web-search-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"provider":"tavily","apiKey":"tvly-secret","baseUrl":"https://example.com/${"a".repeat(500)}","maxResults":6,"timeoutMs":15000,"maxRounds":6,"maxPageChars":1500}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, tooLong.status)

            // 共享测试数据库：把开关恢复为关闭，避免影响其它用例。
            val disabled = client.put("/admin/api/web-search-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":false,"provider":"tavily","baseUrl":null,"maxResults":6,"timeoutMs":15000,"maxRounds":6,"maxPageChars":1500}""",
                )
            }
            assertEquals(HttpStatusCode.OK, disabled.status)
        }
    }

    @Test
    fun `admin iq vision settings round trip without exposing the api key`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()

            val unauthorized = client.get("/admin/api/iq-vision-settings")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val (sessionCookie, csrf) = client.loginAdmin()
            val initial = client.get("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            val initialJson = Json.parseToJsonElement(initial.bodyAsText()).jsonObject
            // 未保存过配置时沿用环境变量默认值，测试环境没有配置，因此是 environment 来源。
            assertEquals("environment", initialJson["source"]?.jsonPrimitive?.content)
            // 视觉判分器必须始终出现在列表里，启用与否由配置决定。
            val judges = client.get("/admin/api/iq-tests/judges") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val judgeIds = Json.parseToJsonElement(judges.bodyAsText()).jsonObject["judges"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue("vision" in judgeIds, "JUDGES=$judgeIds")

            val updated = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"http://127.0.0.1:8080/v1/","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":90000}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val body = updated.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(true, json["enabled"]?.jsonPrimitive?.boolean)
            assertEquals("settings", json["source"]?.jsonPrimitive?.content)
            assertEquals("settings", json["apiKeySource"]?.jsonPrimitive?.content)
            assertEquals(true, json["apiKeySet"]?.jsonPrimitive?.boolean)
            assertEquals("gpt-vision", json["model"]?.jsonPrimitive?.content)
            // 末尾斜杠统一去掉，避免拼出 //chat/completions。
            assertEquals("http://127.0.0.1:8080/v1", json["baseUrl"]?.jsonPrimitive?.content)
            assertEquals(90_000, json["timeoutMs"]?.jsonPrimitive?.long)
            assertFalse(body.contains("vision-secret"), "BODY=[$body]")

            // 端点变更后不能沿用旧密钥，必须显式提供新密钥。
            val switched = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"https://vision.example.com/v1","model":"gpt-vision","timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, switched.status)
            // 被拒绝的请求不能改动已保存的配置。
            val afterRejected = client.get("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val afterRejectedJson = Json.parseToJsonElement(afterRejected.bodyAsText()).jsonObject
            assertEquals("http://127.0.0.1:8080/v1", afterRejectedJson["baseUrl"]?.jsonPrimitive?.content)
            assertEquals(true, afterRejectedJson["apiKeySet"]?.jsonPrimitive?.boolean)

            // 明确勾选清除时可以换端点，结果是端点生效、密钥为空。
            val switchedCleared = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"https://vision.example.com/v1","model":"gpt-vision","clearApiKey":true,"timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.OK, switchedCleared.status)
            val switchedClearedJson = Json.parseToJsonElement(switchedCleared.bodyAsText()).jsonObject
            assertEquals("https://vision.example.com/v1", switchedClearedJson["baseUrl"]?.jsonPrimitive?.content)
            assertEquals(false, switchedClearedJson["apiKeySet"]?.jsonPrimitive?.boolean)
            assertEquals("none", switchedClearedJson["apiKeySource"]?.jsonPrimitive?.content)

            // 启用时超时必须落在合法区间，且在保存前完成校验。
            val invalidTimeout = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"https://vision.example.com/v1","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":100}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, invalidTimeout.status)

            // 关闭状态下也会写库，列宽校验不能只在启用时生效，否则超长值会撞上 varchar 变成 500。
            val longModel = "m".repeat(201)
            val oversizedModel = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":false,"baseUrl":"https://vision.example.com/v1","model":"$longModel","timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, oversizedModel.status)

            val invalidScheme = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"ftp://example.com","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, invalidScheme.status)

            // 保存后立即生效：重新读取必须反映新值，不需要重启。
            val reloaded = client.get("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val reloadedJson = Json.parseToJsonElement(reloaded.bodyAsText()).jsonObject
            assertEquals("gpt-vision", reloadedJson["model"]?.jsonPrimitive?.content)
            assertEquals(true, reloadedJson["enabled"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `admin runs candy iq test and reads history`() = runBlocking {
        val channel = createChannel("iq-test", "iq-json")
        val candyResponse =
            """{"answer":21,"reason":"先取完圆形苹果 7 颗、圆形桃子 9 颗、圆形西瓜 8 颗和五角星形西瓜 4 颗，再取一颗。"}"""

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val definitions = client.get("/admin/api/iq-tests/benchmarks") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, definitions.status)
            assertEquals(2, Json.parseToJsonElement(definitions.bodyAsText()).jsonArray.size)

            val judges = client.get("/admin/api/iq-tests/judges") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, judges.status)
            val judgesBody = Json.parseToJsonElement(judges.bodyAsText()).jsonObject
            val judgeIds = judgesBody["judges"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(judgeIds?.contains("local") == true, judges.bodyAsText())
            assertTrue(judgeIds?.contains("manxue") == true, judges.bodyAsText())
            assertEquals("local", judgesBody["defaultJudgeId"]?.jsonPrimitive?.content)

            val targets = client.get("/admin/api/iq-tests/targets") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, targets.status)
            assertTrue(targets.bodyAsText().contains(channel.id), targets.bodyAsText())

            val run = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","judgeId":"local"}""")
            }
            assertEquals(HttpStatusCode.OK, run.status)
            val body = Json.parseToJsonElement(run.bodyAsText()).jsonObject
            assertEquals("candy", body["benchmark"]?.jsonPrimitive?.content)
            assertEquals("passed", body["verdict"]?.jsonPrimitive?.content)
            assertEquals(candyResponse, body["responseText"]?.jsonPrimitive?.content)
            assertEquals("21", body["expectedAnswer"]?.jsonPrimitive?.content)
            assertEquals("21", body["matchedAnswer"]?.jsonPrimitive?.content)
            assertEquals(2, body["checks"]?.jsonArray?.size)
            assertEquals("json", body["checks"]!!.jsonArray.first().jsonObject["id"]?.jsonPrimitive?.content)
            assertTrue(body["checks"]!!.jsonArray.all { it.jsonObject["passed"]?.jsonPrimitive?.boolean == true })
            val judgeResult = body["judgeResult"]
            assertTrue(judgeResult == null || judgeResult is JsonNull, run.bodyAsText())
            val html = body["html"]
            assertTrue(html == null || html is JsonNull, run.bodyAsText())
            assertTrue(body["prompt"]?.jsonPrimitive?.content?.contains("糖果") == true)
            val id = body["id"]?.jsonPrimitive?.content

            val history = client.get("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, history.status)
            val items = Json.parseToJsonElement(history.bodyAsText()).jsonArray
            assertEquals(1, items.size)
            assertEquals(id, items[0].jsonObject["id"]?.jsonPrimitive?.content)
            val historyPrompt = items[0].jsonObject["prompt"]
            assertTrue(historyPrompt == null || historyPrompt is JsonNull)

            val detail = client.get("/admin/api/iq-tests/$id") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, detail.status)
            val detailBody = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals(candyResponse, detailBody["responseText"]?.jsonPrimitive?.content)
            assertEquals(2, detailBody["checks"]?.jsonArray?.size)
        }
    }

    @Test
    fun `admin keeps legacy candy html verdict in history detail`() = runBlocking {
        val channel = createChannel("iq-test-legacy", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, _) = client.loginAdmin()
            val record = app.iqTestRunRepository.insert(
                NewIqTestRun(
                    benchmark = "candy",
                    channelId = channel.id,
                    channelName = channel.name,
                    channelKeyId = null,
                    channelKeyName = null,
                    model = "gpt-4o-mini",
                    upstreamModel = "gpt-4o-mini",
                    protocol = GatewayProtocol.CHAT_COMPLETIONS.value,
                    success = true,
                    statusCode = 200,
                    latencyMs = 10,
                    verdict = "passed",
                    prompt = "legacy prompt",
                    responseText = "<html><body><p>答案是 21 颗。</p></body></html>",
                    expectedAnswer = 21,
                    matchedAnswer = 21,
                    errorMessage = null,
                    usage = null,
                    upstreamReportedModel = "gpt-4o-mini",
                    judgeId = "local",
                    judgeLabel = "本地结构 + 人工",
                    judgeVerdict = "passed",
                    judgeResultLabel = "通过",
                    judgeReason = "旧版判分",
                    judgeScore = null,
                    judgeDetails = null,
                    judgeCheckedAt = 0L,
                ),
            )

            val detail = client.get("/admin/api/iq-tests/${record.id}") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, detail.status)
            val body = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals("passed", body["verdict"]?.jsonPrimitive?.content)
            assertNotNull(body["html"]?.jsonPrimitive?.content, detail.bodyAsText())
            val checks = body["checks"]!!.jsonArray
            assertEquals(1, checks.size)
            assertTrue(
                checks.single().jsonObject["label"]!!.jsonPrimitive.content.contains("最终答案包含 21"),
                detail.bodyAsText(),
            )
        }
    }

    @Test
    fun `admin failed iq test omits fabricated judge result`() = runBlocking {
        val channel = createChannel("iq-fail", "500")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","judgeId":"local"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("failed", body["verdict"]?.jsonPrimitive?.content)
            val judgeResult = body["judgeResult"]
            assertTrue(judgeResult == null || judgeResult is JsonNull, response.bodyAsText())
        }
    }

    @Test
    fun `admin classifies pelican html with manxue`() = runBlocking {
        val channel = createChannelWithBaseUrl("iq-html", "${fake.baseUrl}/iq-html/v1")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            suspend fun run(benchmark: String): kotlinx.serialization.json.JsonObject {
                val response = client.post("/admin/api/iq-tests") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"$benchmark","judgeId":"manxue"}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }

            val pelican = run("pelican")
            assertEquals("passed", pelican["verdict"]?.jsonPrimitive?.content)
            assertEquals("manxue", pelican["judgeResult"]?.jsonObject?.get("judgeId")?.jsonPrimitive?.content)
            assertEquals("passed", pelican["judgeResult"]?.jsonObject?.get("verdict")?.jsonPrimitive?.content)
            assertEquals(1, fake.manxueRequests.size)
            assertTrue(fake.manxueRequests.single().body.contains("<svg"))
        }
    }

    @Test
    fun `admin scores pelican svg with built-in vision judge`() = runBlocking {
        val channel = createChannelWithBaseUrl("iq-html", "${fake.baseUrl}/iq-html/v1")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val judges = client.get("/admin/api/iq-tests/judges") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val judgeIds = Json.parseToJsonElement(judges.bodyAsText()).jsonObject["judges"]?.jsonArray
                ?.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(judgeIds?.contains("vision") == true, judges.bodyAsText())

            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"vision"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("passed", body["verdict"]?.jsonPrimitive?.content)
            val judge = assertNotNull(body["judgeResult"]?.jsonObject)
            assertEquals("vision", judge["judgeId"]?.jsonPrimitive?.content)
            assertEquals("passed", judge["verdict"]?.jsonPrimitive?.content)
            assertEquals("视觉评分通过", judge["label"]?.jsonPrimitive?.content)
            assertEquals(90.0, judge["score"]?.jsonPrimitive?.content?.toDouble())
            val details = assertNotNull(judge["details"]?.jsonObject)
            assertEquals(
                "通过：白色长颈鸟，长喙和橙色喉囊清楚",
                details["鹈鹕特征"]?.jsonPrimitive?.content,
            )
            assertNotNull(details["技术信息"])

            val visionRequest = fake.requests.first { it.scenario == "vision" }
            assertTrue(visionRequest.body.contains("data:image/png;base64,"))
            assertEquals(6, Regex("data:image/png;base64,").findAll(visionRequest.body).count())
            assertNull(visionRequest.authorization)
        }
    }

    @Test
    fun `admin local judge and manual verdict`() = runBlocking {
        val channel = createChannelWithBaseUrl("iq-html", "${fake.baseUrl}/iq-html/v1")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val customPrompt = "自定义：画一只戴墨镜的鹈鹕骑自行车。"
            val run = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"local","prompt":"$customPrompt"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, run.status)
            val body = Json.parseToJsonElement(run.bodyAsText()).jsonObject
            assertEquals("review", body["verdict"]?.jsonPrimitive?.content)
            assertEquals("local", body["judgeResult"]?.jsonObject?.get("judgeId")?.jsonPrimitive?.content)
            assertEquals("review", body["judgeResult"]?.jsonObject?.get("verdict")?.jsonPrimitive?.content)
            assertEquals(customPrompt, body["prompt"]?.jsonPrimitive?.content)
            assertTrue(fake.requests.any { it.scenario == "iq-html" && it.body.contains(customPrompt) })
            val id = body["id"]?.jsonPrimitive?.content

            val manual = client.put("/admin/api/iq-tests/$id/manual-verdict") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"verdict":"passed","score":5,"note":"looks good"}""")
            }
            assertEquals(HttpStatusCode.OK, manual.status)
            val updated = Json.parseToJsonElement(manual.bodyAsText()).jsonObject
            assertEquals("passed", updated["verdict"]?.jsonPrimitive?.content)
            assertEquals("人工通过", updated["verdictLabel"]?.jsonPrimitive?.content)
            assertEquals("passed", updated["manualVerdict"]?.jsonPrimitive?.content)
            assertEquals("5", updated["manualScore"]?.jsonPrimitive?.content)

            val history = client.get("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val item = Json.parseToJsonElement(history.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .first { it["id"]?.jsonPrimitive?.content == id }
            assertEquals("passed", item["verdict"]?.jsonPrimitive?.content)
            assertEquals("passed", item["manualVerdict"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `admin rejects unknown iq judge`() = runBlocking {
        val channel = createChannel("iq-test", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"missing"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("iq_judge_not_found"), response.bodyAsText())
        }
    }

    @Test
    fun `admin rejects oversized iq prompt`() = runBlocking {
        val channel = createChannel("iq-test", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","prompt":"${"a".repeat(20_001)}"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("提示词长度"), response.bodyAsText())
        }
    }

    @Test
    fun `admin tests a channel model without reasoning effort`() = runBlocking {
        val channel = createChannel("model-test", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"gpt-4o-mini","protocol":"chat_completions","prompt":"custom prompt","reasoningEffort":"high"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, json["success"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("Hello!", json["responseText"]?.jsonPrimitive?.content)
            assertEquals("10", json["usage"]?.jsonObject?.get("inputTokens")?.jsonPrimitive?.content)
            assertTrue("reasoningEffort" in json)
            assertEquals(JsonNull, json["reasoningEffort"])
            val upstream = fake.requests.last().body
            assertFalse(upstream.contains("reasoning_effort"), upstream)
            assertTrue(upstream.contains("\"content\":\"custom prompt\""), upstream)
            assertTrue(upstream.contains("\"max_tokens\":128"), upstream)
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
    fun `model test returns upstream failure details`() = runBlocking {
        val channel = createChannel("model-test-fail", "500")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","protocol":"chat_completions","prompt":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false, json["success"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("500", json["statusCode"]?.jsonPrimitive?.content)
            assertEquals("upstream down", json["errorMessage"]?.jsonPrimitive?.content)
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
    @Tag("gemini")
    fun `Gemini channels accept converted chat requests`() = runBlocking {
        createChannelWithBaseUrl(
            "gemini-only",
            "${fake.baseUrl}/ok/v1beta",
            listOf(GatewayProtocol.GEMINI),
            mappingsOf("gemini-2.5-pro"),
            authType = "google",
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gemini-2.5-pro","stream":false,"messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Hello!", body["choices"]!!.jsonArray.single().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
            val upstream = fake.requests.single()
            assertEquals("/ok/v1beta/models/gemini-2.5-pro:generateContent", upstream.uri?.substringBefore('?'))
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertTrue(upstreamBody.containsKey("contents"))
            assertFalse(upstreamBody.containsKey("model"))
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.protocol)
            assertEquals(GatewayProtocol.GEMINI, log.upstreamProtocol)
        }
    }

    @Test
    @Tag("gemini")
    fun `Gemini requests convert to chat channels`() = runBlocking {
        createChannel("gemini-to-chat", "ok")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/gpt-4o-mini:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("STOP", body["candidates"]!!.jsonArray.single().jsonObject["finishReason"]!!.jsonPrimitive.content)
            assertEquals("Hello!", body["candidates"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            val upstream = fake.requests.single()
            assertEquals("/ok/v1/chat/completions", upstream.path)
            val upstreamBody = Json.parseToJsonElement(upstream.body).jsonObject
            assertEquals("gpt-4o-mini", upstreamBody["model"]!!.jsonPrimitive.content)
            assertTrue(upstreamBody["messages"]!!.jsonArray.isNotEmpty())
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.GEMINI, log.protocol)
            assertEquals(GatewayProtocol.CHAT_COMPLETIONS, log.upstreamProtocol)
        }
    }

    @Test
    @Tag("gemini")
    fun `Gemini streaming request asks chat upstream to include usage`() = runBlocking {
        val channel = createChannel("gemini-to-chat-stream-usage", "ok")
        exposeAsModel("fast-gemini", channel to "gpt-4o-mini")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val sent = fake.requests.single()
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
            assertEquals(true, body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
            assertTrue(response.bodyAsText().contains("totalTokenCount"))
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

            // 渠道授权的上游模型直接对外暴露，不再需要别名层。
            assertTrue(app.modelCatalog.listEnabledModels().contains("gpt-4o-mini"))

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
            assertTrue(models.bodyAsText().contains("gpt-4o-mini"))
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
    fun `usage report aggregates requests tokens and costs by key`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            repeat(2) {
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"fast-chat","messages":[]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                awaitRequestLogCount(it + 1)
            }
            app.requestLogRepository.insert(
                requestLog("renamed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "renamed-key",
                    costAmount = java.math.BigDecimal("0.00000500"),
                    costCurrency = "USD",
                ),
            )
            app.requestLogRepository.insert(
                requestLog("failed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    statusCode = 500,
                ),
            )
            app.requestLogRepository.insert(
                requestLog("stream-failed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    statusCode = 200,
                    errorCode = "stream_interrupted",
                ),
            )
            app.requestLogRepository.insert(
                requestLog("retried", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    channelId = channel.id,
                    channelName = channel.name,
                    channelKeyId = channel.keys.single().id,
                    channelKeyName = channel.keys.single().name,
                    upstreamModel = "gpt-4o-mini",
                    cachedTokens = 3,
                    reasoningTokens = 2,
                    upstreamAttempts = 3,
                    firstTokenMs = 120,
                ),
            )
            val currentKey = app.clientKeyRepository.list().single()
            app.clientKeyRepository.update(
                id = currentKey.id,
                name = "paid",
                enabled = currentKey.enabled,
                expiresAt = currentKey.expiresAt,
                quotas = currentKey.quotas,
            )
            val login = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            val cookie = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .first { it.startsWith("gw_session=") }.substringBefore(";")
            val report = client.get("/admin/api/usage-report?from=${System.currentTimeMillis() - 86_400_000L}&to=${System.currentTimeMillis() + 60_000L}") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
            val json = Json.parseToJsonElement(report.bodyAsText()).jsonObject
            val total = json["total"]!!.jsonObject
            assertEquals("6", total["requests"]!!.jsonPrimitive.content)
            assertEquals("2", total["errors"]!!.jsonPrimitive.content)
            assertEquals("34", total["totalTokens"]!!.jsonPrimitive.content)
            assertEquals("3", total["cachedTokens"]!!.jsonPrimitive.content)
            assertEquals("2", total["reasoningTokens"]!!.jsonPrimitive.content)
            assertEquals("2", total["failovers"]!!.jsonPrimitive.content)
            assertEquals("0.000037", total["costs"]!!.jsonObject["USD"]!!.jsonPrimitive.content)
            val byKey = json["byClientKey"]!!.jsonArray.single().jsonObject
            assertEquals(currentKey.id, byKey["id"]!!.jsonPrimitive.content)
            assertEquals("paid", byKey["name"]!!.jsonPrimitive.content)
            assertEquals(
                setOf("test-key", "renamed-key"),
                app.requestLogRepository.list(10).mapNotNull { it.clientKeyName }.toSet(),
            )
            val logsResponse = client.get("/admin/api/request-logs") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, logsResponse.status, logsResponse.bodyAsText())
            val displayedLogs = Json.parseToJsonElement(logsResponse.bodyAsText()).jsonArray
            assertTrue(displayedLogs.isNotEmpty())
            assertTrue(
                displayedLogs.all { it.jsonObject["clientKeyName"]!!.jsonPrimitive.content == "paid" },
                logsResponse.bodyAsText(),
            )
            app.clientKeyRepository.delete(currentKey.id)
            val logsAfterDelete = client.get("/admin/api/request-logs") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, logsAfterDelete.status, logsAfterDelete.bodyAsText())
            assertEquals(
                setOf("test-key", "renamed-key"),
                Json.parseToJsonElement(logsAfterDelete.bodyAsText()).jsonArray
                    .map { it.jsonObject["clientKeyName"]!!.jsonPrimitive.content }
                    .toSet(),
            )
            val reportAfterDelete = client.get("/admin/api/usage-report?from=${System.currentTimeMillis() - 86_400_000L}&to=${System.currentTimeMillis() + 60_000L}") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, reportAfterDelete.status, reportAfterDelete.bodyAsText())
            val byKeyAfterDelete = Json.parseToJsonElement(reportAfterDelete.bodyAsText())
                .jsonObject["byClientKey"]!!.jsonArray.single().jsonObject
            assertTrue(
                byKeyAfterDelete["name"]!!.jsonPrimitive.content in setOf("test-key", "renamed-key"),
                reportAfterDelete.bodyAsText(),
            )
            assertEquals("2", byKey["failovers"]!!.jsonPrimitive.content)
            assertEquals(
                channel.id,
                json["byChannel"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.single { it == channel.id },
            )
            assertTrue(json["byModel"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.contains("gpt-4o-mini"))
            assertEquals(
                setOf("stream_interrupted", "500"),
                json["byErrorCode"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet(),
            )
            assertTrue(json["trend"]!!.jsonArray.isNotEmpty())

            val rangeNow = System.currentTimeMillis()
            suspend fun reportStatus(from: Long, to: Long): Pair<HttpStatusCode, String> {
                val response = client.get("/admin/api/usage-report?from=$from&to=$to") {
                    header(HttpHeaders.Cookie, cookie)
                }
                return response.status to response.bodyAsText()
            }

            val (atLimitStatus, atLimitBody) = reportStatus(rangeNow - 30 * 86_400_000L, rangeNow)
            assertEquals(HttpStatusCode.OK, atLimitStatus, atLimitBody)
            val (overByOneMsStatus, overByOneMsBody) = reportStatus(rangeNow - 30 * 86_400_000L - 1, rangeNow)
            assertEquals(HttpStatusCode.BadRequest, overByOneMsStatus, overByOneMsBody)
            assertEquals(
                "invalid_report_range",
                Json.parseToJsonElement(overByOneMsBody).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
            val (invertedStatus, invertedBody) = reportStatus(rangeNow, rangeNow - 86_400_000L)
            assertEquals(HttpStatusCode.BadRequest, invertedStatus, invertedBody)
            assertEquals(
                "invalid_report_range",
                Json.parseToJsonElement(invertedBody).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
            val (sameInstantStatus, sameInstantBody) = reportStatus(rangeNow, rangeNow)
            assertEquals(HttpStatusCode.OK, sameInstantStatus, sameInstantBody)
            val overLimit = client.get("/admin/api/usage-report?from=${rangeNow - 31 * 86_400_000L}&to=$rangeNow") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.BadRequest, overLimit.status, overLimit.bodyAsText())
            assertEquals(
                "invalid_report_range",
                Json.parseToJsonElement(overLimit.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `request log cleanup deletes only expired rows`() = runBlocking {
        val now = System.currentTimeMillis()
        val old = requestLog("old", createdAt = now - 31 * 86_400_000L)
        val recent = requestLog("recent", createdAt = now)
        app.requestLogRepository.insert(old)
        app.requestLogRepository.insert(recent)
        val retired = app.logRetentionService.cleanExpired()

        assertEquals(1, retired.deleted)
        assertEquals(listOf("recent"), app.requestLogRepository.list(10).map { it.requestId })
    }

    @Test
    fun `admin client key API validates and returns quota settings`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val login = client.post("/admin/api/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test-password"}""")
            }
            val setCookies = login.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            val sessionCookie = setCookies.first { it.startsWith("gw_session=") }.substringBefore(";")
            val csrf = setCookies.first { it.startsWith("gw_csrf=") }.substringAfter("=").substringBefore(";")

            suspend fun create(body: String) = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val created = create(
                """{"name":"bounded","dailyTokenLimit":100,"dailyUsdLimit":"1.50","dailyCnyLimit":"10","maxConcurrentRequests":2,"requestsPerMinute":60}""",
            )
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            assertEquals("100", createdJson["dailyTokenLimit"]!!.jsonPrimitive.content)
            assertEquals("1.5", createdJson["dailyUsdLimit"]!!.jsonPrimitive.content)
            assertEquals("2", createdJson["maxConcurrentRequests"]!!.jsonPrimitive.content)
            assertEquals("60", createdJson["requestsPerMinute"]!!.jsonPrimitive.content)
            assertNull(createdJson["dailyEurLimit"])

            val listed = client.get("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, listed.status)
            val listedKey = Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject
            val usage = listedKey["quotaUsage"]!!.jsonObject
            assertEquals("0", usage["dailyTokens"]!!.jsonPrimitive.content)
            assertEquals("0", usage["concurrentRequests"]!!.jsonPrimitive.content)
            assertEquals("0", usage["currentMinuteRequests"]!!.jsonPrimitive.content)

            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"negative","dailyTokenLimit":-1}""").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"bad-cost","dailyUsdLimit":"-0.01"}""").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"bad-concurrency","maxConcurrentRequests":0}""").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                create("""{"name":"bad-rate","requestsPerMinute":-1}""").status,
            )
        }
    }

    @Test
    fun `client key lifecycle and admin audit enforce permission boundaries`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()

            val failedLogin = client.post("/admin/api/login") {
                header("X-Request-Id", "failed-login-request")
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"wrong-password"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, failedLogin.status)

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/admin/api/audit-logs").status,
            )
            val unmatchedWrite = client.post("/admin/api/not-a-route") {
                header("X-Request-Id", "unmatched-no-session")
            }
            assertEquals(HttpStatusCode.NotFound, unmatchedWrite.status)

            val (sessionCookie, csrf) = client.loginAdmin()
            val created = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "create-key-request")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"search-service","dailyTokenLimit":100,"dailyUsdLimit":"1.50","dailyCnyLimit":"10","maxConcurrentRequests":2,"requestsPerMinute":30}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertEquals("no-store", created.headers["Cache-Control"])
            assertEquals("create-key-request", created.headers["X-Request-Id"])
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val keyId = createdJson["id"]!!.jsonPrimitive.content
            val oldKey = createdJson["key"]!!.jsonPrimitive.content

            val rotateWithoutSession = client.post("/admin/api/client-keys/$keyId/rotate") {
                header("X-Request-Id", "rotate-no-session")
            }
            assertEquals(HttpStatusCode.Unauthorized, rotateWithoutSession.status)
            val revokeWithoutSession = client.post("/admin/api/client-keys/$keyId/revoke") {
                header("X-Request-Id", "revoke-no-session")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokeWithoutSession.status)

            val bearerCannotReadAudit = client.get("/admin/api/audit-logs") {
                header(HttpHeaders.Authorization, "Bearer $oldKey")
            }
            assertEquals(HttpStatusCode.Unauthorized, bearerCannotReadAudit.status)

            val missingCsrf = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-Request-Id", "rotate-missing-csrf")
            }
            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)

            val invalidCsrf = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", "wrong-token")
                header("X-Request-Id", "rotate-invalid-csrf")
            }
            assertEquals(HttpStatusCode.Forbidden, invalidCsrf.status)

            val rotated = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "rotate-key-request")
            }
            assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
            assertEquals("no-store", rotated.headers["Cache-Control"])
            assertEquals("rotate-key-request", rotated.headers["X-Request-Id"])
            val rotatedJson = Json.parseToJsonElement(rotated.bodyAsText()).jsonObject
            val newKey = rotatedJson["key"]!!.jsonPrimitive.content
            assertNotEquals(oldKey, newKey)
            assertEquals("100", rotatedJson["dailyTokenLimit"]!!.jsonPrimitive.content)
            assertEquals("1.5", rotatedJson["dailyUsdLimit"]!!.jsonPrimitive.content)
            assertEquals("2", rotatedJson["maxConcurrentRequests"]!!.jsonPrimitive.content)
            assertEquals("30", rotatedJson["requestsPerMinute"]!!.jsonPrimitive.content)
            assertNotNull(rotatedJson["rotatedAt"])
            assertNull(rotatedJson["revokedAt"])

            val oldKeyResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $oldKey")
            }
            assertEquals(HttpStatusCode.Unauthorized, oldKeyResponse.status)
            val newKeyResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $newKey")
            }
            assertEquals(HttpStatusCode.OK, newKeyResponse.status)

            val revoked = client.post("/admin/api/client-keys/$keyId/revoke") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "revoke-key-request")
            }
            assertEquals(HttpStatusCode.OK, revoked.status, revoked.bodyAsText())
            val revokedJson = Json.parseToJsonElement(revoked.bodyAsText()).jsonObject
            assertEquals("false", revokedJson["enabled"]!!.jsonPrimitive.content)
            assertNotNull(revokedJson["revokedAt"])

            val revokedKeyResponse = client.get("/v1/models") {
                header(HttpHeaders.Authorization, "Bearer $newKey")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokedKeyResponse.status)
            assertTrue(revokedKeyResponse.bodyAsText().contains("撤销"))

            val rotateRevoked = client.post("/admin/api/client-keys/$keyId/rotate") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "rotate-revoked-request")
            }
            assertEquals(HttpStatusCode.Conflict, rotateRevoked.status)

            val reenableRevoked = client.put("/admin/api/client-keys/$keyId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "reenable-revoked-request")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"search-service","enabled":true,"expiresAt":null,"dailyTokenLimit":100,"dailyUsdLimit":"1.50","dailyCnyLimit":"10","maxConcurrentRequests":2,"requestsPerMinute":30}""",
                )
            }
            assertEquals(HttpStatusCode.Conflict, reenableRevoked.status)

            val auditReadOnly = client.delete("/admin/api/audit-logs") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                header("X-Request-Id", "delete-audit-request")
            }
            assertEquals(HttpStatusCode.MethodNotAllowed, auditReadOnly.status)

            val auditResponse = client.get("/admin/api/audit-logs?limit=100") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, auditResponse.status, auditResponse.bodyAsText())
            assertEquals("no-store", auditResponse.headers["Cache-Control"])
            val audits = Json.parseToJsonElement(auditResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
            val rotateAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "rotate-key-request"
            }
            assertEquals("admin", rotateAudit["actor"]!!.jsonPrimitive.content)
            assertEquals("client_key.rotate", rotateAudit["action"]!!.jsonPrimitive.content)
            assertEquals("success", rotateAudit["result"]!!.jsonPrimitive.content, rotateAudit.toString())
            assertEquals("200", rotateAudit["statusCode"]!!.jsonPrimitive.content, rotateAudit.toString())

            val missingCsrfAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "rotate-missing-csrf"
            }
            assertEquals("failure", missingCsrfAudit["result"]!!.jsonPrimitive.content)
            assertEquals("csrf_missing", missingCsrfAudit["errorCode"]!!.jsonPrimitive.content)
            assertEquals("admin", missingCsrfAudit["actor"]!!.jsonPrimitive.content)

            val invalidCsrfAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "rotate-invalid-csrf"
            }
            assertEquals("csrf_invalid", invalidCsrfAudit["errorCode"]!!.jsonPrimitive.content)
            assertTrue(
                audits.any {
                    it["action"]!!.jsonPrimitive.content == "client_key.revoke" &&
                        it["requestId"]!!.jsonPrimitive.content == "revoke-key-request"
                },
            )
            assertTrue(
                audits.any {
                    it["action"]!!.jsonPrimitive.content == "client_key.create" &&
                        it["requestId"]!!.jsonPrimitive.content == "create-key-request"
                },
            )
            assertTrue(
                audits.any {
                    it["action"]!!.jsonPrimitive.content == "admin.login" &&
                        it["actor"]!!.jsonPrimitive.content == "admin"
                },
            )
            assertTrue(
                audits.none {
                    it["requestId"]!!.jsonPrimitive.content == "unmatched-no-session"
                },
            )
            val failedLoginAudit = audits.single {
                it["requestId"]!!.jsonPrimitive.content == "failed-login-request"
            }
            assertNull(failedLoginAudit["actor"])
            assertEquals("failure", failedLoginAudit["result"]!!.jsonPrimitive.content)
            assertEquals("invalid_credentials", failedLoginAudit["errorCode"]!!.jsonPrimitive.content)
            listOf("rotate-no-session", "revoke-no-session").forEach { requestId ->
                val unauthenticatedAudit = audits.single {
                    it["requestId"]!!.jsonPrimitive.content == requestId
                }
                assertNull(unauthenticatedAudit["actor"])
                assertEquals("failure", unauthenticatedAudit["result"]!!.jsonPrimitive.content)
                assertEquals("admin_session_missing", unauthenticatedAudit["errorCode"]!!.jsonPrimitive.content)
            }

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select concat_ws('|', actor, action, method, path, request_id, source_ip, error_code) from admin_audit_logs",
                    ).use { result ->
                        while (result.next()) {
                            val row = result.getString(1)
                            assertTrue(!row.contains(oldKey), "audit row leaked old key: $row")
                            assertTrue(!row.contains(newKey), "audit row leaked rotated key: $row")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `admin audit filters apply with cursor pagination`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, _) = client.loginAdmin()

            app.adminAuditRepository.record(
                actor = "filter-user", action = "channel.update", method = "PUT", path = "/channels/1",
                statusCode = 200, requestId = "audit-filter-success-old", sourceIp = null, errorCode = null,
            )
            app.adminAuditRepository.record(
                actor = "filter-user", action = "channel.update", method = "PUT", path = "/channels/1",
                statusCode = 400, requestId = "audit-filter-failure", sourceIp = null, errorCode = "invalid_channel",
            )
            app.adminAuditRepository.record(
                actor = "other-user", action = "channel.update", method = "PUT", path = "/channels/1",
                statusCode = 200, requestId = "audit-filter-other-actor", sourceIp = null, errorCode = null,
            )
            app.adminAuditRepository.record(
                actor = "filter-user", action = "channel.update", method = "PUT", path = "/channels/1",
                statusCode = 200, requestId = "audit-filter-success-new", sourceIp = null, errorCode = null,
            )

            val query = "limit=1&from=0&to=${System.currentTimeMillis() + 10_000}&actor=filter-user&action=channel.update&result=success"
            val firstResponse = client.get("/admin/api/audit-logs?$query") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, firstResponse.status, firstResponse.bodyAsText())
            val first = Json.parseToJsonElement(firstResponse.bodyAsText()).jsonArray.single().jsonObject
            assertEquals("audit-filter-success-new", first["requestId"]!!.jsonPrimitive.content)

            val secondResponse = client.get("/admin/api/audit-logs?$query&beforeId=${first["id"]!!.jsonPrimitive.content}") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, secondResponse.status, secondResponse.bodyAsText())
            val second = Json.parseToJsonElement(secondResponse.bodyAsText()).jsonArray.single().jsonObject
            assertEquals("audit-filter-success-old", second["requestId"]!!.jsonPrimitive.content)

            val invalidRange = client.get("/admin/api/audit-logs?from=10&to=10") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.BadRequest, invalidRange.status)
            for (invalidTime in listOf("from=abc", "to=abc")) {
                val invalidValue = client.get("/admin/api/audit-logs?$invalidTime") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
                assertEquals(HttpStatusCode.BadRequest, invalidValue.status)
            }
        }
    }

    @Test
    fun `admin audit tolerates malformed details without dropping records`() = runBlocking {
        val created = app.adminAuditRepository.record(
            actor = "system",
            action = "test.malformed",
            method = "AUTO",
            path = "/test",
            statusCode = 200,
            requestId = "malformed-details",
            sourceIp = null,
            errorCode = null,
        )
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update admin_audit_logs set details = ? where id = ?").use { statement ->
                statement.setString(1, "{not-json")
                statement.setLong(2, created.id)
                statement.executeUpdate()
            }
        }
        val record = app.adminAuditRepository.list(10).single { it.requestId == "malformed-details" }
        assertNull(record.details)
        assertEquals("test.malformed", record.action)
    }

    @Test
    fun `admin discovers upstream models before saving a channel`() = runBlocking {
        val channel = createChannelWithBaseUrl("discover", "${fake.baseUrl}/ok/v1").let { created ->
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

            val withExplicitKey = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}"}""")
            }
            assertEquals(HttpStatusCode.OK, withExplicitKey.status)
            assertTrue(withExplicitKey.bodyAsText().contains("gpt-4o-mini"))

            val mixedCredential = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}","credential":"upstream-secret"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, mixedCredential.status)
            assertTrue(mixedCredential.bodyAsText().contains("keyId 与 credential 不能同时提交"))

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

    @Test
    fun `migrations tolerate duplicate rows`() {
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
                        """
                        insert into model_prices (id, channel_id, upstream_model, currency, input_price_per_million, output_price_per_million, cached_input_price_per_million, created_at, updated_at)
                        values ('migration-price', 'migration-channel', 'duplicate-model', 'USD', 1, 2, null, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into model_prices (id, channel_id, upstream_model, currency, input_price_per_million, output_price_per_million, cached_input_price_per_million, created_at, updated_at)
                        values ('migration-price-old', 'migration-channel', 'duplicate-model ', 'USD', 9, 9, null, 1, 0)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "insert into request_logs (request_id, alias, status_code, total_ms, created_at) values ('legacy-request-log', 'legacy', 200, 1, 1)",
                    )
                }
            }

            // 先迁到 V19，构造一个关闭直连的渠道，验证 V20 会把它转为停用
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("19"))
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, credential_ciphertext, credential_nonce, credential_key_version, auth_type, enabled, expose_upstream_models, created_at, updated_at)
                        values ('alias-only-conversion', 'alias-only-conversion', 'https://example.com/v1', 'cipher', 'nonce', 1, 'bearer', true, false, 1, 1)
                        """.trimIndent(),
                    )
                }
            }

            // 迁到 V31，构造三种旧密钥验证 V32 回填规则
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("31"))
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, auth_type, enabled, created_at, updated_at)
                        values ('v32-channel', 'v32-channel', 'https://example.com/v1', 'bearer', true, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, auth_type, enabled, protocols, created_at, updated_at)
                        values ('v34-empty', 'v34-empty', 'https://example.com/v1', 'bearer', true, '', 1, 1),
                               ('v34-order', 'v34-order', 'https://example.com/v1', 'bearer', true, 'messages, responses,chat_completions,responses,unknown', 1, 1),
                               ('v34-unknown', 'v34-unknown', 'https://example.com/v1', 'bearer', true, 'unknown', 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channel_keys (id, channel_id, name, credential_ciphertext, credential_nonce, credential_key_version, enabled, auto_disabled_at, priority, created_at, updated_at)
                        values ('key-active', 'v32-channel', 'key-active', 'c', 'n', 1, true, null, 1, 1, 1),
                               ('key-auto-perm', 'v32-channel', 'key-auto-perm', 'c', 'n', 1, false, 1000, 1, 1, 1),
                               ('key-manual-dis', 'v32-channel', 'key-manual-dis', 'c', 'n', 1, false, null, 1, 1, 1)
                        """.trimIndent(),
                    )
                }
            }

            // 迁到 V53，构造一个仍关联两个手工渠道的 Antigravity 账号，验证 V54 不删除历史关联。
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("53"))
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into oauth_accounts (id, name, provider, refresh_token_ciphertext, refresh_token_nonce, refresh_token_key_version, created_at, updated_at)
                        values ('v54-account', 'v54-account', 'antigravity', 'cipher', 'nonce', 1, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_account_channels (account_id, channel_id, created_at)
                        values ('v54-account', 'migration-channel', 1),
                               ('v54-account', 'alias-only-conversion', 1)
                        """.trimIndent(),
                    )
                }
            }

            // 先升到 V55，写入含 activity_disabled 的全局规则，再验证 V57 在缺少 Grox 渠道时不会删除它。
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("55"))
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("update key_auto_disable_settings set rules = ? where id = 1").use { statement ->
                    statement.setString(1, Json.encodeToString(KeyDisablePolicy.DEFAULT_RULES + KeyDisableRule(
                        id = "activity_disabled",
                        name = "活动已关闭或Key失效",
                        statusCodes = listOf(403),
                        bodyPatterns = listOf("DEEPSEEK_ACTIVITY_DISABLED"),
                        threshold = 2,
                        action = KeyDisableAction.PERMANENT,
                    )))
                    statement.executeUpdate()
                }
            }

            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from oauth_account_channels where account_id = 'v54-account'").use { result ->
                        assertTrue(result.next())
                        assertEquals(2, result.getInt(1), "V54 不应删除未托管账号的手工渠道关联")
                    }
                    statement.executeQuery("select count(*) from oauth_account_channels where account_id = 'v54-account' and managed").use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "历史渠道不是系统置备渠道")
                    }
                    statement.executeQuery("select provider_channel_pending from oauth_accounts where id = 'v54-account'").use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1), "迁移前的账号应标记为待搬迁")
                    }
                    statement.execute("update oauth_account_channels set managed = true where account_id = 'v54-account' and channel_id = 'migration-channel'")
                    val duplicateManaged = runCatching {
                        statement.execute("update oauth_account_channels set managed = true where account_id = 'v54-account' and channel_id = 'alias-only-conversion'")
                    }
                    assertTrue(duplicateManaged.isFailure, "一个账号最多只能有一条托管关联")
                    statement.executeQuery("select enabled from channels where id = 'alias-only-conversion'").use { result ->
                        assertTrue(result.next())
                        assertFalse(result.getBoolean(1), "原关闭直连的渠道应被转为停用")
                    }
                    statement.executeQuery("select enabled from channels where id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1), "默认直连的渠道应保持启用")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'channels' and column_name = 'expose_upstream_models'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "expose_upstream_models 列应已删除")
                    }
                    statement.executeQuery("select count(*) from channel_models").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.tables where table_name in ('model_aliases', 'alias_candidates')",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "V40 应删除别名两张表")
                    }
                    statement.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1").use { result ->
                        assertTrue(result.next())
                        assertEquals("59", result.getString(1))
                    }
                    statement.executeQuery("select request_overrides, quota_settings, quota_credential_ciphertext, quota_credential_nonce, protocol_auth_types from channels where id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertEquals("{}", result.getString(1), "历史渠道应默认继承全局请求配置")
                        assertEquals("{}", result.getString(2), "历史渠道应默认关闭额度插件")
                        assertEquals("", result.getString(3), "历史渠道没有独立额度凭据")
                        assertEquals("", result.getString(4))
                        assertEquals("{}", result.getString(5), "历史渠道应默认继承渠道级鉴权方式")
                    }
                    statement.executeQuery("select enabled, auth_failure_threshold, failure_threshold, failure_window_minutes from key_auto_disable_settings where id = 1").use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1))
                        assertEquals(3, result.getInt(2))
                        assertEquals(5, result.getInt(3))
                        assertEquals(5, result.getInt(4))
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'channel_keys' and column_name in ('state_version', 'auto_disabled_at', 'auto_disable_reason', 'auto_disable_until')",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(4, result.getInt(1))
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'channel_keys' and column_name in ('state', 'enabled')",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V32 应新增 state 并删除 enabled")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'channel_keys' and column_name = 'probe_until'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V35 应新增 probe_until")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name in ('key_auto_disable_settings','channel_key_health') and column_name in ('rules','rule_counters')",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(2, result.getInt(1), "V33 应新增 rules 与 rule_counters 列")
                    }
                    statement.executeQuery(
                        "select id, state, auto_disable_until from channel_keys where id in ('key-active', 'key-auto-perm', 'key-manual-dis') order by id",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("key-active", result.getString(1))
                        assertEquals("ACTIVE", result.getString(2), "enabled=true 应回填为 ACTIVE")
                        assertNull(result.getObject(3))
                        assertTrue(result.next())
                        assertEquals("key-auto-perm", result.getString(1))
                        assertEquals("AUTO_PERMANENT", result.getString(2), "enabled=false 且 auto_disabled_at 非空应回填为 AUTO_PERMANENT")
                        assertNull(result.getObject(3))
                        assertTrue(result.next())
                        assertEquals("key-manual-dis", result.getString(1))
                        assertEquals("MANUAL_DISABLED", result.getString(2), "enabled=false 且 auto_disabled_at 为空应回填为 MANUAL_DISABLED")
                        assertNull(result.getObject(3))
                    }
                    statement.executeQuery(
                        "select state from channel_keys where channel_id = 'migration-channel'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("ACTIVE", result.getString(1), "启用密钥应回填为 ACTIVE")
                    }
                    statement.executeQuery(
                        "select name, credential_ciphertext from channel_keys where channel_id = 'migration-channel'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("默认密钥", result.getString(1))
                        assertEquals("cipher", result.getString(2), "V29 应迁移原渠道凭据")
                        assertFalse(result.next())
                    }
                    statement.executeQuery(
                        "select count(*) from channel_key_models km join channel_keys k on k.id = km.key_id where k.channel_id = 'migration-channel'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V29 应迁移原渠道模型授权")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'channels' and column_name like 'credential_%'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "V29 应删除渠道级凭据列")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.tables where table_name = 'model_test_settings'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V28 应创建 model_test_settings 表")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.tables where table_name = 'channel_key_rate_limits'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V31 应创建 channel_key_rate_limits 表")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.tables where table_name = 'model_price_versions'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "V27 应删除 model_price_versions 表")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.tables where table_name = 'model_default_price_versions'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "V27 应删除 model_default_price_versions 表")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'request_logs' and column_name = 'price_version'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1), "V27 应删除 request_logs.price_version 列")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'model_prices' and column_name = 'cached_input_write_price_per_million'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V25 应为 model_prices 增加 cached_input_write_price_per_million 列")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'model_default_prices' and column_name = 'pricing_mode'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V25 应为 model_default_prices 增加 pricing_mode 列")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'request_logs' and column_name = 'cached_write_tokens'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V25 应为 request_logs 增加 cached_write_tokens 列")
                    }
                    statement.executeQuery(
                        "select upstream_model, input_price_per_million from model_prices where channel_id = 'migration-channel'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("duplicate-model", result.getString(1), "V24 应清理 trim 后重复的价格并保留最近更新的一条")
                        assertEquals(0, result.getBigDecimal(2).compareTo(BigDecimal("1")))
                        assertFalse(result.next(), "同一渠道 trim 后只应保留一条价格")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'client_keys' and column_name = 'requests_per_minute'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'request_logs' and column_name = 'upstream_attempts'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery(
                        "select id, protocols from channels where id like 'v34-%' order by id",
                    ).use { result ->
                        val expected = mapOf(
                            "v34-empty" to "chat_completions",
                            "v34-order" to "chat_completions,responses,messages",
                            "v34-unknown" to "chat_completions",
                        )
                        expected.forEach { (id, protocols) ->
                            assertTrue(result.next())
                            assertEquals(id, result.getString(1))
                            assertEquals(protocols, result.getString(2), "V34 应按优先级规范化旧渠道协议")
                        }
                        assertFalse(result.next())
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'request_logs' and column_name = 'upstream_protocol'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V34 应新增 upstream_protocol 列")
                    }
                    statement.executeQuery(
                        "select count(*) from information_schema.columns where table_name = 'request_logs' and column_name = 'attempts_json'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V39 应新增 attempts_json 列")
                    }
                    statement.executeQuery(
                        "select attempts_json from request_logs where request_id = 'legacy-request-log'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("[]", result.getString(1), "历史日志的 attempts_json 应回填为空数组")
                    }
                    statement.executeQuery(
                        "select is_nullable from information_schema.columns where table_name = 'request_logs' and column_name = 'stream'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("YES", result.getString(1))
                    }
                    statement.executeQuery("select stream from request_logs where request_id = 'legacy-request-log'").use { result ->
                        assertTrue(result.next())
                        assertNull(result.getObject(1))
                    }
                    statement.executeQuery("select canonical_model_id, mapping_source from channel_models where channel_id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertNull(result.getString(1))
                        assertNull(result.getString(2))
                    }
                    // V57：库里没有名为 Grox 的渠道，第一步跳过，第二步必须同样保留全局规则。
                    statement.executeQuery(
                        "select key_disable_policy, key_disable_policy_revision from channels where id = 'migration-channel'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertNull(result.getString(1), "没有 Grox 渠道时不应写入任何覆盖策略")
                        assertEquals(0, result.getInt(2))
                    }
                    statement.executeQuery(
                        "select rules, revision from key_auto_disable_settings where id = 1",
                    ).use { result ->
                        assertTrue(result.next())
                        val rules = Json.decodeFromString<List<KeyDisableRule>>(result.getString(1))
                        assertEquals(6, rules.size, "Grox 未接管规则时全局规则必须原样保留")
                        val activity = rules.singleOrNull { it.id == "activity_disabled" }
                        assertNotNull(activity, "没有 Grox 渠道时不能把规则从全局删掉")
                        assertEquals(2, activity.threshold, "未搬迁时阈值不应被改写")
                        assertEquals(0L, result.getLong(2), "全局规则未变化时 revision 不应递增")
                    }
                    statement.executeQuery(
                        "select policy_scope from channel_key_health limit 1",
                    ).use { result ->
                        if (result.next()) assertEquals("global", result.getString(1))
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
    fun `v57 migration moves activity rule into existing grox channel`() {
        val databaseName = "migration_v57_${System.nanoTime()}"
        val migrationUrl = postgres.jdbcUrl.substringBefore("?").substringBeforeLast("/") + "/$databaseName"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("create database \"$databaseName\"") }
        }
        try {
            Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("55"))
                .load()
                .migrate()

            val activityRule = KeyDisableRule(
                id = "activity_disabled",
                name = "活动已关闭或Key失效",
                statusCodes = listOf(403),
                bodyPatterns = listOf("DEEPSEEK_ACTIVITY_DISABLED"),
                threshold = 2,
                action = KeyDisableAction.PERMANENT,
            )
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement("update key_auto_disable_settings set rules = ? where id = 1").use { statement ->
                    statement.setString(1, Json.encodeToString(KeyDisablePolicy.DEFAULT_RULES + activityRule))
                    statement.executeUpdate()
                }
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, auth_type, enabled, created_at, updated_at)
                        values ('v56-grox', 'Grox', 'https://grox.example.com/v1', 'bearer', true, 1, 1)
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
                    // Grox 覆盖策略接管了该规则，阈值收紧为 1，其余全局规则一并固化过去。
                    statement.executeQuery(
                        "select key_disable_policy, key_disable_policy_revision from channels where id = 'v56-grox'",
                    ).use { result ->
                        assertTrue(result.next())
                        val policy = Json.decodeFromString<KeyDisablePolicy>(result.getString(1))
                        assertTrue(policy.enabled)
                        assertEquals(6, policy.rules.size)
                        val moved = policy.rules.single { it.id == "activity_disabled" }
                        assertEquals(1, moved.threshold, "搬运后阈值应收紧为 1")
                        assertEquals(1L, result.getLong(2), "写入覆盖策略应记一个版本")
                    }
                    // 全局规则移除该条并递增 revision，使旧计数失效。
                    statement.executeQuery(
                        "select rules, revision from key_auto_disable_settings where id = 1",
                    ).use { result ->
                        assertTrue(result.next())
                        val rules = Json.decodeFromString<List<KeyDisableRule>>(result.getString(1))
                        assertEquals(5, rules.size)
                        assertNull(rules.singleOrNull { it.id == "activity_disabled" }, "规则已由 Grox 接管")
                        assertEquals(1L, result.getLong(2))
                    }
                    // 其它渠道未被写入覆盖策略，继续继承全局。
                    statement.executeQuery(
                        "select count(*) from channels where key_disable_policy is not null",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "只有 Grox 应获得覆盖策略")
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
    fun `admin can toggle request log cleanup and persists the setting`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val initial = client.get("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertFalse(
                Json.parseToJsonElement(initial.bodyAsText()).jsonObject["cleanupEnabled"]!!.jsonPrimitive.boolean,
            )
            assertFalse(app.logRetentionService.scheduledCleanupRunning)

            val enabled = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":true}""")
            }
            assertEquals(HttpStatusCode.OK, enabled.status)
            assertTrue(
                Json.parseToJsonElement(enabled.bodyAsText()).jsonObject["cleanupEnabled"]!!.jsonPrimitive.boolean,
            )
            assertTrue(app.logRetentionService.scheduledCleanupRunning)

            val disabled = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":false}""")
            }
            assertEquals(HttpStatusCode.OK, disabled.status)
            assertFalse(
                Json.parseToJsonElement(disabled.bodyAsText()).jsonObject["cleanupEnabled"]!!.jsonPrimitive.boolean,
            )
            assertFalse(app.logRetentionService.scheduledCleanupRunning)
            assertEquals(false, app.retentionSettingsRepository.loadCleanupEnabled())

            app.requestLogRepository.insert(
                requestLog(
                    requestId = "manual-cleanup-while-disabled",
                    createdAt = System.currentTimeMillis() - 31 * 86_400_000L,
                ),
            )
            val manualCleanup = client.post("/admin/api/request-log-retention/cleanup") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, manualCleanup.status)
            assertEquals(
                "1",
                Json.parseToJsonElement(manualCleanup.bodyAsText())
                    .jsonObject["deleted"]!!
                    .jsonPrimitive
                    .content,
            )
            assertFalse(app.logRetentionService.scheduledCleanupRunning)
        }
    }

    @Test
    fun `overlapping retention updates keep runtime and persisted state aligned`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            suspend fun setCleanup(enabled: Boolean) = client.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":$enabled}""")
            }

            val started = AtomicInteger(0)
            val results = coroutineScope {
                (0 until 10).map { index ->
                    async(Dispatchers.Default) {
                        started.incrementAndGet()
                        while (started.get() < 10) yield()
                        setCleanup(index % 2 == 0)
                    }
                }
            }
            results.forEach { result ->
                assertEquals(HttpStatusCode.OK, result.await().status)
            }

            val persistedAfterConcurrentBatch = app.retentionSettingsRepository.loadCleanupEnabled()
            assertEquals(
                app.logRetentionService.currentPolicy().cleanupEnabled,
                persistedAfterConcurrentBatch,
            )
            assertEquals(
                app.logRetentionService.scheduledCleanupRunning,
                persistedAfterConcurrentBatch,
            )

            assertEquals(HttpStatusCode.OK, setCleanup(true).status)

            val persisted = app.retentionSettingsRepository.loadCleanupEnabled()
            assertEquals(true, persisted)
            assertEquals(true, app.logRetentionService.currentPolicy().cleanupEnabled)
            assertEquals(app.logRetentionService.scheduledCleanupRunning, persisted)
        }
    }

    @Test
    fun `admin request logs expose id cursors for loading older pages`() = runBlocking {
        repeat(3) { index ->
            app.requestLogRepository.insert(
                requestLog(
                    requestId = "cursor-page-$index",
                    createdAt = 1_000L + index,
                ),
            )
        }

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, _) = client.loginAdmin()

            val firstPage = client.get("/admin/api/request-logs?limit=2") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, firstPage.status)
            val firstIds = Json.parseToJsonElement(firstPage.bodyAsText())
                .jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
            assertEquals(2, firstIds.size)
            assertEquals(firstIds.sortedDescending(), firstIds)

            val secondPage = client.get("/admin/api/request-logs?limit=2&beforeId=${firstIds.last()}") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, secondPage.status)
            val secondIds = Json.parseToJsonElement(secondPage.bodyAsText())
                .jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
            assertEquals(1, secondIds.size)
            assertTrue(secondIds.single() < firstIds.last())
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

    @Test
    fun `admin manages model default prices`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val created = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"deepseek-v4.1-flash","currency":"CNY","inputPricePerMillion":"7","outputPricePerMillion":"14","cachedInputPricePerMillion":"1"}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = createdJson["id"]!!.jsonPrimitive.content
            assertEquals("CNY", createdJson["currency"]!!.jsonPrimitive.content)

            val duplicate = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"deepseek-v4.1-flash","currency":"USD","inputPricePerMillion":"1","outputPricePerMillion":"2"}""",
                )
            }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)

            val listed = client.get("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(1, Json.parseToJsonElement(listed.bodyAsText()).jsonArray.size)

            val updated = client.put("/admin/api/model-default-prices/$id") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"deepseek-v4.1-flash","currency":"USD","inputPricePerMillion":"3","outputPricePerMillion":"4"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedJson = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
            assertEquals("3.00000000", updatedJson["inputPricePerMillion"]!!.jsonPrimitive.content)

            val deleted = client.delete("/admin/api/model-default-prices/$id") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertNull(app.modelDefaultPriceRepository.find("deepseek-v4.1-flash"))

            val perReq = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"dall-e-3","currency":"USD","pricingMode":"per_request","perRequestPrice":"0.04"}""",
                )
            }
            assertEquals(HttpStatusCode.Created, perReq.status)
            val perReqJson = Json.parseToJsonElement(perReq.bodyAsText()).jsonObject
            assertEquals("per_request", perReqJson["pricingMode"]!!.jsonPrimitive.content)
            assertEquals("0.04000000", perReqJson["perRequestPrice"]!!.jsonPrimitive.content)

            val perReqId = perReqJson["id"]!!.jsonPrimitive.content
            val tiered = client.put("/admin/api/model-default-prices/$perReqId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"dall-e-3","currency":"USD","pricingMode":"tiered","priceTiers":[{"upToTokens":128000,"inputPricePerMillion":"1","outputPricePerMillion":"2"},{"upToTokens":null,"inputPricePerMillion":"2","outputPricePerMillion":"4"}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, tiered.status)
            val tieredJson = Json.parseToJsonElement(tiered.bodyAsText()).jsonObject
            assertEquals("tiered", tieredJson["pricingMode"]!!.jsonPrimitive.content)
            assertEquals(2, tieredJson["priceTiers"]!!.jsonArray.size)

            val hugeLimit = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"overflow-test","currency":"USD","pricingMode":"tiered","priceTiers":[{"upToTokens":100000000001,"inputPricePerMillion":"1","outputPricePerMillion":"2"},{"upToTokens":null,"inputPricePerMillion":"2","outputPricePerMillion":"4"}]}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, hugeLimit.status)

            client.delete("/admin/api/model-default-prices/$perReqId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
        }
    }

    @Test
    fun `concurrent default price updates map unique conflicts to 409`() = runBlocking {
        suspend fun createDefaultPrice(modelId: String) = checkNotNull(
            app.modelDefaultPriceRepository.create(
                modelId = modelId,
                currency = "USD",
                inputPricePerMillion = BigDecimal.ONE,
                outputPricePerMillion = BigDecimal.ONE,
                cachedInputPricePerMillion = null,
            ),
        )

        val first = createDefaultPrice("race-first")
        val second = createDefaultPrice("race-second")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val ready = AtomicInteger(0)
            val responses = coroutineScope {
                listOf(first.id, second.id).map { id ->
                    async(Dispatchers.Default) {
                        ready.incrementAndGet()
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (ready.get() < 2 && System.nanoTime() < deadline) yield()
                        client.put("/admin/api/model-default-prices/$id") {
                            header(HttpHeaders.Cookie, sessionCookie)
                            header("X-CSRF-Token", csrf)
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"modelId":"race-target","currency":"USD","inputPricePerMillion":"2","outputPricePerMillion":"3"}""",
                            )
                        }
                    }
                }.map { it.await() }
            }

            assertTrue(responses.all { it.status == HttpStatusCode.BadRequest }, responses.map { it.status }.toString())
            assertTrue(responses.all { it.bodyAsText().contains("model_default_price_model_immutable") })
            assertEquals(0, app.modelDefaultPriceRepository.list().count { it.modelId == "race-target" })
        }
    }


    @Test
    fun `records request cost with per_request pricing and skips failed request`() = runBlocking {
        val okChannel = createChannel("ok-channel", "ok")
        val failChannel = createChannel("fail-channel", "500")
        exposeAsModel("req-chat-ok", okChannel to "gpt-4o-mini")
        exposeAsModel("req-chat-fail", failChannel to "gpt-4o-mini")
        app.modelPriceRepository.create(
            channelId = okChannel.id,
            upstreamModel = "gpt-4o-mini",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.llmgateway.domain.PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.05"),
        )
        app.modelPriceRepository.create(
            channelId = failChannel.id,
            upstreamModel = "gpt-4o-mini",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.llmgateway.domain.PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.05"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val okResponse = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"req-chat-ok","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, okResponse.status)
            val okLog = awaitRequestLog()
            assertEquals(0, okLog.costAmount?.compareTo(BigDecimal("0.05000000")))
            assertEquals("per_request", okLog.pricingMode.value)

            val failResponse = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"req-chat-fail","messages":[]}""")
            }
            assertEquals(HttpStatusCode.BadGateway, failResponse.status)
            awaitRequestLogCount(2)
            val failLog = app.requestLogRepository.list(10).first { it.alias == "req-chat-fail" }
            assertNull(failLog.costAmount)
        }
    }

    @Test
    fun `per_request pricing does not charge interrupted stream`() = runBlocking {
        val channel = createChannel("stream-disc", "disconnect")
        exposeAsModel("per-req-stream-disc", channel to "m1")
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = "m1",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.llmgateway.domain.PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.08"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"per-req-stream-disc","stream":true,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals("stream_interrupted", log.errorCode)
            assertNull(log.costAmount, "中断的流式请求不应收取按次费用")
        }
    }

    @Test
    fun `records request cost with tiered pricing`() = runBlocking {
        val channel = createChannel("tiered-channel", "ok")
        exposeAsModel("tiered-chat", channel to "gpt-4o-mini")
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = "gpt-4o-mini",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.llmgateway.domain.PricingMode.TIERED,
            priceTiers = listOf(
                dev.soven.llmgateway.domain.ModelPriceTier(
                    upToTokens = 5,
                    inputPricePerMillion = BigDecimal("1.0"),
                    outputPricePerMillion = BigDecimal("2.0"),
                ),
                dev.soven.llmgateway.domain.ModelPriceTier(
                    upToTokens = null,
                    inputPricePerMillion = BigDecimal("2.0"),
                    outputPricePerMillion = BigDecimal("4.0"),
                ),
            ),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"tiered-chat","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            // input 10 (5*1 + 5*2 = 15), output 3 (3*2 = 6) -> 21 / 1M = 0.00002100
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.00002100")))
            assertEquals("tiered", log.pricingMode.value)
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
    fun `probe lease is released when request conversion fails`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "probe-conversion-request",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("probe-conversion-request", channel to "claude-sonnet-4-5")
        val key = createClientKey()
        val now = System.currentTimeMillis()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "update channel_keys set state = 'AUTO_TEMPORARY', auto_disabled_at = ?, auto_disable_until = ? where channel_id = ?",
            ).use { statement ->
                statement.setLong(1, now - 2)
                statement.setLong(2, now - 1)
                statement.setString(3, channel.id)
                statement.executeUpdate()
            }
        }

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"model":"probe-conversion-request","messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}}]}]}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertTrue(fake.requests.isEmpty())
            val keyRow = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.AUTO_TEMPORARY, keyRow.state)
            assertNull(keyRow.probeUntil)
            assertTrue(app.keyAutoDisableRepository.isAvailable(keyRow, now = now))
        }
    }

    @Test
    fun `probe lease is released as success when response conversion fails`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "probe-conversion-response",
            baseUrl = fake.baseUrl + "/unsupported-response/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("probe-conversion-response", channel to "claude-sonnet-4-5")
        val key = createClientKey()
        val now = System.currentTimeMillis()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "update channel_keys set state = 'AUTO_TEMPORARY', auto_disabled_at = ?, auto_disable_until = ? where channel_id = ?",
            ).use { statement ->
                statement.setLong(1, now - 2)
                statement.setLong(2, now - 1)
                statement.setString(3, channel.id)
                statement.executeUpdate()
            }
        }

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"probe-conversion-response","messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals(1, fake.requests.size)
            val recovered = assertNotNull(app.channelRepository.find(channel.id)).keys.single()
            assertEquals(ChannelKeyState.ACTIVE, recovered.state)
            assertNull(recovered.probeUntil)
            assertNull(recovered.autoDisableUntil)
        }
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
    fun `X-Prompt-Cache-Key header fallback preserves affinity and body field wins`() = runBlocking {
        val channels = (1..4).map { index ->
            createChannelWithBaseUrl(
                name = "header-affinity-ch$index",
                baseUrl = fake.baseUrl + "/ok/v1",
                protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
                modelMappings = listOf(ChannelModelRecord("gpt-4o-mini")),
            )
        }
        exposeAsModel("header-affinity-model", *channels.map { it to "gpt-4o-mini" }.toTypedArray())
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            var sent = 0

            // awaitRequestLogCount 只查 list(10)，本轮请求数会超过 10，这里放宽读取上限。
            suspend fun awaitLogCount(count: Int, timeoutMs: Long = 5_000) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (app.requestLogRepository.list(500).size >= count) return
                    delay(50)
                }
                error("expected $count request logs within ${timeoutMs}ms")
            }

            suspend fun send(headerKey: String?, bodyKey: String?): String {
                val bodyKeyField = bodyKey?.let { """"prompt_cache_key": $it,""" } ?: ""
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    if (headerKey != null) header("X-Prompt-Cache-Key", headerKey)
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"header-affinity-model",$bodyKeyField"messages":[{"role":"user","content":"hi"}]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                sent += 1
                awaitLogCount(sent)
                return awaitRequestLog().channelName ?: error("请求日志缺少渠道名")
            }

            // 单次比对有 1/4 概率因两个不同 key 恰好命中同一渠道而漏检（4 个同档渠道），
            // 因此每个场景用 6 组不同 key 重复校验，漏检概率降到 (1/4)^6。
            (1..6).map { "affinity-key-$it" }.forEach { probe ->
                // 仅凭请求头即可建立亲和：相同 header 命中同一渠道
                val headerOnly = send(probe, null)
                assertEquals(
                    headerOnly,
                    send(probe, null),
                    "相同 X-Prompt-Cache-Key 应保持渠道路由亲和性（$probe）",
                )

                // 请求体空白串必须被过滤并回退到请求头，否则所有请求会吸附到同一渠道
                assertEquals(
                    headerOnly,
                    send(probe, "\"   \""),
                    "空白 prompt_cache_key 应回退到 X-Prompt-Cache-Key（$probe）",
                )

                // 请求体优先于请求头：换掉 header 不影响命中渠道
                val bodyFirst = send("ignored-header-a-$probe", "\"$probe\"")
                assertEquals(
                    bodyFirst,
                    send("ignored-header-b-$probe", "\"$probe\""),
                    "请求体 prompt_cache_key 应优先于请求头（$probe）",
                )
            }
        }
    }

    @Test
    fun `six explicit converters route non streaming requests and record actual protocols`() = runBlocking {
        assertSixConverterRoutes(stream = false)
    }

    @Test
    fun `six explicit converters route streaming requests and record actual protocols`() = runBlocking {
        assertSixConverterRoutes(stream = true)
    }

    private suspend fun assertSixConverterRoutes(stream: Boolean) {
        val protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES)
        protocols.forEach { protocol ->
            val upstreamModel = "upstream-"+protocol.value
            val channel = createChannelWithBaseUrl(
                name = "converter-"+protocol.value,
                baseUrl = fake.baseUrl + "/ok/v1",
                protocols = listOf(protocol),
                modelMappings = listOf(ChannelModelRecord(upstreamModel)),
            )
            exposeAsModel("converter-"+protocol.value, channel to upstreamModel)
        }
        val key = createClientKey()
        var count = 0
        testApplication {
            application { app.install(this) }
            protocols.forEach { inbound -> protocols.filter { it != inbound }.forEach { outbound ->
                val model = "converter-"+outbound.value
                val body = when (inbound) {
                    GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.MESSAGES ->
                        """{"model":"$model","stream":$stream,"max_tokens":32,"messages":[{"role":"user","content":"hi"}]}"""
                    GatewayProtocol.RESPONSES -> """{"model":"$model","stream":$stream,"max_output_tokens":32,"input":"hi"}"""
                    else -> error("No Gemini conversion")
                }
                val response = client.post("/v1"+inbound.endpointPath) {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                val responseBody = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, "$inbound → $outbound: $responseBody")
                if (stream || outbound != GatewayProtocol.RESPONSES) assertTrue(responseBody.contains("Hel"), responseBody)
                if (!stream) {
                    val returned = Json.parseToJsonElement(responseBody).jsonObject
                    assertEquals(model, returned["model"]!!.jsonPrimitive.content)
                    val contentField = when (inbound) {
                        GatewayProtocol.CHAT_COMPLETIONS -> "choices"
                        GatewayProtocol.MESSAGES -> "content"
                        GatewayProtocol.RESPONSES -> "output"
                        else -> error("No Gemini conversion")
                    }
                    assertTrue(returned[contentField] is kotlinx.serialization.json.JsonArray, responseBody)
                }
                if (stream) {
                    val terminal = when (inbound) {
                        GatewayProtocol.CHAT_COMPLETIONS -> "[DONE]"
                        GatewayProtocol.MESSAGES -> "message_stop"
                        GatewayProtocol.RESPONSES -> "response.completed"
                        else -> error("No Gemini conversion")
                    }
                    assertTrue(responseBody.contains(terminal), responseBody)
                }
                assertEquals("/ok/v1"+outbound.endpointPath, fake.requests.last().path)
                val upstreamBody = Json.parseToJsonElement(fake.requests.last().body).jsonObject
                assertEquals("upstream-"+outbound.value, upstreamBody["model"]!!.jsonPrimitive.content)
                awaitRequestLogCount(++count)
                val log = app.requestLogRepository.list(10).first { it.alias == model && it.protocol == inbound }
                assertEquals(stream, log.stream)
                assertEquals(outbound, log.upstreamProtocol)
                assertEquals(1, log.upstreamAttempts)
                assertEquals(1, log.attempts.size)
                assertEquals(200, log.attempts.single().statusCode)
                assertEquals(outbound, log.attempts.single().upstreamProtocol)
                assertNull(log.errorCode)
            } }
        }
    }

    @Test
    fun `records request cost with cache write rate for anthropic messages`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-cache-write",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-cache", channel to "claude-sonnet-4-5")
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = "claude-sonnet-4-5",
            currency = "USD",
            inputPricePerMillion = BigDecimal("1.0"),
            outputPricePerMillion = BigDecimal("2.0"),
            cachedInputPricePerMillion = BigDecimal("0.5"),
            cachedInputWritePricePerMillion = BigDecimal("1.5"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-cache","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            // input 15 (uncached 10, cachedRead 3, cachedWrite 2), output 4
            // cost = (10*1 + 3*0.5 + 2*1.5 + 4*2) / 1M = 22.5 / 1M = 0.00002250
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.00002250")))
            assertEquals(2L, log.cachedWriteTokens)
            assertEquals(0, log.cachedInputWritePricePerMillion?.compareTo(BigDecimal("1.5")))
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

    private suspend fun HttpClient.assertAdminRequestLogStream(requestId: String, expected: Boolean) {
        val (sessionCookie, csrf) = loginAdmin()
        val response = get("/admin/api/request-logs") {
            header(HttpHeaders.Cookie, sessionCookie)
            header("X-CSRF-Token", csrf)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val loggedStream = Json.parseToJsonElement(response.bodyAsText())
            .jsonArray
            .first { it.jsonObject["requestId"]?.jsonPrimitive?.content == requestId }
            .jsonObject["stream"]
            ?.jsonPrimitive
            ?.content
        assertEquals(expected.toString(), loggedStream)
    }

    private fun config(
        firstTokenTimeoutMs: Long = 2_000,
        idleTimeoutMs: Long = 2_000,
        totalTimeoutMs: Long = 5_000,
        corsOrigins: List<String> = emptyList(),
        maxAttempts: Int = 2,
        cooldownTransientBaseMs: Long = 3_600_000,
        cooldownRateLimitDefaultMs: Long = 60_000,
        cooldownRateLimitMaxMs: Long = 60_000,
        ttftSlowThresholdMs: Long = 600_000,
        ttftSlowStreak: Int = 100,
        keyAutoRecoveryIntervalMs: Long = 3_600_000,
    ): GatewayConfig = GatewayConfig(
        port = 0,
        databaseUrl = postgres.jdbcUrl,
        databaseUser = postgres.username,
        databasePassword = postgres.password,
        masterKey = ByteArray(32) { 7 },
        adminUsername = "admin",
        adminPassword = "test-password",
        corsOrigins = corsOrigins,
        requestBodyLimitBytes = 1024 * 1024,
        connectTimeoutMs = 1_000,
        firstTokenTimeoutMs = firstTokenTimeoutMs,
        idleTimeoutMs = idleTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
        maxAttempts = maxAttempts,
        drainTimeoutMs = 1_000,
        staticDir = Path.of("web/dist"),
        quotaPluginDir = quotaPluginDirectory,
        manxueEnabled = true,
        manxueApiBaseUrl = fake.baseUrl + "/manxue",
        manxueTimeoutMs = 5_000,
        iqDefaultJudge = "local",
        iqJudgeTimeoutMs = 5_000,
        iqJudgePluginDir = iqJudgePluginDirectory,
        iqVisionEnabled = true,
        iqVisionBaseUrl = fake.baseUrl + "/vision/v1",
        iqVisionModel = "vision-test",
        iqVisionTimeoutMs = 5_000,
        iqVisionRenderWidth = 512,
        cookieSecure = false,
        retention = RetentionPolicy(cleanupEnabled = false),
        cooldown = CooldownPolicy(
            transientBaseMs = cooldownTransientBaseMs,
            transientMaxMs = 3_600_000,
            rateLimitDefaultMs = cooldownRateLimitDefaultMs,
            rateLimitMaxMs = cooldownRateLimitMaxMs,
            authFailureMs = 600_000,
            ttftSlowThresholdMs = ttftSlowThresholdMs,
            ttftSlowStreak = ttftSlowStreak,
        ),
        keyAutoRecoveryIntervalMs = keyAutoRecoveryIntervalMs,
        upstreamHeaderRefreshEnabled = false,
        upstreamHeaderRefreshIntervalMs = 3_600_000,
        // 订阅账号的 token 刷新与额度查询都指向假上游，测试不访问真实 OAuth 端点。
        oauth = OAuthConfig(
            chatGpt = OAuthConfig().chatGpt.copy(
                tokenEndpoint = fake.baseUrl + "/oauth/token",
                usageUrl = fake.baseUrl + "/oauth/usage",
            ),
        ),
    )

    private fun requestLog(requestId: String, createdAt: Long) = RequestLogRecord(
        requestId = requestId,
        clientKeyId = null,
        clientKeyName = null,
        alias = "fast-chat",
        channelId = null,
        channelName = null,
        upstreamModel = null,
        protocol = GatewayProtocol.CHAT_COMPLETIONS,
        statusCode = 200,
        firstTokenMs = null,
        totalMs = 1,
        inputTokens = 1,
        outputTokens = 1,
        cachedTokens = null,
        reasoningTokens = null,
        reasoningEffort = null,
        totalTokens = 2,
        usageKnown = true,
        costAmount = null,
        costCurrency = null,
        inputPricePerMillion = null,
        outputPricePerMillion = null,
        cachedInputPricePerMillion = null,
        errorCode = null,
        failureStage = null,
        errorMessage = null,
        upstreamErrorCode = null,
        createdAt = createdAt,
    )

    private suspend fun awaitRequestLogCount(count: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (app.requestLogRepository.list(10).size >= count) return
            delay(50)
        }
        error("expected $count request logs within ${timeoutMs}ms")
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
    fun globalAndChannelOverridesApplyToDirectRoutes() = runBlocking {
        app.upstreamRequestSettingsRepository.save(UpstreamRequestOverrides(
            userAgent = "global-UA", headers = mapOf("X-Feature" to "global", "X-Remove" to "gone"),
            body = Json.parseToJsonElement("""{"store":null,"metadata":{"global":true,"remove":"old"}}""").jsonObject,
            protocols = mapOf("responses" to UpstreamProtocolOverrides(userAgent = "global-responses-UA")),
        ))
        val channel = createChannelWithBaseUrl(
            "override-channel", fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("m1", "public-model")),
            requestOverrides = UpstreamRequestOverrides(
                userAgent = "channel-UA", headers = mapOf("x-feature" to "channel", "x-remove" to null),
                body = Json.parseToJsonElement("""{"metadata":{"channel":true,"remove":null}}""").jsonObject,
                protocols = mapOf("messages" to UpstreamProtocolOverrides(
                    userAgent = "channel-messages-UA", headers = mapOf("anthropic-version" to "2023-06-01"),
                )),
            ),
        )
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            for (model in listOf("public-model")) {
                for (protocol in listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES)) {
                    val response = client.post("/v1" + assertNotNull(protocol.endpointPath)) {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        header("User-Agent", "caller-UA")
                        header("X-Caller-Private", "do not forward")
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"$model","messages":[],"input":"hi","max_tokens":8,"store":true,"stream":false}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    response.bodyAsText()
                    val sent = fake.requests.last()
                    assertEquals(listOf(if (protocol == GatewayProtocol.MESSAGES) "channel-messages-UA" else "channel-UA"), sent.requestHeaders["user-agent"])
                    assertEquals(listOf("channel"), sent.requestHeaders["x-feature"])
                    assertNull(sent.requestHeaders["x-remove"])
                    assertNull(sent.requestHeaders["x-caller-private"])
                    assertEquals("Bearer upstream-secret", sent.authorization)
                    val body = Json.parseToJsonElement(sent.body).jsonObject
                    assertEquals("m1", body["model"]!!.jsonPrimitive.content)
                    assertFalse(body.containsKey("store"))
                    assertEquals("""{"global":true,"channel":true}""", body["metadata"].toString())
                    assertEquals("false", body["stream"]!!.jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    @Tag("upstream-request-overrides")
    fun channelOverridesDoNotLeakIntoTheNextAttempt() = runBlocking {
        app.upstreamRequestSettingsRepository.save(UpstreamRequestOverrides(
            headers = mapOf("X-Shared" to "yes"), body = Json.parseToJsonElement("""{"metadata":{"shared":true}}""").jsonObject,
        ))
        val bad = createChannelWithBaseUrl(
            "a-bad", fake.baseUrl + "/500/v1", modelMappings = mappingsOf("m1"), priority = 0,
            requestOverrides = UpstreamRequestOverrides(userAgent = "bad-UA", body = Json.parseToJsonElement("""{"metadata":{"bad":true}}""").jsonObject),
        )
        val good = createChannelWithBaseUrl(
            "b-good", fake.baseUrl + "/ok/v1", modelMappings = mappingsOf("m2"), priority = 10,
            requestOverrides = UpstreamRequestOverrides(userAgent = "good-UA", body = Json.parseToJsonElement("""{"metadata":{"good":true}}""").jsonObject),
        )
        exposeAsModel("retry-model", bad to "m1", good to "m2")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"retry-model","messages":[],"stream":true}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("[DONE]"))
            assertEquals(2, fake.requests.size)
            val sent = fake.requests.last()
            assertEquals(listOf("good-UA"), sent.requestHeaders["user-agent"])
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals("m2", body["model"]!!.jsonPrimitive.content)
            assertEquals("""{"shared":true,"good":true}""", body["metadata"].toString())
        }
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogSnapshotIsStableAcrossFailoverAttempts() = runBlocking {
        val repository = app.upstreamHeaderCatalogRepository
        val codex = UpstreamHeaderProfiles.defaults.first { it.id == "codex" }
        val oldProfile = codex.copy(
            version = "9.9.1",
            headers = codex.headers + mapOf(
                "User-Agent" to "codex_cli_rs/9.9.1 (Linux; x86_64)",
                "version" to "9.9.1",
            ),
        )
        val newProfile = oldProfile.copy(
            version = "9.9.2",
            headers = oldProfile.headers + mapOf(
                "User-Agent" to "codex_cli_rs/9.9.2 (Linux; x86_64)",
                "version" to "9.9.2",
            ),
        )
        repository.saveRefresh(
            UpstreamHeaderProfiles.defaults.map { if (it.id == "codex") oldProfile else it },
            checkedAt = 1_000,
            successAt = 1_000,
            lastError = null,
        )
        app.upstreamHeaderCatalogService.initialize()
        val bad = createChannelWithBaseUrl(
            "snapshot-bad", fake.baseUrl + "/hold-500/v1", modelMappings = mappingsOf("m1"),
        )
        val good = createChannelWithBaseUrl(
            "snapshot-good", fake.baseUrl + "/ok/v1", modelMappings = mappingsOf("m2"),
        )
        exposeAsModel("snapshot-model", bad to "m1", good to "m2")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val release = fake.holdChatRequests()
            val response = async {
                client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"snapshot-model","messages":[]}""")
                }
            }
            try {
                awaitCondition("first attempt reaches the upstream") { fake.requests.size == 1 }
                repository.saveRefresh(
                    UpstreamHeaderProfiles.defaults.map { if (it.id == "codex") newProfile else it },
                    checkedAt = 2_000,
                    successAt = 2_000,
                    lastError = null,
                )
                app.upstreamHeaderCatalogService.initialize()
                release.complete(Unit)
                val result = response.await()
                assertEquals(HttpStatusCode.OK, result.status)
                result.bodyAsText()
                assertEquals(2, fake.requests.size)
                assertEquals(
                    listOf(
                        "codex_cli_rs/9.9.1 (Linux; x86_64)",
                        "codex_cli_rs/9.9.1 (Linux; x86_64)",
                    ),
                    fake.requests.map { it.requestHeaders["user-agent"]?.single() },
                )
            } finally {
                release.complete(Unit)
                response.cancel()
                fake.clearChatHold()
            }
        }
    }

    @Test
    @Tag("upstream-request-overrides")
    fun globalOverridesApiValidatesPersistsAndAuditsWithoutValues() = runBlocking {
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/upstream-request-settings").status)
            val missingCsrf = client.put("/admin/api/upstream-request-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                contentType(ContentType.Application.Json)
                setBody("""{"overrides":{"userAgent":"private-UA"}}""")
            }
            assertEquals(HttpStatusCode.Forbidden, missingCsrf.status)
            val saved = client.put("/admin/api/upstream-request-settings") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"overrides":{"userAgent":"private-UA","headers":{"X-Feature":"yes"}}}""")
            }
            assertEquals(HttpStatusCode.OK, saved.status)
            val settings = Json.parseToJsonElement(saved.bodyAsText()).jsonObject
            assertEquals(4, settings["profiles"]!!.jsonArray.size)
            val catalog = settings["headerCatalog"]!!.jsonObject
            assertEquals(4, catalog["profiles"]!!.jsonArray.size)
            assertFalse(catalog["autoRefreshEnabled"]!!.jsonPrimitive.boolean)
            app.upstreamHeaderCatalogRepository.saveRefresh(
                UpstreamHeaderProfiles.defaults,
                checkedAt = 1_000,
                successAt = 1_000,
                lastError = null,
            )
            app.upstreamHeaderCatalogService.initialize()
            val pinned = client.put("/admin/api/upstream-request-settings/header-profiles/claude/pin") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"version":"2.1.312"}""")
            }
            assertEquals(HttpStatusCode.OK, pinned.status)
            assertEquals(
                "2.1.312",
                Json.parseToJsonElement(pinned.bodyAsText()).jsonObject["headerCatalog"]!!
                    .jsonObject["pins"]!!.jsonObject["claude"]!!.jsonPrimitive.content,
            )
            val unpinned = client.delete("/admin/api/upstream-request-settings/header-profiles/claude/pin") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
            }
            assertEquals(HttpStatusCode.OK, unpinned.status)
            assertFalse(
                Json.parseToJsonElement(unpinned.bodyAsText()).jsonObject["headerCatalog"]!!
                    .jsonObject["pins"]!!.jsonObject.containsKey("claude"),
            )
            assertEquals("private-UA", app.upstreamRequestSettingsRepository.load().overrides.userAgent)
            val invalidRules = listOf(
                """{"headers":{"Authorization":"plaintext"}}""",
                """{"userAgent":"bad\r\nX-Injected: true"}""",
                """{"body":{"stream":true}}""",
                """{"protocols":{"invalid":{"userAgent":"x"}}}""",
            )
            for (rules in invalidRules) {
                val invalid = client.put("/admin/api/upstream-request-settings") {
                    header(HttpHeaders.Cookie, "gw_session=" + session.token)
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"overrides":$rules}""")
                }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertTrue(invalid.bodyAsText().contains("invalid_request_overrides"))
            }
            assertEquals("private-UA", app.upstreamRequestSettingsRepository.load().overrides.userAgent)
            val audit = client.get("/admin/api/audit-logs") { header(HttpHeaders.Cookie, "gw_session=" + session.token) }.bodyAsText()
            assertTrue(audit.contains("upstream_request_settings.update"))
            assertFalse(audit.contains("private-UA"))
            assertFalse(audit.contains("plaintext"))
        }
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogRefreshesPersistsAndPinsVersions() = runBlocking {
        var clock = 1_000L
        var claudeVersion = "2.1.400"
        var codexVersion = "0.170.0"
        var claudeFailure = false
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        val sources = listOf(
            HeaderProfileVersionSource("claude", "test:npm") {
                if (claudeFailure) error("registry unavailable") else claudeVersion
            },
            HeaderProfileVersionSource("codex", "test:github") { codexVersion },
            HeaderProfileVersionSource("google", "test:npm") { error("registry unavailable") },
        )
        fun service() = UpstreamHeaderCatalogService(
            httpClient = app.httpClient,
            repository = repository,
            refreshIntervalMs = 60_000,
            autoRefreshEnabled = false,
            versionSources = sources,
            now = { clock },
        )

        val catalog = service()
        catalog.initialize()
        val first = catalog.refreshNow()
        assertEquals("2.1.400", first.profiles.first { it.id == "claude" }.version)
        assertEquals("0.170.0", first.profiles.first { it.id == "codex" }.version)
        assertEquals(UpstreamHeaderProfiles.defaults.first { it.id == "google" }.version, first.profiles.first { it.id == "google" }.version)
        assertTrue(first.lastError!!.contains("google"))

        catalog.pin("claude", "2.1.400")
        clock = 2_000L
        claudeVersion = "2.1.401"
        codexVersion = "0.171.0"
        val refreshed = catalog.refreshNow()
        assertEquals("2.1.400", refreshed.profiles.first { it.id == "claude" }.version)
        assertEquals("2.1.400", refreshed.pins["claude"])
        assertEquals("2.1.401", refreshed.history["claude"]!!.first().version)
        assertEquals("0.171.0", refreshed.profiles.first { it.id == "codex" }.version)

        clock = 3_000L
        claudeFailure = true
        val failedPinnedRefresh = catalog.refreshNow()
        assertEquals("2.1.400", failedPinnedRefresh.profiles.first { it.id == "claude" }.version)

        val reloaded = service()
        reloaded.initialize()
        assertEquals("2.1.400", reloaded.profiles().first { it.id == "claude" }.version)
        val unpinned = reloaded.unpin("claude")
        assertEquals("2.1.401", unpinned.profiles.first { it.id == "claude" }.version)
        assertEquals(2, unpinned.history["claude"]!!.size)
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogConcurrentUpsertsAndPinnedVersionSurvivePruning() = runBlocking {
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        val base = UpstreamHeaderProfiles.defaults.first { it.id == "claude" }
        val first = base.copy(version = "1.0.0")
        coroutineScope {
            (1..8).map { index ->
                async(Dispatchers.IO) {
                    repository.saveRefresh(listOf(first), index.toLong(), index.toLong(), null)
                }
            }.awaitAll()
        }
        repository.pin("claude", first.version)
        repeat(25) { index ->
            repository.saveRefresh(
                listOf(base.copy(version = "2.0." + index)),
                checkedAt = 100L + index,
                successAt = 100L + index,
                lastError = null,
            )
        }
        val loaded = repository.load(UpstreamHeaderProfiles.defaults)
        assertEquals(first.version, loaded.pins["claude"])
        assertTrue(loaded.history["claude"]!!.any { it.version == first.version })
        val deletePinned = runCatching {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.prepareStatement(
                    "delete from upstream_header_profile_versions where profile_id = ? and version = ?",
                ).use { statement ->
                    statement.setString(1, "claude")
                    statement.setString(2, first.version)
                    statement.executeUpdate()
                }
            }
        }
        assertTrue(deletePinned.isFailure)
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogRejectsInvalidPersistedProfilesAndRedactsStoredErrors() = runBlocking {
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        repository.saveRefresh(UpstreamHeaderProfiles.defaults, 1_000, 1_000, null)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """insert into upstream_header_profile_versions
                       (profile_id, version, name, headers, source, fetched_at)
                       values ('claude', '9.9.9', 'Poisoned', '{"Authorization":"secret"}', 'test', 9999)""",
                )
                statement.executeUpdate(
                    """insert into upstream_header_profile_versions
                       (profile_id, version, name, headers, source, fetched_at)
                       values ('claude', '9.9.8', 'MalformedJson', '{invalid json}', 'test', 9998)""",
                )
                statement.executeUpdate(
                    """insert into upstream_header_profile_versions
                       (profile_id, version, name, headers, source, fetched_at)
                       values ('codex', 'invalid version!', 'BadVersion', '{}', 'test', 9997)""",
                )
                statement.executeUpdate(
                    """update upstream_header_catalog_state
                       set last_error = 'https://user:secret@internal.example/path?token=secret' where id = 1""",
                )
            }
        }
        val service = UpstreamHeaderCatalogService(
            httpClient = app.httpClient,
            repository = repository,
            refreshIntervalMs = 60_000,
            autoRefreshEnabled = false,
            versionSources = emptyList(),
        )
        service.initialize()
        val snapshot = service.snapshot()
        assertEquals(
            UpstreamHeaderProfiles.defaults.first { it.id == "claude" }.version,
            snapshot.profiles.first { it.id == "claude" }.version,
        )
        assertEquals(
            UpstreamHeaderProfiles.defaults.first { it.id == "codex" }.version,
            snapshot.profiles.first { it.id == "codex" }.version,
        )
        assertTrue(snapshot.lastError!!.contains("catalog:invalid_persisted_profile"))
        assertTrue(snapshot.lastError.contains("catalog:stored_error_redacted"))
        assertFalse(snapshot.lastError.contains("secret"))
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogRefreshErrorsExposeOnlyStableCategories() = runBlocking {
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        val service = UpstreamHeaderCatalogService(
            httpClient = app.httpClient,
            repository = repository,
            refreshIntervalMs = 60_000,
            autoRefreshEnabled = false,
            versionSources = listOf(
                HeaderProfileVersionSource("claude", "test") {
                    error("https://user:secret@internal.example/path?token=secret")
                },
            ),
        )
        service.initialize()
        val snapshot = service.refreshNow()
        assertEquals("claude:invalid_response", snapshot.lastError)
        assertEquals(
            UpstreamHeaderProfiles.defaults.map { it.id to it.version },
            snapshot.profiles.map { it.id to it.version },
        )
        assertFalse(snapshot.lastError!!.contains("secret"))
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogInvalidSourceVersionDoesNotAbortOtherSources() = runBlocking {
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        val service = UpstreamHeaderCatalogService(
            httpClient = app.httpClient,
            repository = repository,
            refreshIntervalMs = 60_000,
            autoRefreshEnabled = false,
            versionSources = listOf(
                HeaderProfileVersionSource("claude", "test") { "not a version!" },
                HeaderProfileVersionSource("codex", "test") { "0.171.0" },
            ),
        )
        service.initialize()
        val snapshot = service.refreshNow()
        assertEquals(
            UpstreamHeaderProfiles.defaults.first { it.id == "claude" }.version,
            snapshot.profiles.first { it.id == "claude" }.version,
        )
        assertEquals("0.171.0", snapshot.profiles.first { it.id == "codex" }.version)
        assertTrue(snapshot.lastError!!.contains("claude:invalid_response"))
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogVersionIdentityCannotBeOverwritten() = runBlocking {
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        val original = UpstreamHeaderProfiles.defaults.first { it.id == "claude" }
        repository.saveRefresh(UpstreamHeaderProfiles.defaults, 1_000, 1_000, null)
        repository.pin("claude", original.version)
        val changed = original.copy(headers = original.headers + ("X-Changed" to "yes"))
        val partial = repository.saveRefresh(
            UpstreamHeaderProfiles.defaults.map { if (it.id == "claude") changed else it },
            2_000,
            2_000,
            null,
        )
        assertEquals(original.headers, partial.profiles.first { it.id == "claude" }.headers)
        assertTrue(partial.lastError!!.contains("claude:version_conflict"))
        assertEquals(2_000, partial.successAt)

        val allConflict = repository.saveRefresh(listOf(changed), 3_000, 3_000, null)
        assertEquals(original.headers, allConflict.profiles.first { it.id == "claude" }.headers)
        assertTrue(allConflict.lastError!!.contains("claude:version_conflict"))
        assertEquals(2_000, allConflict.successAt)
        assertEquals(3_000, allConflict.checkedAt)
    }

    @Test
    @Tag("upstream-request-overrides")
    fun headerCatalogRefreshPropagatesCancellationAndResetsRefreshingState() = runBlocking {
        val repository = UpstreamHeaderCatalogRepository(app.databaseFactory.database)
        val service = UpstreamHeaderCatalogService(
            httpClient = app.httpClient,
            repository = repository,
            refreshIntervalMs = 60_000,
            autoRefreshEnabled = false,
            versionSources = listOf(
                HeaderProfileVersionSource("claude", "test") {
                    throw CancellationException("simulated cancellation")
                },
            ),
        )
        service.initialize()
        assertFailsWith<CancellationException> {
            service.refreshNow()
        }
        assertFalse(service.snapshot().refreshing)
    }

    @Test
    @Tag("upstream-request-overrides")
    fun legacyChannelUpdatesKeepOverridesAndEmptyObjectResetsThem() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "legacy", fake.baseUrl + "/ok/v1", requestOverrides = UpstreamRequestOverrides(userAgent = "kept-UA"),
        )
        val session = app.sessions.create("admin")
        val baseUrl = channel.baseUrl
        testApplication {
            application { app.install(this) }
            val legacyUpdate = client.put("/admin/api/channels/" + channel.id) {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"legacy","baseUrl":"$baseUrl","priority":2}""")
            }
            assertEquals(HttpStatusCode.OK, legacyUpdate.status)
            assertEquals("kept-UA", app.channelRepository.find(channel.id)!!.requestOverrides.userAgent)
            val reset = client.put("/admin/api/channels/" + channel.id) {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"legacy","baseUrl":"$baseUrl","requestOverrides":{}}""")
            }
            assertEquals(HttpStatusCode.OK, reset.status)
            assertEquals(UpstreamRequestOverrides(), app.channelRepository.find(channel.id)!!.requestOverrides)
        }
    }

    @Test
    @Tag("upstream-request-overrides")
    fun discoveryTestsAndProbesUseOverridesWithBoundedOutput() = runBlocking {
        app.upstreamRequestSettingsRepository.save(UpstreamRequestOverrides(
            userAgent = "global-UA", body = Json.parseToJsonElement("""{"temperature":0.4,"max_tokens":10000}""").jsonObject,
            protocols = mapOf("chat_completions" to UpstreamProtocolOverrides(
                body = Json.parseToJsonElement("""{"max_completion_tokens":10000}""").jsonObject,
            )),
        ))
        val channel = createChannelWithBaseUrl(
            "admin-overrides", fake.baseUrl + "/ok/v1", requestOverrides = UpstreamRequestOverrides(userAgent = "channel-UA"),
        )
        val baseUrl = channel.baseUrl
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val models = client.get("/admin/api/channels/" + channel.id + "/models") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
            }
            assertEquals(HttpStatusCode.OK, models.status)
            assertEquals(listOf("channel-UA"), fake.modelRequests.last().requestHeaders["user-agent"])
            val discover = client.post("/admin/api/channels/model-discovery") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"baseUrl":"$baseUrl","channelId":"${channel.id}","requestOverrides":{"userAgent":"draft-UA"}}""")
            }
            assertEquals(HttpStatusCode.OK, discover.status)
            assertEquals(listOf("draft-UA"), fake.modelRequests.last().requestHeaders["user-agent"])
            val test = client.post("/admin/api/channels/" + channel.id + "/model-test") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1","protocol":"chat_completions"}""")
            }
            assertEquals(HttpStatusCode.OK, test.status)
            assertEquals(listOf("channel-UA"), fake.requests.last().requestHeaders["user-agent"])
            val testBody = Json.parseToJsonElement(fake.requests.last().body).jsonObject
            assertEquals("128", testBody["max_completion_tokens"]!!.jsonPrimitive.content)
            assertFalse(testBody.containsKey("max_tokens"), "使用 max_completion_tokens 时不应再发送旧参数")
            assertEquals("0.4", testBody["temperature"]!!.jsonPrimitive.content)
            fake.requests.clear()
            val probe = client.post("/admin/api/channels/" + channel.id + "/probe-protocols") {
                header(HttpHeaders.Cookie, "gw_session=" + session.token)
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1"}""")
            }
            assertEquals(HttpStatusCode.OK, probe.status)
            assertEquals(4, fake.requests.size)
            fake.requests.forEach { assertEquals(listOf("channel-UA"), it.requestHeaders["user-agent"]) }
            val chatBody = Json.parseToJsonElement(fake.requests.first().body).jsonObject
            assertEquals("1", chatBody["max_completion_tokens"]!!.jsonPrimitive.content)
            assertFalse(chatBody.containsKey("max_tokens"))
            val messagesBody = Json.parseToJsonElement(fake.requests.first { it.uri?.endsWith("/messages") == true }.body).jsonObject
            assertEquals("1", messagesBody["max_tokens"]!!.jsonPrimitive.content)
            assertTrue(app.requestLogRepository.list(10).isEmpty())
        }
    }

    @Test
    @Tag("upstream-request-overrides")
    fun globalRequestSettingsCacheLoadsAndPublishesPersistedSnapshots() = runBlocking {
        val repository = app.upstreamRequestSettingsRepository
        val snapshots = coroutineScope { (1..10).map { async(Dispatchers.IO) { repository.load() } }.awaitAll() }
        assertEquals(UpstreamRequestOverrides(), snapshots.first().overrides)
        snapshots.forEach { assertSame(snapshots.first(), it) }

        val headers = mutableMapOf<String, String?>("X-Test" to "saved")
        val saved = repository.save(UpstreamRequestOverrides(userAgent = "cached-UA", headers = headers))
        headers["X-Test"] = "unsaved"
        assertSame(saved, repository.load())
        assertEquals("saved", repository.load().overrides.headers["X-Test"])
        assertEquals(saved, UpstreamRequestSettingsRepository(app.databaseFactory.database).load())

        val cleared = repository.save(UpstreamRequestOverrides())
        assertSame(cleared, repository.load())
        assertEquals(UpstreamRequestOverrides(), cleared.overrides)
        assertEquals(cleared, UpstreamRequestSettingsRepository(app.databaseFactory.database).load())
    }

    @Test
    @Tag("upstream-request-overrides")
    fun failedGlobalSettingsSavePreservesTheCachedSnapshot() = runBlocking {
        val repository = app.upstreamRequestSettingsRepository
        val saved = repository.save(UpstreamRequestOverrides(userAgent = "working-UA"))
        app.databaseFactory.close()
        assertSame(saved, repository.load(), "缓存命中不应访问数据库")
        assertFailsWith<java.sql.SQLException> { repository.save(UpstreamRequestOverrides(userAgent = "failed-UA")) }
        assertSame(saved, repository.load(), "失败的写入不应替换快照")
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

    @Test
    @Tag("upstream-request-overrides")
    fun explicitEmptyUserAgentDoesNotFallBackToJavaIdentity() = runBlocking {
        createChannelWithBaseUrl("empty-UA", fake.baseUrl + "/ok/v1", requestOverrides = UpstreamRequestOverrides(userAgent = ""))
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            response.bodyAsText()
            assertEquals(listOf(""), fake.requests.single().requestHeaders["user-agent"])
        }
    }

    @Test
    @Tag("gemini")
    fun geminiQueryCredentialsAreExcludedFromLogsAndUpstreamUrls() = runBlocking {
        createChannelWithBaseUrl(
            "gemini-query-logging", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI),
            modelMappings = mappingsOf("gemini-2.5-pro"), authType = "google",
        )
        val key = createClientKey()
        val rejectedKey = "query-client-secret-for-log-test"
        val queryTag = "private-query-tag"
        val events = ConcurrentLinkedQueue<ILoggingEvent>()
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                events.add(event)
            }
        }.apply { context = rootLogger.loggerContext; start() }
        rootLogger.addAppender(appender)
        try {
            testApplication {
                application { app.install(this) }
                for (action in listOf("generateContent", "streamGenerateContent")) {
                    val response = client.post("/v1beta/models/gemini-2.5-pro:$action?key=$key&tracking=$queryTag") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"contents":[]}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertFalse(response.bodyAsText().contains(key))
                }
                val rejected = client.get("/v1beta/models?key=$rejectedKey&tracking=$queryTag")
                assertEquals(HttpStatusCode.Unauthorized, rejected.status)
                assertFalse(rejected.bodyAsText().contains(rejectedKey))
                val malformed = client.post("/v1beta/models/gemini-2.5-pro:generateContent?key=$key&tracking=$queryTag") {
                    contentType(ContentType.Application.Json)
                    setBody("{")
                }
                assertEquals(HttpStatusCode.BadRequest, malformed.status)
                assertFalse(malformed.bodyAsText().contains(key))
                awaitRequestLogCount(2)
                val requestLogs = app.requestLogRepository.list(10).toString()
                assertFalse(requestLogs.contains(key))
                assertFalse(requestLogs.contains(queryTag))
            }
            val messages = events.map { it.formattedMessage }
            assertEquals(4, messages.count { it.contains(" - /v1beta/models") })
            val captured = events.joinToString("\n") { event ->
                event.formattedMessage + event.mdcPropertyMap.values.joinToString() +
                    (event.throwableProxy?.let(ThrowableProxyUtil::asString) ?: "")
            }
            for (secret in listOf(key, rejectedKey, queryTag, "upstream-secret")) {
                assertFalse(captured.contains(secret), "日志不应包含凭据或查询参数")
            }
            assertEquals(2, fake.requests.size)
            fake.requests.forEach {
                val uri = assertNotNull(it.uri)
                assertFalse(uri.contains(key))
                assertFalse(uri.contains("key="))
                assertFalse(uri.contains(queryTag))
                assertEquals(listOf("upstream-secret"), it.requestHeaders["x-goog-api-key"])
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    @Tag("gemini")
    fun geminiNativeJsonUsesMappedUrlAndGoogleCredential() = runBlocking {
        createChannelWithBaseUrl(
            "gemini-native", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI),
            modelMappings = listOf(ChannelModelRecord("gemini-2.5-pro", "google/fast-gemini")), authType = "google",
            requestOverrides = UpstreamRequestOverrides(protocols = mapOf("gemini" to UpstreamProtocolOverrides(
                body = Json.parseToJsonElement("""{"generationConfig":{"temperature":0.3}}""").jsonObject,
            ))),
        )
        createChannel("chat-only", "ok")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val list = client.get("/v1beta/models") { header("x-goog-api-key", key) }
            val names = Json.parseToJsonElement(list.bodyAsText()).jsonObject["models"]!!.jsonArray
            assertEquals(1, names.size)
            assertEquals("models/google/fast-gemini", names.single().jsonObject["name"]!!.jsonPrimitive.content)
            val response = client.post("/v1beta/models/google/fast-gemini:generateContent?key=$key") {
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[{"role":"user","parts":[{"text":"hi"}]}],"generationConfig":{"thinkingConfig":{"thinkingLevel":"high"}}}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Hello!"))
            val sent = fake.requests.single()
            assertEquals("/ok/v1beta/models/gemini-2.5-pro:generateContent", sent.uri)
            assertEquals(listOf("upstream-secret"), sent.requestHeaders["x-goog-api-key"])
            assertNull(sent.authorization)
            assertTrue(sent.requestHeaders["user-agent"]!!.single().startsWith("google-genai-sdk/"))
            val body = Json.parseToJsonElement(sent.body).jsonObject
            assertFalse(body.containsKey("model"))
            assertFalse(body.containsKey("stream"))
            assertEquals("0.3", body["generationConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
            val log = awaitRequestLog()
            assertEquals(GatewayProtocol.GEMINI, log.protocol)
            assertEquals("google/fast-gemini", log.alias)
            assertEquals("gemini-2.5-pro", log.upstreamModel)
            assertEquals(10L, log.inputTokens)
            assertEquals(5L, log.outputTokens)
            assertEquals(2L, log.reasoningTokens)
            assertEquals(15L, log.totalTokens)
            assertEquals("high", log.reasoningEffort)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiSseKeepsNativeFramesAndUsageAfterFinishReason() = runBlocking {
        val channel = createChannelWithBaseUrl(
            "gemini-sse", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI),
            modelMappings = mappingsOf("gemini-2.5-pro"),
            requestOverrides = UpstreamRequestOverrides(protocols = mapOf("gemini" to UpstreamProtocolOverrides(headerProfile = "antigravity"))),
        )
        exposeAsModel("fast-gemini", channel to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/models/fast-gemini:streamGenerateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("finishReason"))
            assertTrue(text.contains("totalTokenCount"))
            assertFalse(text.contains("[DONE]"))
            val sent = fake.requests.single()
            assertTrue(sent.uri!!.endsWith(":streamGenerateContent?alt=sse"))
            assertTrue(sent.requestHeaders["user-agent"]!!.single().startsWith("antigravity/"))
            assertNull(sent.requestHeaders["originator"])
            assertEquals("Bearer upstream-secret", sent.authorization)
            val log = awaitRequestLog()
            assertEquals(15L, log.totalTokens)
            assertEquals(5L, log.outputTokens)
            assertNotNull(log.firstTokenMs)
            assertNull(log.errorCode)
            assertTrue(log.stream == true)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiServerErrorBeforeContentCanFailOver() = runBlocking {
        val bad = createChannelWithBaseUrl("a-error", fake.baseUrl + "/sse-error/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        val good = createChannelWithBaseUrl("b-good", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        exposeAsModel("fast-gemini", bad to "gemini-2.5-pro", good to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("Hello!"))
            assertFalse(text.contains("upstream down"))
            assertEquals(2, fake.requests.size)
            assertEquals(2, awaitRequestLog().upstreamAttempts)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiErrorFrameAfterContentIsLoggedAsUpstreamFailureWithoutFailover() = runBlocking {
        val bad = createChannelWithBaseUrl(
            "a-error", fake.baseUrl + "/sse-error-after-content/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"),
        )
        val backup = createChannelWithBaseUrl("b-backup", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        exposeAsModel("fast-gemini", bad to "gemini-2.5-pro", backup to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Hello!"))
            assertTrue(body.contains("INTERNAL"))
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals("gemini_failed", log.errorCode)
            assertEquals("上游响应失败", log.errorMessage)
            assertEquals(FailureStage.STREAMING, log.failureStage)
            assertEquals(1, log.upstreamAttempts)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiUnexpectedEofAfterContentIsLoggedWithoutFailover() = runBlocking {
        val bad = createChannelWithBaseUrl("a-eof", fake.baseUrl + "/no-terminal/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        val backup = createChannelWithBaseUrl("b-backup", fake.baseUrl + "/ok/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        exposeAsModel("fast-gemini", bad to "gemini-2.5-pro", backup to "gemini-2.5-pro")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1beta/models/fast-gemini:streamGenerateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertTrue(response.bodyAsText().contains("Hello!"))
            assertEquals(1, fake.requests.size)
            val log = awaitRequestLog()
            assertEquals("stream_interrupted", log.errorCode)
            assertNotNull(log.firstTokenMs)
        }
    }

    @Test
    @Tag("gemini")
    fun geminiNativeErrorsAndProtocolFilteringRemainNative() = runBlocking {
        createChannelWithBaseUrl("native-error", fake.baseUrl + "/429/v1beta", listOf(GatewayProtocol.GEMINI), mappingsOf("gemini-2.5-pro"))
        createChannel("chat-channel", "ok")
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val unauth = client.post("/v1beta/models/gemini-2.5-pro:generateContent") {
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, unauth.status)
            assertTrue(unauth.bodyAsText().contains("UNAUTHENTICATED"))
            val incompatible = client.post("/v1beta/models/m1:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.OK, incompatible.status)
            val converted = Json.parseToJsonElement(incompatible.bodyAsText()).jsonObject
            assertTrue(converted.containsKey("candidates"), incompatible.bodyAsText())
            val rateLimited = client.post("/v1beta/models/gemini-2.5-pro:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"contents":[]}""")
            }
            assertEquals(HttpStatusCode.TooManyRequests, rateLimited.status)
            assertEquals("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"rate limited"}}""", rateLimited.bodyAsText())
            assertEquals(2, fake.requests.size)
        }
    }

    private suspend fun createAutoDisablePool(withBackup: Boolean = true): ChannelRecord = app.channelRepository.create(
        name = "auto-disable-pool",
        baseUrl = "${fake.baseUrl}/key-health/v1",
        keys = listOf(NewChannelKey("primary", app.credentialCipher.encrypt("key-primary"), models = listOf("m1", "m2"))) +
            if (withBackup) listOf(NewChannelKey("backup", app.credentialCipher.encrypt("key-backup"), priority = 2, models = listOf("m1", "m2")))
            else emptyList(),
        authType = "bearer", protocols = GatewayProtocol.entries.toList(), enabled = true, modelMappings = mappingsOf("m1", "m2"),
    )

    private suspend fun HttpClient.sendAutoDisableRequest(
        key: String,
        model: String = "m1",
        path: String = "/v1/chat/completions",
        requestId: String = java.util.UUID.randomUUID().toString(),
    ) = post(path) {
        header(HttpHeaders.Authorization, "Bearer $key")
        header("X-Request-Id", requestId)
        contentType(ContentType.Application.Json)
        setBody("""{"model":"$model","messages":[{"role":"user","content":"private-test-prompt"}],"max_tokens":8}""")
    }

    private suspend fun createChannel(name: String, scenario: String): ChannelRecord {
        return createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1")
    }

    private suspend fun createChannelWithBaseUrl(
        name: String,
        baseUrl: String,
        protocols: List<GatewayProtocol> = GatewayProtocol.default,
        modelMappings: List<ChannelModelRecord> = mappingsOf("gpt-4o-mini", "deepseek-chat", "m1", "m2"),
        priority: Int = 0,
        authType: String = "bearer",
        requestOverrides: UpstreamRequestOverrides = UpstreamRequestOverrides(),
    ): ChannelRecord {
        val encrypted = app.credentialCipher.encrypt("upstream-secret")
        return app.channelRepository.create(
            name = name,
            baseUrl = baseUrl,
            keys = listOf(
                dev.soven.llmgateway.repo.NewChannelKey(
                    name = "默认密钥",
                    credential = encrypted,
                    models = modelMappings.map { it.upstreamModel },
                ),
            ),
            authType = authType,
            protocols = protocols,
            enabled = true,
            modelMappings = modelMappings,
            priority = priority,
            requestOverrides = requestOverrides,
        )
    }

    private fun mappingsOf(vararg models: String): List<ChannelModelRecord> =
        models.map { ChannelModelRecord(upstreamModel = it) }

    private suspend fun createOAuthAccount(
        channel: ChannelRecord,
        email: String,
        refreshToken: String = "oauth-refresh-token",
        provider: OAuthProvider = OAuthProvider.CHATGPT,
    ): OAuthAccountRecord = app.oauthAccountRepository.create(
        NewOAuthAccount(
            name = email,
            provider = provider,
            accountEmail = email,
            refreshToken = app.credentialCipher.encrypt(refreshToken),
            channelIds = listOf(channel.id),
        ),
    )

    private suspend fun createChannelWithProtocols(
        name: String,
        scenario: String,
        protocols: List<GatewayProtocol>,
    ): ChannelRecord = createChannelWithBaseUrl(name, "${fake.baseUrl}/$scenario/v1", protocols)

    /**
     * 把给定渠道的上游模型映射到同一个对外模型 ID，等价于旧测试里“建一个同名别名”。
     * 现在只有直连暴露，跨渠道聚合靠渠道模型映射的 canonical ID。
     */
    private suspend fun exposeAsModel(publicId: String, vararg candidates: Pair<ChannelRecord, String>) {
        exposeUnderCanonicalId(publicId, candidates.map { it.first.id to it.second })
    }

    /** 同 [exposeAsModel]；档位由渠道优先级决定，候选里的 position 不再生效。 */
    private suspend fun exposeUnderCanonicalIdInOrder(publicId: String, vararg candidates: Triple<ChannelRecord, String, Int>) {
        exposeUnderCanonicalId(publicId, candidates.map { it.first.id to it.second })
    }

    private suspend fun exposeUnderCanonicalId(publicId: String, mappings: List<Pair<String, String>>) {
        val byChannel = mappings.groupBy({ it.first }, { it.second })
        byChannel.forEach { (channelId, upstreamModels) ->
            val channel = assertNotNull(app.channelRepository.find(channelId))
            val targets = upstreamModels.toSet()
            val updatedMappings = channel.modelMappings.map { mapping ->
                if (mapping.upstreamModel in targets) mapping.copy(canonicalModelId = publicId) else mapping
            }
            app.channelRepository.update(
                id = channel.id,
                name = channel.name,
                baseUrl = channel.baseUrl,
                authType = channel.authType,
                protocols = channel.protocols,
                enabled = channel.enabled,
                modelMappings = updatedMappings,
                priority = channel.priority,
            )
        }
    }

    private suspend fun createClientKey(
        quotas: ClientKeyQuotaLimits = ClientKeyQuotaLimits(),
    ): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create(
            name = "test-key",
            keyHash = generated.hash,
            keyPrefix = generated.prefix,
            enabled = true,
            expiresAt = null,
            quotas = quotas,
        )
        return generated.raw
    }

    private suspend fun createPrice(
        channel: ChannelRecord,
        upstreamModel: String,
        input: String,
        output: String,
        cached: String? = null,
    ) = checkNotNull(
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = upstreamModel,
            currency = "USD",
            inputPricePerMillion = BigDecimal(input),
            outputPricePerMillion = BigDecimal(output),
            cachedInputPricePerMillion = cached?.let(::BigDecimal),
        ),
    )

    private suspend fun createDefaultPrice(
        modelId: String,
        input: String,
        output: String,
        cached: String? = null,
    ) = app.modelDefaultPriceRepository.create(
        modelId = modelId,
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

    private suspend fun awaitCondition(
        description: String,
        timeoutMs: Long = 3_000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        error("timed out waiting for $description within ${timeoutMs}ms")
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
                    "truncate table cooldown_state, admin_audit_logs, retention_settings, model_test_settings, upstream_request_settings, upstream_header_catalog_state, upstream_header_profile_versions, upstream_header_profile_pins, key_auto_disable_settings, request_logs, model_default_prices, model_prices, client_key_quota_usage, client_keys, channels, admin_users, web_search_settings, iq_vision_settings, iq_test_runs restart identity cascade",
                )
            }
        }
    }

    private fun scalarInt(sql: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "query returned no rows: $sql" }
                    result.getInt(1)
                }
            }
        }
}
