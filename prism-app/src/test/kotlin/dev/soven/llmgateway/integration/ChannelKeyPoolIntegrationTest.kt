package dev.soven.llmgateway.integration

import dev.soven.llmgateway.domain.ChannelModelRecord
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.repo.NewChannelKey
import dev.soven.llmgateway.repo.ChannelKeyUpdate
import dev.soven.llmgateway.resilience.KeyAttemptOutcome
import dev.soven.llmgateway.resilience.ChannelKeyState
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.resilience.KeyAutoDisablePolicy
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class ChannelKeyPoolIntegrationTest : GatewayIntegrationTestBase() {

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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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

        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("update channel_keys set state = 'AUTO_TEMPORARY', auto_disable_until = 1000 where id = ?").use { stmt ->
                stmt.setString(1, manualKey.id)
                stmt.executeUpdate()
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
}
