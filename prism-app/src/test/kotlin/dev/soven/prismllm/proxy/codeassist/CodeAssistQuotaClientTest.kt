package dev.soven.prismllm.proxy.codeassist

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** retrieveUserQuotaSummary 的解析：5h 与 weekly 两个窗口。 */
class CodeAssistQuotaClientTest {
    private val client = CodeAssistQuotaClient(HttpClient())

    @Test
    fun sendsTheAntigravityUserAgentAndProject() = runBlocking {
        val userAgent = AtomicReference<String>()
        val body = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:retrieveUserQuotaSummary") { exchange ->
            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            body.set(exchange.requestBody.readAllBytes().decodeToString())
            reply(exchange, """{"groups":[{"buckets":[{"window":"5h","remainingFraction":0.8}]}]}""")
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val snapshot = CodeAssistQuotaClient(
                HttpClient(Java),
                baseUrl = base,
                userAgent = { "antigravity/hub/2.30.0 windows/amd64" },
            ).read("token", "aicode-consumers")

            assertEquals(20.0, snapshot!!.primary!!.usedPercent, 0.001)
            // 缺少 Antigravity 标识时 Google 会拒绝请求；UA 由全局请求头目录注入。
            assertEquals("antigravity/hub/2.30.0 windows/amd64", userAgent.get())
            assertTrue(body.get().contains("\"project\":\"aicode-consumers\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun readsBothWindowsAndSkipsDisabledBuckets() {
        val body = """
            {"groups":[{"displayName":"Gemini","buckets":[
              {"window":"5h","remainingFraction":0.75,"resetTime":"2026-11-06T12:00:00Z"},
              {"window":"weekly","remainingFraction":0.5,"resetTime":"2026-11-10T00:00:00Z"},
              {"window":"5h","remainingFraction":0.1,"disabled":true}
            ]}]}
        """.trimIndent()

        val snapshot = client.parse(body, checkedAt = 1_000L)

        assertEquals(1_000L, snapshot!!.checkedAt)
        assertEquals(25.0, snapshot.primary!!.usedPercent)
        assertEquals(50.0, snapshot.secondary!!.usedPercent)
        assertEquals(Instant.parse("2026-11-06T12:00:00Z").toEpochMilli(), snapshot.primary!!.resetsAt)
        assertEquals(listOf("Gemini"), snapshot.groups.map { it.name })
        assertEquals(listOf(18_000, 604_800), snapshot.groups.single().windows.map { it.windowSeconds })
    }

    @Test
    fun normalizesUpstreamGroupNames() {
        assertEquals("Gemini", client.normalizeQuotaGroupName("Gemini Models"))
        assertEquals("Claude & GPT", client.normalizeQuotaGroupName("Claude and GPT Models"))
        assertEquals("Gemini 4 Argon", client.normalizeQuotaGroupName("Gemini 4 Argon"))
        assertEquals("额度", client.normalizeQuotaGroupName("   "))
    }

    @Test
    fun returnsNullWithoutUsableBuckets() {
        assertNull(client.parse("""{"groups":[]}""", 1L))
        assertNull(client.parse("not json", 1L))
    }

    private fun reply(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
