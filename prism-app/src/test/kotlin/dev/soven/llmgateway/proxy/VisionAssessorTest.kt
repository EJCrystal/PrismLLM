package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import io.ktor.client.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VisionAssessorTest {
    private val client = HttpClient()
    private val assessor = OpenAiVisionAssessor(client, "http://localhost:1", model = "vision-test")

    @AfterTest
    fun tearDown() {
        client.close()
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
    fun `parses json wrapped in markdown fence`() {
        val result = assessor.parseAssessment(
            "```json\n{\"verdict\":\"incorrect\",\"score\":10,\"reason\":\"看不到鹈鹕\"}\n```",
        )

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(10.0, result.score)
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
}
