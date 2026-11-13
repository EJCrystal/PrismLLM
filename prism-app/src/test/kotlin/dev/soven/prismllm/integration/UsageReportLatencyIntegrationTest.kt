package dev.soven.prismllm.integration

import dev.soven.prismllm.requestlog.domain.UsageReportQuery
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
class UsageReportLatencyIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun percentilesIncludeRecoveredSuccessAndExcludeUnsuccessfulOutcomes() = runBlocking {
        val now = System.currentTimeMillis()
        listOf(100L, 200L, 300L, 400L).forEachIndexed { index, duration ->
            app.requestLogRepository.insert(
                requestLog("success-$index", now).copy(totalMs = duration, upstreamAttempts = if (index == 3) 3 else 1),
            )
        }
        val base = requestLog("excluded", now).copy(totalMs = 10_000)
        listOf(
            base.copy(requestId = "http-error", statusCode = 500),
            base.copy(requestId = "redirect", statusCode = 302),
            base.copy(requestId = "cancelled", statusCode = 499),
            base.copy(requestId = "client-cancelled", errorCode = "client_cancelled"),
            base.copy(requestId = "disconnected", errorCode = "client_disconnect"),
            base.copy(requestId = "stream-failed", errorCode = "stream_interrupted"),
            base.copy(requestId = "upstream-failed", upstreamErrorCode = "rate_limit_exceeded"),
            base.copy(requestId = "invalid-latency", totalMs = -1),
        ).forEach { app.requestLogRepository.insert(it) }
        val total = app.requestLogRepository.report(UsageReportQuery(now - 1, now + 1)).total
        assertEquals(12L, total.requests)
        assertEquals(4L, total.successfulLatencySamples)
        assertEquals(250.0, total.p50TotalMs)
        assertEquals(370.0, total.p90TotalMs)
        assertEquals(397.0, total.p99TotalMs)
        testApplication {
            application { app.install(this) }
            val (cookie, _) = client.loginAdmin()
            val response = client.get("/admin/api/usage-report?from=" + (now - 1) + "&to=" + (now + 1)) {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject["total"]!!.jsonObject
            assertEquals("4", json["successfulLatencySamples"]!!.jsonPrimitive.content)
            assertEquals("250.0", json["p50TotalMs"]!!.jsonPrimitive.content)
            assertEquals("370.0", json["p90TotalMs"]!!.jsonPrimitive.content)
            assertEquals("397.0", json["p99TotalMs"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun latencyPercentilesHonorTimeBoundariesAndEveryDimensionFilter() = runBlocking {
        val now = System.currentTimeMillis()
        val query = UsageReportQuery(now - 100, now, clientKeyId = "key", channelId = "channel", model = "model", protocol = "responses")
        val base = requestLog("base", now).copy(
            clientKeyId = "key", channelId = "channel", upstreamModel = "model", protocol = GatewayProtocol.RESPONSES, totalMs = 100,
        )
        listOf(
            base,
            base.copy(requestId = "lower-bound", createdAt = now - 100, totalMs = 300),
            base.copy(requestId = "too-old", createdAt = now - 101, totalMs = 9_000),
            base.copy(requestId = "too-new", createdAt = now + 1, totalMs = 9_000),
            base.copy(requestId = "other-key", clientKeyId = "other", totalMs = 9_000),
            base.copy(requestId = "other-channel", channelId = "other", totalMs = 9_000),
            base.copy(requestId = "other-model", upstreamModel = "other", totalMs = 9_000),
            base.copy(requestId = "other-protocol", protocol = GatewayProtocol.CHAT_COMPLETIONS, totalMs = 9_000),
        ).forEach { app.requestLogRepository.insert(it) }
        val total = app.requestLogRepository.report(query).total
        assertEquals(2L, total.requests)
        assertEquals(2L, total.successfulLatencySamples)
        assertEquals(200.0, total.p50TotalMs)
        assertEquals(280.0, total.p90TotalMs)
        assertEquals(298.0, total.p99TotalMs)
    }

    @Test
    fun noSamplesRemainNullWhileZeroMillisecondSuccessRemainsZero() = runBlocking {
        val now = System.currentTimeMillis()
        val query = UsageReportQuery(now - 1, now + 1)
        val empty = app.requestLogRepository.report(query).total
        assertEquals(0L, empty.successfulLatencySamples)
        assertNull(empty.p50TotalMs)
        assertNull(empty.p90TotalMs)
        assertNull(empty.p99TotalMs)
        app.requestLogRepository.insert(requestLog("failure", now).copy(statusCode = 503))
        val failed = app.requestLogRepository.report(query).total
        assertEquals(0L, failed.successfulLatencySamples)
        assertNull(failed.p50TotalMs)
        assertNull(failed.p90TotalMs)
        assertNull(failed.p99TotalMs)
        app.requestLogRepository.insert(requestLog("zero", now).copy(totalMs = 0))
        val zero = app.requestLogRepository.report(query).total
        assertEquals(1L, zero.successfulLatencySamples)
        assertEquals(0.0, zero.p50TotalMs)
        assertEquals(0.0, zero.p90TotalMs)
        assertEquals(0.0, zero.p99TotalMs)
    }
}
