package dev.soven.prismllm.antigravity

import dev.soven.prismllm.proxy.codeassist.CodeAssistSuspension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal enum class AcpStopReason(val wireValue: String) {
    END_TURN("end_turn"),
    MAX_TOKENS("max_tokens"),
    MAX_TURN_REQUESTS("max_turn_requests"),
    REFUSAL("refusal"),
    CANCELLED("cancelled"),
}

internal data class AcpPilotCatalog(
    val modelIds: List<String>,
    val currentModelId: String?,
    val officialSubscriptionTier: String? = null,
)

private data class AcpPilotSession(
    val id: String,
    val catalog: AcpPilotCatalog,
    val modelOptionId: String?,
)

internal class AcpPilotException(val kind: Kind, val rpcCode: Int? = null) : RuntimeException(kind.description) {
    enum class Kind(val description: String) {
        BUSY("An ACP pilot turn is already running"),
        START_FAILED("Could not start the ACP agent"),
        TIMED_OUT("The ACP pilot turn timed out"),
        TRANSPORT_CLOSED("The ACP agent closed the transport"),
        PROTOCOL_ERROR("Invalid or unsupported ACP message"),
        AGENT_ERROR("The ACP agent returned an error"),
        SERVICE_DISABLED("Google disabled this account's service for a terms-of-service violation"),
        QUOTA_EXHAUSTED("The ACP agent reported exhausted quota or a rate limit"),
        MODEL_UNAVAILABLE("The selected model is not available in the ACP session"),
        MODEL_MISMATCH("The ACP session is not using the configured model; model switching is disabled"),
    }
}

