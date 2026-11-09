package dev.soven.llmgateway.integration

import dev.soven.llmgateway.channel.domain.key.KeyDisableAction
import dev.soven.llmgateway.channel.domain.key.KeyDisablePolicy
import dev.soven.llmgateway.channel.domain.key.KeyDisableRule
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
                        assertEquals("60", result.getString(1))
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
