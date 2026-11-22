package dev.soven.prismllm.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat

internal class ProxyCredentials(val username: String, val password: String) {
    override fun toString(): String = "ProxyCredentials([REDACTED])"
}

internal class AuthenticatedChannelProxy(type: Type, address: InetSocketAddress, val credentials: ProxyCredentials) : Proxy(type, address)

internal fun channelProxyPoolKey(proxy: Proxy): String {
    val address = proxy.address() as InetSocketAddress
    val endpoint = proxy.type().name + ":" + address.hostString + ":" + address.port
    if (proxy !is AuthenticatedChannelProxy) return endpoint
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(proxy.credentials.username.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(proxy.credentials.password.toByteArray(Charsets.UTF_8))
    return endpoint + ":auth:" + HexFormat.of().formatHex(digest.digest())
}

internal fun authenticatedChannelProxyClient(proxy: AuthenticatedChannelProxy, connectTimeoutMs: Long): HttpClient = HttpClient(OkHttp) {
    engine {
        this.proxy = if (proxy.type() == Proxy.Type.SOCKS) Proxy.NO_PROXY else proxy
        config { configureChannelProxyAuthentication(proxy, connectTimeoutMs) }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeoutMs
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
}

internal fun OkHttpClient.Builder.configureChannelProxyAuthentication(endpoint: AuthenticatedChannelProxy, connectTimeoutMs: Long) {
    proxy(if (endpoint.type() == Proxy.Type.SOCKS) Proxy.NO_PROXY else endpoint)
    retryOnConnectionFailure(false)
    if (endpoint.type() == Proxy.Type.SOCKS) {
        socketFactory(AuthenticatedSocks5SocketFactory(endpoint.address() as InetSocketAddress, endpoint.credentials, connectTimeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()))
        dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val routeAddress = MessageDigest.getInstance("SHA-256").digest(hostname.toByteArray(Charsets.UTF_8)).copyOf(16)
                byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()).copyInto(routeAddress)
                return listOf(InetAddress.getByAddress(hostname, routeAddress))
            }
        })
    } else {
        proxyAuthenticator { _, response ->
            if (response.request.header("Proxy-Authorization") != null) throw IOException("HTTP 代理认证失败") else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(endpoint.credentials.username, endpoint.credentials.password, Charsets.UTF_8))
                    .build()
            }
        }
    }
}
