package dev.soven.prismllm.proxy

import dev.soven.prismllm.config.GatewayConfig
import dev.soven.prismllm.spi.iq.IqJudgeResult
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 验证智商检测的 HTML 提取、结构检查与糖果题判分。 */
@Tag("unit")
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
    fun `standalone svg preview gives widthless svg a definite size`() {
        val html = extractHtmlDocument("<svg viewBox=\"0 0 800 520\"><circle r=\"2\" /></svg>")

        assertNotNull(html)
        assertTrue(html.contains("body>svg:not([width]){width:100%}"))
        assertTrue(html.contains("body>svg{display:block;max-width:100%;max-height:100vh;height:auto}"))
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
    fun `candy prompt only asks for json`() {
        val prompt = IqBenchmark.CANDY.prompt

        assertTrue(prompt.contains("""{"answer": <整数>, "reason": "<简要推导过程>"}"""), prompt)
        assertTrue(prompt.contains("不要输出 HTML"), prompt)
        assertFalse(prompt.contains("单文件 HTML"), prompt)
    }

    @Test
    fun `candy accepts expected json answer`() {
        val result = evaluateIqTest(
            IqBenchmark.CANDY,
            true,
            """{"answer":21,"reason":"先排除一种形状，再取完另一种形状的所有糖果，最后加一。"}""",
        )

        assertEquals(IqVerdict.PASSED, result.verdict)
        assertEquals(21, result.expectedAnswer)
        assertEquals(21, result.matchedAnswer)
        assertNull(result.html)
        assertEquals(2, result.checks.size)
        assertTrue(result.checks.all { it.passed })
        assertTrue(result.checks.last().detail?.contains("推理") == true)
    }

    @Test
    fun `candy rejects other integer as incorrect`() {
        val result = evaluateIqTest(IqBenchmark.CANDY, true, """{"answer":29,"reason":"组合推理得到 29。"}""")

        assertEquals(IqVerdict.INCORRECT, result.verdict)
        assertEquals(21, result.expectedAnswer)
        assertNull(result.matchedAnswer)
        val answerCheck = result.checks.first { it.id == "answer" }
        assertFalse(answerCheck.passed)
        assertTrue(answerCheck.detail?.contains("answer 为 29") == true, answerCheck.detail)
    }

    @Test
    fun `candy accepts json inside markdown fence and surrounding prose`() {
        val raw = """
            推导如下：
            ```json
            {"answer": 21, "reason": "取完所有干扰项后再取一颗。"}
            ```
            以上。
        """.trimIndent()

        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, raw).verdict)
    }

    @Test
    fun `candy accepts string and integral decimal answers`() {
        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, """{"answer":"21"}""").verdict)
        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, """{"answer":21.0}""").verdict)
    }

    @Test
    fun `candy handles braces and quotes inside reason`() {
        val raw = """{"answer":21,"reason":"用集合 {圆苹果, 星桃} 和 {星苹果, 圆桃} 表示目标，答案是 \"21\"。"}"""

        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, raw).verdict)
    }

    @Test
    fun `candy skips unbracketed prose braces before the json answer`() {
        val raw = """
            目标集合 {圆苹果, 星桃} 与 {星苹果, 圆桃} 至少命中一个。
            {"answer": 21, "reason": "取完所有干扰项后再取一颗。"}
        """.trimIndent()

        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, raw).verdict)
    }

    @Test
    fun `candy skips unbalanced prose braces before the json answer`() {
        val raw = """
            记集合为 {圆苹果, 星桃 这样的写法。
            {"answer": 21}
        """.trimIndent()

        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, raw).verdict)
    }

    @Test
    fun `candy prefers fenced json over earlier prose braces`() {
        val raw = """
            先把 {A} 和 {B} 并列考虑：
            ```json
            {"answer": 21, "reason": "干扰项"}
            ```
        """.trimIndent()

        assertEquals(IqVerdict.PASSED, evaluateIqTest(IqBenchmark.CANDY, true, raw).verdict)
    }

    @Test
    fun `candy fails when response is not json`() {
        for (raw in listOf("Hello!", "答案是 21 颗。", "<html><body>21</body></html>")) {
            val result = evaluateIqTest(IqBenchmark.CANDY, true, raw)

            assertEquals(IqVerdict.FAILED, result.verdict, raw)
            assertEquals(1, result.checks.size)
            assertFalse(result.checks.single().passed)
            assertNull(result.html)
        }
    }

    @Test
    fun `candy fails when answer is missing or not an integer`() {
        for (raw in listOf("""{"reason":"只有推理"}""", """{"answer":"二十一","reason":"中文数字"}""")) {
            val result = evaluateIqTest(IqBenchmark.CANDY, true, raw)

            assertEquals(IqVerdict.FAILED, result.verdict, raw)
            assertEquals(2, result.checks.size)
            assertTrue(result.checks.first().passed)
            assertFalse(result.checks.last().passed)
        }
    }

    @Test
    fun `candy ignores judge verdict and html`() {
        val result = evaluateIqTest(
            benchmark = IqBenchmark.CANDY,
            success = true,
            responseText = """{"answer":21,"reason":"21 颗。"}""",
            structure = analyzePelicanStructure("<html><body><svg></svg></body></html>"),
            judgeResult = IqJudgeResult(IqJudgeVerdict.INCORRECT, "未通过"),
        )

        assertEquals(IqVerdict.PASSED, result.verdict)
        assertNull(result.html)
    }

    @Test
    fun `legacy candy record keeps stored verdict and html`() {
        val result = evaluateLegacyCandy(
            responseText = "<html><body><p>答案是 21 颗。</p></body></html>",
            verdict = "passed",
            expectedAnswer = 21,
            matchedAnswer = 21,
        )

        assertEquals(IqVerdict.PASSED, result.verdict)
        assertEquals(21, result.expectedAnswer)
        assertEquals(21, result.matchedAnswer)
        assertNotNull(result.html)
        assertEquals("answer", result.checks.single().id)
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

    @Test
    fun `benchmark timeout falls back to its own default`() {
        assertEquals(180_000L, IqBenchmark.PELICAN.resolveTimeoutMs())
        assertEquals(120_000L, IqBenchmark.CANDY.resolveTimeoutMs())
    }

    @Test
    fun `request timeout overrides the default for one run`() {
        assertEquals(300_000L, IqBenchmark.PELICAN.resolveTimeoutMs(300_000))
        // 覆盖值只影响单次检测，题目默认值保持不变。
        assertEquals(180_000L, IqBenchmark.PELICAN.resolveTimeoutMs(null))
    }

    @Test
    fun `deployment default replaces the benchmark default until a request overrides it`() {
        // IQ_TEST_TIMEOUT_MS 显式配置后成为所有题目的默认值。
        assertEquals(240_000L, IqBenchmark.PELICAN.resolveTimeoutMs(configuredDefaultMs = 240_000))
        assertEquals(240_000L, IqBenchmark.CANDY.resolveTimeoutMs(configuredDefaultMs = 240_000))
        // 单次请求仍然优先。
        assertEquals(90_000L, IqBenchmark.PELICAN.resolveTimeoutMs(90_000, configuredDefaultMs = 240_000))
    }

    @Test
    fun `resolved timeout never exceeds the hard ceiling`() {
        assertEquals(
            GatewayConfig.IQ_TEST_TIMEOUT_MAX_MS,
            IqBenchmark.PELICAN.resolveTimeoutMs(GatewayConfig.IQ_TEST_TIMEOUT_MAX_MS + 1),
        )
    }
}