internal class AntigravityAcpPilot(
    command: List<String>,
    private val workspace: Path,
    environment: Map<String, String> = emptyMap(),
    private val timeoutMs: Long = 30_000,
    private val cancelGraceMs: Long = 500,
    private val agentWorkspace: Path = workspace,
    private val allowModelSelection: Boolean = true,
) {
    private val command = command.toList()
    private val environment = environment.toMap()
    private val active = Mutex()

    init {
        require(command.isNotEmpty() && Path.of(command.first()).isAbsolute) { "ACP executable must be absolute" }
        require(workspace.isAbsolute) { "ACP workspace must be absolute" }
        require(agentWorkspace.isAbsolute) { "ACP agent workspace must be absolute" }
        require(timeoutMs > 0 && cancelGraceMs > 0)
    }

    suspend fun inspect(): AcpPilotCatalog = withConnection { connection -> createSession(connection).catalog }

    suspend fun prompt(
        text: String,
        modelId: String? = null,
        onCancelled: (Boolean) -> Unit = {},
        onText: suspend (String) -> Unit,
    ): AcpStopReason {
        require(text.isNotBlank() && text.length <= MAX_PROMPT_CHARS) { "Invalid ACP pilot prompt length" }
        return withConnection { connection -> runTurn(connection, text, modelId, onCancelled, onText) }
    }

    private suspend fun <Result : Any> withConnection(block: suspend (AcpStdio) -> Result): Result {
        currentCoroutineContext().ensureActive()
        if (!active.tryLock()) throw AcpPilotException(AcpPilotException.Kind.BUSY)
        try {
            val connection = AcpStdio(startAgent())
            try {
                return withTimeoutOrNull(timeoutMs) { block(connection) }
                    ?: throw AcpPilotException(AcpPilotException.Kind.TIMED_OUT)
            } finally {
                connection.close()
            }
        } finally {
            active.unlock()
        }
    }

    private fun startAgent(): Process = try {
        ProcessBuilder(command)
            .directory(workspace.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment().clear()
                environment().putAll(this@AntigravityAcpPilot.environment)
            }
            .start()
    } catch (_: IOException) {
        throw AcpPilotException(AcpPilotException.Kind.START_FAILED)
    }

    private suspend fun createSession(connection: AcpStdio): AcpPilotSession {
        connection.send(request(INITIALIZE_ID, "initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("clientCapabilities", buildJsonObject {
                put("fs", buildJsonObject {
                    put("readTextFile", false)
                    put("writeTextFile", false)
                })
                put("terminal", false)
                put("auth", buildJsonObject { put("terminal", false) })
            })
        }))
        if (connection.reply(INITIALIZE_ID)["protocolVersion"] != JsonPrimitive(PROTOCOL_VERSION)) invalidMessage()

        connection.send(request(NEW_SESSION_ID, "session/new", buildJsonObject {
            put("cwd", agentWorkspace.toString())
            put("mcpServers", buildJsonArray {})
        }))
        val response = connection.reply(NEW_SESSION_ID)
        val sessionId = response.string("sessionId")?.takeIf(String::isNotBlank)
            ?: invalidMessage()
        val option = (response["configOptions"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.string("category") == "model" && it.string("type") == "select" }
        val models = response["models"] as? JsonObject
        val ids = if (option != null) {
            modelValues(option["options"])
        } else {
            (models?.get("availableModels") as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.mapNotNull { it.string("modelId") }.orEmpty()
        }.filter { it.matches(MODEL_ID_PATTERN) }.distinct()
        val current = (option?.string("currentValue") ?: models?.string("currentModelId"))?.takeIf { it in ids }
        val optionId = if (option == null) null else option.string("id")?.takeIf(String::isNotBlank) ?: invalidMessage()
        val metadata = response["_meta"] as? JsonObject
        val antigravity = metadata?.get("antigravity") as? JsonObject
        val tier = antigravity?.get("tier") as? JsonObject
        val tierName = (tier?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val officialTier = tierName?.let {
            Regex("""(?i)^(?:Google +(AI|One) +)?(Pro|Ultra|Plus|AI Premium|Free)$""")
                .matchEntire(it)?.groupValues?.get(2)?.uppercase()?.replace(' ', '_')
        }
        return AcpPilotSession(sessionId, AcpPilotCatalog(ids, current, officialTier), optionId)
    }

    private suspend fun runTurn(
        connection: AcpStdio,
        text: String,
        modelId: String?,
        onCancelled: (Boolean) -> Unit,
        onText: suspend (String) -> Unit,
    ): AcpStopReason {
        val session = createSession(connection)
        val sessionId = session.id
        if (!allowModelSelection && (modelId == null || session.catalog.currentModelId != modelId)) {
            throw AcpPilotException(AcpPilotException.Kind.MODEL_MISMATCH)
        }
        if (modelId != null) {
            if (modelId !in session.catalog.modelIds) throw AcpPilotException(AcpPilotException.Kind.MODEL_UNAVAILABLE)
            if (modelId != session.catalog.currentModelId) {
                val method = if (session.modelOptionId == null) "session/set_model" else "session/set_config_option"
                connection.send(request(SELECT_MODEL_ID, method, buildJsonObject {
                    put("sessionId", sessionId)
                    if (session.modelOptionId == null) {
                        put("modelId", modelId)
                    } else {
                        put("configId", session.modelOptionId)
                        put("value", modelId)
                    }
                }))
                val selected = connection.reply(SELECT_MODEL_ID)
                if (session.modelOptionId != null) {
                    val option = (selected["configOptions"] as? JsonArray)?.filterIsInstance<JsonObject>()
                        ?.firstOrNull { it.string("id") == session.modelOptionId }
                    if (option?.string("currentValue") != modelId) invalidMessage()
                }
            }
        }
        val params = buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        }
        try {
            connection.send(request(PROMPT_ID, "session/prompt", params))
            val reason = connection.reply(PROMPT_ID, sessionId,
                fixedModelId = modelId.takeUnless { allowModelSelection }, onText = onText).string("stopReason")
            return AcpStopReason.entries.firstOrNull { it.wireValue == reason } ?: invalidMessage()
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                val acknowledged = withTimeoutOrNull(cancelGraceMs) {
                    try {
                        connection.send(message {
                            put("method", "session/cancel")
                            put("params", buildJsonObject { put("sessionId", sessionId) })
                        })
                        connection.reply(PROMPT_ID, sessionId, cancelling = true).string("stopReason") == "cancelled"
                    } catch (_: Exception) {
                        false
                    }
                }
                onCancelled(acknowledged == true)
            }
            throw failure
        }
    }

    private suspend fun AcpStdio.reply(
        requestId: Int,
        sessionId: String? = null,
        cancelling: Boolean = false,
        fixedModelId: String? = null,
        onText: suspend (String) -> Unit = {},
    ): JsonObject {
        while (true) {
            currentCoroutineContext().ensureActive()
            val frame = receive()
            if (frame.string("jsonrpc") != JSON_RPC_VERSION) invalidMessage()
            val id = frame["id"]
            if ("method" !in frame) {
                if (id != JsonPrimitive(requestId) || ("result" in frame) == ("error" in frame)) invalidMessage()
                if ("error" in frame) {
                    val code = ((frame["error"] as? JsonObject)?.get("code") as? JsonPrimitive)
                        ?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: invalidMessage()
                    val kind = acpServiceFailure(frame.getValue("error").toString()) ?: AcpPilotException.Kind.AGENT_ERROR
                    throw AcpPilotException(kind, code)
                }
                return frame["result"] as? JsonObject ?: invalidMessage()
            }

            val method = frame.string("method") ?: invalidMessage()
            val params = frame["params"] as? JsonObject
            val isCurrentSession = sessionId != null && params?.string("sessionId") == sessionId
            if (id != null) {
                if (id !is JsonPrimitive || (!id.isString && id.longOrNull == null)) invalidMessage()
                send(message {
                    put("id", id)
                    if (method == "session/request_permission") {
                        val option = if (isCurrentSession && !cancelling) rejectionOption(params) else null
                        put("result", buildJsonObject {
                            put("outcome", buildJsonObject {
                                put("outcome", if (option == null) "cancelled" else "selected")
                                if (option != null) put("optionId", option)
                            })
                        })
                    } else {
                        put("error", buildJsonObject {
                            put("code", -32601)
                            put("message", "Method not supported by the ACP pilot")
                        })
                    }
                })
            } else if (method == "session/update" && isCurrentSession && !cancelling) {
                val update = params?.get("update") as? JsonObject
                if (fixedModelId != null) {
                    val changed = when (update?.string("sessionUpdate")) {
                        "current_model_update" -> update.string("currentModelId") != fixedModelId
                        "config_option_update" -> (update["configOptions"] as? JsonArray)
                            ?.filterIsInstance<JsonObject>()?.any {
                                (it.string("category") == "model" || it.string("id") == "model") &&
                                    it.string("currentValue") != fixedModelId
                            } == true
                        else -> false
                    }
                    if (changed) throw AcpPilotException(AcpPilotException.Kind.MODEL_MISMATCH)
                }
                if (update?.string("sessionUpdate") == "agent_message_chunk" || update?.string("status") == "failed") {
                    acpServiceFailure(update.toString())?.let { throw AcpPilotException(it) }
                }
                val content = update?.get("content") as? JsonObject
                if (update?.string("sessionUpdate") == "agent_message_chunk" && content?.string("type") == "text") {
                    currentCoroutineContext().ensureActive()
                    content.string("text")?.let { onText(it) }
                }
            }
        }
    }

    private fun rejectionOption(params: JsonObject?): String? {
        val options = (params?.get("options") as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
        val reject = options.firstOrNull { it.string("kind") == "reject_once" }?.string("optionId") ?: return null
        return reject.takeIf { it.isNotBlank() && options.count { option -> option.string("optionId") == reject } == 1 }
    }
}

