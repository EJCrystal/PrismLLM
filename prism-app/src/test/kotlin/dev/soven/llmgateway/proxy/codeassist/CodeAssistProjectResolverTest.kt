package dev.soven.llmgateway.proxy.codeassist

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.soven.llmgateway.oauth.domain.OAuthAccountRecord
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.oauth.persistence.OAuthAccountRepository
import dev.soven.llmgateway.oauth.persistence.ProviderChannelLookup
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertTrue

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

    @Test
    fun `changing the account proxy renegotiates the cached project`() = runBlocking {
        val loads = AtomicReference(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            val call = loads.updateAndGet { it + 1 }
            reply(exchange, 200, """{"cloudaicompanionProject":"proj-$call"}""")
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base)
            val account = account()

            assertEquals("proj-1", resolver.context(account, "token").project)
            // 同一出口：直接命中缓存，不再请求上游。
            assertEquals("proj-1", resolver.context(account, "token").project)
            assertEquals(1, loads.get())
            // 出口变了：重新发现，避免沿用旧出口下协商的 project。
            val moved = account.copy(proxyUrl = "http://127.0.0.1:7890")
            assertEquals("proj-2", resolver.context(moved, "token").project)
            assertEquals(2, loads.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `failed renegotiation on a new egress does not reuse the old project`() = runBlocking {
        // 第一个出口发现成功；换出口后 loadCodeAssist 暂时不可用。
        val failing = AtomicReference(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            if (failing.get()) {
                reply(exchange, 503, """{"error":{"code":503}}""")
                return@createContext
            }
            reply(exchange, 200, """{"cloudaicompanionProject":"proj-old","currentTier":{"id":"free-tier"}}""")
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base)
            val account = account()

            assertEquals("proj-old", resolver.context(account, "token").project)
            failing.set(true)
            val moved = account.copy(proxyUrl = "http://127.0.0.1:7890")

            // 旧出口协商出的 project 不能带到新出口；同一出口下仍可沿用缓存。
            assertNull(resolver.context(moved, "token").project)
            assertEquals("proj-old", resolver.context(account, "token").project)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `egress key treats equivalent proxies as one egress and separates real changes`() = runBlocking {
        val calls = AtomicReference(0)
        // 本地正向代理：直接返回 loadCodeAssist 响应，不真正回源。
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        proxy.createContext("/") { exchange ->
            val call = calls.updateAndGet { it + 1 }
            reply(exchange, 200, """{"cloudaicompanionProject":"proj-$call"}""")
        }
        proxy.start()
        val port = proxy.address.port
        val address = InetSocketAddress("127.0.0.1", port)
        // 直连时这个地址连不上：能拿到 project 就说明确实走了代理。
        val unreachable = "http://127.0.0.1:1"
        try {
            val resolver = CodeAssistProjectResolver(
                FakeAccounts(AtomicReference()),
                HttpClient(Java),
                baseUrl = unreachable,
                loadBaseUrl = unreachable,
            )
            val account = account()

            assertEquals("proj-1", resolver.context(account.copy(proxyUrl = "http://127.0.0.1:$port"), "token", address).project)
            // 同一个出口的另一种写法（结尾斜杠）命中缓存，不再请求上游。
            assertEquals(
                "proj-1",
                resolver.context(account.copy(proxyUrl = "http://127.0.0.1:$port/"), "token", null).project,
            )
            assertEquals(1, calls.get())
            // 白名单不接受这个代理：出口变成直连，旧出口的 project 不得沿用。
            assertNull(resolver.context(account.copy(proxyUrl = "http://proxy.internal:3128"), "token", null).project)
            assertEquals(1, calls.get())
        } finally {
            proxy.stop(0)
        }
    }

    @Test
    fun `failed onboarding does not refresh the negotiated state`() = runBlocking {
        val calls = AtomicReference(0)
        val project = AtomicReference<String?>("proj-1")
        val clock = AtomicReference(0L)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            val current = project.get()
            reply(exchange, 200, if (current == null) """{"allowedTiers":[{"id":"free-tier"}]}""" else """{"cloudaicompanionProject":"$current"}""")
        }
        server.createContext("/v1internal:onboardUser") { exchange ->
            calls.updateAndGet { it + 1 }
            reply(exchange, 503, """{"error":{"code":503}}""")
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(
                FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base, now = { clock.get() },
            )
            val account = account()

            assertEquals("proj-1", resolver.context(account, "token").project)
            // 缓存过期后上游不再提供 project，开通也失败：本轮沿用已协商的上下文……
            clock.set(2 * 60 * 60 * 1000L)
            project.set(null)
            assertEquals("proj-1", resolver.context(account, "token").project)
            assertEquals(1, calls.get())
            // ……但不能把失败当成刚协商过，下次请求仍要重新尝试开通。
            assertEquals("proj-1", resolver.context(account, "token").project)
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `context keeps the in-memory and persisted tier in sync`() = runBlocking {
        val clock = AtomicReference(0L)
        val tier = AtomicReference<String?>("standard-tier")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            val current = tier.get()
            reply(
                exchange,
                200,
                if (current == null) """{"cloudaicompanionProject":"proj-1"}""" else """{"cloudaicompanionProject":"proj-1","currentTier":{"id":"$current"}}""",
            )
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(
                FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base, now = { clock.get() },
            )
            val account = account()

            assertEquals("standard-tier", resolver.context(account, "token").tier)
            assertEquals("standard-tier", saved.get()?.tier)
            // 上游这次不再报告 tier：本次请求沿用已协商的 tier，数据库也必须写出同一个值，
            // 否则重启后读到的 tier 与进程内不一致。
            tier.set(null)
            clock.set(2 * 60 * 60 * 1000L)
            assertEquals("standard-tier", resolver.context(account, "token").tier)
            assertEquals("standard-tier", saved.get()?.tier)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cancellation during discovery propagates`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            Thread.sleep(2_000)
            runCatching { reply(exchange, 200, """{"cloudaicompanionProject":"proj-1"}""") }
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
            val started = System.currentTimeMillis()
            val result = runCatching {
                withTimeout(150) { resolver.context(account(), "token") }
            }

            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            assertTrue(System.currentTimeMillis() - started < 1_500, "cancellation must not wait for the upstream")
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
    ) : OAuthAccountRepository(unusedDatabase(), ProviderChannelLookup { emptySet() }) {
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
