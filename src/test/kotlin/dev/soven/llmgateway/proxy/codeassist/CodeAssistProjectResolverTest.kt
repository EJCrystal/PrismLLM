package dev.soven.llmgateway.proxy.codeassist

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.repo.OAuthAccountRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * loadCodeAssist / onboardUser 的请求体：tier 传给上游前必须经过 JSON 序列化，
 * 并且开通后要把 project 回写到账号缓存。
 */
class CodeAssistProjectResolverTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `onboard serializes tier and caches the project`() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            requests += "/v1internal:loadCodeAssist" to exchange.requestBody.readAllBytes().decodeToString()
            reply(exchange, 200, """{"allowedTiers":[{"id":"free-tier"}]}""")
        }
        server.createContext("/v1internal:onboardUser") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            requests += "/v1internal:onboardUser" to body
            reply(exchange, 200, """{"done":true,"response":{"cloudaicompanionProject":"proj-1"}}""")
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val accounts = FakeAccounts(saved)
            val resolver = CodeAssistProjectResolver(accounts, HttpClient(Java), baseUrl = base, loadBaseUrl = base)

            val probe = resolver.probe(account(), "token")

            assertEquals("proj-1", probe.project)
            assertEquals("proj-1", saved.get()?.project)
            // 当前 tier 缺失时，用账号可开通的第一个 tier 开通。
            val onboardBody = requests.first { it.first == "/v1internal:onboardUser" }.second
            assertEquals("free-tier", json.parseToJsonElement(onboardBody).jsonObject["tier_id"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `tier with quotes and backslashes survives serialization`() = runBlocking {
        val onboardBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            reply(exchange, 200, """{"currentTier":{"id":"a\"b\\c"}}""")
        }
        server.createContext("/v1internal:onboardUser") { exchange ->
            onboardBody.set(exchange.requestBody.readAllBytes().decodeToString())
            reply(exchange, 200, """{"done":true,"response":{"cloudaicompanionProject":"proj-1"}}""")
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val resolver = CodeAssistProjectResolver(
                FakeAccounts(AtomicReference()),
                HttpClient(Java),
                baseUrl = base,
                loadBaseUrl = base,
            )

            resolver.probe(account(), "token")

            val parsed = json.parseToJsonElement(onboardBody.get()) as JsonObject
            assertEquals("a\"b\\c", parsed["tier_id"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `probe surfaces allowed tiers when the account has no project`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            // 没有现成 project，开通后也只返回空响应。
            reply(exchange, 200, """{"allowedTiers":[{"id":"standard-tier"},{"id":"free-tier"}]}""")
        }
        server.createContext("/v1internal:onboardUser") { exchange ->
            reply(exchange, 200, """{"done":true,"response":{}}""")
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base)

            val probe = resolver.probe(account(), "token")

            assertNull(probe.project)
            // 目录拉取要能按账号可开通的 tier 逐个尝试。
            assertEquals(listOf("standard-tier", "free-tier"), probe.tiers())
            assertNull(saved.get())
        } finally {
            server.stop(0)
        }
    }

    private fun account(): OAuthAccountRecord = OAuthAccountRecord(
        id = "account-1",
        name = "antigravity",
        provider = OAuthProvider.ANTIGRAVITY,
        refreshTokenCiphertext = "c",
        refreshTokenNonce = "n",
        refreshTokenKeyVersion = 1,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 测试用内存实现：替换 repository 的缓存读写，不触碰真实数据库。 */
    private class FakeAccounts(
        private val saved: AtomicReference<OAuthAccountRepository.UpstreamContext?>,
    ) : OAuthAccountRepository(unusedDatabase()) {
        override suspend fun loadUpstreamContext(accountId: String): OAuthAccountRepository.UpstreamContext? =
            saved.get()

        override suspend fun persistUpstreamContext(
            accountId: String,
            project: String?,
            tier: String?,
            checkedAt: Long,
        ): Boolean {
            saved.set(OAuthAccountRepository.UpstreamContext(project, tier, checkedAt))
            return true
        }

        private companion object {
            /**
             * 基类构造需要一个 Database 实例。测试只覆盖了缓存读写，不会走到任何真正访问
             * 数据库的方法，所以这里只登记一个不会有人使用的连接定义。
             */
            fun unusedDatabase(): Database = Database.connect(UnusedDataSource())

            private class UnusedDataSource : javax.sql.DataSource {
                override fun getConnection(): java.sql.Connection = throw UnsupportedOperationException()
                override fun getConnection(username: String?, password: String?): java.sql.Connection =
                    throw UnsupportedOperationException()

                override fun getLogWriter(): java.io.PrintWriter = throw UnsupportedOperationException()
                override fun setLogWriter(out: java.io.PrintWriter?) = throw UnsupportedOperationException()
                override fun setLoginTimeout(seconds: Int) = throw UnsupportedOperationException()
                override fun getLoginTimeout(): Int = 0
                override fun getParentLogger(): java.util.logging.Logger = java.util.logging.Logger.getGlobal()
                override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
                override fun isWrapperFor(iface: java.lang.Class<*>?): Boolean = false
            }
        }
    }
}
