package dev.soven.llmgateway.plugin.iq

import dev.soven.llmgateway.proxy.HtmlImageRenderer
import dev.soven.llmgateway.proxy.SvgRenderResult
import dev.soven.llmgateway.proxy.UnavailableBrowserRenderer
import dev.soven.llmgateway.proxy.VisionAspect
import dev.soven.llmgateway.proxy.VisionAspectStatus
import dev.soven.llmgateway.proxy.VisionAssessor
import dev.soven.llmgateway.proxy.VisionAssessment
import dev.soven.llmgateway.proxy.VisionRequest
import dev.soven.llmgateway.spi.iq.IqJudgeRequest
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import dev.soven.llmgateway.spi.iq.IqStructureCheck
import dev.soven.llmgateway.spi.iq.IqStructureSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DEFAULT_PELICAN_SVG =
    "<svg viewBox=\"0 0 10 10\"><animateTransform attributeName=\"transform\" type=\"rotate\" " +
        "from=\"0\" to=\"360\" dur=\"2s\" repeatCount=\"indefinite\"/></svg>"

private val CSS_ONLY_SVG = """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
      <style>@keyframes spin { to { transform: rotate(360deg); } } .a { animation: spin 1s linear infinite; }</style>
      <rect class="a" width="10" height="10"/>
    </svg>
""".trimIndent()

class VisionIqJudgeTest {
    private val structure = IqStructureSummary(
        checks = listOf(IqStructureCheck("html", "包含 HTML", true)),
        htmlPresent = true,
        svgPresent = true,
        animationPresent = true,
        selfContained = true,
    )

    private class RecordingRenderer(
        private val result: SvgRenderResult,
        override val label: String = "测试渲染器",
        private val distinctFrames: Boolean = true,
    ) : HtmlImageRenderer {
        var calls = 0
            private set

        override fun render(html: String): SvgRenderResult {
            calls += 1
            return result
        }

        override fun renderFrames(html: String, times: List<Float>): List<SvgRenderResult> = times.mapIndexed { index, _ ->
            when (val frame = result) {
                is SvgRenderResult.Success ->
                    if (distinctFrames) frame.copy(png = frame.png + byteArrayOf(index.toByte())) else frame
                is SvgRenderResult.Failure -> frame
            }
        }
    }

    private class RecordingAssessor(private val assessment: VisionAssessment) : VisionAssessor {
        var calls = 0
            private set
        var lastRequest: VisionRequest? = null

        override suspend fun assess(request: VisionRequest): VisionAssessment {
            calls += 1
            lastRequest = request
            return assessment
        }
    }

    private fun request(benchmark: String = "pelican", html: String = DEFAULT_PELICAN_SVG) = IqJudgeRequest(
        benchmark = benchmark,
        html = html,
        model = "test-model",
        structure = structure,
    )

    private fun allPassedAspects() = listOf(
        VisionAspect("鹈鹕特征", VisionAspectStatus.PASSED, "白色长颈鸟，喙和喉囊清楚。"),
        VisionAspect("自行车结构", VisionAspectStatus.PASSED, "双三角车架、双轮、车把和链条齐全。"),
        VisionAspect("骑行关系", VisionAspectStatus.PASSED, "坐在车座上，双脚在踏板上，翅膀握住车把。"),
        VisionAspect("动画连贯性", VisionAspectStatus.PASSED, "逐帧可见轮辐转动，脚未脱离踏板。"),
        VisionAspect("画面完成度", VisionAspectStatus.PASSED, "主体完整居中，无裁切或碰撞。"),
    )

    @Test
    fun `render failure asks for manual review`() {
        val assessor = RecordingAssessor(VisionAssessment(IqJudgeVerdict.PASSED))
        val judge = VisionIqJudge(RecordingRenderer(SvgRenderResult.Failure("没有内联 SVG")), assessor, "m")

        val result = judge.judge(request())

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals("无法生成截图", result.label)
        assertEquals("测试渲染器", result.details["渲染器"])
        assertEquals(0, assessor.calls)
    }

    @Test
    fun `passes per-aspect results and technical details through`() {
        val assessor = RecordingAssessor(
            VisionAssessment(
                verdict = IqJudgeVerdict.PASSED,
                score = 88.0,
                reason = "五项检测全部通过",
                aspects = allPassedAspects(),
            ),
        )
        val judge = VisionIqJudge(
            RecordingRenderer(
                SvgRenderResult.Success(ByteArray(2048), 512, 320),
                label = "无头浏览器",
            ),
            assessor,
            "vision-a",
        )

        val result = judge.judge(request())

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals("视觉评分通过", result.label)
        assertEquals(88.0, result.score)
        assertEquals("五项检测全部通过", result.reason)
        assertEquals("通过：白色长颈鸟，喙和喉囊清楚。", result.details["鹈鹕特征"])
        assertEquals("通过：双三角车架、双轮、车把和链条齐全。", result.details["自行车结构"])
        val technical = assertNotNull(result.details["技术信息"])
        assertTrue(technical.contains("无头浏览器"))
        assertTrue(technical.contains("6 帧"))
        assertTrue(technical.contains("512×320"))
        assertTrue(technical.contains("vision-a"))
        assertFalse(technical.contains("各帧相同"))
        assertEquals(6, assessor.lastRequest?.frames?.size)
        assertFalse(assessor.lastRequest?.animation?.framesIdentical ?: true)
    }

