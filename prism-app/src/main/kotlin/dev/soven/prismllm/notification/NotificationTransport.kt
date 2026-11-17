package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun interface NotificationTransport {
    suspend fun send(target: NotificationTarget, event: NotificationEvent): NotificationSendResult
}

data class NotificationSendResult(
    val success: Boolean,
    val retryable: Boolean,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val retryAfterMs: Long? = null,
)

/** Database credentials, isolated connections and stable error codes; never logs endpoints. */
class SafeNotificationTransport internal constructor(
    private val credentials: NotificationCredentials,
    private val resolver: NotificationDnsResolver,
    private val httpExecutor: NotificationHttpExecutor,
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
) : NotificationTransport, AutoCloseable {
    private val lifecycleLock = Any()
    private val activeSends = mutableSetOf<Job>()
    private var closed = false

    constructor(credentials: NotificationCredentials) : this(
        credentials,
        SystemNotificationDnsResolver,
        OkHttpNotificationExecutor(),
    )

    override suspend fun send(target: NotificationTarget, event: NotificationEvent): NotificationSendResult = coroutineScope {
        val job = coroutineContext.job
        synchronized(lifecycleLock) {
            if (closed) return@coroutineScope permanent("transport_closed")
            activeSends += job
        }
        try {
            withTimeoutOrNull(10_000L) { sendOnce(target, event) } ?: retryable("timeout")
        } finally {
            synchronized(lifecycleLock) { activeSends -= job }
        }
    }

    private suspend fun sendOnce(target: NotificationTarget, event: NotificationEvent): NotificationSendResult {
        if (event.eventId.isEmpty() || event.eventId.length > 256 || event.eventId.any { it.code !in 0x21..0x7e }) {
            return permanent("invalid_payload")
        }
        val environment = try {
            credentials.resolve(target)
        } catch (_: NotificationCredentialUnreadable) {
            return permanent("credential_unreadable")
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // 数据库或连接异常是临时的；交给交付重试，避免一次读取失败就让事件永久失败。
            return retryable("credential_read_failed")
        }
        environment.bearerToken?.let { token ->
            if (token.any { it.code !in 0x21..0x7e }) return permanent("invalid_configuration")
        }
        val configuredUrl = environment.url ?: return permanent("missing_configuration")
        if (target.type != "webhook" && target.type != "feishu") return permanent("unsupported_target_type")
        val url = try {
            NotificationUrlPolicy.parse(configuredUrl)
        } catch (_: NotificationUrlRejected) {
            return permanent("unsafe_url")
        }
        val dns = try {
            val answers = withTimeoutOrNull(3_000L) { resolveNotificationDns(resolver, url.host) }
                ?: return retryable("timeout")
            NotificationUrlPolicy.resolve(url, NotificationDnsResolver { answers })
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: NotificationUrlRejected) {
            return permanent("unsafe_url")
        } catch (_: Exception) {
            return retryable("dns_failure")
        }

        val now = epochSeconds()
        val payload = try {
            if (target.type == "feishu") feishuPayload(event, now, environment.signingSecret)
            else EVENT_JSON.encodeToString(event).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            return permanent("invalid_payload")
        }
        if (payload.size > 64 * 1024) return permanent("payload_too_large")
        val headers = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "X-Prism-Event-Id" to event.eventId,
        )
        environment.bearerToken?.let { headers["Authorization"] = "Bearer $it" }
        if (target.type == "webhook") environment.signingSecret?.let { secret ->
            headers["X-Prism-Timestamp"] = now.toString()
            headers["X-Prism-Signature"] = webhookSignature(secret, now, payload.toString(Charsets.UTF_8))
        }

        val response = try {
            httpExecutor.post(url, dns, headers, payload)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: InterruptedIOException) {
            return retryable("timeout")
        } catch (_: Exception) {
            return retryable("network_error")
        }
        val status = response.statusCode
        if (status == 429 || status in 500..599) {
            return NotificationSendResult(
                false, true, status,
                if (status == 429) "rate_limited" else "upstream_unavailable",
                retryAfter(response.retryAfter),
            )
        }
        if (status !in 200..299) return NotificationSendResult(false, false, status, "http_rejected")
        if (response.bodyTooLarge || response.body.size > 8 * 1024) {
            return NotificationSendResult(false, false, status, "response_too_large")
        }
        if (target.type == "feishu" && !feishuAccepted(response.body)) {
            return NotificationSendResult(false, false, status, "feishu_rejected")
        }
        return NotificationSendResult(true, false, status)
    }

    override fun close() {
        val jobs = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeSends.toList()
        }
        jobs.forEach { it.cancel(CancellationException("transport_closed")) }
        (httpExecutor as? AutoCloseable)?.close()
    }

    private fun feishuPayload(event: NotificationEvent, timestamp: Long, secret: String?): ByteArray {
        val text = buildString {
            appendLine("PrismLLM 事件通知")
            appendLine("事件: ${label(event.eventType)}")
            appendLine("事件 ID: ${event.eventId}")
            appendLine("级别: ${label(event.severity)}")
            appendLine("时间: ${Instant.ofEpochMilli(event.occurredAt)}")
            (event.channelName ?: event.channelId)?.let { appendLine("渠道: ${display(it)}") }
            (event.credentialName ?: event.credentialId)?.let { appendLine("凭据: ${display(it)}") }
            event.upstreamModel?.let { appendLine("模型: ${display(it)}") }
            event.previousState?.let { appendLine("先前状态: ${label(it)}") }
            event.state?.let { appendLine("当前状态: ${label(it)}") }
            event.reasonCode?.let { appendLine("原因: ${label(it)}") }
            event.consecutiveFailures?.let { appendLine("连续失败: $it") }
        }.trimEnd()
        return buildJsonObject {
            put("msg_type", "text")
            put("content", buildJsonObject { put("text", text) })
            if (secret != null) {
                put("timestamp", timestamp.toString())
                put("sign", feishuSignature(secret, timestamp))
            }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun feishuAccepted(body: ByteArray): Boolean = try {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
        if (!boundedJsonDepth(text)) false else {
            val code = STRICT_JSON.parseToJsonElement(text).jsonObject["code"] as? JsonPrimitive
            code != null && !code.isString && code.content == "0"
        }
    } catch (_: Exception) {
        false
    }

    /** Prevent deeply nested untrusted input from overflowing the JSON parser's stack. */
    private fun boundedJsonDepth(text: String): Boolean {
        var quoted = false
        var escaped = false
        var depth = 0
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 16) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }

    private fun display(value: String): String =
        value.replace(Regex("[\\r\\n\\u0000-\\u001f\\u007f]+"), " ").take(240)

    private fun label(value: String): String = LABELS[value.lowercase()] ?: display(value)

    private fun retryAfter(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.all { it in '0'..'9' }) {
            val seconds = raw.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: Long.MAX_VALUE
            return seconds.coerceAtMost(60L) * 1_000L
        }
        return runCatching {
            val date = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(Instant.ofEpochSecond(epochSeconds()), date).toMillis().coerceIn(0L, 60_000L)
        }.getOrNull()
    }

    private fun permanent(code: String) = NotificationSendResult(false, false, errorCode = code)
    private fun retryable(code: String) = NotificationSendResult(false, true, errorCode = code)

    companion object {
        private val EVENT_JSON = Json { encodeDefaults = true; explicitNulls = false }
        private val STRICT_JSON = Json { isLenient = false }
        private val LABELS = mapOf(
            "channel.unhealthy" to "渠道异常", "channel.recovered" to "渠道已恢复",
            "key.disabled" to "凭据已禁用", "key.recovered" to "凭据已恢复",
            "attempt.consecutive_failures" to "连续请求失败", "attempt.recovered" to "请求已恢复",
            "notification.test" to "测试通知", "info" to "提示", "warning" to "警告",
            "critical" to "严重", "error" to "错误", "healthy" to "健康", "degraded" to "性能下降",
            "unhealthy" to "异常", "unavailable" to "不可用", "failing" to "失败中",
            "active" to "可用", "disabled" to "已禁用", "auto_disabled" to "已自动禁用",
            "manual_disabled" to "已手动禁用", "auto_temporary" to "临时自动禁用",
            "auto_permanent" to "永久自动禁用", "manual_disable" to "手动禁用",
            "manual_recovery" to "手动恢复", "auto_temporary_disable" to "自动临时禁用",
            "auto_permanent_disable" to "自动永久禁用", "probe_success" to "探测成功",
            "expired" to "已过期", "failure_rate" to "失败率过高", "slow_first_token" to "首个响应令牌过慢",
            "cooldown" to "冷却中", "no_credentials" to "没有可用凭据", "unknown" to "未知",
            "consecutive_failures" to "连续失败达到阈值", "successful_attempt" to "请求成功",
            "upstream_timeout" to "上游请求超时", "health_recovered" to "健康状态已恢复",
            "health_degraded" to "健康状态下降", "admin_test" to "管理员发送测试",
            "rate_limited" to "上游限流", "invalid_api_key" to "凭据无效",
        )
        // JVM DNS may ignore interruption. Bound both the abandoned workers and pending queue.
        private val DNS_EXECUTOR = ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
            { task -> Thread(task, "notification-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

        private suspend fun resolveNotificationDns(
            resolver: NotificationDnsResolver,
            host: String,
        ): List<InetAddress> = suspendCancellableCoroutine { continuation ->
            val task = object : FutureTask<List<InetAddress>>(Callable { resolver.resolve(host) }) {
                override fun done() {
                    if (!continuation.isActive) return
                    val result = try {
                        Result.success(get())
                    } catch (failure: ExecutionException) {
                        Result.failure(failure.cause ?: failure)
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                    continuation.resumeWith(result)
                }
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                DNS_EXECUTOR.remove(task)
            }
            try {
                if (continuation.isActive) DNS_EXECUTOR.execute(task)
            } catch (failure: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(failure))
            }
        }

        internal fun webhookSignature(secret: String, timestamp: Long, body: String): String =
            "sha256=${HexFormat.of().formatHex(hmac(secret, "$timestamp.$body"))}"

        internal fun feishuSignature(secret: String, timestamp: Long): String =
            Base64.getEncoder().encodeToString(hmac("$timestamp\n$secret", ""))

        private fun hmac(secret: String, value: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(value.toByteArray(Charsets.UTF_8))
        }
    }
}

internal object SystemNotificationDnsResolver : NotificationDnsResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

internal data class NotificationHttpResponse(
    val statusCode: Int,
    val retryAfter: String?,
    val body: ByteArray,
    val bodyTooLarge: Boolean,
)

internal fun interface NotificationHttpExecutor {
    suspend fun post(url: HttpUrl, pinnedDns: Dns, headers: Map<String, String>, body: ByteArray): NotificationHttpResponse
}

/** Isolated direct clients prevent route reuse; the injected call factory keeps unit tests offline. */
internal class OkHttpNotificationExecutor(
    private val newCall: (OkHttpClient, Request) -> Call = { client, request -> client.newCall(request) },
) : NotificationHttpExecutor, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activeLock = Any()
    private val activeCalls = LinkedHashMap<Call, OkHttpClient>()

    override suspend fun post(
        url: HttpUrl,
        pinnedDns: Dns,
        headers: Map<String, String>,
        body: ByteArray,
    ): NotificationHttpResponse {
        if (closed.get()) throw IOException("transport_closed")
        val client = OkHttpClient.Builder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .build()
        val call = try {
            val request = Request.Builder().url(url).post(OneShotJsonBody(body)).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            newCall(client, request)
        } catch (failure: Exception) {
            dispose(client)
            throw failure
        }
        synchronized(activeLock) {
            if (closed.get()) {
                call.cancel()
                dispose(client)
                throw IOException("transport_closed")
            }
            activeCalls[call] = client
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cleanup(call, client)
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome = try {
                            response.use {
                                val bounded = readBounded(response.body?.byteStream())
                                Result.success(
                                    NotificationHttpResponse(
                                        response.code, response.header("Retry-After"), bounded.first, bounded.second,
                                    ),
                                )
                            }
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        } finally {
                            cleanup(call, client)
                        }
                        continuation.resumeWith(outcome)
                    }
                })
            } catch (failure: Exception) {
                cleanup(call, client)
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val calls = synchronized(activeLock) { activeCalls.toMap() }
        calls.forEach { (call, client) ->
            call.cancel()
            client.dispatcher.cancelAll()
            dispose(client)
        }
    }

    private fun cleanup(call: Call, client: OkHttpClient) {
        synchronized(activeLock) { activeCalls.remove(call) }
        dispose(client)
    }

    private fun dispose(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    // retryOnConnectionFailure alone does not disable OkHttp's 503/408 response follow-ups.
    private class OneShotJsonBody(private val bytes: ByteArray) : RequestBody() {
        override fun contentType() = JSON_MEDIA_TYPE
        override fun contentLength(): Long = bytes.size.toLong()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
    }

    private fun readBounded(input: java.io.InputStream?): Pair<ByteArray, Boolean> {
        if (input == null) return ByteArray(0) to false
        input.use {
            val output = ByteArrayOutputStream(MAX_RESPONSE_BYTES)
            val chunk = ByteArray(1_024)
            while (output.size() <= MAX_RESPONSE_BYTES) {
                val count = input.read(chunk, 0, minOf(chunk.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(chunk, 0, count)
            }
            val value = output.toByteArray()
            return if (value.size > MAX_RESPONSE_BYTES) value.copyOf(MAX_RESPONSE_BYTES) to true else value to false
        }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 8 * 1024
    }
}
