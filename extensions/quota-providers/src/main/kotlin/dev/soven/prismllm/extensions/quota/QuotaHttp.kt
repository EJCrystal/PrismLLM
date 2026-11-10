package dev.soven.prismllm.extensions.quota

import dev.soven.prismllm.spi.quota.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** 官方插件共用的有界 HTTP 读取；不跟随重定向，也不把原始错误正文带出插件。 */
abstract class HttpQuotaProvider : ChannelQuotaProvider {
    private val directClient = newClient()

    final override fun query(request: ChannelQuotaQuery): ChannelQuotaResult = try {
        val config = Json.parseToJsonElement(request.configJson).jsonObject
        // 代理仅属于本次渠道查询，不能在共享 Provider 上切换全局客户端。
        val proxyClient = proxyAddress(config)?.let { newClient(ProxySelector.of(it)) }
        try {
            queryRemote(request, config, proxyClient ?: directClient)
        } finally {
            proxyClient?.shutdownNow()
        }
    } catch (cause: QuotaHttpFailure) {
        ChannelQuotaResult(cause.status, message = cause.message)
    } catch (cause: InterruptedException) {
        Thread.currentThread().interrupt()
        throw cause
    } catch (_: Exception) {
        ChannelQuotaResult(ChannelQuotaStatus.ERROR, message = "上游额度响应无效或查询超时，请检查地址及配置")
    }

    protected abstract fun queryRemote(request: ChannelQuotaQuery, config: JsonObject, client: HttpClient): ChannelQuotaResult

    protected fun get(client: HttpClient, url: String, credential: String? = null, headers: Map<String, String> = emptyMap()): JsonObject {
        val builder = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").GET()
        if (credential != null) builder.header("Authorization", "Bearer " + credential)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandler { BoundedBody() })
        val response = try {
            future.get(15, TimeUnit.SECONDS)
        } finally {
            if (!future.isDone) future.cancel(true)
        }
        when (response.statusCode()) {
            401, 403 -> throw QuotaHttpFailure(ChannelQuotaStatus.AUTHENTICATION_FAILED, "上游凭据无效或没有查询权限")
            404, 405 -> throw QuotaHttpFailure(ChannelQuotaStatus.UNSUPPORTED, "上游不支持此额度接口，请检查插件及站点地址")
            429 -> throw QuotaHttpFailure(ChannelQuotaStatus.RATE_LIMITED, "上游限制了额度查询频率，请稍后重试")
        }
        if (response.statusCode() !in 200..299) {
            throw QuotaHttpFailure(ChannelQuotaStatus.ERROR, "上游额度查询失败（HTTP " + response.statusCode() + "）")
        }
        return Json.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject
    }

    protected fun siteBase(request: ChannelQuotaQuery, config: JsonObject): String {
        val original = URI(request.baseUrl.trimEnd('/'))
        val configured = config.string("baseUrl")?.takeIf { it.isNotBlank() }
        val target = URI(configured ?: request.baseUrl.trimEnd('/').replace(Regex("/v1(?:beta)?$"), ""))
        require(
            target.host != null &&
                (target.scheme == "https" || (target.scheme == "http" && target.host == "127.0.0.1")),
        )
        require(target.userInfo == null && target.rawQuery == null && target.rawFragment == null)
        fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        require(target.scheme == original.scheme && target.host.equals(original.host, ignoreCase = true) && port(target) == port(original))
        return target.toString().trimEnd('/')
    }

    private fun newClient(proxy: ProxySelector? = null): HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .apply { if (proxy != null) this.proxy(proxy) }
        .build()

    private fun proxyAddress(config: JsonObject): InetSocketAddress? = try {
        config.string("proxyUrl")?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            val uri = URI(value)
            require(uri.scheme == "http" && uri.host != null)
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            val port = if (uri.port == -1) 80 else uri.port
            require(port in 1..65535)
            InetSocketAddress(uri.host, port)
        }
    } catch (_: Exception) {
        throw QuotaHttpFailure(ChannelQuotaStatus.ERROR, "额度查询代理配置无效，请使用不含账号密码的 http://主机:端口")
    }

    override fun close() { directClient.shutdownNow() }
}

internal class QuotaHttpFailure(val status: ChannelQuotaStatus, message: String) : RuntimeException(message)

internal fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && it.isString)
    it.content
}
internal fun JsonObject.number(key: String): java.math.BigDecimal? = get(key)?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && it.content.length <= 80)
    requireNotNull(it.content.toBigDecimalOrNull()).also { number ->
        require(number.precision() <= 40 && number.scale() in -20..20)
    }
}
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeUnless { it == JsonNull }?.let {
    require(it is JsonObject)
    it
}
internal fun JsonObject.bool(key: String): Boolean? = get(key)?.takeUnless { it == JsonNull }?.let {
    require(it is JsonPrimitive && !it.isString)
    requireNotNull(it.booleanOrNull)
}

private class BoundedBody : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        for (item in items) {
            if (item.remaining() > 1_048_576 - bytes.size()) {
                subscription.cancel()
                result.completeExceptionally(IllegalArgumentException("quota response too large"))
                return
            }
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            bytes.write(chunk)
        }
        subscription.request(1)
    }
    override fun onError(cause: Throwable) { result.completeExceptionally(cause) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}
