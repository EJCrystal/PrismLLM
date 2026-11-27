package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.persistence.AntigravityAgySettingsRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@Tag("integration")
@Tag("shard-2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AntigravityAgySettingsIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun settingsRequireAdminAndCsrfRejectUnsafeProxiesAndPersistWithoutStartingSetup() = runBlocking {
        testApplication {
            application { app.install(this) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/antigravity-agy-settings").status)
            val (cookie, csrf) = client.loginAdmin()
            val initial = client.get("/admin/api/antigravity-agy-settings") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertEquals("no-store", initial.headers["Cache-Control"])
            val state = Json.parseToJsonElement(initial.bodyAsText()).jsonObject
            assertFalse(state.getValue("enabled").jsonPrimitive.boolean)
            assertEquals("DISABLED", state.getValue("status").jsonPrimitive.content)

            val withoutCsrf = client.put("/admin/api/antigravity-agy-settings") {
                header(HttpHeaders.Cookie, cookie)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false,"proxy":"http://127.0.0.1:7897"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, withoutCsrf.status)

            suspend fun save(proxy: String) = client.put("/admin/api/antigravity-agy-settings") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false,"proxy":"$proxy"}""")
            }
            val repository = AntigravityAgySettingsRepository(app.databaseFactory.database)
            assertEquals(HttpStatusCode.BadRequest, save("http://untrusted.invalid:7897").status)
            assertNull(repository.load())
            val saved = save("http://127.0.0.1:7898")
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals("http://127.0.0.1:7898", repository.load()?.proxy)
            assertNull(app.antigravityAgyLogin)
        }
    }
}
