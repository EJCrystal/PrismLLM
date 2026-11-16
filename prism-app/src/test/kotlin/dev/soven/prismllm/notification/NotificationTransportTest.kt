package dev.soven.prismllm.notification

import dev.soven.prismllm.admin.domain.NotificationEvent
import dev.soven.prismllm.admin.domain.NotificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@Tag("unit")
class NotificationTransportTest {
    private val prefix = "PRISM_NOTIFY_TEST"
    private val publicIpv4 = address("93.184.216.34")

    @Test
    fun `credential status exposes configured flags only and prefix follows exact contract`() {
        val bearer = generatedSecret()
        val signing = generatedSecret()
        val values = mapOf(
            "${prefix}_URL" to "https://hooks.example.test/path",
            "${prefix}_BEARER_TOKEN" to bearer,
            "${prefix}_SIGNING_SECRET" to signing,
        )
        val status = NotificationSecrets(values::get).status(prefix)

        assertTrue(status.urlConfigured)
        assertTrue(status.bearerTokenConfigured)
        assertTrue(status.signingSecretConfigured)
        assertTrue(NotificationSecrets.isValidPrefix("PRISM_NOTIFY_A"))
        assertTrue(NotificationSecrets.isValidPrefix("PRISM_NOTIFY_A_2"))
        assertTrue(NotificationSecrets.isValidPrefix("PRISM_NOTIFY_${"A".repeat(48)}"))
        assertFalse(NotificationSecrets.isValidPrefix("prism_NOTIFY_A"))
        assertFalse(NotificationSecrets.isValidPrefix("PRISM_NOTIFY_"))
        assertFalse(NotificationSecrets.isValidPrefix("PRISM_NOTIFY_${"A".repeat(49)}"))

        val invalidStatus = NotificationSecrets { error("invalid prefix must not read environment") }
            .status("PRISM_NOTIFY_BAD-NAME")
        assertFalse(invalidStatus.urlConfigured)
        assertFalse(invalidStatus.bearerTokenConfigured)
        assertFalse(invalidStatus.signingSecretConfigured)
        assertNotEquals(bearer, status.toString())
        assertNotEquals(signing, status.toString())
    }

    @Test
    fun `URL policy blocks local special numeric and reserved IPv6 destinations`() {
        val credential = generatedSecret()
        val blocked = listOf(
            "http://localhost/", "http://service.local/", "http://127.0.0.1/",
            "http://10.1.2.3/", "http://100.64.0.1/", "http://169.254.169.254/",
            "http://172.31.4.2/", "http://192.0.2.10/", "http://192.175.48.1/",
            "http://198.18.0.1/", "http://224.0.0.1/", "http://240.0.0.1/",
            "http://2130706433/", "http://0177.0.0.1/", "http://0x7f000001/",
            "http://$credential@example.com/", "https://example.com/path#$credential", "file://example.com/path",
            "http://[::1]/", "http://[fc00::1]/", "http://[fe80::1]/", "http://[ff02::1]/",
            "http://[::ffff:7f00:1]/", "http://[2001:db8::1]/", "http://[2001::1]/",
            "http://[2002:0808:0808::1]/", "http://[2620:4f:8000::1]/", "http://[3fff::1]/",
        )
        blocked.forEach { value -> assertFailsWith<NotificationUrlRejected>(value) { NotificationUrlPolicy.parse(value) } }
        assertEquals("https", NotificationUrlPolicy.parse("https://hooks.example.test/path").scheme)
        assertEquals("2606:4700:4700::1111", NotificationUrlPolicy.parse("https://[2606:4700:4700::1111]/").host)
    }

    @Test
    fun `public address check rejects special IPv4 and scoped or mapped IPv6`() {
        val blocked = listOf(
            "0.1.2.3", "10.2.3.4", "100.64.0.1", "127.1.2.3", "169.254.1.2",
            "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "192.168.1.2",
            "192.175.48.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
            "::", "::1", "::ffff:7f00:1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2620:4f:8000::1",
        )
        blocked.forEach { assertFalse(NotificationUrlPolicy.isPublicAddress(address(it)), it) }
        val scoped = Inet6Address.getByAddress(null, address("2606:4700:4700::1111").address, 2)
        assertFalse(NotificationUrlPolicy.isPublicAddress(scoped))
        assertTrue(NotificationUrlPolicy.isPublicAddress(publicIpv4))
        assertTrue(NotificationUrlPolicy.isPublicAddress(address("2606:4700:4700::1111")))
    }

