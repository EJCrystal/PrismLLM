package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.domain.OAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一次 refresh token 交换的结果。refresh token 轮换时 [refreshToken] 为新值。
 *
 * @author 文杰
 * @since 2026-11-06
 */
data class OAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresInSeconds: Long = 3_600,
    val tokenType: String = "Bearer",
    val scope: String? = null,
)

/**
 * 一次刷新失败。[terminal] 为 true 表示 refresh token 已失效，只能由管理员重新导入。
 * [errorCode] 只保存上游错误码或 `http_<status>`，不包含响应正文。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthRefreshException(
    val terminal: Boolean,
    val errorCode: String,
    message: String,
) : RuntimeException(message)

/**
 * OAuth refresh token 提供方。实现只负责一次刷新请求，不读写数据库，也不记录凭据。
 * 同一账号的刷新与上游调用必须使用账号绑定的同一个代理出口。
 *
 * @author 文杰
 * @since 2026-11-06
 */
interface OAuthTokenProvider {
    val provider: OAuthProvider

    suspend fun refresh(refreshToken: String, proxyUrl: String?): OAuthTokenResponse
}

/**
 * 已装配的 Provider 集合。未配置的 Provider（例如未提供 Antigravity client id）不会出现在这里。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthTokenProviderRegistry(providers: Collection<OAuthTokenProvider>) {
    private val byProvider = providers.associateBy { it.provider }

    fun find(provider: OAuthProvider): OAuthTokenProvider? = byProvider[provider]

    fun available(): Set<OAuthProvider> = byProvider.keys.toSet()
}

fun buildOAuthTokenProviders(config: OAuthConfig): List<OAuthTokenProvider> = buildList {
    add(ChatGptOAuthProvider(config.chatGpt, config.requestTimeoutMs, config.proxyAllowedHosts))
    config.antigravity?.let {
        add(AntigravityOAuthProvider(it, config.requestTimeoutMs, config.proxyAllowedHosts))
    }
}

private const val MAX_RESPONSE_BYTES = 256 * 1024
private val TERMINAL_ERROR_CODES = setOf("invalid_grant", "invalid_client", "unauthorized_client", "invalid_token")

/**
 * 发送一次 token 刷新请求。每次调用创建独立的 HttpClient：账号代理是请求级参数，
 * 不能污染共享客户端；刷新频率低，创建与销毁成本可以接受。
 */
internal suspend fun postTokenRequest(
    endpoint: String,
    contentType: String,
    body: String,
    proxyUrl: String?,
    timeoutMs: Long,
    headers: Map<String, String> = emptyMap(),
    proxyAllowedHosts: Set<String> = emptySet(),
): JsonObject {
    val proxy = proxyAddress(proxyUrl, proxyAllowedHosts)
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(minOf(timeoutMs, 10_000)))
        .followRedirects(HttpClient.Redirect.NEVER)
        .apply { proxy?.let { this.proxy(ProxySelector.of(it)) } }
        .build()
    try {
        val builder = HttpRequest.newBuilder(URI(endpoint))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
        for ((name, value) in headers) builder.header(name, value)
        val request = builder
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = sendBounded(client, request)
        val text = response.body().toString(StandardCharsets.UTF_8)
        if (response.statusCode() !in 200..299) throw httpFailure(response.statusCode(), text)
        return parseJsonObject(text)
    } catch (cause: OAuthRefreshException) {
        throw cause
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OAuthRefreshException(false, "interrupted", "OAuth 刷新被中断")
    } catch (cause: HttpTimeoutException) {
        throw OAuthRefreshException(false, "timeout", "OAuth 刷新超时")
    } catch (cause: Throwable) {
        if (cause is VirtualMachineError) throw cause
        throw OAuthRefreshException(false, "network_error", "无法连接 OAuth 服务")
    } finally {
        client.shutdownNow()
    }
}

/**
 * 账号代理只支持无认证的 HTTP 正向代理。Java HttpClient 不支持 SOCKS5，
 * 需要 SOCKS5 时应在网关外提供本地 HTTP 桥接。
 *
 * 代理目标默认只允许本机回环地址，其他主机必须由 [allowedHosts] 显式放行：
 * 管理面输入不应成为访问任意内网主机的跳板。回环地址始终允许，便于本地代理调试。
 */
