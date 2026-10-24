package dev.soven.llmgateway.fake

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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
    val cancellations = AtomicInteger()

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
                call.respondText(MODELS_BODY, ContentType.Application.Json)
            }
            get("/v1/models") {
                call.respondText(MODELS_BODY, ContentType.Application.Json)
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
        }
    }

    private suspend fun handle(call: ApplicationCall, scenario: String) {
        val body = call.receiveText()
        requests.add(
            FakeRequest(
                scenario = scenario,
                body = body,
                authorization = call.request.headers[HttpHeaders.Authorization],
                traceparent = call.request.headers["traceparent"],
            ),
        )
        val stream = runCatching {
            Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        }.getOrDefault(false)

        when (scenario) {
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
        }
        if (!stream) {
            call.respondText(NON_STREAM_BODY, ContentType.Application.Json)
            return
        }

        var completed = false
        try {
            if (scenario == "slow") {
                delay(2_000)
            }
            call.respondBytesWriter(ContentType.Text.EventStream) {
                when (scenario) {
                    "disconnect" -> {
                        writeEvent("""{"id":"fake","choices":[{"delta":{"content":"partial"}}]}""")
                        delay(50)
                        throw IOException("fake disconnect")
                    }
                    "malformed" -> {
                        writeEvent("{not-json}")
                        writeDone()
                    }
                    "no-usage" -> {
                        writeEvent(CHUNK_HEL)
                        writeEvent(CHUNK_LO)
                        writeEvent(FINAL_NO_USAGE)
                        writeDone()
                    }
                    "slow-stream" -> {
                        writeEvent(CHUNK_HEL)
                        delay(500)
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
            ),
        )
        val stream = runCatching {
            Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        }.getOrDefault(false)

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
        private const val ERROR_429 =
            """{"error":{"message":"rate limited","type":"rate_limit_error","code":"rate_limit_exceeded"}}"""
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
)
