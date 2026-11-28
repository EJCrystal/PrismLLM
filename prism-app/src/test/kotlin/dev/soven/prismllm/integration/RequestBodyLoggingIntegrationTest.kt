package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.requestlog.domain.RequestLogRecord
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("shard-2")
class RequestBodyLoggingIntegrationTest : GatewayIntegrationTestBase() {
    private val body = """ {"model":"body-test","messages":[{"role":"user","content":"PRIVATE_PROMPT_中文"}]} """

    @Test
    fun disabledByDefaultButStillMeasuresActualRequestBytes() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = prepareProxy()
            assertFalse(app.retentionSettingsRepository.isRequestBodyLoggingEnabled())

            assertEquals(HttpStatusCode.OK, client.sendProxy(key, body).status)
            val log = awaitRequestLog()
            assertEquals(body.toByteArray().size.toLong(), log.requestBodySizeBytes)
            assertTrue(log.requestBodySizeExact)
            assertFalse(log.requestBodyCaptured)
            assertFalse(log.requestBodyTruncated)
            assertNull(app.requestLogRepository.findRequestBody(log.id!!))
        }
    }

    @Test
    fun enabledBodyIsAdminOnlyOnDemandAndNeverIncludedInLogList() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = prepareProxy()
            val admin = jsonClient()
            val (cookie, csrf) = admin.loginAdmin()
            assertEquals(HttpStatusCode.OK, admin.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":false,"requestBodyLoggingEnabled":true}""")
            }.status)

            assertEquals(HttpStatusCode.OK, client.sendProxy(key, body).status)
            val log = awaitRequestLog()
            assertTrue(log.requestBodyCaptured)
            assertNull(log.requestBody)
            assertEquals(body, app.requestLogRepository.findRequestBody(log.id!!))
            assertEquals(body.toByteArray().size.toLong(), log.requestBodySizeBytes)

            val path = "/admin/api/request-logs/" + log.id + "/body"
            assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) {
                header(HttpHeaders.Authorization, "Bearer $key")
            }.status)
            val saved = admin.get(path) { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals("no-store", saved.headers[HttpHeaders.CacheControl])
            assertEquals(body, Json.parseToJsonElement(saved.bodyAsText()).jsonObject["body"]!!.jsonPrimitive.content)

            val listed = admin.get("/admin/api/request-logs") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, listed.status)
            val listing = listed.bodyAsText()
            assertFalse(listing.contains("PRIVATE_PROMPT"))
            val row = Json.parseToJsonElement(listing).jsonArray.single().jsonObject
            assertFalse("requestBody" in row)
            assertTrue(row["requestBodyCaptured"]!!.jsonPrimitive.boolean)
            assertEquals(HttpStatusCode.BadRequest, admin.get("/admin/api/request-logs/-1/body") {
                header(HttpHeaders.Cookie, cookie)
            }.status)
            assertEquals(HttpStatusCode.NotFound, admin.get("/admin/api/request-logs/9223372036854775807/body") {
                header(HttpHeaders.Cookie, cookie)
            }.status)
        }
    }

    @Test
    fun malformedJsonIncludingNullBytesIsCapturedWithoutBreakingLogPersistence() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = createClientKey()
            app.updateLogCleanupEnabled(false, true)
            val invalid = "{\"model\":\"broken\",\"messages\":\u0000中文"
            assertEquals(HttpStatusCode.BadRequest, client.sendProxy(key, invalid).status)

            val log = awaitRequestLog()
            assertEquals("invalid_json", log.errorCode)
            assertTrue(log.requestBodyCaptured)
            assertTrue(log.requestBodySizeExact)
            assertEquals(invalid.toByteArray().size.toLong(), log.requestBodySizeBytes)
            assertEquals(invalid, app.requestLogRepository.findRequestBody(log.id!!))
        }
    }

    @Test
    fun authenticationFailureDoesNotReadOrStoreUntrustedBody() = runBlocking {
        testApplication {
            application { app.install(this) }
            app.updateLogCleanupEnabled(false, true)
            assertEquals(HttpStatusCode.Unauthorized, client.sendProxy("invalid-key", body).status)

            val log = awaitRequestLog()
            assertNull(log.requestBodySizeBytes)
            assertFalse(log.requestBodyCaptured)
            assertNull(app.requestLogRepository.findRequestBody(log.id!!))
        }
    }

    @Test
    fun largeBodyIsTruncatedOnlyInLogsWhileProxyReceivesTheWholeRequest() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = prepareProxy()
            app.updateLogCleanupEnabled(false, true)
            val large = body.replace("PRIVATE_PROMPT_中文", "x".repeat(70 * 1024))
            assertEquals(HttpStatusCode.OK, client.sendProxy(key, large).status)

            val log = awaitRequestLog()
            assertTrue(log.requestBodyCaptured)
            assertTrue(log.requestBodyTruncated)
            assertTrue(log.requestBodySizeExact)
            assertEquals(large.toByteArray().size.toLong(), log.requestBodySizeBytes)
            assertEquals(large.take(64 * 1024), app.requestLogRepository.findRequestBody(log.id!!))
            val forwarded = Json.parseToJsonElement(fake.requests.single().body).jsonObject
            assertEquals("x".repeat(70 * 1024), forwarded["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun declaredOversizedBodyIsRejectedWithoutReadingIt() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = createClientKey()
            app.updateLogCleanupEnabled(false, true)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.sendProxy(key, "x".repeat(1024 * 1024 + 1)).status)

            val log = awaitRequestLog()
            assertEquals("request_too_large", log.errorCode)
            assertNull(log.requestBodySizeBytes)
            assertFalse(log.requestBodyCaptured)
        }
    }

    @Test
    fun oversizedChunkedBodyRecordsReadPrefixAndLowerBoundSize() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = createClientKey()
            app.updateLogCleanupEnabled(false, true)
            val bytes = ByteArray(1024 * 1024 + 10) { 'x'.code.toByte() }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(object : OutgoingContent.ReadChannelContent() {
                    override val contentType = ContentType.Application.Json
                    override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)

            val log = awaitRequestLog()
            assertTrue(log.requestBodyCaptured)
            assertTrue(log.requestBodyTruncated)
            assertFalse(log.requestBodySizeExact)
            assertEquals(1024 * 1024L + 1, log.requestBodySizeBytes)
            assertEquals("x".repeat(64 * 1024), app.requestLogRepository.findRequestBody(log.id!!))
        }
    }

    @Test
    fun turningCaptureOffStopsNewBodiesButKeepsExistingOnes() = runBlocking {
        testApplication {
            application { app.install(this) }
            val key = prepareProxy()
            app.updateLogCleanupEnabled(false, true)
            assertEquals(HttpStatusCode.OK, client.sendProxy(key, body).status)
            val captured = awaitRequestLog()

            app.updateLogCleanupEnabled(false, false)
            assertEquals(HttpStatusCode.OK, client.sendProxy(key, body).status)
            val uncaptured = awaitRequestLog()
            assertFalse(uncaptured.requestBodyCaptured)
            assertEquals(body.toByteArray().size.toLong(), uncaptured.requestBodySizeBytes)
            assertNull(app.requestLogRepository.findRequestBody(uncaptured.id!!))
            assertEquals(body, app.requestLogRepository.findRequestBody(captured.id!!))
        }
    }

    @Test
    fun settingsRequireAdminAndCsrfAndSurviveRestartAndLegacyPayloads() = runBlocking {
        testApplication {
            application { app.install(this) }
            val admin = jsonClient()
            val (cookie, csrf) = admin.loginAdmin()
            val initial = admin.get("/admin/api/request-log-retention") { header(HttpHeaders.Cookie, cookie) }
            assertFalse(Json.parseToJsonElement(initial.bodyAsText()).jsonObject["requestBodyLoggingEnabled"]!!.jsonPrimitive.boolean)
            assertEquals(HttpStatusCode.Unauthorized, client.put("/admin/api/request-log-retention") {
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":false,"requestBodyLoggingEnabled":true}""")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, admin.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, cookie)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":false,"requestBodyLoggingEnabled":true}""")
            }.status)
            assertFalse(app.retentionSettingsRepository.isRequestBodyLoggingEnabled())
            app.updateLogCleanupEnabled(false, true)
            assertEquals(HttpStatusCode.OK, admin.put("/admin/api/request-log-retention") {
                header(HttpHeaders.Cookie, cookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"cleanupEnabled":false}""")
            }.status)
            assertTrue(app.retentionSettingsRepository.isRequestBodyLoggingEnabled())
        }

        app.close()
        app = GatewayApplication(config())
        testApplication {
            application { app.install(this) }
            startApplication()
            assertTrue(app.retentionSettingsRepository.isRequestBodyLoggingEnabled())
            assertEquals(true, app.retentionSettingsRepository.loadRequestBodyLoggingEnabled())
        }
    }

    @Test
    fun retentionCascadesToBodiesWithoutDeletingRecentOnes() = runBlocking {
        val now = System.currentTimeMillis()
        app.requestLogRepository.insert(requestLog("old-body", now - 31 * 86_400_000L).copy(requestBody = "old"))
        app.requestLogRepository.insert(requestLog("recent-body", now).copy(requestBody = "recent"))
        val records = app.requestLogRepository.list(10).associateBy(RequestLogRecord::requestId)
        val oldId = records.getValue("old-body").id!!
        val recentId = records.getValue("recent-body").id!!

        assertEquals(1, app.logRetentionService.cleanExpired().deleted)
        assertNull(app.requestLogRepository.findRequestBody(oldId))
        assertEquals("recent", app.requestLogRepository.findRequestBody(recentId))
    }

    private suspend fun prepareProxy(): String {
        val channel = createChannel("body-channel", "ok")
        exposeAsModel("body-test", channel to "gpt-4o-mini")
        return createClientKey()
    }

    private suspend fun HttpClient.sendProxy(key: String, content: String) = post("/v1/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(content)
    }
}
