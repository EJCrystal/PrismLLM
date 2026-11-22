package dev.soven.prismllm.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest

internal class ProxyCredentials(val username: String, val password: String) {
    override fun toString(): String = "ProxyCredentials([REDACTED])"
}

internal class ChannelProxy(type: Type, address: InetSocketAddress, val credentials: ProxyCredentials?) : Proxy(type, address)

internal fun channelProxyClient(proxy: ChannelProxy, connectTimeoutMs: Long): HttpClient = HttpClient(OkHttp) {
    engine {
        config { configureChannelProxy(proxy, connectTimeoutMs) }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = connectTimeoutMs
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
}

internal fun OkHttpClient.Builder.configureChannelProxy(endpoint: ChannelProxy, connectTimeoutMs: Long) {
    proxy(if (endpoint.type() == Proxy.Type.SOCKS) Proxy.NO_PROXY else endpoint)
    connectionPool(ConnectionPool())
    retryOnConnectionFailure(false)
    val credentials = endpoint.credentials
    if (endpoint.type() == Proxy.Type.SOCKS) {
        socketFactory(ChannelSocks5SocketFactory(endpoint.address() as InetSocketAddress, credentials, connectTimeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()))
        dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val routeAddress = MessageDigest.getInstance("SHA-256").digest(hostname.toByteArray(Charsets.UTF_8)).copyOf(16)
                byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()).copyInto(routeAddress)
                return listOf(InetAddress.getByAddress(hostname, routeAddress))
            }
        })
    } else if (credentials != null) {
        proxyAuthenticator { _, response ->
            val request = response.request
            if (response.handshake != null || (request.url.isHttps && request.method != "CONNECT")) return@proxyAuthenticator null
            if (request.header("Proxy-Authorization") != null) throw IOException("HTTP 代理认证失败")
            request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8))
                .build()
        }
    }
}
