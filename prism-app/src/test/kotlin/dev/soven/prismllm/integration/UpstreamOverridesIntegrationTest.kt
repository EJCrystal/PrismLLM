package dev.soven.prismllm.integration

import dev.soven.prismllm.db.DatabaseFactory
import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.shared.domain.UpstreamRequestOverrides
import dev.soven.prismllm.shared.domain.UpstreamProtocolOverrides
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.channel.persistence.UpstreamRequestSettingsRepository
import dev.soven.prismllm.channel.persistence.UpstreamHeaderCatalogRepository
import dev.soven.prismllm.proxy.HeaderProfileVersionSource
import dev.soven.prismllm.proxy.UpstreamHeaderCatalogService
import dev.soven.prismllm.proxy.UpstreamHeaderProfiles
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
@Tag("integration")
@Tag("shard-1")
@Tag("channel")
@Tag("protocol")
class UpstreamOverridesIntegrationTest : GatewayIntegrationTestBase() {

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
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            // 多条写入放在同一事务里，避免中途失败时留下半套脏数据影响后续断言。
            connection.autoCommit = false
            try {
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
                connection.commit()
            } catch (cause: Throwable) {
                connection.rollback()
                throw cause
            } finally {
                connection.autoCommit = true
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
        val lastError = snapshot.lastError!!
        assertTrue(lastError.contains("catalog:invalid_persisted_profile"))
        assertTrue(lastError.contains("catalog:stored_error_redacted"))
        assertFalse(lastError.contains("secret"))
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
        // 用独立连接池模拟数据库故障，避免关闭基类共享的 app.databaseFactory。
        val failingFactory = DatabaseFactory(config())
        try {
            val repository = UpstreamRequestSettingsRepository(failingFactory.database)
            val saved = repository.save(UpstreamRequestOverrides(userAgent = "working-UA"))
            failingFactory.close()
            assertSame(saved, repository.load(), "缓存命中不应访问数据库")
            assertFailsWith<java.sql.SQLException> { repository.save(UpstreamRequestOverrides(userAgent = "failed-UA")) }
            assertSame(saved, repository.load(), "失败的写入不应替换快照")
        } finally {
            failingFactory.close()
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
}