    @Test
    fun `missing browser asks for manual review`() {
        val assessor = RecordingAssessor(VisionAssessment(IqJudgeVerdict.PASSED, 90.0, "ok", allPassedAspects()))
        val judge = VisionIqJudge(UnavailableBrowserRenderer, assessor, "m")

        val result = judge.judge(request(html = CSS_ONLY_SVG))

        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals("需人工查看", result.label)
        assertTrue(result.reason?.contains("无头浏览器") == true, result.reason)
        assertEquals("无可用浏览器", result.details["渲染器"])
        assertEquals(0, assessor.calls)
    }

    @Test
    fun `css-only animation is judged from browser frames without unknown`() {
        val assessor = RecordingAssessor(
            VisionAssessment(IqJudgeVerdict.PASSED, 90.0, "逐帧动画连贯", allPassedAspects()),
        )
        val judge = VisionIqJudge(
            RecordingRenderer(
                SvgRenderResult.Success(ByteArray(16), 100, 100),
                label = "无头浏览器",
            ),
            assessor,
            "m",
        )

        val result = judge.judge(request(html = CSS_ONLY_SVG))

        assertEquals(IqJudgeVerdict.PASSED, result.verdict)
        assertEquals("通过：逐帧可见轮辐转动，脚未脱离踏板。", result.details["动画连贯性"])
        assertEquals(1, assessor.calls)
    }

    @Test
    fun `identical browser frames fail animation regardless of declaration`() {
        val judge = VisionIqJudge(
            RecordingRenderer(
                SvgRenderResult.Success(ByteArray(16), 100, 100),
                label = "无头浏览器",
                distinctFrames = false,
            ),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.PASSED, 90.0, "ok", allPassedAspects())),
            "m",
        )

        val result = judge.judge(request(html = CSS_ONLY_SVG))

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals("疑似降智", result.label)
        assertTrue(result.details["动画连贯性"]?.startsWith("未通过") == true, result.details["动画连贯性"])
        assertTrue(result.details["动画连贯性"]?.contains("各帧截图完全相同") == true)
    }

    @Test
    fun `identical frames fail even when model omits aspects`() {
        val judge = VisionIqJudge(
            RecordingRenderer(
                SvgRenderResult.Success(ByteArray(16), 100, 100),
                distinctFrames = false,
            ),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.PASSED, 95.0, "看起来不错")),
            "m",
        )

        val result = judge.judge(request())

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals(0.0, result.score)
        assertTrue(result.details["动画连贯性"]?.startsWith("未通过") == true, result.details["动画连贯性"])
    }

    @Test
    fun `animation unknown is converted to failed`() {
        val aspects = allPassedAspects().map {
            if (it.key == "动画连贯性") it.copy(status = VisionAspectStatus.UNKNOWN, detail = "看不清") else it
        }
        val judge = VisionIqJudge(
            RecordingRenderer(SvgRenderResult.Success(ByteArray(16), 100, 100)),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.REVIEW, 80.0, "动画无法确认", aspects)),
            "m",
        )

        val result = judge.judge(request())

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertTrue(result.details["动画连贯性"]?.startsWith("未通过") == true, result.details["动画连贯性"])
        assertTrue(result.details["动画连贯性"]?.contains("无法确认连续运动") == true)
    }

    @Test
    fun `missing animation and identical frames fail`() {
        val html = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>"""
        val judge = VisionIqJudge(
            RecordingRenderer(
                SvgRenderResult.Success(ByteArray(16), 100, 100),
                distinctFrames = false,
            ),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.PASSED, 90.0, "ok", allPassedAspects())),
            "m",
        )

        val result = judge.judge(request(html = html))

        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals("疑似降智", result.label)
        assertTrue(result.details["动画连贯性"]?.startsWith("未通过") == true, result.details["动画连贯性"])
    }

    @Test
    fun `incorrect and review verdicts get distinct labels`() {
        val incorrect = VisionIqJudge(
            RecordingRenderer(SvgRenderResult.Success(ByteArray(16), 100, 100)),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.INCORRECT, 12.0)),
            "m",
        ).judge(request())
        val review = VisionIqJudge(
            RecordingRenderer(SvgRenderResult.Success(ByteArray(16), 100, 100)),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.REVIEW)),
            "m",
        ).judge(request())

        assertEquals("疑似降智", incorrect.label)
        assertEquals(0.0, incorrect.score)
        assertEquals("需人工确认", review.label)
        assertFalse(incorrect.label == review.label)
    }

    @Test
    fun `exposes stable plugin identity`() {
        val judge = VisionIqJudge(
            RecordingRenderer(SvgRenderResult.Failure("x")),
            RecordingAssessor(VisionAssessment(IqJudgeVerdict.REVIEW)),
            "m",
        )

        assertEquals("vision", judge.id)
        assertTrue(judge.description.contains("鹈鹕特征"))
        assertTrue(judge.description.contains("无头浏览器"))
        assertTrue(judge.description.contains("不发送渠道密钥"))
        assertEquals(1, judge.apiVersion)
    }
}
