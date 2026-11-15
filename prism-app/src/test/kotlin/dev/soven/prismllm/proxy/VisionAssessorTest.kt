package dev.soven.prismllm.proxy

import com.sun.net.httpserver.HttpServer
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Tag
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class VisionAssessorTest {
    private val client = HttpClient()
    private val assessor = OpenAiVisionAssessor(client, "http://localhost:1", model = "vision-test")

    @AfterTest
    fun tearDown() {
        client.close()
    }

    @Test
    fun `requests json object output and parses fenced response`() = runBlocking {
        val requestBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val body = buildJsonObject {
                putJsonArray("choices") {
                    addJsonObject {
                        putJsonObject("message") {
                            put("content", "```json\n{\"verdict\":\"incorrect\",\"score\":10,\"reason\":\"看不到鹈鹕\"}\n```")
                        }
                    }
                }
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val httpAssessor = OpenAiVisionAssessor(
                client,
                "http://127.0.0.1:${server.address.port}/v1",
                model = "vision-test",
            )
            val result = httpAssessor.assess(
                VisionRequest(
                    frames = listOf(VisionFrame(0f, byteArrayOf(1, 2, 3))),
                    benchmark = "pelican",
                    structure = analyzePelicanStructure("<svg/>"),
                    animation = VisionAnimationInfo(2f, 1, false, false, true),
                ),
            )
            val request = Json.parseToJsonElement(requestBody.get()).jsonObject

            assertEquals("json_object", request["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
            assertEquals("vision-test", request["model"]?.jsonPrimitive?.content)
            assertEquals(2, request["messages"]?.jsonArray?.size)
            assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
            assertEquals("看不到鹈鹕", result.reason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `parses score and reason but requires aspects for a pass`() {
        val result = assessor.parseAssessment("""{"verdict":"passed","score":86,"reason":"鹈鹕和自行车清晰"}""")

        // 五项必检维度缺失时，模型自报 passed 不能直接通过。
        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(86.0, result.score)
        assertEquals("鹈鹕和自行车清晰", result.reason)
    }

    @Test
    fun `all five aspects passed yields passed`() {
        val text = """
            {"aspects":{
              "鹈鹕特征":{"status":"passed","detail":"白色长颈鸟"},
              "自行车结构":{"status":"passed","detail":"结构完整"},
              "骑行关系":{"status":"passed","detail":"坐在车座上"},
              "动画连贯性":{"status":"passed","detail":"逐帧有连续运动"},
              "画面完成度":{"status":"passed","detail":"主体完整"}
            },"verdict":"passed","score":90,"reason":"五项通过"}
        """.trimIndent()

        assertEquals(IqJudgeVerdict.PASSED, assessor.parseAssessment(text).verdict)
    }

    @Test
    fun `five item summary never substitutes for structured aspect results`() {
        val result = assessor.parseAssessment(
            """{"verdict":"review","score":95,"reason":"五项均通过：鹈鹕特征完整，自行车结构合理，骑行关系正确，轮辐与踏板的连续运动肉眼可见，画面完整居中。"}""",
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(0, result.aspects.size)
    }

    @Test
    fun `summary without evidence for every aspect remains review`() {
        val result = assessor.parseAssessment(
            """{"verdict":"passed","score":95,"reason":"五项均通过：鹈鹕特征完整，自行车结构合理，骑行关系正确，画面完整居中。"}""",
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(0, result.aspects.size)
    }

    @Test
    fun `contradictory summary cannot pass without structured results`() {
        val result = assessor.parseAssessment(
            """{"verdict":"passed","score":95,"reason":"五项均通过：鹈鹕特征完整，自行车结构合理，但骑行关系不正确，轮辐与踏板的连续运动肉眼可见，画面完整居中。"}""",
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(0, result.aspects.size)
    }

    @Test
    fun `missing structured results are repaired with original frames and one followup`() = runBlocking {
        val (result, requests) = assessResponses(
            listOf(
                """{"verdict":"passed","score":95,"reason":"五项均通过"}""",
                completeResponse(),
            ),
        )

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(5, result.aspects.size)
        assertEquals(2, requests.size)
        val messages = requests.last()["messages"]!!.jsonArray
        assertEquals(4, messages.size)
        assertEquals("assistant", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(requests.first()["messages"]!!.jsonArray[1], messages[1])
        assertTrue(messages.last().jsonObject["content"]!!.jsonPrimitive.content.contains("不得依据分数或摘要补造通过结果"))
    }

    @Test
    fun `incomplete repair stays review and never retries a third time`() = runBlocking {
        val (result, requests) = assessResponses(
            listOf("""{"verdict":"passed","score":95,"reason":"五项均通过"}"""),
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(2, requests.size)
        assertTrue(result.reason.orEmpty().contains("补问后仍缺少完整逐项结果"))
    }

    @Test
    fun `partial aspect results trigger a repair instead of automatic pass`() = runBlocking {
        val (result, requests) = assessResponses(
            listOf(
                """{"verdict":"passed","aspects":{"鹈鹕特征":{"status":"passed","detail":"特征完整"}}}""",
                completeResponse(),
            ),
        )

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(5, result.aspects.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun `explicit unknown results stay review without asking model to pass`() = runBlocking {
        val (result, requests) = assessResponses(listOf(completeResponse(unknownAspect = "骑行关系")))

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(1, requests.size)
        assertEquals(VisionAspectStatus.UNKNOWN, result.aspects[2].status)
    }

    @Test
    fun `all five structured passes override contradictory review verdict`() {
        val result = assessor.parseAssessment(completeResponse(verdict = "review"))

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
    }

    @Test
    fun `parses json wrapped in markdown fence`() {
        val result = assessor.parseAssessment(
            "```json\n{\"verdict\":\"incorrect\",\"score\":10,\"reason\":\"看不到鹈鹕\"}\n```",
        )

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(10.0, result.score)
    }

    @Test
    fun `parses alternate aspect container and english keys`() {
        val result = assessor.parseAssessment(
            """
                ```json
                {"items":{
                  "pelican_features":{"status":"passed","reason":"白色长颈鸟"},
                  "bicycle_structure":{"status":"passed","reason":"结构完整"},
                  "riding_relationship":{"status":"passed","reason":"坐在车座上"},
                  "animation_continuity":{"status":"passed","reason":"逐帧有连续运动"},
                  "composition_completeness":{"status":"passed","reason":"主体完整"}
                },"verdict":"passed","score":90,"summary":"五项通过"}
                ```
            """.trimIndent(),
        )

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(5, result.aspects.size)
        assertEquals("白色长颈鸟", result.aspects.first().detail)
        assertEquals("五项通过", result.reason)
    }

    @Test
    fun `parses item arrays with english aliases and alternate status fields`() {
        val result = assessor.parseAssessment(
            """
                {"items":[
                  {"name":"pelican_features","status":"passed","description":"鹈鹕特征完整"},
                  {"key":"bicycle_structure","result":"pass","reason":"车架与两轮完整"},
                  {"aspect":"riding_relationship","passed":true,"text":"骑行关系正确"},
                  {"dimension":"animation_continuity","verdict":"passed","evidence":"六帧连续运动"},
                  {"criterion":"composition_completeness","status":"passed","detail":"主体完整"}
                ],"verdict":"passed","score":95,"summary":"五项均通过"}
            """.trimIndent(),
        )

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(5, result.aspects.size)
        assertEquals("鹈鹕特征完整", result.aspects[0].detail)
        assertEquals(VisionAspectStatus.PASSED, result.aspects[2].status)
        assertEquals("六帧连续运动", result.aspects[3].detail)
        assertEquals(95.0, result.score)
    }

    @Test
    fun `missing array aspect remains unknown and blocks automatic pass`() {
        val result = assessor.parseAssessment(
            """
                {"items":[
                  {"name":"pelican_features","status":"passed"},
                  {"name":"bicycle_structure","status":"passed"},
                  {"name":"riding_relationship","status":"passed"},
                  {"name":"animation_continuity","status":"passed"}
                ],"verdict":"passed","score":95}
            """.trimIndent(),
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(VisionAspectStatus.UNKNOWN, result.aspects.last().status)
    }

    @Test
    fun `rider bicycle alias never substitutes for missing bicycle structure`() {
        val result = assessor.parseAssessment(
            """{"items":[
              {"name":"pelican_features","status":"passed","detail":"特征完整"},
              {"name":"rider_bicycle_relationship","status":"passed","detail":"骑行关系正确"},
              {"name":"animation_continuity","status":"passed","detail":"连续运动"},
              {"name":"composition_completeness","status":"passed","detail":"画面完整"}
            ],"verdict":"passed"}""",
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(VisionAspectStatus.UNKNOWN, result.aspects[1].status)
        assertEquals(VisionAspectStatus.PASSED, result.aspects[2].status)
    }

    @Test
    fun `riding alias cannot hide explicit bicycle structure failure`() {
        val result = assessor.parseAssessment(
            """{"items":[
              {"name":"pelican_features","status":"passed"},
              {"name":"rider_bicycle_relationship","status":"passed"},
              {"name":"bicycle_structure","status":"failed","detail":"缺少链条"},
              {"name":"animation_continuity","status":"passed"},
              {"name":"composition_completeness","status":"passed"}
            ],"verdict":"passed"}""",
        )

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
        assertEquals("缺少链条", result.aspects[1].detail)
    }

    @Test
    fun `duplicate item cannot overwrite explicit failure with a later pass`() {
        val result = assessor.parseAssessment(
            """{"items":[
              {"name":"pelican_features","status":"passed"},
              {"name":"bicycle_structure","status":"failed","detail":"缺少链条"},
              {"name":"bicycle_structure","status":"passed"},
              {"name":"riding_relationship","status":"passed"},
              {"name":"animation_continuity","status":"passed"},
              {"name":"composition_completeness","status":"passed"}
            ],"verdict":"passed"}""",
        )

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
        assertEquals("缺少链条", result.aspects[1].detail)
    }

    @Test
    fun `conflicting alternate container cannot hide an explicit failure`() {
        val full = Json.parseToJsonElement(completeResponse()).jsonObject
        val result = assessor.parseAssessment(
            JsonObject(full + ("items" to Json.parseToJsonElement(
                """[{"name":"bicycle_structure","status":"failed","detail":"缺少链条"}]""",
            ))).toString(),
        )

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
    }

    @Test
    fun `prefers fenced assessment over surrounding json objects`() {
        val text = """
            元数据：{"example":true}
            ```json
            {"verdict":"incorrect","score":10,"reason":"看不到鹈鹕"}
            ```
            补充：{"done":true}
        """.trimIndent()

        val result = assessor.parseAssessment(text)

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(10.0, result.score)
        assertEquals("看不到鹈鹕", result.reason)
    }

    @Test
    fun `parses unlabeled fence with prose braces and escaped quotes`() {
        val text = """
            检测项目 {鹈鹕, 自行车} 的结果如下：
            ```
            {"verdict":"incorrect","score":10,"reason":"缺少 {车轮}，提示为 \"未绘制\""}
            ```
            后续说明 {需要重绘}。
        """.trimIndent()

        val result = assessor.parseAssessment(text)

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals("缺少 {车轮}，提示为 \"未绘制\"", result.reason)
    }

    @Test
    fun `parses plain assessment before trailing prose braces`() {
        val result = assessor.parseAssessment(
            """{"verdict":"incorrect","score":10,"reason":"看不到鹈鹕"} 后续说明 {需要重绘}。""",
        )

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(10.0, result.score)
    }

    @Test
    fun `malformed fenced json still asks for manual review`() {
        val result = assessor.parseAssessment(
            "```json\n{\"verdict\":\"passed\",\"score\":90,}\n```",
        )

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertNull(result.score)
        assertEquals("视觉模型返回的 JSON 无法解析", result.reason)
    }

    @Test
    fun `score alone is not enough to pass without aspects`() {
        assertEquals(IqJudgeVerdict.REVIEW, assessor.parseAssessment("""{"score":75}""").verdict)
        assertEquals(IqJudgeVerdict.REVIEW, assessor.parseAssessment("""{"score":55}""").verdict)
        assertEquals(IqJudgeVerdict.REVIEW, assessor.parseAssessment("""{"score":20}""").verdict)
    }

    @Test
    fun `clamps out of range score`() {
        assertEquals(100.0, assessor.parseAssessment("""{"verdict":"passed","score":180}""").score)
    }

    @Test
    fun `unknown verdict without aspects asks for review`() {
        val result = assessor.parseAssessment("""{"verdict":"maybe","score":72}""")

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
    }

    @Test
    fun `parses per-aspect results and lets failures override explicit pass`() {
        val text = """
            {"aspects":{
              "鹈鹕特征":{"status":"passed","detail":"白色长颈鸟"},
              "自行车结构":{"status":"failed","detail":"缺少链条"},
              "骑行关系":{"status":"passed","detail":"坐在车座上"},
              "动画连贯性":{"status":"unknown","detail":"CSS 动画无法采样"},
              "画面完成度":{"status":"passed","detail":"主体完整"}
            },"verdict":"passed","score":80,"reason":"summary"}
        """.trimIndent()

        val result = assessor.parseAssessment(text)

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(5, result.aspects.size)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
        assertEquals("缺少链条", result.aspects[1].detail)
        assertEquals(VisionAspectStatus.UNKNOWN, result.aspects[3].status)
    }

    @Test
    fun `chinese failed status is not mistaken for passed`() {
        val text = """
            {"aspects":{
              "鹈鹕特征":{"status":"通过","detail":"白色长颈鸟"},
              "自行车结构":{"status":"未通过","detail":"缺少链条"},
              "骑行关系":{"status":"通过","detail":"坐在车座上"},
              "动画连贯性":{"status":"未通过","detail":"脚脱离踏板"},
              "画面完成度":{"status":"通过","detail":"主体完整"}
            },"verdict":"review","score":60,"reason":"有两项失败"}
        """.trimIndent()

        val result = assessor.parseAssessment(text)

        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[3].status)
        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
    }

    @Test
    fun `negated english pass status is treated as failed`() {
        val text = """
            {"aspects":{
              "鹈鹕特征":{"status":"passed","detail":"白色长颈鸟"},
              "自行车结构":{"status":"not passed","detail":"缺少链条"},
              "骑行关系":{"status":"passed","detail":"坐在车座上"},
              "动画连贯性":{"status":"not passed","detail":"脚脱离踏板"},
              "画面完成度":{"status":"passed","detail":"主体完整"}
            },"verdict":"passed","score":80,"reason":"summary"}
        """.trimIndent()

        val result = assessor.parseAssessment(text)

        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[3].status)
        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
    }

    @Test
    fun `unknown aspect asks for review when nothing failed`() {
        val text = """
            {"aspects":{
              "鹈鹕特征":{"status":"passed","detail":"白色长颈鸟"},
              "自行车结构":{"status":"passed","detail":"结构完整"},
              "骑行关系":{"status":"passed","detail":"坐在车座上"},
              "动画连贯性":{"status":"unknown","detail":"CSS 动画无法逐帧采样"},
              "画面完成度":{"status":"passed","detail":"主体完整"}
            },"verdict":"passed","score":85,"reason":"仅动画无法确认"}
        """.trimIndent()

        val result = assessor.parseAssessment(text)

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals(VisionAspectStatus.UNKNOWN, result.aspects[3].status)
    }

    @Test
    fun `missing aspects are marked unknown`() {
        val result = assessor.parseAssessment(
            """{"aspects":{"鹈鹕特征":{"status":"passed","detail":"白色长颈鸟"}},"verdict":"passed"}""",
        )

        assertEquals(5, result.aspects.size)
        assertEquals(1, result.aspects.count { it.status == VisionAspectStatus.PASSED })
        assertEquals(4, result.aspects.count { it.status == VisionAspectStatus.UNKNOWN })
        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
    }

    @Test
    fun `non json response asks for manual review`() {
        val result = assessor.parseAssessment("这张图看起来不错")

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertNull(result.score)
        assertEquals("视觉模型未返回 JSON 评分", result.reason)
    }

    @Test
    fun `complete top level aspect results do not trigger a repair`() = runBlocking {
        val complete = Json.parseToJsonElement(completeResponse()).jsonObject
        val topLevel = JsonObject((complete - "aspects") + complete.getValue("aspects").jsonObject)

        val (result, requests) = assessResponses(listOf(topLevel.toString()))

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(5, result.aspects.size)
        assertEquals(
            listOf("鹈鹕特征", "自行车结构", "骑行关系", "动画连贯性", "画面完成度"),
            result.aspects.map { it.key },
        )
        assertTrue(result.aspects.all { it.status == VisionAspectStatus.PASSED })
        assertTrue(result.aspects.all { it.detail == "逐帧观察到的该项证据" })
        assertEquals(95.0, result.score)
        assertEquals("逐项检测结果", result.reason)
        assertEquals(1, requests.size)
    }

    @Test
    fun `partial top level results trigger a repair`() = runBlocking {
        val complete = Json.parseToJsonElement(completeResponse()).jsonObject
        val partial = JsonObject(complete.getValue("aspects").jsonObject.filterKeys { it == "鹈鹕特征" })
        val partialResult = assessor.parseAssessment(partial.toString())

        assertEquals(IqJudgeVerdict.REVIEW, partialResult.verdict)
        assertEquals(4, partialResult.aspects.count { it.status == VisionAspectStatus.UNKNOWN })

        val (result, requests) = assessResponses(listOf(partial.toString(), complete.toString()))

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(2, requests.size)
    }

    @Test
    fun `nested passes cannot hide a top level failure`() {
        val complete = Json.parseToJsonElement(completeResponse()).jsonObject
        val failed = buildJsonObject {
            put("status", "failed")
            put("detail", "缺少链条")
        }
        val result = assessor.parseAssessment(JsonObject(complete + ("自行车结构" to failed)).toString())

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(VisionAspectStatus.FAILED, result.aspects[1].status)
        assertEquals("缺少链条", result.aspects[1].detail)
    }

    @Test
    fun `duplicate top level and nested passes require a repair`() = runBlocking {
        val complete = Json.parseToJsonElement(completeResponse()).jsonObject
        val duplicated = JsonObject(complete + complete.getValue("aspects").jsonObject)

        val (result, requests) = assessResponses(listOf(duplicated.toString(), complete.toString()))

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals(2, requests.size)
    }

    private fun completeResponse(verdict: String = "passed", unknownAspect: String? = null): String = buildJsonObject {
        put("verdict", verdict)
        put("score", 95)
        put("reason", "逐项检测结果")
        putJsonObject("aspects") {
            listOf("鹈鹕特征", "自行车结构", "骑行关系", "动画连贯性", "画面完成度").forEach { key ->
                putJsonObject(key) {
                    put("status", if (key == unknownAspect) "unknown" else "passed")
                    put("detail", "逐帧观察到的该项证据")
                }
            }
        }
    }.toString()

    private suspend fun assessResponses(responses: List<String>): Pair<VisionAssessment, List<JsonObject>> {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requests.add(Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject)
            val response = responses.getOrElse(requests.size - 1) { responses.last() }
            val body = buildJsonObject {
                putJsonArray("choices") {
                    addJsonObject {
                        putJsonObject("message") { put("content", response) }
                    }
                }
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return try {
            val httpAssessor = OpenAiVisionAssessor(
                client, "http://127.0.0.1:${server.address.port}/v1", model = "vision-test",
            )
            val result = httpAssessor.assess(
                VisionRequest(
                    frames = listOf(VisionFrame(0f, byteArrayOf(1, 2, 3))),
                    benchmark = "pelican",
                    structure = analyzePelicanStructure("<svg/>"),
                    animation = VisionAnimationInfo(2f, 1, false, false, true),
                ),
            )
            result to requests.toList()
        } finally {
            server.stop(0)
        }
    }
}
