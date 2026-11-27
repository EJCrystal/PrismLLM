package dev.soven.prismllm.api

import dev.soven.prismllm.antigravity.AntigravityAcpGateway
import dev.soven.prismllm.proxy.RequestDrain
import dev.soven.prismllm.proxy.trackResponse
import dev.soven.prismllm.quota.domain.ClientKeyRecord
import dev.soven.prismllm.quota.infra.RequestLimitService
import dev.soven.prismllm.shared.error.GatewayError
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.serviceUnavailable
import dev.soven.prismllm.shared.error.toEnvelope
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import io.netty.channel.ChannelFutureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal fun Route.antigravityAcpRoutes(app: GatewayApplication) {
    val gateway = app.antigravityAcp ?: return
    antigravityAcpRoutes(gateway, { authenticate(it, app) }, app.requestLimitService,
        app.requestDrain, app.config.requestBodyLimitBytes)
}

internal fun Route.antigravityAcpRoutes(
    gateway: AntigravityAcpGateway,
    authenticateKey: suspend (ApplicationCall) -> ClientKeyRecord,
    limits: RequestLimitService,
    drain: RequestDrain,
    requestBodyLimitBytes: Long,
) {
    get("/antigravity/v1/models") {
        gateway.authorize(authenticateKey(call))
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(buildJsonObject {
            put("object", "list")
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("id", gateway.modelId)
                    put("object", "model")
                    put("created", 0)
                    put("owned_by", "google")
                })
            })
        })
    }
    post("/antigravity/v1/chat/completions") {
        call.response.headers.append("x-should-retry", "false")
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        coroutineScope {
            val requestJob = checkNotNull(currentCoroutineContext()[Job])
            val lease = drain.acquire(requestJob)
                ?: throw serviceUnavailable("gateway_shutting_down", "网关正在停止，请稍后重试")
            lease.use {
                lease.trackResponse(call)
                call.cancelOnClientDisconnect(requestJob).use {
                    val key = authenticateKey(call)
                    gateway.authorize(key)
                    val request = parseAcpChatRequest(call.receiveLimitedJson(minOf(requestBodyLimitBytes, 262_144)), gateway.modelId)
                    val quotaLease = limits.acquire(key)
                    try {
                        gateway.withAdmission {
                            if (request.stream) {
                                streamAcpChat(call, gateway, request)
                            } else {
                                val text = StringBuilder()
                                val finishReason = gateway.prompt(request.text) { text.append(it) }
                                call.respond(request.response(text.toString(), finishReason))
                            }
                        }
                    } finally {
                        limits.release(quotaLease)
                    }
                }
            }
        }
    }
}

internal data class AcpChatRequest(
    val model: String,
    val text: String,
    val stream: Boolean,
    val id: String = "chatcmpl-agy-" + UUID.randomUUID(),
    val created: Long = System.currentTimeMillis() / 1_000,
) {
    fun response(text: String, finishReason: String): JsonObject = envelope("chat.completion") {
        put("message", buildJsonObject {
            put("role", "assistant")
            put("content", text)
        })
        put("finish_reason", finishReason)
    }

    fun chunk(text: String? = null, role: Boolean = false, finishReason: String? = null): JsonObject =
        envelope("chat.completion.chunk") {
            put("delta", buildJsonObject {
                if (role) put("role", "assistant")
                if (text != null) put("content", text)
            })
            put("finish_reason", finishReason?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun envelope(kind: String, choice: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id)
        put("object", kind)
        put("created", created)
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject { put("index", 0); choice() })
        })
    }
}

internal fun parseAcpChatRequest(body: JsonObject, model: String): AcpChatRequest {
    if (body.keys.any { it !in setOf("model", "messages", "stream") }) {
        throw badRequest("antigravity_acp_unsupported_option", "ACP 首版仅支持 model、单条 user 纯文本 messages 和 stream")
    }
    if (body["model"] != JsonPrimitive(model)) {
        throw badRequest("antigravity_acp_fixed_model", "必须使用已验收的固定模型；不切换模型或回退")
    }
    val stream = body["stream"]?.let {
        (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
            ?: throw badRequest("invalid_stream", "stream 必须是布尔值")
    } ?: false
    val message = (body["messages"] as? JsonArray)?.singleOrNull() as? JsonObject
        ?: throw badRequest("antigravity_acp_unsupported_messages", "ACP 首版仅支持一条 user 纯文本消息")
    val text = (message["content"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    if (message.keys != setOf("role", "content") || message["role"] != JsonPrimitive("user") ||
        text == null || text.isBlank() || text.length > 32_768) {
        throw badRequest("antigravity_acp_unsupported_messages", "仅支持 1 至 32768 字符的 user 纯文本；不支持工具、附件或历史会话")
    }
    return AcpChatRequest(model, text, stream)
}

private suspend fun streamAcpChat(call: ApplicationCall, gateway: AntigravityAcpGateway, request: AcpChatRequest) {
    val completion = CompletableDeferred<Unit>()
    call.response.headers.append("X-Accel-Buffering", "no")
    try {
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val producerJob = checkNotNull(currentCoroutineContext()[Job])
            val cancellation = completion.invokeOnCompletion { if (completion.isCancelled) producerJob.cancel() }
            try {
                currentCoroutineContext().ensureActive()
                val writeLock = Mutex()
                suspend fun emit(value: String) = writeLock.withLock {
                    writeStringUtf8(value + "\n\n")
                    flush()
                }
                coroutineScope {
                    emit("data: " + request.chunk(role = true))
                    val heartbeat = launch {
                        while (isActive) {
                            delay(15_000)
                            emit(": keepalive")
                        }
                    }
                    try {
                        val reason = gateway.prompt(request.text) { emit("data: " + request.chunk(text = it)) }
                        emit("data: " + request.chunk(finishReason = reason))
                    } catch (failure: GatewayError) {
                        emit("data: " + Json.encodeToString(failure.toEnvelope()))
                    } finally {
                        heartbeat.cancel()
                    }
                    emit("data: [DONE]")
                }
                completion.complete(Unit)
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
                throw failure
            } finally {
                cancellation.dispose()
            }
        }
        completion.await()
    } finally {
        completion.cancel()
    }
}

internal fun ApplicationCall.cancelOnClientDisconnect(job: Job): AutoCloseable {
    val response = response as? BaseApplicationResponse
        ?: attributes.getOrNull(BaseApplicationResponse.EngineResponseAttributeKey)
    val engineCall = response?.call as? NettyApplicationCall ?: return AutoCloseable {}
    val future = engineCall.context.channel().closeFuture()
    val listener = ChannelFutureListener { job.cancel(CancellationException("ACP client disconnected")) }
    future.addListener(listener)
    return AutoCloseable { future.removeListener(listener) }
}
