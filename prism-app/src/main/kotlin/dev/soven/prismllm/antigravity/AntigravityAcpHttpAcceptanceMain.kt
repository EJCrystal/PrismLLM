package dev.soven.prismllm.antigravity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import dev.soven.prismllm.api.antigravityAcpRoutes
import dev.soven.prismllm.config.AntigravityAcpConfig
import dev.soven.prismllm.proxy.RequestDrain
import dev.soven.prismllm.quota.domain.ClientKeyAccessPolicy
import dev.soven.prismllm.quota.domain.ClientKeyQuotaLimits
import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.infra.ClientKeyQuotaService
import dev.soven.prismllm.quota.infra.RequestLimitService
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.toEnvelope
import dev.soven.prismllm.shared.error.unauthorized
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    (LoggerFactory.getILoggerFactory() as? LoggerContext)?.getLogger("ROOT")?.level = Level.OFF
    val result = try {
        require(args.size == 3)
        val config = AntigravityAcpConfig(
            script = Path.of(args[0]), stateDir = Path.of(args[1]), modelId = args[2],
            clientKeyIds = setOf(UUID.randomUUID().toString()),
        )
        val reports = config.stateDir.resolve("reports")
        require(Files.isDirectory(reports, NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(reports) == PosixFilePermissions.fromString("rwx------"))
        persistHttpAcceptanceReport(reports, buildJsonObject {
            put("status", "HTTP_ACCEPTANCE_RUNNING")
            put("startedAt", Instant.now().toString())
            put("modelId", config.modelId)
        })
        runBlocking {
            runAcpHttpAcceptance(config, writeReport = { report ->
                persistHttpAcceptanceReport(reports, report)
                println(report)
            }, onStage = { System.err.println("HTTP_ACCEPTANCE_STAGE " + it) })
        }
    } catch (_: Exception) {
        println("{\"status\":\"HTTP_ACCEPTANCE_SETUP_FAILED\"}")
        1
    }
    exitProcess(result)
}

