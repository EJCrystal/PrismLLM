package dev.soven.prismllm.integration

import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.shared.domain.GatewayProtocol
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据面集成测试：真实 PostgreSQL、假上游、Ktor 测试宿主。
 *
 * @author 文杰
 * @since 2026-10-21
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
@Tag("shard-4")
class PricingIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `records request cost from configured model price`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("priced-chat", channel to "gpt-4o-mini")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000016")))
            assertEquals("USD", log.costCurrency)
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("1")))
            assertEquals(0, log.outputPricePerMillion?.compareTo(BigDecimal("2")))
        }
    }

    @Test
    fun `records request cost from model default price when channel has no override`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("default-priced-chat", channel to "gpt-4o-mini")
        createDefaultPrice("gpt-4o-mini", input = "3", output = "4")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"default-priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000042")))
            assertEquals("USD", log.costCurrency)
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("3")))
            assertEquals(0, log.outputPricePerMillion?.compareTo(BigDecimal("4")))
        }
    }

    @Test
    fun `channel price overrides model default price`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("override-priced-chat", channel to "gpt-4o-mini")
        createDefaultPrice("gpt-4o-mini", input = "3", output = "4")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"override-priced-chat","messages":[]}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.000016")))
            assertEquals(0, log.inputPricePerMillion?.compareTo(BigDecimal("1")))
            assertEquals(0, log.outputPricePerMillion?.compareTo(BigDecimal("2")))
        }
    }

    @Test
    fun `admin batch upserts model prices for multiple upstream models`() = runBlocking {
        val channel = createChannel("openai", "ok")
        createPrice(channel, "gpt-4o-mini", input = "9", output = "9")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/model-prices/batch") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","upstreamModels":["gpt-4o-mini","deepseek-chat"],"currency":"USD","inputPricePerMillion":"7","outputPricePerMillion":"14"}""",
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val prices = app.modelPriceRepository.list().filter { it.channelId == channel.id }
            assertEquals(2, prices.size)
            assertTrue(prices.all { it.currency == "USD" })
            assertTrue(prices.all { it.inputPricePerMillion.compareTo(BigDecimal("7")) == 0 })
            assertTrue(prices.all { it.outputPricePerMillion.compareTo(BigDecimal("14")) == 0 })
        }
    }

    @Test
    fun `admin rejects non-USD currency so foreign amounts cannot enter the USD ledger`() = runBlocking {
        val channel = createChannel("openai", "ok")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            // issue #222：API 层不再接受任何非 USD 币种，防止原币金额未换算混入 USD 账本与报表。
            val rejected = client.post("/admin/api/model-prices/batch") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","upstreamModels":["gpt-4o-mini"],"currency":"CNY","inputPricePerMillion":"7","outputPricePerMillion":"14"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertTrue(rejected.bodyAsText().contains("invalid_model_price"))

            val stored = app.modelPriceRepository.list().filter { it.channelId == channel.id }
            assertTrue(stored.isEmpty(), "被拒绝的配置不应落库")
        }
    }

    @Test
    fun `admin deletes one channel price override without affecting other models`() = runBlocking {
        val channel = createChannel("openai", "ok")
        val target = createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        createPrice(channel, "deepseek-chat", input = "3", output = "4")

        testApplication {
            application { app.install(this) }
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.delete("/admin/api/model-prices/${target.id}") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(app.modelPriceRepository.find(channel.id, "gpt-4o-mini"))
            assertNotNull(app.modelPriceRepository.find(channel.id, "deepseek-chat"))
        }
    }

    @Test
    fun `usage report aggregates requests tokens and costs by key`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "gpt-4o-mini")
        createPrice(channel, "gpt-4o-mini", input = "1", output = "2")
        val key = createClientKey()
        // 固定一次时间基准，避免 from/to 分别取时钟后在边界用例里产生毫秒级漂移。
        val reportNow = System.currentTimeMillis()
        testApplication {
            application { app.install(this) }
            repeat(2) {
                val response = client.post("/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody("""{"model":"fast-chat","messages":[]}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                awaitRequestLogCount(it + 1)
            }
            app.requestLogRepository.insert(
                requestLog("renamed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "renamed-key",
                    costAmount = java.math.BigDecimal("0.00000500"),
                    costCurrency = "USD",
                ),
            )
            app.requestLogRepository.insert(
                requestLog("failed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    statusCode = 500,
                ),
            )
            app.requestLogRepository.insert(
                requestLog("stream-failed", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    statusCode = 200,
                    errorCode = "stream_interrupted",
                ),
            )
            app.requestLogRepository.insert(
                requestLog("retried", createdAt = System.currentTimeMillis()).copy(
                    clientKeyId = app.clientKeyRepository.list().single().id,
                    clientKeyName = "test-key",
                    channelId = channel.id,
                    channelName = channel.name,
                    channelKeyId = channel.keys.single().id,
                    channelKeyName = channel.keys.single().name,
                    upstreamModel = "gpt-4o-mini",
                    cachedTokens = 3,
                    reasoningTokens = 2,
                    upstreamAttempts = 3,
                    firstTokenMs = 120,
                ),
            )
            val currentKey = app.clientKeyRepository.list().single()
            app.clientKeyRepository.update(
                id = currentKey.id,
                name = "paid",
                enabled = currentKey.enabled,
                expiresAt = currentKey.expiresAt,
                quotas = currentKey.quotas,
            )
            val (cookie, _) = client.loginAdmin()
            val report = client.get("/admin/api/usage-report?from=${reportNow - 86_400_000L}&to=${reportNow + 60_000L}") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, report.status, report.bodyAsText())
            val json = Json.parseToJsonElement(report.bodyAsText()).jsonObject
            val total = json["total"]!!.jsonObject
            assertEquals("6", total["requests"]!!.jsonPrimitive.content)
            assertEquals("2", total["errors"]!!.jsonPrimitive.content)
            assertEquals("34", total["totalTokens"]!!.jsonPrimitive.content)
            assertEquals("3", total["cachedTokens"]!!.jsonPrimitive.content)
            assertEquals("2", total["reasoningTokens"]!!.jsonPrimitive.content)
            assertEquals("2", total["failovers"]!!.jsonPrimitive.content)
            assertEquals("0.000037", total["costUsd"]!!.jsonPrimitive.content)
            val byKey = json["byClientKey"]!!.jsonArray.single().jsonObject
            assertEquals(currentKey.id, byKey["id"]!!.jsonPrimitive.content)
            assertEquals("paid", byKey["name"]!!.jsonPrimitive.content)
            assertEquals("0.000037", byKey["costUsd"]!!.jsonPrimitive.content)
            assertEquals(
                setOf("test-key", "renamed-key"),
                app.requestLogRepository.list(10).mapNotNull { it.clientKeyName }.toSet(),
            )
            val logsResponse = client.get("/admin/api/request-logs") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, logsResponse.status, logsResponse.bodyAsText())
            val displayedLogs = Json.parseToJsonElement(logsResponse.bodyAsText()).jsonArray
            assertTrue(displayedLogs.isNotEmpty())
            assertTrue(
                displayedLogs.all { it.jsonObject["clientKeyName"]!!.jsonPrimitive.content == "paid" },
                logsResponse.bodyAsText(),
            )
            app.clientKeyRepository.delete(currentKey.id)
            val logsAfterDelete = client.get("/admin/api/request-logs") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, logsAfterDelete.status, logsAfterDelete.bodyAsText())
            assertEquals(
                setOf("test-key", "renamed-key"),
                Json.parseToJsonElement(logsAfterDelete.bodyAsText()).jsonArray
                    .map { it.jsonObject["clientKeyName"]!!.jsonPrimitive.content }
                    .toSet(),
            )
            val reportAfterDelete = client.get("/admin/api/usage-report?from=${reportNow - 86_400_000L}&to=${reportNow + 60_000L}") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.OK, reportAfterDelete.status, reportAfterDelete.bodyAsText())
            val byKeyAfterDelete = Json.parseToJsonElement(reportAfterDelete.bodyAsText())
                .jsonObject["byClientKey"]!!.jsonArray.single().jsonObject
            assertTrue(
                byKeyAfterDelete["name"]!!.jsonPrimitive.content in setOf("test-key", "renamed-key"),
                reportAfterDelete.bodyAsText(),
            )
            assertEquals("2", byKey["failovers"]!!.jsonPrimitive.content)
            assertEquals(
                channel.id,
                json["byChannel"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.single { it == channel.id },
            )
            assertTrue(json["byModel"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.contains("gpt-4o-mini"))
            assertEquals(
                setOf("stream_interrupted", "500"),
                json["byErrorCode"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet(),
            )
            val trendRows = json["trend"]!!.jsonArray.map { it.jsonObject }
            assertTrue(trendRows.isNotEmpty())
            // 后端只返回有请求的桶（sum 不产生空桶行）；空桶由前端补齐且费用为 null。
            // 该断言固定接口契约：无可计费数据的桶不出现在响应里，费用列不留 0 占位。
            assertTrue(trendRows.all { it["costUsd"]!!.jsonPrimitive.isString.not() || it["costUsd"]!!.jsonPrimitive.content != "0" })

            val rangeNow = reportNow
            suspend fun reportStatus(from: Long, to: Long): Pair<HttpStatusCode, String> {
                val response = client.get("/admin/api/usage-report?from=$from&to=$to") {
                    header(HttpHeaders.Cookie, cookie)
                }
                return response.status to response.bodyAsText()
            }

            val (atLimitStatus, atLimitBody) = reportStatus(rangeNow - 30 * 86_400_000L, rangeNow)
            assertEquals(HttpStatusCode.OK, atLimitStatus, atLimitBody)
            val (overByOneMsStatus, overByOneMsBody) = reportStatus(rangeNow - 30 * 86_400_000L - 1, rangeNow)
            assertEquals(HttpStatusCode.BadRequest, overByOneMsStatus, overByOneMsBody)
            assertEquals(
                "invalid_report_range",
                Json.parseToJsonElement(overByOneMsBody).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
            val (invertedStatus, invertedBody) = reportStatus(rangeNow, rangeNow - 86_400_000L)
            assertEquals(HttpStatusCode.BadRequest, invertedStatus, invertedBody)
            assertEquals(
                "invalid_report_range",
                Json.parseToJsonElement(invertedBody).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
            val (sameInstantStatus, sameInstantBody) = reportStatus(rangeNow, rangeNow)
            assertEquals(HttpStatusCode.OK, sameInstantStatus, sameInstantBody)
            val overLimit = client.get("/admin/api/usage-report?from=${rangeNow - 31 * 86_400_000L}&to=$rangeNow") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.BadRequest, overLimit.status, overLimit.bodyAsText())
            assertEquals(
                "invalid_report_range",
                Json.parseToJsonElement(overLimit.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `looks up reference price for model`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val response = client.get("/admin/api/model-prices/reference?model=deepseek-chat") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("0.27", json["inputPricePerMillion"]?.jsonPrimitive?.content)
            assertEquals("1.1", json["outputPricePerMillion"]?.jsonPrimitive?.content)
            assertEquals("0.07", json["cachedInputPricePerMillion"]?.jsonPrimitive?.content)
            assertEquals("官方基准价", json["source"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `admin manages model default prices`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val created = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"deepseek-v4.1-flash","currency":"USD","inputPricePerMillion":"7","outputPricePerMillion":"14","cachedInputPricePerMillion":"1"}""",
                )
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val createdJson = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = createdJson["id"]!!.jsonPrimitive.content
            assertEquals("USD", createdJson["currency"]!!.jsonPrimitive.content)

            val duplicate = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"deepseek-v4.1-flash","currency":"USD","inputPricePerMillion":"1","outputPricePerMillion":"2"}""",
                )
            }
            assertEquals(HttpStatusCode.Conflict, duplicate.status)

            val listed = client.get("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(1, Json.parseToJsonElement(listed.bodyAsText()).jsonArray.size)

            val updated = client.put("/admin/api/model-default-prices/$id") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"deepseek-v4.1-flash","currency":"USD","inputPricePerMillion":"3","outputPricePerMillion":"4"}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val updatedJson = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
            assertEquals("3.00000000", updatedJson["inputPricePerMillion"]!!.jsonPrimitive.content)

            val deleted = client.delete("/admin/api/model-default-prices/$id") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertNull(app.modelDefaultPriceRepository.find("deepseek-v4.1-flash"))

            val perReq = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"dall-e-3","currency":"USD","pricingMode":"per_request","perRequestPrice":"0.04"}""",
                )
            }
            assertEquals(HttpStatusCode.Created, perReq.status)
            val perReqJson = Json.parseToJsonElement(perReq.bodyAsText()).jsonObject
            assertEquals("per_request", perReqJson["pricingMode"]!!.jsonPrimitive.content)
            assertEquals("0.04000000", perReqJson["perRequestPrice"]!!.jsonPrimitive.content)

            val perReqId = perReqJson["id"]!!.jsonPrimitive.content
            val tiered = client.put("/admin/api/model-default-prices/$perReqId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"dall-e-3","currency":"USD","pricingMode":"tiered","priceTiers":[{"upToTokens":128000,"inputPricePerMillion":"1","outputPricePerMillion":"2"},{"upToTokens":null,"inputPricePerMillion":"2","outputPricePerMillion":"4"}]}""",
                )
            }
            assertEquals(HttpStatusCode.OK, tiered.status)
            val tieredJson = Json.parseToJsonElement(tiered.bodyAsText()).jsonObject
            assertEquals("tiered", tieredJson["pricingMode"]!!.jsonPrimitive.content)
            assertEquals(2, tieredJson["priceTiers"]!!.jsonArray.size)

            val hugeLimit = client.post("/admin/api/model-default-prices") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"overflow-test","currency":"USD","pricingMode":"tiered","priceTiers":[{"upToTokens":100000000001,"inputPricePerMillion":"1","outputPricePerMillion":"2"},{"upToTokens":null,"inputPricePerMillion":"2","outputPricePerMillion":"4"}]}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, hugeLimit.status)

            client.delete("/admin/api/model-default-prices/$perReqId") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
            }
        }
    }

    @Test
    fun `concurrent default price creates map duplicate model to 409`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val ready = AtomicInteger(0)
            val responses = coroutineScope {
                (1..2).map {
                    async(Dispatchers.Default) {
                        ready.incrementAndGet()
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (ready.get() < 2 && System.nanoTime() < deadline) yield()
                        client.post("/admin/api/model-default-prices") {
                            header(HttpHeaders.Cookie, sessionCookie)
                            header("X-CSRF-Token", csrf)
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"modelId":"race-target","currency":"USD","inputPricePerMillion":"2","outputPricePerMillion":"3"}""",
                            )
                        }
                    }
                }.map { it.await() }
            }

            // 两个请求都通过了前置存在性检查时，唯一键冲突必须映射成 409 而不是 500。
            assertEquals(1, responses.count { it.status == HttpStatusCode.Created }, responses.map { it.status }.toString())
            assertEquals(1, responses.count { it.status == HttpStatusCode.Conflict }, responses.map { it.status }.toString())
            val conflict = responses.single { it.status == HttpStatusCode.Conflict }
            assertTrue(conflict.bodyAsText().contains("model_default_price_exists"), conflict.bodyAsText())
            assertEquals(1, app.modelDefaultPriceRepository.list().count { it.modelId == "race-target" })
        }
    }

    @Test
    fun `default price update rejects model id changes`() = runBlocking {
        val existing = checkNotNull(
            app.modelDefaultPriceRepository.create(
                modelId = "immutable-source",
                currency = "USD",
                inputPricePerMillion = BigDecimal.ONE,
                outputPricePerMillion = BigDecimal.ONE,
                cachedInputPricePerMillion = null,
            ),
        )
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.put("/admin/api/model-default-prices/${existing.id}") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"modelId":"immutable-target","currency":"USD","inputPricePerMillion":"2","outputPricePerMillion":"3"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("model_default_price_model_immutable"), response.bodyAsText())
            assertEquals("immutable-source", app.modelDefaultPriceRepository.findById(existing.id)?.modelId)
        }
    }

    @Test
    fun `records request cost with per_request pricing and skips failed request`() = runBlocking {
        val okChannel = createChannel("ok-channel", "ok")
        val failChannel = createChannel("fail-channel", "500")
        exposeAsModel("req-chat-ok", okChannel to "gpt-4o-mini")
        exposeAsModel("req-chat-fail", failChannel to "gpt-4o-mini")
        app.modelPriceRepository.create(
            channelId = okChannel.id,
            upstreamModel = "gpt-4o-mini",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.prismllm.pricing.domain.PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.05"),
        )
        app.modelPriceRepository.create(
            channelId = failChannel.id,
            upstreamModel = "gpt-4o-mini",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.prismllm.pricing.domain.PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.05"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val okResponse = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"req-chat-ok","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, okResponse.status)
            val okLog = awaitRequestLog()
            assertEquals(0, okLog.costAmount?.compareTo(BigDecimal("0.05000000")))
            assertEquals("per_request", okLog.pricingMode.value)

            val failResponse = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"req-chat-fail","messages":[]}""")
            }
            assertEquals(HttpStatusCode.BadGateway, failResponse.status)
            awaitRequestLogCount(2)
            val failLog = app.requestLogRepository.list(10).first { it.alias == "req-chat-fail" }
            assertNull(failLog.costAmount)
        }
    }

    @Test
    fun `per_request pricing does not charge interrupted stream`() = runBlocking {
        val channel = createChannel("stream-disc", "disconnect")
        exposeAsModel("per-req-stream-disc", channel to "m1")
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = "m1",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.prismllm.pricing.domain.PricingMode.PER_REQUEST,
            perRequestPrice = BigDecimal("0.08"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"per-req-stream-disc","stream":true,"messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            assertEquals("stream_interrupted", log.errorCode)
            assertNull(log.costAmount, "中断的流式请求不应收取按次费用")
        }
    }

    @Test
    fun `records request cost with tiered pricing`() = runBlocking {
        val channel = createChannel("tiered-channel", "ok")
        exposeAsModel("tiered-chat", channel to "gpt-4o-mini")
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = "gpt-4o-mini",
            currency = "USD",
            inputPricePerMillion = BigDecimal.ZERO,
            outputPricePerMillion = BigDecimal.ZERO,
            cachedInputPricePerMillion = null,
            pricingMode = dev.soven.prismllm.pricing.domain.PricingMode.TIERED,
            priceTiers = listOf(
                dev.soven.prismllm.pricing.domain.ModelPriceTier(
                    upToTokens = 5,
                    inputPricePerMillion = BigDecimal("1.0"),
                    outputPricePerMillion = BigDecimal("2.0"),
                ),
                dev.soven.prismllm.pricing.domain.ModelPriceTier(
                    upToTokens = null,
                    inputPricePerMillion = BigDecimal("2.0"),
                    outputPricePerMillion = BigDecimal("4.0"),
                ),
            ),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"tiered-chat","messages":[]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            // input 10 (5*1 + 5*2 = 15), output 3 (3*2 = 6) -> 21 / 1M = 0.00002100
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.00002100")))
            assertEquals("tiered", log.pricingMode.value)
        }
    }

    @Test
    fun `records request cost with cache write rate for anthropic messages`() = runBlocking {
        val channel = createChannelWithBaseUrl(
            name = "anthropic-cache-write",
            baseUrl = fake.baseUrl + "/ok/v1",
            protocols = listOf(GatewayProtocol.MESSAGES),
            modelMappings = listOf(ChannelModelRecord("claude-sonnet-4-5")),
            authType = "anthropic",
        )
        exposeAsModel("claude-cache", channel to "claude-sonnet-4-5")
        app.modelPriceRepository.create(
            channelId = channel.id,
            upstreamModel = "claude-sonnet-4-5",
            currency = "USD",
            inputPricePerMillion = BigDecimal("1.0"),
            outputPricePerMillion = BigDecimal("2.0"),
            cachedInputPricePerMillion = BigDecimal("0.5"),
            cachedInputWritePricePerMillion = BigDecimal("1.5"),
        )
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val response = client.post("/v1/messages") {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-cache","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val log = awaitRequestLog()
            // input 15 (uncached 10, cachedRead 3, cachedWrite 2), output 4
            // cost = (10*1 + 3*0.5 + 2*1.5 + 4*2) / 1M = 22.5 / 1M = 0.00002250
            assertEquals(0, log.costAmount?.compareTo(BigDecimal("0.00002250")))
            assertEquals(2L, log.cachedWriteTokens)
            assertEquals(0, log.cachedInputWritePricePerMillion?.compareTo(BigDecimal("1.5")))
        }
    }
}
