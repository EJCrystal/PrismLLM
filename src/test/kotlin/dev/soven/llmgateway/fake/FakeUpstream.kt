package dev.soven.llmgateway.fake

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容的假上游，用路径选择故障场景，供集成测试与本地联调使用。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class FakeUpstream {
    private val port = freePort()
    private val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
        fakeModule()
    }

    val baseUrl: String get() = "http://127.0.0.1:$port"
    val requests = ConcurrentLinkedQueue<FakeRequest>()
    val modelRequests = ConcurrentLinkedQueue<FakeRequest>()
    val cancellations = AtomicInteger()
    val failingModels = ConcurrentHashMap.newKeySet<String>()
    val keyStatuses = ConcurrentHashMap<String, Int>()
    val keyErrorBodies = ConcurrentHashMap<String, String>()
    private val chatHold = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun holdChatRequests(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also(chatHold::set)

    fun clearChatHold() {
        chatHold.set(null)
    }

    fun start() {
        server.start(wait = false)
        awaitReady()
    }

    fun stop() {
        server.stop(0, 1_000)
    }

    private fun Application.fakeModule() {
        routing {
            get("/{scenario}/v1/models") {
                recordModelRequest(call, call.parameters["scenario"] ?: "ok")
                call.respondText(MODELS_BODY, ContentType.Application.Json)
            }
            get("/v1/models") {
                recordModelRequest(call, "ok")
                call.respondText(MODELS_BODY, ContentType.Application.Json)
            }
            get("/{scenario}/v1beta/models") {
                recordModelRequest(call, call.parameters["scenario"] ?: "ok")
                call.respondText(GEMINI_MODELS_BODY, ContentType.Application.Json)
            }
            get("/v1beta/models") {
                recordModelRequest(call, "ok")
                call.respondText(GEMINI_MODELS_BODY, ContentType.Application.Json)
            }
            post("/{scenario}/v1/chat/completions") {
                handle(call, call.parameters["scenario"] ?: "ok")
            }
            post("/v1/chat/completions") {
                handle(call, "ok")
            }
            post("/{scenario}/v1/responses") {
                handleResponses(call, call.parameters["scenario"] ?: "ok")
            }
            post("/v1/responses") {
                handleResponses(call, "ok")
            }
            post("/{scenario}/v1/messages") {
                handleMessages(call, call.parameters["scenario"] ?: "ok")
            }
            post("/v1/messages") {
                handleMessages(call, "ok")
            }
            listOf("/v1", "/v1beta", "/{scenario}/v1", "/{scenario}/v1beta").forEach { prefix ->
                post("$prefix/models/{modelAction...}") {
                    handleGemini(call, call.parameters["scenario"] ?: "ok")
                }
            }
        }
    }

    private fun recordModelRequest(call: ApplicationCall, scenario: String) {
        modelRequests.add(FakeRequest(
            scenario, "", call.request.headers[HttpHeaders.Authorization],
            requestHeaders = call.request.headers.entries().associate { it.key.lowercase() to it.value },
            uri = call.request.uri,
            path = call.request.path(),
        ))
    }

    private suspend fun respondKeyHealth(call: ApplicationCall, scenario: String): Boolean {
        if (scenario != "key-health") return false
        val credential = call.request.headers[HttpHeaders.Authorization] ?: call.request.headers["x-api-key"]
            ?: call.request.headers["x-goog-api-key"]
        val status = credential?.let(keyStatuses::get) ?: 200
        if (status < 400) {
            chatHold.get()?.await()
            return false
        }
        val errorBody = credential?.let(keyErrorBodies::get)
            ?: """{"error":{"message":"fake key failure","code":"fake_key_failure"}}"""
        call.respondText(
            errorBody,
            ContentType.Application.Json, HttpStatusCode.fromValue(status),
        )
        return true
    }

    private suspend fun handle(call: ApplicationCall, scenario: String) {
        val body = call.receiveText()
        requests.add(
            FakeRequest(
                scenario = scenario,
                body = body,
                authorization = call.request.headers[HttpHeaders.Authorization],
                traceparent = call.request.headers["traceparent"],
                requestHeaders = call.request.headers.entries().associate { it.key.lowercase() to it.value },
                uri = call.request.uri,
                path = call.request.path(),
            ),
        )
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val stream = json?.get("stream")?.jsonPrimitive?.booleanOrNull ?: false
        val model = json?.get("model")?.jsonPrimitive?.content
        if (respondKeyHealth(call, scenario)) return

        when (scenario) {
            "key-route" -> {
                if (call.request.headers[HttpHeaders.Authorization] == "Bearer key-primary") {
                    call.respondText(ERROR_401, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return
                }
            }
            "429-retry-after" -> {
                call.response.header(HttpHeaders.RetryAfter, "1")
                call.respondText(ERROR_429, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return
            }
            "429" -> {
                call.respondText(ERROR_429, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return
            }
            "500" -> {
                call.respondText(ERROR_500, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return
            }
            "pseudo-400" -> {
                call.respondText(
                    """{"error":{"message":"模型请求失败，请稍后重试或更换模型。","type":"upstream_error","param":"","code":null}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "standard-400" -> {
                call.respondText(
                    """{"error":{"message":"Invalid parameter: max_tokens must be positive","type":"invalid_request_error","param":"max_tokens","code":null}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "pseudo-400-blank-code" -> {
                call.respondText(
                    """{"error":{"code":"","type":"upstream_error","message":"provider failure"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "standard-400-with-upstream-error-message" -> {
                call.respondText(
                    """{"error":{"type":"invalid_request_error","message":"Invalid value: upstream error"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "gemini-400-invalid-argument" -> {
                call.respondText(
                    """{"error":{"code":400,"message":"Invalid argument: upstream error","status":"INVALID_ARGUMENT"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "structured-400-no-message-debug-keyword" -> {
                call.respondText(
                    """{"error":{"code":"invalid_parameter"},"debug":"upstream error"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "unstructured-400-pseudo" -> {
                call.respondText(
                    "模型请求失败，请稍后重试或更换模型。",
                    ContentType.Text.Plain,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "model-route" -> {
                if (model != null && model in failingModels) {
                    call.respondText(ERROR_429, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                    return
                }
            }
            "model-route-500" -> {
                if (model != null && model in failingModels) {
                    call.respondText(ERROR_500, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    return
                }
            }
            "hold-500" -> {
                chatHold.get()?.await()
                call.respondText(ERROR_500, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return
            }
        }
        if (!stream) {
            call.respondText(
                if (scenario == "reported-model") REPORTED_MODEL_NON_STREAM_BODY else NON_STREAM_BODY,
                ContentType.Application.Json,
            )
            return
        }
        if (scenario == "concurrency-hold") {
            chatHold.get()?.await()
        }

        var completed = false
        try {
            if (scenario == "slow") {
                delay(2_000)
            }
            call.respondBytesWriter(ContentType.Text.EventStream) {
                when (scenario) {
                    "oversized-line-before-content", "oversized-line-after-content" -> {
                        if (scenario == "oversized-line-after-content") writeEvent(CHUNK_HEL)
                        writeEvent(oversizedPayload())
                        writeEvent(FINAL_WITH_USAGE)
                        writeDone()
                    }
                    "disconnect" -> {
                        writeEvent("""{"id":"fake","choices":[{"delta":{"content":"partial"}}]}""")
                        delay(50)
                        throw IOException("fake disconnect")
                    }
                    "malformed" -> {
                        writeEvent("{not-json}")
                        writeDone()
                    }
                    "malformed-after-content" -> {
                        writeEvent(CHUNK_HEL)
                        writeEvent("{not-json}")
                        writeEvent(FINAL_WITH_USAGE)
                        writeDone()
                    }
                    "malformed-at-eof" -> {
                        writeEvent(CHUNK_HEL)
                        writeFully("data: {not-json}".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    "no-usage" -> {
                        writeEvent(CHUNK_HEL)
                        writeEvent(CHUNK_LO)
                        writeEvent(FINAL_NO_USAGE)
                        writeDone()
                    }
                    "reported-model", "reported-model-malformed" -> {
                        writeEvent(REPORTED_MODEL_CHUNK)
                        if (scenario == "reported-model-malformed") {
                            writeEvent("{not-json}")
                            writeEvent("[]")
                        }
                        writeEvent(CHUNK_LO)
                        writeEvent(FINAL_WITH_USAGE)
                        writeDone()
                    }
                    else -> {
                        writeEvent(CHUNK_HEL)
                        delay(10)
                        writeEvent(CHUNK_LO)
                        delay(10)
                        writeEvent(FINAL_WITH_USAGE)
                        writeDone()
                    }
                }
                completed = true
            }
        } finally {
            if (!completed) cancellations.incrementAndGet()
        }
    }

    private suspend fun handleResponses(call: ApplicationCall, scenario: String) {
        val body = call.receiveText()
        requests.add(
            FakeRequest(
                scenario = scenario,
                body = body,
                authorization = call.request.headers[HttpHeaders.Authorization],
                traceparent = call.request.headers["traceparent"],
                requestHeaders = call.request.headers.entries().associate { it.key.lowercase() to it.value },
                uri = call.request.uri,
                path = call.request.path(),
            ),
        )
        val stream = runCatching {
            Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        }.getOrDefault(false)
        if (respondKeyHealth(call, scenario)) return

        when (scenario) {
            "chat-only" -> {
                call.respondText(ERROR_404, ContentType.Application.Json, HttpStatusCode.NotFound)
                return
            }
            "429" -> {
                call.respondText(ERROR_429, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return
            }
            "500" -> {
                call.respondText(ERROR_500, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return
            }
            "pseudo-400" -> {
                call.respondText(
                    """{"error":{"message":"模型请求失败，请稍后重试或更换模型。","type":"upstream_error","param":"","code":null}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "standard-400" -> {
                call.respondText(
                    """{"error":{"message":"Invalid parameter: max_tokens must be positive","type":"invalid_request_error","param":"max_tokens","code":null}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "pseudo-400-blank-code" -> {
                call.respondText(
                    """{"error":{"code":"","type":"upstream_error","message":"provider failure"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "standard-400-with-upstream-error-message" -> {
                call.respondText(
                    """{"error":{"type":"invalid_request_error","message":"Invalid value: upstream error"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "gemini-400-invalid-argument" -> {
                call.respondText(
                    """{"error":{"code":400,"message":"Invalid argument: upstream error","status":"INVALID_ARGUMENT"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "structured-400-no-message-debug-keyword" -> {
                call.respondText(
                    """{"error":{"code":"invalid_parameter"},"debug":"upstream error"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }
            "unstructured-400-pseudo" -> {
                call.respondText(
                    "模型请求失败，请稍后重试或更换模型。",
                    ContentType.Text.Plain,
                    HttpStatusCode.BadRequest,
                )
                return
            }
        }
        if (!stream) {
            call.respondText(RESPONSES_NON_STREAM_BODY, ContentType.Application.Json)
            return
        }

        var completed = false
        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeSseEvent(
                    "response.created",
                    """{"type":"response.created","response":{"id":"resp_fake","status":"in_progress"}}""",
                )
                if (scenario == "oversized-line-before-content") {
                    writeSseEvent("response.completed", oversizedPayload())
                    completed = true
                    return@respondBytesWriter
                }
                if (scenario == "malformed") {
                    writeSseEvent("response.output_text.delta", "{not-json}")
                    writeSseEvent("response.completed", RESPONSES_FINAL_EVENT)
                    completed = true
                    return@respondBytesWriter
                }
                if (scenario == "responses-created-stall") {
                    delay(2_000)
                    completed = true
                    return@respondBytesWriter
                }
                if (scenario == "responses-function-call") {
                    writeSseEvent(
                        "response.function_call_arguments.delta",
                        """{"type":"response.function_call_arguments.delta","delta":"{\"city\":\"Paris\"}"}""",
                    )
                    delay(500)
                    writeSseEvent(
                        "response.function_call_arguments.done",
                        """{"type":"response.function_call_arguments.done","arguments":"{\"city\":\"Paris\"}"}""",
                    )
                    writeSseEvent("response.completed", RESPONSES_FINAL_EVENT)
                    completed = true
                    return@respondBytesWriter
                }
                delay(10)
                writeSseEvent(
                    "response.output_text.delta",
                    """{"type":"response.output_text.delta","delta":"Hel"}""",
                )
                delay(10)
                writeSseEvent(
                    "response.output_text.delta",
                    """{"type":"response.output_text.delta","delta":"lo"}""",
                )
                delay(10)
                when (scenario) {
                    "malformed-at-eof" -> {
                        writeFully("data: {not-json}".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    "malformed-after-content" -> {
                        writeSseEvent("response.output_text.delta", "{not-json}")
                        writeSseEvent("response.completed", RESPONSES_FINAL_EVENT)
                    }
                    "oversized-line-after-content" -> writeSseEvent("response.completed", oversizedPayload())
                    "responses-failed" -> writeSseEvent(
                        "response.failed",
                        """{"type":"response.failed","response":{"id":"resp_fake","status":"failed","error":{"code":"server_error","message":"upstream failed"}}}""",
                    )
                    "responses-incomplete" -> writeSseEvent(
                        "response.incomplete",
                        """{"type":"response.incomplete","response":{"id":"resp_fake","status":"incomplete"}}""",
                    )
                    "no-usage" -> writeSseEvent(
                        "response.completed",
                        """{"type":"response.completed","response":{"id":"resp_fake","status":"completed"}}""",
                    )
                    else -> writeSseEvent("response.completed", RESPONSES_FINAL_EVENT)
                }
                completed = true
            }
        } finally {
            if (!completed) cancellations.incrementAndGet()
        }
    }

    private fun oversizedPayload(): String = buildJsonObject {
        put("payload", "private-upstream-payload-" + "x".repeat(1024 * 1024 + 32))
    }.toString()

    private suspend fun handleMessages(call: ApplicationCall, scenario: String) {
        val body = call.receiveText()
        requests.add(
            FakeRequest(
                scenario = scenario,
                body = body,
                authorization = call.request.headers[HttpHeaders.Authorization],
                traceparent = call.request.headers["traceparent"],
                path = call.request.path(),
                xApiKey = call.request.headers["x-api-key"],
                anthropicVersion = call.request.headers["anthropic-version"],
                anthropicBeta = call.request.headers["anthropic-beta"],
                requestHeaders = call.request.headers.entries().associate { it.key.lowercase() to it.value },
                uri = call.request.uri,
            ),
        )
        val stream = runCatching {
            Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        }.getOrDefault(false)
        if (respondKeyHealth(call, scenario)) return

        when (scenario) {
            "429" -> {
                call.respondText(ANTHROPIC_ERROR_429, ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                return
            }
            "500" -> {
                call.respondText(ANTHROPIC_ERROR_500, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return
            }
            "unsupported-response" -> {
                call.respondText(
                    """{"id":"msg_fake","type":"message","role":"assistant","content":[{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{}}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
                    ContentType.Application.Json,
                )
                return
            }
        }
        if (!stream) {
            call.respondText(ANTHROPIC_NON_STREAM_BODY, ContentType.Application.Json)
            return
        }

        if (scenario == "sse-error" || scenario == "sse-rate-limit") {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeSseEvent("error", if (scenario == "sse-rate-limit") ANTHROPIC_ERROR_429 else ANTHROPIC_STREAM_ERROR)
            }
            return
        }
        if (scenario == "empty-message") {
            var completed = false
            try {
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    writeSseEvent("message_start", ANTHROPIC_MESSAGE_START)
                    writeSseEvent("message_delta", ANTHROPIC_MESSAGE_DELTA)
                    writeSseEvent("message_stop", ANTHROPIC_MESSAGE_STOP)
                    completed = true
                }
            } finally {
                if (!completed) cancellations.incrementAndGet()
            }
            return
        }

        var completed = false
        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeSseEvent("message_start", ANTHROPIC_MESSAGE_START)
                writeSseEvent("content_block_start", ANTHROPIC_CONTENT_BLOCK_START)
                writeSseEvent("content_block_delta", ANTHROPIC_CONTENT_BLOCK_DELTA)
                if (scenario == "sse-rate-limit-after-content") {
                    writeSseEvent("error", ANTHROPIC_ERROR_429)
                    completed = true
                    return@respondBytesWriter
                }
                writeSseEvent("content_block_stop", ANTHROPIC_CONTENT_BLOCK_STOP)
                writeSseEvent("message_delta", ANTHROPIC_MESSAGE_DELTA)
                writeSseEvent("message_stop", ANTHROPIC_MESSAGE_STOP)
                completed = true
            }
        } finally {
            if (!completed) cancellations.incrementAndGet()
        }
    }

    private suspend fun handleGemini(call: ApplicationCall, scenario: String) {
        val body = call.receiveText()
        requests.add(FakeRequest(
            scenario, body, call.request.headers[HttpHeaders.Authorization],
            traceparent = call.request.headers["traceparent"],
            requestHeaders = call.request.headers.entries().associate { it.key.lowercase() to it.value },
            uri = call.request.uri,
            path = call.request.path(),
        ))
        if (scenario == "chat-only") {
            call.respondText(GEMINI_ERROR_404, ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        if (scenario == "429" || scenario == "500") {
            call.respondText(
                if (scenario == "429") GEMINI_ERROR_429 else GEMINI_ERROR_500, ContentType.Application.Json,
                if (scenario == "429") HttpStatusCode.TooManyRequests else HttpStatusCode.InternalServerError,
            )
            return
        }
        if (respondKeyHealth(call, scenario)) return
        if (!call.request.uri.contains(":streamGenerateContent")) {
            call.respondText(GEMINI_NON_STREAM_BODY, ContentType.Application.Json)
            return
        }
        if (scenario == "sse-error" || scenario == "sse-rate-limit") {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeEvent(if (scenario == "sse-rate-limit") GEMINI_ERROR_429 else GEMINI_ERROR_500)
            }
            return
        }
        var completed = false
        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeEvent("{\"usageMetadata\":{\"promptTokenCount\":10}}")
                if (scenario == "gemini-meta-stall") {
                    delay(2_000)
                    completed = true
                    return@respondBytesWriter
                }
                if (scenario != "empty-message") {
                    writeEvent("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello!\"}],\"role\":\"model\"}}]}")
                }
                if (scenario == "disconnect") throw IOException("fake Gemini disconnect")
                if (scenario == "sse-error-after-content" || scenario == "sse-rate-limit-after-content") {
                    writeEvent(if (scenario == "sse-rate-limit-after-content") GEMINI_ERROR_429 else GEMINI_ERROR_500)
                    completed = true
                    return@respondBytesWriter
                }
                if (scenario == "no-terminal") {
                    completed = true
                    return@respondBytesWriter
                }
                writeEvent("{\"candidates\":[{\"finishReason\":\"STOP\",\"index\":0}]}")
                delay(10)
                if (scenario != "no-usage") writeEvent(GEMINI_USAGE_CHUNK)
                completed = true
            }
        } finally {
            if (!completed) cancellations.incrementAndGet()
        }
    }

    private suspend fun ByteWriteChannel.writeSseEvent(event: String, json: String) {
        writeFully("event: $event\ndata: $json\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private suspend fun ByteWriteChannel.writeEvent(json: String) {
        writeFully("data: $json\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private suspend fun ByteWriteChannel.writeDone() {
        writeFully("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    private fun awaitReady() {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            runCatching { Socket("127.0.0.1", port).use { return } }
            Thread.sleep(50)
        }
        error("fake upstream did not start on port $port")
    }

    companion object {
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private const val CHUNK_HEL =
            """{"id":"fake","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
        private const val CHUNK_LO =
            """{"id":"fake","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}"""
        private const val FINAL_WITH_USAGE =
            """{"id":"fake","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""
        private const val FINAL_NO_USAGE =
            """{"id":"fake","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        private const val NON_STREAM_BODY =
            """{"id":"fake","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""
        private const val REPORTED_MODEL_NON_STREAM_BODY =
            """{"id":"fake","object":"chat.completion","model":"reported-by-upstream","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}"""
        private const val REPORTED_MODEL_CHUNK =
            """{"id":"fake","object":"chat.completion.chunk","model":"reported-by-upstream","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}"""
        private const val ERROR_429 =
            """{"error":{"message":"rate limited","type":"rate_limit_error","code":"rate_limit_exceeded"}}"""
        private const val ERROR_401 =
            """{"error":{"message":"invalid key","type":"authentication_error","code":"invalid_api_key"}}"""
        private const val ERROR_500 =
            """{"error":{"message":"upstream down","type":"api_error","code":"upstream_down"}}"""
        private const val ERROR_404 =
            """{"error":{"message":"not found","type":"not_found_error","code":"not_found"}}"""
        private const val MODELS_BODY =
            """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model","owned_by":"fake"},{"id":"deepseek-chat","object":"model","owned_by":"fake"}]}"""
        private const val RESPONSES_NON_STREAM_BODY =
            """{"id":"resp_fake","object":"response","status":"completed","model":"gpt-4o-mini","output":[],"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens":3,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":13}}"""
        private const val RESPONSES_FINAL_EVENT =
            """{"type":"response.completed","response":{"id":"resp_fake","status":"completed","usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":2},"output_tokens":3,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":13}}}"""
        private const val ANTHROPIC_NON_STREAM_BODY =
            """{"id":"msg_fake","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[{"type":"text","text":"Hello!"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":4}}"""
        private const val ANTHROPIC_MESSAGE_START =
            """{"type":"message_start","message":{"id":"msg_fake","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":1}}}"""
        private const val ANTHROPIC_CONTENT_BLOCK_START =
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        private const val ANTHROPIC_CONTENT_BLOCK_DELTA =
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello!"}}"""
        private const val ANTHROPIC_CONTENT_BLOCK_STOP =
            """{"type":"content_block_stop","index":0}"""
        private const val ANTHROPIC_MESSAGE_DELTA =
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}"""
        private const val ANTHROPIC_MESSAGE_STOP =
            """{"type":"message_stop"}"""
        private const val ANTHROPIC_ERROR_429 =
            """{"type":"error","error":{"type":"rate_limit_error","message":"rate limited"}}"""
        private const val ANTHROPIC_ERROR_500 =
            """{"type":"error","error":{"type":"api_error","message":"upstream down"}}"""
        private const val ANTHROPIC_STREAM_ERROR =
            """{"type":"error","error":{"type":"overloaded_error","message":"upstream overloaded"}}"""
        private const val GEMINI_MODELS_BODY =
            """{"models":[{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]}]}"""
        private const val GEMINI_NON_STREAM_BODY =
            """{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"thoughtsTokenCount":2,"cachedContentTokenCount":2,"totalTokenCount":15}}"""
        private const val GEMINI_USAGE_CHUNK =
            """{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"thoughtsTokenCount":2,"cachedContentTokenCount":2,"totalTokenCount":15}}"""
        private const val GEMINI_ERROR_404 =
            """{"error":{"code":404,"status":"NOT_FOUND","message":"not found"}}"""
        private const val GEMINI_ERROR_429 =
            """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"rate limited"}}"""
        private const val GEMINI_ERROR_500 =
            """{"error":{"code":500,"status":"INTERNAL","message":"upstream down"}}"""
    }
}

/**
 * 假上游收到的一次请求。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class FakeRequest(
    val scenario: String,
    val body: String,
    val authorization: String?,
    val traceparent: String? = null,
    val xApiKey: String? = null,
    val anthropicVersion: String? = null,
    val anthropicBeta: String? = null,
    val requestHeaders: Map<String, List<String>> = emptyMap(),
    val uri: String? = null,
    val path: String? = null,
)
