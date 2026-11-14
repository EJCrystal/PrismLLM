package dev.soven.prismllm.integration

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.admin.domain.WebSearchSettingsRecord
import dev.soven.prismllm.channel.domain.ChannelKeyRateLimitPolicy
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyRateLimitDecision
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
            assertEquals(HttpStatusCode.NotFound, disabledOnly.status)
            assertTrue(disabledOnly.bodyAsText().contains("model_not_found"))
            assertEquals(1, fake.requests.size)
            assertEquals(0, app.requestLimitService.clientKeyOccupancy().values.sum())
        }
    }

    @Test
    fun `only authorized temporary keys contribute retry time and disabled keys stay unavailable`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "states", baseUrl = "${fake.baseUrl}/ok/v1", authType = "bearer",
            protocols = GatewayProtocol.default, enabled = true, modelMappings = mappingsOf("m1"),
            keys = listOf(
                NewChannelKey("temporary", app.credentialCipher.encrypt("temporary"), ChannelKeyState.AUTO_TEMPORARY, models = listOf("m1")),
                NewChannelKey("outside", app.credentialCipher.encrypt("outside"), ChannelKeyState.AUTO_TEMPORARY, models = listOf("m1")),
                NewChannelKey("permanent", app.credentialCipher.encrypt("permanent"), ChannelKeyState.AUTO_PERMANENT, models = listOf("m1")),
                NewChannelKey("manual", app.credentialCipher.encrypt("manual"), ChannelKeyState.MANUAL_DISABLED, models = listOf("m1")),
                NewChannelKey("active-outside", app.credentialCipher.encrypt("active-outside"), models = listOf("m1")),
            ),
        )
        setRecovery(channel.keys.first { it.name == "temporary" }.id, System.currentTimeMillis() + 120_000)
        setRecovery(channel.keys.first { it.name == "outside" }.id, System.currentTimeMillis() + 20_000)
        testApplication {
            application { app.install(this) }
            listOf("temporary", "permanent", "manual").forEach { name ->
                val selected = channel.keys.first { it.name == name }
                val key = scopedKey(ClientKeyAccessPolicy(allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, selected.id))))
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
    fun `static key allowlist excludes OAuth before refresh and upstream calls`() = runBlocking {
        val channel = app.channelRepository.create(
            name = "oauth-only", baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(NewChannelKey("static", app.credentialCipher.encrypt("static"), models = listOf("m2"))), authType = "bearer",
            protocols = GatewayProtocol.default, enabled = true, modelMappings = mappingsOf("m1", "m2"),
        )
        createOAuthAccount(channel, "scope@example.test")
        val key = scopedKey(ClientKeyAccessPolicy(allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id))))
        testApplication {
            application { app.install(this) }
            assertRejected(client.chat(key), "route_access_denied")
            assertEquals(0, fake.requests.size)
            assertEquals(0, fake.tokenRequests.size)
            assertEquals(0, fake.quotaRequests.size)
            val visible = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $key") }
            assertEquals(listOf("m2"), Json.parseToJsonElement(visible.bodyAsText()).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val unrestricted = scopedKey(ClientKeyAccessPolicy())
            val global = client.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer $unrestricted") }
            assertEquals(listOf("m1", "m2"), Json.parseToJsonElement(global.bodyAsText()).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content })
        }
    }

    @Test
    fun `search internal model rounds reuse the selected authorized candidate`() = runBlocking {
        accessChannel("outside-search", priority = 0)
        val allowed = createChannelWithBaseUrl("selected-search", "${fake.baseUrl}/web-search/v1",
            modelMappings = mappingsOf("m1"), priority = 1)
        val key = scopedKey(ClientKeyAccessPolicy(allowedChannelIds = setOf(allowed.id)))
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
                val response = client.send(key, "/v1/responses",
                    """{"model":"m1","input":"latest?","tools":[{"type":"web_search"}]}""")
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals(2, fake.requests.size)
                assertTrue(fake.requests.all { it.scenario == "web-search" })
                assertTrue(fake.requests.none { it.authorization == "Bearer outside-search-secret" })
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
        val upstreamKey = channel.keys.single()
        setRecovery(upstreamKey.id, System.currentTimeMillis() - 60_000)
        val policies = listOf(
            ClientKeyAccessPolicy(allowedModelIds = emptySet()) to "model_access_denied",
            ClientKeyAccessPolicy(allowedChannelIds = emptySet()) to "route_access_denied",
            ClientKeyAccessPolicy(allowedChannelKeys = emptySet()) to "route_access_denied",
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
    )

    /** 发送普通聊天请求，使用对外模型 ID。 */
    private suspend fun HttpClient.chat(key: String, model: String = "m1"): HttpResponse =
        send(key, "/v1/chat/completions", """{"model":"$model","messages":[]}""")

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