    @Test
    fun `mixed DNS answers are rejected and validated addresses remain pinned after resolver changes`() = runBlocking<Unit> {
        val url = NotificationUrlPolicy.parse("https://hooks.example.test/notify")
        val privateAddress = address("10.0.0.7")
        assertFailsWith<NotificationUrlRejected> {
            NotificationUrlPolicy.resolve(url, NotificationDnsResolver { listOf(publicIpv4, privateAddress) })
        }

        val backing = mutableListOf(publicIpv4)
        val pinned = NotificationUrlPolicy.resolve(url, NotificationDnsResolver { backing })
        backing[0] = privateAddress
        assertEquals(publicIpv4, pinned.lookup(url.host).single())
        assertFailsWith<UnknownHostException> { pinned.lookup("other.example.test") }
    }

    @Test
    fun `webhook uses generated credentials and signs exact timestamp and serialized body`() = runBlocking {
        val bearer = generatedSecret()
        val signing = generatedSecret()
        val now = 1_800_000_123L
        val capturedHeaders = linkedMapOf<String, String>()
        var capturedBody = ""
        var resolverCalls = 0
        val env = mapOf(
            "${prefix}_URL" to "https://hooks.example.test/notify?key=${generatedSecret()}",
            "${prefix}_BEARER_TOKEN" to bearer,
            "${prefix}_SIGNING_SECRET" to signing,
        )
        val http = NotificationHttpExecutor { url, dns, headers, body ->
            assertEquals("hooks.example.test", url.host)
            assertEquals(publicIpv4, dns.lookup(url.host).single())
            capturedHeaders.putAll(headers)
            capturedBody = body.toString(Charsets.UTF_8)
            NotificationHttpResponse(204, null, ByteArray(0), false)
        }
        val transport = SafeNotificationTransport(
            NotificationSecrets(env::get), NotificationDnsResolver { resolverCalls++; listOf(publicIpv4) }, http, { now },
        )
        val event = event()
        val result = transport.send(target("webhook"), event)
        val json = Json.parseToJsonElement(capturedBody).jsonObject

        assertTrue(result.success)
        assertFalse(result.retryable)
        assertEquals(204, result.statusCode)
        assertEquals(1, resolverCalls)
        assertEquals("Bearer $bearer", capturedHeaders["Authorization"])
        assertEquals(event.eventId, capturedHeaders["X-Prism-Event-Id"])
        assertEquals(now.toString(), capturedHeaders["X-Prism-Timestamp"])
        assertEquals(independentWebhookSignature(signing, now, capturedBody), capturedHeaders["X-Prism-Signature"])
        assertEquals("1", json.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(event.eventId, json.getValue("eventId").jsonPrimitive.content)
    }

    @Test
    fun `Feishu body includes readable event ID signature and rejects business error`() = runBlocking {
        val signing = generatedSecret()
        val now = 1_800_000_456L
        var capturedBody = ""
        val env = mapOf("${prefix}_URL" to "https://hooks.example.test/feishu", "${prefix}_SIGNING_SECRET" to signing)
        val transport = SafeNotificationTransport(
            NotificationSecrets(env::get), NotificationDnsResolver { listOf(publicIpv4) },
            NotificationHttpExecutor { _, _, _, body ->
                capturedBody = body.toString(Charsets.UTF_8)
                NotificationHttpResponse(200, null, """{"code":19001,"msg":"rejected"}""".toByteArray(), false)
            }, { now },
        )
        val event = event().copy(channelName = "core\nchannel")
        val result = transport.send(target("feishu"), event)
        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        val text = payload.getValue("content").jsonObject.getValue("text").jsonPrimitive.content

        assertFalse(result.success)
        assertFalse(result.retryable)
        assertEquals(200, result.statusCode)
        assertEquals("feishu_rejected", result.errorCode)
        assertEquals("text", payload.getValue("msg_type").jsonPrimitive.content)
        assertTrue(text.contains("渠道异常"))
        assertTrue(text.contains("警告"))
        assertTrue(text.contains("上游请求超时"))
        assertTrue(text.contains(event.eventId))
        assertTrue(text.contains("core channel"))
        assertTrue(text.contains("2027-01-15T08:00:00Z"))
        assertEquals(now.toString(), payload.getValue("timestamp").jsonPrimitive.content)
        assertEquals(independentFeishuSignature(signing, now), payload.getValue("sign").jsonPrimitive.content)
    }

    @Test
    fun `Feishu only accepts numeric zero code`() = runBlocking {
        listOf("""{"code":"0"}""", """{"code":false}""", """{"code":1}""", "not-json").forEach { body ->
            val result = transport(
                mapOf("${prefix}_URL" to "https://hooks.example.test/notify"),
                NotificationHttpExecutor { _, _, _, _ -> NotificationHttpResponse(200, null, body.toByteArray(), false) },
            ).send(target("feishu"), event())
            assertFalse(result.success, body)
            assertEquals("feishu_rejected", result.errorCode)
        }
        val accepted = transport(
            mapOf("${prefix}_URL" to "https://hooks.example.test/notify"),
            NotificationHttpExecutor { _, _, _, _ -> NotificationHttpResponse(200, null, """{"code":0}""".toByteArray(), false) },
        ).send(target("feishu"), event())
        assertTrue(accepted.success)
    }

    @Test
    fun `rate limit and network failures are retryable with capped retry delay`() = runBlocking {
        val env = mapOf("${prefix}_URL" to "https://hooks.example.test/notify")
        val limited = transport(env, NotificationHttpExecutor { _, _, _, _ ->
            NotificationHttpResponse(429, "999999999999999999999999", ByteArray(0), false)
        }).send(target("webhook"), event())
        assertFalse(limited.success)
        assertTrue(limited.retryable)
        assertEquals(60_000L, limited.retryAfterMs)
        assertEquals("rate_limited", limited.errorCode)

        val network = transport(env, NotificationHttpExecutor { _, _, _, _ -> throw IOException() })
            .send(target("webhook"), event())
        assertFalse(network.success)
        assertTrue(network.retryable)
        assertEquals("network_error", network.errorCode)
    }

    @Test
    fun `invalid URLs and missing configuration are permanent without network access`() = runBlocking {
        var dnsCalls = 0
        var httpCalls = 0
        val badUrlTransport = SafeNotificationTransport(
            NotificationSecrets { name -> if (name == "${prefix}_URL") "http://127.0.0.1/" else null },
            NotificationDnsResolver { dnsCalls++; listOf(publicIpv4) },
            NotificationHttpExecutor { _, _, _, _ -> httpCalls++; NotificationHttpResponse(200, null, ByteArray(0), false) },
        )
        val badUrl = badUrlTransport.send(target("webhook"), event())
        assertFalse(badUrl.success)
        assertFalse(badUrl.retryable)
        assertEquals("unsafe_url", badUrl.errorCode)
        val missing = transport(emptyMap(), NotificationHttpExecutor { _, _, _, _ ->
            httpCalls++
            NotificationHttpResponse(200, null, ByteArray(0), false)
        }).send(target("webhook"), event())
        assertFalse(missing.success)
        assertFalse(missing.retryable)
        assertEquals("missing_configuration", missing.errorCode)
        assertEquals(0, dnsCalls)
        assertEquals(0, httpCalls)
    }

    @Test
    fun `OkHttp isolates DNS and pools disables proxy redirects retries and sends one-shot bodies`() = runBlocking {
        val clients = mutableListOf<OkHttpClient>()
        val requests = mutableListOf<Request>()
        val url = "https://hooks.example.test/notify".toHttpUrl()
        val dns = NotificationPinnedDns(url.host, listOf(publicIpv4))
        val executor = OkHttpNotificationExecutor { client, request ->
            clients += client
            requests += request
            ImmediateCall(request) {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302)
                    .message("redirect").header("Location", "http://127.0.0.1/blocked")
                    .body(ByteArray(0).toResponseBody()).build()
            }
        }
        try {
            repeat(2) {
                val response = executor.post(url, dns, emptyMap(), "{}".toByteArray())
                assertEquals(302, response.statusCode)
            }
            assertEquals(2, requests.size)
            clients.forEach { client ->
                assertEquals(Proxy.NO_PROXY, client.proxy)
                assertFalse(client.followRedirects)
                assertFalse(client.followSslRedirects)
                assertFalse(client.retryOnConnectionFailure)
                assertEquals(3_000, client.connectTimeoutMillis)
                assertEquals(10_000, client.callTimeoutMillis)
                assertEquals(10_000, client.readTimeoutMillis)
                assertEquals(10_000, client.writeTimeoutMillis)
                assertEquals(publicIpv4, client.dns.lookup(url.host).single())
                assertTrue(client.dispatcher.executorService.isShutdown)
            }
            requests.forEach { assertTrue(it.body!!.isOneShot()) }
            assertNotEquals(clients[0].connectionPool, clients[1].connectionPool)
        } finally {
            executor.close()
        }
    }

