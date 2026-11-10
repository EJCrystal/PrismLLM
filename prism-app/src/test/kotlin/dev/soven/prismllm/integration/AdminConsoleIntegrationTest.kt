package dev.soven.prismllm.integration

import dev.soven.prismllm.shared.domain.GatewayProtocol
import dev.soven.prismllm.admin.domain.NewIqTestRun
import dev.soven.prismllm.admin.domain.IqVisionSettingsRecord
import dev.soven.prismllm.admin.domain.effective
import dev.soven.prismllm.admin.persistence.IqVisionSettingsUpdate
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
    fun `iq vision settings concurrent endpoint switch does not pair key with the wrong endpoint`() = runBlocking {
        val repository = app.iqVisionSettingsRepository
        val defaults = app.iqVisionDefaults
        // 先写入一个端点固定、带密钥的配置，模拟「端点 A 上有一把已保存的密钥」。
        repository.update {
            IqVisionSettingsUpdate(
                IqVisionSettingsRecord(
                    enabled = true,
                    baseUrl = "https://a.example.com/v1",
                    model = "vision-a",
                    apiKey = "key-a",
                    timeoutMs = 60_000,
                    updatedAt = 0,
                ),
            )
        }

        // 两个请求并发把端点换成各自的地址，都不重新填密钥：都应被拒绝，
        // 因为换端点且不显式清除时不能沿用旧密钥。
        val rejections = coroutineScope {
            listOf("https://b.example.com/v1", "https://c.example.com/v1").map { endpoint ->
                async(Dispatchers.IO) {
                    runCatching {
                        repository.update { stored ->
                            val previous = stored.effective(defaults)
                            if (previous.baseUrl != endpoint) {
                                throw IllegalStateException("endpoint changed without a new key")
                            }
                            null
                        }
                    }.isFailure
                }
            }.awaitAll()
        }
        assertTrue(rejections.all { it }, "REJECTIONS=$rejections")

        // 配置必须保持原样：端点仍是 A，密钥仍是 A 的。
        val after = assertNotNull(repository.load())
        assertEquals("https://a.example.com/v1", after.baseUrl)
        assertEquals("key-a", after.apiKey)
    }

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
    fun `admin iq vision settings round trip without exposing the api key`() = runBlocking {
        testApplication {
            application { app.install(this) }
            val client = jsonClient()

            val unauthorized = client.get("/admin/api/iq-vision-settings")
            assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

            val (sessionCookie, csrf) = client.loginAdmin()
            val initial = client.get("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            val initialJson = Json.parseToJsonElement(initial.bodyAsText()).jsonObject
            // 未保存过配置时沿用环境变量默认值，测试环境没有配置，因此是 environment 来源。
            assertEquals("environment", initialJson["source"]?.jsonPrimitive?.content)
            // 视觉判分器必须始终出现在列表里，启用与否由配置决定。
            val judges = client.get("/admin/api/iq-tests/judges") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val judgeIds = Json.parseToJsonElement(judges.bodyAsText()).jsonObject["judges"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue("vision" in judgeIds, "JUDGES=$judgeIds")

            val updated = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"http://127.0.0.1:8080/v1/","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":90000}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updated.status)
            val body = updated.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals(true, json["enabled"]?.jsonPrimitive?.boolean)
            assertEquals("settings", json["source"]?.jsonPrimitive?.content)
            assertEquals("settings", json["apiKeySource"]?.jsonPrimitive?.content)
            assertEquals(true, json["apiKeySet"]?.jsonPrimitive?.boolean)
            assertEquals("gpt-vision", json["model"]?.jsonPrimitive?.content)
            // 末尾斜杠统一去掉，避免拼出 //chat/completions。
            assertEquals("http://127.0.0.1:8080/v1", json["baseUrl"]?.jsonPrimitive?.content)
            assertEquals(90_000, json["timeoutMs"]?.jsonPrimitive?.long)
            assertFalse(body.contains("vision-secret"), "BODY=[$body]")

            // 端点变更后不能沿用旧密钥，必须显式提供新密钥。
            val switched = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"https://vision.example.com/v1","model":"gpt-vision","timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, switched.status)
            // 被拒绝的请求不能改动已保存的配置。
            val afterRejected = client.get("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val afterRejectedJson = Json.parseToJsonElement(afterRejected.bodyAsText()).jsonObject
            assertEquals("http://127.0.0.1:8080/v1", afterRejectedJson["baseUrl"]?.jsonPrimitive?.content)
            assertEquals(true, afterRejectedJson["apiKeySet"]?.jsonPrimitive?.boolean)

            // 明确勾选清除时可以换端点，结果是端点生效、密钥为空。
            val switchedCleared = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"https://vision.example.com/v1","model":"gpt-vision","clearApiKey":true,"timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.OK, switchedCleared.status)
            val switchedClearedJson = Json.parseToJsonElement(switchedCleared.bodyAsText()).jsonObject
            assertEquals("https://vision.example.com/v1", switchedClearedJson["baseUrl"]?.jsonPrimitive?.content)
            assertEquals(false, switchedClearedJson["apiKeySet"]?.jsonPrimitive?.boolean)
            assertEquals("none", switchedClearedJson["apiKeySource"]?.jsonPrimitive?.content)

            // 启用时超时必须落在合法区间，且在保存前完成校验。
            val invalidTimeout = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"https://vision.example.com/v1","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":100}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, invalidTimeout.status)

            // 关闭状态下也会写库，列宽校验不能只在启用时生效，否则超长值会撞上 varchar 变成 500。
            val longModel = "m".repeat(201)
            val oversizedModel = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":false,"baseUrl":"https://vision.example.com/v1","model":"$longModel","timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, oversizedModel.status)

            val invalidScheme = client.put("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"enabled":true,"baseUrl":"ftp://example.com","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":60000}""",
                )
            }
            assertEquals(HttpStatusCode.BadRequest, invalidScheme.status)

            // 只检查前缀会放行这类地址：保存成功，但请求阶段才失败。直接调用接口也必须被拒绝。
            listOf("https://example.com:abc/v1", "https://example com/v1", "https://example.com:70000/v1").forEach { bad ->
                val invalidHost = client.put("/admin/api/iq-vision-settings") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"enabled":true,"baseUrl":"$bad","model":"gpt-vision","apiKey":"vision-secret","timeoutMs":60000}""",
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, invalidHost.status, "BASE_URL=$bad")
            }

            // 保存后立即生效：重新读取必须反映新值，不需要重启。
            val reloaded = client.get("/admin/api/iq-vision-settings") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val reloadedJson = Json.parseToJsonElement(reloaded.bodyAsText()).jsonObject
            assertEquals("gpt-vision", reloadedJson["model"]?.jsonPrimitive?.content)
            assertEquals(true, reloadedJson["enabled"]?.jsonPrimitive?.boolean)
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

            val run = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","judgeId":"local"}""")
            }
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

            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy","judgeId":"local"}""")
            }
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
                val response = client.post("/admin/api/iq-tests") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header("X-CSRF-Token", csrf)
                    contentType(ContentType.Application.Json)
                    setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"$benchmark","judgeId":"manxue"}""")
                }
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
    fun `admin scores pelican svg with built-in vision judge`() = runBlocking {
        val channel = createChannelWithBaseUrl("iq-html", "${fake.baseUrl}/iq-html/v1")

        testApplication {
            application { app.install(this) }
            val client = jsonClient()
            val (sessionCookie, csrf) = client.loginAdmin()

            val judges = client.get("/admin/api/iq-tests/judges") {
                header(HttpHeaders.Cookie, sessionCookie)
            }
            val judgeIds = Json.parseToJsonElement(judges.bodyAsText()).jsonObject["judges"]?.jsonArray
                ?.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(judgeIds?.contains("vision") == true, judges.bodyAsText())

            val response = client.post("/admin/api/iq-tests") {
                header(HttpHeaders.Cookie, sessionCookie)
                header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json)
                setBody("""{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"vision"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("passed", body["verdict"]?.jsonPrimitive?.content)
            val judge = assertNotNull(body["judgeResult"]?.jsonObject)
            assertEquals("vision", judge["judgeId"]?.jsonPrimitive?.content)
            assertEquals("passed", judge["verdict"]?.jsonPrimitive?.content)
            assertEquals("视觉评分通过", judge["label"]?.jsonPrimitive?.content)
            assertEquals(90.0, judge["score"]?.jsonPrimitive?.content?.toDouble())
            val details = assertNotNull(judge["details"]?.jsonObject)
            assertEquals(
                "通过：白色长颈鸟，长喙和橙色喉囊清楚",
                details["鹈鹕特征"]?.jsonPrimitive?.content,
            )
            assertNotNull(details["技术信息"])

            val visionRequest = fake.requests.first { it.scenario == "vision" }
            assertTrue(visionRequest.body.contains("data:image/png;base64,"))
            assertEquals(6, Regex("data:image/png;base64,").findAll(visionRequest.body).count())
            assertNull(visionRequest.authorization)
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