internal fun acpServiceFailure(text: String): AcpPilotException.Kind? = when {
    CodeAssistSuspension.isSuspended(text) -> AcpPilotException.Kind.SERVICE_DISABLED
    listOf("RESOURCE_EXHAUSTED", "quota exhausted", "quota exceeded", "exhausted your quota", "exceeded your quota",
        "quota has been exhausted", "rate limit exceeded").any { text.contains(it, ignoreCase = true) } ->
        AcpPilotException.Kind.QUOTA_EXHAUSTED
    else -> null
}

private fun modelValues(options: kotlinx.serialization.json.JsonElement?): List<String> =
    (options as? JsonArray)?.filterIsInstance<JsonObject>()?.flatMap { option ->
        option.string("value")?.let(::listOf) ?: (option["options"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.string("value") }.orEmpty()
    }.orEmpty()

private class AcpStdio(private val process: Process) {
    private val incoming = Channel<JsonObject>(1)
    private val outgoing = Channel<JsonObject>()
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        workers.launch {
            try {
                InputStreamReader(process.inputStream, Charsets.UTF_8.newDecoder()).buffered().use { input ->
                    while (true) incoming.send(input.readFrame())
                }
            } catch (failure: AcpPilotException) {
                incoming.close(failure)
            } catch (_: IOException) {
                incoming.close(transportClosed())
            }
        }
        workers.launch {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { output ->
                    for (frame in outgoing) {
                        output.write(frame.toString())
                        output.write("\n")
                        output.flush()
                    }
                }
            } catch (_: IOException) {
                val failure = transportClosed()
                outgoing.close(failure)
                incoming.close(failure)
            }
        }
    }

    suspend fun send(frame: JsonObject) = outgoing.send(frame)

    suspend fun receive(): JsonObject = incoming.receive()

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        try {
            outgoing.close()
            if (!process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(PROCESS_TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.toHandle().destroyForcibly()
                    process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            workers.cancel()
            incoming.cancel()
            outgoing.cancel()
        }
    }
}

private fun BufferedReader.readFrame(): JsonObject {
    val raw = buildString {
        while (true) {
            val next = read()
            if (next < 0) throw transportClosed()
            if (next == '\n'.code) break
            if (length >= MAX_FRAME_CHARS) invalidMessage()
            append(next.toChar())
        }
    }
    return try {
        Json.parseToJsonElement(raw) as? JsonObject ?: invalidMessage()
    } catch (_: IllegalArgumentException) {
        invalidMessage()
    }
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)?.content

private fun message(body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("jsonrpc", JSON_RPC_VERSION)
    body()
}

private fun request(id: Int, method: String, params: JsonObject): JsonObject = message {
    put("id", id)
    put("method", method)
    put("params", params)
}

private fun invalidMessage(): Nothing = throw AcpPilotException(AcpPilotException.Kind.PROTOCOL_ERROR)

private fun transportClosed() = AcpPilotException(AcpPilotException.Kind.TRANSPORT_CLOSED)

private const val JSON_RPC_VERSION = "2.0"
private const val PROTOCOL_VERSION = 1
private const val INITIALIZE_ID = 1
private const val NEW_SESSION_ID = 2
private const val PROMPT_ID = 3
private const val SELECT_MODEL_ID = 4
private const val MAX_PROMPT_CHARS = 32_768
private const val MAX_FRAME_CHARS = 1_048_576
private const val PROCESS_EXIT_GRACE_MS = 1_000L
private const val PROCESS_TERMINATE_GRACE_MS = 2_000L
private val MODEL_ID_PATTERN = Regex("[A-Za-z0-9._:/-]{1,160}")
