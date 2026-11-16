package dev.soven.prismllm.integration

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.admin.domain.WebSearchSettingsRecord
import dev.soven.prismllm.channel.domain.ChannelKeyRateLimitPolicy
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitDecision
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.oauth.ProviderModelMappingChange
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.persistence.NewOAuthAccount
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity
import dev.soven.prismllm.routing.readmodel.CredentialRouteType
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.OAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 使用真实持久化、请求准入和假上游验证调用方访问范围的硬性边界。 */
@Tag("integration")
@Tag("shard-1")
@Tag("channel")
@Tag("protocol")
class ClientKeyRoutingAccessIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun `access denial precedes all four protocols Gemini versions and search preparation`() = runBlocking {
        accessChannel("all", models = listOf("allowed", "denied"), protocols = GatewayProtocol.entries.toList())
        val key = scopedKey(ClientKeyAccessPolicy(allowedModelIds = setOf("allowed")))
        testApplication {
            application { app.install(this) }
            val requests = listOf(
                "/v1/chat/completions" to """{"model":"denied","messages":[]}""",
                "/v1/responses" to """{"model":"denied","input":"hi","tools":[{"type":"web_search"}]}""",
                "/v1/messages" to """{"model":"denied","max_tokens":8,"messages":[]}""",
                "/v1beta/models/denied:generateContent" to """{"model":"allowed","contents":[]}""",
                "/v1beta/models/denied:streamGenerateContent" to """{"contents":[]}""",
                "/v1/models/denied:generateContent" to """{"model":"allowed","contents":[]}""",
                "/v1/models/denied:streamGenerateContent" to """{"contents":[]}""",
            )
            listOf(key to "model_access_denied", scopedKey(ClientKeyAccessPolicy(allowedChannelIds = emptySet())) to "route_access_denied")
                .forEach { (callerKey, code) ->
                    requests.forEach { (path, body) -> assertRejected(client.send(callerKey, path, body), code) }
                }
            assertEquals(0, fake.requests.size)
            assertEquals(0, fake.tokenRequests.size)
            assertEquals(0, fake.quotaRequests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
            assertEquals(14, app.requestLogRepository.list(30).size)
            assertTrue(app.requestLogRepository.list(30).all { it.upstreamAttempts == 0 && it.attempts.isEmpty() })
            listOf("/v1/models", "/v1beta/models").forEach { path ->
                val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $key") }
                val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val field = if (path == "/v1/models") "data" else "models"
                val idField = if (path == "/v1/models") "id" else "name"
                val expected = if (path == "/v1/models") "allowed" else "models/allowed"
                assertEquals(listOf(expected), root[field]!!.jsonArray.map { it.jsonObject[idField]!!.jsonPrimitive.content })
            }
            assertEquals(HttpStatusCode.OK, client.chat(key, "allowed").status)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun `unknown model outside model allowlist stays not found across all protocols`() = runBlocking {
        accessChannel("known", models = listOf("allowed"), protocols = GatewayProtocol.entries.toList())
        val key = scopedKey(ClientKeyAccessPolicy(allowedModelIds = setOf("allowed")))
        testApplication {
            application { app.install(this) }
            missingModelRequests("typo").forEach { (path, body) ->
                assertMissing(client.send(key, path, body), key)
            }
            assertEquals(7, app.requestLogRepository.list(30).size)
        }
    }

    @Test
    fun `deleted model outside model allowlist stays not found across all protocols`() = runBlocking {
        accessChannel("known", models = listOf("allowed"), protocols = GatewayProtocol.entries.toList())
        val deleted = accessChannel("removed", models = listOf("deleted"), protocols = GatewayProtocol.entries.toList())
        val key = scopedKey(ClientKeyAccessPolicy(allowedModelIds = setOf("allowed")))
        app.channelRepository.delete(deleted.id)
        testApplication {
            application { app.install(this) }
            missingModelRequests("deleted").forEach { (path, body) ->
                assertMissing(client.send(key, path, body), key)
            }
            assertEquals(7, app.requestLogRepository.list(30).size)
        }
    }

    @Test
    fun `channel key and public model intersection filters routing and both listings`() = runBlocking {
        val first = accessChannel("first", models = listOf("raw-first", "first-only"), priority = 0)
        val second = accessChannel("second", models = listOf("raw-second", "second-only"), priority = 1,
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.GEMINI))
        exposeAsModel("PublicModel", first to "raw-first", second to "raw-second")
        val key = scopedKey(ClientKeyAccessPolicy(
            allowedChannelIds = setOf(first.id, second.id),
            allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(second.id, second.keys.single().id)),
            allowedModelIds = setOf("PublicModel", "first-only", "second-only"),
        ))
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.OK, client.chat(key, "PublicModel").status)
            assertEquals(listOf("Bearer second-secret"), fake.requests.map { it.authorization })
            assertTrue(fake.requests.single().body.contains("raw-second"))
            assertRejected(client.chat(key, "first-only"), "route_access_denied")
            val openAi = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
            val openAiIds = Json.parseToJsonElement(openAi.bodyAsText()).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf("PublicModel", "second-only"), openAiIds)
            val gemini = client.get("/v1beta/models") { header("x-goog-api-key", key) }
            val geminiIds = Json.parseToJsonElement(gemini.bodyAsText()).jsonObject["models"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(listOf("models/PublicModel", "models/second-only"), geminiIds)
            assertEquals(1, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `empty whitelist of each dimension denies and exposes no models`() = runBlocking {
        accessChannel("empty")
        val policies = listOf(
            ClientKeyAccessPolicy(allowedChannelIds = emptySet()) to "route_access_denied",
            ClientKeyAccessPolicy(allowedChannelKeys = emptySet()) to "route_access_denied",
            ClientKeyAccessPolicy(allowedModelIds = emptySet()) to "model_access_denied",
        )
        testApplication {
            application { app.install(this) }
            policies.forEach { (policy, code) ->
                val key = scopedKey(policy)
                assertRejected(client.chat(key), code)
                listOf("/v1/models", "/v1beta/models").forEach { path ->
                    val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $key") }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val field = if (path == "/v1/models") "data" else "models"
                    assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonObject[field]!!.jsonArray.size)
                }
            }
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `disabled configured channel distinguishes denied authorized and unrestricted requests`() = runBlocking {
        val disabled = accessChannel("disabled", enabled = false)
        val other = accessChannel("other", models = listOf("m1", "other-model"))
        val unrelated = accessChannel("unrelated", models = listOf("other-model"))
        val denied = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(unrelated.id)))
        val modelDenied = scopedKey(ClientKeyAccessPolicy(allowedModelIds = setOf("other-model")))
        val authorized = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(disabled.id)))
        val unrestricted = scopedKey(ClientKeyAccessPolicy())
        testApplication {
            application { app.install(this) }
            assertRejected(client.chat(denied), "route_access_denied")
            val unavailable = client.chat(authorized)
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status, unavailable.bodyAsText())
            assertTrue(unavailable.bodyAsText().contains("model_credentials_unavailable"))
            assertNull(unavailable.headers[HttpHeaders.RetryAfter])
            val legacy = client.chat(unrestricted)
            assertEquals(HttpStatusCode.OK, legacy.status)
            assertEquals(1, fake.requests.size)
            app.channelRepository.delete(other.id)
            assertRejected(client.chat(modelDenied), "model_access_denied")
            val disabledOnly = client.chat(unrestricted)
            assertEquals(HttpStatusCode.ServiceUnavailable, disabledOnly.status)
            assertTrue(disabledOnly.bodyAsText().contains("model_credentials_unavailable"))
            assertEquals(1, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `only authorized temporary keys on active mappings contribute retry time and disabled keys stay unavailable`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "states", baseUrl = "${fake.baseUrl}/ok/v1", authType = "bearer",
            protocols = GatewayProtocol.default, enabled = true, modelMappings = listOf(
                ChannelModelRecord("m1"), ChannelModelRecord("inactive-upstream", "m1", "manual"),
            ),
            keys = listOf(
                NewChannelKey("temporary", app.credentialCipher.encrypt("temporary"), ChannelKeyState.AUTO_TEMPORARY, models = listOf("m1")),
                NewChannelKey("outside", app.credentialCipher.encrypt("outside"), ChannelKeyState.AUTO_TEMPORARY, models = listOf("m1")),
                NewChannelKey("inactive", app.credentialCipher.encrypt("inactive"), ChannelKeyState.AUTO_TEMPORARY, models = listOf("inactive-upstream")),
                NewChannelKey("permanent", app.credentialCipher.encrypt("permanent"), ChannelKeyState.AUTO_PERMANENT, models = listOf("m1")),
                NewChannelKey("manual", app.credentialCipher.encrypt("manual"), ChannelKeyState.MANUAL_DISABLED, models = listOf("m1")),
                NewChannelKey("active-outside", app.credentialCipher.encrypt("active-outside"), models = listOf("m1")),
            ),
        )
        configureModelRoute("m1", *channel.keys.map { channel to it }.toTypedArray())
        setRecovery(channel.keys.first { it.name == "temporary" }.id, System.currentTimeMillis() + 120_000)
        setRecovery(channel.keys.first { it.name == "outside" }.id, System.currentTimeMillis() + 20_000)
        val inactive = channel.keys.first { it.name == "inactive" }
        setRecovery(inactive.id, System.currentTimeMillis() + 60_000)
        // 保留遗留凭据的模型授权，确保是目录 active 判定而非授权裁剪排除更早的恢复时间。
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_models set catalog_active = false where channel_id = ? and upstream_model = ?").use { statement ->
                statement.setString(1, channel.id)
                statement.setString(2, "inactive-upstream")
                assertEquals(1, statement.executeUpdate())
            }
        }
        testApplication {
            application { app.install(this) }
            listOf("temporary", "permanent", "manual").forEach { name ->
                val selected = channel.keys.first { it.name == name }
                val allowedKeys = setOf(ClientKeyAllowedChannelKey(channel.id, selected.id)) +
                    if (name == "temporary") setOf(ClientKeyAllowedChannelKey(channel.id, inactive.id)) else emptySet()
                val key = scopedKey(ClientKeyAccessPolicy(allowedChannelKeys = allowedKeys))
                val response = client.chat(key)
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("model_credentials_unavailable"))
                if (name == "temporary") assertTrue(assertNotNull(response.headers[HttpHeaders.RetryAfter]).toLong() in 100..125)
                else assertNull(response.headers[HttpHeaders.RetryAfter])
            }
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `normal failover and all cooling fallback never use unselected credentials`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "pool", baseUrl = "${fake.baseUrl}/key-health/v1", authType = "bearer",
            protocols = GatewayProtocol.default, enabled = true, modelMappings = mappingsOf("m1"),
            keys = listOf("bad", "backup", "outside").mapIndexed { index, name ->
                NewChannelKey(name, app.credentialCipher.encrypt(name), priority = index,
                    models = listOf("m1"), rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1))
            },
        )
        configureModelRoute("m1", *listOf("bad", "backup", "outside").map { name -> channel to channel.keys.single { it.name == name } }.toTypedArray())
        fake.keyStatuses["Bearer bad"] = 500
        val bad = channel.keys.first { it.name == "bad" }
        val backup = channel.keys.first { it.name == "backup" }
        val onlyBad = scopedKey(ClientKeyAccessPolicy(allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, bad.id))))
        val withBackup = scopedKey(ClientKeyAccessPolicy(allowedChannelKeys = setOf(
            ClientKeyAllowedChannelKey(channel.id, bad.id), ClientKeyAllowedChannelKey(channel.id, backup.id))))
        testApplication {
            application { app.install(this) }
            repeat(2) {
                val response = client.chat(onlyBad)
                assertTrue(response.status.value >= 500, response.bodyAsText())
                assertEquals(it + 1, fake.requests.size)
                assertTrue(fake.requests.all { request -> request.authorization == "Bearer bad" })
                assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
                val lease = assertIs<ChannelKeyRateLimitDecision.Allowed>(app.channelKeyRateLimiter.acquire(bad)).lease
                app.channelKeyRateLimiter.release(lease)
            }
            app.candidateCooldown.clearKey(channel.id, bad.id)
            assertEquals(HttpStatusCode.OK, client.chat(withBackup).status)
            assertEquals(listOf("Bearer bad", "Bearer bad", "Bearer bad", "Bearer backup"), fake.requests.map { it.authorization })
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `allowed missing model and protocol conversion keep original behavior`() = runBlocking {
        val channel = accessChannel("chat-only", models = listOf("m1", "M1"), protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS))
        val key = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("m1", "missing")))
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.NotFound, client.chat(key, "missing").status)
            val protocol = client.send(key, "/v1beta/models/m1:generateContent", """{"contents":[]}""")
            assertEquals(HttpStatusCode.OK, protocol.status, protocol.bodyAsText())
            val models = client.get("/v1beta/models") { header(HttpHeaders.Authorization, "Bearer $key") }
            assertEquals(0, Json.parseToJsonElement(models.bodyAsText()).jsonObject["models"]!!.jsonArray.size)
            assertRejected(client.chat(key, "M1"), "model_access_denied")
            assertEquals(1, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `authorized requests use all native protocols and both Gemini URL versions`() = runBlocking {
        val channel = accessChannel("native", protocols = GatewayProtocol.entries.toList())
        val key = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("m1")))
        testApplication {
            application { app.install(this) }
            val requests = listOf(
                "/v1/chat/completions" to """{"model":"m1","messages":[]}""",
                "/v1/responses" to """{"model":"m1","input":"hi"}""",
                "/v1/messages" to """{"model":"m1","max_tokens":8,"messages":[]}""",
                "/v1beta/models/m1:generateContent" to """{"contents":[]}""",
                "/v1/models/m1:generateContent" to """{"contents":[]}""",
            )
            requests.forEach { (path, body) ->
                val response = client.send(key, path, body)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }
            assertEquals(5, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `static key allowlist excludes canonical OAuth routes before refresh and upstream calls`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "oauth-only", baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(NewChannelKey("static", app.credentialCipher.encrypt("static"), models = listOf("m2"))), authType = "bearer",
            protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES), enabled = true, modelMappings = mappingsOf("m1", "m2"),
        )
        createOAuthAccount(channel, "scope@example.test")
        exposeAsModel("oauth-public", channel to "m1")
        configureModelRoute("m2", channel to channel.keys.single())
        val key = scopedKey(ClientKeyAccessPolicy(
            allowedChannelIds = setOf(channel.id),
            allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id)),
            allowedModelIds = setOf("oauth-public", "m2"),
        ))
        testApplication {
            application { app.install(this) }
            assertMissing(client.chat(key, "m1"), key)
            assertRejected(client.chat(key, "oauth-public"), "route_access_denied")
            assertEquals(0, fake.requests.size)
            assertEquals(0, fake.tokenRequests.size)
            assertEquals(0, fake.quotaRequests.size)
            assertEquals(listOf("m2"), client.publicModels(key))
            val unrestricted = scopedKey(ClientKeyAccessPolicy())
            assertEquals(listOf("m2", "oauth-public"), client.publicModels(unrestricted))
        }
    }

    @Test
    fun `inactive OAuth mapping is not found in every caller scope and restoration keeps disabled authorization diagnostics`() = runBlocking {
        val channelId = assertNotNull(app.providerChannelProvisioner.ensure(OAuthProvider.CHATGPT))
        val channel = assertNotNull(app.channelRepository.find(channelId))
        app.channelRepository.update(channel.id, channel.name, "${fake.baseUrl}/ok/v1", authType = channel.authType, enabled = true)
        val account = app.oauthAccountRepository.create(NewOAuthAccount(
            name = "unsynchronized", provider = OAuthProvider.CHATGPT,
            refreshToken = app.credentialCipher.encrypt("refresh-secret"),
            accessToken = app.credentialCipher.encrypt("access-secret"),
            accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000, channelIds = listOf(channel.id),
        ))
        assertTrue(app.providerChannelProvisioner.setProviderModels(OAuthProvider.CHATGPT, listOf("upstream-variant")))
        app.providerChannelProvisioner.updateSettings(account.id,
            modelMappingChanges = listOf(ProviderModelMappingChange("upstream-variant", "manual", "canonical-family")),
            expectedModelMappingRevision = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).revision)
        configureModelRoute("canonical-family", channel to account)
        val unrestricted = scopedKey(ClientKeyAccessPolicy())
        val authorized = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("canonical-family")))
        val modelDenied = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(channel.id), allowedModelIds = emptySet()))
        val routeDenied = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = emptySet(), allowedModelIds = setOf("canonical-family")))
        val callers = listOf(unrestricted, authorized, modelDenied, routeDenied)
        testApplication {
            application { app.install(this) }
            assertEquals(listOf("canonical-family"), client.publicModels(authorized))
            // 宿主已完成启动对账；未知账号支持上游名，但不能复活之后休眠的人工映射。
            assertTrue(app.providerChannelProvisioner.setProviderModels(OAuthProvider.CHATGPT, emptyList()))
            val unknown = assertNotNull(app.oauthAccountRepository.findDetailed(account.id))
            assertNull(unknown.modelCatalog)
            assertTrue(unknown.supports("upstream-variant"))
            val dormant = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).mappings.single().mapping
            assertFalse(dormant.catalogActive)
            assertEquals("canonical-family", dormant.publicModelId)
            callers.forEach { caller ->
                assertTrue(client.publicModels(caller).isEmpty())
                assertMissing(client.responses(caller, "canonical-family"), caller)
            }
            assertTrue(app.providerChannelProvisioner.syncModelCatalog(account.id,
                listOf(OAuthAccountModelCatalogEntry("upstream-variant", "automatic-family"))))
            assertTrue(app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).mappings.single().mapping.catalogActive)
            assertEquals(listOf("canonical-family"), client.publicModels(authorized))
            assertEquals(listOf("canonical-family"), client.publicModels(unrestricted))
            assertTrue(client.publicModels(modelDenied).isEmpty())
            assertTrue(client.publicModels(routeDenied).isEmpty())
            assertMissing(client.responses(authorized, "upstream-variant"), authorized)
            assertRejected(client.responses(modelDenied, "canonical-family"), "model_access_denied")
            assertRejected(client.responses(routeDenied, "canonical-family"), "route_access_denied")
            val restored = client.responses(authorized, "canonical-family")
            assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
            assertEquals("upstream-variant", Json.parseToJsonElement(fake.requests.single().body).jsonObject["model"]!!.jsonPrimitive.content)
            assertEquals("Bearer access-secret", fake.requests.single().authorization)
            assertNotNull(app.providerChannelProvisioner.updateSettings(account.id, enabled = false))
            listOf(unrestricted, authorized).forEach { caller ->
                val unavailable = client.responses(caller, "canonical-family")
                assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status, unavailable.bodyAsText())
                assertTrue(unavailable.bodyAsText().contains("model_credentials_unavailable"))
                assertNull(unavailable.headers[HttpHeaders.RetryAfter])
            }
            assertRejected(client.responses(modelDenied, "canonical-family"), "model_access_denied")
            assertRejected(client.responses(routeDenied, "canonical-family"), "route_access_denied")
            callers.forEach { assertTrue(client.publicModels(it).isEmpty()) }
            assertEquals(1, fake.requests.size)
            assertEquals(0, fake.tokenRequests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `shared OAuth mapping mode changes require explicit caller grants and rejected admin edits roll back after policy roundtrip`() = runBlocking {
        val channelId = assertNotNull(app.providerChannelProvisioner.ensure(OAuthProvider.CHATGPT))
        val channel = assertNotNull(app.channelRepository.find(channelId))
        app.channelRepository.update(channel.id, channel.name, "${fake.baseUrl}/ok/v1", authType = channel.authType, enabled = true)
        val accounts = listOf("upstream-a", "upstream-b").map { upstream ->
            app.oauthAccountRepository.create(NewOAuthAccount(
                name = upstream, provider = OAuthProvider.CHATGPT,
                refreshToken = app.credentialCipher.encrypt("refresh-$upstream"),
                accessToken = app.credentialCipher.encrypt("access-$upstream"),
                accessTokenExpiresAt = System.currentTimeMillis() + 3_600_000,
                channelIds = listOf(channel.id), models = listOf(upstream),
            ))
        }
        val catalog = listOf("upstream-a", "upstream-b").map { OAuthAccountModelCatalogEntry(it, "automatic-family") }
        accounts.forEach { assertTrue(app.providerChannelProvisioner.syncModelCatalog(it.id, catalog)) }
        app.providerChannelProvisioner.updateSettings(accounts.first().id,
            modelMappingChanges = catalog.map { ProviderModelMappingChange(it.upstreamModel, "manual", "shared-public") },
            expectedModelMappingRevision = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).revision)
        val outside = accessChannel("outside-scope", models = listOf("outside-upstream"), protocols = listOf(GatewayProtocol.RESPONSES))
        exposeAsModel("shared-public", outside to "outside-upstream")
        configureModelRoute("shared-public", *accounts.map { channel to it }.toTypedArray(), outside to outside.keys.single())
        testApplication {
            application { app.install(this) }
            val (cookie, csrf) = client.loginAdmin()
            fun policy(model: String) = """{"channels":{"mode":"allowlist","ids":["${channel.id}"]},"channelKeys":{"mode":"unrestricted","keys":[]},"models":{"mode":"allowlist","ids":["$model"]}}"""
            val initialPolicy = policy("shared-public")
            val createdResponse = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json)
                setBody("""{"name":"oauth-caller","maxConcurrentRequests":1,"accessPolicy":$initialPolicy}""")
            }
            assertEquals(HttpStatusCode.Created, createdResponse.status, createdResponse.bodyAsText())
            val created = Json.parseToJsonElement(createdResponse.bodyAsText()).jsonObject
            val keyId = created["id"]!!.jsonPrimitive.content
            val key = created["key"]!!.jsonPrimitive.content
            assertEquals(Json.parseToJsonElement(initialPolicy), created["accessPolicy"])
            val listed = client.get("/admin/api/client-keys") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
            assertEquals(created["accessPolicy"], Json.parseToJsonElement(listed.bodyAsText()).jsonArray.single().jsonObject["accessPolicy"])
            assertEquals(listOf("shared-public"), client.publicModels(key))
            val beforeAccount = assertNotNull(app.oauthAccountRepository.findDetailed(accounts.first().id))
            val beforeMappings = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT)
            val beforeKey = assertNotNull(app.clientKeyRepository.find(keyId))
            val rejectedEdits = listOf(
                beforeMappings.revision to """[{"upstreamModel":"upstream-a","mode":"manual","canonicalModelId":" "}]""",
                (beforeMappings.revision - 1) to """[{"upstreamModel":"upstream-a","mode":"manual","canonicalModelId":"must-not-save"}]""",
            )
            rejectedEdits.forEachIndexed { index, (revision, changes) ->
                val rejected = client.put("/admin/api/oauth-accounts/${accounts.first().id}/settings") {
                    header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json)
                    setBody("""{"name":"must-not-save","priority":999,"enabled":false,"models":[],"expectedModelMappingRevision":$revision,"modelMappingChanges":$changes}""")
                }
                assertEquals(if (index == 0) HttpStatusCode.BadRequest else HttpStatusCode.Conflict, rejected.status, rejected.bodyAsText())
                if (index == 1) assertTrue(rejected.bodyAsText().contains("model_mapping_revision_changed"))
                assertEquals(beforeAccount, app.oauthAccountRepository.findDetailed(accounts.first().id))
                assertEquals(beforeMappings, app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT))
                assertEquals(beforeKey, app.clientKeyRepository.find(keyId))
            }
            listOf("upstream-a", "upstream-b").forEach { assertMissing(client.responses(key, it), key) }
            suspend fun assertForwarded(model: String) {
                val count = fake.requests.size
                val response = client.responses(key, model)
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals(count + 1, fake.requests.size)
                val request = fake.requests.last()
                val authorizedUpstream = mapOf("Bearer access-upstream-a" to "upstream-a", "Bearer access-upstream-b" to "upstream-b")
                val upstream = Json.parseToJsonElement(request.body).jsonObject["model"]!!.jsonPrimitive.content
                assertEquals(assertNotNull(authorizedUpstream[assertNotNull(request.authorization)]), upstream)
                if (model == "upstream-a") assertEquals(model, upstream)
            }
            assertForwarded("shared-public")
            assertTrue(app.channelRepository.delete(outside.id))
            var previousId = "shared-public"
            listOf("upstream" to "upstream-a", "auto" to "automatic-family", "manual" to "renamed-public").forEach { (mode, publicId) ->
                val previousPolicy = assertNotNull(app.clientKeyRepository.find(keyId)).accessPolicy
                assertNotNull(app.providerChannelProvisioner.updateSettings(accounts.first().id,
                    modelMappingChanges = catalog.map { ProviderModelMappingChange(it.upstreamModel, mode, if (mode == "manual") publicId else null) },
                    expectedModelMappingRevision = app.providerChannelProvisioner.modelMappings(OAuthProvider.CHATGPT).revision))
                assertEquals(previousPolicy, assertNotNull(app.clientKeyRepository.find(keyId)).accessPolicy)
                configureModelRoutes()
                assertTrue(client.publicModels(key).isEmpty())
                val count = fake.requests.size
                val obsolete = client.responses(key, previousId)
                assertEquals(HttpStatusCode.NotFound, obsolete.status, obsolete.bodyAsText())
                assertTrue(obsolete.bodyAsText().contains("model_not_found"))
                assertRejected(client.responses(key, publicId), "model_access_denied")
                assertEquals(count, fake.requests.size)
                val updated = client.put("/admin/api/client-keys/$keyId") {
                    header(HttpHeaders.Cookie, cookie); header("X-CSRF-Token", csrf); contentType(ContentType.Application.Json)
                    setBody("""{"name":"oauth-caller","maxConcurrentRequests":1,"accessPolicy":${policy(publicId)}}""")
                }
                assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
                assertEquals(Json.parseToJsonElement(policy(publicId)), Json.parseToJsonElement(updated.bodyAsText()).jsonObject["accessPolicy"])
                assertEquals(listOf(publicId), client.publicModels(key))
                assertForwarded(publicId)
                previousId = publicId
            }
            accounts.forEach { account ->
                val current = assertNotNull(app.oauthAccountRepository.findDetailed(account.id))
                assertEquals(account.models, current.models)
                assertFalse(current.supports(if (account.models.single() == "upstream-a") "upstream-b" else "upstream-a"))
            }
            assertEquals(0, fake.tokenRequests.size)
            assertEquals(0, fake.modelRequests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `search internal model rounds reuse the selected authorized candidate`() = runBlocking {
        val outside = accessChannel("outside-search", priority = 0)
        val allowed = createChannelWithBaseUrl("selected-search", "${fake.baseUrl}/web-search/v1",
            modelMappings = mappingsOf("m1"), priority = 1)
        val keys = listOf(
            scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(allowed.id))),
            scopedKey(ClientKeyAccessPolicy(deniedChannelIds = setOf(outside.id))),
        )
        val searchServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        searchServer.createContext("/search") { exchange ->
            val bytes = """{"results":[{"title":"Result","url":"https://example.test","content":"Answer"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        searchServer.start()
        try {
            app.webSearchSettingsRepository.save(WebSearchSettingsRecord(
                enabled = true, provider = "tavily", apiKey = "test", baseUrl = "http://127.0.0.1:${searchServer.address.port}",
                maxResults = 3, timeoutMs = 2_000, maxRounds = 2, maxPageChars = 2_000, updatedAt = 0,
            ))
            testApplication {
                application { app.install(this) }
                keys.forEach { key ->
                    // 假上游只在首个 web-search 请求返回工具调用，每种权限单独重置回合。
                    fake.requests.clear()
                    val response = client.send(key, "/v1/responses",
                        """{"model":"m1","input":"latest?","tools":[{"type":"web_search"}]}""")
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals(2, fake.requests.size)
                    assertTrue(fake.requests.all { it.scenario == "web-search" })
                    assertTrue(fake.requests.none { it.authorization == "Bearer outside-search-secret" })
                }
                assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
            }
        } finally {
            searchServer.stop(0)
        }
    }

    @Test
    fun `authorized mapping without any credentials remains unavailable with unrestricted key dimension`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "no-credentials", baseUrl = "${fake.baseUrl}/ok/v1", keys = emptyList(), authType = "bearer",
            protocols = GatewayProtocol.default, enabled = true, modelMappings = mappingsOf("m1"),
        )
        val key = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(channel.id), allowedModelIds = setOf("m1")))
        testApplication {
            application { app.install(this) }
            val response = client.chat(key)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("model_credentials_unavailable"))
            assertNull(response.headers[HttpHeaders.RetryAfter])
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `denied recovered key never acquires upstream concurrency or probe leases`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "probe-denied", baseUrl = "${fake.baseUrl}/ok/v1", authType = "bearer",
            protocols = GatewayProtocol.default, enabled = true, modelMappings = mappingsOf("m1"),
            keys = listOf(NewChannelKey("probe", app.credentialCipher.encrypt("probe"), state = ChannelKeyState.AUTO_TEMPORARY,
                models = listOf("m1"), rateLimit = ChannelKeyRateLimitPolicy(enabled = true, maxConcurrentRequests = 1))),
        )
        configureModelRoute("m1", channel to channel.keys.single())
        val upstreamKey = channel.keys.single()
        setRecovery(upstreamKey.id, System.currentTimeMillis() - 60_000)
        val policies = listOf(
            ClientKeyAccessPolicy(allowedModelIds = emptySet()) to "model_access_denied",
            ClientKeyAccessPolicy(allowedChannelIds = emptySet()) to "route_access_denied",
            ClientKeyAccessPolicy(allowedChannelKeys = emptySet()) to "route_access_denied",
            ClientKeyAccessPolicy(deniedModelIds = setOf("m1")) to "model_access_denied",
            ClientKeyAccessPolicy(deniedChannelIds = setOf(channel.id)) to "route_access_denied",
            ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, upstreamKey.id))) to "route_access_denied",
        )
        testApplication {
            application { app.install(this) }
            policies.forEach { (policy, code) ->
                val key = scopedKey(policy)
                assertRejected(client.chat(key), code)
                assertEquals(0, fake.requests.size)
                assertNull(assertNotNull(app.channelRepository.find(channel.id)).keys.single().probeUntil)
                val upstreamLease = assertIs<ChannelKeyRateLimitDecision.Allowed>(app.channelKeyRateLimiter.acquire(upstreamKey)).lease
                assertNotNull(upstreamLease)
                app.channelKeyRateLimiter.release(upstreamLease)
                val cooldownPermit = assertNotNull(app.candidateCooldown.acquire(channel.id, upstreamKey.id, "m1"))
                app.candidateCooldown.release(cooldownPermit)
                val record = assertNotNull(app.clientKeyRepository.findByHash(ClientKeyHasher.hash(key)))
                val callerLease = app.requestLimitService.acquire(record)
                app.requestLimitService.release(callerLease)
            }
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `deleting last authorized target leaves empty scope and cannot route to another channel`() = runBlocking {
        val selected = accessChannel("deleted")
        accessChannel("survivor")
        val key = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(selected.id),
            allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(selected.id, selected.keys.single().id))))
        app.channelRepository.delete(selected.id)
        testApplication {
            application { app.install(this) }
            assertRejected(client.chat(key), "route_access_denied")
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    /** 验证黑名单与已存在的协议入口、候选及错误合同一致。 */
    @Test
    fun `model denylist rejects all protocols and hides only excluded public models`() = runBlocking {
        accessChannel("deny-model", models = listOf("m1", "M1"), protocols = GatewayProtocol.entries.toList())
        val key = scopedKey(ClientKeyAccessPolicy(deniedModelIds = setOf("m1")))
        testApplication {
            application { app.install(this) }
            missingModelRequests("m1").forEach { (path, body) -> assertRejected(client.send(key, path, body), "model_access_denied") }
            assertEquals(0, fake.requests.size)
            assertEquals(HttpStatusCode.NotFound, client.chat(key, "missing").status)
            for ((path, field) in listOf("/v1/models" to "data", "/v1beta/models" to "models")) {
                val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $key") }
                val models = Json.parseToJsonElement(response.bodyAsText()).jsonObject[field]!!.jsonArray
                assertEquals(1, models.size)
                assertTrue(models.single().toString().contains("M1"))
                assertTrue(models.none { it.toString().contains("m1") })
            }
            assertEquals(HttpStatusCode.OK, client.chat(key, "M1").status)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `channel and static key denylist refuse routes when all configured targets are excluded`() = runBlocking {
        val channel = accessChannel("denied-route")
        val policies = listOf(
            ClientKeyAccessPolicy(deniedChannelIds = setOf(channel.id)),
            ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id))),
        )
        testApplication {
            application { app.install(this) }
            for (policy in policies) {
                val key = scopedKey(policy)
                missingModelRequests("m1").forEach { (path, body) -> assertRejected(client.send(key, path, body), "route_access_denied") }
                val models = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
                assertTrue(Json.parseToJsonElement(models.bodyAsText()).jsonObject["data"]!!.jsonArray.isEmpty())
            }
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    /** 空凭据映射不得掩盖其他渠道全部支持凭据被排除的权限拒绝。 */
    @Test
    fun `all supporting keys denied stays forbidden with another empty credential mapping`() = runBlocking {
        val channel = accessChannel("denied-with-empty")
        app.channelRepository.create("empty-mapping", "${fake.baseUrl}/ok/v1", emptyList(), "bearer",
            GatewayProtocol.default, enabled = true, modelMappings = mappingsOf("m1"))
        val key = scopedKey(ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id))))
        testApplication {
            application { app.install(this) }
            missingModelRequests("m1").forEach { (path, body) -> assertRejected(client.send(key, path, body), "route_access_denied") }
            assertEquals(0, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `empty denylist behaves as unrestricted including unavailable credentials and disabled channels`() = runBlocking {
        val empty = ClientKeyAccessPolicy(deniedChannelIds = emptySet(), deniedChannelKeys = emptySet(), deniedModelIds = emptySet())
        val noKeys = app.channelRepository.create("empty-deny", "${fake.baseUrl}/ok/v1", emptyList(), "bearer", GatewayProtocol.default,
            enabled = true, modelMappings = mappingsOf("m1"))
        accessChannel("disabled-deny", models = listOf("disabled-model"), enabled = false)
        val excludedOther = accessChannel("irrelevant-deny", models = listOf("other"))
        testApplication {
            application { app.install(this) }
            for (policy in listOf(empty, ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(excludedOther.id, excludedOther.keys.single().id))))) {
                val key = scopedKey(policy)
                val response = client.chat(key)
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("model_credentials_unavailable"))
            }
            val key = scopedKey(empty)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.chat(key, "disabled-model").status)
            assertEquals(0, fake.requests.size)
            assertTrue(noKeys.keys.isEmpty())
        }
    }

    @Test
    fun `static denylist keeps OAuth candidates but channel denylist excludes both credential kinds`() = runBlocking {
        val channel = accessChannel("oauth-deny", protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS, GatewayProtocol.RESPONSES))
        createOAuthAccount(channel, "deny@example.test")
        val static = ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id)
        testApplication {
            application { app.install(this) }
            val key = scopedKey(ClientKeyAccessPolicy(deniedChannelKeys = setOf(static)))
            val response = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
            assertEquals(listOf("m1"), Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val denied = scopedKey(ClientKeyAccessPolicy(deniedChannelIds = setOf(channel.id)))
            assertRejected(client.chat(denied), "route_access_denied")
            assertEquals(0, fake.requests.size)
            assertEquals(0, fake.tokenRequests.size)
            assertEquals(0, fake.quotaRequests.size)
        }
    }

    @Test
    fun `denylist normal retries and all cooling fallback never use excluded static keys`() = runBlocking {
        val channel = app.channelRepository.create("deny-pool", "${fake.baseUrl}/key-health/v1",
            listOf("bad", "backup", "outside").mapIndexed { index, name -> NewChannelKey(name, app.credentialCipher.encrypt(name), priority = index, models = listOf("m1")) },
            "bearer", GatewayProtocol.default, true, modelMappings = mappingsOf("m1"))
        configureModelRoute("m1", *listOf("bad", "backup", "outside").map { name -> channel to channel.keys.single { it.name == name } }.toTypedArray())
        fake.keyStatuses["Bearer bad"] = 500
        val bad = channel.keys.first { it.name == "bad" }
        val backup = channel.keys.first { it.name == "backup" }
        val outside = channel.keys.first { it.name == "outside" }
        val onlyBad = scopedKey(ClientKeyAccessPolicy(deniedChannelKeys = setOf(
            ClientKeyAllowedChannelKey(channel.id, backup.id), ClientKeyAllowedChannelKey(channel.id, outside.id))))
        val withBackup = scopedKey(ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, outside.id))))
        testApplication {
            application { app.install(this) }
            repeat(2) {
                val response = client.chat(onlyBad)
                assertTrue(response.status.value >= 500, response.bodyAsText())
                assertEquals(it + 1, fake.requests.size)
                assertTrue(fake.requests.all { request -> request.authorization == "Bearer bad" })
            }
            app.candidateCooldown.clearKey(channel.id, bad.id)
            assertEquals(HttpStatusCode.OK, client.chat(withBackup).status)
            assertEquals(listOf("Bearer bad", "Bearer bad", "Bearer bad", "Bearer backup"), fake.requests.map { it.authorization })
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `denylist removes higher priority channels while allowing protocol conversion`() = runBlocking {
        val outside = accessChannel("outside-deny", priority = 0)
        val allowed = accessChannel("allowed-deny", protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS), priority = 1)
        val key = scopedKey(ClientKeyAccessPolicy(deniedChannelIds = setOf(outside.id), allowedModelIds = setOf("m1")))
        testApplication {
            application { app.install(this) }
            val converted = client.send(key, "/v1beta/models/m1:generateContent", """{"contents":[]}""")
            assertEquals(HttpStatusCode.OK, converted.status, converted.bodyAsText())
            assertEquals(listOf("Bearer allowed-deny-secret"), fake.requests.map { it.authorization })
            assertTrue(allowed.enabled)
        }
    }

    @Test
    fun `excluded temporary key does not contribute an earlier retry hint`() = runBlocking {
        val channel = app.channelRepository.create("deny-recovery", "${fake.baseUrl}/ok/v1",
            listOf("allowed", "denied").map { name -> NewChannelKey(name, app.credentialCipher.encrypt(name), ChannelKeyState.AUTO_TEMPORARY, models = listOf("m1")) },
            "bearer", GatewayProtocol.default, true, modelMappings = mappingsOf("m1"))
        configureModelRoute("m1", *channel.keys.map { channel to it }.toTypedArray())
        val allowed = channel.keys.first { it.name == "allowed" }
        val denied = channel.keys.first { it.name == "denied" }
        setRecovery(allowed.id, System.currentTimeMillis() + 120_000)
        setRecovery(denied.id, System.currentTimeMillis() + 20_000)
        val key = scopedKey(ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, denied.id))))
        testApplication {
            application { app.install(this) }
            val response = client.chat(key)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(assertNotNull(response.headers[HttpHeaders.RetryAfter]).toLong() in 100..125)
            assertEquals(0, fake.requests.size)
        }
    }

    /** 创建带并发上限的调用方密钥，使每次拒绝后的租约泄漏可被直接观测。 */
    private suspend fun scopedKey(policy: ClientKeyAccessPolicy): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create("scoped", generated.hash, generated.prefix, true, null,
            ClientKeyQuotaLimits(maxConcurrentRequests = 1), policy)
        return generated.raw
    }

    /** 建立支持指定模型的静态密钥渠道。 */
    private suspend fun accessChannel(
        name: String,
        models: List<String> = listOf("m1"),
        protocols: List<GatewayProtocol> = GatewayProtocol.default,
        priority: Int = 0,
        enabled: Boolean = true,
    ): ChannelRecord = app.channelRepository.create(
        name, "${fake.baseUrl}/ok/v1", listOf(NewChannelKey(name, app.credentialCipher.encrypt("$name-secret"), models = models)),
        "bearer", protocols, enabled, modelMappings = models.map { ChannelModelRecord(it) }, priority = priority,
    ).also { channel ->
        for (modelId in models) {
            val route = app.modelCredentialRouteRepository.find(modelId)
            val identity = CredentialRouteIdentity(channel.id, CredentialRouteType.STATIC, channel.keys.single().id)
            app.modelCredentialRouteRepository.save(modelId, route?.revision ?: 0, route?.entries.orEmpty() + identity)
        }
    }

    /** 发送普通聊天请求，使用对外模型 ID。 */
    private suspend fun HttpClient.chat(key: String, model: String = "m1"): HttpResponse =
        send(key, "/v1/chat/completions", """{"model":"$model","messages":[]}""")

    /** 使用 provider 原生 Responses 协议验证 OAuth 映射与转发。 */
    private suspend fun HttpClient.responses(key: String, model: String): HttpResponse =
        send(key, "/v1/responses", """{"model":"$model","stream":false,"input":"ping"}""")

    /** 读取调用方可见的聚合后对外模型 ID。 */
    private suspend fun HttpClient.publicModels(key: String): List<String> {
        val response = get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    /** 发送带调用方鉴权的原生请求。 */
    private suspend fun HttpClient.send(key: String, path: String, body: String): HttpResponse = post(path) {
        header(HttpHeaders.Authorization, "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** 构造四种协议及两个 Gemini 版本的不存在模型请求，URL 模型优先于合法 body 模型。 */
    private fun missingModelRequests(model: String): List<Pair<String, String>> = listOf(
        "/v1/chat/completions" to """{"model":"$model","messages":[]}""",
        "/v1/responses" to """{"model":"$model","input":"hi","tools":[{"type":"web_search"}]}""",
        "/v1/messages" to """{"model":"$model","max_tokens":8,"messages":[]}""",
        "/v1beta/models/$model:generateContent" to """{"model":"allowed","contents":[]}""",
        "/v1beta/models/$model:streamGenerateContent" to """{"contents":[]}""",
        "/v1/models/$model:generateContent" to """{"model":"allowed","contents":[]}""",
        "/v1/models/$model:streamGenerateContent" to """{"contents":[]}""",
    )

    /** 保留各协议原生不存在响应，并确认零上游尝试及调用方租约释放。 */
    private suspend fun assertMissing(response: HttpResponse, key: String) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.NotFound, response.status, body)
        assertNull(response.headers["X-Prism-Error-Code"])
        val root = Json.parseToJsonElement(body).jsonObject
        val error = root["error"]!!.jsonObject
        when {
            root["type"]?.jsonPrimitive?.content == "error" -> assertEquals("not_found_error", error["type"]!!.jsonPrimitive.content)
            error["status"] != null -> {
                assertEquals("NOT_FOUND", error["status"]!!.jsonPrimitive.content)
                assertEquals("404", error["code"]!!.jsonPrimitive.content)
            }
            else -> assertEquals("model_not_found", error["code"]!!.jsonPrimitive.content)
        }
        val requestId = assertNotNull(response.headers["X-Request-Id"])
        awaitCondition("不存在模型请求日志") { app.requestLogRepository.list(30).any { it.requestId == requestId } }
        val log = app.requestLogRepository.list(30).single { it.requestId == requestId }
        assertEquals("model_not_found", log.errorCode)
        assertEquals(0, log.upstreamAttempts)
        assertTrue(log.attempts.isEmpty())
        assertEquals(0, fake.requests.size)
        assertEquals(0, fake.tokenRequests.size)
        assertEquals(0, fake.quotaRequests.size)
        assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        val record = assertNotNull(app.clientKeyRepository.findByHash(ClientKeyHasher.hash(key)))
        val callerLease = app.requestLimitService.acquire(record)
        app.requestLimitService.release(callerLease)
    }

    /** 断言跨协议稳定错误码、原生错误体、零上游日志，以及调用方并发占位释放。 */
    private suspend fun assertRejected(response: HttpResponse, code: String) {
        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        assertEquals(code, response.headers["X-Prism-Error-Code"])
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = root["error"]!!.jsonObject
        when {
            root["type"]?.jsonPrimitive?.content == "error" -> assertEquals("permission_error", error["type"]!!.jsonPrimitive.content)
            error["status"] != null -> {
                assertEquals("PERMISSION_DENIED", error["status"]!!.jsonPrimitive.content)
                assertEquals("403", error["code"]!!.jsonPrimitive.content)
            }
            else -> assertEquals(code, error["code"]!!.jsonPrimitive.content)
        }
        assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        val requestId = assertNotNull(response.headers["X-Request-Id"])
        awaitCondition("授权拒绝请求日志") { app.requestLogRepository.list(30).any { it.requestId == requestId } }
        val log = app.requestLogRepository.list(30).single { it.requestId == requestId }
        assertEquals(code, log.errorCode)
        assertEquals(0, log.upstreamAttempts)
        assertTrue(log.attempts.isEmpty())
    }

    /** 在测试数据库中设定静态密钥的自动恢复时间。 */
    private fun setRecovery(keyId: String, recoveryAt: Long) {
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update channel_keys set auto_disable_until = ? where id = ?").use { statement ->
                statement.setLong(1, recoveryAt)
                statement.setString(2, keyId)
                statement.executeUpdate()
            }
        }
    }
}
