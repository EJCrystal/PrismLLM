package dev.soven.prismllm.plugin.iq

import dev.soven.prismllm.proxy.analyzePelicanStructure
import dev.soven.prismllm.spi.iq.IqJudgeRequest
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("unit")
class LocalStructureIqJudgeTest {
    private val judge = LocalStructureIqJudge()

    @Test
    fun `passes structure checks to manual review`() {
        val html = """
            <html><body>
            <svg viewBox="0 0 10 10"><circle cx="5" cy="5" r="4"/></svg>
            <style>.p { animation: ride 2s infinite; } @keyframes ride { to { transform: translateX(1px); } }</style>
            </body></html>
        """.trimIndent()
        val result = judge.judge(request("pelican", html))
        assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
        assertEquals("结构通过，待人工评分", result.label)
    }

    @Test
    fun `fails when svg is missing`() {
        val html = "<html><body><p>no drawing</p></body></html>"
        val result = judge.judge(request("pelican", html))
        assertEquals(IqJudgeVerdict.FAILED, result.verdict)
        assertEquals("未通过", result.label)
    }

    @Test
    fun `marks incomplete structure as incorrect`() {
        val html = "<html><body><svg viewBox=\"0 0 10 10\"></svg></body></html>"
        val result = judge.judge(request("pelican", html))
        assertEquals(IqJudgeVerdict.INCORRECT, result.verdict)
        assertEquals("结构未通过", result.label)
    }

    private fun request(benchmark: String, html: String) = IqJudgeRequest(
        benchmark = benchmark,
        html = html,
        model = "test",
        structure = analyzePelicanStructure(html),
    )
}