internal fun proxyAddress(proxyUrl: String?, allowedHosts: Set<String> = emptySet()): InetSocketAddress? {
    val value = proxyUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(value) }.getOrElse {
        throw OAuthRefreshException(false, "proxy_invalid", "账号代理地址无效")
    }
    val valid = uri.scheme == "http" &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    if (!valid) {
        throw OAuthRefreshException(
            false,
            "proxy_invalid",
            "账号代理必须是 http://主机:端口，且不能包含账号密码、路径或查询参数",
        )
    }
    val port = if (uri.port == -1) 80 else uri.port
    if (port !in 1..65_535) throw OAuthRefreshException(false, "proxy_invalid", "账号代理端口无效")
    if (!isProxyHostAllowed(uri.host, allowedHosts)) {
        throw OAuthRefreshException(
            false,
            "proxy_not_allowed",
            "账号代理主机不在允许列表内；本机回环地址以外的代理需要配置 OAUTH_PROXY_ALLOWED_HOSTS",
        )
    }
    return InetSocketAddress(uri.host, port)
}

/** Java 的 URI 把 IPv6 字面量保留为 `[::1]`，比较前去掉方括号并统一小写。 */
internal fun normalizeProxyHost(host: String): String =
    host.trim().removePrefix("[").removeSuffix("]").lowercase()

internal fun isProxyHostAllowed(host: String?, allowedHosts: Set<String>): Boolean {
    val normalized = host?.takeIf { it.isNotBlank() }?.let(::normalizeProxyHost) ?: return false
    return normalized in LOOPBACK_PROXY_HOSTS || normalized in allowedHosts
}

private val LOOPBACK_PROXY_HOSTS = setOf("127.0.0.1", "localhost", "::1")

internal fun JsonObject.toOAuthTokenResponse(): OAuthTokenResponse {
    val accessToken = (this["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw OAuthRefreshException(false, "invalid_response", "OAuth 刷新响应缺少 access_token")
    val expiresIn = (this["expires_in"] as? JsonPrimitive)?.longOrNull?.coerceIn(60, 2_592_000) ?: 3_600
    return OAuthTokenResponse(
        accessToken = accessToken,
        refreshToken = (this["refresh_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
        expiresInSeconds = expiresIn,
        tokenType = (this["token_type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Bearer",
        scope = (this["scope"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
    )
}

private fun parseJsonObject(text: String): JsonObject = runCatching {
    Json.parseToJsonElement(text) as? JsonObject
}.getOrNull() ?: throw OAuthRefreshException(false, "invalid_response", "OAuth 刷新响应不是有效 JSON 对象")

private fun httpFailure(status: Int, text: String): OAuthRefreshException {
    val code = extractErrorCode(text) ?: "http_$status"
    // 400/401/403 表示请求或凭据本身有问题，重试不会自愈；429 与 5xx 保持可重试。
    val terminal = status in setOf(400, 401, 403) || code in TERMINAL_ERROR_CODES
    return OAuthRefreshException(terminal, code, "OAuth 刷新失败（HTTP $status）")
}

/** 只提取错误码，不保留响应正文或其中的任何凭据。 */
private fun extractErrorCode(text: String): String? = runCatching {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
    when (val error = root["error"]) {
        is JsonPrimitive -> error.contentOrNull
        is JsonObject -> (error["code"] as? JsonPrimitive)?.contentOrNull
            ?: (error["type"] as? JsonPrimitive)?.contentOrNull
        else -> (root["error_code"] as? JsonPrimitive)?.contentOrNull
    }
}.getOrNull()
    ?.filter { it.isLetterOrDigit() || it in "._-" }
    ?.take(80)
    ?.takeIf { it.isNotBlank() }

/** 有界读取响应体；token 与额度查询共用，避免上游返回超大响应时占满内存。 */
internal suspend fun sendBounded(client: HttpClient, request: HttpRequest): HttpResponse<ByteArray> =
    suspendCancellableCoroutine { continuation ->
        val future = client.sendAsync(
            request,
            HttpResponse.BodyHandler { BoundedBody(MAX_RESPONSE_BYTES) },
        )
        continuation.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { response, error ->
            if (!continuation.isActive) return@whenComplete
            if (error != null) continuation.resumeWithException(error) else continuation.resume(response)
        }
    }

/** 有界响应读取；上游返回异常大或无限流时主动取消。 */
private class BoundedBody(private val maxBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value
        value.request(1)
    }

    override fun onNext(items: List<ByteBuffer>) {
        for (item in items) {
            if (item.remaining() > maxBytes - bytes.size()) {
                subscription.cancel()
                result.completeExceptionally(IllegalArgumentException("oauth response too large"))
                return
            }
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            bytes.write(chunk)
        }
        subscription.request(1)
    }

    override fun onError(cause: Throwable) {
        result.completeExceptionally(cause)
    }

    override fun onComplete() {
        result.complete(bytes.toByteArray())
    }
}
