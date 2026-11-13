package dev.soven.prismllm.integration

import dev.soven.prismllm.requestlog.domain.RequestLogQuery
import dev.soven.prismllm.requestlog.domain.UpstreamAttemptRecord
import dev.soven.prismllm.requestlog.domain.requestOutcome
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-2")
class RequestLogQueryIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun `filters precede cursor limits and preserve exclusive time bounds`() = runBlocking {
        app.requestLogRepository.insert(requestLog("outside-low", 999).copy(alias = "needle"))
        repeat(5) { index ->
            app.requestLogRepository.insert(requestLog("match-$index", 1000L + index).copy(alias = "needle"))
            app.requestLogRepository.insert(requestLog("noise-$index", 1000L + index).copy(alias = "other"))
        }
        app.requestLogRepository.insert(requestLog("outside-high", 1005).copy(alias = "needle"))
        val filters = RequestLogQuery(from = 1000, to = 1005, keyword = "NEEDLE", model = "needle", httpStatus = "2xx", outcome = "success")
        val first = app.requestLogRepository.listForDisplay(2, filters = filters)
        val second = app.requestLogRepository.listForDisplay(2, first.last().id, filters)
        val third = app.requestLogRepository.listForDisplay(2, second.last().id, filters)
        assertEquals(listOf("match-4", "match-3", "match-2", "match-1", "match-0"), (first + second + third).map { it.requestId })
    }

    @Test
    fun `request outcomes agree with SQL including stream errors and skipped candidates`() = runBlocking {
        val base = requestLog("success", System.currentTimeMillis())
        val fixtures = listOf(
            base,
            base.copy(requestId = "server", statusCode = 502),
            base.copy(requestId = "rate", statusCode = 429),
            base.copy(requestId = "stream", errorCode = "stream_interrupted"),
            base.copy(requestId = "upstream-code", upstreamErrorCode = "overloaded"),
            base.copy(requestId = "disconnect", statusCode = 499),
            base.copy(requestId = "cancel", errorCode = "client_cancelled"),
            base.copy(requestId = "retry", upstreamAttempts = 2),
            base.copy(requestId = "attempt-error", attempts = listOf(UpstreamAttemptRecord(index = 1, statusCode = 503, elapsedMs = 20))),
            base.copy(requestId = "two-attempts", attempts = listOf(UpstreamAttemptRecord(index = 1, statusCode = 200, elapsedMs = 20), UpstreamAttemptRecord(index = 2, statusCode = 200, elapsedMs = 30))),
            base.copy(requestId = "skip", attempts = listOf(UpstreamAttemptRecord(index = 0, skipped = true, statusCode = 503, elapsedMs = 0))),
            base.copy(requestId = "redirect", statusCode = 302),
        )
        fixtures.forEach { app.requestLogRepository.insert(it) }
        for (outcome in listOf("success", "recovered", "failed", "cancelled", "other")) {
            val actual = app.requestLogRepository.listForDisplay(100, filters = RequestLogQuery(outcome = outcome))
            assertEquals(fixtures.filter { it.requestOutcome() == outcome }.map { it.requestId }.toSet(), actual.map { it.requestId }.toSet(), outcome)
            assertTrue(actual.all { it.requestOutcome() == outcome })
        }
        val abnormal = app.requestLogRepository.listForDisplay(100, filters = RequestLogQuery(httpStatus = "2xx", outcome = "abnormal"))
        assertEquals(setOf("stream", "upstream-code", "cancel"), abnormal.map { it.requestId }.toSet())
        app.requestLogRepository.insert(base.copy(requestId = "broken-json"))
        DriverManager.getConnection(databaseUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("update request_logs set attempts_json = ? where request_id = ?").use { statement ->
                statement.setString(1, "not-json")
                statement.setString(2, "broken-json")
                statement.executeUpdate()
            }
        }
        assertTrue(app.requestLogRepository.listForDisplay(100, filters = RequestLogQuery(outcome = "success")).any { it.requestId == "broken-json" })
    }

    @Test
    fun `jsonb incompatible attempt payloads cannot break outcome filters`() = runBlocking {
        val record = requestLog("escaped-null", System.currentTimeMillis()).copy(
            upstreamAttempts = 1,
            attempts = listOf(UpstreamAttemptRecord(index = 1, elapsedMs = 0, errorMessage = "\u0000")),
        )
        app.requestLogRepository.insert(record)
        val successes = app.requestLogRepository.listForDisplay(100, filters = RequestLogQuery(outcome = "success"))
        assertEquals(listOf(record.requestId), successes.map { it.requestId })
        assertTrue(app.requestLogRepository.listForDisplay(100, filters = RequestLogQuery(outcome = "recovered")).isEmpty())
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (cookie, _) = client.loginAdmin()
            val response = client.get("/admin/api/request-logs?outcome=success") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(record.requestId, Json.parseToJsonElement(response.bodyAsText()).jsonArray.single().jsonObject["requestId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `search resolves current names and treats wildcard characters literally`() = runBlocking {
        val channel = createChannel("Current%_Name", "channel")
        val now = System.currentTimeMillis()
        app.requestLogRepository.insert(requestLog("archived", now - 86_400_000).copy(channelId = channel.id, channelName = "snapshot", alias = "archived-model"))
        app.requestLogRepository.insert(requestLog("plain", now).copy(alias = "plain-model"))
        for (keyword in listOf("CURRENT%_NAME", "%_", "snapshot")) {
            assertEquals(listOf("archived"), app.requestLogRepository.listForDisplay(10, filters = RequestLogQuery(keyword = keyword)).map { it.requestId })
        }
        assertTrue(app.requestLogRepository.listForDisplay(10, filters = RequestLogQuery(keyword = "' OR 1=1 --")).isEmpty())
        val options = app.requestLogRepository.filterOptions(RequestLogQuery(from = now - 2 * 86_400_000, to = now + 1, keyword = "plain", model = "plain-model"))
        assertEquals(setOf("plain-model", "archived-model"), options.models.map { it.value }.toSet())
        assertEquals("Current%_Name", options.channels.first { it.value == channel.id }.label)
        assertTrue(options.channels.any { it.value == "__unrouted__" })
    }

    @Test
    fun `admin endpoints validate filters expose outcomes and protect options`() = runBlocking {
        app.requestLogRepository.insert(requestLog("stream", System.currentTimeMillis()).copy(errorCode = "stream_interrupted"))
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/api/request-logs/filter-options").status)
            val (cookie, _) = client.loginAdmin()
            for (query in listOf("from=no", "from=-1", "from=5&to=5", "httpStatus=wat", "outcome=wat")) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/admin/api/request-logs?$query") { header(HttpHeaders.Cookie, cookie) }.status, query)
            }
            val result = client.get("/admin/api/request-logs?httpStatus=2xx&outcome=failed") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, result.status)
            val records = Json.parseToJsonElement(result.bodyAsText()).jsonArray
            assertEquals("failed", records.single().jsonObject["outcome"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.OK, client.get("/admin/api/request-logs/filter-options") { header(HttpHeaders.Cookie, cookie) }.status)
        }
    }
}