internal fun persistHttpAcceptanceReport(reports: Path, report: JsonObject, filename: String = "http-verification.json") {
    require(filename.matches(Regex("[a-z0-9-]+\\.json")))
    val temporary = Files.createTempFile(reports, ".http-verification-", ".json",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
        Files.writeString(temporary, report.toString() + "\n")
        Files.move(temporary, reports.resolve(filename), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal suspend fun runAcpHttpAcceptance(
    config: AntigravityAcpConfig,
    pilot: AntigravityAcpPilot = AntigravityAcpPilot(
        command = config.command(), workspace = config.stateDir, agentWorkspace = Path.of("/workspace"),
        timeoutMs = config.timeoutMs, cancelGraceMs = 3_000, allowModelSelection = false,
    ),
    writeReport: (JsonObject) -> Unit,
    onStage: (String) -> Unit = {},
): Int {
    require(config.clientKeyIds.size == 1)
    val generated = ClientKeyHasher.generate()
    val key = ClientKeyRecord(
        id = config.clientKeyIds.single(), name = "isolated-antigravity-http-acceptance",
        keyHash = generated.hash, keyPrefix = generated.prefix, enabled = true,
        expiresAt = System.currentTimeMillis() + 600_000,
        quotas = ClientKeyQuotaLimits(maxConcurrentRequests = 1, requestsPerMinute = 3),
        createdAt = System.currentTimeMillis(),
        accessPolicy = ClientKeyAccessPolicy(allowedModelIds = setOf(config.modelId)),
    )
    val activeKey = AtomicBoolean(true)
    val cancelled = CompletableDeferred<Boolean>()
    val gateway = AntigravityAcpGateway(config, pilot, onCancelled = { cancelled.complete(it) })
    val limits = RequestLimitService(ClientKeyQuotaService(
        usageReader = { _, _ -> ClientKeyQuotaUsage() },
        usageWriter = { _, _, _, _ -> error("ACP acceptance must not invent token or USD usage") },
    ))
    val drain = RequestDrain()
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<GatewayError> { call, failure -> call.respond(failure.status, failure.toEnvelope()) }
        }
        routing {
            antigravityAcpRoutes(gateway, { call ->
                val bearer = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }
                    ?.removePrefix("Bearer ").orEmpty()
                if (!activeKey.get() || !key.enabled || key.revokedAt != null ||
                    checkNotNull(key.expiresAt) <= System.currentTimeMillis() ||
                    !MessageDigest.isEqual(ClientKeyHasher.hash(bearer).toByteArray(), key.keyHash.toByteArray())) {
                    throw unauthorized()
                }
                key
            }, limits, drain, 262_144)
        }
    }
    var stage = "HTTP_STARTUP"
    var status = "HTTP_ACCEPTANCE_FAILED"
    var port = 0
    var attempts = 0
    var isolated = false
    var authenticated = false
    var nonstream = false
    var streaming = false
    var streamChunks = 0
    var cancellation = false
    var reused = false
    var retired = false
    var drained = false
    val previousChildren = ProcessHandle.current().descendants().use { children -> children.map { it.pid() }.toList().toSet() }
    fun progress(value: String) { stage = value; onStage(value) }
    fun open(path: String, body: String? = null, authorized: Boolean = true) = AcceptanceHttpReply(
        port, path, body, if (authorized) generated.raw else null, config.timeoutMs.toInt() + 10_000,
    )
    fun chat(text: String, stream: Boolean) = buildJsonObject {
        put("model", config.modelId)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", text) })
        })
        put("stream", stream)
    }.toString()
    suspend fun awaitReleased() = withTimeout(12_000) {
        while (limits.clientKeyOccupancy().isNotEmpty()) delay(20)
    }
    try {
        server.start(false)
        port = server.engine.resolvedConnectors().single().port
        progress("HTTP_ISOLATION")
        open("/v1/models").use { check(it.status == 404) }
        isolated = true
        open("/antigravity/v1/models", authorized = false).use { check(it.status == 401) }
        open("/antigravity/v1/models").use { response ->
            response.requireSuccess()
            val models = Json.parseToJsonElement(response.body()).jsonObject.getValue("data").jsonArray
            check(models.single().jsonObject["id"] == JsonPrimitive(config.modelId))
        }
        authenticated = true

        progress("NONSTREAMING")
        val nonstreamMarker = "PRISM-" + UUID.randomUUID()
        attempts += 1
        open("/antigravity/v1/chat/completions", chat("Reply with exactly $nonstreamMarker and nothing else. Do not use any tools.", false)).use { response ->
            response.requireSuccess()
            check(response.headers["x-should-retry"] == "false")
            val result = Json.parseToJsonElement(response.body()).jsonObject
            check(result["object"] == JsonPrimitive("chat.completion") && "usage" !in result)
            check(result["model"] == JsonPrimitive(config.modelId))
            val choice = result.getValue("choices").jsonArray.single().jsonObject
            check(choice["finish_reason"] == JsonPrimitive("stop"))
            check((choice.getValue("message").jsonObject["content"] as? JsonPrimitive)?.content?.trim() == nonstreamMarker)
        }
        awaitReleased()
        nonstream = true

        progress("STREAMING")
        val streamMarker = "PRISM-" + UUID.randomUUID()
        attempts += 1
        open("/antigravity/v1/chat/completions", chat("Reply with exactly $streamMarker and nothing else. Do not use any tools.", true)).use { response ->
            response.requireSuccess()
            check(response.headers["content-type"].orEmpty().startsWith("text/event-stream"))
            check(response.headers["x-should-retry"] == "false")
            val text = StringBuilder()
            var finished = false
            response.events { event ->
                check(event["object"] == JsonPrimitive("chat.completion.chunk") && "usage" !in event)
                check(event["model"] == JsonPrimitive(config.modelId))
                val choice = event.getValue("choices").jsonArray.single().jsonObject
                (choice.getValue("delta").jsonObject["content"] as? JsonPrimitive)?.content?.let {
                    check(!finished)
                    text.append(it)
                    streamChunks += 1
                }
                if (choice["finish_reason"] == JsonPrimitive("stop")) finished = true
                false
            }
            check(finished && streamChunks > 0 && text.toString().trim() == streamMarker)
        }
        awaitReleased()
        streaming = true

        progress("CANCELLATION")
        attempts += 1
        open("/antigravity/v1/chat/completions", chat("Print the numbers 1 to 200, one per line. Do not use any tools.", true)).use { response ->
            response.requireSuccess()
            check(response.headers["content-type"].orEmpty().startsWith("text/event-stream"))
            response.events { event ->
                val choice = event.getValue("choices").jsonArray.single().jsonObject
                val text = (choice.getValue("delta").jsonObject["content"] as? JsonPrimitive)?.content.orEmpty()
                text.isNotEmpty()
            }
            response.abort()
        }
        if (!withTimeout(10_000) { cancelled.await() }) throw HttpAcceptanceStopped("CANCELLATION_NOT_ACKNOWLEDGED")
        cancellation = true
        awaitReleased()

        progress("LOGIN_REUSE_AFTER_CANCEL")
        val catalog = pilot.inspect()
        check(catalog.currentModelId == config.modelId && config.modelId in catalog.modelIds)
        reused = true
        status = "REAL_HTTP_ACP_ACCEPTANCE_PASSED"
    } catch (failure: HttpAcceptanceStopped) {
        status = failure.code
    } catch (failure: AcpPilotException) {
        status = failure.kind.name
    } catch (_: TimeoutCancellationException) {
        status = "TIMED_OUT"
    } catch (_: Exception) {
        status = "HTTP_ACCEPTANCE_FAILED"
    } finally {
        activeKey.set(false)
        if (port != 0) retired = runCatching {
            open("/antigravity/v1/models").use { it.status == 401 }
        }.getOrDefault(false)
        drain.beginShutdown()
        drain.cancelRemaining()
        drained = drain.awaitFinalized(12_000)
        server.stop(0, 3_000)
    }
    val agentsStopped = ProcessHandle.current().descendants().use { children ->
        children.noneMatch { it.isAlive && it.pid() !in previousChildren }
    }
    val listenerStopped = port != 0 && runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
    }.isFailure
    val released = limits.clientKeyOccupancy().isEmpty() && drained && agentsStopped && listenerStopped
    if (status == "REAL_HTTP_ACP_ACCEPTANCE_PASSED" && (!retired || !released)) status = "CLEANUP_NOT_VERIFIED"
    writeReport(buildJsonObject {
        put("status", status)
        put("stage", stage)
        put("verifiedAt", Instant.now().toString())
        put("modelId", config.modelId)
        put("generationRequests", attempts)
        put("loopbackOnly", true)
        put("productionRoutesUntouched", true)
        put("productionDatabaseUsed", false)
        put("ordinaryRoutesAbsent", isolated)
        put("dedicatedCallerKeyVerified", authenticated)
        put("nonStreamingVerified", nonstream)
        put("streamingVerified", streaming)
        put("streamTextChunks", streamChunks)
        put("cancelAfterTextVerified", cancellation)
        put("cancelAcknowledged", cancellation)
        put("credentialsReusedAcrossHttpRequests", nonstream && streaming)
        put("credentialsReusedAfterCancel", reused)
        put("dedicatedCallerKeyRetired", retired)
        put("concurrencyReleased", limits.clientKeyOccupancy().isEmpty())
        put("agentProcessesStopped", agentsStopped)
        put("httpListenerStopped", listenerStopped)
        put("extraCredits", false)
        put("retries", 0)
        put("modelSwitches", 0)
        put("fallbacks", 0)
        put("subscriptionEntitlementVerified", false)
        put("refreshAfterExpiryVerified", false)
    })
    return if (status == "REAL_HTTP_ACP_ACCEPTANCE_PASSED") 0 else 1
}