    @Test
    fun `closing transport cancels an in-progress OkHttp call`() = runBlocking {
        val callbackSeen = CompletableDeferred<Callback>()
        val cancelCount = AtomicInteger(0)
        val requestSeen = CompletableDeferred<Request>()
        val executor = OkHttpNotificationExecutor { _, request ->
            requestSeen.complete(request)
            FakeCall(request, callbackSeen, cancelCount)
        }
        val transport = SafeNotificationTransport(
            NotificationSecrets { name -> if (name == "${prefix}_URL") "https://hooks.example.test/notify" else null },
            NotificationDnsResolver { listOf(publicIpv4) },
            executor,
        )
        val send = async { transport.send(target("webhook"), event()) }
        callbackSeen.await()
        requestSeen.await()

        try {
            transport.close()
            withTimeout(5_000L) { send.join() }

            assertTrue(send.isCancelled)
            assertTrue(cancelCount.get() > 0)
        } finally {
            send.cancelAndJoin()
        }
    }

    private fun transport(env: Map<String, String>, http: NotificationHttpExecutor) = SafeNotificationTransport(
        NotificationSecrets(env::get), NotificationDnsResolver { listOf(publicIpv4) }, http, { 1_800_000_000L },
    )

    private fun target(type: String) = NotificationTarget(
        id = "target-id", name = "test target", type = type, environmentPrefix = prefix,
    )

