package dev.soven.prismllm.proxy.codeassist

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 账号配置了代理时，Code Assist 的目录、额度与 project 请求都要经该代理发出，
 * 不能落到网关的直连出口。
 */
@Tag("component")
class CodeAssistEgressTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun stop() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun `model catalog and quota go through the account proxy`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        // 一个极简正向代理：记录请求行后直接返回固定响应，不真正回源。
        val upstream = server { exchange ->
            seen += exchange.requestHeaders.getFirst("Host") ?: ""
            reply(exchange, """{"models":{"gemini-3.7-flash-high":{"displayName":"Gemini 3.7 Flash (High)"}}}""")
        }
        val proxy = server { exchange ->
            seen += "proxy " + exchange.requestURI.rawPath
            val path = exchange.requestURI.rawPath
            val body = if (path.endsWith("retrieveUserQuotaSummary")) {
                """{"groups":[{"buckets":[{"window":"5h","remainingFraction":0.5}]}]}"""
            } else {
                """{"models":{"gemini-3.7-flash-high":{"displayName":"Gemini 3.7 Flash (High)"}}}"""
            }
            reply(exchange, body)
        }
        val proxyAddress = InetSocketAddress("127.0.0.1", proxy.address.port)
        val pool = CodeAssistClientPool(HttpClient())

        val catalog = CodeAssistModelCatalog(HttpClient(), baseUrl = target(upstream), clients = pool)
        val models = catalog.fetch("token", "project", listOf("free-tier"), proxyAddress)
        val quota = CodeAssistQuotaClient(HttpClient(), baseUrl = target(upstream), clients = pool)
            .read("token", "project", proxyAddress)

        // 单个变体按原始 id 发布，客户端拿到的就是目录里真实存在的模型。
        assertEquals(listOf("gemini-3.7-flash-high"), models.map { it.id })
        assertEquals(50.0, quota!!.primary!!.usedPercent)
        // 两个请求都只打到代理，没有直接连上游。
        assertEquals(2, seen.count { it.startsWith("proxy ") }, "requests: $seen")
        assertEquals(0, seen.count { it.isNotEmpty() && !it.startsWith("proxy ") }, "requests: $seen")
    }

    @Test
    fun `without an account proxy requests keep using the shared client`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val upstream = server { exchange ->
            seen += "upstream " + exchange.requestURI.rawPath
            reply(exchange, """{"groups":[{"buckets":[{"window":"5h","remainingFraction":0.5}]}]}""")
        }
        val pool = CodeAssistClientPool(HttpClient())

        val quota = CodeAssistQuotaClient(HttpClient(), baseUrl = target(upstream), clients = pool)
            .read("token", "project", null)

        assertEquals(50.0, quota!!.primary!!.usedPercent)
        assertEquals(listOf("upstream /v1internal:retrieveUserQuotaSummary"), seen)
    }

    @Test
    fun `catalogs of different projects do not share snapshots`() = runBlocking {
        val upstream = server { exchange ->
            reply(exchange, """{"models":{"gemini-3.7-flash-high":{"displayName":"Gemini 3.7 Flash (High)"}}}""")
        }
        val catalog = CodeAssistModelCatalog(HttpClient(), baseUrl = target(upstream))

        val first = catalog.fetchFor("token", "project-a", emptyList())
        val second = catalog.fetchFor("token", "project-b", emptyList())

        // 每个 project 各自持有一份目录，请求时按自己那份选变体。
        assertEquals("gemini-3.7-flash-high", first.sentId("gemini-3.7-flash", null))
        assertEquals("gemini-3.7-flash-high", second.sentId("gemini-3.7-flash", null))
        assertEquals("gemini-3.7-flash", catalog.cached("project-c")?.sentId("gemini-3.7-flash", null) ?: "gemini-3.7-flash")
        assertEquals(1, catalog.snapshot("project-a").size)
        assertEquals(1, catalog.snapshot("project-b").size)
    }

    @Test
    fun `catalog expires and is refetched`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val upstream = server { exchange ->
            val call = calls.incrementAndGet()
            reply(exchange, """{"models":{"gemini-3.7-flash-high":{"displayName":"Gemini 3.7 Flash (High) $call"}}}""")
        }
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val catalog = CodeAssistModelCatalog(HttpClient(), baseUrl = target(upstream), now = { clock.get() })

        assertEquals(1, catalog.fetchFor("token", "project", emptyList()).models.size)
        // 有效期内命中缓存，不再请求上游。
        assertEquals(1, clock.get().let { catalog.snapshot("project") }.size)
        assertEquals(1, calls.get())
        // 超过有效期后自动失效，下一次请求补拉并换到新目录。
        clock.set(7 * 60 * 60 * 1000L)
        assertEquals(0, catalog.snapshot("project").size)
        assertEquals(1, catalog.fetchFor("token", "project", emptyList()).models.size)
        assertEquals(2, calls.get())
    }

    @Test
    fun `catalog is refetched when the negotiated tier changes`() = runBlocking<Unit> {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val upstream = server { exchange ->
            calls.incrementAndGet()
            reply(exchange, """{"models":{"gemini-3.7-flash-high":{"displayName":"Gemini 3.7 Flash (High)"}}}""")
        }
        val catalog = CodeAssistModelCatalog(HttpClient(), baseUrl = target(upstream))

        assertNotNull(catalog.fetchFor("token", "project", listOf("free-tier")).models.firstOrNull())
        // 同 tier 命中缓存。
        assertNotNull(catalog.cached("project", "free-tier"))
        // 套餐切换：旧目录不再可信。
        assertNull(catalog.cached("project", "standard-tier"))
        // 无 entitlement 的兜底目录对任何套餐都成立。
        assertNotNull(catalog.cached("project", null))
    }

    /** 上游地址指向本地主机；经代理时要让代理按 Host 头转发，因此这里用绝对 URI。 */
    private fun target(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            runCatching { handler(exchange) }
        }
        server.start()
        servers += server
        return server
    }

    private fun reply(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