internal class HttpAcceptanceStopped(val code: String) : RuntimeException()

internal class AcceptanceHttpReply(
    port: Int, path: String, body: String?, secret: String?, timeoutMs: Int,
) : AutoCloseable {
    private val socket = Socket()
    val headers: Map<String, String>
    val status: Int
    private val input: InputStream

    init {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            socket.soTimeout = timeoutMs
            val bytes = body.orEmpty().toByteArray(Charsets.UTF_8)
            val head = buildString {
                append(if (body == null) "GET " else "POST ")
                append(path + " HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                if (secret != null) append("Authorization: Bearer $secret\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: " + bytes.size + "\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            val wire = BufferedInputStream(socket.getInputStream())
            status = wire.httpLine().split(' ')[1].toInt()
            val values = mutableMapOf<String, String>()
            var count = 0
            while (true) {
                val line = wire.httpLine()
                if (line.isEmpty()) break
                check(++count <= 64 && ':' in line)
                values[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            headers = values
            input = if (values["transfer-encoding"] == "chunked") AcceptanceChunkedInput(wire) else wire
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }

    fun requireSuccess() {
        if (status != 200) {
            val error = runCatching { Json.parseToJsonElement(body()).jsonObject.getValue("error").jsonObject }.getOrNull()
            stop(error)
        }
    }

    fun body(): String {
        val bytes = input.readNBytes(65_537)
        check(bytes.size <= 65_536)
        return bytes.toString(Charsets.UTF_8)
    }

    fun events(consume: (JsonObject) -> Boolean) {
        var characters = 0
        while (true) {
            val line = input.httpLine()
            characters += line.length
            check(characters <= 65_536)
            if (!line.startsWith("data: ")) continue
            val value = line.removePrefix("data: ")
            if (value == "[DONE]") return
            val event = Json.parseToJsonElement(value).jsonObject
            if ("error" in event) stop(event.getValue("error").jsonObject)
            if (consume(event)) return
        }
    }

    private fun stop(error: JsonObject?): Nothing {
        val code = (error?.get("code") as? JsonPrimitive)?.content
        throw HttpAcceptanceStopped(code?.takeIf { it.matches(Regex("antigravity_acp_[a-z_]+")) } ?: "HTTP_STATUS_" + status)
    }

    fun abort() { socket.setSoLinger(true, 0); socket.close() }
    override fun close() = socket.close()
}

private fun InputStream.httpLine(): String {
    val result = StringBuilder()
    while (result.length < 16_384) {
        val next = read()
        check(next >= 0)
        if (next == 10) return result.toString().removeSuffix("\r")
        result.append(next.toChar())
    }
    error("HTTP line limit exceeded")
}

private class AcceptanceChunkedInput(private val wire: InputStream) : InputStream() {
    private var remaining = 0
    private var started = false
    private var ended = false

    override fun read(): Int {
        if (ended) return -1
        if (remaining == 0) {
            if (started) check(wire.httpLine().isEmpty())
            remaining = wire.httpLine().substringBefore(';').toInt(16)
            check(remaining in 0..1_048_576)
            started = true
            if (remaining == 0) { ended = true; return -1 }
        }
        val next = wire.read()
        check(next >= 0)
        remaining -= 1
        return next
    }
}
