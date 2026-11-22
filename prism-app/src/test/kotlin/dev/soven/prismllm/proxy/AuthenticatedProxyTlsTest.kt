package dev.soven.prismllm.proxy

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import dev.soven.prismllm.fake.RecordingSocks5Proxy
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.certificates.buildKeyStore
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Tag
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

@Tag("component")
class AuthenticatedProxyTlsTest {
    @Test
    fun `remote dns keeps route addresses distinct to prevent cross host connection coalescing`() {
        val endpoint = AuthenticatedChannelProxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080), ProxyCredentials("user", "password"))
        val client = OkHttpClient.Builder().apply { configureChannelProxyAuthentication(endpoint, 2_000) }.build()
        try {
            val first = client.dns.lookup("first.invalid").single()
            val second = client.dns.lookup("second.invalid").single()
            assertEquals("first.invalid", first.hostName)
            assertEquals("second.invalid", second.hostName)
            assertNotEquals(first, second)
            assertEquals(first, client.dns.lookup("first.invalid").single())
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private class ConnectProxy(private val targetPort: Int, private val authorization: String) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val authenticatedTargets = ConcurrentLinkedQueue<String>()
        val address: InetSocketAddress get() = InetSocketAddress("127.0.0.1", listener.localPort)

        init {
            executor.submit {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    sockets.add(socket)
                    executor.submit { runCatching { socket.use(::connect) } }
                }
            }
        }

        private fun line(input: InputStream): String {
            val result = StringBuilder()
            while (result.length <= 8192) {
                val next = input.read()
                if (next == -1 || next == 10) return result.toString()
                if (next != 13) result.append(next.toChar())
            }
            error("proxy header line too long")
        }

        private fun connect(client: Socket) {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val request = line(input)
            require(request.startsWith("CONNECT "))
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line(input)
                if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            val output = client.getOutputStream()
            if (headers["proxy-authorization"] != authorization) {
                output.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }
            authenticatedTargets.add(request.substringAfter(' ').substringBefore(' '))
            Socket("127.0.0.1", targetPort).use { upstream ->
                sockets.add(upstream)
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                client.soTimeout = 0
                val upload = executor.submit { runCatching { input.copyTo(upstream.getOutputStream()) } }
                try {
                    upstream.getInputStream().copyTo(output)
                } finally {
                    client.close()
                    upload.cancel(true)
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `authenticated http connect and socks5 tunnel https without forwarding proxy authorization to origin`() {
        val store = buildKeyStore {
            certificate("upstream") {
                password = "test-certificate"
                domains = listOf("socks-target.invalid")
                keySizeInBits = 2048
                hash = HashAlgorithm.SHA256
            }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-certificate".toCharArray()) }
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val headers = ConcurrentLinkedQueue<Set<String>>()
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext)
            createContext("/") { exchange ->
                headers.add(exchange.requestHeaders.keys.toSet())
                val response = "tls-through-authenticated-proxy".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val username = "proxy-user"
        val password = "proxy-password"
        try {
            ConnectProxy(server.address.port, Credentials.basic(username, password, Charsets.UTF_8)).use { http ->
                RecordingSocks5Proxy(username = username, password = password).use { socks ->
                    val endpoints = listOf(
                        AuthenticatedChannelProxy(Proxy.Type.HTTP, http.address, ProxyCredentials(username, password)),
                        AuthenticatedChannelProxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", URI(socks.url).port), ProxyCredentials(username, password)),
                    )
                    endpoints.forEach { endpoint ->
                        val client = OkHttpClient.Builder().apply { configureChannelProxyAuthentication(endpoint, 2_000) }
                            .sslSocketFactory(clientContext.socketFactory, trust)
                            .callTimeout(5, TimeUnit.SECONDS).build()
                        try {
                            client.newCall(Request.Builder().url("https://socks-target.invalid:${server.address.port}/").build()).execute().use { response ->
                                assertEquals(200, response.code)
                                assertEquals("tls-through-authenticated-proxy", response.body.string())
                            }
                        } finally {
                            client.connectionPool.evictAll()
                            client.dispatcher.executorService.shutdownNow()
                        }
                    }
                    assertTrue(http.authenticatedTargets.isNotEmpty())
                    assertTrue(socks.authenticatedUsers.isNotEmpty())
                    assertTrue(socks.targets.all { it.host == "socks-target.invalid" && it.addressType == 3 })
                    assertEquals(2, headers.size)
                    assertTrue(headers.all { names -> names.none { it.equals("Proxy-Authorization", ignoreCase = true) } })
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
