package dev.soven.prismllm.extensions.quota

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.spi.quota.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class QuotaProvidersTest {
    private lateinit var server: HttpServer
    private val replies = ConcurrentHashMap<String, Reply>()
    private val requests = ConcurrentLinkedQueue<Pair<String, String?>>()
    private val userHeaders = ConcurrentLinkedQueue<Pair<String, String?>>()
    private val requestUris = ConcurrentLinkedQueue<String>()
    private val base get() = "http://127.0.0.1:" + server.address.port

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requestUris += exchange.requestURI.toString()
            requests += path to exchange.requestHeaders.getFirst("Authorization")
            userHeaders += path to exchange.requestHeaders.getFirst("New-Api-User")
            val reply = replies[path] ?: Reply(404, "{}")
            try {
                reply.headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
                val bytes = reply.body.toByteArray()
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
    }

    @AfterEach
    fun stop() { server.stop(0) }

    @Test
    fun serviceDescriptorRegistersBothIndependentProviders() {
        val providers = ServiceLoader.load(ChannelQuotaProvider::class.java).toList()
        try { assertEquals(setOf("sub2api", "new-api"), providers.map { it.id }.toSet()) }
        finally { providers.forEach { it.close() } }
    }

    @Test
    fun interruptedQueryPropagatesCancellationAndRestoresTheInterruptFlag() {
        val interruption = InterruptedException("query cancelled")
        val provider = object : HttpQuotaProvider() {
            override val id = "interrupted-test"
            override val displayName = "Interrupted test"
            override fun queryRemote(request: ChannelQuotaQuery, config: JsonObject, client: HttpClient): ChannelQuotaResult {
                throw interruption
            }
        }
        try {
            assertSame(interruption, assertFailsWith<InterruptedException> { provider.query(query()) })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            provider.close()
        }
    }

    @Test
    fun sub2ApiUsesAccountWalletAndPreservesZeroAndNegativeBalance() {
        for (balance in listOf("100.25", "0", "-1.25")) {
            replies["/site/v1/usage"] = Reply(200, """{"isValid":true,"balance":$balance,"remaining":999,"unit":"USD"}""")
            val result = sub()
            assertEquals(ChannelQuotaStatus.AVAILABLE, result.status)
            assertEquals(balance, result.remaining!!.toPlainString())
            assertEquals("USD", result.unit)
        }
        assertTrue(requests.all { it == ("/site/v1/usage" to "Bearer test-secret") })
    }

    @Test
    fun sub2ApiDoesNotTreatKeyQuotaOrUnidentifiedRemainingAsAccountBalance() {
        for (body in listOf(
            """{"isValid":true,"mode":"quota_limited","remaining":80,"quota":{"limit":100,"used":20}}""",
            """{"remaining":80,"unit":"USD"}""",
        )) {
            replies["/site/v1/usage"] = Reply(200, body)
            val result = sub()
            assertEquals(ChannelQuotaStatus.UNSUPPORTED, result.status)
            assertNull(result.remaining)
        }
    }

    @Test
    fun sub2ApiDistinguishesSharedSubscriptionAndUnlimitedPlan() {
        replies["/site/v1/usage"] = Reply(200, """{"subscription":{},"remaining":12.5,"unit":"USD","planName":"Pro"}""")
        assertEquals("12.5", sub().remaining!!.toPlainString())
        replies["/site/v1/usage"] = Reply(200, """{"subscription":{},"remaining":-1,"planName":"Pro"}""")
        val unlimited = sub()
        assertEquals(ChannelQuotaStatus.UNLIMITED, unlimited.status)
        assertNull(unlimited.remaining)
        assertEquals("Pro", unlimited.planName)
        replies["/site/v1/usage"] = Reply(200, """{"isValid":false,"balance":100}""")
        assertEquals(ChannelQuotaStatus.AUTHENTICATION_FAILED, sub().status)
    }

    @Test
    fun newApiConvertsOnlyUserQuotaAndNeverUsesTokenUnlimitedFlag() {
        replies["/site/api/usage/token/"] = Reply(200, """{"code":true,"data":{"total_available":1,"unlimited_quota":true,"user_quota":{"total_available":1000000,"total_used":500000}}}""")
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"quota_display_type":"USD"}}""")
        val result = newApi()
        assertEquals(ChannelQuotaStatus.AVAILABLE, result.status)
        assertEquals("2.00000000", result.remaining!!.toPlainString())
        assertEquals("1.00000000", result.used!!.toPlainString())
        assertEquals("USD", result.unit)
        assertEquals(listOf("/site/api/usage/token/" to "Bearer test-secret", "/site/api/status" to null), requests.toList())
    }

    @Test
    fun newApiRequiresExposedAccountQuotaEvenIfTokenHasAvailableBalance() {
        replies["/site/api/usage/token/"] = Reply(200, """{"code":true,"data":{"total_available":1000000,"unlimited_quota":true}}""")
        val result = newApi()
        assertEquals(ChannelQuotaStatus.UNSUPPORTED, result.status)
        assertNull(result.remaining)
        assertTrue(result.message!!.contains("系统访问令牌"))
        assertEquals(1, requests.size)
    }

    @Test
    fun newApiSupportsCurrencyRatesAndKeepsUnknownConversionsAsRawQuota() {
        replies["/site/api/usage/token/"] = Reply(200, """{"data":{"user_quota":{"total_available":1000000}}}""")
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"quota_display_type":"CNY","usd_exchange_rate":7.2}}""")
        assertEquals("14.40000000", newApi().remaining!!.toPlainString())
        assertEquals("CNY", newApi().unit)
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"quota_display_type":"CUSTOM","custom_currency_exchange_rate":2,"custom_currency_symbol":"credits"}}""")
        assertEquals("4.00000000", newApi().remaining!!.toPlainString())
        assertEquals("credits", newApi().unit)
        replies["/site/api/status"] = Reply(503, "private error test-secret")
        val raw = newApi()
        assertEquals("1000000", raw.remaining!!.toPlainString())
        assertEquals("quota", raw.unit)
        assertTrue(raw.message!!.contains("原始"))
    }

    @Test
    fun newApiSupportsLegacyDollarDisplayWithoutGuessingUnknownCurrencies() {
        replies["/site/api/user/self"] = Reply(200, """{"success":true,"data":{"id":123,"quota":1000000,"used_quota":500000}}""")
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"display_in_currency":true,"usd_exchange_rate":7.12,"price":7.3}}""")
        val dollars = system()
        assertEquals("2.00000000", dollars.remaining!!.toPlainString())
        assertEquals("1.00000000", dollars.used!!.toPlainString())
        assertEquals("USD", dollars.unit)
        for (status in listOf(
            """{"quota_per_unit":500000,"display_in_currency":false}""",
            """{"quota_per_unit":500000}""",
            """{"quota_per_unit":0,"display_in_currency":true}""",
            """{"quota_per_unit":500000,"display_in_currency":true,"quota_display_type":"POINTS"}""",
        )) {
            replies["/site/api/status"] = Reply(200, "{\"data\":$status}")
            val raw = system()
            assertEquals("1000000", raw.remaining!!.toPlainString())
            assertEquals("quota", raw.unit)
        }
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"display_in_currency":true,"quota_display_type":"CNY","usd_exchange_rate":7.2}}""")
        val yuan = system()
        assertEquals("14.40000000", yuan.remaining!!.toPlainString())
        assertEquals("CNY", yuan.unit)
    }

    @Test
    fun newApiProxyRoutesAccountAndStatusRequestsWithSeparateHeaders() {
        replies["/site/api/user/self"] = Reply(200, """{"success":true,"data":{"id":123,"quota":1000000}}""")
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"display_in_currency":true}}""")
        NewApiQuotaProvider().use { provider ->
            val result = provider.query(query().copy(
                baseUrl = base + "/site/v1",
                credential = "system-secret",
                configJson = """{"authMode":"system","userId":"123","proxyUrl":"$base"}""",
            ))
            assertEquals(ChannelQuotaStatus.AVAILABLE, result.status)
            assertEquals("2.00000000", result.remaining!!.toPlainString())
            assertEquals("USD", result.unit)
        }
        assertEquals(listOf(base + "/site/api/user/self", base + "/site/api/status"), requestUris.toList())
        assertEquals(listOf("/site/api/user/self" to "Bearer system-secret", "/site/api/status" to null), requests.toList())
        assertEquals(listOf("/site/api/user/self" to "123", "/site/api/status" to null), userHeaders.toList())
    }

    @Test
    fun sharedProviderKeepsConcurrentProxyAndDirectQueriesIsolated() {
        replies["/site/v1/usage"] = Reply(200, """{"balance":12,"unit":"USD"}""")
        replies["/direct/v1/usage"] = Reply(200, """{"balance":34,"unit":"USD"}""")
        Sub2ApiQuotaProvider().use { provider ->
            val proxied = CompletableFuture.supplyAsync { provider.query(query().copy(
                baseUrl = base + "/site/v1",
                configJson = """{"proxyUrl":"$base"}""",
            )) }
            val direct = CompletableFuture.supplyAsync { provider.query(query().copy(
                baseUrl = base + "/direct/v1", credential = "direct-secret",
            )) }
            assertEquals("12", proxied.get(5, TimeUnit.SECONDS).remaining!!.toPlainString())
            assertEquals("34", direct.get(5, TimeUnit.SECONDS).remaining!!.toPlainString())
        }
        assertEquals(setOf(base + "/site/v1/usage", "/direct/v1/usage"), requestUris.toSet())
        assertEquals(setOf("/site/v1/usage" to "Bearer test-secret", "/direct/v1/usage" to "Bearer direct-secret"), requests.toSet())
    }

    @Test
    fun invalidProxyConfigurationsFailBeforeSendingCredentials() {
        val invalid = listOf(
            "https://127.0.0.1:7897", "socks5://127.0.0.1:7897",
            "http://user:private-proxy-secret@127.0.0.1:7897",
            "http://127.0.0.1:7897/path", "http://127.0.0.1:7897?token=private-proxy-secret",
            "http://127.0.0.1:7897#fragment", "http://127.0.0.1:0", "http://127.0.0.1:65536",
            "http:///missing-host", "127.0.0.1:7897",
        ).map { JsonPrimitive(it).toString() } + listOf("123", "true", "[]", "{}")
        Sub2ApiQuotaProvider().use { provider ->
            for (proxy in invalid) {
                val result = provider.query(query().copy(configJson = "{\"proxyUrl\":$proxy}"))
                assertEquals(ChannelQuotaStatus.ERROR, result.status)
                assertTrue(result.message!!.contains("代理配置无效"))
                assertFalse(result.toString().contains("private-proxy-secret"))
                assertFalse(result.toString().contains("test-secret"))
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun quotaTargetsRequireHttpsExceptForIpv4Loopback() {
        val provider = object : HttpQuotaProvider() {
            override val id = "target-validation-test"
            override val displayName = "Target validation test"
            fun resolve(request: ChannelQuotaQuery, config: JsonObject): String = siteBase(request, config)
            override fun queryRemote(
                request: ChannelQuotaQuery,
                config: JsonObject,
                client: HttpClient,
            ): ChannelQuotaResult = ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE, message = siteBase(request, config))
        }
        try {
            assertTrue(provider.resolve(query(), JsonObject(emptyMap())).startsWith("http://127.0.0.1:"))
            assertEquals("https://quota-origin.invalid/site", provider.resolve(
                query().copy(baseUrl = "https://quota-origin.invalid/site/v1"), JsonObject(emptyMap()),
            ))
            assertFails { provider.resolve(query().copy(baseUrl = "http://quota-origin.invalid/site/v1"), JsonObject(emptyMap())) }
        } finally {
            provider.close()
        }
    }

    @Test
    fun emptyProxyKeepsDirectBehaviorAndProxyDoesNotFollowRedirects() {
        replies["/site/v1/usage"] = Reply(200, """{"balance":12}""")
        Sub2ApiQuotaProvider().use { provider ->
            for (proxy in listOf("null", "\"\"", "\" \"")) {
                val result = provider.query(query().copy(configJson = "{\"proxyUrl\":$proxy}"))
                assertEquals(ChannelQuotaStatus.AVAILABLE, result.status)
            }
            assertTrue(requestUris.all { it == "/site/v1/usage" })
            requests.clear()
            requestUris.clear()
            replies["/site/v1/usage"] = Reply(302, "private test-secret", mapOf("Location" to "http://other.invalid/stolen"))
            val result = provider.query(query().copy(
                baseUrl = base + "/site/v1",
                configJson = """{"proxyUrl":"$base"}""",
            ))
            assertEquals(ChannelQuotaStatus.ERROR, result.status)
            assertFalse(result.toString().contains("test-secret"))
            assertEquals(listOf(base + "/site/v1/usage"), requestUris.toList())
        }
    }

    @Test
    fun mapsHttpFailuresWithoutLeakingResponseBody() {
        for ((code, expected) in listOf(401 to ChannelQuotaStatus.AUTHENTICATION_FAILED,
            403 to ChannelQuotaStatus.AUTHENTICATION_FAILED, 404 to ChannelQuotaStatus.UNSUPPORTED,
            429 to ChannelQuotaStatus.RATE_LIMITED, 500 to ChannelQuotaStatus.ERROR)) {
            replies["/site/v1/usage"] = Reply(code, "private error test-secret")
            val result = sub()
            assertEquals(expected, result.status)
            assertNull(result.remaining)
            assertFalse(result.message.orEmpty().contains("test-secret"))
        }
    }

    @Test
    fun rejectsMalformedOrOversizedResponsesRatherThanShowingZero() {
        for (body in listOf("<html>login</html>", "[]", """{"balance":"oops"}""",
            """{"balance":1e999999}""", """{"balance":{},"subscription":{},"remaining":10}""",
            """{"balance":0,"isValid":"false"}""", " ".repeat(1_048_577))) {
            replies["/site/v1/usage"] = Reply(200, body)
            val result = sub()
            assertEquals(ChannelQuotaStatus.ERROR, result.status)
            assertNull(result.remaining)
        }
        replies["/site/api/usage/token/"] = Reply(200, """{"code":false,"data":{"user_quota":{"total_available":10}}}""")
        assertEquals(ChannelQuotaStatus.ERROR, newApi().status)
    }

    @Test
    fun doesNotFollowRedirectsOrSendCredentialsToAnotherOrigin() {
        replies["/site/v1/usage"] = Reply(302, "redirect", mapOf("Location" to base + "/stolen"))
        assertEquals(ChannelQuotaStatus.ERROR, sub().status)
        assertEquals(listOf("/site/v1/usage"), requests.map { it.first })
        requests.clear()
        val request = query().copy(configJson = """{"baseUrl":"https://other.example"}""")
        Sub2ApiQuotaProvider().use { assertEquals(ChannelQuotaStatus.ERROR, it.query(request).status) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun supportsSameOriginSitePathOverride() {
        replies["/custom/v1/usage"] = Reply(200, """{"balance":33,"unit":"USD"}""")
        Sub2ApiQuotaProvider().use {
            val request = query().copy(configJson = "{\"baseUrl\":\"" + base + "/custom/\"}")
            assertEquals("33", it.query(request).remaining!!.toPlainString())
        }
        assertEquals("/custom/v1/usage", requests.single().first)
    }

    @Test
    fun newApiSystemTokenQueriesTheMatchingAccountWithBothHeaders() {
        replies["/site/api/user/self"] = Reply(200, """{"success":true,"data":{"id":123,"quota":1000000,"used_quota":500000}}""")
        replies["/site/api/status"] = Reply(200, """{"data":{"quota_per_unit":500000,"quota_display_type":"USD"}}""")
        val result = system()
        assertEquals(ChannelQuotaStatus.AVAILABLE, result.status)
        assertEquals("2.00000000", result.remaining!!.toPlainString())
        assertEquals("1.00000000", result.used!!.toPlainString())
        assertEquals("USD", result.unit)
        assertEquals(listOf("/site/api/user/self" to "Bearer system-secret", "/site/api/status" to null), requests.toList())
        assertEquals(listOf("/site/api/user/self" to "123", "/site/api/status" to null), userHeaders.toList())
    }

    @Test
    fun newApiSystemTokenPreservesNegativeAndZeroBalanceWithoutCurrencyGuessing() {
        for (quota in listOf("0", "-123")) {
            replies["/site/api/user/self"] = Reply(200, """{"success":true,"data":{"id":123,"quota":$quota}}""")
            val result = system()
            assertEquals(ChannelQuotaStatus.AVAILABLE, result.status)
            assertEquals(quota, result.remaining!!.toPlainString())
            assertEquals("quota", result.unit)
        }
    }

    @Test
    fun newApiSystemTokenRejectsMissingInvalidAndMismatchedUserIds() {
        for (config in listOf("{}", "{\"userId\":0}", "{\"userId\":-1}", "{\"userId\":1.5}",
            "{\"userId\":true}", "{\"userId\":[]}", "{\"userId\":\"123\\r\\nInjected\"}")) {
            val extra = config.removePrefix("{").removeSuffix("}")
            assertEquals(ChannelQuotaStatus.ERROR, system("{\"authMode\":\"system\"" + if (extra.isEmpty()) "}" else ",$extra}").status)
        }
        assertTrue(requests.isEmpty())
        replies["/site/api/user/self"] = Reply(200, """{"success":true,"data":{"id":999,"quota":1000000}}""")
        val mismatch = system()
        assertEquals(ChannelQuotaStatus.AUTHENTICATION_FAILED, mismatch.status)
        assertNull(mismatch.remaining)
        assertEquals(1, requests.size)
    }

    @Test
    fun newApiSystemTokenDoesNotExposeUpstreamErrorsOrUseTokenQuotaAsFallback() {
        for (reply in listOf(
            Reply(403, "system-secret private error"),
            Reply(200, """{"success":false,"message":"system-secret private error"}"""),
            Reply(200, """{"success":true,"data":{"id":123,"total_available":1000000}}"""),
        )) {
            replies["/site/api/user/self"] = reply
            val result = system()
            assertNull(result.remaining)
            assertFalse(result.toString().contains("system-secret"))
        }
        assertTrue(requests.all { it.first == "/site/api/user/self" })
    }

    private fun sub() = Sub2ApiQuotaProvider().use { it.query(query()) }
    private fun newApi() = NewApiQuotaProvider().use { it.query(query()) }
    private fun system(config: String = """{"authMode":"system","userId":"123"}""") = NewApiQuotaProvider().use {
        it.query(query().copy(credential = "system-secret", configJson = config))
    }
    private fun query() = ChannelQuotaQuery("channel", base + "/site/v1", "bearer", "test-secret")
    private data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
}
