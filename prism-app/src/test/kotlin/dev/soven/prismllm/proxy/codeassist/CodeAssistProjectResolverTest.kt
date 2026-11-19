package dev.soven.prismllm.proxy.codeassist

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.db.DiagnosticPreparationDeadline
import dev.soven.prismllm.oauth.OAuthTokenUnavailableException
import dev.soven.prismllm.oauth.domain.OAuthAuthState
import dev.soven.prismllm.oauth.matchesCredentialSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.oauth.persistence.ProviderChannelLookup
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Tag
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
@Tag("component")
class CodeAssistProjectResolverTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `onboard serializes tier and caches the project`() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val userAgents = CopyOnWriteArrayList<String>()
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            requests += "/v1internal:loadCodeAssist" to exchange.requestBody.readAllBytes().decodeToString()
            userAgents += exchange.requestHeaders.getFirst("User-Agent")
            reply(exchange, 200, """{"allowedTiers":[{"id":"free-tier"}]}""")
        }
        server.createContext("/v1internal:onboardUser") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            requests += "/v1internal:onboardUser" to body
            userAgents += exchange.requestHeaders.getFirst("User-Agent")
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
            // Google 按 User-Agent 识别调用方；缺少 Antigravity 标识会拿不到项目。
            assertEquals(2, userAgents.size)
            assertTrue(userAgents.all { it.startsWith("antigravity/") })
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
    fun `probe keeps catalog entitlements to the paid and current tier`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            reply(
                exchange,
                200,
                """{"cloudaicompanionProject":"proj-1","currentTier":{"id":"free-tier"},"paidTier":{"id":"g1-pro-tier"},"allowedTiers":[{"id":"standard-tier"},{"id":"free-tier"}]}""",
            )
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base)

            val probe = resolver.probe(account(), "token")

            // 付费档排在当前档之前；free-tier 不能显式命名，Google 会以 400 拒绝。
            assertEquals(listOf("g1-pro-tier"), probe.tiers())
            // 数据面上下文记录付费档，请求目录时不会再退回免费档。
            assertEquals("g1-pro-tier", saved.get()?.tier)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `probe falls back to the current tier and asks without entitlement when none is paid`() = runBlocking {
        val body = AtomicReference(
            """{"cloudaicompanionProject":"proj-1","currentTier":{"id":"standard-tier"}}""",
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            reply(exchange, 200, body.get())
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val resolver = CodeAssistProjectResolver(FakeAccounts(AtomicReference()), HttpClient(Java), baseUrl = base, loadBaseUrl = base)

            assertEquals(listOf("standard-tier"), resolver.probe(account(), "token").tiers())

            // 只有免费档：不显式命名，目录请求由调用方补一次无 entitlement 的兜底。
            body.set("""{"cloudaicompanionProject":"proj-1","currentTier":{"id":"free-tier"}}""")
            assertEquals(emptyList(), resolver.probe(account(), "token").tiers())
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
            assertEquals(listOf("standard-tier", "free-tier"), probe.allowedTiers)
            // 允许档只用于首次开通，不能当成目录 entitlement 逐个去试。
            assertEquals(emptyList(), probe.tiers())
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
            val account = account().copy(proxyUrl = "http://127.0.0.1:$port")

            assertEquals("proj-1", resolver.context(account, "token", address).project)
            // 调用方复用已解析的相同出口，不进行第二次 DNS 解析。
            assertEquals(
                "proj-1",
                resolver.context(account, "token", address).project,
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
    fun `downgrading to the free tier clears the cached paid tier`() = runBlocking {
        val clock = AtomicReference(0L)
        val paid = AtomicReference(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1internal:loadCodeAssist") { exchange ->
            val body = if (paid.get()) {
                """{"cloudaicompanionProject":"proj-1","currentTier":{"id":"free-tier"},"paidTier":{"id":"g1-pro-tier"}}"""
            } else {
                """{"cloudaicompanionProject":"proj-1","currentTier":{"id":"free-tier"}}"""
            }
            reply(exchange, 200, body)
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val resolver = CodeAssistProjectResolver(
                FakeAccounts(saved), HttpClient(Java), baseUrl = base, loadBaseUrl = base, now = { clock.get() },
            )
            val account = account()

            assertEquals("g1-pro-tier", resolver.context(account, "token").tier)
            assertEquals("g1-pro-tier", saved.get()?.tier)
            // 套餐降级后上游只报告 free-tier：过滤后不再有付费档，必须覆盖旧的付费档，
            // 否则目录请求会继续拿已经失效的 entitlement。
            paid.set(false)
            clock.set(2 * 60 * 60 * 1000L)
            assertNull(resolver.context(account, "token").tier)
            assertNull(saved.get()?.tier)
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

    @Test
    fun `discovery rejects a replaced authorization and only publishes accepted snapshots`() = runBlocking {
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val accepts = java.util.concurrent.atomic.AtomicBoolean(true)
        val requests = AtomicInteger()
        val accounts = FakeAccounts(saved, snapshotMatches = { accepts.get() })
        HttpClient(MockEngine {
            val number = requests.incrementAndGet()
            if (number == 2) accepts.set(false)
            respond("""{"cloudaicompanionProject":"proj-$number"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val resolver = CodeAssistProjectResolver(accounts, client)
            val original = account()
            assertEquals("proj-1", resolver.contextForDiagnostic(original, "token", null, DiagnosticPreparationDeadline.start(5_000)).project)
            assertEquals("proj-1", resolver.contextForDiagnostic(original, "token", null, DiagnosticPreparationDeadline.start(5_000)).project)
            assertEquals(1, requests.get())
            saved.set(OAuthAccountRepository.UpstreamContext("different-egress-project", null, System.currentTimeMillis()))
            val failure = runCatching {
                resolver.contextForDiagnostic(original, "token", null, DiagnosticPreparationDeadline.start(5_000))
            }.exceptionOrNull()
            assertEquals("credential_replaced", (failure as? dev.soven.prismllm.oauth.OAuthTokenUnavailableException)?.errorCode)
            assertEquals("different-egress-project", saved.get()?.project)
            accepts.set(true)
            val replacement = original.copy(authorizedAt = 2, refreshTokenCiphertext = "new-authorization")
            assertEquals("proj-3", resolver.contextForDiagnostic(replacement, "new-token", null, DiagnosticPreparationDeadline.start(5_000)).project)
            assertEquals("proj-3", saved.get()?.project)
        }
    }

    @Test
    fun `diagnostic context read timeout does not continue discovery after the database returns`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Job>()
        val scheduler = TestCoroutineScheduler()
        val requests = AtomicInteger()
        val accounts = FakeAccounts(AtomicReference(), beforeRead = {
            worker.set(currentCoroutineContext().job)
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        })
        HttpClient(MockEngine {
            requests.incrementAndGet()
            respond("""{"cloudaicompanionProject":"proj-1"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val resolver = CodeAssistProjectResolver(accounts, client)
            try {
                val caller = async(StandardTestDispatcher(scheduler)) {
                    runCatching {
                        resolver.contextForDiagnostic(account(), "token", null,
                            DiagnosticPreparationDeadline.start(60_000) { scheduler.currentTime * 1_000_000 })
                    }
                }
                scheduler.runCurrent()
                withTimeout(5_000) {
                    while (entered.count != 0L) {
                        scheduler.runCurrent()
                        kotlinx.coroutines.delay(1)
                    }
                }
                scheduler.advanceTimeBy(60_001)
                scheduler.runCurrent()
                val failure = withTimeout(3_000) { caller.await() }.exceptionOrNull()
                assertTrue(DiagnosticPreparationDeadline.isTimeout(assertNotNull(failure)))
                assertEquals(0, requests.get())
            } finally {
                release.countDown()
                withTimeout(5_000) { assertNotNull(worker.get()).join() }
                scheduler.runCurrent()
            }
            assertEquals(0, requests.get(), "late database completion must not start discovery")
        }
    }

    @Test
    fun `diagnostic context write timeout returns while its database worker still owns cleanup`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Job>()
        val scheduler = TestCoroutineScheduler()
        val requests = AtomicInteger()
        val accounts = FakeAccounts(AtomicReference(), beforeWrite = {
            worker.set(currentCoroutineContext().job)
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        })
        HttpClient(MockEngine {
            requests.incrementAndGet()
            respond("""{"cloudaicompanionProject":"proj-1"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val resolver = CodeAssistProjectResolver(accounts, client)
            try {
                val caller = async(StandardTestDispatcher(scheduler)) {
                    runCatching {
                        resolver.contextForDiagnostic(account(), "token", null,
                            DiagnosticPreparationDeadline.start(60_000) { scheduler.currentTime * 1_000_000 })
                    }
                }
                scheduler.runCurrent()
                withTimeout(5_000) {
                    while (entered.count != 0L) {
                        scheduler.runCurrent()
                        kotlinx.coroutines.delay(1)
                    }
                }
                scheduler.runCurrent()
                scheduler.advanceTimeBy(60_001)
                scheduler.runCurrent()
                val failure = withTimeout(3_000) { caller.await() }.exceptionOrNull()
                assertTrue(DiagnosticPreparationDeadline.isTimeout(assertNotNull(failure)))
                assertTrue(!assertNotNull(worker.get()).isCompleted, "the blocked database operation must still be owned")
                assertEquals(1, requests.get())
            } finally {
                release.countDown()
                withTimeout(5_000) { assertNotNull(worker.get()).join() }
                scheduler.runCurrent()
            }
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun `catalog probe reuses only the same confirmed project after temporary discovery misses`() = runBlocking {
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val calls = AtomicInteger()
        HttpClient(MockEngine { request ->
            val body = if (calls.incrementAndGet() == 1) {
                """{"cloudaicompanionProject":"confirmed-project","paidTier":{"id":"paid-tier"}}"""
            } else if (request.url.encodedPath.endsWith("loadCodeAssist")) {
                """{"currentTier":{"id":"paid-tier"}}"""
            } else """{"done":true,"response":{}}"""
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), client)
            val current = account()
            assertEquals("confirmed-project", resolver.probe(current, "token").project)
            assertEquals("confirmed-project", resolver.probe(current, "token").project)
            assertNull(resolver.probe(current.copy(authorizedAt = 123), "new-token").project)
        }
    }

    @Test
    fun `catalog probe rejects stale authorization instead of exposing its tier`() = runBlocking {
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val accounts = FakeAccounts(saved, snapshotMatches = { false })
        HttpClient(MockEngine {
            respond("""{"cloudaicompanionProject":"old-project","paidTier":{"id":"old-tier"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val failure = runCatching { CodeAssistProjectResolver(accounts, client).probe(account(), "old-token") }.exceptionOrNull()
            assertEquals("credential_replaced", (failure as? dev.soven.prismllm.oauth.OAuthTokenUnavailableException)?.errorCode)
            assertNull(saved.get())
        }
    }

    @Test
    fun `projectless probe rejects admin replacement before returning entitlements`() = runBlocking {
        val original = account()
        val replacements = listOf(
            original.copy(authorizedAt = 123),
            original.copy(refreshTokenCiphertext = "replacement-refresh"),
            original.copy(refreshTokenNonce = "replacement-nonce"),
            original.copy(refreshTokenKeyVersion = 2),
            original.copy(accessTokenCiphertext = "replacement-access"),
            original.copy(accessTokenNonce = "replacement-access-nonce"),
            original.copy(accessTokenKeyVersion = 2),
            original.copy(accessTokenExpiresAt = 999),
            original.copy(tokenType = "replacement-type"),
            original.copy(scope = "replacement-scope"),
            original.copy(authState = OAuthAuthState.REAUTH_REQUIRED),
            original.copy(proxyUrl = "http://replacement-proxy:3128"),
        )
        for (replacement in replacements) {
            for (warm in listOf(false, true)) {
                val current = AtomicReference(original)
                val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
                val replace = java.util.concurrent.atomic.AtomicBoolean(false)
                val accounts = FakeAccounts(saved, snapshotMatches = { it.matchesCredentialSnapshot(current.get()) })
                HttpClient(MockEngine { request ->
                    val body = if (!replace.get()) {
                        """{"cloudaicompanionProject":"confirmed-project","paidTier":{"id":"paid-tier"}}"""
                    } else if (request.url.encodedPath.endsWith("loadCodeAssist")) {
                        """{"currentTier":{"id":"stale-tier"},"paidTier":{"id":"stale-paid"},"allowedTiers":[{"id":"stale-allowed"}]}"""
                    } else {
                        current.set(replacement)
                        """{"done":true,"response":{}}"""
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }).use { client ->
                    val resolver = CodeAssistProjectResolver(accounts, client)
                    if (warm) assertEquals("confirmed-project", resolver.probe(original, "captured-token").project)
                    val before = saved.get()
                    replace.set(true)
                    val failure = runCatching { resolver.probe(original, "captured-token") }.exceptionOrNull()
                    assertEquals("credential_replaced", (failure as? OAuthTokenUnavailableException)?.errorCode)
                    assertEquals(before, saved.get(), "rejected probe must not write a context")
                }
            }
        }
    }

    @Test
    fun `projectless fallback rejects a concurrent database context change`() = runBlocking {
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val change = java.util.concurrent.atomic.AtomicBoolean(false)
        HttpClient(MockEngine { request ->
            val body = if (!change.get()) {
                """{"cloudaicompanionProject":"confirmed-project","paidTier":{"id":"paid-tier"}}"""
            } else if (request.url.encodedPath.endsWith("loadCodeAssist")) {
                """{"currentTier":{"id":"paid-tier"}}"""
            } else {
                saved.set(OAuthAccountRepository.UpstreamContext("other-project", "paid-tier", 123))
                """{"done":true,"response":{}}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), client)
            assertEquals("confirmed-project", resolver.probe(account(), "token").project)
            change.set(true)
            assertNull(resolver.probe(account(), "token").project)
            assertEquals("other-project", saved.get()?.project)
        }
    }

    @Test
    fun `context rechecks captured credentials before fallback and cache hits`() = runBlocking {
        val current = AtomicReference(account())
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val clock = AtomicReference(0L)
        val replace = java.util.concurrent.atomic.AtomicBoolean(false)
        val calls = AtomicInteger()
        val accounts = FakeAccounts(saved, snapshotMatches = { it.matchesCredentialSnapshot(current.get()) })
        HttpClient(MockEngine {
            calls.incrementAndGet()
            if (replace.get()) {
                current.set(account().copy(accessTokenNonce = "admin-replacement"))
                respond("unavailable", io.ktor.http.HttpStatusCode.ServiceUnavailable)
            } else respond("""{"cloudaicompanionProject":"confirmed-project"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }).use { client ->
            val resolver = CodeAssistProjectResolver(accounts, client, now = { clock.get() })
            assertEquals("confirmed-project", resolver.context(account(), "token").project)
            val before = saved.get()
            clock.set(7_200_000)
            replace.set(true)
            val fallbackFailure = runCatching { resolver.context(account(), "token") }.exceptionOrNull()
            assertEquals("credential_replaced", (fallbackFailure as? OAuthTokenUnavailableException)?.errorCode)
            clock.set(0)
            val cacheFailure = runCatching { resolver.context(account(), "token") }.exceptionOrNull()
            assertEquals("credential_replaced", (cacheFailure as? OAuthTokenUnavailableException)?.errorCode)
            assertEquals(2, calls.get())
            assertEquals(before, saved.get())
        }
    }

    @Test
    fun `oversized discovery and onboarding never poison cached context or fallback`() = runBlocking {
        val tooLongProject = "p".repeat(129)
        val tooLongTier = "t".repeat(65)
        val invalidBodies = listOf(
            """{"cloudaicompanionProject":"$tooLongProject"}""",
            """{"cloudaicompanionProject":"bad-project","currentTier":{"id":"$tooLongTier"}}""",
            """{"cloudaicompanionProject":"bad-project","paidTier":{"id":"$tooLongTier"}}""",
            """{"allowedTiers":[{"id":"$tooLongTier"}]}""",
            """{"currentTier":{"id":"valid-tier"}}""",
        )
        for (invalidBody in invalidBodies) {
            val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
            val clock = AtomicReference(0L)
            val mode = AtomicInteger(0)
            val calls = AtomicInteger()
            HttpClient(MockEngine { request ->
                calls.incrementAndGet()
                when (mode.get()) {
                    0 -> respond("""{"cloudaicompanionProject":"confirmed-project","paidTier":{"id":"paid-tier"}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    1 -> respond(
                        if (request.url.encodedPath.endsWith("loadCodeAssist")) invalidBody
                        else """{"done":true,"response":{"cloudaicompanionProject":"$tooLongProject"}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond("unavailable", io.ktor.http.HttpStatusCode.ServiceUnavailable)
                }
            }).use { client ->
                val resolver = CodeAssistProjectResolver(FakeAccounts(saved), client, now = { clock.get() })
                val expected = CodeAssistProjectResolver.Context("confirmed-project", "paid-tier")
                assertEquals(expected, resolver.context(account(), "token"))
                val before = saved.get()
                clock.set(7_200_000)
                mode.set(1)
                assertTrue(runCatching { resolver.context(account(), "token") }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { resolver.probe(account(), "token") }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(before, saved.get())
                clock.set(0)
                val beforeCacheHit = calls.get()
                assertEquals(expected, resolver.context(account(), "token"))
                assertEquals(beforeCacheHit, calls.get(), "rejected values must leave the original cache usable")
                clock.set(7_200_000)
                mode.set(2)
                assertEquals(expected, resolver.context(account(), "token"))
                assertEquals(before, saved.get(), "fallback must not refresh the database timestamp")
            }
        }
    }

    @Test
    fun `maximum length context remains identical in memory database cache and fallback`() = runBlocking {
        val project = "p".repeat(128)
        val tier = "t".repeat(64)
        val saved = AtomicReference<OAuthAccountRepository.UpstreamContext?>()
        val clock = AtomicReference(0L)
        val calls = AtomicInteger()
        HttpClient(MockEngine {
            if (calls.incrementAndGet() == 1) respond(
                """{"cloudaicompanionProject":"$project","paidTier":{"id":"$tier"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            ) else respond("unavailable", io.ktor.http.HttpStatusCode.ServiceUnavailable)
        }).use { client ->
            val resolver = CodeAssistProjectResolver(FakeAccounts(saved), client, now = { clock.get() })
            val expected = CodeAssistProjectResolver.Context(project, tier)
            assertEquals(expected, resolver.context(account(), "token"))
            assertEquals(project, saved.get()?.project)
            assertEquals(tier, saved.get()?.tier)
            assertEquals(expected, resolver.context(account(), "token"))
            assertEquals(1, calls.get())
            clock.set(7_200_000)
            assertEquals(expected, resolver.context(account(), "token"))
            assertEquals(2, calls.get())
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
        private val beforeRead: suspend () -> Unit = {},
        private val beforeWrite: suspend () -> Unit = {},
        private val snapshotMatches: (OAuthAccountRecord) -> Boolean = { true },
    ) : OAuthAccountRepository(unusedDatabase(), ProviderChannelLookup { emptySet() }) {
        override suspend fun upstreamContextIfSnapshotMatches(expectedAccount: OAuthAccountRecord): OAuthAccountRepository.UpstreamContext? {
            beforeRead()
            if (!snapshotMatches(expectedAccount)) return null
            return saved.get() ?: OAuthAccountRepository.UpstreamContext(null, null, null)
        }

        override suspend fun loadUpstreamContext(accountId: String): OAuthAccountRepository.UpstreamContext? {
            beforeRead()
            return saved.get()
        }

        override suspend fun saveUpstreamContextIfSnapshotMatches(
            expectedAccount: OAuthAccountRecord,
            project: String?,
            tier: String?,
            checkedAt: Long,
        ): Boolean {
            beforeWrite()
            if (!snapshotMatches(expectedAccount)) return false
            saved.set(OAuthAccountRepository.UpstreamContext(project, tier, checkedAt))
            return true
        }

        override suspend fun persistUpstreamContext(
            accountId: String,
            project: String?,
            tier: String?,
            checkedAt: Long,
        ): Boolean {
            beforeWrite()
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
