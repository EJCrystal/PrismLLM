package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.antigravity.AntigravityAgyAccount
import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.config.OAuthConfig
import dev.soven.prismllm.oauth.domain.OAuthRoutingStrategy
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.ProviderModelMappingChange
import dev.soven.prismllm.oauth.ProviderChannelProvisioner
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.repo.OAuthProviderChannelLookup
import kotlinx.serialization.json.JsonObject
import dev.soven.prismllm.channel.persistence.ChannelModels
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.serialization.json.JsonNull
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.oauth.domain.OAuthAccountPolicy
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.oauth.infra.OAuthAccountDecision
import dev.soven.prismllm.oauth.infra.OAuthAccountDenyReason
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.resilience.FailureKind
import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
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
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
@Tag("channel")
class SubscriptionOAuthIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `shared mappings GET is authenticated provider scoped and never provisions or calls upstream`() = runBlocking {
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/oauth-providers/chatgpt/model-mappings").status)
            val (cookie, _) = client.loginAdmin()
            val response = client.get("/admin/api/oauth-providers/chatgpt/model-mappings") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("chatgpt", body["provider"]!!.jsonPrimitive.content)
            assertEquals("provider", body["scope"]!!.jsonPrimitive.content)
            assertEquals(0, body["revision"]!!.jsonPrimitive.long)
            assertEquals(0, body["affectedAccountCount"]!!.jsonPrimitive.int)
            assertTrue(body["mappings"]!!.jsonArray.isEmpty())
            assertEquals("no-store", response.headers["Cache-Control"])
            assertEquals(0, scalarInt("select count(*) from channels where kind = 'provider'"))
            val invalid = client.get("/admin/api/oauth-providers/not-a-provider/model-mappings") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
        }
        assertTrue(fake.requests.isEmpty())
        assertTrue(fake.modelRequests.isEmpty())
    }

    @Test
    fun `provider endpoints expose metadata toggle enabled and save shared mappings independently`() = runBlocking {
        val account = mappingAccount("provider-api", models = listOf("m"))
        app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry("m", "family-m")))
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val providersResponse = client.get("/admin/api/oauth-providers") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, providersResponse.status)
            val chatgptProvider = Json.parseToJsonElement(providersResponse.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .first { it["id"]!!.jsonPrimitive.content == "chatgpt" }
            assertNotNull(chatgptProvider["channelId"]?.jsonPrimitive?.content)
            assertEquals("https://chatgpt.com/backend-api/codex", chatgptProvider["baseUrl"]!!.jsonPrimitive.content)
            assertEquals(listOf("responses"), chatgptProvider["protocols"]!!.jsonArray.map { it.jsonPrimitive.content })

            val disableRes = client.put("/admin/api/oauth-providers/chatgpt/enabled") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals(HttpStatusCode.OK, disableRes.status)
            assertFalse(Json.parseToJsonElement(disableRes.bodyAsText()).jsonObject["enabled"]!!.jsonPrimitive.boolean)

            val enableRes = client.put("/admin/api/oauth-providers/chatgpt/enabled") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }
            assertEquals(HttpStatusCode.OK, enableRes.status)
            assertTrue(Json.parseToJsonElement(enableRes.bodyAsText()).jsonObject["enabled"]!!.jsonPrimitive.boolean)

            val initial = Json.parseToJsonElement(
                client.get("/admin/api/oauth-providers/chatgpt/model-mappings") { header(HttpHeaders.Cookie, cookie) }.bodyAsText(),
            ).jsonObject
            val revision = initial["revision"]!!.jsonPrimitive.long
            val saveRes = client.put("/admin/api/oauth-providers/chatgpt/model-mappings") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":$revision,"changes":[{"upstreamModel":"m","mode":"manual","canonicalModelId":"alias-m"}]}""")
            }
            assertEquals(HttpStatusCode.OK, saveRes.status)
            assertEquals("no-store", saveRes.headers["Cache-Control"])
            val updated = Json.parseToJsonElement(saveRes.bodyAsText()).jsonObject
            assertEquals(revision + 1, updated["revision"]!!.jsonPrimitive.long)
            val row = updated["mappings"]!!.jsonArray.first { it.jsonObject["upstreamModel"]!!.jsonPrimitive.content == "m" }.jsonObject
            assertEquals("manual", row["mode"]!!.jsonPrimitive.content)
            assertEquals("alias-m", row["publicModelId"]!!.jsonPrimitive.content)

            val conflictRes = client.put("/admin/api/oauth-providers/chatgpt/model-mappings") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":$revision,"changes":[{"upstreamModel":"m","mode":"upstream"}]}""")
            }
            assertEquals(HttpStatusCode.Conflict, conflictRes.status)

            val invalidNull = client.put("/admin/api/oauth-providers/chatgpt/model-mappings") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":${revision + 1},"changes":[{"upstreamModel":"m","mode":"upstream","canonicalModelId":null}]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidNull.status)

            // 人工新增共享行：允许通过 API 创建，先以休眠手工映射保存。
            val addedRes = client.put("/admin/api/oauth-providers/chatgpt/model-mappings") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":${revision + 1},"changes":[{"upstreamModel":"brand-new","mode":"upstream"}]}""")
            }
            assertEquals(HttpStatusCode.OK, addedRes.status)
            val added = Json.parseToJsonElement(addedRes.bodyAsText()).jsonObject
            val addedRow = added["mappings"]!!.jsonArray
                .first { it.jsonObject["upstreamModel"]!!.jsonPrimitive.content == "brand-new" }.jsonObject
            assertEquals("manual", addedRow["mappingSource"]!!.jsonPrimitive.content)
            assertFalse(addedRow["catalogActive"]!!.jsonPrimitive.boolean)
            assertEquals(revision + 2, added["revision"]!!.jsonPrimitive.long)

            val autoNew = client.put("/admin/api/oauth-providers/chatgpt/model-mappings") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":${revision + 2},"changes":[{"upstreamModel":"auto-new","mode":"auto"}]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, autoNew.status)
        }
    }

    @Test
    fun `settings mapping increments are shared without expanding account authorization or other providers`() = runBlocking {
        val a = mappingAccount("a", models = listOf("a"))
        val b = mappingAccount("b", models = listOf("b"))
        val other = mappingAccount("other", OAuthProvider.ANTIGRAVITY)
        app.providerChannelProvisioner.syncModelCatalog(a.id, listOf(OAuthAccountModelCatalogEntry("a", "family-a")))
        app.providerChannelProvisioner.syncModelCatalog(b.id, listOf(OAuthAccountModelCatalogEntry("b", "family-b")))
        app.providerChannelProvisioner.syncModelCatalog(other.id, listOf(OAuthAccountModelCatalogEntry("other", "other-family")))
        val oldOther = app.providerChannelProvisioner.modelMappings(OAuthProvider.ANTIGRAVITY)
        val originalA = app.oauthAccountRepository.findDetailed(a.id)!!
        val originalB = app.oauthAccountRepository.findDetailed(b.id)!!
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun shared() = Json.parseToJsonElement(client.get("/admin/api/oauth-providers/chatgpt/model-mappings") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()).jsonObject
            suspend fun save(account: String, payload: String) = client.put("/admin/api/oauth-accounts/$account/settings") {
                header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json); setBody(payload)
            }
            val initial = shared()
            assertEquals(2, initial["affectedAccountCount"]!!.jsonPrimitive.int)
            assertEquals(setOf("a", "b"), initial["mappings"]!!.jsonArray.map { it.jsonObject["upstreamModel"]!!.jsonPrimitive.content }.toSet())
            val revision = initial["revision"]!!.jsonPrimitive.long
            val frozen = save(a.id, """{"expectedModelMappingRevision":$revision,"modelMappingChanges":[{"upstreamModel":"a","mode":"manual","canonicalModelId":"  family-a  "}]}""")
            assertEquals(HttpStatusCode.OK, frozen.status)
            val returned = Json.parseToJsonElement(frozen.bodyAsText()).jsonObject
            assertEquals(setOf("a"), returned["models"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(listOf("a"), returned["modelCatalog"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse("modelMappings" in returned)
            assertFalse("revision" in returned)
            assertFalse(frozen.bodyAsText().contains("refresh-secret"))
            val changed = shared()
            assertEquals(revision + 1, changed["revision"]!!.jsonPrimitive.long)
            val row = changed["mappings"]!!.jsonArray.first { it.jsonObject["upstreamModel"]!!.jsonPrimitive.content == "a" }.jsonObject
            assertEquals("manual", row["mode"]!!.jsonPrimitive.content)
            assertEquals("family-a", row["automaticCanonicalModelId"]!!.jsonPrimitive.content)
            assertEquals("manual", row["mappingSource"]!!.jsonPrimitive.content)
            assertTrue(row["catalogActive"]!!.jsonPrimitive.boolean)
            val upstream = save(b.id, """{"expectedModelMappingRevision":${revision + 1},"modelMappingChanges":[{"upstreamModel":"b","mode":"upstream"}]}""")
            assertEquals(HttpStatusCode.OK, upstream.status)
            val rows = shared()["mappings"]!!.jsonArray.associate { it.jsonObject["upstreamModel"]!!.jsonPrimitive.content to it.jsonObject }
            assertEquals("manual", rows.getValue("a")["mode"]!!.jsonPrimitive.content, "dirty update must not replace unrelated rows")
            assertEquals("upstream", rows.getValue("b")["mode"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, rows.getValue("b")["canonicalModelId"])
            assertEquals("b", rows.getValue("b")["publicModelId"]!!.jsonPrimitive.content)
            val noopRevision = shared()["revision"]!!.jsonPrimitive.long
            assertEquals(HttpStatusCode.OK, save(a.id, """{"models":null,"modelMappingChanges":[],"expectedModelMappingRevision":0}""").status)
            assertEquals(noopRevision, shared()["revision"]!!.jsonPrimitive.long)
            val restore = save(a.id, """{"expectedModelMappingRevision":$noopRevision,"modelMappingChanges":[{"upstreamModel":"a","mode":"auto"}]}""")
            assertEquals(HttpStatusCode.OK, restore.status)
            val automatic = shared()["mappings"]!!.jsonArray.first { it.jsonObject["upstreamModel"]!!.jsonPrimitive.content == "a" }.jsonObject
            assertEquals("auto", automatic["mode"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, automatic["mappingSource"])
            assertEquals(originalA.models, app.oauthAccountRepository.findDetailed(a.id)!!.models)
            assertEquals(originalB.models, app.oauthAccountRepository.findDetailed(b.id)!!.models)
            assertFalse(app.oauthAccountRepository.findDetailed(a.id)!!.supports("b"))
            assertFalse(app.oauthAccountRepository.findDetailed(b.id)!!.supports("a"))
            assertEquals(oldOther, app.providerChannelProvisioner.modelMappings(OAuthProvider.ANTIGRAVITY))
        }
        assertTrue(fake.requests.isEmpty())
        assertTrue(fake.modelRequests.isEmpty())
    }

    @Test
    fun `subscription display models follow enabled account authorization while all mappings remain editable`() = runBlocking {
        val provider = OAuthProvider.ANTIGRAVITY
        val first = mappingAccount("enabled-first", provider, models = listOf("claude-first"))
        val second = mappingAccount("enabled-second", provider, models = listOf("claude-second"))
        val disabled = (1..3).map { mappingAccount("disabled-$it", provider) }
        app.providerChannelProvisioner.syncModelCatalog(first.id, listOf(
            OAuthAccountModelCatalogEntry("claude-first", "claude-family"),
            OAuthAccountModelCatalogEntry("gemini-3-flash"),
            OAuthAccountModelCatalogEntry("gemini-3.1-flash-image"),
        ))
        app.providerChannelProvisioner.syncModelCatalog(second.id, listOf(OAuthAccountModelCatalogEntry("claude-second", "claude-family")))
        for (account in disabled) {
            app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry("disabled-only")))
            app.providerChannelProvisioner.updateSettings(account.id, enabled = false)
        }
        val channelId = app.providerChannelProvisioner.ensure(provider)!!
        val shared = app.providerChannelProvisioner.modelMappings(provider)
        val saved = app.providerChannelProvisioner.updateModelMappings(provider, listOf(
            ProviderModelMappingChange("gemini-3-flash", "manual", "saved-flash-alias"),
            ProviderModelMappingChange("future-model", "manual", "saved-future-alias"),
        ), shared.revision)
        val originalMappings = app.channelRepository.find(channelId)!!.modelMappings

        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun channel() = Json.parseToJsonElement(client.get("/admin/api/channels") {
                header(HttpHeaders.Cookie, cookie)
            }.bodyAsText()).jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == channelId }
            suspend fun mappings() = Json.parseToJsonElement(client.get("/admin/api/oauth-providers/antigravity/model-mappings") {
                header(HttpHeaders.Cookie, cookie)
            }.bodyAsText()).jsonObject
            suspend fun settings(accountId: String, body: String) {
                assertEquals(HttpStatusCode.OK, client.put("/admin/api/oauth-accounts/$accountId/settings") {
                    header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json); setBody(body)
                }.status)
            }
            suspend fun assertEnabled(expected: Set<String>) {
                val response = channel()
                assertEquals(expected, response["enabledModels"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertTrue(response["models"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(
                    listOf("gemini-3-flash", "gemini-3.1-flash-image", "disabled-only"),
                ), "full channel catalog must remain available for account settings")
                val sharedResponse = mappings()
                assertEquals(saved.revision, sharedResponse["revision"]!!.jsonPrimitive.long)
                val rows = sharedResponse["mappings"]!!.jsonArray.map { it.jsonObject }
                assertEquals(expected, rows.filter { it["enabled"]!!.jsonPrimitive.boolean }.map { it["upstreamModel"]!!.jsonPrimitive.content }.toSet())
                val manual = rows.single { it["upstreamModel"]!!.jsonPrimitive.content == "gemini-3-flash" }
                assertEquals("saved-flash-alias", manual["canonicalModelId"]!!.jsonPrimitive.content)
                assertTrue(manual["catalogActive"]!!.jsonPrimitive.boolean)
                val dormant = rows.single { it["upstreamModel"]!!.jsonPrimitive.content == "future-model" }
                assertFalse(dormant["catalogActive"]!!.jsonPrimitive.boolean)
                assertFalse(dormant["enabled"]!!.jsonPrimitive.boolean)
                assertEquals("saved-future-alias", dormant["canonicalModelId"]!!.jsonPrimitive.content)
                assertEquals(originalMappings, app.channelRepository.find(channelId)!!.modelMappings)
            }

            assertEnabled(setOf("claude-first", "claude-second"))
            // Empty authorization inherits only this account's own known catalog, not disabled accounts' models.
            settings(first.id, """{"models":[]}""")
            assertEnabled(setOf("claude-first", "claude-second", "gemini-3-flash", "gemini-3.1-flash-image"))
            settings(first.id, """{"enabled":false}""")
            settings(second.id, """{"enabled":false}""")
            assertEnabled(emptySet())
            settings(first.id, """{"enabled":true,"models":["gemini-3-flash"]}""")
            assertEnabled(setOf("gemini-3-flash"))
        }
        assertTrue(fake.requests.isEmpty())
        assertTrue(fake.modelRequests.isEmpty())
    }

    @Test
    fun `invalid settings noauth csrf and revision conflicts leave entire account and mappings unchanged`() = runBlocking {
        val account = mappingAccount("validation", models = listOf("m"))
        app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry("m", "family")))
        val beforeAccount = app.oauthAccountRepository.findDetailed(account.id)!!
        val before = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT)
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun save(payload: String, auth: Boolean = true, csrfToken: String? = csrf) = client.put("/admin/api/oauth-accounts/${account.id}/settings") {
                if (auth) header(HttpHeaders.Cookie, cookie)
                csrfToken?.let { header("X-CSRF-Token", it) }
                contentType(ContentType.Application.Json); setBody(payload)
            }
            val valid = """{"name":"changed","models":[],"expectedModelMappingRevision":${before.revision},"modelMappingChanges":[{"upstreamModel":"m","mode":"manual","canonicalModelId":"changed"}]}"""
            assertEquals(HttpStatusCode.Unauthorized, save(valid, auth = false).status)
            assertEquals(HttpStatusCode.Forbidden, save(valid, csrfToken = null).status)
            val invalidRows = listOf(
                """[{"upstreamModel":"m","mode":"manual"}]""",
                """[{"upstreamModel":"m","mode":"manual","canonicalModelId":"   "}]""",
                """[{"upstreamModel":"m","mode":"manual","canonicalModelId":"${"x".repeat(201)}"}]""",
                """[{"upstreamModel":"m","mode":"unknown"}]""",
                """[{"upstreamModel":"m","mode":"upstream","canonicalModelId":"bad"}]""",
                """[{"upstreamModel":"m","mode":"auto","canonicalModelId":null}]""",
                """[{"upstreamModel":"m","mode":"auto"},{"upstreamModel":" m ","mode":"upstream"}]""",
                """[{"upstreamModel":"unknown","mode":"manual","canonicalModelId":"bad"}]""",
                """[{"upstreamModel":"${"x".repeat(201)}","mode":"upstream"}]""",
            )
            invalidRows.forEach { rows ->
                val response = save("""{"name":"changed","models":[],"expectedModelMappingRevision":${before.revision},"modelMappingChanges":$rows}""")
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertEquals(beforeAccount, app.oauthAccountRepository.findDetailed(account.id))
                assertEquals(before, app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT))
            }
            assertEquals(HttpStatusCode.BadRequest, save("""{"modelMappingChanges":[{"upstreamModel":"m","mode":"upstream"}]}""").status)
            val conflict = save(valid.replace("\"expectedModelMappingRevision\":${before.revision}", "\"expectedModelMappingRevision\":${before.revision - 1}"))
            assertEquals(HttpStatusCode.Conflict, conflict.status, conflict.bodyAsText())
            assertTrue(conflict.bodyAsText().contains("model_mapping_revision_changed"))
            assertEquals(beforeAccount, app.oauthAccountRepository.findDetailed(account.id))
            assertEquals(before, app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT))
        }
        assertTrue(fake.requests.isEmpty())
        assertTrue(fake.modelRequests.isEmpty())
    }

    @Test
    fun `provider HTTP catalog exposes canonical only and sends upstream model while raw requests are rejected`() = runBlocking {
        val account = mappingAccount("forward", models = listOf("upstream-model"))
        app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry("upstream-model")))
        val channel = app.channelRepository.findProviderChannel(OAuthProvider.CHATGPT)!!
        // 测试自己的 provider 端点指向本机 fake，不外访官方端点。
        app.channelRepository.update(channel.id, channel.name, fake.baseUrl + "/ok/v1", authType = channel.authType, enabled = true)
        app.providerChannelProvisioner.updateSettings(account.id, modelMappingChanges = listOf(ProviderModelMappingChange("upstream-model", "manual", "public-model")), expectedModelMappingRevision = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).revision)
        configureModelRoute("public-model", channel to account)
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val listing = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
            assertEquals(HttpStatusCode.OK, listing.status)
            assertEquals(listOf("public-model"), Json.parseToJsonElement(listing.bodyAsText()).jsonObject["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            suspend fun send(model: String) = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key"); contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","stream":false,"input":"ping"}""")
            }
            val raw = send("upstream-model")
            assertEquals(HttpStatusCode.NotFound, raw.status, raw.bodyAsText())
            assertTrue(fake.requests.isEmpty())
            val canonical = send("public-model")
            assertEquals(HttpStatusCode.OK, canonical.status, canonical.bodyAsText())
            assertEquals("upstream-model", Json.parseToJsonElement(fake.requests.single().body).jsonObject["model"]!!.jsonPrimitive.content)
            assertEquals("Bearer access-secret", fake.requests.single().authorization)
        }
    }

    @Test
    fun `shared GET and routing fall back to upstream when last known catalog is deleted with an unknown account`() = runBlocking<Unit> {
        assertUnknownCatalogFallbackOverHttp(deleteKnownAccount = true)
    }

    @Test
    fun `shared GET and routing fall back to upstream when last known catalog is cleared with an unknown account`() = runBlocking<Unit> {
        assertUnknownCatalogFallbackOverHttp(deleteKnownAccount = false)
    }

    private suspend fun assertUnknownCatalogFallbackOverHttp(deleteKnownAccount: Boolean) {
        val known = mappingAccount("known", models = listOf("m"))
        app.providerChannelProvisioner.syncModelCatalog(known.id, listOf(OAuthAccountModelCatalogEntry("m", "family")))
        val unknown = mappingAccount("unknown", models = listOf("m"))
        val other = ProviderChannelProvisioner(
            ChannelRepository(app.databaseFactory.database, app.credentialCipher),
            OAuthAccountRepository(app.databaseFactory.database, OAuthProviderChannelLookup()),
        )
        val channel = app.channelRepository.findProviderChannel(OAuthProvider.CHATGPT)!!
        app.channelRepository.update(channel.id, channel.name, fake.baseUrl + "/ok/v1", authType = channel.authType, enabled = true)
        configureModelRoute("family", channel to known, channel to unknown)
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun shared(): JsonObject {
                val response = client.get("/admin/api/oauth-providers/chatgpt/model-mappings") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            suspend fun assertPublicModels(model: String) {
                val response = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals(listOf(model), Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            }
            val before = shared()
            val revision = before["revision"]!!.jsonPrimitive.long
            assertEquals("family", before["mappings"]!!.jsonArray.single().jsonObject["publicModelId"]!!.jsonPrimitive.content)
            assertPublicModels("family")
            if (deleteKnownAccount) {
                val response = client.delete("/admin/api/oauth-accounts/${known.id}") {
                    header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
            } else {
                // 可信已知空目录走存储编排；不取消上游 refresh HTTP 的空目录 502 防护。
                assertTrue(other.syncModelCatalog(known.id, emptyList()))
            }
            val after = shared()
            assertEquals(revision + 1, after["revision"]!!.jsonPrimitive.long)
            assertEquals(if (deleteKnownAccount) 1 else 2, after["affectedAccountCount"]!!.jsonPrimitive.int)
            val row = after["mappings"]!!.jsonArray.single().jsonObject
            assertEquals("m", row["upstreamModel"]!!.jsonPrimitive.content)
            assertEquals("m", row["publicModelId"]!!.jsonPrimitive.content)
            assertEquals("auto", row["mode"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, row["canonicalModelId"])
            assertEquals(JsonNull, row["automaticCanonicalModelId"])
            assertEquals(JsonNull, row["mappingSource"])
            assertTrue(row["catalogActive"]!!.jsonPrimitive.boolean)
            // 目录的公开 ID 变化不会自动迁移凭据路由；测试显式配置新的公开模型。
            configureModelRoute("m", channel to unknown)
            assertPublicModels("m")
            other.syncModelUnion(OAuthProvider.CHATGPT)
            app.providerChannelProvisioner.syncModelUnion(OAuthProvider.CHATGPT)
            if (!deleteKnownAccount) assertTrue(other.syncModelCatalog(known.id, emptyList()))
            assertEquals(after, shared(), "same-state sync must not advance revision")
            assertNull(app.oauthAccountRepository.findDetailed(unknown.id)!!.modelCatalog)
            assertEquals(listOf("m"), app.oauthAccountRepository.findDetailed(unknown.id)!!.models)
            assertTrue(fake.requests.isEmpty())
            assertTrue(fake.modelRequests.isEmpty())
            suspend fun send(model: String) = client.post("/v1/responses") {
                header(HttpHeaders.Authorization, "Bearer $key"); contentType(ContentType.Application.Json)
                setBody("""{"model":"$model","stream":false,"input":"ping"}""")
            }
            val obsolete = send("family")
            assertEquals(HttpStatusCode.NotFound, obsolete.status, obsolete.bodyAsText())
            assertTrue(fake.requests.isEmpty())
            val upstream = send("m")
            assertEquals(HttpStatusCode.OK, upstream.status, upstream.bodyAsText())
            assertEquals("m", Json.parseToJsonElement(fake.requests.single().body).jsonObject["model"]!!.jsonPrimitive.content)
            assertEquals("Bearer access-secret", fake.requests.single().authorization)
        }
    }

    @Test
    fun `legacy provider models keeps dormant mappings and provider probes reject inactive before external requests`() = runBlocking {
        val account = mappingAccount("probe")
        app.providerChannelProvisioner.syncModelCatalog(account.id, listOf(OAuthAccountModelCatalogEntry("m", "family")))
        app.providerChannelProvisioner.updateSettings(account.id, modelMappingChanges = listOf(ProviderModelMappingChange("m", "manual", "frozen")), expectedModelMappingRevision = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).revision)
        val channel = app.channelRepository.findProviderChannel(OAuthProvider.CHATGPT)!!
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val update = client.put("/admin/api/oauth-providers/chatgpt/models") {
                header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json); setBody("""{"models":[]}""")
            }
            assertEquals(HttpStatusCode.NoContent, update.status)
            val stored = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).mappings.single().mapping
            assertFalse(stored.catalogActive)
            assertEquals("frozen", stored.canonicalModelId)
            val probe = client.post("/admin/api/channels/${channel.id}/probe-protocols") {
                header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json); setBody("""{"model":"m","keyId":"${account.id}"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, probe.status, probe.bodyAsText())
            assertTrue(probe.bodyAsText().contains("model_not_authorized"))
            val test = client.post("/admin/api/oauth-accounts/${account.id}/model-test") {
                header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json); setBody("""{"model":"m","protocol":"responses"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, test.status)
        }
        assertTrue(fake.requests.isEmpty())
        assertTrue(fake.modelRequests.isEmpty())
    }

    private suspend fun mappingAccount(name: String, provider: OAuthProvider = OAuthProvider.CHATGPT, models: List<String> = emptyList()): OAuthAccountRecord {
        val channelId = app.providerChannelProvisioner.ensure(provider)!!
        return app.oauthAccountRepository.create(NewOAuthAccount(name = name, provider = provider, refreshToken = app.credentialCipher.encrypt("refresh-secret"), accessToken = app.credentialCipher.encrypt("access-secret"), accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000, channelIds = listOf(channelId), models = models))
    }

    @Test
    fun `legacy OAUTH_ROUTING strategies never reorder saved OAuth identities`() = runBlocking {
        for (strategy in OAuthRoutingStrategy.entries) {
            app.close()
            val base = config()
            app = GatewayApplication(base.copy(oauth = base.oauth.copy(routing = strategy)))
            val model = "oauth-order-${strategy.value}"
            val channel = app.channelRepository.create(
                name = model, baseUrl = "${fake.baseUrl}/ok/v1", keys = emptyList(), authType = "bearer",
                protocols = listOf(GatewayProtocol.RESPONSES), enabled = true, modelMappings = mappingsOf(model),
            )
            suspend fun account(name: String): Pair<OAuthAccountRecord, String> {
                val accessToken = java.util.UUID.randomUUID().toString()
                val saved = app.oauthAccountRepository.create(NewOAuthAccount(
                    name = name, provider = OAuthProvider.CHATGPT,
                    refreshToken = app.credentialCipher.encrypt(java.util.UUID.randomUUID().toString()),
                    accessToken = app.credentialCipher.encrypt(accessToken),
                    accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                    channelIds = listOf(channel.id), models = listOf(model),
                ))
                app.oauthAccountRepository.updatePolicy(saved.id, OAuthAccountPolicy(windowLimit = 100, windowSeconds = 3_600))
                return saved to accessToken
            }
            val (first, firstToken) = account("first-${strategy.value}")
            val (second, secondToken) = account("second-${strategy.value}")
            val (omitted, _) = account("omitted-${strategy.value}")
            configureModelRoute(model, channel to second, channel to first)
            val key = createClientKey()
            val before = fake.requests.size
            testApplication {
                application { app.install(this) }
                suspend fun send(expectedAccount: OAuthAccountRecord, expectedToken: String) {
                    val response = client.post("/v1/responses") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"$model","input":"hi"}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals("Bearer $expectedToken", fake.requests.last().authorization)
                    val log = awaitRequestLog()
                    assertEquals(expectedAccount.id, log.channelKeyId)
                    assertEquals(1, log.upstreamAttempts)
                    assertEquals(listOf(expectedAccount.id), log.attempts.map { it.channelKeyId })
                    assertTrue(log.attempts.none { it.channelKeyId == omitted.id })
                }
                send(second, secondToken)
                // 使用量发生变化后，least_used/pace 和轮询也不能把另一个账号移到前面。
                send(second, secondToken)
                configureModelRoute(model, channel to first, channel to second)
                // drain 不能保留旧首选；新的显式顺序在下一次请求立即生效。
                send(first, firstToken)
            }
            assertEquals(before + 3, fake.requests.size)
            assertTrue(fake.tokenRequests.isEmpty())
        }
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
            protocols = listOf(GatewayProtocol.RESPONSES),
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
            protocols = listOf(GatewayProtocol.RESPONSES),
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
    fun `admin oauth dashboard lists the account and reports provider capabilities`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val account = createOAuthAccount(channel, "free@example.com")

        testApplication {
            application { app.install(this) }
            val (cookie, _) = client.loginAdmin()

            val listing = client.get("/admin/api/oauth-accounts") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, listing.status)
            assertEquals("no-store", listing.headers["Cache-Control"])
            val body = listing.bodyAsText()
            assertFalse(body.contains("oauth-refresh-token"))
            val entries = Json.parseToJsonElement(body).jsonArray
            val listed = entries.single { it.jsonObject["id"]!!.jsonPrimitive.content == account.id }.jsonObject
            assertEquals("chatgpt", listed["provider"]!!.jsonPrimitive.content)
            assertEquals("ACTIVE", listed["authState"]!!.jsonPrimitive.content)
            assertEquals("free@example.com", listed["accountEmail"]!!.jsonPrimitive.content)
            // 看板按账号自己的关联渠道展示。
            assertEquals(channel.id, listed["channelIds"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals(listed["createdAt"]!!.jsonPrimitive.long, listed["authorizedAt"]!!.jsonPrimitive.long)
            val policy = listed["policy"]!!.jsonObject
            assertEquals(10_800, policy["windowSeconds"]!!.jsonPrimitive.int)
            assertEquals(1, policy["maxConcurrentRequests"]!!.jsonPrimitive.int)
            assertEquals(0, listed["runtime"]!!.jsonObject["windowUsed"]!!.jsonPrimitive.int)

            val providers = client.get("/admin/api/oauth-providers") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, providers.status)
            val byProvider = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["configured"]!!.jsonPrimitive.boolean }
            // ChatGPT 使用内置 OAuth 客户端；Antigravity 仅在官方 agy 环境启用后可新增账号。
            assertEquals(mapOf("chatgpt" to true, "antigravity" to false), byProvider)
            val managed = Json.parseToJsonElement(providers.bodyAsText()).jsonArray
                .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["managedChannel"]!!.jsonPrimitive.boolean }
            // Antigravity 走 Code Assist 信封协议，账号同样挂到系统置备渠道。
            assertEquals(mapOf("chatgpt" to true, "antigravity" to true), managed)

            // 长期凭据只以密文落库，列表与详情都不回显。
            val stored = assertNotNull(app.oauthAccountRepository.find(account.id))
            assertNotEquals("oauth-refresh-token", stored.refreshTokenCiphertext)
            assertEquals(
                "oauth-refresh-token",
                app.credentialCipher.decrypt(
                    requireNotNull(stored.refreshTokenCiphertext),
                    requireNotNull(stored.refreshTokenNonce),
                ),
            )
            assertEquals("free@example.com", stored.accountEmail)
        }
    }

    @Test
    fun `admin oauth login start validates account proxy`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            suspend fun start(body: String) = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val badScheme = start("""{"provider":"chatgpt","proxyUrl":"socks5://127.0.0.1:1080"}""")
            assertEquals(HttpStatusCode.BadRequest, badScheme.status)
            assertTrue(badScheme.bodyAsText().contains("invalid_oauth_proxy"))

            // 远程代理主机默认不放行，避免管理面输入被用来访问任意内网主机。
            val remoteProxy = start("""{"provider":"chatgpt","proxyUrl":"http://proxy.example.com:8080"}""")
            assertEquals(HttpStatusCode.BadRequest, remoteProxy.status)
            assertTrue(remoteProxy.bodyAsText().contains("OAUTH_PROXY_ALLOWED_HOSTS"))

            val unknownProvider = start("""{"provider":"gemini"}""")
            assertEquals(HttpStatusCode.BadRequest, unknownProvider.status)
            assertTrue(unknownProvider.bodyAsText().contains("invalid_oauth_provider"))

            val missingChannel = start("""{"provider":"chatgpt","channelIds":["missing"]}""")
            assertEquals(HttpStatusCode.BadRequest, missingChannel.status)
            assertTrue(missingChannel.bodyAsText().contains("invalid_oauth_channels"))
        }
    }

    @Test
    fun `oauth model test rejects models the account is not authorized for`() = runBlocking {
        val channel = createChannel("openai", "ok")
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val account = createOAuthAccount(channel, "model-test@example.com", provider = OAuthProvider.ANTIGRAVITY)
            val accountId = account.id

            val update = client.put("/admin/api/oauth-accounts/$accountId/models") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"models":["gemini-3-pro"]}""")
            }
            assertEquals(HttpStatusCode.OK, update.status)

            // 账号只授权了 gemini-3-pro；测试接口必须加载这层限制，不能按“全部模型”放行。
            val rejected = client.post("/admin/api/oauth-accounts/$accountId/model-test") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gemini-3-flash","protocol":"gemini","prompt":"ping"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertTrue(rejected.bodyAsText().contains("model_not_authorized"))
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
    fun `admin oauth login rejects a duplicate account email`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createOAuthAccount(channel, "login@example.com")

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

            val completed = client.post("/admin/api/oauth-login/$loginId/complete") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"callbackUrl":"http://localhost:1455/auth/callback?code=auth-code&state=$state"}""")
            }
            assertEquals(HttpStatusCode.Conflict, completed.status)
            assertTrue(completed.bodyAsText().contains("oauth_account_email_exists"))
            // 冲突不会留下第二个账号。
            assertEquals(1, scalarInt("select count(*) from oauth_accounts where account_email = 'login@example.com'"))
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
                app.credentialCipher.decrypt(
                    requireNotNull(storedAccount.refreshTokenCiphertext),
                    requireNotNull(storedAccount.refreshTokenNonce),
                ),
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
    fun `antigravity rejects legacy oauth and agy remains administrator and csrf protected`() = runBlocking {
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/antigravity/agy/accounts").status)
            assertEquals(HttpStatusCode.Unauthorized, client.delete("/admin/api/antigravity/agy/accounts/fixture").status)
            val (cookie, csrf) = client.loginAdmin()
            assertEquals(HttpStatusCode.Forbidden, client.delete("/admin/api/antigravity/agy/accounts/fixture") {
                header(HttpHeaders.Cookie, cookie)
            }.status)
            val listing = client.get("/admin/api/antigravity/agy/accounts") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, listing.status)
            assertTrue(listing.bodyAsText().contains("\"configured\":false"))
            assertEquals("no-store", listing.headers["Cache-Control"])
            val providers = Json.parseToJsonElement(client.get("/admin/api/oauth-providers") {
                header(HttpHeaders.Cookie, cookie)
            }.bodyAsText()).jsonArray.map { it.jsonObject }
            assertFalse(providers.first { it.getValue("id").jsonPrimitive.content == "antigravity" }
                .getValue("configured").jsonPrimitive.boolean)
            assertTrue(providers.first { it.getValue("id").jsonPrimitive.content == "chatgpt" }
                .getValue("configured").jsonPrimitive.boolean)
            val forbidden = client.post("/admin/api/antigravity/agy/logins") {
                header(HttpHeaders.Cookie, cookie)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            val notConfigured = client.post("/admin/api/antigravity/agy/logins") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.BadRequest, notConfigured.status)
            assertTrue(notConfigured.bodyAsText().contains("agy_not_configured"))
            val legacy = client.post("/admin/api/oauth-login/start") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"antigravity"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, legacy.status)
            assertTrue(legacy.bodyAsText().contains("antigravity_agy_required"))
            assertTrue(fake.tokenRequests.isEmpty())
        }
    }

    @Test
    fun agyAdminHttpLoginMigratesLegacyCredentialsWithoutChangingRouteConfiguration() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("prism-agy-http-fixture-")
        val loginScript = directory.resolve("login.py")
        java.nio.file.Files.writeString(loginScript, """
            import json, os, sys
            root = sys.argv[sys.argv.index('--state') + 1]
            os.makedirs(root, mode=0o700, exist_ok=True)
            print(json.dumps({'event':'authorize', 'authorizeUrl':'https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture.apps.googleusercontent.com&response_type=code&state=fixture&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback'}), flush=True)
            assert json.loads(input())['code'] == 'fixture-code'
            print(json.dumps({'event':'logged_in'}), flush=True)
        """.trimIndent())
        java.nio.file.Files.writeString(directory.resolve("antigravity_acp_pilot.py"), """
            import json, sys
            for line in sys.stdin:
                request = json.loads(line)
                method = request['method']
                assert method in ['initialize', 'session/new']
                result = {'protocolVersion':1} if method == 'initialize' else {'sessionId':'fixture-session', 'models':{'availableModels':[{'modelId':'fixture-model'}], 'currentModelId':'fixture-model'}, '_meta':{'antigravity':{'tier':{'name':'Pro'}}}}
                print(json.dumps({'jsonrpc':'2.0', 'id':request['id'], 'result':result}), flush=True)
        """.trimIndent())
        app.close()
        app = GatewayApplication(config().copy(antigravityAgy = dev.soven.prismllm.config.AntigravityAgyConfig(
            loginScript, directory.resolve("profiles"))))
        try {
            testApplication {
                application { app.install(this) }
                val channel = createChannel("agy-legacy-fixture", "ok")
                val legacy = createOAuthAccount(channel, "fixture-legacy@example.invalid", provider = OAuthProvider.ANTIGRAVITY)
                val routesBeforeMigration = app.modelCredentialRouteRepository.list()
                val (cookie, csrf) = client.loginAdmin()
                val providers = Json.parseToJsonElement(client.get("/admin/api/oauth-providers") {
                    header(HttpHeaders.Cookie, cookie)
                }.bodyAsText()).jsonArray.map { it.jsonObject }
                assertTrue(providers.first { it.getValue("id").jsonPrimitive.content == "antigravity" }
                    .getValue("configured").jsonPrimitive.boolean)
                val started = client.post("/admin/api/antigravity/agy/logins") {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"CLI Pro","sourceAccountId":"SOURCE_ID"}""".replace("SOURCE_ID", legacy.id))
                }
                assertEquals(HttpStatusCode.Accepted, started.status)
                val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("loginId").jsonPrimitive.content
                suspend fun snapshot(loginId: String) = client.get("/admin/api/antigravity/agy/logins/LOGIN_ID".replace("LOGIN_ID", loginId)) {
                    header(HttpHeaders.Cookie, cookie)
                }
                suspend fun awaitState(loginId: String, expected: String) = kotlinx.coroutines.withTimeout(8_000) {
                    while (true) {
                        val value = Json.parseToJsonElement(snapshot(loginId).bodyAsText()).jsonObject
                        if (value.getValue("state").jsonPrimitive.content == expected) break
                        kotlinx.coroutines.delay(15)
                    }
                }
                awaitState(id, "AWAITING_CODE")
                val submitted = client.post("/admin/api/antigravity/agy/logins/LOGIN_ID/code".replace("LOGIN_ID", id)) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"code":"fixture-code"}""")
                }
                assertEquals(HttpStatusCode.Accepted, submitted.status)
                awaitState(id, "AUTHENTICATED")
                val listing = client.get("/admin/api/antigravity/agy/accounts") { header(HttpHeaders.Cookie, cookie) }
                val profile = Json.parseToJsonElement(listing.bodyAsText()).jsonObject.getValue("accounts").jsonArray.single().jsonObject
                assertEquals("PRO", profile.getValue("subscriptionTier").jsonPrimitive.content)
                assertEquals(legacy.id, profile.getValue("sourceAccountId").jsonPrimitive.content)
                assertEquals(true, profile.getValue("registered").jsonPrimitive.boolean)
                assertEquals(false, profile.getValue("routingEnabled").jsonPrimitive.boolean)
                assertEquals(false, profile.getValue("cleanupEligible").jsonPrimitive.boolean)
                assertTrue(profile.getValue("routeModels").jsonArray.isEmpty())
                assertFalse(listing.bodyAsText().contains("fixture-code"))
                val after = requireNotNull(app.oauthAccountRepository.findDetailed(legacy.id))
                assertTrue(after.isAgy)
                assertEquals(profile.getValue("id").jsonPrimitive.content, after.agyProfileId)
                assertNull(after.refreshTokenCiphertext)
                assertNull(after.refreshTokenNonce)
                assertNull(after.refreshTokenKeyVersion)
                assertNull(after.accessTokenCiphertext)
                assertNull(after.accessTokenNonce)
                assertNull(after.accessTokenKeyVersion)
                assertEquals(legacy.channelIds, after.channelIds)
                assertEquals(routesBeforeMigration, app.modelCredentialRouteRepository.list())
                val audit = client.get("/admin/api/audit-logs?limit=30") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
                assertTrue(audit.contains("antigravity.agy.login.code"))
                assertFalse(audit.contains("fixture-code"))
                assertFalse(audit.contains("accounts.google.com"))
                assertTrue(fake.tokenRequests.none { it.body.contains("grant_type=authorization_code") })

                val edited = requireNotNull(app.oauthAccountRepository.updateSettings(
                    legacy.id,
                    name = "Admin label",
                    proxyUrl = "http://proxy.example.invalid:8080",
                    now = after.authorizedAt + 1_000,
                ))
                repeat(3) { snapshot(id) }
                val refreshed = client.get("/admin/api/antigravity/agy/accounts") { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, refreshed.status)
                val refreshedAccount = requireNotNull(app.oauthAccountRepository.findDetailed(legacy.id))
                assertEquals("Admin label", refreshedAccount.name)
                assertEquals("http://proxy.example.invalid:8080", refreshedAccount.proxyUrl)
                assertEquals(edited.authorizedAt, refreshedAccount.authorizedAt)

                val protectedCleanup = client.delete("/admin/api/antigravity/agy/accounts/" + profile.getValue("id").jsonPrimitive.content) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.Conflict, protectedCleanup.status)
                assertTrue(protectedCleanup.bodyAsText().contains("agy_profile_referenced"))

                val deletedMigration = client.delete("/admin/api/oauth-accounts/" + legacy.id) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.NoContent, deletedMigration.status)
                assertNull(app.oauthAccountRepository.findDetailed(legacy.id))
                val afterMigrationDelete = client.get("/admin/api/antigravity/agy/accounts") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertEquals(HttpStatusCode.OK, afterMigrationDelete.status, afterMigrationDelete.bodyAsText())
                val orphanedMigration = Json.parseToJsonElement(afterMigrationDelete.bodyAsText()).jsonObject
                    .getValue("accounts").jsonArray.single().jsonObject
                assertEquals(false, orphanedMigration.getValue("registered").jsonPrimitive.boolean)
                assertEquals(false, orphanedMigration.getValue("cleanupEligible").jsonPrimitive.boolean)

                val routeProtectedCleanup = client.delete("/admin/api/antigravity/agy/accounts/" + profile.getValue("id").jsonPrimitive.content) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.Conflict, routeProtectedCleanup.status)
                assertTrue(routeProtectedCleanup.bodyAsText().contains("agy_profile_referenced"))
                routesBeforeMigration.forEach { route ->
                    app.modelCredentialRouteRepository.save(
                        route.modelId,
                        route.revision,
                        route.entries.filterNot { it.credentialId == legacy.id },
                    )
                }
                val eligibleListing = client.get("/admin/api/antigravity/agy/accounts") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertTrue(Json.parseToJsonElement(eligibleListing.bodyAsText()).jsonObject
                    .getValue("accounts").jsonArray.single().jsonObject.getValue("cleanupEligible").jsonPrimitive.boolean)
                val cleanup = client.delete("/admin/api/antigravity/agy/accounts/" + profile.getValue("id").jsonPrimitive.content) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.NoContent, cleanup.status, cleanup.bodyAsText())
                assertFalse(java.nio.file.Files.exists(directory.resolve("profiles").resolve(profile.getValue("id").jsonPrimitive.content)))
                val afterProfileCleanup = client.get("/admin/api/antigravity/agy/accounts") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertTrue(Json.parseToJsonElement(afterProfileCleanup.bodyAsText()).jsonObject
                    .getValue("accounts").jsonArray.isEmpty())
                val cleanupAudit = client.get("/admin/api/audit-logs?limit=30") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
                assertTrue(cleanupAudit.contains("antigravity.agy.profile.delete"))
                assertFalse(cleanupAudit.contains(directory.toString()))

                val newStarted = client.post("/admin/api/antigravity/agy/logins") {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"New CLI account"}""")
                }
                assertEquals(HttpStatusCode.Accepted, newStarted.status)
                val newLoginId = Json.parseToJsonElement(newStarted.bodyAsText()).jsonObject
                    .getValue("loginId").jsonPrimitive.content
                awaitState(newLoginId, "AWAITING_CODE")
                val newSubmitted = client.post("/admin/api/antigravity/agy/logins/LOGIN_ID/code".replace("LOGIN_ID", newLoginId)) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"code":"fixture-code"}""")
                }
                assertEquals(HttpStatusCode.Accepted, newSubmitted.status)
                awaitState(newLoginId, "AUTHENTICATED")
                val newProfileId = Json.parseToJsonElement(snapshot(newLoginId).bodyAsText()).jsonObject
                    .getValue("account").jsonObject.getValue("id").jsonPrimitive.content
                assertNotNull(app.oauthAccountRepository.findDetailed(newProfileId))
                val deletedNew = client.delete("/admin/api/oauth-accounts/" + newProfileId) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.NoContent, deletedNew.status)
                val afterNewDelete = client.get("/admin/api/antigravity/agy/accounts") {
                    header(HttpHeaders.Cookie, cookie)
                }
                assertEquals(HttpStatusCode.OK, afterNewDelete.status, afterNewDelete.bodyAsText())
                assertEquals(false, Json.parseToJsonElement(afterNewDelete.bodyAsText()).jsonObject
                    .getValue("accounts").jsonArray.map { it.jsonObject }
                    .single { it.getValue("id").jsonPrimitive.content == newProfileId }
                    .getValue("registered").jsonPrimitive.boolean)
                assertNull(app.oauthAccountRepository.findDetailed(newProfileId))
            }
        } finally {
            app.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun agyCleanupProtectsLegacySourceAndAllSavedCredentialIdentities() = runBlocking {
        val directory = Files.createTempDirectory("prism-agy-cleanup-fixture-")
        val state = Files.createDirectory(directory.resolve("profiles"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val channel = createChannel("agy-cleanup-fixture", "ok")
        val legacy = createOAuthAccount(channel, "cleanup-fixture@example.invalid", provider = OAuthProvider.ANTIGRAVITY)
        val profile = AntigravityAgyAccount(UUID.randomUUID().toString(), "orphan with source", legacy.id, authorizedAt = 1)
        val metadata = Files.writeString(state.resolve("accounts.json"), Json.encodeToString(listOf(profile)))
        Files.setPosixFilePermissions(metadata, PosixFilePermissions.fromString("rw-------"))
        val home = Files.createDirectory(state.resolve(profile.id))
        Files.writeString(home.resolve("credential.fixture"), "synthetic credential")
        app.close()
        app = GatewayApplication(config().copy(antigravityAgy = AntigravityAgyConfig(directory.resolve("unused.py"), state)))
        try {
            testApplication {
                application { app.install(this) }
                val (cookie, csrf) = client.loginAdmin()
                suspend fun cleanup() = client.delete("/admin/api/antigravity/agy/accounts/" + profile.id) {
                    header(HttpHeaders.Cookie, cookie)
                    header("X-CSRF-Token", csrf)
                }
                assertEquals(HttpStatusCode.Conflict, cleanup().status)
                assertTrue(Files.exists(home))
                app.oauthAccountRepository.delete(legacy.id)
                app.modelCredentialRouteRepository.list().forEach { route ->
                    app.modelCredentialRouteRepository.save(route.modelId, route.revision, emptyList())
                }
                for (credentialId in listOf(profile.id, legacy.id)) {
                    for (type in CredentialRouteType.entries) {
                        val route = app.modelCredentialRouteRepository.find("cleanup-fixture-model")
                        val saved = app.modelCredentialRouteRepository.save("cleanup-fixture-model", route?.revision ?: 0,
                            listOf(CredentialRouteIdentity(channel.id, type, credentialId)))
                        assertEquals(HttpStatusCode.Conflict, cleanup().status)
                        assertTrue(Files.exists(home))
                        app.modelCredentialRouteRepository.save(saved.modelId, saved.revision, emptyList())
                    }
                }
                assertEquals(HttpStatusCode.NoContent, cleanup().status)
                assertFalse(Files.exists(home))
                assertEquals(HttpStatusCode.NotFound, cleanup().status)
            }
        } finally {
            app.close()
            directory.toFile().deleteRecursively()
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
            refreshToken = "test-only-conflict-refresh-token",
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
}
