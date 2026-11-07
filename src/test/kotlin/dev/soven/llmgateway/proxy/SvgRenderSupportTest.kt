package dev.soven.llmgateway.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgRenderSupportTest {
    @Test
    fun `extracts first inline svg from fenced output`() {
        val html = "说明\n```html\n<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>\n```"
        assertTrue(extractInlineSvg(html)?.startsWith("<svg") == true)
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
}
