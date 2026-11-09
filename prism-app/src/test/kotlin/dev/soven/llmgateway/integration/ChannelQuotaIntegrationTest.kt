package dev.soven.llmgateway.integration

import dev.soven.llmgateway.channel.domain.ChannelQuotaSettings
import dev.soven.llmgateway.channel.domain.ChannelQuotaCredentialSource
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.channel.persistence.ChannelKeyUpdate
import dev.soven.llmgateway.shared.crypto.ClientKeyHasher
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaLimits
import io.ktor.client.request.header
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
@Tag("shard-1")
@Tag("channel")
class ChannelQuotaIntegrationTest : GatewayIntegrationTestBase() {

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
    fun `admin client key API validates and returns quota settings`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()

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
}
