package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.config.TrustedProxyNetwork
import dev.soven.prismllm.quota.domain.ClientKeyIpPolicy
import dev.soven.prismllm.shared.crypto.ClientKeyHasher
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("shard-1")
class ClientKeyIpPolicyIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun createReadUpdateRotateAndRevokePreserveIpPolicy() = testApplication {
        application { app.install(this) }
        val (cookie, csrf) = client.loginAdmin()
        val expected = Json.parseToJsonElement("""{"allowlist":["2001:db8::/32","203.0.113.0/24"],"denylist":["203.0.113.9"]}""")
        val created = client.post("/admin/api/client-keys") {
            header(HttpHeaders.Cookie, cookie)
            header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ip-scoped","ipPolicy":{"allowlist":[" 203.0.113.0/24 ","2001:db8::/32","203.0.113.0/24"],"denylist":["203.0.113.9"]}}""")
        }.objectBody(HttpStatusCode.Created)
        val id = created["id"]!!.jsonPrimitive.content
        assertEquals(expected, created["ipPolicy"])
        val listed = client.get("/admin/api/client-keys") { header(HttpHeaders.Cookie, cookie) }.objectList().single()
        assertEquals(expected, listed["ipPolicy"])
        val stored = assertNotNull(app.clientKeyRepository.find(id))
        assertEquals(stored.ipPolicy, app.clientKeyRepository.findByHash(stored.keyHash)?.ipPolicy)
        for (suffix in listOf("", ",\"ipPolicy\":null")) {
            val updated = client.put("/admin/api/client-keys/$id") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"renamed","enabled":false$suffix}""")
            }.objectBody(HttpStatusCode.OK)
            assertEquals(expected, updated["ipPolicy"])
        }
        for (action in listOf("rotate", "revoke")) {
            val changed = client.post("/admin/api/client-keys/$id/$action") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
            }.objectBody(HttpStatusCode.OK)
            assertEquals(expected, changed["ipPolicy"])
        }
    }

    @Test
    fun oldRequestsRemainUnrestrictedAndExplicitEmptyPolicyClearsRestrictions() = testApplication {
        application { app.install(this) }
        val (cookie, csrf) = client.loginAdmin()
        val created = client.post("/admin/api/client-keys") {
            header(HttpHeaders.Cookie, cookie)
            header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"old-client"}""")
        }.objectBody(HttpStatusCode.Created)
        val id = created["id"]!!.jsonPrimitive.content
        val empty = Json.parseToJsonElement("""{"allowlist":[],"denylist":[]}""")
        assertEquals(empty, created["ipPolicy"])
        for (policy in listOf("""{"denylist":["192.0.2.1"]}""", "{}")) {
            val updated = client.put("/admin/api/client-keys/$id") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"old-client","ipPolicy":$policy}""")
            }.objectBody(HttpStatusCode.OK)
            val expected = Json.parseToJsonElement(policy).jsonObject["denylist"] ?: empty.jsonObject["denylist"]
            assertEquals(expected, updated["ipPolicy"]!!.jsonObject["denylist"])
        }
        assertTrue(assertNotNull(app.clientKeyRepository.find(id)).ipPolicy.isUnrestricted)
    }

    @Test
    fun invalidPoliciesFailAtomically() = testApplication {
        application { app.install(this) }
        val (cookie, csrf) = client.loginAdmin()
        for (rule in listOf("localhost", "203.0.113.256", "::/129", "203.0.113.1/33", "")) {
            val response = client.post("/admin/api/client-keys") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"invalid","ipPolicy":{"allowlist":["$rule"]}}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("invalid_client_key_ip_policy"))
        }
        assertTrue(app.clientKeyRepository.list().isEmpty())
        val key = createIpKey(ClientKeyIpPolicy(denylist = setOf("192.0.2.1")))
        val saved = assertNotNull(app.clientKeyRepository.findByHash(ClientKeyHasher.hash(key)))
        val invalidUpdate = client.put("/admin/api/client-keys/" + saved.id) {
            header(HttpHeaders.Cookie, cookie)
            header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"must-not-change","enabled":false,"ipPolicy":{"denylist":["not-an-ip"]}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidUpdate.status)
        assertEquals(saved, app.clientKeyRepository.find(saved.id))
    }

    @Test
    fun allProtocolsAndModelListsRejectBlockedIpsBeforeCallingUpstream() = testApplication {
        application { app.install(this) }
        val key = createIpKey(ClientKeyIpPolicy(denylist = setOf("0.0.0.0/0", "::/0")))
        val paths = listOf(
            "/v1/chat/completions", "/v1/responses", "/v1/messages",
            "/v1beta/models/fast-chat:generateContent", "/v1/models/fast-chat:generateContent",
        )
        for (path in paths) {
            val response = client.post(path) {
                when {
                    path == "/v1/messages" -> header("x-api-key", key)
                    path.contains("generateContent") -> header("x-goog-api-key", key)
                    else -> header(HttpHeaders.Authorization, "Bearer $key")
                }
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","messages":[],"input":"hi","max_tokens":8,"contents":[]}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status, "$path: " + response.bodyAsText())
            assertEquals("ip_access_denied", response.headers["X-Prism-Error-Code"])
        }
        for (path in listOf("/v1/models", "/v1beta/models")) {
            val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $key") }
            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun directClientsCannotSpoofForwardingHeadersToPassAllowlist() = testApplication {
        application { app.install(this) }
        val key = createIpKey(ClientKeyIpPolicy(allowlist = setOf("203.0.113.0/24")))
        val response = client.get("/v1/models") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header("CF-Connecting-IP", "203.0.113.8")
            header("X-Forwarded-For", "203.0.113.8")
            header("X-Real-IP", "203.0.113.8")
            header("Forwarded", "for=203.0.113.8")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
    }

    @Test
    fun trustedProxyUsesLastUntrustedHopAndDenylistWinsOverAllowlist() = runBlocking {
        app.close()
        app = GatewayApplication(config().copy(trustedProxyNetworks = listOf(
            TrustedProxyNetwork.parse("127.0.0.0/8"), TrustedProxyNetwork.parse("::1"),
        )))
        val key = createIpKey(ClientKeyIpPolicy(setOf("203.0.113.0/24", "2001:db8::/32"), setOf("203.0.113.9")))
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { app.install(this) }.start(false)
        try {
            val baseUrl = "http://127.0.0.1:" + server.engine.resolvedConnectors().single().port
            HttpClient(CIO).use { http ->
                for ((ip, expected) in listOf(
                    "203.0.113.8" to HttpStatusCode.OK,
                    "2001:db8::1" to HttpStatusCode.OK,
                    "203.0.113.9" to HttpStatusCode.Forbidden,
                    "192.0.2.1" to HttpStatusCode.Forbidden,
                    "203.0.113.8, 192.0.2.1" to HttpStatusCode.Forbidden,
                )) {
                    val response = http.get("$baseUrl/v1/models") {
                        header(HttpHeaders.Authorization, "Bearer $key")
                        header("X-Forwarded-For", "$ip, 127.0.0.1")
                    }
                    assertEquals(expected, response.status, "$ip: " + response.bodyAsText())
                }
            }
        } finally {
            server.stop(0, 1_000)
        }
    }

    private suspend fun createIpKey(policy: ClientKeyIpPolicy): String {
        val generated = ClientKeyHasher.generate()
        app.clientKeyRepository.create("ip-policy", generated.hash, generated.prefix, true, null, ipPolicy = policy)
        return generated.raw
    }

    private suspend fun HttpResponse.objectBody(expected: HttpStatusCode): JsonObject {
        val body = bodyAsText()
        assertEquals(expected, status, body)
        return Json.parseToJsonElement(body).jsonObject
    }

    private suspend fun HttpResponse.objectList(): List<JsonObject> {
        assertEquals(HttpStatusCode.OK, status)
        return Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject }
    }
}
