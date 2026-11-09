package dev.soven.llmgateway.integration

import dev.soven.llmgateway.api.GatewayApplication
import dev.soven.llmgateway.channel.domain.ChannelRecord
import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import dev.soven.llmgateway.channel.domain.ChannelModelRecord
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import dev.soven.llmgateway.channel.persistence.NewChannelKey
import dev.soven.llmgateway.channel.persistence.ChannelKeyUpdate
import dev.soven.llmgateway.domain.effective
import dev.soven.llmgateway.repo.KeyAutoDisableRepository
import dev.soven.llmgateway.channel.domain.key.KeyAttemptOutcome
import dev.soven.llmgateway.channel.domain.key.KeyAttemptSignal
import dev.soven.llmgateway.channel.domain.key.ChannelKeyDisableMode
import dev.soven.llmgateway.channel.domain.key.ChannelKeyState
import dev.soven.llmgateway.resilience.FailureKind
import dev.soven.llmgateway.channel.domain.key.KeyAutoDisablePolicy
import dev.soven.llmgateway.channel.domain.key.KeyDisableAction
import dev.soven.llmgateway.channel.domain.key.KeyDisablePolicy
import dev.soven.llmgateway.channel.domain.key.KeyDisableRule
import dev.soven.llmgateway.channel.domain.key.ResolvedKeyDisablePolicy
import dev.soven.llmgateway.channel.domain.key.toKeyDisablePolicy
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
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.util.cio.ChannelWriteException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
@Tag("shard-3")
@Tag("channel")
class KeyAutoDisableIntegrationTest : GatewayIntegrationTestBase() {

    /**
     * 审计失败用例会临时给 admin_audit_logs 加 CHECK 约束。约束名是本类专用，
     * 每个用例结束后无条件删除，避免用例中途失败时残留约束并拖垮后续用例。
     */
    @AfterEach
    fun dropTemporaryConstraints() {
        runCatching {
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use {
                    it.execute("alter table admin_audit_logs drop constraint if exists test_auto_disable_audit_failure")
                }
            }
        }
    }

    @Test
    fun keyAutoDisableDefaultsFailOverAndPersistAudit() = runBlocking {
        val channel = createAutoDisablePool()
        val key = createClientKey()
        // 本用例需要 1ms 的鉴权失败冷却；使用独立实例，避免关闭并替换基类共享的 app。
        val policyApp = GatewayApplication(config().let { it.copy(cooldown = it.cooldown.copy(authFailureMs = 1)) })
        try {
            testApplication {
                application { policyApp.install(this) }
                repeat(3) { index ->
                    fake.keyStatuses["Bearer key-primary"] = if (index == 2) 403 else 401
                    assertEquals(HttpStatusCode.OK, client.sendAutoDisableRequest(key, requestId = "auto-auth-$index").status)
                    val current = assertNotNull(policyApp.channelRepository.find(channel.id))
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
        } finally {
            policyApp.close()
        }
        // 基类实例一直未关闭，重新读取同一数据库即可验证停用状态已持久化。
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
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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

            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            // 到期时间由数据库在写入瞬间计算，避免应用与数据库时钟偏差或计算/写入间隙。
            connection.prepareStatement(
                "update channel_keys set auto_disable_until = (extract(epoch from now()) * 1000)::bigint + 300000 where id = ?",
            ).use { statement ->
                statement.setString(1, channel.keys.single().id)
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
            assertTrue(retryAfter.toLong() in 1..600, "RETRY-AFTER=[$retryAfter]")
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
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
            // finally 里再失败不能让约束留到后续用例；@AfterEach 会再做一次兜底清理。
            runCatching {
                constraint("alter table admin_audit_logs drop constraint if exists test_auto_disable_audit_failure")
            }
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        val persistedRules = DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
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
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("update channel_keys set state = 'AUTO_TEMPORARY', auto_disable_until = ? where id = ?").use { stmt ->
                stmt.setLong(1, System.currentTimeMillis() + 600_000)
                stmt.setString(2, key.id)
                stmt.executeUpdate()
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

            DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { conn ->
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
}
