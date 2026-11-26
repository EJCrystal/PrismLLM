package dev.soven.prismllm.antigravity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.soven.prismllm.api.antigravityAcpRoutes
import dev.soven.prismllm.api.authenticateClientKey
import dev.soven.prismllm.config.AntigravityAcpConfig
import dev.soven.prismllm.proxy.RequestDrain
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.persistence.ClientKeyQuotaRepository
import dev.soven.prismllm.quota.persistence.ClientKeyRepository
import dev.soven.prismllm.quota.infra.ClientKeyQuotaService
import dev.soven.prismllm.quota.infra.RequestLimitService
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.toEnvelope
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger("ROOT")?.level = Level.OFF
    val result = try {
        require(args.size == 4 && args[0] == "--production-database")
        val script = Path.of(args[1])
        val state = Path.of(args[2])
        val model = args[3]
        val reports = state.resolve("reports")
        require(script.isAbsolute && state.isAbsolute && Files.isDirectory(reports, NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(reports) == PosixFilePermissions.fromString("rwx------"))
        val env = System.getenv()
        fun setting(name: String) = requireNotNull(env["PRISM_ACP_ACCEPTANCE_" + name]?.takeIf { it.isNotBlank() })
        val url = setting("DATABASE_URL")
        require(url.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/[A-Za-z0-9_-]+")))
        require(setting("DATABASE_CONFIRMED") == "true")
        val poolConfig = HikariConfig().apply {
            jdbcUrl = url
            username = setting("DATABASE_USER")
            password = setting("DATABASE_PASSWORD")
            maximumPoolSize = 2
            minimumIdle = 0
            connectionTimeout = 5_000
            validationTimeout = 3_000
            connectionInitSql = "SET statement_timeout = '3s'"
            addDataSourceProperty("ApplicationName", "prism-antigravity-acp-acceptance")
        }
        HikariDataSource(poolConfig).use { pool ->
            pool.connection.use { connection ->
                require(connection.metaData.databaseProductName == "PostgreSQL")
                require(connection.catalog == url.substringAfterLast('/'))
            }
            runBlocking {
                runAcpDatabaseAcceptance(Database.connect(pool), script, state, model, productionDatabase = true,
                    writeReport = { report ->
                        persistHttpAcceptanceReport(reports, report, "database-verification.json")
                        if (report["status"] != JsonPrimitive("DATABASE_ACCEPTANCE_RUNNING")) println(report)
                    }, onStage = { System.err.println("DATABASE_ACCEPTANCE_STAGE " + it) })
            }
        }
    } catch (_: Exception) {
        println("{\"status\":\"DATABASE_ACCEPTANCE_SETUP_FAILED\"}")
        1
    }
    exitProcess(result)
}

internal suspend fun runAcpDatabaseAcceptance(
    database: Database,
    script: Path,
    state: Path,
    model: String,
    productionDatabase: Boolean = false,
    pilotFactory: ((AntigravityAcpConfig) -> AntigravityAcpPilot)? = null,
    writeReport: (JsonObject) -> Unit,
    onStage: (String) -> Unit = {},
): Int {
    val keys = ClientKeyRepository(database)
    val ledger = ClientKeyQuotaRepository(database)
    val limits = RequestLimitService(ClientKeyQuotaService(ledger::usage,
        usageWriter = { _, _, _, _ -> error("ACP cannot settle unreported token or USD usage") }))
    val drain = RequestDrain()
    val ids = mutableListOf<String>()
    val checks = mutableListOf<String>()
    var stage = "DATABASE_PREFLIGHT"
    var status = "DATABASE_ACCEPTANCE_FAILED"
    var requests = 0
    var port = 0
    var stopServer: (() -> Unit)? = null
    var generated = ClientKeyHasher.generate()
    var generation = false
    var revoked = false
    var deleted = false
    var finalized = false
    fun progress(value: String) { stage = value; onStage(value) }
    fun receipt(value: String) = buildJsonObject {
        put("status", value)
        put("stage", stage)
        put("verifiedAt", Instant.now().toString())
        put("modelId", model)
        put("productionDatabaseUsed", productionDatabase)
        put("databaseBackedAuthentication", true)
        put("productionRoutesUntouched", true)
        put("migrationsRun", false)
        put("loopbackOnly", true)
        put("dedicatedRecordIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        put("authenticationChecks", buildJsonArray { checks.forEach { add(JsonPrimitive(it)) } })
        put("generationRequests", requests)
        put("realGenerationVerified", generation)
        put("dedicatedCallerKeyRevoked", revoked)
        put("dedicatedRecordsDeleted", deleted)
        put("concurrencyReleased", limits.clientKeyOccupancy().isEmpty())
        put("requestsFinalized", finalized)
        put("extraCredits", false)
        put("retries", 0)
        put("modelSwitches", 0)
        put("fallbacks", 0)
    }
    fun open(path: String = "/antigravity/v1/models", secret: String? = generated.raw, body: String? = null) =
        AcceptanceHttpReply(port, path, body, secret, 130_000)
    fun expect(name: String, expected: Int, secret: String? = generated.raw, path: String = "/antigravity/v1/models") {
        open(path, secret).use { check(it.status == expected) }
        checks += name
    }
    try {
        progress("DATABASE_PREFLIGHT")
        keys.findByHash("0".repeat(64))
        val name = "acp-acceptance-" + UUID.randomUUID()
        val policy = ClientKeyAccessPolicy(allowedModelIds = setOf(model))
        val quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 1, requestsPerMinute = 1)
        val expires = System.currentTimeMillis() + 600_000
        val primary = keys.create(name, generated.hash, generated.prefix, true, expires, quotas, policy)
        ids += primary.id
        writeReport(receipt("DATABASE_ACCEPTANCE_RUNNING"))
        val other = ClientKeyHasher.generate()
        ids += keys.create(name + "-denied", other.hash, other.prefix, true, expires, quotas, policy).id
        writeReport(receipt("DATABASE_ACCEPTANCE_RUNNING"))
        val config = AntigravityAcpConfig(script = script, stateDir = state, modelId = model,
            clientKeyIds = setOf(primary.id))
        val gateway = if (pilotFactory == null) AntigravityAcpGateway(config)
            else AntigravityAcpGateway(config, pilotFactory(config))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<GatewayError> { call, failure -> call.respond(failure.status, failure.toEnvelope()) }
            }
            routing { antigravityAcpRoutes(gateway, { authenticateClientKey(it, keys::findByHash) }, limits, drain, 262_144) }
        }
        stopServer = { server.stop(0, 3_000) }
        server.start(false)
        port = server.engine.resolvedConnectors().single().port
        progress("DATABASE_AUTHENTICATION")
        expect("ordinary_route_absent", 404, path = "/v1/models")
        expect("missing_key", 401, secret = null)
        expect("unknown_key", 401, secret = ClientKeyHasher.generate().raw)
        expect("non_allowlisted_database_key", 403, secret = other.raw)
        expect("valid_database_key", 200)
        suspend fun update(enabled: Boolean = true, expiry: Long = expires,
            access: ClientKeyAccessPolicy = policy, quota: ClientKeyQuotaLimits = quotas) {
            checkNotNull(keys.update(primary.id, name, enabled = enabled, expiresAt = expiry,
                accessPolicy = access, quotas = quota))
        }
        update(enabled = false)
        expect("disabled_database_key", 401)
        update(expiry = System.currentTimeMillis() - 1_000)
        expect("expired_database_key", 401)
        update(access = ClientKeyAccessPolicy(allowedModelIds = emptySet()))
        expect("database_model_policy_denied", 403)
        update(access = ClientKeyAccessPolicy(allowedChannelIds = emptySet()))
        expect("database_channel_policy_denied", 403)
        update(quota = ClientKeyQuotaLimits(dailyTokens = 1))
        expect("unaccountable_daily_quota_rejected", 403)
        update()
        val previous = generated.raw
        generated = ClientKeyHasher.generate()
        checkNotNull(keys.rotate(primary.id, generated.hash, generated.prefix))
        expect("rotated_old_key_rejected", 401, secret = previous)
        expect("rotated_new_key_accepted", 200)

        progress("DATABASE_AUTHENTICATED_GENERATION")
        requests = 1
        writeReport(receipt("DATABASE_ACCEPTANCE_RUNNING"))
        val marker = "PRISM-" + UUID.randomUUID()
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "Reply with exactly $marker and nothing else. Do not use any tools.") })
            })
            put("stream", false)
        }.toString()
        open("/antigravity/v1/chat/completions", body = body).use { response ->
            response.requireSuccess()
            check(response.headers["x-should-retry"] == "false")
            val result = Json.parseToJsonElement(response.body()).jsonObject
            check(result["model"] == JsonPrimitive(model) && "usage" !in result)
            val choice = result.getValue("choices").jsonArray.single().jsonObject
            check(choice["finish_reason"] == JsonPrimitive("stop"))
            check((choice.getValue("message").jsonObject["content"] as? JsonPrimitive)?.content?.trim() == marker)
        }
        generation = true
        progress("DATABASE_REVOCATION")
        checkNotNull(keys.revoke(primary.id))
        expect("revoked_database_key", 401)
        revoked = true
        status = "REAL_DATABASE_ACP_ACCEPTANCE_PASSED"
    } catch (failure: HttpAcceptanceStopped) {
        status = failure.code
    } catch (_: Exception) {
        status = "DATABASE_ACCEPTANCE_FAILED"
    } finally {
        withContext(NonCancellable) {
            ids.forEach { runCatching { keys.revoke(it) } }
            drain.beginShutdown()
            drain.cancelRemaining()
            finalized = drain.awaitFinalized(12_000)
            runCatching { stopServer?.invoke() }.onFailure { finalized = false }
            val results = ids.map { id -> runCatching { keys.delete(id); keys.find(id) == null }.getOrDefault(false) }
            deleted = ids.size == 2 && results.all { it }
        }
    }
    val listenerStopped = port != 0 && runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
    }.isFailure
    if (status == "REAL_DATABASE_ACP_ACCEPTANCE_PASSED" &&
        (!deleted || !finalized || !listenerStopped || limits.clientKeyOccupancy().isNotEmpty())) status = "DATABASE_CLEANUP_NOT_VERIFIED"
    writeReport(JsonObject(receipt(status) + ("httpListenerStopped" to JsonPrimitive(listenerStopped))))
    return if (status == "REAL_DATABASE_ACP_ACCEPTANCE_PASSED") 0 else 1
}
