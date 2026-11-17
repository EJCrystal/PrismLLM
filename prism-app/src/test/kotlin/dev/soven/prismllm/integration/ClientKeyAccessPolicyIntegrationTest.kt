package dev.soven.prismllm.integration

import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyAllowedChannelKey
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 使用真实 PostgreSQL 验证调用方访问范围的管理契约与生命周期。 */
@Tag("integration")
@Tag("shard-1")
@Tag("channel")
class ClientKeyAccessPolicyIntegrationTest : GatewayIntegrationTestBase() {
    /** 完整的不限制策略，用于显式清除已有授权限制。 */
    private val unrestricted = """{"channels":{"mode":"unrestricted","ids":[]},"channelKeys":{"mode":"unrestricted","keys":[]},"models":{"mode":"unrestricted","ids":[]}}"""

    @Test
    fun `repository normalizes scopes and preserves them through unrelated updates`() = runBlocking {
        val channel = createChannel("repository", "ok")
        val policy = ClientKeyAccessPolicy(
            allowedChannelIds = setOf(" ${channel.id} ", channel.id),
            allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(" ${channel.id} ", " ${channel.keys.single().id} ")),
            allowedModelIds = setOf(" Model-A ", "Model-A"),
        )
        val saved = app.clientKeyRepository.create("repository", "hash", "prefix", true, null, accessPolicy = policy)
        assertEquals(setOf(channel.id), saved.accessPolicy.allowedChannelIds)
        assertEquals(setOf(ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id)), saved.accessPolicy.allowedChannelKeys)
        assertEquals(setOf("Model-A"), saved.accessPolicy.allowedModelIds)
        assertEquals(saved, app.clientKeyRepository.findByHash("hash"))
        assertEquals(saved, app.clientKeyRepository.find(saved.id))
        assertEquals(saved, app.clientKeyRepository.list().single())
        val updated = assertNotNull(app.clientKeyRepository.update(saved.id, "status", enabled = false, expiresAt = null, quotas = ClientKeyQuotaLimits(dailyTokens = 12)))
        assertEquals(saved.accessPolicy, updated.accessPolicy)
        val explicitNull = assertNotNull(app.clientKeyRepository.update(saved.id, "null", enabled = true, expiresAt = null, accessPolicy = null))
        assertEquals(saved.accessPolicy, explicitNull.accessPolicy)
        val replaced = assertNotNull(app.clientKeyRepository.update(saved.id, "empty", enabled = true, expiresAt = null, accessPolicy = ClientKeyAccessPolicy(allowedModelIds = emptySet())))
        assertEquals(null, replaced.accessPolicy.allowedChannelIds)
        assertEquals(null, replaced.accessPolicy.allowedChannelKeys)
        assertEquals(emptySet(), replaced.accessPolicy.allowedModelIds)
        assertFalse(replaced.accessPolicy.isUnrestricted)
        val cleared = assertNotNull(app.clientKeyRepository.update(saved.id, "clear", enabled = true, expiresAt = null, accessPolicy = ClientKeyAccessPolicy()))
        assertTrue(cleared.accessPolicy.isUnrestricted)
    }

    @Test
    fun `repository foreign key rejects mismatched channel key pair atomically`() = runBlocking {
        val first = createChannel("foreign-first", "ok")
        val second = createChannel("foreign-second", "ok")
        val failed = runCatching {
            app.clientKeyRepository.create(
                "invalid", "hash", "prefix", true, null,
                accessPolicy = ClientKeyAccessPolicy(allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(first.id, second.keys.single().id))),
            )
        }
        assertTrue(failed.isFailure)
        assertTrue(app.clientKeyRepository.list().isEmpty())
    }

    @Test
    fun `find hash lookup and list retain one snapshot when policy changes between queries`() = runBlocking {
        val saved = app.clientKeyRepository.create(
            "snapshot", "snapshot-hash", "prefix", true, null,
            accessPolicy = ClientKeyAccessPolicy(allowedModelIds = setOf("Model-A")),
        )
        for (lookup in listOf("find", "hash", "list")) {
            app.clientKeyRepository.update(saved.id, "snapshot", enabled = true, expiresAt = null, accessPolicy = ClientKeyAccessPolicy(allowedModelIds = setOf("Model-A")))
            suspendTransaction(app.databaseFactory.database) {
                var changed = false
                // 在基础密钥 SELECT 执行完成后由独立连接原子切换策略，准确插入两次读取之间。
                addLogger(object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        val sql = context.statement.prepareSQL(transaction).lowercase()
                        if (!changed && sql.startsWith("select") && "from client_keys" in sql) {
                            changed = true
                            java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                                connection.autoCommit = false
                                connection.createStatement().use { statement ->
                                    statement.executeUpdate("UPDATE client_keys SET access_models_restricted = false WHERE id = '${saved.id}'")
                                    statement.executeUpdate("DELETE FROM client_key_allowed_models WHERE client_key_id = '${saved.id}'")
                                }
                                connection.commit()
                            }
                        }
                    }
                })
                val current = when (lookup) {
                    "find" -> app.clientKeyRepository.find(saved.id)
                    "hash" -> app.clientKeyRepository.findByHash("snapshot-hash")
                    else -> app.clientKeyRepository.list().single()
                }
                assertTrue(changed, "独立更新没有发生在 $lookup 的查询之间")
                assertEquals(setOf("Model-A"), assertNotNull(current).accessPolicy.allowedModelIds, lookup)
            }
            assertTrue(assertNotNull(app.clientKeyRepository.find(saved.id)).accessPolicy.isUnrestricted)
        }
    }

    @Test
    fun `old create requests return complete unrestricted policy`() = testApplication {
        application { app.install(this) }
        val auth = client.loginAdmin()
        val created = client.create(auth)
        assertEquals(Json.parseToJsonElement(unrestricted), created["accessPolicy"])
        val listed = client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray.single().jsonObject
        assertEquals(created["accessPolicy"], listed["accessPolicy"])
    }

    @Test
    fun `empty allowlists survive create list update rotate and revoke`() = testApplication {
        application { app.install(this) }
        val auth = client.loginAdmin()
        val policy = """{"channels":{"mode":"allowlist","ids":[]},"channelKeys":{"mode":"allowlist","keys":[]},"models":{"mode":"allowlist","ids":[]}}"""
        val created = client.create(auth, policy)
        val id = created.id()
        assertEquals(Json.parseToJsonElement(policy), created["accessPolicy"])
        val updated = client.put("/admin/api/client-keys/$id") {
            authenticate(auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"changed","enabled":false,"dailyTokenLimit":123,"requestsPerMinute":7}""")
        }.objectBody(HttpStatusCode.OK)
        assertEquals(created["accessPolicy"], updated["accessPolicy"])
        val explicitNull = client.put("/admin/api/client-keys/$id") {
            authenticate(auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"changed","accessPolicy":null}""")
        }.objectBody(HttpStatusCode.OK)
        assertEquals(created["accessPolicy"], explicitNull["accessPolicy"])
        val rotated = client.post("/admin/api/client-keys/$id/rotate") { authenticate(auth) }.objectBody(HttpStatusCode.OK)
        assertEquals(created["accessPolicy"], rotated["accessPolicy"])
        assertTrue(rotated["key"] != created["key"])
        val revoked = client.post("/admin/api/client-keys/$id/revoke") { authenticate(auth) }.objectBody(HttpStatusCode.OK)
        assertEquals(created["accessPolicy"], revoked["accessPolicy"])
        val rejected = client.put("/admin/api/client-keys/$id") {
            authenticate(auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"revoked","accessPolicy":$unrestricted}""")
        }
        assertEquals(HttpStatusCode.Conflict, rejected.status)
    }

    @Test
    fun `canonical policy round trips and explicit unrestricted clears every scope`() = runBlocking {
        val channel = createChannel("scope", "ok")
        exposeAsModel("Case-Sensitive", channel to "gpt-4o-mini")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val policy = scoped(channel.id, channel.keys.single().id, "Case-Sensitive")
            val created = client.create(auth, policy.replace(channel.id, " ${channel.id} ").replace("Case-Sensitive", " Case-Sensitive "))
            assertEquals(Json.parseToJsonElement(policy), created["accessPolicy"])
            val list = client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray
            assertEquals(created["accessPolicy"], list.single().jsonObject["accessPolicy"])
            val record = assertNotNull(app.clientKeyRepository.find(created.id()))
            assertNotNull(app.clientKeyRepository.findByHash(record.keyHash))
            assertEquals(record, app.clientKeyRepository.list().single())
            val cleared = client.put("/admin/api/client-keys/${created.id()}") {
                authenticate(auth)
                header("X-Request-Id", "access-policy-clear")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"cleared","accessPolicy":$unrestricted}""")
            }.objectBody(HttpStatusCode.OK)
            assertEquals(Json.parseToJsonElement(unrestricted), cleared["accessPolicy"])
            val audits = client.get("/admin/api/audit-logs?limit=100") { authenticate(auth) }.json().jsonArray
            val audit = audits.single { it.jsonObject["requestId"]?.jsonPrimitive?.content == "access-policy-clear" }.jsonObject
            assertEquals("client_key.update", audit["action"]!!.jsonPrimitive.content)
            assertEquals("success", audit["result"]!!.jsonPrimitive.content)
            assertFalse(audit.toString().contains(created["key"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `deleting a static key and model mapping retains restricted authorization`() = runBlocking {
        val channel = createChannel("key-removed", "ok")
        val saved = app.clientKeyRepository.create(
            "key-removed", "hash", "prefix", true, null,
            accessPolicy = ClientKeyAccessPolicy(allowedChannelKeys = setOf(ClientKeyAllowedChannelKey(channel.id, channel.keys.single().id)), allowedModelIds = setOf("m1")),
        )
        java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM channel_keys WHERE id = '${channel.keys.single().id}'")
                statement.executeUpdate("DELETE FROM channel_models WHERE channel_id = '${channel.id}'")
            }
        }
        val current = assertNotNull(app.clientKeyRepository.find(saved.id))
        assertEquals(emptySet(), current.accessPolicy.allowedChannelKeys)
        assertEquals(setOf("m1"), current.accessPolicy.allowedModelIds)
        assertFalse(current.accessPolicy.isUnrestricted)
    }

    @Test
    fun `invalid policy update rejects without changing key status name or quota`() = runBlocking {
        val first = createChannel("first", "ok")
        val second = createChannel("second", "ok")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val created = client.create(auth, scoped(first.id, first.keys.single().id, "m1"))
            val invalidPolicies = listOf(
                scoped(first.id, second.keys.single().id, "m1"),
                scoped("unknown-channel", first.keys.single().id, "m1"),
                scoped(first.id, "unknown-key", "m1"),
                scoped(first.id, first.keys.single().id, "unknown-model"),
                scoped(first.id, first.keys.single().id, "m1").replace("\"ids\":[\"${first.id}\"]", "\"ids\":[\"${second.id}\"]"),
                scoped(second.id, second.keys.single().id, "m1").replace("\"channelId\":\"${second.id}\"", "\"channelId\":\"${first.id}\""),
                unrestricted.replace("unrestricted", "unknown-mode"),
                unrestricted.replace("\"ids\":[]", "\"ids\":[\"m1\"]"),
                """{"channels":{"mode":"unrestricted","ids":[]}}""",
                unrestricted.replace("\"mode\":\"unrestricted\",", ""),
                scoped(first.id, first.keys.single().id, "m1").replace("m1", " "),
                scoped(first.id, first.keys.single().id, "m1").replace("m1", "x".repeat(201)),
            )
            for (policy in invalidPolicies) {
                val response = client.put("/admin/api/client-keys/${created.id()}") {
                    authenticate(auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"must-not-save","enabled":false,"dailyTokenLimit":1,"accessPolicy":$policy}""")
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                val current = assertNotNull(app.clientKeyRepository.find(created.id()))
                assertEquals("access-test", current.name)
                assertTrue(current.enabled)
                assertEquals(null, current.quotas.dailyTokens)
            }
            val list = client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray
            assertEquals(created["accessPolicy"], list.single().jsonObject["accessPolicy"])
        }
    }

    @Test
    fun `disabled configured channel key and public model remain valid authorization targets`() = runBlocking {
        val channel = createChannel("disabled", "ok")
        exposeAsModel("Configured-Model", channel to "gpt-4o-mini")
        java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE channels SET enabled = false WHERE id = '${channel.id}'")
                statement.executeUpdate("UPDATE channel_keys SET state = 'MANUAL_DISABLED' WHERE id = '${channel.keys.single().id}'")
            }
        }
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val policy = scoped(channel.id, channel.keys.single().id, "Configured-Model")
            assertEquals(Json.parseToJsonElement(policy), client.create(auth, policy)["accessPolicy"])
            val rejected = client.post("/admin/api/client-keys") {
                authenticate(auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"invalid-case","accessPolicy":${scoped(channel.id, channel.keys.single().id, "configured-model")}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
        }
    }

    @Test
    fun `deleting authorized channel retains empty restricted scopes and deleting caller cleans associations`() = runBlocking {
        val channel = createChannel("removed", "ok")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val created = client.create(auth, scoped(channel.id, channel.keys.single().id, "m1"))
            assertTrue(app.channelRepository.delete(channel.id))
            val policy = assertNotNull(client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray.single().jsonObject["accessPolicy"]).jsonObject
            assertEquals("allowlist", policy["channels"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertTrue(policy["channels"]!!.jsonObject["ids"]!!.jsonArray.isEmpty())
            assertEquals("allowlist", policy["channelKeys"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertTrue(policy["channelKeys"]!!.jsonObject["keys"]!!.jsonArray.isEmpty())
            assertEquals("m1", policy["models"]!!.jsonObject["ids"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals(HttpStatusCode.NoContent, client.delete("/admin/api/client-keys/${created.id()}") { authenticate(auth) }.status)
            java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    for (table in listOf("client_key_allowed_channels", "client_key_allowed_channel_keys", "client_key_allowed_models")) {
                        statement.executeQuery("SELECT count(*) FROM $table").use { result ->
                            assertTrue(result.next())
                            assertEquals(0, result.getInt(1))
                        }
                    }
                }
            }
        }
    }

    /** 验证黑名单管理契约、快照读取及密钥生命周期。 */
    @Test
    fun `denylist foreign key and mutually exclusive modes reject atomic writes`() = runBlocking {
        val first = createChannel("deny-first", "ok")
        val second = createChannel("deny-second", "ok")
        assertTrue(runCatching {
            app.clientKeyRepository.create("invalid-deny", "hash", "prefix", true, null,
                accessPolicy = ClientKeyAccessPolicy(deniedChannelKeys = setOf(ClientKeyAllowedChannelKey(first.id, second.keys.single().id))))
        }.isFailure)
        assertTrue(app.clientKeyRepository.list().isEmpty())
        val saved = app.clientKeyRepository.create("mutex", "hash", "prefix", true, null,
            accessPolicy = ClientKeyAccessPolicy(allowedChannelIds = emptySet(), allowedChannelKeys = emptySet(), allowedModelIds = emptySet()))
        java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            for (column in listOf("access_channels_denied", "access_channel_keys_denied", "access_models_denied")) {
                assertTrue(runCatching { connection.createStatement().use { it.executeUpdate("UPDATE client_keys SET $column = true WHERE id = '${saved.id}'") } }.isFailure)
            }
        }
        assertEquals(saved, app.clientKeyRepository.find(saved.id))
        assertTrue(runCatching {
            app.clientKeyRepository.update(saved.id, "invalid", enabled = false, expiresAt = null,
                accessPolicy = ClientKeyAccessPolicy(allowedModelIds = emptySet(), deniedModelIds = emptySet()))
        }.isFailure)
        assertEquals(saved, app.clientKeyRepository.find(saved.id))
    }

    @Test
    fun `denylist associations use the same snapshot as base modes in every lookup`() = runBlocking {
        val saved = app.clientKeyRepository.create("deny-snapshot", "deny-hash", "prefix", true, null,
            accessPolicy = ClientKeyAccessPolicy(deniedModelIds = setOf("Model-A")))
        for (lookup in listOf("find", "hash", "list")) {
            app.clientKeyRepository.update(saved.id, "deny-snapshot", enabled = true, expiresAt = null,
                accessPolicy = ClientKeyAccessPolicy(deniedModelIds = setOf("Model-A")))
            suspendTransaction(app.databaseFactory.database) {
                var changed = false
                addLogger(object : SqlLogger {
                    override fun log(context: StatementContext, transaction: Transaction) {
                        val sql = context.statement.prepareSQL(transaction).lowercase()
                        if (!changed && sql.startsWith("select") && "from client_keys" in sql) {
                            changed = true
                            java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                                connection.autoCommit = false
                                connection.createStatement().use { statement ->
                                    statement.executeUpdate("UPDATE client_keys SET access_models_denied = false WHERE id = '${saved.id}'")
                                    statement.executeUpdate("DELETE FROM client_key_denied_models WHERE client_key_id = '${saved.id}'")
                                }
                                connection.commit()
                            }
                        }
                    }
                })
                val current = when (lookup) {
                    "find" -> app.clientKeyRepository.find(saved.id)
                    "hash" -> app.clientKeyRepository.findByHash("deny-hash")
                    else -> app.clientKeyRepository.list().single()
                }
                assertTrue(changed)
                assertEquals(setOf("Model-A"), assertNotNull(current).accessPolicy.deniedModelIds)
            }
        }
    }

    /** 验证黑名单管理契约、快照读取及密钥生命周期。 */
    @Test
    fun `denylist policy round trips through all repository reads and lifecycle updates`() = runBlocking {
        val channel = createChannel("denylist", "ok")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val policy = scoped(channel.id, channel.keys.single().id, "m1").replace("allowlist", "denylist")
            val created = client.create(auth, policy.replace(channel.id, " ${channel.id} ").replace("m1", " m1 "))
            assertEquals(Json.parseToJsonElement(policy), created["accessPolicy"])
            val record = assertNotNull(app.clientKeyRepository.find(created.id()))
            assertEquals(record, app.clientKeyRepository.findByHash(record.keyHash))
            assertEquals(record, app.clientKeyRepository.list().single())
            val listed = client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray.single().jsonObject
            assertEquals(created["accessPolicy"], listed["accessPolicy"])
            for (extra in listOf("", ",\"accessPolicy\":null")) {
                val updated = client.put("/admin/api/client-keys/${created.id()}") {
                    authenticate(auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"denylist-updated","enabled":false$extra}""")
                }.objectBody(HttpStatusCode.OK)
                assertEquals(created["accessPolicy"], updated["accessPolicy"])
            }
            val rotated = client.post("/admin/api/client-keys/${created.id()}/rotate") { authenticate(auth) }.objectBody(HttpStatusCode.OK)
            assertEquals(created["accessPolicy"], rotated["accessPolicy"])
            assertEquals(null, app.clientKeyRepository.findByHash(record.keyHash))
        }
    }

    @Test
    fun `empty denylist retains mode and switching replaces old whitelist associations`() = runBlocking {
        val channel = createChannel("switch", "ok")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val created = client.create(auth, scoped(channel.id, channel.keys.single().id, "m1"))
            val policies = listOf(
                scoped(channel.id, channel.keys.single().id, "m1").replace("allowlist", "denylist"),
                unrestricted.replace("unrestricted", "denylist"),
                scoped(channel.id, channel.keys.single().id, "m1"),
                unrestricted,
            )
            for (policy in policies) {
                val updated = client.put("/admin/api/client-keys/${created.id()}") {
                    authenticate(auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"switch","accessPolicy":$policy}""")
                }.objectBody(HttpStatusCode.OK)
                assertEquals(Json.parseToJsonElement(policy), updated["accessPolicy"])
                assertEquals(updated["accessPolicy"], client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray.single().jsonObject["accessPolicy"])
                val tablePrefix = if (policy.contains("denylist")) "allowed" else "denied"
                java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                    for (suffix in listOf("channels", "channel_keys", "models")) {
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT count(*) FROM client_key_${tablePrefix}_$suffix WHERE client_key_id = '${created.id()}'").use { result ->
                                assertTrue(result.next())
                                assertEquals(0, result.getInt(1))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `deleted denylist targets retain mode and model identity`() = runBlocking {
        val channel = createChannel("deleted-deny", "ok")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val created = client.create(auth, scoped(channel.id, channel.keys.single().id, "m1").replace("allowlist", "denylist"))
            app.channelRepository.delete(channel.id)
            val policy = client.get("/admin/api/client-keys") { authenticate(auth) }.json().jsonArray.single().jsonObject["accessPolicy"]!!.jsonObject
            assertEquals("denylist", policy["channels"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals("denylist", policy["channelKeys"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertTrue(policy["channels"]!!.jsonObject["ids"]!!.jsonArray.isEmpty())
            assertTrue(policy["channelKeys"]!!.jsonObject["keys"]!!.jsonArray.isEmpty())
            assertEquals("m1", policy["models"]!!.jsonObject["ids"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals(HttpStatusCode.NoContent, client.delete("/admin/api/client-keys/${created.id()}") { authenticate(auth) }.status)
            java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                for (suffix in listOf("channels", "channel_keys", "models")) {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM client_key_denied_$suffix").use { result ->
                            assertTrue(result.next()); assertEquals(0, result.getInt(1))
                        }
                    }
                }
            }
        }
    }

    /** 完整编辑保留已有模型排除规则，但不接受没有模型路由的新模型 ID。 */
    @Test
    fun `full update preserves deleted model denylist and rejects new unrouted exclusions`() = runBlocking {
        val channel = createChannel("retained-deny", "ok")
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val original = unrestricted.replace("\"models\":{\"mode\":\"unrestricted\",\"ids\":[]}",
                "\"models\":{\"mode\":\"denylist\",\"ids\":[\"m1\"]}")
            val created = client.create(auth, original)
            assertTrue(app.channelRepository.delete(channel.id))
            val retained = original.replace("\"channels\":{\"mode\":\"unrestricted\",\"ids\":[]}",
                "\"channels\":{\"mode\":\"denylist\",\"ids\":[]}")
            val updated = client.put("/admin/api/client-keys/${created.id()}") {
                authenticate(auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"renamed","dailyTokenLimit":12,"accessPolicy":$retained}""")
            }.objectBody(HttpStatusCode.OK)
            assertEquals(Json.parseToJsonElement(retained), updated["accessPolicy"])
            assertEquals("renamed", updated["name"]!!.jsonPrimitive.content)
            assertEquals(ClientKeyQuotaLimits(dailyTokens = 12), assertNotNull(app.clientKeyRepository.find(created.id())).quotas)
            for (invalid in listOf(retained.replace("[\"m1\"]", "[\"m1\",\"unknown\"]"),
                retained.replace("[\"m1\"]", "[\"M1\"]"))) {
                val rejected = client.put("/admin/api/client-keys/${created.id()}") {
                    authenticate(auth)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"must-not-change","accessPolicy":$invalid}""")
                }
                assertEquals(HttpStatusCode.BadRequest, rejected.status, rejected.bodyAsText())
                assertEquals(setOf("m1"), assertNotNull(app.clientKeyRepository.find(created.id())).accessPolicy.deniedModelIds)
            }
            val newKey = client.post("/admin/api/client-keys") {
                authenticate(auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"new-key","accessPolicy":$original}""")
            }
            assertEquals(HttpStatusCode.Created, newKey.status, newKey.bodyAsText())
            val unknown = client.post("/admin/api/client-keys") {
                authenticate(auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"unknown-key","accessPolicy":${original.replace("[\"m1\"]", "[\"unknown\"]")}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, unknown.status, unknown.bodyAsText())
            createChannel("recreated-model", "ok")
            val denied = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${created["key"]!!.jsonPrimitive.content}")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1","messages":[]}""")
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status, denied.bodyAsText())
            assertEquals("model_access_denied", denied.headers["X-Prism-Error-Code"])
            assertTrue(fake.requests.isEmpty())
        }
    }

    /** 新增模型必须已有模型路由；已有选择在路由移除后仍允许保存其他修改。 */
    @Test
    fun `new model access requires a saved route while existing route choices remain editable`() = runBlocking {
        val routed = createChannel("route-required", "ok")
        app.channelRepository.create(
            name = "no-route",
            baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(NewChannelKey("no-route-key", app.credentialCipher.encrypt("no-route-secret"), models = listOf("no-route-model"))),
            authType = "bearer",
            protocols = GatewayProtocol.default,
            enabled = true,
            modelMappings = listOf(ChannelModelRecord("no-route-model")),
        )
        val keyId = routed.keys.single().id
        testApplication {
            application { app.install(this) }
            val auth = client.loginAdmin()
            val rejected = client.post("/admin/api/client-keys") {
                authenticate(auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"no-route","accessPolicy":${scoped(routed.id, keyId, "no-route-model")}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status, rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("没有已保存的模型路由"))

            val created = client.create(auth, scoped(routed.id, keyId, "m1"))
            java.sql.DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    assertEquals(1, statement.executeUpdate("delete from model_credential_routes where model_id = 'm1'"))
                }
            }
            val updated = client.put("/admin/api/client-keys/${created.id()}") {
                authenticate(auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"kept","accessPolicy":${scoped(routed.id, keyId, "m1")}}""")
            }.objectBody(HttpStatusCode.OK)
            assertEquals(Json.parseToJsonElement(scoped(routed.id, keyId, "m1")), updated["accessPolicy"])
        }
    }

    /** 生成独立预期的完整访问范围 JSON。 */
    private fun scoped(channelId: String, keyId: String, modelId: String): String =
        """{"channels":{"mode":"allowlist","ids":["$channelId"]},"channelKeys":{"mode":"allowlist","keys":[{"channelId":"$channelId","keyId":"$keyId"}]},"models":{"mode":"allowlist","ids":["$modelId"]}}"""

    /** 给管理请求附加当前会话和 CSRF 令牌。 */
    private fun io.ktor.client.request.HttpRequestBuilder.authenticate(auth: Pair<String, String>) {
        header(HttpHeaders.Cookie, auth.first)
        header("X-CSRF-Token", auth.second)
    }

    /** 创建调用方密钥并验证管理接口成功状态。 */
    private suspend fun HttpClient.create(auth: Pair<String, String>, policy: String? = null): JsonObject =
        post("/admin/api/client-keys") {
            authenticate(auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"access-test"${policy?.let { ",\"accessPolicy\":$it" }.orEmpty()}}""")
        }.objectBody(HttpStatusCode.Created)

    /** 解析响应，保留响应体以方便定位失败。 */
    private suspend fun HttpResponse.objectBody(expected: HttpStatusCode): JsonObject {
        val body = bodyAsText()
        assertEquals(expected, status, body)
        return Json.parseToJsonElement(body).jsonObject
    }

    /** 解析无需对象约束的响应 JSON。 */
    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText())

    /** 返回调用方密钥标识。 */
    private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content
}
