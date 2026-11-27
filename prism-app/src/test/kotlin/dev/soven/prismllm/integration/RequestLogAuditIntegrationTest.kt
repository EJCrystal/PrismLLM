package dev.soven.prismllm.integration

import dev.soven.prismllm.channel.domain.key.KeyDisableAction
import dev.soven.prismllm.channel.domain.key.KeyDisablePolicy
import dev.soven.prismllm.channel.domain.key.KeyDisableRule
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.persistence.ClientKeyRepository
import dev.soven.prismllm.repo.OAuthProviderChannelLookup
import dev.soven.prismllm.testsupport.db.TestDatabase
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
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
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
@Tag("shard-2")
class RequestLogAuditIntegrationTest : GatewayIntegrationTestBase() {

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
    fun `request log display resolves current channel and client key names`() = runBlocking {
        val channel = createChannel("current-channel", "channel")
        createClientKey()
        val clientKey = app.clientKeyRepository.list().single()
        val channelKey = channel.keys.single()
        app.requestLogRepository.insert(
            requestLog("name-resolution", createdAt = System.currentTimeMillis()).copy(
                clientKeyId = clientKey.id,
                clientKeyName = "stale-client",
                channelId = channel.id,
                channelName = "stale-channel",
                channelKeyId = channelKey.id,
                channelKeyName = "stale-key",
            ),
        )

        val displayed = app.requestLogRepository.listForDisplay(10)
            .single { it.requestId == "name-resolution" }
        assertEquals(clientKey.name, displayed.clientKeyName)
        assertEquals(channel.name, displayed.channelName)
        assertEquals(channelKey.name, displayed.channelKeyName)
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

            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    // 固定 SQL，不拼接任何动态值；逐列检查而不是先拼串，避免列边界造成误判。
                    statement.executeQuery(
                        "select actor, action, method, path, request_id, source_ip, error_code, details::text from admin_audit_logs",
                    ).use { result ->
                        while (result.next()) {
                            (1..8).forEach { index ->
                                val value = result.getString(index) ?: return@forEach
                                // 失败信息不要带上列值，避免把泄漏的密钥原样打进 CI 日志。
                                assertFalse(value.contains(oldKey), "audit column $index leaked old key")
                                assertFalse(value.contains(newKey), "audit column $index leaked rotated key")
                            }
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
    @Timeout(300)
    fun `migrations tolerate duplicate rows`() {
        val databaseName = createMigrationDatabase("migration_latest")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "7")

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
            migrateTo(migrationUrl, "19")

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
            migrateTo(migrationUrl, "31")

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
            migrateTo(migrationUrl, "53")

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
            migrateTo(migrationUrl, "55")

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

            migrateTo(migrationUrl)

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
                        assertEquals("81", result.getString(1))
                    }
                    statement.executeQuery("select count(*) from channels where egress_proxies <> '[]'").use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1))
                    }
                    statement.executeQuery("select count(*) from channel_keys where egress_proxy_id is not null").use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getInt(1))
                    }
                    statement.executeQuery("select model_mapping_revision from channels where id = 'migration-channel'").use { result ->
                        assertTrue(result.next())
                        assertEquals(0L, result.getLong(1), "V71 应为历史渠道初始化独立映射版本")
                    }
                    statement.executeQuery("select catalog_active from channel_models").use { result ->
                        assertTrue(result.next())
                        assertTrue(result.getBoolean(1), "V71 不应停用历史模型映射")
                    }
                    // V67 只对清洁库生效：USD 历史行原样保留，仅加 CHECK 约束（非 USD 数据由独立用例验证会被拒迁）。
                    statement.executeQuery(
                        "select count(*) from information_schema.table_constraints where constraint_name = 'ck_model_prices_currency_usd'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1), "V67 应为 model_prices 加 USD-only CHECK")
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
                        "select upstream_model, input_price_per_million from model_prices where channel_id = 'migration-channel' and upstream_model = 'duplicate-model'",
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
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `v57 migration moves activity rule into existing grox channel`() {
        val databaseName = createMigrationDatabase("migration_v57")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "55")

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

            migrateTo(migrationUrl)

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
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `v58 migration drops the retired reasoning and developer policy keys`() {
        val databaseName = createMigrationDatabase("migration_v58")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "57")

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    // 一个带两个已删除键的渠道、一个不带任何键的渠道，以及全局设置。
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, auth_type, enabled, created_at, updated_at, request_overrides)
                        values ('v58-dirty', 'dirty', 'https://dirty.example.com/v1', 'bearer', true, 1, 1,
                                '{"reasoningContentPolicy":"always","developerRolePolicy":"never","headerProfile":"auto"}'),
                               ('v58-clean', 'clean', 'https://clean.example.com/v1', 'bearer', true, 1, 1,
                                '{"headerProfile":"codex"}')
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into upstream_request_settings (id, overrides, updated_at)
                        values (1, '{"developerRolePolicy":"always","body":{"temperature":0.2}}', 1)
                        on conflict (id) do update set overrides = excluded.overrides
                        """.trimIndent(),
                    )
                }
            }

            migrateTo(migrationUrl)

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select request_overrides from channels where id = 'v58-dirty'",
                    ).use { result ->
                        assertTrue(result.next())
                        val overrides = Json.parseToJsonElement(result.getString(1)).jsonObject
                        assertFalse(overrides.containsKey("reasoningContentPolicy"), "已删除的策略键应被清理")
                        assertFalse(overrides.containsKey("developerRolePolicy"))
                        assertEquals("auto", overrides["headerProfile"]?.jsonPrimitive?.content, "其余配置必须保留")
                    }
                    // 不含这两个键的行不应被重写。
                    statement.executeQuery(
                        "select request_overrides from channels where id = 'v58-clean'",
                    ).use { result ->
                        assertTrue(result.next())
                        assertEquals("""{"headerProfile":"codex"}""", result.getString(1))
                    }
                    statement.executeQuery(
                        "select overrides from upstream_request_settings",
                    ).use { result ->
                        assertTrue(result.next())
                        val overrides = Json.parseToJsonElement(result.getString(1)).jsonObject
                        assertFalse(overrides.containsKey("developerRolePolicy"))
                        assertEquals(
                            "0.2",
                            overrides["body"]?.jsonObject?.get("temperature")?.jsonPrimitive?.content,
                        )
                    }
                }
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `v67 migration refuses non-USD legacy data instead of relabeling amounts`() {
        val databaseName = createMigrationDatabase("migration_v67_dirty")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "55")

            // 旧版校验遗留的非 USD 数据（CNY 价格与 CNY 费用日志）。V67 必须中止迁移
            // 而不是把原币金额改标 USD——未换算金额混入 USD 账本正是 issue #222 要阻止的。
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, auth_type, enabled, created_at, updated_at)
                        values ('migration-channel', 'migration-channel', 'https://example.com/v1', 'bearer', true, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into model_prices (id, channel_id, upstream_model, currency, input_price_per_million, output_price_per_million, created_at, updated_at)
                        values ('v67-cny-price', 'migration-channel', 'cny-model', 'CNY', 7, 14, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into request_logs (request_id, alias, status_code, total_ms, created_at, cost_amount, cost_currency)
                        values ('v67-cny-log', 'cny-legacy', 200, 1, 1, 7, 'CNY')
                        """.trimIndent(),
                    )
                }
            }

            val failure = runCatching { migrateTo(migrationUrl) }
            assertTrue(failure.isFailure, "存在非 USD 存量数据时 V67 应中止迁移")
            val cause = generateSequence(failure.exceptionOrNull()) { it.cause }
                .map { it.message ?: "" }
                .firstOrNull { it.contains("V67") }
            assertNotNull(cause, "失败链里应有 V67 守卫的中文报错，实际：${failure.exceptionOrNull()}")

            // 中止后数据库停留在 V66，脏行未被改写，管理员可换算或删除后重跑。
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1").use { result ->
                        assertTrue(result.next())
                        assertEquals("66", result.getString(1), "V67 中止后应停在 V66")
                    }
                    statement.executeQuery("select currency, input_price_per_million from model_prices where id = 'v67-cny-price'").use { result ->
                        assertTrue(result.next())
                        assertEquals("CNY", result.getString(1), "中止迁移不得改标币种")
                        assertEquals(0, result.getBigDecimal(2).compareTo(BigDecimal("7")), "中止迁移不得改写金额")
                    }
                    statement.executeQuery("select cost_amount, cost_currency from request_logs where request_id = 'v67-cny-log'").use { result ->
                        assertTrue(result.next())
                        assertEquals(0, result.getBigDecimal(1).compareTo(BigDecimal("7")), "中止迁移不得改写历史费用")
                        assertEquals("CNY", result.getString(2))
                    }
                }
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `v68 through v71 migrations keep legacy client keys unrestricted`() = runBlocking {
        val databaseName = createMigrationDatabase("migration_v68_v71_legacy")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "67")
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into client_keys (id, name, key_hash, key_prefix, enabled, expires_at, daily_token_limit,
                            daily_usd_limit, max_concurrent_requests, requests_per_minute, created_at, rotated_at)
                        values ('legacy-client', 'legacy-client', 'legacy-hash', 'legacy-prefix', true, 1000, 123,
                            1.25, 2, 7, 1, 2)
                        """.trimIndent(),
                    )
                }
            }

            // 必须先应用主线的访问范围迁移，不能把 feature 的映射状态迁移占用到 V68。
            migrateTo(migrationUrl, "68")
            // V68 阶段 client_keys 还没有黑名单列，而 Exposed 表定义已随主线扩展；该阶段用原生 SQL 校验。
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select access_channels_restricted, access_channel_keys_restricted, access_models_restricted from client_keys where id = 'legacy-client'",
                    ).use { result ->
                        assertTrue(result.next())
                        for (index in 1..3) assertFalse(result.getBoolean(index), "V68 应让旧 caller 三个维度均不限制")
                        assertFalse(result.next())
                    }
                }
            }

            migrateTo(migrationUrl)

            val legacy = ClientKeyRecord(
                id = "legacy-client",
                name = "legacy-client",
                keyHash = "legacy-hash",
                keyPrefix = "legacy-prefix",
                enabled = true,
                expiresAt = 1000L,
                quotas = ClientKeyQuotaLimits(
                    dailyTokens = 123,
                    dailyUsdLimit = BigDecimal("1.25000000"),
                    maxConcurrentRequests = 2,
                    requestsPerMinute = 7,
                ),
                createdAt = 1L,
                rotatedAt = 2L,
            )
            TestDatabase(migrationUrl, postgres.username, postgres.password).use { database ->
                val clients = ClientKeyRepository(database.database)
                val found = assertNotNull(clients.find(legacy.id), "V69/V70/V71 不应重置旧 caller 的策略或其它字段")
                assertEquals(ClientKeyAccessPolicy(), found.accessPolicy, "V69/V70/V71 不应把旧 caller 改成任何名单模式")
                assertTrue(found.accessPolicy.isUnrestricted)
                assertTrue(found.ipPolicy.isUnrestricted, "V81 不应为旧 caller 增加 IP 限制")
                assertEquals(legacy, found)
                assertEquals(legacy, clients.findByHash("legacy-hash"))
                assertEquals(listOf(legacy), clients.list())
            }
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select version, script from flyway_schema_history where success and version in ('67', '68', '69', '70', '71') order by installed_rank",
                    ).use { result ->
                        for ((version, script) in listOf(
                            "67" to "V67__usd_only_billing_currency.sql",
                            "68" to "V68__client_key_access_scope.sql",
                            "69" to "V69__client_key_access_denylist.sql",
                            "70" to "V70__iq_test_jobs.sql",
                            "71" to "V71__provider_model_mapping_state.sql",
                        )) {
                            assertTrue(result.next())
                            assertEquals(version, result.getString(1))
                            assertEquals(script, result.getString(2), "主线 V68/V69/V70 与 feature V71 应按顺序执行")
                        }
                        assertFalse(result.next())
                    }
                }
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `v71 migration preserves v68 client scopes mappings and upstream authorizations`() = runBlocking {
        val databaseName = createMigrationDatabase("migration_v71_scopes")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "68")
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, auth_type, enabled, kind, provider,
                            key_disable_policy_revision, created_at, updated_at)
                        values ('v69-static', 'v69-static', 'https://static.example.com/v1', 'bearer', true, 'custom', null, 7, 1, 2),
                               ('v69-provider', 'v69-provider', 'https://provider.example.com/v1', 'bearer', true, 'provider', 'chatgpt', 7, 1, 2)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channel_models (channel_id, upstream_model, canonical_model_id, mapping_source, created_at)
                        values ('v69-static', 'Static-Upstream', 'Public-Static', 'manual', 1),
                               ('v69-provider', 'Account-Upstream', 'Public-Account', 'manual', 1),
                               ('v69-provider', 'Suggested-Upstream', 'Public-Suggested', 'models_dev', 1),
                               ('v69-provider', 'Fallback-Upstream', null, null, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channel_keys (id, channel_id, name, credential_ciphertext, credential_nonce,
                            credential_key_version, state, created_at, updated_at)
                        values ('v69-static-key', 'v69-static', 'v69-static-key', 'cipher', 'nonce', 1, 'ACTIVE', 1, 2)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "insert into channel_key_models (key_id, upstream_model, created_at) values ('v69-static-key', 'Static-Upstream', 1)",
                    )
                    statement.execute(
                        """
                        insert into oauth_accounts (id, name, provider, refresh_token_ciphertext, refresh_token_nonce,
                            refresh_token_key_version, created_at, updated_at, authorized_at, model_catalog, model_catalog_synced_at)
                        values ('v69-oauth-all', 'all', 'chatgpt', 'cipher', 'nonce', 1, 1, 2, 1, null, null),
                               ('v69-oauth-restricted', 'restricted', 'chatgpt', 'cipher', 'nonce', 1, 1, 2, 1,
                                '[{"upstreamModel":"Account-Upstream","canonicalModelId":"Public-Account"},{"upstreamModel":"Suggested-Upstream","canonicalModelId":"Public-Suggested"}]', 2),
                               ('v69-oauth-none', 'none', 'chatgpt', 'cipher', 'nonce', 1, 1, 2, 1, null, null)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_account_channels (account_id, channel_id, managed, created_at)
                        values ('v69-oauth-all', 'v69-provider', true, 1),
                               ('v69-oauth-restricted', 'v69-provider', true, 1),
                               ('v69-oauth-none', 'v69-provider', true, 1)
                        """.trimIndent(),
                    )
                    // OAuth 授权无行表示 models=[] 跟随目录；显式零授权必须保留哨兵，不能变成无行。
                    statement.execute(
                        """
                        insert into oauth_account_models (account_id, upstream_model, created_at)
                        values ('v69-oauth-restricted', 'Account-Upstream', 1),
                               ('v69-oauth-none', '(no models available)', 1)
                        """.trimIndent(),
                    )
                }
            }

            // V68 阶段 client_keys 还没有黑名单列，Exposed 表定义已随主线扩展；caller 与关联都用原生 SQL 播种。
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    for (index in 0..4) {
                        val channelsRestricted = index == 0 || index == 1 || index == 2
                        val channelKeysRestricted = index == 0 || index == 1 || index == 3
                        val modelsRestricted = index == 0 || index == 1 || index == 4
                        statement.execute(
                            """
                            insert into client_keys (id, name, key_hash, key_prefix, enabled, expires_at, daily_token_limit,
                                daily_usd_limit, max_concurrent_requests, requests_per_minute, created_at,
                                access_channels_restricted, access_channel_keys_restricted, access_models_restricted)
                            values ('v69-client-$index', 'v69-client-$index', 'v69-hash-$index', 'v69-prefix-$index', ${index != 1},
                                ${1000 + index}, 123, 1.25, 2, 7, ${100 + index}, $channelsRestricted, $channelKeysRestricted, $modelsRestricted)
                            """.trimIndent(),
                        )
                    }
                    statement.execute(
                        """
                        insert into client_key_allowed_channels (client_key_id, channel_id)
                        values ('v69-client-0', 'v69-static'), ('v69-client-0', 'v69-provider')
                        """.trimIndent(),
                    )
                    statement.execute(
                        "insert into client_key_allowed_channel_keys (client_key_id, channel_id, key_id) values ('v69-client-0', 'v69-static', 'v69-static-key')",
                    )
                    statement.execute(
                        """
                        insert into client_key_allowed_models (client_key_id, model_id)
                        values ('v69-client-0', 'Public-Static'), ('v69-client-0', 'Public-Account'),
                               ('v69-client-0', 'Public-Suggested'), ('v69-client-0', 'Fallback-Upstream')
                        """.trimIndent(),
                    )
                }
            }

            // 账号表不在黑名单迁移范围内，Exposed 可在 V68 阶段安全读取，用于升级前后对比。
            val savedAccounts = TestDatabase(migrationUrl, postgres.username, postgres.password).use { database ->
                OAuthAccountRepository(database.database, OAuthProviderChannelLookup(), includeAgyColumns = false)
                    .listAll().associateBy { it.id }
            }

            migrateTo(migrationUrl)

            val policies = listOf(
                ClientKeyAccessPolicy(
                    allowedChannelIds = setOf("v69-static", "v69-provider"),
                    allowedChannelKeys = setOf(ClientKeyAllowedChannelKey("v69-static", "v69-static-key")),
                    allowedModelIds = setOf("Public-Static", "Public-Account", "Public-Suggested", "Fallback-Upstream"),
                ),
                ClientKeyAccessPolicy(emptySet(), emptySet(), emptySet()),
                ClientKeyAccessPolicy(allowedChannelIds = emptySet()),
                ClientKeyAccessPolicy(allowedChannelKeys = emptySet()),
                ClientKeyAccessPolicy(allowedModelIds = emptySet()),
            )
            val savedClients = policies.mapIndexed { index, policy ->
                ClientKeyRecord(
                    id = "v69-client-$index",
                    name = "v69-client-$index",
                    keyHash = "v69-hash-$index",
                    keyPrefix = "v69-prefix-$index",
                    enabled = index != 1,
                    expiresAt = 1000L + index,
                    quotas = ClientKeyQuotaLimits(
                        dailyTokens = 123,
                        dailyUsdLimit = BigDecimal("1.25000000"),
                        maxConcurrentRequests = 2,
                        requestsPerMinute = 7,
                    ),
                    createdAt = 100L + index,
                    accessPolicy = policy,
                )
            }
            TestDatabase(migrationUrl, postgres.username, postgres.password).use { database ->
                val clients = ClientKeyRepository(database.database)
                val accounts = OAuthAccountRepository(database.database, OAuthProviderChannelLookup(), includeAgyColumns = false)

                assertEquals(savedClients.associateBy { it.id }, clients.list().associateBy { it.id })
                savedClients.forEach { saved ->
                    val current = assertNotNull(clients.find(saved.id))
                    assertEquals(saved, current, "完整策略与调用方字段都应保留")
                    assertEquals(saved, clients.findByHash(saved.keyHash))
                    assertFalse(current.accessPolicy.isUnrestricted, "caller 空白名单仍是 deny-all，不是 OAuth models=[] 的跟随语义")
                    assertTrue(current.ipPolicy.isUnrestricted, "V81 应独立保留旧 caller 的访问范围，不增加 IP 限制")
                }
                val migratedAccounts = accounts.listAll().associateBy { it.id }
                assertEquals(savedAccounts, migratedAccounts, "账号的 upstream 授权、目录与关联不得改写")
                savedAccounts.forEach { (id, saved) -> assertEquals(saved, accounts.findDetailed(id)) }
                val all = migratedAccounts.getValue("v69-oauth-all")
                val restricted = migratedAccounts.getValue("v69-oauth-restricted")
                val none = migratedAccounts.getValue("v69-oauth-none")
                assertTrue(all.models.isEmpty())
                assertNull(all.modelCatalog)
                assertEquals(listOf("Account-Upstream"), restricted.models)
                assertTrue(restricted.supports("Account-Upstream"))
                assertFalse(restricted.supports("Public-Account"), "账号授权仍以 upstream ID 匹配，不应改成 canonical ID")
                assertFalse(restricted.supports("Suggested-Upstream"), "共享渠道映射不能放宽账号自己的显式授权")
                assertEquals(listOf("(no models available)"), none.models)
                for (upstream in listOf("Account-Upstream", "Suggested-Upstream", "Fallback-Upstream")) {
                    assertTrue(all.supports(upstream), "OAuth models=[] 应继续跟随渠道全部模型")
                    assertFalse(none.supports(upstream), "显式零授权哨兵不能被迁移清空后放权")
                }
            }

            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1").use { result ->
                        assertTrue(result.next())
                        assertEquals("81", result.getString(1))
                    }
                    statement.executeQuery("select id, model_mapping_revision, key_disable_policy_revision, updated_at from channels order by id").use { result ->
                        for (id in listOf("v69-provider", "v69-static")) {
                            assertTrue(result.next())
                            assertEquals(id, result.getString(1))
                            assertEquals(0L, result.getLong(2), "映射版本应独立初始化，不复用密钥策略版本")
                            assertEquals(7L, result.getLong(3))
                            assertEquals(2L, result.getLong(4), "新增映射状态不应重写渠道更新时间")
                        }
                        assertFalse(result.next())
                    }
                    statement.executeQuery("select channel_id, upstream_model, canonical_model_id, mapping_source, catalog_active, created_at from channel_models").use { result ->
                        val mappings = buildMap {
                            while (result.next()) {
                                put(result.getString(1) to result.getString(2), result.getString(3) to result.getString(4))
                                assertTrue(result.getBoolean(5), "历史普通与 provider 映射都应保持 active")
                                assertEquals(1L, result.getLong(6))
                            }
                        }
                        assertEquals(
                            mapOf(
                                ("v69-static" to "Static-Upstream") to ("Public-Static" to "manual"),
                                ("v69-provider" to "Account-Upstream") to ("Public-Account" to "manual"),
                                ("v69-provider" to "Suggested-Upstream") to ("Public-Suggested" to "models_dev"),
                                ("v69-provider" to "Fallback-Upstream") to (null to null),
                            ),
                            mappings,
                            "canonical ID 和 manual/models_dev/null 来源必须原样保留",
                        )
                    }
                    statement.executeQuery("select key_id, upstream_model, created_at from channel_key_models").use { result ->
                        assertTrue(result.next())
                        assertEquals("v69-static-key", result.getString(1))
                        assertEquals("Static-Upstream", result.getString(2), "静态密钥授权仍是 upstream ID")
                        assertEquals(1L, result.getLong(3))
                        assertFalse(result.next())
                    }
                    statement.executeQuery("select account_id, channel_id, managed, created_at from oauth_account_channels order by account_id").use { result ->
                        for (id in listOf("v69-oauth-all", "v69-oauth-none", "v69-oauth-restricted")) {
                            assertTrue(result.next())
                            assertEquals(id, result.getString(1))
                            assertEquals("v69-provider", result.getString(2))
                            assertTrue(result.getBoolean(3), "托管关联不得被迁移重建为普通关联")
                            assertEquals(1L, result.getLong(4))
                        }
                        assertFalse(result.next())
                    }
                }
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `v74 initializes active public models once using eligible legacy credential order`() = runBlocking {
        val databaseName = createMigrationDatabase("migration_v74_routes")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "71")
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, protocols, enabled, priority, created_at, updated_at)
                        values ('route-a', 'route-a', 'https://example.invalid/v1', 'responses', true, 20, 1, 1),
                               ('route-b', 'route-b', 'https://example.invalid/v1', 'responses', true, 10, 1, 1),
                               ('route-disabled', 'route-disabled', 'https://example.invalid/v1', 'responses', false, 1, 1, 1),
                               ('route-gemini', 'route-gemini', 'https://example.invalid/v1', 'gemini', true, 10, 1, 1),
                               ('route-protocol', 'route-protocol', 'https://example.invalid/v1', 'unknown', true, 1, 1, 1),
                               ('route-empty', 'route-empty', 'https://example.invalid/v1', 'responses', true, 1, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channel_models (channel_id, upstream_model, canonical_model_id, catalog_active, created_at)
                        values ('route-a', 'alias-a', 'public/family', true, 1),
                               ('route-a', 'alias-b', 'public/family', true, 1),
                               ('route-a', 'dormant', 'public/dormant', false, 1),
                               ('route-b', 'alias-b', 'public/family', true, 1),
                               ('route-disabled', 'alias-a', 'public/disabled', true, 1),
                               ('route-gemini', 'alias-a', 'public/family', true, 1),
                               ('route-protocol', 'alias-a', 'protocol/empty', true, 1),
                               ('route-empty', 'empty', 'public/empty', true, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channel_keys (id, channel_id, name, credential_ciphertext, credential_nonce,
                            credential_key_version, state, priority, created_at, updated_at)
                        values ('key-first', 'route-a', 'first', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 1, 1, 1),
                               ('shared-id', 'route-a', 'static-shared', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 3, 1, 1),
                               ('key-last', 'route-a', 'last', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 9, 1, 1),
                               ('key-b', 'route-b', 'key-b', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 3, 1, 1),
                               ('key-disabled', 'route-a', 'disabled', 'fixture-cipher', 'fixture-nonce', 1, 'MANUAL_DISABLED', 1, 1, 1),
                               ('key-temporary', 'route-a', 'temporary', 'fixture-cipher', 'fixture-nonce', 1, 'AUTO_TEMPORARY', 1, 1, 1),
                               ('key-no-model', 'route-a', 'no-model', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 1, 1, 1),
                               ('key-disabled-channel', 'route-disabled', 'disabled-channel', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 1, 1, 1),
                               ('key-no-protocol', 'route-protocol', 'no-protocol', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 1, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into channel_key_models (key_id, upstream_model, created_at)
                        select id, 'alias-a', 1 from channel_keys where id not in ('key-b', 'key-no-model');
                        insert into channel_key_models (key_id, upstream_model, created_at)
                        values ('key-first', 'alias-b', 1), ('shared-id', 'alias-b', 1),
                               ('key-last', 'alias-b', 1), ('key-b', 'alias-b', 1), ('key-no-model', 'unrelated', 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_accounts (id, name, provider, refresh_token_ciphertext, refresh_token_nonce,
                            refresh_token_key_version, enabled, auth_state, priority, model_catalog, created_at, updated_at, authorized_at)
                        values ('shared-id', 'oauth-shared', 'chatgpt', 'fixture-cipher', 'fixture-nonce', 1, true, 'ACTIVE', 2,
                                    '[{"upstreamModel":"alias-a"},{"upstreamModel":"alias-b"}]', 1, 1, 1),
                               ('oauth-inherited', 'inherited', 'chatgpt', 'fixture-cipher', 'fixture-nonce', 1, true, 'ACTIVE', 5, null, 1, 1, 1),
                               ('oauth-gemini', 'gemini', 'antigravity', 'fixture-cipher', 'fixture-nonce', 1, true, 'ACTIVE', 4, null, 1, 1, 1),
                               ('oauth-disabled', 'disabled', 'chatgpt', 'fixture-cipher', 'fixture-nonce', 1, false, 'ACTIVE', 1, null, 1, 1, 1),
                               ('oauth-reauth', 'reauth', 'chatgpt', 'fixture-cipher', 'fixture-nonce', 1, true, 'REAUTH_REQUIRED', 1, null, 1, 1, 1),
                               ('oauth-no-model', 'no-model', 'chatgpt', 'fixture-cipher', 'fixture-nonce', 1, true, 'ACTIVE', 1, null, 1, 1, 1),
                               ('oauth-no-catalog', 'no-catalog', 'chatgpt', 'fixture-cipher', 'fixture-nonce', 1, true, 'ACTIVE', 1, '[]', 1, 1, 1),
                               ('oauth-no-protocol', 'no-protocol', 'antigravity', 'fixture-cipher', 'fixture-nonce', 1, true, 'ACTIVE', 1, null, 1, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_account_channels (account_id, channel_id, created_at)
                        select id, 'route-a', 1 from oauth_accounts where id <> 'oauth-gemini';
                        insert into oauth_account_channels (account_id, channel_id, created_at)
                        values ('shared-id', 'route-b', 1), ('oauth-gemini', 'route-gemini', 1);
                        insert into oauth_account_models (account_id, upstream_model, created_at)
                        values ('shared-id', 'alias-a', 1), ('shared-id', 'alias-b', 1), ('oauth-no-model', 'unrelated', 1)
                        """.trimIndent(),
                    )
                }
            }
            migrateTo(migrationUrl, "74")
            val expected = listOf(
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-b", dev.soven.prismllm.routing.readmodel.CredentialRouteType.OAUTH, "shared-id"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-b", dev.soven.prismllm.routing.readmodel.CredentialRouteType.STATIC, "key-b"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-gemini", dev.soven.prismllm.routing.readmodel.CredentialRouteType.OAUTH, "oauth-gemini"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-a", dev.soven.prismllm.routing.readmodel.CredentialRouteType.STATIC, "key-first"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-a", dev.soven.prismllm.routing.readmodel.CredentialRouteType.OAUTH, "shared-id"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-a", dev.soven.prismllm.routing.readmodel.CredentialRouteType.STATIC, "shared-id"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-a", dev.soven.prismllm.routing.readmodel.CredentialRouteType.OAUTH, "oauth-inherited"),
                dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity("route-a", dev.soven.prismllm.routing.readmodel.CredentialRouteType.STATIC, "key-last"),
            )
            TestDatabase(migrationUrl, postgres.username, postgres.password).use { database ->
                val repository = dev.soven.prismllm.repo.ModelCredentialRouteRepository(database.database)
                val route = assertNotNull(repository.find("public/family"))
                assertEquals(0L, route.revision)
                assertTrue(route.configured)
                assertEquals(expected, route.entries, "aliases collapse within channel/type/ID; legacy priority is preserved only at migration")
                assertEquals(setOf("public/family", "public/empty", "protocol/empty"), repository.list().map { it.modelId }.toSet())
                assertTrue(assertNotNull(repository.find("public/empty")).entries.isEmpty())
                assertTrue(assertNotNull(repository.find("protocol/empty")).entries.isEmpty())
                DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                            insert into channel_models (channel_id, upstream_model, canonical_model_id, catalog_active, created_at)
                            values ('route-a', 'post-upstream', 'post/model', true, 2);
                            insert into channel_keys (id, channel_id, name, credential_ciphertext, credential_nonce,
                                credential_key_version, state, priority, created_at, updated_at)
                            values ('post-key', 'route-a', 'post-key', 'fixture-cipher', 'fixture-nonce', 1, 'ACTIVE', 1, 2, 2);
                            insert into channel_key_models (key_id, upstream_model, created_at)
                            values ('post-key', 'alias-a', 2), ('post-key', 'post-upstream', 2)
                            """.trimIndent(),
                        )
                    }
                }
                migrateTo(migrationUrl, "74")
                assertNull(repository.find("post/model"), "migration reruns and reads never populate later models")
                assertEquals(expected, assertNotNull(repository.find("public/family")).entries, "new credentials require an explicit save")
                val empty = repository.save("public/family", 0, emptyList())
                assertEquals(1L, empty.revision, "migration revision zero is the first editable revision")
                assertTrue(assertNotNull(repository.find("public/family")).entries.isEmpty())
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `migration repository reads channels and keys before egress columns exist`() = runBlocking {
        val databaseName = createMigrationDatabase("migration_v79_egress_reads")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(migrationUrl, "73")
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("""insert into channels (id, name, base_url, protocols, enabled, created_at, updated_at)
                        values ('legacy-egress', 'legacy-egress', 'https://example.invalid/v1', 'chat_completions', true, 1, 1)""")
                    statement.execute("""insert into channel_keys
                        (id, channel_id, name, credential_ciphertext, credential_nonce, credential_key_version, state, priority, created_at, updated_at)
                        values ('legacy-key', 'legacy-egress', 'legacy-key', 'cipher', 'nonce', 1, '${ChannelKeyState.ACTIVE.value}', 1, 1, 1)""")
                }
            }
            TestDatabase(migrationUrl, postgres.username, postgres.password).use { database ->
                val repository = ChannelRepository(database.database, app.credentialCipher, includeEgressConfiguration = false)
                val channel = repository.list().single()
                assertEquals(emptyList(), channel.egressProxies)
                assertNull(channel.keys.single().egressProxyId)
                assertEquals(channel, repository.find(channel.id))
                assertEquals(channel, repository.findByName(channel.name))
                assertEquals(channel, repository.inTransaction { repository.findInTransaction(channel.id) })
            }
            migrateTo(migrationUrl)
            TestDatabase(migrationUrl, postgres.username, postgres.password).use { database ->
                val current = ChannelRepository(database.database, app.credentialCipher).list().single()
                assertEquals(emptyList(), current.egressProxies)
                assertNull(current.keys.single().egressProxyId)
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    @Test
    @Timeout(300)
    fun `gateway reconciles pending oauth channel before v74 seeds credential routes`() = runBlocking {
        val databaseName = createMigrationDatabase("migration_v74_pending_oauth")
        val migrationUrl = migrationDatabaseUrl(databaseName)
        val originalId = "legacy-oauth"
        val publicModel = "migration-public/model"
        val upstreamModel = "migration-upstream"
        try {
            migrateTo(migrationUrl, "71")
            DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into channels (id, name, base_url, protocols, enabled, created_at, updated_at)
                        values ('legacy-oauth-channel', 'legacy-oauth-channel', 'https://example.invalid/v1', 'responses', true, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_accounts (id, name, provider, refresh_token_ciphertext, refresh_token_nonce,
                            refresh_token_key_version, enabled, auth_state, priority, model_catalog,
                            provider_channel_pending, created_at, updated_at, authorized_at)
                        values ('$originalId', 'legacy-pending', 'chatgpt', 'fixture-ciphertext', 'fixture-nonce', 1,
                            true, 'ACTIVE', 1,
                            '[{"upstreamModel":"$upstreamModel","canonicalModelId":"$publicModel"}]',
                            true, 1, 1, 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_account_channels (account_id, channel_id, created_at)
                        values ('$originalId', 'legacy-oauth-channel', 1)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into oauth_account_models (account_id, upstream_model, created_at)
                        values ('$originalId', '$upstreamModel', 1)
                        """.trimIndent(),
                    )
                }
            }

            val upgrade = dev.soven.prismllm.api.GatewayApplication(config().copy(databaseUrl = migrationUrl))
            try {
                DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("alter table oauth_account_channels add constraint migration_v74_test_guard check (channel_id = 'legacy-oauth-channel')")
                    }
                }
                assertTrue(runCatching { upgrade.databaseFactory.migrate() }.isFailure, "a reconciliation failure must block V74")
                DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select to_regclass('model_credential_routes')").use { result ->
                            assertTrue(result.next())
                            assertNull(result.getString(1), "the route seed must not run after a failed prerequisite")
                        }
                        statement.execute("alter table oauth_account_channels drop constraint migration_v74_test_guard")
                    }
                }
                testApplication {
                    application { upgrade.install(this) }
                    startApplication()
                    val providerChannel = assertNotNull(upgrade.channelRepository.findProviderChannel(dev.soven.prismllm.shared.domain.OAuthProvider.CHATGPT))
                    val account = assertNotNull(upgrade.oauthAccountRepository.findDetailed(originalId))
                    assertEquals(setOf(providerChannel.id), account.channelIds, "pending legacy association must be moved before route bootstrap")
                    DriverManager.getConnection(migrationUrl, postgres.username, postgres.password).use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("select provider_channel_pending from oauth_accounts where id = '$originalId'").use { result ->
                                assertTrue(result.next())
                                assertFalse(result.getBoolean(1))
                            }
                        }
                    }
                    val route = assertNotNull(upgrade.modelCredentialRouteRepository.find(publicModel))
                    val identity = dev.soven.prismllm.routing.readmodel.CredentialRouteIdentity(
                        providerChannel.id,
                        dev.soven.prismllm.routing.readmodel.CredentialRouteType.OAUTH,
                        originalId,
                    )
                    assertEquals(listOf(identity), route.entries, "V74 must seed the final provider-channel identity")
                    val candidates = upgrade.modelCatalog.findCandidates(publicModel, dev.soven.prismllm.shared.domain.GatewayProtocol.RESPONSES)
                    assertEquals(listOf(identity.channelId), candidates.map { it.channel.id })
                    assertEquals(listOf(upstreamModel), candidates.map { it.upstreamModel })

                    val laterAccount = upgrade.oauthAccountRepository.create(
                        dev.soven.prismllm.oauth.persistence.NewOAuthAccount(
                            name = "later-account",
                            provider = dev.soven.prismllm.shared.domain.OAuthProvider.CHATGPT,
                            refreshToken = upgrade.credentialCipher.encrypt(java.util.UUID.randomUUID().toString()),
                            channelIds = listOf(providerChannel.id),
                            models = listOf(upstreamModel),
                        ),
                    )
                    assertEquals(listOf(identity), upgrade.modelCredentialRouteRepository.find(publicModel)!!.entries)

                    val restarted = dev.soven.prismllm.api.GatewayApplication(config().copy(databaseUrl = migrationUrl))
                    try {
                        testApplication {
                            application { restarted.install(this) }
                            startApplication()
                            val persisted = assertNotNull(restarted.modelCredentialRouteRepository.find(publicModel))
                            assertEquals(listOf(identity), persisted.entries, "an already-applied V74 must not append later credentials on restart")
                            assertFalse(persisted.entries.any { it.credentialId == laterAccount.id })
                            val restartedCandidates = restarted.modelCatalog.findCandidates(
                                publicModel,
                                dev.soven.prismllm.shared.domain.GatewayProtocol.RESPONSES,
                            )
                            assertEquals(listOf(originalId), restartedCandidates.map { it.credential.id })
                        }
                    } finally {
                        restarted.close()
                    }
                }
            } finally {
                upgrade.close()
            }
        } finally {
            dropDatabaseQuietly(databaseName)
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

    private val databaseNamePattern = Regex("[A-Za-z0-9_]+")

    /** 迁移用例需要独立数据库；名称由固定前缀和 nanoTime 生成，仍校验字符集后再拼 DDL。 */
    /** 验证升级不会将旧白名单改成黑名单或丢失关联。 */
    @Test
    fun `v69 preserves v68 caller policy modes and whitelist rows`() {
        val databaseName = createMigrationDatabase("migration_v69")
        val url = migrationDatabaseUrl(databaseName)
        try {
            migrateTo(url, "68")
            DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO client_keys (id,name,key_hash,key_prefix,enabled,created_at,access_models_restricted) VALUES ('legacy','legacy','legacy-hash','prefix',true,1,true)")
                    statement.executeUpdate("INSERT INTO client_key_allowed_models (client_key_id,model_id) VALUES ('legacy','Model-A')")
                }
            }
            migrateTo(url)
            DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT access_models_restricted, access_channels_denied, access_channel_keys_denied, access_models_denied FROM client_keys WHERE id='legacy'").use { result ->
                        assertTrue(result.next()); assertTrue(result.getBoolean(1))
                        for (index in 2..4) assertFalse(result.getBoolean(index))
                    }
                    statement.executeQuery("SELECT model_id FROM client_key_allowed_models WHERE client_key_id='legacy'").use { result ->
                        assertTrue(result.next()); assertEquals("Model-A", result.getString(1))
                    }
                }
            }
        } finally {
            dropDatabaseQuietly(databaseName)
        }
    }

    private fun createMigrationDatabase(prefix: String): String {
        val name = "${prefix}_${System.nanoTime()}"
        require(name.matches(databaseNamePattern)) { "unsafe database name: $name" }
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("create database \"$name\"") }
        }
        return name
    }

    /** 分阶段执行迁移；失败时带上目标版本，避免多段迁移里的异常难以定位。 */
    private fun migrateTo(migrationUrl: String, version: String? = null) {
        val stage = version?.let { "V$it" } ?: "latest"
        try {
            val configuration = Flyway.configure()
                .dataSource(migrationUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
            if (version != null) configuration.target(MigrationVersion.fromVersion(version))
            configuration.load().migrate()
        } catch (e: Exception) {
            throw IllegalStateException("Flyway migration to $stage failed", e)
        }
    }

    private fun migrationDatabaseUrl(databaseName: String): String {
        require(databaseName.matches(databaseNamePattern)) { "unsafe database name: $databaseName" }
        return databaseUrl.substringBefore("?").substringBeforeLast("/") + "/$databaseName"
    }

    /** 清理临时迁移库；清理失败不应覆盖测试本身的原始异常。 */
    private fun dropDatabaseQuietly(databaseName: String) {
        require(databaseName.matches(databaseNamePattern)) { "unsafe database name: $databaseName" }
        runCatching {
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use {
                    it.execute("drop database if exists \"$databaseName\" with (force)")
                }
            }
        }
    }
}
