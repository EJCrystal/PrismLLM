package dev.soven.prismllm.integration

import dev.soven.prismllm.api.ModelCredentialRouteIdentityDto
import dev.soven.prismllm.api.SaveModelCredentialRouteRequest
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
@Tag("channel")
class ModelCredentialRoutesIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun `admin route API is authenticated ordered revision guarded and secret free`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val staticSecret = UUID.randomUUID().toString()
            val refreshSecret = UUID.randomUUID().toString()
            val accessSecret = UUID.randomUUID().toString()
            val model = "vendor/family"
            val channel = routeChannel("static", model, credential = staticSecret, aliases = listOf("alias-b", "alias-a"))
            val oauthChannel = routeChannel("oauth", model, withKey = false)
            val account = app.oauthAccountRepository.create(NewOAuthAccount(
                name = "account", provider = OAuthProvider.CHATGPT,
                refreshToken = app.credentialCipher.encrypt(refreshSecret),
                accessToken = app.credentialCipher.encrypt(accessSecret),
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                channelIds = listOf(oauthChannel.id), models = listOf("upstream"),
            ))
            routeChannel("other", "vendor/other")
            val static = ModelCredentialRouteIdentityDto(channel.id, "static", channel.keys.single().id)
            val oauth = ModelCredentialRouteIdentityDto(oauthChannel.id, "oauth", account.id)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/model-routes").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/model-routes/options").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/model-routes/detail") { parameter("modelId", model) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.saveRoute(model, 0, listOf(static), null, csrf).status)
            assertEquals(HttpStatusCode.Forbidden, client.saveRoute(model, 0, listOf(static), cookie, null).status)
            assertNull(app.modelCredentialRouteRepository.find(model))
            assertTrue(app.modelCatalog.findCandidates(model, GatewayProtocol.RESPONSES).isEmpty())

            val initial = client.get("/admin/api/model-routes/detail") {
                header(HttpHeaders.Cookie, cookie)
                parameter("modelId", model)
            }
            assertEquals(HttpStatusCode.OK, initial.status, initial.bodyAsText())
            assertEquals("no-store", initial.headers[HttpHeaders.CacheControl])
            val detail = initial.payload()
            assertFalse(detail["configured"]!!.jsonPrimitive.boolean)
            assertEquals(0L, detail["revision"]!!.jsonPrimitive.long)
            assertTrue(detail["entries"]!!.jsonArray.isEmpty())
            assertEquals(2, detail["availableCredentials"]!!.jsonArray.size)
            val staticMetadata = detail["availableCredentials"]!!.jsonArray.single {
                it.jsonObject["credentialId"]!!.jsonPrimitive.content == static.credentialId
            }.jsonObject
            assertEquals(listOf("alias-a", "alias-b"), staticMetadata["upstreamModels"]!!.jsonArray.map { it.jsonPrimitive.content })

            val listed = client.get("/admin/api/model-routes") {
                header(HttpHeaders.Cookie, cookie)
                parameter("q", "VENDOR/")
                parameter("offset", 1)
                parameter("limit", 1)
            }.payload()
            assertEquals(2, listed["total"]!!.jsonPrimitive.int)
            assertEquals(1, listed["offset"]!!.jsonPrimitive.int)
            assertEquals(1, listed["limit"]!!.jsonPrimitive.int)
            assertEquals("vendor/other", listed["items"]!!.jsonArray.single().jsonObject["modelId"]!!.jsonPrimitive.content)
            assertNull(app.modelCredentialRouteRepository.find(model), "reads must not initialize future routes")

            val saved = client.saveRoute(model, 0, listOf(oauth, static), cookie, csrf)
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertEquals("no-store", saved.headers[HttpHeaders.CacheControl])
            assertEquals(listOf(oauth.credentialId, static.credentialId), saved.payload().entryIds())
            assertEquals(1L, saved.payload()["revision"]!!.jsonPrimitive.long)
            assertTrue(saved.payload()["configured"]!!.jsonPrimitive.boolean)
            listOf(staticSecret, refreshSecret, accessSecret, requireNotNull(account.refreshTokenCiphertext)).forEach { secret ->
                assertFalse(saved.bodyAsText().contains(secret))
            }
            val metadataKeys = saved.payload()["entries"]!!.jsonArray.first().jsonObject.keys
            assertTrue(metadataKeys.all { it in setOf("channelId", "credentialType", "credentialId", "channelName", "credentialName", "upstreamModels", "protocols", "selectable", "reason") })

            val options = client.get("/admin/api/model-routes/options") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, options.status, options.bodyAsText())
            assertEquals("no-store", options.headers[HttpHeaders.CacheControl])
            val option = options.payload()["models"]!!.jsonArray.single { it.jsonObject["modelId"]!!.jsonPrimitive.content == model }.jsonObject
            assertEquals(listOf(oauth.credentialId, static.credentialId), option["entries"]!!.jsonArray.map { it.jsonObject["credentialId"]!!.jsonPrimitive.content })
            val optionKeys = option["entries"]!!.jsonArray.first().jsonObject.keys
            assertTrue(optionKeys.containsAll(setOf("channelId", "credentialType", "credentialId", "selectable")))
            assertTrue(optionKeys.all { it in setOf("channelId", "credentialType", "credentialId", "selectable", "reason") })
            listOf(staticSecret, refreshSecret, accessSecret, requireNotNull(account.refreshTokenCiphertext)).forEach { secret ->
                assertFalse(options.bodyAsText().contains(secret))
            }

            val reordered = client.saveRoute(model, 1, listOf(static, oauth), cookie, csrf)
            assertEquals(HttpStatusCode.OK, reordered.status, reordered.bodyAsText())
            assertEquals(listOf(static.credentialId, oauth.credentialId), reordered.payload().entryIds())
            val conflict = client.saveRoute(model, 1, emptyList(), cookie, csrf)
            assertEquals(HttpStatusCode.Conflict, conflict.status, conflict.bodyAsText())
            assertTrue(conflict.bodyAsText().contains("model_route_revision_changed"))
            assertEquals(2L, app.modelCredentialRouteRepository.find(model)!!.revision)
            val cleared = client.saveRoute(model, 2, emptyList(), cookie, csrf)
            assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
            assertTrue(cleared.payload()["configured"]!!.jsonPrimitive.boolean)
            assertTrue(cleared.payload()["entries"]!!.jsonArray.isEmpty())
            assertTrue(app.modelCatalog.findCandidates(model, GatewayProtocol.RESPONSES).isEmpty())
            assertTrue(app.modelCredentialRouteRepository.find(model)!!.entries.isEmpty())

            val audit = app.adminAuditRepository.list(100).first { it.action == "model_route.update" && it.statusCode == 200 }
            assertEquals(setOf("modelId", "revision", "entryCount"), assertNotNull(audit.details).keys)
            assertEquals(model, audit.details!!["modelId"]!!.jsonPrimitive.content)
            assertEquals(3L, audit.details!!["revision"]!!.jsonPrimitive.long)
            assertEquals(0, audit.details!!["entryCount"]!!.jsonPrimitive.int)
            listOf(staticSecret, refreshSecret, accessSecret).forEach { assertFalse(audit.details.toString().contains(it)) }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `previously selected disabled and deleted identities remain but cannot be newly selected`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val model = "retained/model"
            val channel = routeChannel("retained", model)
            val keyId = channel.keys.single().id
            val account = app.oauthAccountRepository.create(NewOAuthAccount(
                name = "retained-account", provider = OAuthProvider.CHATGPT,
                refreshToken = app.credentialCipher.encrypt(UUID.randomUUID().toString()),
                channelIds = listOf(channel.id),
            ))
            val static = ModelCredentialRouteIdentityDto(channel.id, "static", keyId)
            val oauth = ModelCredentialRouteIdentityDto(channel.id, "oauth", account.id)
            assertEquals(HttpStatusCode.OK, client.saveRoute(model, 0, listOf(static, oauth), cookie, csrf).status)
            execute("update channel_keys set state = 'MANUAL_DISABLED' where id = ?", keyId)
            execute("update oauth_accounts set auth_state = 'REAUTH_REQUIRED', enabled = false where id = ?", account.id)
            val retained = client.saveRoute(model, 1, listOf(oauth, static), cookie, csrf)
            assertEquals(HttpStatusCode.OK, retained.status, retained.bodyAsText())
            assertTrue(retained.payload()["entries"]!!.jsonArray.all { !it.jsonObject["selectable"]!!.jsonPrimitive.boolean })
            assertEquals(HttpStatusCode.OK, client.saveRoute(model, 2, listOf(static), cookie, csrf).status)
            val disabledAddition = client.saveRoute(model, 3, listOf(static, oauth), cookie, csrf)
            assertEquals(HttpStatusCode.BadRequest, disabledAddition.status, disabledAddition.bodyAsText())
            assertEquals(3L, app.modelCredentialRouteRepository.find(model)!!.revision)

            app.channelRepository.delete(channel.id)
            val missing = client.saveRoute(model, 3, listOf(static), cookie, csrf)
            assertEquals(HttpStatusCode.OK, missing.status, missing.bodyAsText())
            val metadata = missing.payload()["entries"]!!.jsonArray.single().jsonObject
            assertEquals(channel.id, metadata["channelName"]!!.jsonPrimitive.content)
            assertEquals(keyId, metadata["credentialName"]!!.jsonPrimitive.content)
            assertEquals("channel_missing", metadata["reason"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.saveRoute(model, 4, emptyList(), cookie, csrf).status)
            val missingAddition = client.saveRoute(model, 5, listOf(static), cookie, csrf)
            assertEquals(HttpStatusCode.BadRequest, missingAddition.status, missingAddition.bodyAsText())
            assertEquals(5L, app.modelCredentialRouteRepository.find(model)!!.revision)
        }
    }

    @Test
    fun `new identities require active matching channel model and protocol and failures roll back`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            val model = "validation/model"
            val channel = routeChannel("valid", model)
            val disabled = routeChannel("disabled", model, state = ChannelKeyState.MANUAL_DISABLED)
            val temporary = routeChannel("temporary", model, state = ChannelKeyState.AUTO_TEMPORARY)
            val otherModel = routeChannel("other-model", "other/model")
            val disabledChannel = routeChannel("disabled-channel", model, enabled = false)
            val noProtocol = routeChannel("no-protocol", model)
            execute("update channels set protocols = 'unknown' where id = ?", noProtocol.id)
            val noModels = routeChannel("no-models", model, aliases = listOf("upstream"), keyModels = emptyList())
            val account = app.oauthAccountRepository.create(NewOAuthAccount(
                name = "protocol-mismatch", provider = OAuthProvider.ANTIGRAVITY,
                refreshToken = app.credentialCipher.encrypt(UUID.randomUUID().toString()), channelIds = listOf(channel.id),
            ))
            val restricted = app.oauthAccountRepository.create(NewOAuthAccount(
                name = "authorization-mismatch", provider = OAuthProvider.CHATGPT,
                refreshToken = app.credentialCipher.encrypt(UUID.randomUUID().toString()), channelIds = listOf(channel.id),
                models = listOf("unrelated"),
            ))
            val catalogRestricted = app.oauthAccountRepository.create(NewOAuthAccount(
                name = "catalog-mismatch", provider = OAuthProvider.CHATGPT,
                refreshToken = app.credentialCipher.encrypt(UUID.randomUUID().toString()), channelIds = listOf(channel.id),
            ))
            execute("update oauth_accounts set model_catalog = '[]' where id = ?", catalogRestricted.id)
            fun static(target: ChannelRecord) = ModelCredentialRouteIdentityDto(target.id, "static", target.keys.single().id)
            val valid = static(channel)
            val invalid = listOf(
                valid.copy(channelId = otherModel.id), valid.copy(credentialType = "oauth"),
                valid.copy(credentialId = UUID.randomUUID().toString()),
                static(disabled), static(temporary), static(otherModel), static(disabledChannel), static(noProtocol), static(noModels),
                ModelCredentialRouteIdentityDto(channel.id, "oauth", account.id),
                ModelCredentialRouteIdentityDto(channel.id, "oauth", restricted.id),
                ModelCredentialRouteIdentityDto(channel.id, "oauth", catalogRestricted.id),
                ModelCredentialRouteIdentityDto(otherModel.id, "oauth", account.id),
            )
            invalid.forEach { identity ->
                val response = client.saveRoute(model, 0, listOf(identity), cookie, csrf)
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("invalid_model_route_credential"))
                assertNull(app.modelCredentialRouteRepository.find(model))
            }
            val duplicate = client.saveRoute(model, 0, listOf(valid, valid), cookie, csrf)
            assertEquals(HttpStatusCode.BadRequest, duplicate.status)
            assertTrue(duplicate.bodyAsText().contains("invalid_model_route"))
            val missingRevision = client.put("/admin/api/model-routes") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"modelId":"$model","entries":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, missingRevision.status)
            assertEquals(HttpStatusCode.NotFound, client.saveRoute("unknown/model", 0, emptyList(), cookie, csrf).status)
            assertNull(app.modelCredentialRouteRepository.find("unknown/model"))
            assertNull(app.modelCredentialRouteRepository.find(model))
            assertEquals(HttpStatusCode.OK, client.saveRoute(model, 0, listOf(valid), cookie, csrf).status)
        }
    }

    private suspend fun routeChannel(
        name: String,
        model: String,
        credential: String = UUID.randomUUID().toString(),
        aliases: List<String> = listOf("upstream"),
        keyModels: List<String> = aliases,
        state: ChannelKeyState = ChannelKeyState.ACTIVE,
        enabled: Boolean = true,
        withKey: Boolean = true,
    ): ChannelRecord = app.channelRepository.create(
        name = name, baseUrl = "${fake.baseUrl}/ok/v1", authType = "bearer", enabled = enabled,
        protocols = listOf(GatewayProtocol.RESPONSES),
        keys = if (withKey) listOf(NewChannelKey(name, app.credentialCipher.encrypt(credential), state = state, models = keyModels)) else emptyList(),
        modelMappings = aliases.map { ChannelModelRecord(it, model) },
    )

    private suspend fun HttpClient.saveRoute(
        model: String,
        revision: Long,
        entries: List<ModelCredentialRouteIdentityDto>,
        cookie: String?,
        csrf: String?,
    ) = put("/admin/api/model-routes") {
        cookie?.let { header(HttpHeaders.Cookie, it) }
        csrf?.let { header("X-CSRF-Token", it) }
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(SaveModelCredentialRouteRequest(model, revision, entries)))
    }

    private suspend fun HttpResponse.payload(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.entryIds(): List<String> = getValue("entries").jsonArray.map {
        it.jsonObject["credentialId"]!!.jsonPrimitive.content
    }

    private fun execute(sql: String, id: String) {
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
    }
}
