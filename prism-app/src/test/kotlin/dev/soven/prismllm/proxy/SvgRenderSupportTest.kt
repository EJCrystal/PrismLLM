package dev.soven.prismllm.proxy

import org.junit.jupiter.api.Tag
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("unit")
class SvgRenderSupportTest {
    @Test
    fun `extracts first inline svg from fenced output`() {
        val html = "说明\n```html\n<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>\n```"
        assertTrue(extractInlineSvg(html)?.startsWith("<svg") == true)
    }

    @Test
    fun `extracts outer svg when inner svg is nested`() {
        val html = """
            <html><body>
            <svg viewBox="0 0 10 10"><svg viewBox="0 0 2 2"><rect width="2" height="2"/></svg>
            <g id="bike">自行车主体</g></svg>
            </body></html>
        """.trimIndent()

        val svg = extractInlineSvg(html)

        assertTrue(svg?.contains("id=\"bike\"") == true, svg)
        assertTrue(svg?.endsWith("</svg>") == true, svg)
    }

    @Test
    fun `analyzes smil duration and css animation`() {
        val smil = analyzeSvgAnimation(
            "<svg><animateTransform attributeName=\"transform\" dur=\"3s\" repeatCount=\"indefinite\"/></svg>",
        )
        assertEquals(3f, smil.durationSeconds)
        assertTrue(smil.hasSmil)
        assertFalse(smil.hasCss)

        val css = analyzeSvgAnimation(
            "<style>@keyframes spin { to { transform: rotate(360deg); } } .wheel { animation: spin 800ms linear infinite; }</style>",
        )
        assertEquals(0.8f, css.durationSeconds)
        assertTrue(css.hasCss)
        assertFalse(css.hasSmil)
    }

    @Test
    fun `strips scripts external references and event handlers`() {
        val html = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 80" onload="alert(1)">
              <script>alert('x')</script>
              <image href="https://example.com/pelican.png" width="10" height="10"/>
              <image href="file:///etc/passwd" width="10" height="10"/>
              <rect width="120" height="80" fill="#7dd3fc" onclick="alert(1)"/>
            </svg>
        """.trimIndent()

        val cleaned = SvgRenderSupport.sanitizeSvg(html)

        assertFalse(cleaned.contains("<script", ignoreCase = true))
        assertFalse(cleaned.contains("example.com"))
        assertFalse(cleaned.contains("file://"))
        assertFalse(cleaned.contains("onload", ignoreCase = true))
        assertFalse(cleaned.contains("onclick", ignoreCase = true))
        assertTrue(cleaned.contains("<rect"))
    }

    @Test
    fun `does not synthesize blocked tags from removed attributes`() {
        val payload = """
            <svg xmlns="http://www.w3.org/2000/svg"><foreignObject>
            <me href="http://x"ta http-equiv="refresh" content="0;url=http://127.0.0.1:8080/private">
            </foreignObject></svg>
        """.trimIndent()

        val cleaned = SvgRenderSupport.sanitizeSvg(payload)

        assertFalse(cleaned.contains(Regex("""<meta[\s/>]""", RegexOption.IGNORE_CASE)), cleaned)
        assertFalse(cleaned.contains("http-equiv", ignoreCase = true))
    }

    @Test
    fun `sanitizes malformed tags that browsers still parse`() {
        val payload = """
            <svg xmlns="http://www.w3.org/2000/svg"><foreignObject>
            <meta http-equiv="refresh" content="0;url=http://127.0.0.1:8080/private" data-x=x">
            </foreignObject><rect width="10" height="10"/></svg>
        """.trimIndent()

        val cleaned = SvgRenderSupport.sanitizeSvg(payload)

        assertFalse(cleaned.contains("<meta", ignoreCase = true), cleaned)
        assertFalse(cleaned.contains("http-equiv", ignoreCase = true), cleaned)
        assertTrue(cleaned.contains("<rect"), cleaned)
    }

    @Test
    fun `sanitizes style blocks inside svg`() {
        val payload = """
            <svg xmlns="http://www.w3.org/2000/svg"><style>
            </sty</stylele><meta http-equiv="refresh" content="0;url=http://127.0.0.1:8080/private">
            </style><rect width="10" height="10"/></svg>
        """.trimIndent()

        val cleaned = SvgRenderSupport.sanitizeSvg(payload)

        assertFalse(cleaned.contains("<meta", ignoreCase = true), cleaned)
        assertFalse(cleaned.contains("http-equiv", ignoreCase = true), cleaned)
        // 只剩原本那个 style 的闭合标签，清洗不能拼出第二个 </style>。
        assertEquals(1, Regex("</style", RegexOption.IGNORE_CASE).findAll(cleaned).count(), cleaned)
        assertTrue(cleaned.contains("<rect"), cleaned)
    }

    @Test
    fun `keeps fragment and image data references`() {
        val cleaned = SvgRenderSupport.sanitizeSvg(
            """<svg xmlns="http://www.w3.org/2000/svg"><use href="#wheel"/>""" +
                """<image href="data:image/png;base64,AA=="/></svg>""",
        )

        assertTrue(cleaned.contains("href=\"#wheel\""))
        assertTrue(cleaned.contains("data:image/png"))
    }

    @Test
    fun `css cannot close the style element`() {
        val css = "</sty</stylele><meta http-equiv=\"refresh\" content=\"0;url=http://127.0.0.1:8080/private\">"

        val cleaned = SvgRenderSupport.sanitizeCss(css)

        assertFalse(cleaned.contains("</style", ignoreCase = true), cleaned)
        assertFalse(cleaned.contains("<meta", ignoreCase = true), cleaned)
    }

    @Test
    fun `strips css imports and external urls`() {
        val css = """
            @import url("file:///etc/passwd");
            .pelican { fill: url(https://evil.example/x.svg#g); }
            .wheel { animation: spin 1s linear infinite; }
        """.trimIndent()

        val cleaned = SvgRenderSupport.sanitizeCss(css)

        assertFalse(cleaned.contains("@import"))
        assertFalse(cleaned.contains("evil.example"))
        assertTrue(cleaned.contains("animation: spin 1s linear infinite"))
    }

    @Test
    fun `computes size from viewbox and caps width`() {
        assertEquals(512 to 256, SvgRenderSupport.svgSize("<svg viewBox=\"0 0 200 100\">", 512))
    }

    @Test
    fun `sampling avoids alignment with shorter animation periods`() {
        val info = analyzeSvgAnimation(
            "<svg><animateTransform attributeName=\"transform\" dur=\"1s\"/>" +
                "<animate attributeName=\"opacity\" dur=\"6s\"/></svg>",
        )

        assertEquals(6f, info.durationSeconds)
        val times = samplingTimes(info, 6)
        assertEquals(0f, times.first())
        val phases = times.map { ((it % 1f) * 1000).roundToInt() }.toSet()
        assertTrue(phases.size >= 3, "短周期动画的采样相位应错开，实际=$phases")
    }
}
