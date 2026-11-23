package dev.soven.prismllm.integration

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.admin.domain.WebSearchSettingsRecord
import dev.soven.prismllm.api.ChannelProxyHostAllowlist
import dev.soven.prismllm.api.parseChannelProxy
import dev.soven.prismllm.api.withUpstreamClient
import dev.soven.prismllm.api.normalizeChannelProxies
import dev.soven.prismllm.api.ChannelEgressProxyRequest
import dev.soven.prismllm.api.ChannelRequest
import dev.soven.prismllm.api.ChannelKeyRequest
import dev.soven.prismllm.api.ChannelModelMappingRequest
import dev.soven.prismllm.fake.RecordingSocks5Proxy
import dev.soven.prismllm.channel.domain.ChannelEgressProxy
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.channel.persistence.Channels
import dev.soven.prismllm.channel.domain.ChannelEgressConfigurationException
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.net.URI
import java.net.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.Base64
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@Tag("integration")
@Tag("shard-4")
@Tag("channel")
class ChannelEgressIntegrationTest : GatewayIntegrationTestBase() {
    private class RecordingProxy(private val failing: Boolean = false, username: String? = null, password: String? = null) : AutoCloseable {
        val requests = ConcurrentLinkedQueue<String>()
        private val authentication = username?.let { "Basic " + Base64.getEncoder().encodeToString((it + ":" + password.orEmpty()).toByteArray(Charsets.UTF_8)) }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                if (authentication != null && exchange.requestHeaders.getFirst("Proxy-Authorization") != authentication) {
                    exchange.responseHeaders.set("Proxy-Authenticate", "Basic realm=\"proxy\"")
                    exchange.sendResponseHeaders(407, -1)
                    exchange.close()
                    return@createContext
                }
                requests.add(exchange.requestMethod + " " + exchange.requestURI + " " + body)
                val streaming = body.contains("\"stream\":true")
                val response = when {
                    failing -> """{"error":{"message":"proxy unavailable","type":"api_error"}}"""
                    exchange.requestMethod == "GET" -> """{"object":"list","data":[{"id":"m1","object":"model"}]}"""
                    streaming -> "data: {\"id\":\"proxy\",\"object\":\"chat.completion.chunk\",\"model\":\"m1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"proxied\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
                    else -> """{"id":"proxy","object":"chat.completion","model":"m1","choices":[{"index":0,"message":{"role":"assistant","content":"proxied"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                }
                val bytes = response.toByteArray()
                exchange.responseHeaders.set("Content-Type", if (streaming && !failing) "text/event-stream" else "application/json")
                exchange.sendResponseHeaders(if (failing) 502 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private suspend fun channel(
        proxies: List<ChannelEgressProxy>,
        bindings: List<String?>,
        baseUrl: String = "${fake.baseUrl}/ok/v1",
    ): ChannelRecord = app.channelRepository.create(
        name = "egress-channel",
        baseUrl = baseUrl,
        keys = bindings.mapIndexed { index, proxyId ->
            NewChannelKey(
                name = "key-$index",
                credential = app.credentialCipher.encrypt("secret-$index"),
                models = listOf("m${index + 1}"),
                egressProxyId = proxyId,
            )
        },
        authType = "bearer",
        protocols = listOf(GatewayProtocol.CHAT_COMPLETIONS),
        enabled = true,
        modelMappings = mappingsOf(*bindings.indices.map { "m${it + 1}" }.toTypedArray()),
        egressProxies = proxies,
    )

    @Test
    fun `saved authenticated proxy credentials are reused by draft discovery without exposing passwords`() = runBlocking {
        val password = "saved-proxy-secret"
        RecordingSocks5Proxy(username = "proxy-user", password = password).use { proxy ->
            val endpoint = normalizeChannelProxies(app, listOf(ChannelEgressProxyRequest("auth", "认证代理", proxy.url, "proxy-user", password))).single()
            val port = URI(fake.baseUrl).port
            val channel = channel(listOf(endpoint), listOf("auth"), "http://socks-target.invalid:$port/ok/v1")
            val session = app.sessions.create("admin")
            testApplication {
                application { app.install(this) }
                val response = client.post("/admin/api/channels/model-discovery") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}","egressProxy":{"id":"auth","name":"认证代理","url":"${proxy.url}","username":"proxy-user"}}""")
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertFalse(response.bodyAsText().contains(password))
                val changed = client.post("/admin/api/channels/model-discovery") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}","egressProxy":{"id":"auth","name":"认证代理","url":"socks5://127.0.0.1:1","username":"proxy-user"}}""")
                }
                assertEquals(HttpStatusCode.BadRequest, changed.status, changed.bodyAsText())
                assertTrue(changed.bodyAsText().contains("代理地址已变更"))
            }
            assertTrue(proxy.authenticatedUsers.isNotEmpty())
            assertTrue(fake.modelRequests.isNotEmpty())
        }
    }

    @Test
    fun `http proxy basic authentication works for streaming and non streaming calls`() = runBlocking {
        val password = " leading:p@ss#word "
        RecordingProxy(username = "proxy-user", password = password).use { proxy ->
            val endpoint = normalizeChannelProxies(app, listOf(ChannelEgressProxyRequest("auth", "认证代理", proxy.url, "proxy-user", password))).single()
            val channel = channel(listOf(endpoint), listOf("auth"))
            configureModelRoute("m1", channel to channel.keys.single())
            val key = createClientKey()
            testApplication {
                application { app.install(this) }
                for (stream in listOf(false, true)) {
                    val response = client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"m1","stream":$stream,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                }
            }
            assertEquals(2, proxy.requests.size)
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `authenticated socks5 forwards unicode credentials and resolves target domains at the proxy`() = runBlocking {
        val password = " leading:p@ss#word "
        RecordingSocks5Proxy(username = "住宅用户", password = password).use { proxy ->
            val endpoint = normalizeChannelProxies(app, listOf(ChannelEgressProxyRequest("auth", "认证代理", proxy.url, "住宅用户", password))).single()
            val port = URI(fake.baseUrl).port
            val channel = channel(listOf(endpoint), listOf("auth"), "http://socks-target.invalid:$port/ok/v1")
            configureModelRoute("m1", channel to channel.keys.single())
            val key = createClientKey()
            testApplication {
                application { app.install(this) }
                for (stream in listOf(false, true)) {
                    val response = client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"m1","stream":$stream,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                }
            }
            assertEquals(2, fake.requests.size)
            assertTrue(proxy.authenticatedUsers.isNotEmpty())
            assertTrue(proxy.authenticatedUsers.all { it == "住宅用户" })
            assertTrue(proxy.targets.all { it.host == "socks-target.invalid" && it.addressType == 3 })
            assertTrue(fake.requests.all { it.requestHeaders.keys.none { name -> name.equals("Proxy-Authorization", ignoreCase = true) } })
        }
    }

    private suspend fun assertProxyAuthenticationFailure(url: String, password: String? = "incorrect-password") {
        val endpoint = normalizeChannelProxies(app, listOf(ChannelEgressProxyRequest("auth", "认证代理", url, password?.let { "proxy-user" }, password))).single()
        val channel = channel(listOf(endpoint), listOf("auth"))
        configureModelRoute("m1", channel to channel.keys.single())
        val key = createClientKey()
        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"m1","stream":false,"messages":[{"role":"user","content":"hi"}]}""")
            }
            assertTrue(response.status.value in 400..599, response.bodyAsText())
            assertFalse(response.bodyAsText().contains("incorrect-password"))
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `http proxy authentication failure never falls back to direct`() = runBlocking {
        RecordingProxy(username = "proxy-user", password = "correct-password").use { assertProxyAuthenticationFailure(it.url) }
    }

    @Test
    fun `socks5 authentication failure never falls back to direct`() = runBlocking {
        RecordingSocks5Proxy(username = "proxy-user", password = "correct-password").use {
            assertProxyAuthenticationFailure(it.url)
            assertTrue(it.offeredMethods.isNotEmpty())
            assertTrue(it.offeredMethods.all { methods -> methods == listOf(2) })
            assertTrue(it.authenticatedUsers.isEmpty())
            assertTrue(it.targets.isEmpty())
        }
    }

    @Test
    fun `unauthenticated socks5 refuses proxies that require authentication`() = runBlocking {
        RecordingSocks5Proxy(username = "proxy-user", password = "test-password").use { proxy ->
            assertProxyAuthenticationFailure(proxy.url, password = null)
            assertTrue(proxy.offeredMethods.isNotEmpty())
            assertTrue(proxy.offeredMethods.all { it == listOf(0) })
            assertTrue(proxy.authenticatedUsers.isEmpty())
            assertTrue(proxy.targets.isEmpty())
        }
    }

    @Test
    fun `proxy passwords are encrypted never returned retained when omitted and explicitly clearable`() = runBlocking {
        val password = "proxy-password-not-for-output-489"
        val proxy = ChannelEgressProxyRequest("auth", "认证代理", "socks5://localhost:1080", "proxy-user", password)
        val request = ChannelRequest(
            name = "auth-storage", baseUrl = "${fake.baseUrl}/ok/v1",
            keys = listOf(ChannelKeyRequest(name = "key", credential = "upstream-secret", models = listOf("m1"))),
            modelMappings = listOf(ChannelModelMappingRequest("m1")), egressProxies = listOf(proxy),
        )
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            val created = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(request))
            }
            val body = created.bodyAsText()
            assertEquals(HttpStatusCode.Created, created.status, body)
            assertFalse(body.contains(password))
            assertFalse(body.contains("passwordCiphertext"))
            val result = Json.parseToJsonElement(body).jsonObject
            val proxyResult = result.getValue("egressProxies").jsonArray.single().jsonObject
            assertEquals("true", proxyResult.getValue("passwordSet").jsonPrimitive.content)
            assertFalse(proxyResult.containsKey("password"))
            assertFalse(proxyResult.containsKey("authentication"))
            val id = result.getValue("id").jsonPrimitive.content
            val stored = app.channelRepository.find(id)!!.egressProxies.single()
            val raw = app.channelRepository.inTransaction {
                Channels.select(Channels.egressProxies).where { Channels.id eq id }.single()[Channels.egressProxies]
            }
            assertFalse(raw.contains(password))
            assertFalse(stored.toString().contains(password))
            val baseUpdate = request.copy(keys = null, modelMappings = null)
            for (updated in listOf(baseUpdate.copy(egressProxies = null), baseUpdate.copy(egressProxies = listOf(proxy.copy(url = "socks5://LOCALHOST/", password = ""))))) {
                val response = client.put("/admin/api/channels/$id") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(updated))
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertFalse(response.bodyAsText().contains(password))
                assertEquals(stored.authentication, app.channelRepository.find(id)!!.egressProxies.single().authentication)
            }
            val saved = app.channelRepository.find(id)!!.egressProxies.single()
            for (url in listOf("http://localhost:1080", "socks5://127.0.0.1:1080", "socks5://localhost:1081")) {
                val update = baseUpdate.copy(egressProxies = listOf(proxy.copy(url = url, username = null, password = null)))
                val response = client.put("/admin/api/channels/$id") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(update))
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertTrue(response.bodyAsText().contains("代理地址已变更"))
                assertEquals(saved, app.channelRepository.find(id)!!.egressProxies.single())
            }
            assertFalse(app.adminAuditRepository.list(100).toString().contains(password))
            val cleared = client.put("/admin/api/channels/$id") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(baseUpdate.copy(egressProxies = listOf(proxy.copy(url = "socks5://localhost:1081", username = null, password = null, clearAuthentication = true)))))
            }
            assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
            assertEquals(null, app.channelRepository.find(id)!!.egressProxies.single().authentication)
        }
    }

    @Test
    fun `channel proxy clients close on success and failure without retaining credentials in the account cache`() = runBlocking {
        val request = ChannelEgressProxyRequest("auth", "认证代理", "socks5://127.0.0.1:1080", "proxy-user", "first-secret")
        val original = normalizeChannelProxies(app, listOf(request)).single()
        val changed = normalizeChannelProxies(app, listOf(request.copy(url = "socks5://127.0.0.1:1081", password = "second-secret")), listOf(original)).single()
        val first = withUpstreamClient(app, parseChannelProxy(app, original)) { it.coroutineContext.job to it.engine.coroutineContext.job }
        val second = withUpstreamClient(app, parseChannelProxy(app, changed)) { it.coroutineContext.job to it.engine.coroutineContext.job }
        assertNotSame(first.first, second.first)
        assertNotSame(first.second, second.second)
        lateinit var failed: Job
        lateinit var failedEngine: Job
        assertFailsWith<IllegalStateException> {
            withUpstreamClient(app, parseChannelProxy(app, changed)) { client ->
                failed = client.coroutineContext.job
                failedEngine = client.engine.coroutineContext.job
                error("test failure")
            }
        }
        lateinit var cancelled: Job
        lateinit var cancelledEngine: Job
        assertFailsWith<CancellationException> {
            withUpstreamClient(app, parseChannelProxy(app, original)) { client ->
                cancelled = client.coroutineContext.job
                cancelledEngine = client.engine.coroutineContext.job
                throw CancellationException("test cancellation")
            }
        }
        withTimeout(2_000) {
            listOf(first.first, first.second, second.first, second.second, failed, failedEngine, cancelled, cancelledEngine).forEach { it.join() }
        }
        assertTrue(app.accountEgressClients.isEmpty())
        assertFailsWith<ChannelEgressConfigurationException> { parseChannelProxy(app, original.copy(id = "different-id")) }
        assertFalse(request.toString().contains("first-secret"))
    }

    @Test
    fun `incomplete conflicting or oversized proxy authentication is rejected`() {
        val request = ChannelEgressProxyRequest("auth", "认证代理", "http://127.0.0.1:1080", "proxy-user", "secret")
        listOf(
            request.copy(password = null), request.copy(username = null), request.copy(username = ""),
            request.copy(username = "user:tenant"), request.copy(password = "密".repeat(86)),
            request.copy(username = "x".repeat(256)), request.copy(password = "bad\npassword"),
            request.copy(clearAuthentication = true),
        ).forEach { invalid ->
            assertFailsWith<ChannelEgressConfigurationException> { normalizeChannelProxies(app, listOf(invalid)) }
        }
    }

    @Test
    fun `socks5 forwards streaming and non streaming requests with proxy side dns`() = runBlocking {
        RecordingSocks5Proxy().use { proxy ->
            val port = URI(fake.baseUrl).port
            val channel = channel(listOf(ChannelEgressProxy("socks", "SOCKS5", proxy.url)), listOf("socks"), "http://socks-target.invalid:$port/ok/v1")
            configureModelRoute("m1", channel to channel.keys.single())
            val clientKey = createClientKey()
            testApplication {
                application { app.install(this) }
                for (stream in listOf(false, true)) {
                    val response = client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $clientKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"model":"m1","stream":$stream,"messages":[{"role":"user","content":"hi"}]}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                }
            }
            assertEquals(2, fake.requests.size)
            assertTrue(proxy.targets.isNotEmpty())
            assertTrue(proxy.targets.all { it.host == "socks-target.invalid" && it.port == port && it.addressType == 3 })
        }
    }

    @Test
    fun `socks5 is used by saved and draft model discovery and connectivity tests`() = runBlocking {
        RecordingSocks5Proxy().use { proxy ->
            val port = URI(fake.baseUrl).port
            val channel = channel(listOf(ChannelEgressProxy("socks", "SOCKS5", proxy.url)), listOf("socks"), "http://socks-target.invalid:$port/ok/v1")
            val session = app.sessions.create("admin")
            testApplication {
                application { app.install(this) }
                val models = client.get("/admin/api/channels/${channel.id}/models?keyId=${channel.keys.single().id}") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                }
                assertEquals(HttpStatusCode.OK, models.status, models.bodyAsText())
                for ((path, body) in listOf(
                    "/channels/model-discovery" to """{"baseUrl":"${channel.baseUrl}","credential":"draft-secret","egressProxy":{"id":"draft","name":"草稿 SOCKS5","url":"${proxy.url}"}}""",
                    "/channels/${channel.id}/model-test" to """{"model":"m1","protocol":"chat_completions"}""",
                    "/channels/${channel.id}/probe-protocols" to """{"model":"m1"}""",
                )) {
                    val result = client.post("/admin/api$path") {
                        header(HttpHeaders.Cookie, "gw_session=${session.token}")
                        header("X-CSRF-Token", session.session.csrf)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    assertEquals(HttpStatusCode.OK, result.status, result.bodyAsText())
                }
            }
            assertTrue(proxy.targets.isNotEmpty())
            assertTrue(proxy.targets.all { it.host == "socks-target.invalid" && it.addressType == 3 })
            assertTrue(fake.modelRequests.size >= 2)
        }
    }

    @Test
    fun `socks5 connection refusal never falls back to direct requests`() = runBlocking {
        RecordingSocks5Proxy(refusing = true).use { proxy ->
            val channel = channel(listOf(ChannelEgressProxy("socks", "SOCKS5", proxy.url)), listOf("socks"))
            configureModelRoute("m1", channel to channel.keys.single())
            val clientKey = createClientKey()
            testApplication {
                application { app.install(this) }
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $clientKey")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"m1","stream":false,"messages":[{"role":"user","content":"hi"}]}""")
                }
                assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
            }
            assertTrue(proxy.targets.isNotEmpty())
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `proxy endpoints preserve scheme and default ports without resolving hosts`() {
        val http = parseChannelProxy(app, "http://127.0.0.1:1080")
        val socks = parseChannelProxy(app, "socks5://127.0.0.1:1080")
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(Proxy.Type.SOCKS, socks.type())
        assertEquals(http.address(), socks.address())
        assertTrue((socks.address() as InetSocketAddress).isUnresolved)
        assertEquals(1080, (parseChannelProxy(app, "socks5://localhost").address() as InetSocketAddress).port)
        assertEquals(80, (parseChannelProxy(app, "http://localhost").address() as InetSocketAddress).port)
    }

    @Test
    fun `each key uses its assigned proxy for streaming and non streaming while unbound keys keep existing egress`() = runBlocking {
        RecordingProxy().use { first ->
            RecordingProxy().use { second ->
                val channel = channel(listOf(ChannelEgressProxy("us", "美国", first.url), ChannelEgressProxy("jp", "日本", second.url)), listOf("us", "jp", null))
                channel.keys.forEach { credential -> configureModelRoute(credential.models.single(), channel to credential) }
                val clientKey = createClientKey()
                testApplication {
                    application { app.install(this) }
                    for (model in listOf("m1", "m2", "m3")) {
                        for (stream in listOf(false, true)) {
                            val response = client.post("/v1/chat/completions") {
                                header(HttpHeaders.Authorization, "Bearer $clientKey")
                                contentType(ContentType.Application.Json)
                                setBody("""{"model":"$model","stream":$stream,"messages":[{"role":"user","content":"hi"}]}""")
                            }
                            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                        }
                    }
                }
                assertEquals(2, first.requests.size)
                assertEquals(2, second.requests.size)
                assertTrue(first.requests.all { it.contains("\"model\":\"m1\"") })
                assertTrue(second.requests.all { it.contains("\"model\":\"m2\"") })
                assertEquals(2, fake.requests.size)
            }
        }
    }

    @Test
    fun `saved proxy is used by model discovery connectivity tests and protocol probes`() = runBlocking {
        RecordingProxy().use { proxy ->
            val channel = channel(listOf(ChannelEgressProxy("us", "美国", proxy.url)), listOf("us"))
            val session = app.sessions.create("admin")
            testApplication {
                application { app.install(this) }
                val response = client.get("/admin/api/channels/${channel.id}/models?keyId=${channel.keys.single().id}") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                for ((path, body) in listOf(
                    "/channels/model-discovery" to """{"baseUrl":"${channel.baseUrl}","channelId":"${channel.id}","keyId":"${channel.keys.single().id}"}""",
                    "/channels/${channel.id}/model-test" to """{"model":"m1","protocol":"chat_completions"}""",
                    "/channels/${channel.id}/probe-protocols" to """{"model":"m1"}""",
                )) {
                    val result = client.post("/admin/api$path") {
                        header(HttpHeaders.Cookie, "gw_session=${session.token}")
                        header("X-CSRF-Token", session.session.csrf)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    assertEquals(HttpStatusCode.OK, result.status, result.bodyAsText())
                }
            }
            assertTrue(proxy.requests.count { it.startsWith("GET") } >= 2)
            assertTrue(proxy.requests.count { it.startsWith("POST") } >= 2)
            assertTrue(fake.requests.isEmpty())
            assertTrue(fake.modelRequests.isEmpty())
        }
    }

    @Test
    fun `proxy failure never falls back to direct requests`() = runBlocking {
        RecordingProxy(failing = true).use { proxy ->
            val channel = channel(listOf(ChannelEgressProxy("us", "美国", proxy.url)), listOf("us"))
            configureModelRoute("m1", channel to channel.keys.single())
            val clientKey = createClientKey()
            testApplication {
                application { app.install(this) }
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $clientKey")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"m1","stream":false,"messages":[{"role":"user","content":"hi"}]}""")
                }
                assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
            }
            assertTrue(proxy.requests.isNotEmpty())
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `responses web search orchestration uses the assigned key proxy`() = runBlocking {
        RecordingProxy().use { proxy ->
            val channel = channel(listOf(ChannelEgressProxy("us", "美国", proxy.url)), listOf("us"))
            configureModelRoute("m1", channel to channel.keys.single())
            val clientKey = createClientKey()
            app.webSearchSettingsRepository.save(WebSearchSettingsRecord(
                enabled = true, provider = "tavily", apiKey = "test-key", baseUrl = "http://127.0.0.1:1",
                maxResults = 3, timeoutMs = 2_000, maxRounds = 2, maxPageChars = 2_000, updatedAt = 0,
            ))
            testApplication {
                application { app.install(this) }
                val response = client.post("/v1/responses") {
                    header(HttpHeaders.Authorization, "Bearer $clientKey")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"m1","stream":false,"input":"hi","tools":[{"type":"web_search"}]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }
            assertTrue(proxy.requests.isNotEmpty())
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `admin updates preserve legacy bindings and reject deleting an assigned proxy atomically`() = runBlocking {
        val channel = channel(listOf(ChannelEgressProxy("us", "美国", "http://127.0.0.1:7897")), listOf("us", "us"))
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            for ((extra, expected) in listOf("" to HttpStatusCode.OK, ",\"egressProxies\":[]" to HttpStatusCode.BadRequest)) {
                val response = client.put("/admin/api/channels/${channel.id}") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"updated","baseUrl":"${channel.baseUrl}","enabled":true$extra}""")
                }
                assertEquals(expected, response.status, response.bodyAsText())
            }
            val saved = app.channelRepository.find(channel.id)!!
            assertEquals(channel.egressProxies, saved.egressProxies)
            assertTrue(saved.keys.all { it.egressProxyId == "us" })
            val updates = saved.keys.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","models":${Json.encodeToString(it.models)},"clearEgressProxy":true}""" }
            val cleared = client.put("/admin/api/channels/${channel.id}") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"updated","baseUrl":"${channel.baseUrl}","enabled":true,"egressProxies":[],"keys":[$updates]}""")
            }
            assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
            assertTrue(app.channelRepository.find(channel.id)!!.keys.all { it.egressProxyId == null })
        }
    }

    @Test
    fun `admin create validates proxy ownership host allowlist and single binding contract`() = runBlocking {
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            for ((proxyUrl, binding, expected) in listOf(
                Triple("http://127.0.0.1:7897", "us", HttpStatusCode.Created),
                Triple("socks5://127.0.0.1:1080", "us", HttpStatusCode.Created),
                Triple("http://127.0.0.1:7897", "other-channel", HttpStatusCode.BadRequest),
                Triple("http://remote.example:7897", "us", HttpStatusCode.BadRequest),
                Triple("socks5://remote.example:1080", "us", HttpStatusCode.BadRequest),
                Triple("socks5://user:secret@127.0.0.1:1080", "us", HttpStatusCode.BadRequest),
                Triple("http://user:secret@127.0.0.1:7897", "us", HttpStatusCode.BadRequest),
            )) {
                val response = client.post("/admin/api/channels") {
                    header(HttpHeaders.Cookie, "gw_session=${session.token}")
                    header("X-CSRF-Token", session.session.csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"create-$binding-$proxyUrl","baseUrl":"${fake.baseUrl}/ok/v1","modelMappings":[{"upstreamModel":"m1"}],"egressProxies":[{"id":"us","name":"美国","url":"$proxyUrl"}],"keys":[{"name":"key","credential":"secret","models":["m1"],"egressProxyId":"$binding"}]}""")
                }
                assertEquals(expected, response.status, response.bodyAsText())
                if (expected == HttpStatusCode.Created) {
                    val id = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                    assertEquals("us", app.channelRepository.find(id)!!.keys.single().egressProxyId)
                }
            }
        }
    }

    @Test
    fun `admin managed allowlist admits wildcard proxy hosts immediately without restart`() = runBlocking {
        val session = app.sessions.create("admin")
        testApplication {
            application { app.install(this) }
            // 模拟另一个实例：未刷新前持有旧快照，周期刷新后收敛。
            val replica = ChannelProxyHostAllowlist(app.config.channelProxyAllowedHosts, app.channelEgressSettingsRepository)
            replica.refresh()
            assertFalse(replica.allows("egress.example.com"))
            val body = """{"name":"allowlist-channel","baseUrl":"${fake.baseUrl}/ok/v1","modelMappings":[{"upstreamModel":"m1"}],"egressProxies":[{"id":"us","name":"美国","url":"http://egress.example.com:8080"}],"keys":[{"name":"key","credential":"secret","models":["m1"],"egressProxyId":"us"}]}"""

            val rejected = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertTrue(rejected.bodyAsText().contains("channel_proxy_host_not_allowed"), rejected.bodyAsText())

            val settings = client.put("/admin/api/channel-egress-settings") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"hosts":["*.example.com."]}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status, settings.bodyAsText())
            val settingsBody = Json.parseToJsonElement(settings.bodyAsText()).jsonObject
            assertEquals(
                listOf("*.example.com"),
                settingsBody.getValue("hosts").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("*.example.com"),
                settingsBody.getValue("effectiveHosts").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(1, settingsBody.getValue("revision").jsonPrimitive.content.toLong())

            // 另一个实例在刷新前仍使用旧快照，刷新后看到新放行结果。
            assertFalse(replica.allows("egress.example.com"))
            replica.refresh()
            assertTrue(replica.allows("egress.example.com"))

            val accepted = client.post("/admin/api/channels") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Created, accepted.status, accepted.bodyAsText())
            val id = Json.parseToJsonElement(accepted.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
            assertEquals("us", app.channelRepository.find(id)!!.keys.single().egressProxyId)

            val updated = client.put("/admin/api/channel-egress-settings") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"hosts":["*.example.com","proxy.example"],"expectedRevision":1}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
            assertEquals(
                2,
                Json.parseToJsonElement(updated.bodyAsText()).jsonObject.getValue("revision").jsonPrimitive.content.toLong(),
            )

            val stale = client.put("/admin/api/channel-egress-settings") {
                header(HttpHeaders.Cookie, "gw_session=${session.token}")
                header("X-CSRF-Token", session.session.csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"hosts":["stale.example"],"expectedRevision":1}""")
            }
            assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
            assertTrue(stale.bodyAsText().contains("channel_egress_settings_conflict"), stale.bodyAsText())
            assertEquals(
                listOf("*.example.com", "proxy.example"),
                app.channelEgressSettingsRepository.load()?.allowedHosts,
            )
        }
    }
}
