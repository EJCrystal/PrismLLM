package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqJudgeResult
import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 验证智商检测的 HTML 提取、结构检查与糖果题判分。 */
class IqTestTest {
    @Test
    fun `extracts html from markdown fence`() {
        val raw = """
            下面是作品：
            ```html
            <!DOCTYPE html><html><body><svg><circle r="2" /></svg></body></html>
            ```
        """.trimIndent()

        assertEquals(
            "<!DOCTYPE html><html><body><svg><circle r=\"2\" /></svg></body></html>",
            extractHtmlDocument(raw),
        )
    }

    @Test
    fun `ignores non html fences and finds html outside`() {
        val raw = """
            先说明一下：
            ```text
            not html
            ```
            <svg><circle r="2" /></svg>
        """.trimIndent()

        assertNotNull(extractHtmlDocument(raw))
    }

    @Test
    fun `wraps standalone svg for preview`() {
        val html = extractHtmlDocument("<svg><rect width=\"10\" height=\"10\" /></svg>")

        assertNotNull(html)
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("<svg>"))
    }

    @Test
    fun `pelican passes structural checks`() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>@keyframes wheel{to{transform:rotate(360deg)}}</style></head>
            <body><svg><circle class="wheel" /></svg></body></html>
        """.trimIndent()

        val result = evaluateIqTest(IqBenchmark.PELICAN, true, html)

        assertEquals(IqVerdict.PASSED, result.verdict)
        assertTrue(result.checks.all { it.passed })
        assertNotNull(result.html)
    }

    @Test
    fun `pelican with external image needs review`() {
        val html = """
            <!DOCTYPE html>
            <html><body><svg><image href="https://example.com/pelican.png" /></svg></body></html>
        """.trimIndent()

        val result = evaluateIqTest(IqBenchmark.PELICAN, true, html)

        assertEquals(IqVerdict.REVIEW, result.verdict)
        assertFalse(result.checks.first { it.id == "self-contained" }.passed)
    }

    @Test
    fun `pelican without svg fails`() {
        val result = evaluateIqTest(IqBenchmark.PELICAN, true, "一只鹈鹕骑着自行车。")

        assertEquals(IqVerdict.FAILED, result.verdict)
        assertNull(result.html)
    }

    @Test
    fun `candy accepts standalone answer`() {
        val result = evaluateIqTest(IqBenchmark.CANDY, true, "最少需要取出 **21** 颗糖果。")

        assertEquals(IqVerdict.PASSED, result.verdict)
        assertEquals(21, result.expectedAnswer)
        assertEquals(21, result.matchedAnswer)
    }

    @Test
    fun `candy accepts chinese answer`() {
        val result = evaluateIqTest(IqBenchmark.CANDY, true, "所以答案是二十一颗。")

        assertEquals(IqVerdict.PASSED, result.verdict)
    }

    @Test
    fun `candy rejects larger chinese numbers containing the answer substring`() {
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, "答案是一百二十一颗。").verdict)
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, "答案是二十一万颗。").verdict)
    }

    @Test
    fun `candy accepts standalone chinese answer with punctuation`() {
        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, "最终答案是廿一颗。").verdict)
    }

    @Test
    fun `candy ignores hidden answers`() {
        val hiddenAttribute = """
            <html><body><span hidden>21</span><p>答案是 20 颗。</p></body></html>
        """.trimIndent()
        val inlineStyle = """
            <html><body><span style="display: none">21</span><p>答案是 20 颗。</p></body></html>
        """.trimIndent()
        val styleClass = """
            <html><head><style>.ghost { visibility: hidden; }</style></head>
            <body><span class="ghost">21</span><p>答案是 20 颗。</p></body></html>
        """.trimIndent()

        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, hiddenAttribute).verdict)
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, inlineStyle).verdict)
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, styleClass).verdict)
    }

    @Test
    fun `candy keeps aria hidden but visible text`() {
        val html = """
            <html><body><p aria-hidden="true">答案是 21 颗。</p></body></html>
        """.trimIndent()

        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, html).verdict)
    }

    @Test
    fun `candy reads visible text from html and ignores css numbers`() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>.box{width:21px;color:#211111}</style></head>
            <body><p>最少需要取出 21 颗糖果。</p></body></html>
        """.trimIndent()

        val result = evaluateIqTest(IqBenchmark.CANDY, true, html)

        assertEquals(IqVerdict.PASSED, result.verdict)
    }

    @Test
    fun `candy does not treat css values as answers`() {
        val html = """
            <!DOCTYPE html>
            <html><head><style>.box{width:21px;color:#211111}</style></head>
            <body><p>答案是 20 颗。</p></body></html>
        """.trimIndent()

        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, html).verdict)
    }

    @Test
    fun `candy rejects wrong or embedded number`() {
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, "答案是 29 颗。").verdict)
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, "我取了 121 颗。").verdict)
        assertEquals(IqVerdict.INCORRECT, evaluateIqTest(IqBenchmark.CANDY, true, "再取出 210 颗。").verdict)
    }

    @Test
    fun `candy ignores judge verdict`() {
        val result = evaluateIqTest(
            benchmark = IqBenchmark.CANDY,
            success = true,
            responseText = "答案是 21 颗。",
            judgeResult = IqJudgeResult(IqJudgeVerdict.INCORRECT, "未通过"),
        )

        assertEquals(IqVerdict.PASSED, result.verdict)
    }

    @Test
    fun `pelican uses judge verdict`() {
        val html = """
            <!DOCTYPE html>
            <html><body><svg><circle class="wheel" /></svg></body></html>
        """.trimIndent()
        val structure = analyzePelicanStructure(html)

        assertEquals(
            IqVerdict.PASSED,
            evaluateIqTest(
                IqBenchmark.PELICAN, true, html,
                structure = structure,
                judgeResult = IqJudgeResult(IqJudgeVerdict.PASSED, "正常"),
            ).verdict,
        )
        assertEquals(
            IqVerdict.INCORRECT,
            evaluateIqTest(
                IqBenchmark.PELICAN, true, html,
                structure = structure,
                judgeResult = IqJudgeResult(IqJudgeVerdict.INCORRECT, "疑似降智"),
            ).verdict,
        )
        assertEquals(
            IqVerdict.REVIEW,
            evaluateIqTest(
                IqBenchmark.PELICAN, true, html,
                structure = structure,
                judgeResult = IqJudgeResult(IqJudgeVerdict.REVIEW, "无法判定"),
            ).verdict,
        )
        // 判分器自身失败时不能把作品判成失败，应交给人工确认。
        assertEquals(
            IqVerdict.REVIEW,
            evaluateIqTest(
                IqBenchmark.PELICAN, true, html,
                structure = structure,
                judgeResult = IqJudgeResult(IqJudgeVerdict.FAILED, "判分超时"),
            ).verdict,
        )
    }

    @Test
    fun `manual verdict overrides pelican judge`() {
        val html = """
            <!DOCTYPE html>
            <html><body><svg><circle class="wheel" /></svg></body></html>
        """.trimIndent()
        val result = evaluateIqTest(
            benchmark = IqBenchmark.PELICAN,
            success = true,
            responseText = html,
            structure = analyzePelicanStructure(html),
            judgeResult = IqJudgeResult(IqJudgeVerdict.REVIEW, "待人工"),
            manualVerdict = "passed",
        )

        assertEquals(IqVerdict.PASSED, result.verdict)
    }

    @Test
    fun `failed upstream request is marked failed`() {
        val result = evaluateIqTest(IqBenchmark.CANDY, false, null, "上游返回 HTTP 500")

        assertEquals(IqVerdict.FAILED, result.verdict)
        assertEquals("request", result.checks.single().id)
        assertEquals("上游返回 HTTP 500", result.checks.single().detail)
    }
}