    private fun event() = NotificationEvent(
        eventId = "event-generated-id",
        eventType = "channel.unhealthy",
        occurredAt = 1_800_000_000_000L,
        severity = "warning",
        channelId = "channel-id",
        channelName = "Primary",
        reasonCode = "upstream_timeout",
    )

    private fun generatedSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    private fun independentWebhookSignature(secret: String, timestamp: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun independentFeishuSignature(secret: String, timestamp: Long): String {
        val key = "$timestamp\n$secret".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0)))
    }

    private class ImmediateCall(
        private val requestValue: Request,
        private val response: () -> Response,
    ) : Call by OkHttpClient().newCall(requestValue) {
        private var executed = false
        private var cancelled = false
        override fun request(): Request = requestValue
        override fun execute(): Response = error("synchronous calls are forbidden in these tests")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            responseCallback.onResponse(this, response())
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ImmediateCall(requestValue, response)
    }

    private class FakeCall(
        private val requestValue: Request,
        private val callbackSeen: CompletableDeferred<Callback>,
        private val cancelCount: AtomicInteger,
    ) : Call by OkHttpClient().newCall(requestValue) {
        private var executed = false
        private var cancelled = false

        override fun request(): Request = requestValue
        override fun execute(): Response = error("not used")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            callbackSeen.complete(responseCallback)
        }
        override fun cancel() { cancelled = true; cancelCount.incrementAndGet() }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(requestValue, callbackSeen, cancelCount)
    }
}
