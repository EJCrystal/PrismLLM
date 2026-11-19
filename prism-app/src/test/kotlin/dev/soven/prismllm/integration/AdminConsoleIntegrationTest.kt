package dev.soven.prismllm.integration

import dev.soven.prismllm.api.GatewayApplication
import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.admin.domain.NewIqTestRun
import dev.soven.prismllm.channel.persistence.NewChannelKey
import dev.soven.prismllm.channel.persistence.ChannelKeyUpdate
import dev.soven.prismllm.proxy.fingerprint.FingerprintAnalysis
import dev.soven.prismllm.proxy.fingerprint.FingerprintOutput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
@Tag("shard-3")
class AdminConsoleIntegrationTest : GatewayIntegrationTestBase() {

    @Test
    fun `rejects invalid model and stream types`() = runBlocking {
        val channel = createChannel("openai", "ok")
        exposeAsModel("fast-chat", channel to "m1")
        val key = createClientKey()

        testApplication {
            application { app.install(this) }
            val invalidModel = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":{},"messages":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidModel.status)
            assertTrue(invalidModel.bodyAsText().contains("invalid_model"))

            val invalidStream = client.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast-chat","stream":"yes","messages":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidStream.status)
            assertTrue(invalidStream.bodyAsText().contains("invalid_stream"))
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `search proxy rejects an oversized model response`() = runBlocking {
        assertOversizedSearchResponseRejected("oversized-search-response")
    }

    @Test
    fun `search proxy rejects an oversized model error response`() = runBlocking {
        assertOversizedSearchResponseRejected("oversized-search-error")
    }

    @Test
    fun `admin updates model test settings`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val initial = client.get("/admin/api/model-test-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertTrue(initial.bodyAsText().contains("请只回复 OK"))

            val updated = client.put("/admin/api/model-test-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"defaultPrompt":"  custom prompt  ","defaultReasoningEffort":"high"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val json = Json.parseToJsonElement(updated.bodyAsText()).jsonObject
            assertEquals("custom prompt", json["defaultPrompt"]?.jsonPrimitive?.content)
            assertTrue("defaultReasoningEffort" in json)
            assertEquals(JsonNull, json["defaultReasoningEffort"])
        }
    }

    @Test
    fun `fingerprint route analyzes only completed raw samples while exposing redacted display text`() = runBlocking {
        val credential = "7"
        val rawSample = List(200) { credential }.joinToString(", ")
        val model = "fingerprint-synthetic-model"
        data class CapturedRequest(
            val method: String,
            val host: String,
            val path: String,
            val authorized: Boolean,
        )
        val captured = java.util.concurrent.ConcurrentLinkedQueue<CapturedRequest>()
        val upstreamPostCount = java.util.concurrent.atomic.AtomicInteger()
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val host = request.url.host
                    val path = request.url.encodedPath
                    val authorized = request.headers[HttpHeaders.Authorization] == "Bearer $credential"
                    captured.add(CapturedRequest(request.method.value, host, path, authorized))
                    when {
                        request.method == io.ktor.http.HttpMethod.Get && host == "models.dev" && path == "/catalog.json" ->
                            respond("{}", headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                        request.method == io.ktor.http.HttpMethod.Post && host == "fingerprint.synthetic.invalid" && path == "/v1/responses" -> {
                            val failed = upstreamPostCount.getAndIncrement() == 0
                            val events = buildList {
                                add("event: response.output_text.delta")
                                add("data: {\"type\":\"response.output_text.delta\",\"delta\":${Json.encodeToString(rawSample)}}")
                                add("")
                                if (failed) {
                                    add("event: response.failed")
                                    add("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"denied $credential\"}}}")
                                } else {
                                    add("event: response.completed")
                                    add(
                                        "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"$model\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":${Json.encodeToString(rawSample)}}]}]}}",
                                    )
                                }
                            }
                            respond(
                                events.joinToString("\n", postfix = "\n\n"),
                                status = HttpStatusCode.OK,
                                headers = io.ktor.http.headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                            )
                        }
                        else -> error("Unexpected mocked HTTP request: ${request.method.value} $host$path")
                    }
                }
            }
        }
        app.close()
        app = GatewayApplication(app.config.copy(upstreamHeaderRefreshEnabled = false), httpClientOverride = mockClient)
        val channel = app.channelRepository.create(
            name = "fingerprint numeric credential",
            baseUrl = "https://fingerprint.synthetic.invalid/v1",
            keys = listOf(
                NewChannelKey(
                    name = "synthetic numeric key",
                    credential = app.credentialCipher.encrypt(credential),
                    models = listOf(model),
                ),
            ),
            authType = "bearer",
            protocols = listOf(GatewayProtocol.RESPONSES),
            enabled = true,
            modelMappings = mappingsOf(model),
        )

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val run = client.post("/admin/api/model-tests/fingerprint") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"$model","protocol":"responses"}""")
            }
            assertEquals(HttpStatusCode.OK, run.status, run.bodyAsText())
            val runText = run.bodyAsText()
            val runBody = Json.parseToJsonElement(runText).jsonObject
            val runId = runBody["id"]!!.jsonPrimitive.content
            val displayedSamples = runBody["samples"]!!.jsonArray.map { it.jsonObject }
            assertEquals(3, displayedSamples.size)
            val failedSamples = displayedSamples.filter { it["errorMessage"] != null && it["errorMessage"] != JsonNull }
            val successfulSamples = displayedSamples.filter { it["errorMessage"] == null || it["errorMessage"] == JsonNull }
            assertEquals(1, failedSamples.size, "One HTTP 200 stream must terminate with response.failed")
            assertEquals(2, successfulSamples.size, "Two streams must terminate with response.completed")
            assertTrue(successfulSamples.all { it["accepted"]!!.jsonPrimitive.boolean })
            assertTrue(successfulSamples.all { it["parsedNumbers"]!!.jsonPrimitive.int == 200 })
            assertFalse(failedSamples.single()["accepted"]!!.jsonPrimitive.boolean)
            assertEquals(0, failedSamples.single()["parsedNumbers"]!!.jsonPrimitive.int, "Failed stream deltas must not reach the detector")
            assertTrue(displayedSamples.all { it["text"]!!.jsonPrimitive.content == rawSample.replace(credential, "[REDACTED]") })
            assertTrue(displayedSamples.all { it["statusCode"]!!.jsonPrimitive.int == 200 })
            assertTrue(failedSamples.single()["errorMessage"]!!.jsonPrimitive.content.contains("denied [REDACTED]"))
            assertFalse(runText.contains(rawSample), "The response must not expose the original numeric samples")

            val analysis = Json.decodeFromString<FingerprintAnalysis>(runBody["analysis"]!!.toString())
            val expectedRawAnalysis = assertNotNull(app.fingerprintDetector).analyze(
                displayedSamples.map { sample ->
                    FingerprintOutput(
                        expectedCount = sample["expectedCount"]!!.jsonPrimitive.int,
                        text = if (sample["errorMessage"] == null || sample["errorMessage"] == JsonNull) rawSample else "",
                    )
                },
            )
            assertEquals(expectedRawAnalysis, analysis, "The route must analyze original text only from completed streams")
            assertEquals(2, analysis.usedOutputs)
            val displayOnlyAnalysis = app.fingerprintDetector!!.analyze(
                displayedSamples.map { sample ->
                    FingerprintOutput(
                        expectedCount = sample["expectedCount"]!!.jsonPrimitive.int,
                        text = sample["text"]!!.jsonPrimitive.content,
                    )
                },
            )
            assertEquals(0, displayOnlyAnalysis.usedOutputs, "Redacted display strings must not be reused as detector input")

            val postedSamplesJson = Json.parseToJsonElement(app.fingerprintTestRunRepository.find(runId.toLong())!!.samplesJson)
            assertFalse(postedSamplesJson.toString().contains(rawSample))
            assertTrue(postedSamplesJson.jsonArray.all { it.jsonObject["text"]!!.jsonPrimitive.content == rawSample.replace(credential, "[REDACTED]") })

            val detail = client.get("/admin/api/model-tests/fingerprint/$runId") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, detail.status, detail.bodyAsText())
            val detailText = detail.bodyAsText()
            val detailBody = Json.parseToJsonElement(detailText).jsonObject
            assertFalse(detailText.contains(rawSample), "Retrieved history must not expose the original numeric samples")
            assertEquals(displayedSamples.map { it["text"] }, detailBody["samples"]!!.jsonArray.map { it.jsonObject["text"] })
            assertEquals(analysis, Json.decodeFromString<FingerprintAnalysis>(detailBody["analysis"]!!.toString()))

            val history = client.get("/admin/api/model-tests/fingerprint") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, history.status, history.bodyAsText())
            val historyText = history.bodyAsText()
            val historyItem = Json.parseToJsonElement(historyText).jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == runId }
            assertFalse(historyText.contains(rawSample), "History listing must not expose sample text")
            assertNull(historyItem.jsonObject["analysis"])
            assertTrue(historyItem.jsonObject["samples"]!!.jsonArray.isEmpty())

            val audit = client.get("/admin/api/audit-logs?action=model_test.fingerprint_run") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, audit.status, audit.bodyAsText())
            val auditText = audit.bodyAsText()
            val auditItem = Json.parseToJsonElement(auditText).jsonArray.single {
                it.jsonObject["action"]!!.jsonPrimitive.content == "model_test.fingerprint_run"
            }.jsonObject
            val auditDetails = auditItem["details"]!!.jsonObject
            val requiredAuditFields = setOf("channelId", "model", "keyId", "protocol", "decision")
            assertTrue(auditDetails.keys == requiredAuditFields || auditDetails.keys == requiredAuditFields + "prediction")
            assertFalse(auditText.contains(rawSample), "Audit output must not contain upstream sample text")
            assertFalse("samples" in auditDetails)

            val upstreamRequests = captured.filter { it.method == "POST" }
            assertEquals(3, upstreamRequests.size)
            assertTrue(upstreamRequests.all {
                it.host == "fingerprint.synthetic.invalid" && it.path == "/v1/responses" && it.authorized
            }, "Every sample request must stay in MockEngine with only the synthetic credential")
        }
    }

    @Test
    fun `admin web search settings round trip without exposing the api key`() = runBlocking {
        // 共享测试数据库：无论断言在哪一步失败，finally 都会把开关恢复为关闭。
        testApplication {
            application { app.install(this) }
            val client = jsonClient()

            val unauthorized = client.get("/admin/api/web-search-settings")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val (sessionCookie, csrf) = client.loginAdmin()
            try {
                val initial = client.get("/admin/api/web-search-settings") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
                assertEquals(HttpStatusCode.OK, initial.status)
                assertEquals(
                    "environment",
                    Json.parseToJsonElement(initial.bodyAsText()).jsonObject["source"]?.jsonPrimitive?.content,
                )

                val updated = client.put("/admin/api/web-search-settings") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"enabled":true,"provider":"tavily","apiKey":"tvly-secret","baseUrl":null,"maxResults":8,"timeoutMs":10000,"maxRounds":4,"maxPageChars":1200}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, updated.status)
                val body = updated.bodyAsText()
                val json = Json.parseToJsonElement(body).jsonObject
                assertEquals(true, json["enabled"]?.jsonPrimitive?.boolean)
                assertEquals("settings", json["source"]?.jsonPrimitive?.content)
                assertEquals("settings", json["apiKeySource"]?.jsonPrimitive?.content)
                assertEquals(true, json["apiKeySet"]?.jsonPrimitive?.boolean)
                assertEquals(8, json["maxResults"]?.jsonPrimitive?.int)
                assertEquals(1200, json["maxPageChars"]?.jsonPrimitive?.int)
                assertFalse(body.contains("tvly-secret"), "BODY=[$body]")

                // 切换后端时不能沿用旧后端的密钥。
                val switched = client.put("/admin/api/web-search-settings") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"enabled":true,"provider":"brave","baseUrl":null,"maxResults":6,"timeoutMs":15000,"maxRounds":6,"maxPageChars":1500}""",
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, switched.status)

                // 地址长度在保存前校验，避免数据库列宽错误变成 500。
                val tooLong = client.put("/admin/api/web-search-settings") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"enabled":true,"provider":"tavily","apiKey":"tvly-secret","baseUrl":"https://example.com/${"a".repeat(500)}","maxResults":6,"timeoutMs":15000,"maxRounds":6,"maxPageChars":1500}""",
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, tooLong.status)
            } finally {
                // 共享测试数据库：即使断言失败也把开关恢复为关闭，避免影响其它用例。
                val disabled = client.put("/admin/api/web-search-settings") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"enabled":false,"provider":"tavily","baseUrl":null,"maxResults":6,"timeoutMs":15000,"maxRounds":6,"maxPageChars":1500}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, disabled.status)
            }
        }
    }

    @Test
    fun `admin runs candy iq test and reads history`() = runBlocking {
        val channel = createChannel("iq-test", "iq-json")
        val candyResponse =
            """{"answer":21,"reason":"先取完圆形苹果 7 颗、圆形桃子 9 颗、圆形西瓜 8 颗和五角星形西瓜 4 颗，再取一颗。"}"""

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val definitions = client.get("/admin/api/iq-tests/benchmarks") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, definitions.status)
            assertEquals(2, Json.parseToJsonElement(definitions.bodyAsText()).jsonArray.size)
            // 超时口径由服务端下发，管理台不再写死 180/120 秒。
            val pelicanDefinition = Json.parseToJsonElement(definitions.bodyAsText()).jsonArray
                .map { it.jsonObject }
                .first { it["id"]?.jsonPrimitive?.content == "pelican" }
            assertEquals(180_000L, pelicanDefinition["defaultTimeoutMs"]?.jsonPrimitive?.long)
            // 测试配置未设置 IQ_TEST_TIMEOUT_MS，题目默认值即生效值。
            assertEquals(180_000L, pelicanDefinition["effectiveTimeoutMs"]?.jsonPrimitive?.long)
            assertEquals(600_000L, pelicanDefinition["maxTimeoutMs"]?.jsonPrimitive?.long)

            val judges = client.get("/admin/api/iq-tests/judges") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, judges.status)
            val judgesBody = Json.parseToJsonElement(judges.bodyAsText()).jsonObject
            val judgeIds = judgesBody["judges"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(judgeIds?.contains("local") == true, judges.bodyAsText())
            assertTrue(judgeIds?.contains("manxue") == true, judges.bodyAsText())
            assertEquals("local", judgesBody["defaultJudgeId"]?.jsonPrimitive?.content)

            val targets = client.get("/admin/api/iq-tests/targets") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, targets.status)
            assertTrue(targets.bodyAsText().contains(channel.id), targets.bodyAsText())

            val run = client.runIqTestJob(
                sessionCookie,
                csrf,
                """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","judgeId":"local"}""",
            )
            assertEquals(HttpStatusCode.OK, run.status)
            val body = Json.parseToJsonElement(run.bodyAsText()).jsonObject
            assertEquals("candy", body["benchmark"]?.jsonPrimitive?.content)
            assertEquals("passed", body["verdict"]?.jsonPrimitive?.content)
            assertEquals(candyResponse, body["responseText"]?.jsonPrimitive?.content)
            assertEquals("21", body["expectedAnswer"]?.jsonPrimitive?.content)
            assertEquals("21", body["matchedAnswer"]?.jsonPrimitive?.content)
            assertEquals(2, body["checks"]?.jsonArray?.size)
            assertEquals("json", body["checks"]!!.jsonArray.first().jsonObject["id"]?.jsonPrimitive?.content)
            assertTrue(body["checks"]!!.jsonArray.all { it.jsonObject["passed"]?.jsonPrimitive?.boolean == true })
            val judgeResult = body["judgeResult"]
            assertTrue(judgeResult == null || judgeResult is JsonNull, run.bodyAsText())
            val html = body["html"]
            assertTrue(html == null || html is JsonNull, run.bodyAsText())
            assertTrue(body["prompt"]?.jsonPrimitive?.content?.contains("糖果") == true)
            val id = body["id"]?.jsonPrimitive?.content

            val history = client.get("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, history.status)
            val items = Json.parseToJsonElement(history.bodyAsText()).jsonArray
            assertEquals(1, items.size)
            assertEquals(id, items[0].jsonObject["id"]?.jsonPrimitive?.content)
            val historyPrompt = items[0].jsonObject["prompt"]
            assertTrue(historyPrompt == null || historyPrompt is JsonNull)

            val detail = client.get("/admin/api/iq-tests/$id") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, detail.status)
            val detailBody = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals(candyResponse, detailBody["responseText"]?.jsonPrimitive?.content)
            assertEquals(2, detailBody["checks"]?.jsonArray?.size)
        }
    }

    @Test
    fun `candy grades the original answer when redaction changes its numeric value`() = runBlocking {
        val channel = createChannel("iq-numeric-credential", "iq-json")
        val key = channel.keys.single()
        app.channelRepository.update(
            id = channel.id, name = channel.name, baseUrl = channel.baseUrl,
            authType = channel.authType, enabled = true,
            keys = listOf(ChannelKeyUpdate(id = key.id, name = key.name,
                credential = app.credentialCipher.encrypt("21"), models = key.models)),
        )
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.runIqTestJob(sessionCookie, csrf,
                """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("passed", body["verdict"]!!.jsonPrimitive.content)
            assertTrue(body["responseText"]!!.jsonPrimitive.content.contains("[REDACTED]"))
            assertFalse(body["responseText"]!!.jsonPrimitive.content.contains("21"))
            val detail = client.get("/admin/api/iq-tests/${body["id"]!!.jsonPrimitive.content}") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val persisted = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals("passed", persisted["verdict"]!!.jsonPrimitive.content)
            assertTrue(persisted["checks"]!!.jsonArray.all { it.jsonObject["passed"]!!.jsonPrimitive.boolean })
            assertFalse(persisted["responseText"]!!.jsonPrimitive.content.contains("21"))
            assertTrue(fake.manxueRequests.isEmpty(), "Candy grading must remain local")
        }
    }

    @Test
    fun `admin can override iq test timeout per run and invalid values are rejected`() = runBlocking {
        val channel = createChannel("iq-test-timeout", "iq-json")
        val candyResponse =
            """{"answer":21,"reason":"先取完圆形苹果 7 颗、圆形桃子 9 颗、圆形西瓜 8 颗和五角星形西瓜 4 颗，再取一颗。"}"""

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            suspend fun runWithTimeout(payload: String) = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            // 单次覆盖放宽到 5 分钟：请求正常完成，题目默认值不受影响。
            val accepted = client.runIqTestJob(
                sessionCookie,
                csrf,
                """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","timeoutMs":300000}""",
            )
            assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
            assertEquals("passed", Json.parseToJsonElement(accepted.bodyAsText()).jsonObject["verdict"]?.jsonPrimitive?.content)
            assertEquals(candyResponse, Json.parseToJsonElement(accepted.bodyAsText()).jsonObject["responseText"]?.jsonPrimitive?.content)

            // 非正值和超过上限的值都直接拒绝，不能悄悄退化成立即超时。
            for (invalid in listOf(0, -1, 600_001)) {
                val rejected = runWithTimeout(
                    """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","timeoutMs":$invalid}""",
                )
                assertEquals(HttpStatusCode.BadRequest, rejected.status, "timeoutMs=$invalid: ${rejected.bodyAsText()}")
                assertTrue(rejected.bodyAsText().contains("invalid_iq_test_timeout"), rejected.bodyAsText())
            }
        }
    }

    @Test
    fun `admin keeps legacy candy html verdict in history detail`() = runBlocking {
        val channel = createChannel("iq-test-legacy", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, _) = client.loginAdmin()
            val record = app.iqTestRunRepository.insert(
                NewIqTestRun(
                    benchmark = "candy",
                    channelId = channel.id,
                    channelName = channel.name,
                    channelKeyId = null,
                    channelKeyName = null,
                    model = "gpt-4o-mini",
                    upstreamModel = "gpt-4o-mini",
                    protocol = GatewayProtocol.CHAT_COMPLETIONS.value,
                    success = true,
                    statusCode = 200,
                    latencyMs = 10,
                    verdict = "passed",
                    prompt = "legacy prompt",
                    responseText = "<html><body><p>答案是 21 颗。</p></body></html>",
                    expectedAnswer = 21,
                    matchedAnswer = 21,
                    errorMessage = null,
                    usage = null,
                    upstreamReportedModel = "gpt-4o-mini",
                    judgeId = "local",
                    judgeLabel = "本地结构 + 人工",
                    judgeVerdict = "passed",
                    judgeResultLabel = "通过",
                    judgeReason = "旧版判分",
                    judgeScore = null,
                    judgeDetails = null,
                    judgeCheckedAt = 0L,
                ),
            )

            val detail = client.get("/admin/api/iq-tests/${record.id}") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, detail.status)
            val body = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            assertEquals("passed", body["verdict"]?.jsonPrimitive?.content)
            assertNotNull(body["html"]?.jsonPrimitive?.content, detail.bodyAsText())
            val checks = body["checks"]!!.jsonArray
            assertEquals(1, checks.size)
            assertTrue(
                checks.single().jsonObject["label"]!!.jsonPrimitive.content.contains("最终答案包含 21"),
                detail.bodyAsText(),
            )
        }
    }

    @Test
    fun `admin failed iq test omits fabricated judge result`() = runBlocking {
        val channel = createChannel("iq-fail", "500")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val response = client.runIqTestJob(
                sessionCookie,
                csrf,
                """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","judgeId":"local"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("failed", body["verdict"]?.jsonPrimitive?.content)
            val judgeResult = body["judgeResult"]
            assertTrue(judgeResult == null || judgeResult is JsonNull, response.bodyAsText())
        }
    }

    @Test
    fun `admin classifies pelican html with manxue`() = runBlocking {
        val channel = createChannelWithBaseUrl("iq-html", "${fake.baseUrl}/iq-html/v1")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            suspend fun run(benchmark: String): kotlinx.serialization.json.JsonObject {
                val response = client.runIqTestJob(
                    sessionCookie,
                    csrf,
                    """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"$benchmark","judgeId":"manxue"}""",
                )
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }

            val pelican = run("pelican")
            assertEquals("passed", pelican["verdict"]?.jsonPrimitive?.content)
            assertEquals("manxue", pelican["judgeResult"]?.jsonObject?.get("judgeId")?.jsonPrimitive?.content)
            assertEquals("passed", pelican["judgeResult"]?.jsonObject?.get("verdict")?.jsonPrimitive?.content)
            assertEquals(1, fake.manxueRequests.size)
            assertTrue(fake.manxueRequests.single().body.contains("<svg"))
        }
    }

    @Test
    fun `iq test job status requires admin and reports unknown jobs`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, _) = client.loginAdmin()

            val unauthenticated = client.get("/admin/api/iq-tests/jobs/unknown")
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

            val missing = client.get("/admin/api/iq-tests/jobs/unknown") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("iq_test_job_not_found"), missing.bodyAsText())
        }
    }

    @Test
    fun `admin rejects unknown iq judge`() = runBlocking {
        val channel = createChannel("iq-test", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"missing"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("iq_judge_not_found"), response.bodyAsText())
        }
    }

    @Test
    fun `admin rejects oversized iq prompt`() = runBlocking {
        val channel = createChannel("iq-test", "ok")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","prompt":"${"a".repeat(20_001)}"}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("提示词长度"), response.bodyAsText())
        }
    }

    @Test
    fun `model test returns upstream failure details`() = runBlocking {
        val channel = createChannel("model-test-fail", "500")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val response = client.post("/admin/api/channels/${channel.id}/model-test") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","protocol":"chat_completions","prompt":"hi"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false, json["success"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals("500", json["statusCode"]?.jsonPrimitive?.content)
            assertEquals("upstream down", json["errorMessage"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `admin can update and clear avatar`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()
            val avatar = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

            val updated = client.put("/admin/api/account/avatar") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"avatarDataUrl":"$avatar"}""")
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            assertTrue(updated.bodyAsText().contains(avatar))

            val me = client.get("/admin/api/me") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals(
                avatar,
                Json.parseToJsonElement(me.bodyAsText()).jsonObject["avatarDataUrl"]?.jsonPrimitive?.content,
            )

            val invalid = client.put("/admin/api/account/avatar") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"avatarDataUrl":"data:image/svg+xml;base64,PHN2Zz48L3N2Zz4="}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("invalid_avatar"))

            val cleared = client.put("/admin/api/account/avatar") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"avatarDataUrl":null}""")
            }
            assertEquals(HttpStatusCode.OK, cleared.status)
            assertTrue(!cleared.bodyAsText().contains("avatarDataUrl"))
        }
    }

    @Test
    fun `admin password change revokes sessions and uses new password`() = runBlocking {
        // 共享测试数据库：无论断言在哪一步失败，finally 都会把密码还原。
        val initialPassword = initialAdminPassword
        val rotatedPassword = "new-password-123"
        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            suspend fun restoreAdminPassword() {
                // 修改成功后当前会话会被吊销，必须用新密码重新登录再改回初始密码；
                // 如果修改本身没有生效，则确认初始密码仍然可用即可。
                val rotatedLogin = client.post("/admin/api/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","password":"$rotatedPassword"}""")
                }
                if (rotatedLogin.status == HttpStatusCode.OK) {
                    val (newSession, newCsrf) = client.loginAdmin(password = rotatedPassword)
                    val restored = client.put("/admin/api/account/password") {
                        header(HttpHeaders.Cookie, newSession)
                        header("X-CSRF-Token", newCsrf)
                        contentType(ContentType.Application.Json)
                        setBody("""{"currentPassword":"$rotatedPassword","newPassword":"$initialPassword"}""")
                    }
                    assertEquals(HttpStatusCode.NoContent, restored.status, "还原管理员密码失败")
                    return
                }
                val initialLogin = client.post("/admin/api/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","password":"$initialPassword"}""")
                }
                assertEquals(HttpStatusCode.OK, initialLogin.status, "管理员密码既不是新密码也不是初始密码")
            }

            try {
                val wrongCurrent = client.put("/admin/api/account/password") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"wrong-password","newPassword":"$rotatedPassword"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, wrongCurrent.status)
                assertTrue(wrongCurrent.bodyAsText().contains("invalid_current_password"))

                val changed = client.put("/admin/api/account/password") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"currentPassword":"$initialPassword","newPassword":"$rotatedPassword"}""")
                }
                assertEquals(HttpStatusCode.NoContent, changed.status)

                val oldSession = client.get("/admin/api/me") {
                    header(HttpHeaders.Cookie, sessionCookie)
                }
                assertEquals(HttpStatusCode.Unauthorized, oldSession.status)

                val oldPassword = client.post("/admin/api/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","password":"$initialPassword"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, oldPassword.status)

                val newPassword = client.post("/admin/api/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"admin","password":"$rotatedPassword"}""")
                }
                assertEquals(HttpStatusCode.OK, newPassword.status)
            } finally {
                // 共享测试数据库：无论断言是否失败都还原密码，避免影响同类中的后续用例。
                restoreAdminPassword()
            }
        }
    }
}
